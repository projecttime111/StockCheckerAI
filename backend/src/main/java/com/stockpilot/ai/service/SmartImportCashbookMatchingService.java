package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.repo.Repositories;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.stockpilot.ai.domain.DomainEnums.*;

@Service
public class SmartImportCashbookMatchingService {
    public static final int POLICY_VERSION = 2;
    private final Repositories.SmartStagedCashbookEntryRepository cashbook;
    private final Repositories.SmartStagedPartyRepository parties;
    private final Repositories.SmartStagedVoucherRepository vouchers;
    private final Repositories.CustomerRepository customers;
    private final Repositories.SupplierRepository suppliers;
    private final Repositories.SalesInvoiceRepository sales;
    private final Repositories.PurchaseInvoiceRepository purchases;
    private final RetailCounterSaleService retail;
    private final Repositories.ImportPlanIssueRepository issues;
    private final Repositories.CustomerPaymentRepository customerPayments;
    private final Repositories.SupplierPaymentRepository supplierPayments;

    public SmartImportCashbookMatchingService(Repositories.SmartStagedCashbookEntryRepository cashbook,
        Repositories.SmartStagedPartyRepository parties, Repositories.SmartStagedVoucherRepository vouchers,
        Repositories.CustomerRepository customers, Repositories.SupplierRepository suppliers,
        Repositories.SalesInvoiceRepository sales, Repositories.PurchaseInvoiceRepository purchases,
        RetailCounterSaleService retail, Repositories.ImportPlanIssueRepository issues,
        Repositories.CustomerPaymentRepository customerPayments, Repositories.SupplierPaymentRepository supplierPayments) {
        this.cashbook = cashbook;
        this.parties = parties;
        this.vouchers = vouchers;
        this.customers = customers;
        this.suppliers = suppliers;
        this.sales = sales;
        this.purchases = purchases;
        this.retail = retail;
        this.issues = issues;
        this.customerPayments = customerPayments;
        this.supplierPayments = supplierPayments;
    }

