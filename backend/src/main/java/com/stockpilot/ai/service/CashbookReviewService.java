package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.CustomerPayment;
import com.stockpilot.ai.domain.Customer;
import com.stockpilot.ai.domain.Supplier;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.ImportSession;
import com.stockpilot.ai.domain.PurchaseInvoice;
import com.stockpilot.ai.domain.SalesInvoice;
import com.stockpilot.ai.domain.SmartStagedCashbookEntry;
import com.stockpilot.ai.domain.SupplierPayment;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class CashbookReviewService {
    private static final String POST_CONFIRMATION = "POST PAYMENT";

    private final Repositories.ImportSessionRepository sessions;
    private final Repositories.SmartStagedCashbookEntryRepository cashbook;
    private final Repositories.CustomerRepository customers;
    private final Repositories.SupplierRepository suppliers;
    private final Repositories.SalesInvoiceRepository salesInvoices;
    private final Repositories.PurchaseInvoiceRepository purchaseInvoices;
    private final Repositories.CustomerPaymentRepository customerPayments;
    private final Repositories.SupplierPaymentRepository supplierPayments;
    private final Repositories.SmartImportCommitRepository commits;
    private final Repositories.ImportPlanIssueRepository issues;
    private final AuditService audit;
    private final RetailCounterSaleService retailSales;
    private final SmartImportCashbookMatchingService matching;

    public CashbookReviewService(
        Repositories.ImportSessionRepository sessions,
        Repositories.SmartStagedCashbookEntryRepository cashbook,
        Repositories.CustomerRepository customers,
        Repositories.SupplierRepository suppliers,
        Repositories.SalesInvoiceRepository salesInvoices,
        Repositories.PurchaseInvoiceRepository purchaseInvoices,
        Repositories.CustomerPaymentRepository customerPayments,
        Repositories.SupplierPaymentRepository supplierPayments,
        Repositories.SmartImportCommitRepository commits,
        Repositories.ImportPlanIssueRepository issues,
        AuditService audit,
        RetailCounterSaleService retailSales,
        SmartImportCashbookMatchingService matching
    ) {
        this.sessions = sessions;
        this.cashbook = cashbook;
        this.customers = customers;
        this.suppliers = suppliers;
        this.salesInvoices = salesInvoices;
        this.purchaseInvoices = purchaseInvoices;
        this.customerPayments = customerPayments;
        this.supplierPayments = supplierPayments;
        this.commits = commits;
        this.issues = issues;
        this.audit = audit;
        this.retailSales = retailSales;
        this.matching = matching;
    }

    @Transactional
    public Map<String, Object> recheck(UUID sessionId) {
        retailSales.lockCashbook(TenantContext.tenantId());
        var session = session(sessionId);
        var commit = commits.findByTenantIdAndImportSessionIdForUpdate(session.tenantId, session.id).orElse(null);
        if (session.status == DomainEnums.ImportSessionStatus.CANCELLED
            || commit != null && commit.status == DomainEnums.SmartImportCommitStatus.COMMITTING) {
            throw ApiErrors.conflict("Cashbook cannot be rechecked while cancelled or committing");
        }
        matching.refresh(session.tenantId, session.id);
        var rows = cashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(session.tenantId, session.id);
        if (commit != null && commit.status == DomainEnums.SmartImportCommitStatus.COMMITTED) {
            for (var row : rows) {
                if (SmartImportCashbookMatchingService.ready(row) && row.customerPaymentId == null && row.supplierPaymentId == null) {
                    row.cashbookMatchStatus = DomainEnums.CashbookMatchStatus.LOW_CONFIDENCE_REVIEW;
                    row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
                    row.matchReason = "This entry was not posted by the completed import. Confirm its party or invoice before posting.";
                }
            }
            cashbook.saveAllAndFlush(rows);
        }
        matching.refreshReviewIssues(session.tenantId, session.id);
        var unmatched = rows.stream().filter(r -> r.resolutionStatus == DomainEnums.CashbookResolutionStatus.UNRESOLVED
            && r.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.UNMATCHED_REVIEW).count();
        var review = rows.stream().filter(r -> r.resolutionStatus == DomainEnums.CashbookResolutionStatus.UNRESOLVED
            && r.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.LOW_CONFIDENCE_REVIEW).count();
        if (commit != null && commit.status == DomainEnums.SmartImportCommitStatus.COMMITTED) {
            var summary = new LinkedHashMap<>(commit.summaryJson);
            summary.put("cashbookRowsUnmatched", unmatched);
            summary.put("cashbookRowsReviewRequired", review);
            summary.put("futurePhaseCashbookRowsSkipped", unmatched + review);
            summary.put("warnings", unmatched + review);
            summary.put("cashbookMessage", (unmatched + review) + " unposted entries require review. Already-posted payments were not changed.");
            summary.put("cashbookRecheckedAt", Instant.now().toString());
            commit.summaryJson = summary;
            commits.save(commit);
        }
        audit.logCurrent("CASHBOOK_UNPOSTED_RECHECKED", "ImportSession", session.id,
            Map.of("unpostedReviewCount", unmatched + review, "businessRecordsChanged", 0));
        return Map.of("unpostedReviewCount", unmatched + review, "businessRecordsChanged", 0);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> unresolved(UUID sessionId) {
        var session = session(sessionId);
        var rows = cashbook.findByTenantIdAndImportSessionIdAndResolutionStatusOrderByEntryDateAscSourceRowNumberAsc(
                session.tenantId, session.id, DomainEnums.CashbookResolutionStatus.UNRESOLVED).stream()
            .filter(this::needsReview).toList();
        if (rows.isEmpty()) return List.of();
        var page = PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "invoiceDate"));
        var catalog = new CandidateCatalog(customers.findByTenantIdOrderByNameAsc(session.tenantId),
            suppliers.findByTenantIdOrderByNameAsc(session.tenantId),
            salesInvoices.findByTenantId(session.tenantId, page).getContent(),
            purchaseInvoices.findByTenantId(session.tenantId, page).getContent());
        return rows.stream().map(row -> reviewRow(catalog, row)).toList();
    }

    @Transactional
    public Map<String, Object> resolve(
        UUID sessionId, UUID entryId, SmartImportDtos.CashbookReviewResolutionRequest request
    ) {
        retailSales.lockCashbook(TenantContext.tenantId());
        var session = session(sessionId);
        if (session.status == DomainEnums.ImportSessionStatus.CANCELLED) {
            throw ApiErrors.conflict("Cancelled import sessions cannot resolve cashbook entries");
        }
        var row = cashbook.findByTenantIdAndImportSessionIdAndIdForUpdate(session.tenantId, session.id, entryId)
            .orElseThrow(() -> ApiErrors.notFound("Cashbook review entry not found"));
        if (row.resolutionStatus == DomainEnums.CashbookResolutionStatus.RETAIL_SALE_RECORDED
            && request.action() == DomainEnums.CashbookResolutionAction.RECORD_RETAIL_SALE) {
            var outcome = retailSales.record(row, request);
            return retailResponse(session, row, outcome.duplicate());
        }
        if (row.resolutionStatus != DomainEnums.CashbookResolutionStatus.UNRESOLVED) {
            throw ApiErrors.conflict("This cashbook entry has already been resolved");
        }
        var repeatsPayment = (row.customerPaymentId != null || row.supplierPaymentId != null)
            && (request.action() == DomainEnums.CashbookResolutionAction.MAP_CUSTOMER
                || request.action() == DomainEnums.CashbookResolutionAction.MAP_SUPPLIER
                || request.action() == DomainEnums.CashbookResolutionAction.MAP_SALES_INVOICE
                || request.action() == DomainEnums.CashbookResolutionAction.MAP_PURCHASE_INVOICE);
        if (!needsReview(row) && !repeatsPayment) {
            throw ApiErrors.conflict("This cashbook entry does not require manual resolution");
        }

        if (request.action() == DomainEnums.CashbookResolutionAction.RECORD_RETAIL_SALE) {
            var outcome = retailSales.record(row, request);
            cashbook.save(row);
            resolvePlanIssue(session, row, request.action());
            audit.logCurrent("CASHBOOK_REVIEW_RESOLVED", "SmartStagedCashbookEntry", row.id,
                Map.of("action", request.action(), "retailCounterSaleId", outcome.saleId(), "duplicateSkipped", outcome.duplicate()));
            commits.findByTenantIdAndImportSessionIdForUpdate(session.tenantId, session.id).ifPresent(commit -> {
                var summary = new LinkedHashMap<String, Object>(commit.summaryJson == null ? Map.of() : commit.summaryJson);
                decrement(summary, row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.UNMATCHED_REVIEW
                    ? "cashbookRowsUnmatched" : "cashbookRowsReviewRequired");
                decrement(summary, "futurePhaseCashbookRowsSkipped");
                summary.put("warnings", number(summary.get("futurePhaseCashbookRowsSkipped")));
                increment(summary, "retailCounterSaleRowsResolved");
                commit.summaryJson = summary;
                commits.save(commit);
            });
            return retailResponse(session, row, outcome.duplicate());
        }

        if (retailSales.recorded(session.tenantId, row.fingerprint).isPresent()) {
            throw ApiErrors.conflict("This source entry is already recorded as a retail counter sale");
        }

        if (request.action() == DomainEnums.CashbookResolutionAction.KEEP_UNRESOLVED) {
            row.manualResolutionAction = request.action();
            row.resolutionNote = clean(request.note());
            cashbook.save(row);
            return response(session.tenantId, row, false, false);
        }
        if (request.action() == DomainEnums.CashbookResolutionAction.IGNORE) {
            ignore(session, row, request.note());
            updateReconciliation(session.tenantId, session.id, row.cashbookMatchStatus, false, false, false, true, false);
            return response(session.tenantId, row, false, false);
        }
        if (!POST_CONFIRMATION.equals(request.confirmation())) {
            throw ApiErrors.badRequest("Type POST PAYMENT to confirm this payment");
        }
        if (row.entryDate == null || row.amount == null || row.amount.signum() <= 0) {
            throw ApiErrors.badRequest("A payment date and positive amount are required before posting");
        }
        if (request.targetId() == null) {
            throw ApiErrors.badRequest("Select a customer, supplier, sales invoice, or purchase invoice");
        }

        var originalStatus = row.cashbookMatchStatus;
        var outcome = switch (request.action()) {
            case MAP_CUSTOMER -> postCustomer(session, row, request.targetId(), null, request.note());
            case MAP_SUPPLIER -> postSupplier(session, row, request.targetId(), null, request.note());
            case MAP_SALES_INVOICE -> {
                var invoice = salesInvoices.findByTenantIdAndId(session.tenantId, request.targetId())
                    .orElseThrow(() -> ApiErrors.notFound("Sales invoice not found"));
                yield postCustomer(session, row, invoice.customerId, invoice, request.note());
            }
            case MAP_PURCHASE_INVOICE -> {
                var invoice = purchaseInvoices.findByTenantIdAndId(session.tenantId, request.targetId())
                    .orElseThrow(() -> ApiErrors.notFound("Purchase invoice not found"));
                yield postSupplier(session, row, invoice.supplierId, invoice, request.note());
            }
            default -> throw ApiErrors.badRequest("Unsupported cashbook resolution action");
        };

        row.manualResolutionAction = request.action();
        row.resolutionStatus = DomainEnums.CashbookResolutionStatus.PAYMENT_POSTED;
        row.reviewStatus = DomainEnums.SmartReviewStatus.RESOLVED;
        row.matchStatus = DomainEnums.SmartMatchStatus.MATCH_EXISTING;
        row.matchConfidence = BigDecimal.ONE;
        row.matchReason = "Manually confirmed by an authorized user.";
        row.resolutionNote = clean(request.note());
        row.resolvedBy = TenantContext.userId();
        row.resolvedAt = Instant.now();
        row.updatedBy = TenantContext.userId();
        cashbook.save(row);
        resolvePlanIssue(session, row, request.action());

        var details = linked(
            "action", request.action(), "targetId", request.targetId(), "customerPaymentId", row.customerPaymentId,
            "supplierPaymentId", row.supplierPaymentId, "duplicatePaymentSkipped", outcome.duplicate,
            "sourceRowNumber", row.sourceRowNumber
        );
        audit.logCurrent("CASHBOOK_REVIEW_RESOLVED", "SmartStagedCashbookEntry", row.id, details);
        if (!outcome.duplicate) {
            audit.logCurrent("PAYMENT_MANUALLY_MATCHED", "SmartStagedCashbookEntry", row.id, details);
        }
        updateReconciliation(session.tenantId, session.id, originalStatus, outcome.customer, outcome.supplier,
            outcome.invoiceLinked, false, outcome.duplicate);
        return response(session.tenantId, row, outcome.duplicate, true);
    }

    private PaymentOutcome postCustomer(
        ImportSession session, SmartStagedCashbookEntry row, UUID customerId, SalesInvoice invoice, String note
    ) {
        var customer = customers.findByTenantIdAndId(session.tenantId, customerId)
            .orElseThrow(() -> ApiErrors.notFound("Customer not found"));
        if (invoice != null && !customer.id.equals(invoice.customerId)) {
            throw ApiErrors.badRequest("The selected sales invoice does not belong to the selected customer");
        }
        var fingerprint = fingerprint(session.tenantId, row);
        if (supplierPayments.findByTenantIdAndSourceFingerprint(session.tenantId, fingerprint).isPresent()) {
            throw ApiErrors.conflict("This cashbook row was already posted as a supplier payment");
        }
        var existing = customerPayments.findByTenantIdAndSourceFingerprint(session.tenantId, fingerprint).orElse(null);
        if (existing != null) {
            if (!customer.id.equals(existing.customerId)) {
                throw ApiErrors.conflict("This cashbook row was already posted to a different customer");
            }
            row.customerPaymentId = existing.id;
            row.matchedPartyId = existing.customerId;
            row.matchedInvoiceId = existing.salesInvoiceId;
            row.matchedPartyType = DomainEnums.SmartPartyType.CUSTOMER;
            row.cashbookMatchStatus = existing.salesInvoiceId == null
                ? DomainEnums.CashbookMatchStatus.MATCHED_CUSTOMER_PAYMENT
                : DomainEnums.CashbookMatchStatus.MATCHED_SALES_INVOICE;
            return new PaymentOutcome(true, true, false, existing.salesInvoiceId != null);
        }

        var payment = new CustomerPayment();
        own(payment, session.tenantId);
        payment.customerId = customer.id;
        payment.salesInvoiceId = invoice == null ? null : invoice.id;
        fillPayment(payment, row, fingerprint, "Manually matched Smart Import receipt", note);
        payment = customerPayments.save(payment);
        row.customerPaymentId = payment.id;
        row.matchedPartyId = customer.id;
        row.matchedInvoiceId = payment.salesInvoiceId;
        row.matchedPartyType = DomainEnums.SmartPartyType.CUSTOMER;
        row.cashbookMatchStatus = invoice == null
            ? DomainEnums.CashbookMatchStatus.MATCHED_CUSTOMER_PAYMENT
            : DomainEnums.CashbookMatchStatus.MATCHED_SALES_INVOICE;
        return new PaymentOutcome(false, true, false, invoice != null);
    }

    private Map<String, Object> retailResponse(ImportSession session, SmartStagedCashbookEntry row, boolean duplicate) {
        var result = response(session.tenantId, row, false, false);
        result.put("retailCounterSaleId", row.retailCounterSaleId);
        result.put("retailSaleRecorded", true);
        result.put("duplicateRetailSaleSkipped", duplicate);
        return result;
    }

    private PaymentOutcome postSupplier(
        ImportSession session, SmartStagedCashbookEntry row, UUID supplierId, PurchaseInvoice invoice, String note
    ) {
        var supplier = suppliers.findByTenantIdAndId(session.tenantId, supplierId)
            .orElseThrow(() -> ApiErrors.notFound("Supplier not found"));
        if (invoice != null && !supplier.id.equals(invoice.supplierId)) {
            throw ApiErrors.badRequest("The selected purchase invoice does not belong to the selected supplier");
        }
        var fingerprint = fingerprint(session.tenantId, row);
        if (customerPayments.findByTenantIdAndSourceFingerprint(session.tenantId, fingerprint).isPresent()) {
            throw ApiErrors.conflict("This cashbook row was already posted as a customer payment");
        }
        var existing = supplierPayments.findByTenantIdAndSourceFingerprint(session.tenantId, fingerprint).orElse(null);
        if (existing != null) {
            if (!supplier.id.equals(existing.supplierId)) {
                throw ApiErrors.conflict("This cashbook row was already posted to a different supplier");
            }
            row.supplierPaymentId = existing.id;
            row.matchedPartyId = existing.supplierId;
            row.matchedInvoiceId = existing.purchaseInvoiceId;
            row.matchedPartyType = DomainEnums.SmartPartyType.SUPPLIER;
            row.cashbookMatchStatus = existing.purchaseInvoiceId == null
                ? DomainEnums.CashbookMatchStatus.MATCHED_SUPPLIER_PAYMENT
                : DomainEnums.CashbookMatchStatus.MATCHED_PURCHASE_INVOICE;
            return new PaymentOutcome(true, false, true, existing.purchaseInvoiceId != null);
        }

        var payment = new SupplierPayment();
        own(payment, session.tenantId);
        payment.supplierId = supplier.id;
        payment.purchaseInvoiceId = invoice == null ? null : invoice.id;
        fillPayment(payment, row, fingerprint, "Manually matched Smart Import payment", note);
        payment = supplierPayments.save(payment);
        row.supplierPaymentId = payment.id;
        row.matchedPartyId = supplier.id;
        row.matchedInvoiceId = payment.purchaseInvoiceId;
        row.matchedPartyType = DomainEnums.SmartPartyType.SUPPLIER;
        row.cashbookMatchStatus = invoice == null
            ? DomainEnums.CashbookMatchStatus.MATCHED_SUPPLIER_PAYMENT
            : DomainEnums.CashbookMatchStatus.MATCHED_PURCHASE_INVOICE;
        return new PaymentOutcome(false, false, true, invoice != null);
    }

    private void ignore(ImportSession session, SmartStagedCashbookEntry row, String note) {
        row.manualResolutionAction = DomainEnums.CashbookResolutionAction.IGNORE;
        row.resolutionStatus = DomainEnums.CashbookResolutionStatus.IGNORED;
        row.reviewStatus = DomainEnums.SmartReviewStatus.IGNORED;
        row.resolutionNote = clean(note);
        row.resolvedBy = TenantContext.userId();
        row.resolvedAt = Instant.now();
        row.updatedBy = TenantContext.userId();
        cashbook.save(row);
        resolvePlanIssue(session, row, DomainEnums.CashbookResolutionAction.IGNORE);
        var details = linked("sourceRowNumber", row.sourceRowNumber, "note", row.resolutionNote);
        audit.logCurrent("CASHBOOK_REVIEW_RESOLVED", "SmartStagedCashbookEntry", row.id, details);
        audit.logCurrent("CASHBOOK_ENTRY_IGNORED", "SmartStagedCashbookEntry", row.id, details);
    }

    private Map<String, Object> reviewRow(CandidateCatalog catalog, SmartStagedCashbookEntry row) {
        var response = baseRow(row);
        response.put("candidates", candidates(catalog, row));
        return response;
    }

    private Map<String, Object> response(UUID tenantId, SmartStagedCashbookEntry row, boolean duplicate, boolean posted) {
        var response = baseRow(row);
        response.put("duplicatePaymentSkipped", duplicate);
        response.put("paymentPosted", posted && !duplicate);
        response.put("remainingUnresolved", cashbook
            .findByTenantIdAndImportSessionIdAndResolutionStatusOrderByEntryDateAscSourceRowNumberAsc(
                tenantId, row.importSessionId, DomainEnums.CashbookResolutionStatus.UNRESOLVED).stream()
            .filter(this::needsReview).count());
        return response;
    }

    private LinkedHashMap<String, Object> baseRow(SmartStagedCashbookEntry row) {
        return linked(
            "id", row.id, "sessionFileId", row.sessionFileId, "sourceRowNumber", row.sourceRowNumber,
            "entryDate", row.entryDate, "partyName", text(row.partyName), "gstin", text(row.gstin),
            "amount", row.amount, "direction", row.direction, "paymentMode", row.paymentMode,
            "referenceNumber", text(row.referenceNumber), "cashbookMatchStatus", row.cashbookMatchStatus,
            "resolutionStatus", row.resolutionStatus, "matchConfidence", row.matchConfidence,
            "matchReason", text(row.matchReason), "customerPaymentId", row.customerPaymentId,
            "sourceLedgerDetails", row.rawMetadataJson == null ? "" : text(Objects.toString(row.rawMetadataJson.get("Ledger Adjustment Details"), "")),
            "supplierPaymentId", row.supplierPaymentId, "resolvedAt", row.resolvedAt,
            "resolutionNote", text(row.resolutionNote)
        );
    }

    private record CandidateCatalog(List<Customer> customers, List<Supplier> suppliers,
                                    List<SalesInvoice> sales, List<PurchaseInvoice> purchases) { }

    private List<Map<String, Object>> candidates(CandidateCatalog catalog, SmartStagedCashbookEntry row) {
        var candidates = new ArrayList<Map<String, Object>>();
        catalog.customers.stream()
            .map(customer -> partyCandidate("CUSTOMER", DomainEnums.CashbookResolutionAction.MAP_CUSTOMER,
                customer.id, customer.name, customer.gstin, row))
            .sorted(candidateOrder()).limit(15).forEach(candidates::add);
        catalog.suppliers.stream()
            .map(supplier -> partyCandidate("SUPPLIER", DomainEnums.CashbookResolutionAction.MAP_SUPPLIER,
                supplier.id, supplier.name, supplier.gstin, row))
            .sorted(candidateOrder()).limit(15).forEach(candidates::add);

        var customerNames = catalog.customers.stream()
            .collect(java.util.stream.Collectors.toMap(customer -> customer.id, customer -> customer.name));
        catalog.sales.stream()
            .map(invoice -> invoiceCandidate("SALES_INVOICE", DomainEnums.CashbookResolutionAction.MAP_SALES_INVOICE,
                invoice.id, invoice.invoiceNumber, invoice.invoiceDate, invoice.totalAmount,
                customerNames.get(invoice.customerId), row))
            .sorted(candidateOrder()).limit(15).forEach(candidates::add);
        var supplierNames = catalog.suppliers.stream()
            .collect(java.util.stream.Collectors.toMap(supplier -> supplier.id, supplier -> supplier.name));
        catalog.purchases.stream()
            .map(invoice -> invoiceCandidate("PURCHASE_INVOICE", DomainEnums.CashbookResolutionAction.MAP_PURCHASE_INVOICE,
                invoice.id, invoice.invoiceNumber, invoice.invoiceDate, invoice.totalAmount,
                supplierNames.get(invoice.supplierId), row))
            .sorted(candidateOrder()).limit(15).forEach(candidates::add);
        return candidates.stream().sorted(candidateOrder()).limit(30).toList();
    }

    private Map<String, Object> partyCandidate(
        String type, DomainEnums.CashbookResolutionAction action, UUID id, String name, String gstin,
        SmartStagedCashbookEntry row
    ) {
        var confidence = new BigDecimal("0.20");
        var reason = "Available tenant party";
        if (!normalize(row.gstin).isBlank() && normalize(row.gstin).equals(normalize(gstin))) {
            confidence = BigDecimal.ONE;
            reason = "GSTIN matches exactly";
        } else if (normalizeName(row.partyName).equals(normalizeName(name))) {
            confidence = new BigDecimal("0.95");
            reason = "Party name matches exactly";
        } else if (similarName(row.partyName, name)) {
            confidence = new BigDecimal("0.60");
            reason = "Party name is similar";
        }
        return candidate(type, action, id, name, text(gstin), confidence, reason);
    }

    private Map<String, Object> invoiceCandidate(
        String type, DomainEnums.CashbookResolutionAction action, UUID id, String number, LocalDate date,
        BigDecimal amount, String partyName, SmartStagedCashbookEntry row
    ) {
        var confidence = new BigDecimal("0.25");
        var reason = "Recent tenant invoice";
        var reference = SmartImportCashbookMatchingService.invoiceReference(row);
        if (reference != null && normalize(reference).equals(normalize(number))
            && normalizeName(row.partyName).equals(normalizeName(partyName))
            && date != null && row.entryDate != null && !date.isAfter(row.entryDate)
            && row.amount != null && amount != null && row.amount.abs().compareTo(amount.abs()) <= 0) {
            confidence = new BigDecimal("0.95");
            reason = "Invoice reference and party match; confirm allocation";
        } else {
            var amountMatch = row.amount != null && amount != null && row.amount.abs().compareTo(amount.abs()) == 0;
            var dateNear = row.entryDate != null && date != null
                && Math.abs(ChronoUnit.DAYS.between(date, row.entryDate)) <= 7;
            var partyMatch = normalizeName(row.partyName).equals(normalizeName(partyName));
            if (amountMatch && dateNear && partyMatch) {
                confidence = new BigDecimal("0.90");
                reason = "Party, amount, and date window match";
            } else if (amountMatch && dateNear) {
                confidence = new BigDecimal("0.70");
                reason = "Amount and date window match";
            } else if (partyMatch) {
                confidence = new BigDecimal("0.55");
                reason = "Invoice party matches";
            }
        }
        var detail = text(partyName) + " | " + text(number) + " | " + date + " | " + amount;
        return candidate(type, action, id, text(number), detail, confidence, reason);
    }

    private LinkedHashMap<String, Object> candidate(
        String type, DomainEnums.CashbookResolutionAction action, UUID id, String label, String detail,
        BigDecimal confidence, String reason
    ) {
        return linked("type", type, "action", action, "targetId", id, "label", text(label),
            "detail", text(detail), "confidence", confidence.setScale(2, RoundingMode.HALF_UP), "reason", reason);
    }

    private Comparator<Map<String, Object>> candidateOrder() {
        return Comparator.<Map<String, Object>, BigDecimal>comparing(
            candidate -> (BigDecimal) candidate.get("confidence")).reversed()
            .thenComparing(candidate -> String.valueOf(candidate.get("label")));
    }

    private void updateReconciliation(
        UUID tenantId, UUID sessionId, DomainEnums.CashbookMatchStatus originalStatus,
        boolean customer, boolean supplier, boolean invoiceLinked, boolean ignored, boolean duplicate
    ) {
        commits.findByTenantIdAndImportSessionIdForUpdate(tenantId, sessionId).ifPresent(commit -> {
            var summary = new LinkedHashMap<String, Object>(commit.summaryJson == null ? Map.of() : commit.summaryJson);
            decrement(summary, originalStatus == DomainEnums.CashbookMatchStatus.UNMATCHED_REVIEW
                ? "cashbookRowsUnmatched" : "cashbookRowsReviewRequired");
            decrement(summary, "futurePhaseCashbookRowsSkipped");
            if (ignored) {
                increment(summary, "cashbookRowsIgnored");
            } else {
                increment(summary, "cashbookRowsMatched");
                increment(summary, "cashbookRowsManuallyResolved");
                if (duplicate) increment(summary, "duplicatePaymentsSkipped");
                else if (customer) increment(summary, "manualCustomerPaymentsCreated");
                else if (supplier) increment(summary, "manualSupplierPaymentsCreated");
                if (invoiceLinked && customer) increment(summary, "manualPaymentsLinkedToSalesInvoices");
                if (invoiceLinked && supplier) increment(summary, "manualPaymentsLinkedToPurchaseInvoices");
            }
            summary.put("manualResolutionMessage", "Manual cashbook resolutions are included in this reconciliation.");
            summary.put("warnings", number(summary.get("futurePhaseCashbookRowsSkipped")));
            commit.summaryJson = summary;
            commit.updatedBy = TenantContext.userId();
            commits.save(commit);
        });
    }

    private void resolvePlanIssue(
        ImportSession session, SmartStagedCashbookEntry row, DomainEnums.CashbookResolutionAction action
    ) {
        issues.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id).stream()
            .filter(issue -> !issue.resolved)
            .filter(issue -> "CASHBOOK_UNMATCHED_ENTRY".equals(issue.code)
                || "CASHBOOK_LOW_CONFIDENCE".equals(issue.code))
            .filter(issue -> row.id.toString().equals(String.valueOf(issue.contextJson.get("stagingId"))))
            .forEach(issue -> {
                issue.resolved = true;
                issue.resolutionJson = linked("action", action, "cashbookEntryId", row.id);
                issue.updatedBy = TenantContext.userId();
                issues.save(issue);
            });
    }

    private void increment(Map<String, Object> summary, String key) {
        summary.put(key, number(summary.get(key)) + 1L);
    }

    private void decrement(Map<String, Object> summary, String key) {
        summary.put(key, Math.max(0L, number(summary.get(key)) - 1L));
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private boolean needsReview(SmartStagedCashbookEntry row) {
        return row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.UNMATCHED_REVIEW
            || row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.LOW_CONFIDENCE_REVIEW;
    }

    private ImportSession session(UUID sessionId) {
        return sessions.findByTenantIdAndId(TenantContext.tenantId(), sessionId)
            .orElseThrow(() -> ApiErrors.notFound("Import session not found"));
    }

    private String fingerprint(UUID tenantId, SmartStagedCashbookEntry row) {
        if (row.fingerprint != null && !row.fingerprint.isBlank()) return row.fingerprint;
        var raw = tenantId + "|" + row.entryDate + "|" + normalizeName(row.partyName) + "|"
            + row.amount.stripTrailingZeros().toPlainString() + "|" + row.direction + "|" + normalize(row.referenceNumber);
        try {
            row.fingerprint = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
            return row.fingerprint;
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void fillPayment(CustomerPayment payment, SmartStagedCashbookEntry row, String fingerprint, String notes, String note) {
        payment.amount = row.amount.abs();
        payment.paymentDate = row.entryDate;
        payment.mode = row.paymentMode == null ? DomainEnums.PaymentMode.OTHER : row.paymentMode;
        payment.referenceNumber = clean(row.referenceNumber);
        payment.sourceImportSessionId = row.importSessionId;
        payment.sourceFileId = row.sessionFileId;
        payment.sourceRowNumber = row.sourceRowNumber;
        payment.sourceFingerprint = fingerprint;
        payment.rawMetadataJson = new LinkedHashMap<>(row.rawMetadataJson == null ? Map.of() : row.rawMetadataJson);
        payment.notes = notes + (clean(note) == null ? "" : ": " + clean(note));
    }

    private void fillPayment(SupplierPayment payment, SmartStagedCashbookEntry row, String fingerprint, String notes, String note) {
        payment.amount = row.amount.abs();
        payment.paymentDate = row.entryDate;
        payment.mode = row.paymentMode == null ? DomainEnums.PaymentMode.OTHER : row.paymentMode;
        payment.referenceNumber = clean(row.referenceNumber);
        payment.sourceImportSessionId = row.importSessionId;
        payment.sourceFileId = row.sessionFileId;
        payment.sourceRowNumber = row.sourceRowNumber;
        payment.sourceFingerprint = fingerprint;
        payment.rawMetadataJson = new LinkedHashMap<>(row.rawMetadataJson == null ? Map.of() : row.rawMetadataJson);
        payment.notes = notes + (clean(note) == null ? "" : ": " + clean(note));
    }

    private void own(com.stockpilot.ai.domain.BaseAudit entity, UUID tenantId) {
        if (entity instanceof com.stockpilot.ai.domain.TenantOwnedEntity tenantOwned) tenantOwned.tenantId = tenantId;
        entity.createdBy = TenantContext.userId();
        entity.updatedBy = TenantContext.userId();
    }

    private boolean similarName(String left, String right) {
        var a = normalizeName(left);
        var b = normalizeName(right);
        return !a.isBlank() && !b.isBlank() && (a.contains(b) || b.contains(a));
    }

    private String normalizeName(String value) {
        return normalize(value).replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private LinkedHashMap<String, Object> linked(Object... values) {
        var map = new LinkedHashMap<String, Object>();
        for (var index = 0; index < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1] == null ? "" : values[index + 1]);
        }
        return map;
    }

    private record PaymentOutcome(boolean duplicate, boolean customer, boolean supplier, boolean invoiceLinked) {
    }
}
