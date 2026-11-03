package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.SmartStagedVoucher;
import com.stockpilot.ai.domain.SmartStagedVoucherItem;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SmartImportReturnService {
    public static final String CONFIRMATION = "POST RETURN STOCK";

    private final Repositories.ImportSessionRepository sessions;
    private final Repositories.SmartStagedVoucherRepository vouchers;
    private final Repositories.SmartStagedVoucherItemRepository items;
    private final Repositories.ProductRepository products;
    private final Repositories.WarehouseRepository warehouses;
    private final AuditService audit;

    public SmartImportReturnService(
        Repositories.ImportSessionRepository sessions,
        Repositories.SmartStagedVoucherRepository vouchers,
        Repositories.SmartStagedVoucherItemRepository items,
        Repositories.ProductRepository products,
        Repositories.WarehouseRepository warehouses,
        AuditService audit
    ) {
        this.sessions = sessions;
        this.vouchers = vouchers;
        this.items = items;
        this.products = products;
        this.warehouses = warehouses;
        this.audit = audit;
    }

    @Transactional
    public void classifySession(UUID tenantId, UUID sessionId) {
        var stagedVouchers = vouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(
            tenantId, sessionId);
        var byVoucher = items.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId).stream()
            .collect(Collectors.groupingBy(item -> item.stagedVoucherId));
        for (var voucher : stagedVouchers) {
            classifyVoucher(voucher, byVoucher.getOrDefault(voucher.id, List.of()));
        }
        vouchers.saveAll(stagedVouchers);
        items.saveAll(byVoucher.values().stream().flatMap(List::stream).toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> unresolved(UUID sessionId) {
        var tenantId = TenantContext.tenantId();
        session(tenantId, sessionId);
        var voucherIndex = vouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(
                tenantId, sessionId).stream()
            .collect(Collectors.toMap(voucher -> voucher.id, voucher -> voucher));
        return items.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId).stream()
            .filter(item -> isReturn(voucherIndex.get(item.stagedVoucherId)))
            .filter(item -> item.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.REVIEW_REQUIRED)
            .map(item -> response(voucherIndex.get(item.stagedVoucherId), item))
            .toList();
    }

    @Transactional
    public Map<String, Object> resolve(
        UUID sessionId, UUID itemId, SmartImportDtos.ReturnReviewResolutionRequest request
    ) {
        var tenantId = TenantContext.tenantId();
        session(tenantId, sessionId);
        var item = items.findByTenantIdAndImportSessionIdAndIdForUpdate(tenantId, sessionId, itemId)
            .orElseThrow(() -> ApiErrors.notFound("Return review item not found"));
        var voucher = vouchers.findByTenantIdAndImportSessionIdAndId(tenantId, sessionId, item.stagedVoucherId)
            .orElseThrow(() -> ApiErrors.notFound("Return note not found"));
        if (!isReturn(voucher)) throw ApiErrors.badRequest("The selected item is not a credit/debit return note");

        switch (request.action()) {
            case KEEP_UNRESOLVED -> {
                item.returnResolutionStatus = DomainEnums.ReturnResolutionStatus.REVIEW_REQUIRED;
                item.returnDisposition = DomainEnums.ReturnDisposition.REVIEW_REQUIRED;
                item.returnDetectionReason = appendReason(item.returnDetectionReason, "Kept unresolved by user.");
            }
            case CONFIRM_FINANCIAL_ONLY -> {
                item.returnDirection = DomainEnums.ReturnDirection.NONE;
                item.returnDisposition = DomainEnums.ReturnDisposition.FINANCIAL_ONLY;
                item.returnResolutionStatus = DomainEnums.ReturnResolutionStatus.USER_CONFIRMED_FINANCIAL_ONLY;
                item.returnDetectionReason = appendReason(item.returnDetectionReason, "User confirmed financial-only.");
                item.reviewStatus = DomainEnums.SmartReviewStatus.RESOLVED;
                audit.logCurrent("SMART_IMPORT_FINANCIAL_ONLY_RETURN_CONFIRMED", "SmartStagedVoucherItem", item.id,
                    Map.of("sessionId", sessionId, "sourceRow", item.sourceRowNumber));
            }
            case CONFIRM_PHYSICAL_SALES_RETURN, CONFIRM_PHYSICAL_PURCHASE_RETURN -> {
                if (!CONFIRMATION.equals(request.confirmation())) {
                    throw ApiErrors.badRequest("Enter POST RETURN STOCK to confirm physical return posting");
                }
                if (request.productId() == null || request.warehouseId() == null
                    || request.quantity() == null || request.quantity().signum() <= 0) {
                    throw ApiErrors.badRequest("Product, warehouse, and a positive normalized quantity are required");
                }
                products.findByTenantIdAndId(tenantId, request.productId())
                    .orElseThrow(() -> ApiErrors.badRequest("Selected product is not available in this workspace"));
                warehouses.findByTenantIdAndId(tenantId, request.warehouseId())
                    .orElseThrow(() -> ApiErrors.badRequest("Selected warehouse is not available in this workspace"));
                var expected = request.action() == DomainEnums.ReturnResolutionAction.CONFIRM_PHYSICAL_SALES_RETURN
                    ? DomainEnums.ReturnDirection.SALES_RETURN_IN : DomainEnums.ReturnDirection.PURCHASE_RETURN_OUT;
                if (expected == DomainEnums.ReturnDirection.SALES_RETURN_IN && !isCredit(voucher)
                    || expected == DomainEnums.ReturnDirection.PURCHASE_RETURN_OUT && !isDebit(voucher)) {
                    throw ApiErrors.badRequest("Return direction does not match the credit/debit note type");
                }
                var disposition = request.disposition() == null
                    ? DomainEnums.ReturnDisposition.RESTOCK_TO_AVAILABLE : request.disposition();
                if (disposition != DomainEnums.ReturnDisposition.RESTOCK_TO_AVAILABLE) {
                    throw ApiErrors.badRequest("Physical posting currently supports RESTOCK_TO_AVAILABLE only");
                }
                item.matchedProductId = request.productId();
                item.matchedWarehouseId = request.warehouseId();
                item.matchStatus = DomainEnums.SmartMatchStatus.MATCH_EXISTING;
                item.reviewStatus = DomainEnums.SmartReviewStatus.RESOLVED;
                item.quantity = request.quantity().abs();
                item.returnDirection = expected;
                item.returnDisposition = disposition;
                item.returnResolutionStatus = DomainEnums.ReturnResolutionStatus.USER_CONFIRMED_PHYSICAL;
                item.returnConfidence = BigDecimal.ONE;
                item.returnDetectionReason = appendReason(item.returnDetectionReason, "Physical return confirmed by user.");
            }
        }
        item.returnResolvedBy = TenantContext.userId();
        item.returnResolvedAt = Instant.now();
        item.updatedBy = TenantContext.userId();
        items.save(item);
        refreshVoucher(voucher, items.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId).stream()
            .filter(candidate -> Objects.equals(candidate.stagedVoucherId, voucher.id)).toList());
        vouchers.save(voucher);
        if (item.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.USER_CONFIRMED_PHYSICAL) {
            audit.logCurrent("SMART_IMPORT_RETURN_REVIEW_RESOLVED", "SmartStagedVoucherItem", item.id,
                Map.of("sessionId", sessionId, "sourceRow", item.sourceRowNumber,
                    "direction", item.returnDirection.name(), "resolutionSource", "USER"));
        }
        return response(voucher, item);
    }

    public boolean isPhysicalConfirmed(SmartStagedVoucherItem item) {
        return item.returnDisposition == DomainEnums.ReturnDisposition.RESTOCK_TO_AVAILABLE
            && (item.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.AUTO_CONFIRMED_PHYSICAL
                || item.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.USER_CONFIRMED_PHYSICAL);
    }

    public boolean isReturn(SmartStagedVoucher voucher) {
        return voucher != null && (isCredit(voucher) || isDebit(voucher));
    }

    private void classifyVoucher(SmartStagedVoucher voucher, List<SmartStagedVoucherItem> sourceItems) {
        if (!isReturn(voucher)) {
            voucher.returnClassification = DomainEnums.ReturnClassification.NOT_A_RETURN;
            voucher.returnResolutionStatus = null;
            return;
        }
        if (sourceItems.isEmpty()) {
            voucher.returnClassification = DomainEnums.ReturnClassification.FINANCIAL_ONLY;
            voucher.returnResolutionStatus = DomainEnums.ReturnResolutionStatus.STOCK_EFFECT_DEFERRED;
            voucher.returnConfidence = BigDecimal.ONE;
            voucher.returnDetectionReason = "No physical inventory lines were present.";
            return;
        }
        for (var item : sourceItems) classifyItem(voucher, item);
        refreshVoucher(voucher, sourceItems);
    }

    private void classifyItem(SmartStagedVoucher voucher, SmartStagedVoucherItem item) {
        if (item.returnResolvedBy != null) return;
        var direction = isCredit(voucher)
            ? DomainEnums.ReturnDirection.SALES_RETURN_IN : DomainEnums.ReturnDirection.PURCHASE_RETURN_OUT;
        item.returnDirection = direction;
        var damaged = containsAny(item.rawMetadataJson,
            "DAMAGED", "EXPIRED", "REJECTED", "SCRAP", "NON-SALEABLE", "NON SALEABLE");
        var mismatch = item.billedQuantity != null && item.actualQuantity != null
            && item.billedQuantity.abs().compareTo(item.actualQuantity.abs()) != 0;
        var quantity = item.quantity == null ? BigDecimal.ZERO : item.quantity.abs();
        var productResolved = item.matchedProductId != null
            || item.matchStatus == DomainEnums.SmartMatchStatus.CREATE_NEW;
        var warehouseResolved = item.matchedWarehouseId != null
            || item.warehouseName != null && !item.warehouseName.isBlank()
                && item.reviewStatus != DomainEnums.SmartReviewStatus.PENDING;
        if (quantity.signum() == 0 && !mismatch) {
            item.returnDirection = DomainEnums.ReturnDirection.NONE;
            item.returnDisposition = DomainEnums.ReturnDisposition.FINANCIAL_ONLY;
            item.returnResolutionStatus = DomainEnums.ReturnResolutionStatus.STOCK_EFFECT_DEFERRED;
            item.returnConfidence = BigDecimal.ONE;
            item.returnDetectionReason = "ZERO_QUANTITY_FINANCIAL_ONLY";
            return;
        }
        var reliable = productResolved && warehouseResolved && !mismatch && !damaged;
        if (reliable) {
            item.returnDisposition = DomainEnums.ReturnDisposition.RESTOCK_TO_AVAILABLE;
            item.returnResolutionStatus = DomainEnums.ReturnResolutionStatus.AUTO_CONFIRMED_PHYSICAL;
            item.returnConfidence = new BigDecimal("0.98");
            item.returnDetectionReason = "Matched inventory item, non-zero quantity, and resolved godown.";
        } else {
            item.returnDisposition = damaged
                ? DomainEnums.ReturnDisposition.REVIEW_REQUIRED : DomainEnums.ReturnDisposition.REVIEW_REQUIRED;
            item.returnResolutionStatus = DomainEnums.ReturnResolutionStatus.REVIEW_REQUIRED;
            item.returnConfidence = new BigDecimal("0.40");
            var reasons = new ArrayList<String>();
            if (quantity.signum() == 0) reasons.add("RETURN_QUANTITY_MISSING");
            if (!productResolved) reasons.add("RETURN_PRODUCT_UNRESOLVED");
            if (!warehouseResolved) reasons.add("RETURN_WAREHOUSE_UNRESOLVED");
            if (mismatch) reasons.add("BILLED_ACTUAL_QUANTITY_MISMATCH");
            if (damaged) reasons.add("DAMAGED_OR_NON_SALEABLE_REVIEW");
            item.returnDetectionReason = String.join(", ", reasons);
        }
    }

    private void refreshVoucher(SmartStagedVoucher voucher, List<SmartStagedVoucherItem> sourceItems) {
        var physical = sourceItems.stream().filter(this::isPhysicalConfirmed).count();
        var unresolved = sourceItems.stream().anyMatch(item ->
            item.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.REVIEW_REQUIRED);
        var financialComponents = voucher.taxLineCount + voucher.discountLineCount + voucher.freightLineCount
            + voucher.roundOffLineCount + voucher.otherChargeLineCount > 0;
        voucher.returnClassification = unresolved
            ? DomainEnums.ReturnClassification.AMBIGUOUS_RETURN
            : physical == 0 ? DomainEnums.ReturnClassification.FINANCIAL_ONLY
            : isCredit(voucher)
                ? financialComponents ? DomainEnums.ReturnClassification.MIXED_SALES_RETURN
                    : DomainEnums.ReturnClassification.PHYSICAL_SALES_RETURN
                : financialComponents ? DomainEnums.ReturnClassification.MIXED_PURCHASE_RETURN
                    : DomainEnums.ReturnClassification.PHYSICAL_PURCHASE_RETURN;
        voucher.returnResolutionStatus = unresolved
            ? DomainEnums.ReturnResolutionStatus.REVIEW_REQUIRED
            : physical > 0 && sourceItems.stream().anyMatch(item ->
                item.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.USER_CONFIRMED_PHYSICAL)
                ? DomainEnums.ReturnResolutionStatus.USER_CONFIRMED_PHYSICAL
                : physical > 0 ? DomainEnums.ReturnResolutionStatus.AUTO_CONFIRMED_PHYSICAL
                : sourceItems.stream().anyMatch(item ->
                    item.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.USER_CONFIRMED_FINANCIAL_ONLY)
                    ? DomainEnums.ReturnResolutionStatus.USER_CONFIRMED_FINANCIAL_ONLY
                    : DomainEnums.ReturnResolutionStatus.STOCK_EFFECT_DEFERRED;
        voucher.returnConfidence = unresolved ? new BigDecimal("0.40") : BigDecimal.ONE;
        voucher.returnDetectionReason = unresolved
            ? "One or more return lines require review."
            : physical > 0 ? "Confirmed physical inventory return evidence." : "Financial-only note.";
        voucher.returnResolvedBy = sourceItems.stream().map(item -> item.returnResolvedBy)
            .filter(Objects::nonNull).findFirst().orElse(null);
        voucher.returnResolvedAt = sourceItems.stream().map(item -> item.returnResolvedAt)
            .filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
        voucher.updatedBy = TenantContext.userId();
    }

    private Map<String, Object> response(SmartStagedVoucher voucher, SmartStagedVoucherItem item) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", item.id);
        result.put("voucherId", voucher.id);
        result.put("voucherType", voucher.voucherType);
        result.put("voucherNumber", safe(voucher.voucherNumber));
        result.put("voucherDate", voucher.voucherDate == null ? "" : voucher.voucherDate);
        result.put("classification", voucher.returnClassification);
        result.put("classificationConfidence", voucher.returnConfidence);
        result.put("detectionReason", safe(item.returnDetectionReason));
        result.put("productId", item.matchedProductId == null ? "" : item.matchedProductId);
        result.put("productName", safe(item.productName));
        result.put("warehouseId", item.matchedWarehouseId == null ? "" : item.matchedWarehouseId);
        result.put("warehouseName", safe(item.warehouseName));
        result.put("rawQuantity", item.rawQuantity == null ? "" : item.rawQuantity);
        result.put("billedQuantity", item.billedQuantity == null ? "" : item.billedQuantity);
        result.put("actualQuantity", item.actualQuantity == null ? "" : item.actualQuantity);
        result.put("normalizedQuantity", item.quantity == null ? "" : item.quantity.abs());
        result.put("returnDirection", item.returnDirection);
        result.put("disposition", item.returnDisposition);
        result.put("resolutionStatus", item.returnResolutionStatus);
        result.put("confirmationPhrase", CONFIRMATION);
        return result;
    }

    private boolean isCredit(SmartStagedVoucher voucher) {
        return normalized(voucher.voucherType).contains("CREDIT");
    }

    private boolean isDebit(SmartStagedVoucher voucher) {
        return normalized(voucher.voucherType).contains("DEBIT")
            || normalized(voucher.voucherType).contains("PURCHASE RETURN");
    }

    private boolean containsAny(Map<String, Object> metadata, String... needles) {
        var value = normalized(String.valueOf(metadata == null ? Map.of() : metadata));
        for (var needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private String appendReason(String current, String addition) {
        return current == null || current.isBlank() ? addition : current + " " + addition;
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void session(UUID tenantId, UUID sessionId) {
        sessions.findByTenantIdAndId(tenantId, sessionId)
            .orElseThrow(() -> ApiErrors.notFound("Import session not found"));
    }
}