    @Transactional
    public void refresh(UUID tenantId, UUID sessionId) {
        retail.lockCashbook(tenantId);
        var rows = cashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(tenantId, sessionId);
        if (rows.isEmpty()) return;
        var partyOptions = new ArrayList<Party>();
        customers.findByTenantIdOrderByNameAsc(tenantId).forEach(p ->
            partyOptions.add(new Party(SmartPartyType.CUSTOMER, p.id, p.name, p.gstin)));
        suppliers.findByTenantIdOrderByNameAsc(tenantId).forEach(p ->
            partyOptions.add(new Party(SmartPartyType.SUPPLIER, p.id, p.name, p.gstin)));
        for (var p : parties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId)) {
            if (p.reviewStatus == SmartReviewStatus.PENDING) continue;
            partyOptions.add(new Party(p.partyType,
                p.partyType == SmartPartyType.CUSTOMER ? p.matchedCustomerId : p.matchedSupplierId, p.rawName, p.gstin));
        }
        var invoices = new ArrayList<Invoice>();
        sales.findByTenantId(tenantId).forEach(i -> invoices.add(new Invoice(SmartPartyType.CUSTOMER,
            i.customerId, null, i.id, null, i.invoiceNumber, i.invoiceDate, i.totalAmount)));
        purchases.findByTenantId(tenantId).forEach(i -> invoices.add(new Invoice(SmartPartyType.SUPPLIER,
            i.supplierId, null, i.id, null, i.invoiceNumber, i.invoiceDate, i.totalAmount)));
        for (var v : vouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(tenantId, sessionId)) {
            if (v.reviewStatus == SmartReviewStatus.PENDING || v.matchStatus == SmartMatchStatus.SKIP_DUPLICATE
                || Boolean.parseBoolean(Objects.toString(v.rawMetadataJson.get("excludedFromCommit"), "false"))) continue;
            var type = norm(v.voucherType);
            if (type.contains("RETURN")) continue;
            var partyType = type.contains("SALE") ? SmartPartyType.CUSTOMER
                : type.contains("PURCHASE") ? SmartPartyType.SUPPLIER : SmartPartyType.UNKNOWN;
            if (partyType != SmartPartyType.UNKNOWN) invoices.add(new Invoice(partyType, v.matchedPartyId,
                v.partyName, null, v.id, v.voucherNumber, v.voucherDate, v.totalAmount));
        }
        var recorded = retail.recordedByFingerprint(tenantId, rows.stream().map(r -> r.fingerprint)
            .filter(Objects::nonNull).distinct().toList());
        var fingerprints = rows.stream().map(r -> r.fingerprint).filter(Objects::nonNull).distinct().toList();
        var postedCustomers = new HashMap<String, CustomerPayment>();
        var postedSuppliers = new HashMap<String, SupplierPayment>();
        if (!fingerprints.isEmpty()) {
            customerPayments.findByTenantIdAndSourceFingerprintIn(tenantId, fingerprints)
                .forEach(p -> postedCustomers.put(p.sourceFingerprint, p));
            supplierPayments.findByTenantIdAndSourceFingerprintIn(tenantId, fingerprints)
                .forEach(p -> postedSuppliers.put(p.sourceFingerprint, p));
        }
        for (var row : rows) {
            if (row.resolutionStatus != CashbookResolutionStatus.UNRESOLVED) continue;
            if (recorded.containsKey(row.fingerprint)) {
                retail.restoreResolution(row);
                continue;
            }
            var customerPayment = postedCustomers.get(row.fingerprint);
            var supplierPayment = postedSuppliers.get(row.fingerprint);
            if (customerPayment != null || supplierPayment != null) {
                row.matchedStagedVoucherId = null;
                row.matchedPartyId = customerPayment != null ? customerPayment.customerId : supplierPayment.supplierId;
                row.matchedInvoiceId = customerPayment != null ? customerPayment.salesInvoiceId : supplierPayment.purchaseInvoiceId;
                row.customerPaymentId = customerPayment == null ? null : customerPayment.id;
                row.supplierPaymentId = supplierPayment == null ? null : supplierPayment.id;
                row.matchedPartyType = customerPayment != null ? SmartPartyType.CUSTOMER : SmartPartyType.SUPPLIER;
                row.cashbookMatchStatus = customerPayment != null
                    ? row.matchedInvoiceId == null ? CashbookMatchStatus.MATCHED_CUSTOMER_PAYMENT : CashbookMatchStatus.MATCHED_SALES_INVOICE
                    : row.matchedInvoiceId == null ? CashbookMatchStatus.MATCHED_SUPPLIER_PAYMENT : CashbookMatchStatus.MATCHED_PURCHASE_INVOICE;
                row.matchStatus = SmartMatchStatus.MATCH_EXISTING;
                row.reviewStatus = SmartReviewStatus.AUTO_RESOLVED;
                row.matchReason = "Payment already posted; existing party and invoice allocation preserved.";
                continue;
            }
            if (row.manualResolutionAction != CashbookResolutionAction.KEEP_UNRESOLVED) {
                match(row, partyOptions, invoices);
            }
        }
        cashbook.saveAllAndFlush(rows);
    }

    public void refreshReviewIssues(UUID tenantId, UUID sessionId) {
        var existing = new HashMap<String, ImportPlanIssue>();
        issues.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(tenantId, sessionId).stream()
            .filter(i -> "CASHBOOK_UNMATCHED_ENTRY".equals(i.code) || "CASHBOOK_LOW_CONFIDENCE".equals(i.code))
            .forEach(i -> existing.put(Objects.toString(i.contextJson.get("stagingId"), ""), i));
        var changed = new ArrayList<ImportPlanIssue>();
        for (var row : cashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(tenantId, sessionId)) {
            var unresolved = row.resolutionStatus == CashbookResolutionStatus.UNRESOLVED && !ready(row);
            var issue = existing.get(row.id.toString());
            if (issue == null && !unresolved) continue;
            if (issue == null) {
                issue = new ImportPlanIssue();
                issue.tenantId = tenantId;
                issue.importSessionId = sessionId;
                issue.createdBy = TenantContext.userId();
                issue.affectedFileId = row.sessionFileId;
                issue.affectedRows = List.of(row.sourceRowNumber);
                issue.contextJson = new LinkedHashMap<>(Map.of("entityType", "CASHBOOK", "stagingId", row.id.toString(),
                    "resolutionKey", "CASHBOOK:" + row.id));
            }
            issue.updatedBy = TenantContext.userId();
            issue.severity = ImportPlanIssueSeverity.WARNING;
            issue.code = row.cashbookMatchStatus == CashbookMatchStatus.LOW_CONFIDENCE_REVIEW
                ? "CASHBOOK_LOW_CONFIDENCE" : "CASHBOOK_UNMATCHED_ENTRY";
            issue.message = "Cashbook entry needs review: " + row.partyName;
            issue.suggestedAction = row.matchReason;
            issue.resolved = !unresolved;
            changed.add(issue);
        }
        issues.saveAllAndFlush(changed);
    }

    static void match(SmartStagedCashbookEntry row, List<Party> parties, List<Invoice> invoices) {
        row.matchedPartyId = null;
        row.matchedPartyName = null;
        row.matchedInvoiceId = null;
        row.matchedStagedVoucherId = null;
        row.matchedPartyType = SmartPartyType.UNKNOWN;
        review(row, false, "No verified customer or supplier was found. Cash/bank ledger names and voucher numbers alone do not identify a payment recipient.");
        if (row.entryDate == null || row.amount == null || row.amount.signum() <= 0
            || row.direction == SmartCashbookDirection.UNKNOWN) {
            review(row, false, "A payment date, positive amount, and receipt/payment direction are required.");
            return;
        }
        if (cashOrBank(row.partyName)) return;
        var type = row.direction == SmartCashbookDirection.RECEIPT ? SmartPartyType.CUSTOMER : SmartPartyType.SUPPLIER;
        var candidates = new LinkedHashMap<String, Party>();
        var sourceName = norm(row.partyName);
        var sourceGstin = norm(row.gstin);
        for (var p : parties) {
            if (p.type == type && (p.normalizedName.equals(sourceName)
                || !sourceGstin.isEmpty() && p.normalizedGstin.equals(sourceGstin))) {
                candidates.merge(identity(p), p, (a, b) -> a.id != null ? a : b);
            }
        }
        if (candidates.size() != 1) {
            if (candidates.size() > 1) review(row, true, "More than one party matches this cashbook row.");
            return;
        }
        var party = candidates.values().iterator().next();
        row.matchedPartyId = party.id;
        row.matchedPartyName = party.name;
        row.matchedPartyType = type;
        var names = new HashSet<String>();
        var ids = new HashSet<UUID>();
        for (var alias : parties) {
            if (alias.key.equals(party.key)) {
                names.add(alias.normalizedName);
                if (alias.id != null) ids.add(alias.id);
            }
        }
        var partyInvoices = invoices.stream().filter(i -> i.type == type
            && (i.partyId != null && ids.contains(i.partyId) || names.contains(i.normalizedPartyName))).toList();
        var reference = invoiceReference(row);
        var referenceMatches = reference == null ? List.<Invoice>of()
            : invoices.stream().filter(i -> i.type == type && same(i.number, reference)).toList();
        List<Invoice> matches;
        if (!referenceMatches.isEmpty()) {
            matches = referenceMatches.stream().filter(i -> partyInvoices.contains(i)
                && i.date != null && !i.date.isAfter(row.entryDate) && i.amount != null
                && row.amount.compareTo(i.amount.abs()) <= 0).toList();
            if (matches.size() != 1) {
                review(row, true, "Invoice reference conflicts with the party, date, amount, or multiple invoices. Confirm the allocation manually.");
                return;
            }
        } else if (reference == null) {
            matches = partyInvoices.stream().filter(i -> i.date != null && i.amount != null
                && row.amount.compareTo(i.amount.abs()) == 0
                && ChronoUnit.DAYS.between(i.date, row.entryDate) >= 0
                && ChronoUnit.DAYS.between(i.date, row.entryDate) <= 7).toList();
        } else {
            matches = List.of();
        }
        if (matches.size() > 1) {
            review(row, true, "Multiple invoices match the party, amount, and date window.");
            return;
        }
        row.matchStatus = SmartMatchStatus.MATCH_EXISTING;
        row.reviewStatus = SmartReviewStatus.AUTO_RESOLVED;
        row.matchConfidence = new BigDecimal("0.80");
        row.cashbookMatchStatus = type == SmartPartyType.CUSTOMER
            ? CashbookMatchStatus.MATCHED_CUSTOMER_PAYMENT : CashbookMatchStatus.MATCHED_SUPPLIER_PAYMENT;
        row.matchReason = "Verified party; payment is unallocated to an invoice.";
        if (matches.size() == 1) {
            var invoice = matches.getFirst();
            row.matchedInvoiceId = invoice.id;
            row.matchedStagedVoucherId = invoice.stagedId;
            row.cashbookMatchStatus = type == SmartPartyType.CUSTOMER
                ? CashbookMatchStatus.MATCHED_SALES_INVOICE : CashbookMatchStatus.MATCHED_PURCHASE_INVOICE;
            row.matchConfidence = new BigDecimal("0.90");
            row.matchReason = "Verified party and invoice evidence; exact invoice identity retained for commit.";
        }
    }

    public static boolean ready(SmartStagedCashbookEntry row) {
        if (row.resolutionStatus != CashbookResolutionStatus.UNRESOLVED
            || row.reviewStatus == SmartReviewStatus.PENDING || row.entryDate == null
            || row.amount == null || row.amount.signum() <= 0
            || (row.matchedPartyId == null && norm(row.matchedPartyName).isEmpty())) return false;
        if (row.matchedPartyId != null && (row.customerPaymentId != null || row.supplierPaymentId != null)) return true;
        var customer = row.direction == SmartCashbookDirection.RECEIPT && row.matchedPartyType == SmartPartyType.CUSTOMER;
        var supplier = row.direction == SmartCashbookDirection.PAYMENT && row.matchedPartyType == SmartPartyType.SUPPLIER;
        return customer && (row.cashbookMatchStatus == CashbookMatchStatus.MATCHED_CUSTOMER_PAYMENT
                || row.cashbookMatchStatus == CashbookMatchStatus.MATCHED_SALES_INVOICE && hasInvoice(row))
            || supplier && (row.cashbookMatchStatus == CashbookMatchStatus.MATCHED_SUPPLIER_PAYMENT
                || row.cashbookMatchStatus == CashbookMatchStatus.MATCHED_PURCHASE_INVOICE && hasInvoice(row));
    }

    private static boolean hasInvoice(SmartStagedCashbookEntry row) {
        return row.matchedInvoiceId != null || row.matchedStagedVoucherId != null;
    }

    static String invoiceReference(SmartStagedCashbookEntry row) {
        var raw = row.rawMetadataJson == null ? Map.<String, Object>of() : row.rawMetadataJson;
        if (raw.containsKey("Invoice Reference")) {
            var explicit = Objects.toString(raw.get("Invoice Reference"), "").trim();
            return explicit.isEmpty() ? null : explicit;
        }
        // Legacy exports used the payment's own voucher number as a fallback reference.
        var reference = Objects.toString(raw.getOrDefault("Reference Number", row.referenceNumber), "").trim();
        if (reference.isEmpty() || same(reference, Objects.toString(raw.get("Voucher Number"), ""))
            || same(reference, Objects.toString(raw.get("Invoice Number"), ""))) return null;
        return reference;
    }

    private static String identity(Party p) {
        return p.key;
    }

    private static void review(SmartStagedCashbookEntry row, boolean ambiguous, String reason) {
        row.cashbookMatchStatus = ambiguous ? CashbookMatchStatus.LOW_CONFIDENCE_REVIEW : CashbookMatchStatus.UNMATCHED_REVIEW;
        row.matchStatus = ambiguous ? SmartMatchStatus.AMBIGUOUS_REVIEW : SmartMatchStatus.UNKNOWN;
        row.reviewStatus = SmartReviewStatus.PENDING;
        row.matchConfidence = BigDecimal.ZERO;
        row.matchReason = reason;
    }

    static boolean cashOrBank(String name) {
        var normalized = norm(name);
        return normalized.isEmpty() || normalized.equals("CASH") || normalized.equals("BANK")
            || normalized.contains("CASH IN HAND") || normalized.matches(".*\\bBANK\\b.*");
    }

    private static boolean same(String a, String b) { return !norm(a).isEmpty() && norm(a).equals(norm(b)); }
    private static String norm(String value) { return Objects.toString(value, "").trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT); }
    record Party(SmartPartyType type, UUID id, String name, String gstin, String normalizedName, String normalizedGstin, String key) {
        Party(SmartPartyType type, UUID id, String name, String gstin) {
            this(type, id, name, gstin, norm(name), norm(gstin), type + "|" + (!norm(gstin).isEmpty()
                ? "GST:" + norm(gstin) : id != null ? "ID:" + id : "NAME:" + norm(name)));
        }
    }
    record Invoice(SmartPartyType type, UUID partyId, String partyName, UUID id, UUID stagedId,
                   String number, LocalDate date, BigDecimal amount, String normalizedPartyName) {
        Invoice(SmartPartyType type, UUID partyId, String partyName, UUID id, UUID stagedId,
                String number, LocalDate date, BigDecimal amount) {
            this(type, partyId, partyName, id, stagedId, number, date, amount, norm(partyName));
        }
    }
}
