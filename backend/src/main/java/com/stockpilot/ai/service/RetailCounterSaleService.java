package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.RetailCounterSale;
import com.stockpilot.ai.domain.SmartStagedCashbookEntry;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RetailCounterSaleService {
    private final Repositories.RetailCounterSaleRepository sales;
    private final Repositories.TenantRepository tenants;
    private final Repositories.CustomerPaymentRepository customerPayments;
    private final Repositories.SupplierPaymentRepository supplierPayments;
    private final AuditService audit;

    public RetailCounterSaleService(Repositories.RetailCounterSaleRepository sales,
        Repositories.TenantRepository tenants, Repositories.CustomerPaymentRepository customerPayments,
        Repositories.SupplierPaymentRepository supplierPayments, AuditService audit) {
        this.sales = sales;
        this.tenants = tenants;
        this.customerPayments = customerPayments;
        this.supplierPayments = supplierPayments;
        this.audit = audit;
    }

    // All Smart Import payment writers take this lock before reading staging or fingerprints.
    // The database unique key additionally protects retail records across repeated imports.
    public void lockCashbook(UUID tenantId) {
        tenants.findByIdForCashbookUpdate(tenantId).orElseThrow(() -> ApiErrors.notFound("Workspace not found"));
    }

    public Optional<RetailCounterSale> recorded(UUID tenantId, String fingerprint) {
        return fingerprint == null ? Optional.empty() : sales.findByTenantIdAndSourceFingerprint(tenantId, fingerprint);
    }

    public Map<String, RetailCounterSale> recordedByFingerprint(UUID tenantId, List<String> fingerprints) {
        if (fingerprints == null || fingerprints.isEmpty()) return Map.of();
        return sales.findByTenantIdAndSourceFingerprintIn(tenantId, fingerprints).stream()
            .collect(Collectors.toMap(sale -> sale.sourceFingerprint, Function.identity(),
                (first, ignored) -> first, LinkedHashMap::new));
    }

    public boolean restoreResolution(SmartStagedCashbookEntry row) {
        var existing = recorded(row.tenantId, row.fingerprint);
        existing.ifPresent(sale -> applyResolution(row, sale));
        return existing.isPresent();
    }

    public RecordOutcome record(SmartStagedCashbookEntry row, SmartImportDtos.CashbookReviewResolutionRequest request) {
        if (!"RECORD RETAIL SALES".equals(request.confirmation())
            || !Boolean.TRUE.equals(request.receivedFromRetailSales())
            || !Boolean.TRUE.equals(request.notAlreadyRecorded())) {
            throw ApiErrors.badRequest("Confirm receipt from retail sales and that this amount is not already recorded");
        }
        if (request.collectionMethod() == null || request.targetId() != null) {
            throw ApiErrors.badRequest("Select Cash, UPI, or Cash + UPI without a customer or invoice target");
        }
        if (row.entryDate == null || row.amount == null || row.amount.signum() <= 0
            || row.amount.stripTrailingZeros().scale() > 2 || row.amount.compareTo(new BigDecimal("10000000000000000")) >= 0) {
            throw ApiErrors.badRequest("A source date and positive amount with at most two decimal places are required");
        }
        if (row.fingerprint == null || row.fingerprint.isBlank()) {
            throw ApiErrors.badRequest("Rebuild this entry to obtain a stable source fingerprint before recording");
        }
        var note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (row.direction != DomainEnums.SmartCashbookDirection.RECEIPT && note == null) {
            throw ApiErrors.badRequest("Explain why this source payment or unknown-direction entry represents money received from retail sales");
        }
        if (customerPayments.findByTenantIdAndSourceFingerprint(row.tenantId, row.fingerprint).isPresent()
            || supplierPayments.findByTenantIdAndSourceFingerprint(row.tenantId, row.fingerprint).isPresent()) {
            throw ApiErrors.conflict("This source entry is already recorded as a payment; it cannot also be a retail sale");
        }
        var existing = recorded(row.tenantId, row.fingerprint);
        if (existing.isPresent()) {
            var sale = existing.get();
            if (sale.collectionMethod != request.collectionMethod()) {
                throw ApiErrors.conflict("This source entry is already recorded with a different collection method");
            }
            applyResolution(row, sale);
            return new RecordOutcome(sale.id, true);
        }
        var sale = new RetailCounterSale();
        sale.tenantId = row.tenantId;
        sale.createdBy = TenantContext.userId();
        sale.updatedBy = TenantContext.userId();
        sale.saleDate = row.entryDate;
        sale.amount = row.amount;
        sale.collectionMethod = request.collectionMethod();
        sale.sourceImportSessionId = row.importSessionId;
        sale.sourceFileId = row.sessionFileId;
        sale.sourceRowNumber = row.sourceRowNumber;
        sale.sourceFingerprint = row.fingerprint;
        sale.sourcePartyName = row.partyName;
        sale.sourceDirection = row.direction;
        sale.referenceNumber = row.referenceNumber;
        sale.note = note;
        sale.rawMetadataJson = new LinkedHashMap<>(row.rawMetadataJson == null ? Map.of() : row.rawMetadataJson);
        sale.rawMetadataJson.put("retailConfirmation", Map.of("receivedFromRetailSales", true, "notAlreadyRecorded", true));
        sale = sales.saveAndFlush(sale);
        applyResolution(row, sale);
        audit.logCurrent("RETAIL_COUNTER_SALE_RECORDED", "RetailCounterSale", sale.id,
            Map.of("sourceFingerprint", sale.sourceFingerprint, "amount", sale.amount,
                "collectionMethod", sale.collectionMethod, "sourceRowNumber", sale.sourceRowNumber));
        return new RecordOutcome(sale.id, false);
    }

    private void applyResolution(SmartStagedCashbookEntry row, RetailCounterSale sale) {
        row.retailCounterSaleId = sale.id;
        row.manualResolutionAction = DomainEnums.CashbookResolutionAction.RECORD_RETAIL_SALE;
        row.resolutionStatus = DomainEnums.CashbookResolutionStatus.RETAIL_SALE_RECORDED;
        row.reviewStatus = DomainEnums.SmartReviewStatus.RESOLVED;
        row.matchStatus = DomainEnums.SmartMatchStatus.MATCH_EXISTING;
        row.matchedPartyId = null;
        row.matchedInvoiceId = null;
        row.matchedPartyType = DomainEnums.SmartPartyType.UNKNOWN;
        row.matchReason = "Confirmed retail counter collection; amount only, no inventory effect.";
        row.resolutionNote = sale.note;
        row.resolvedBy = sale.createdBy;
        row.resolvedAt = sale.createdAt;
        row.updatedBy = TenantContext.userId();
    }

    @Transactional(readOnly = true)
    public Page<SaleResponse> list(Pageable pageable) {
        var page = PageRequest.of(pageable.getPageNumber(), Math.min(100, pageable.getPageSize()),
            Sort.by(Sort.Order.desc("saleDate"), Sort.Order.desc("id")));
        return sales.findByTenantId(TenantContext.tenantId(), page).map(sale -> new SaleResponse(
            sale.id, sale.saleDate, sale.amount, sale.collectionMethod, sale.sourcePartyName, sale.sourceDirection,
            sale.referenceNumber, sale.sourceRowNumber, sale.sourceImportSessionId, sale.note, sale.createdAt));
    }

    public record RecordOutcome(UUID saleId, boolean duplicate) { }
    public record SaleResponse(UUID id, LocalDate saleDate, BigDecimal amount,
        DomainEnums.RetailCollectionMethod collectionMethod, String sourcePartyName,
        DomainEnums.SmartCashbookDirection sourceDirection, String referenceNumber, int sourceRowNumber,
        UUID sourceImportSessionId, String note, Instant createdAt) { }
}
