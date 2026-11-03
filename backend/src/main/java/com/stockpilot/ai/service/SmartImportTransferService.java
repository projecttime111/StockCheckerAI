package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.ImportSessionFile;
import com.stockpilot.ai.domain.SmartStagedTransferCandidate;
import com.stockpilot.ai.domain.SmartStagedVoucher;
import com.stockpilot.ai.domain.SmartStagedVoucherItem;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SmartImportTransferService {
    public static final String CONFIRMATION = "POST STOCK TRANSFER";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final Repositories.ImportSessionRepository sessions;
    private final Repositories.ImportSessionFileRepository files;
    private final Repositories.SmartStagedVoucherRepository vouchers;
    private final Repositories.SmartStagedVoucherItemRepository items;
    private final Repositories.SmartStagedTransferCandidateRepository candidates;
    private final Repositories.ProductRepository products;
    private final Repositories.WarehouseRepository warehouses;
    private final AuditService audit;

    public SmartImportTransferService(
        Repositories.ImportSessionRepository sessions,
        Repositories.ImportSessionFileRepository files,
        Repositories.SmartStagedVoucherRepository vouchers,
        Repositories.SmartStagedVoucherItemRepository items,
        Repositories.SmartStagedTransferCandidateRepository candidates,
        Repositories.ProductRepository products,
        Repositories.WarehouseRepository warehouses,
        AuditService audit
    ) {
        this.sessions = sessions;
        this.files = files;
        this.vouchers = vouchers;
        this.items = items;
        this.candidates = candidates;
        this.products = products;
        this.warehouses = warehouses;
        this.audit = audit;
    }

    @Transactional
    public void classifySession(UUID tenantId, UUID sessionId) {
        candidates.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
        var fileTypes = files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(tenantId, sessionId).stream()
            .collect(Collectors.toMap(file -> file.id, this::effectiveType, (left, right) -> left));
        var byVoucher = items.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId).stream()
            .collect(Collectors.groupingBy(item -> item.stagedVoucherId, LinkedHashMap::new, Collectors.toList()));
        long reviewCount = 0;
        long manufacturingCount = 0;
        for (var voucher : vouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(tenantId, sessionId)) {
            if (!isStockJournal(voucher, fileTypes.get(voucher.sessionFileId))) continue;
            var classified = classifyVoucher(tenantId, sessionId, voucher, byVoucher.getOrDefault(voucher.id, List.of()));
            candidates.saveAll(classified);
            reviewCount += classified.stream()
                .filter(row -> row.resolutionStatus == DomainEnums.TransferResolutionStatus.REVIEW_REQUIRED).count();
            manufacturingCount += classified.stream().filter(this::manufacturing).count();
        }
        if (reviewCount > 0) {
            audit.log(tenantId, TenantContext.userId(), "SMART_IMPORT_STOCK_JOURNAL_REVIEW_REQUIRED",
                "ImportSession", sessionId, Map.of("reviewCount", reviewCount));
        }
        if (manufacturingCount > 0) {
            audit.log(tenantId, TenantContext.userId(), "SMART_IMPORT_MANUFACTURING_JOURNAL_DEFERRED",
                "ImportSession", sessionId, Map.of("journalCount", manufacturingCount));
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> review(UUID sessionId) {
        var tenantId = TenantContext.tenantId();
        requireSession(tenantId, sessionId);
        return candidates.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(tenantId, sessionId)
            .stream().map(this::response).toList();
    }

    @Transactional
    public Map<String, Object> resolve(
        UUID sessionId,
        UUID candidateId,
        SmartImportDtos.TransferReviewResolutionRequest request
    ) {
        var tenantId = TenantContext.tenantId();
        requireSession(tenantId, sessionId);
        var candidate = candidates.findByTenantIdAndImportSessionIdAndIdForUpdate(tenantId, sessionId, candidateId)
            .orElseThrow(() -> ApiErrors.notFound("Stock-journal review item not found"));
        switch (request.action()) {
            case KEEP_UNRESOLVED -> {
                candidate.resolutionStatus = DomainEnums.TransferResolutionStatus.REVIEW_REQUIRED;
                candidate.decisionReasons = append(candidate.decisionReasons, "Kept unresolved by user.");
            }
            case MARK_MANUFACTURING_DEFERRED -> {
                candidate.classification = DomainEnums.StockJournalClassification.MIXED_MANUFACTURING_JOURNAL;
                candidate.resolutionStatus = DomainEnums.TransferResolutionStatus.DEFERRED_MANUFACTURING;
                candidate.decisionReasons = append(candidate.decisionReasons, "User marked this journal as manufacturing/deferred.");
                audit.logCurrent("SMART_IMPORT_MANUFACTURING_JOURNAL_DEFERRED",
                    "SmartStagedTransferCandidate", candidate.id, Map.of("sessionId", sessionId));
            }
            case MARK_UNSUPPORTED_ADJUSTMENT -> {
                candidate.classification = DomainEnums.StockJournalClassification.UNBALANCED_STOCK_JOURNAL;
                candidate.resolutionStatus = DomainEnums.TransferResolutionStatus.DEFERRED_ADJUSTMENT;
                candidate.decisionReasons = append(candidate.decisionReasons, "User marked this journal as an unsupported adjustment.");
                audit.logCurrent("SMART_IMPORT_UNBALANCED_STOCK_JOURNAL_DEFERRED",
                    "SmartStagedTransferCandidate", candidate.id, Map.of("sessionId", sessionId));
            }
            case IGNORE -> {
                candidate.resolutionStatus = DomainEnums.TransferResolutionStatus.IGNORED;
                candidate.decisionReasons = append(candidate.decisionReasons, "Ignored as a non-business/no-op journal.");
                audit.logCurrent("SMART_IMPORT_STOCK_JOURNAL_IGNORED",
                    "SmartStagedTransferCandidate", candidate.id, Map.of("sessionId", sessionId));
            }
            case CONFIRM_WAREHOUSE_TRANSFER -> confirmTransfer(tenantId, candidate, request);
        }
        candidate.resolvedBy = TenantContext.userId();
        candidate.resolvedAt = Instant.now();
        candidate.updatedBy = TenantContext.userId();
        candidates.save(candidate);
        return response(candidate);
    }

    public boolean isConfirmed(SmartStagedTransferCandidate candidate) {
        return candidate.classification == DomainEnums.StockJournalClassification.WAREHOUSE_TRANSFER
            && (candidate.resolutionStatus == DomainEnums.TransferResolutionStatus.AUTO_CONFIRMED
                || candidate.resolutionStatus == DomainEnums.TransferResolutionStatus.USER_CONFIRMED);
    }

    public boolean manufacturing(SmartStagedTransferCandidate candidate) {
        return candidate.classification == DomainEnums.StockJournalClassification.MANUFACTURING_CONSUMPTION
            || candidate.classification == DomainEnums.StockJournalClassification.MANUFACTURING_PRODUCTION
            || candidate.classification == DomainEnums.StockJournalClassification.MIXED_MANUFACTURING_JOURNAL;
    }

    public String transferFingerprint(
        UUID tenantId,
        SmartStagedTransferCandidate candidate,
        UUID productId,
        UUID sourceWarehouseId,
        UUID destinationWarehouseId,
        BigDecimal quantity
    ) {
        return sha256(String.join("|",
            tenantId.toString(),
            firstNonBlank(candidate.voucherExternalId, candidate.voucherNumber, "ROW-" + candidate.sourceRowNumber),
            Objects.toString(candidate.voucherDate, ""),
            productId.toString(),
            sourceWarehouseId.toString(),
            destinationWarehouseId.toString(),
            normalizedDecimal(quantity),
            normalize(candidate.unitCode),
            normalize(candidate.batchReference)));
    }

    public String transferLineFingerprint(
        UUID tenantId, SmartStagedTransferCandidate candidate, UUID productId
    ) {
        return sha256(String.join("|",
            tenantId.toString(),
            firstNonBlank(candidate.voucherExternalId, candidate.voucherNumber, "ROW-" + candidate.sourceRowNumber),
            Objects.toString(candidate.voucherDate, ""),
            productId.toString(),
            String.valueOf(candidate.sourceRowNumber),
            normalize(candidate.batchReference)));
    }

    private List<SmartStagedTransferCandidate> classifyVoucher(
        UUID tenantId,
        UUID sessionId,
        SmartStagedVoucher voucher,
        List<SmartStagedVoucherItem> sourceItems
    ) {
        if (sourceItems.isEmpty()) {
            return List.of(deferredCandidate(tenantId, sessionId, voucher, null,
                DomainEnums.StockJournalClassification.UNSUPPORTED_STOCK_JOURNAL,
                DomainEnums.TransferResolutionStatus.REVIEW_REQUIRED,
                "No inventory lines were present in the stock journal."));
        }

        var explicit = sourceItems.stream().filter(item ->
            !value(item, "Source Godown").isBlank() && !value(item, "Destination Godown").isBlank()).toList();
        if (!explicit.isEmpty()) {
            return explicit.stream().map(item -> explicitCandidate(tenantId, sessionId, voucher, item)).toList();
        }

        var grouped = sourceItems.stream().collect(Collectors.groupingBy(
            item -> normalize(item.normalizedProductName == null ? item.productName : item.normalizedProductName)
                + "|" + normalize(item.unitCode),
            LinkedHashMap::new,
            Collectors.toList()));
        var result = new ArrayList<SmartStagedTransferCandidate>();
        var unmatched = new ArrayList<SmartStagedVoucherItem>();
        for (var group : grouped.values()) {
            var outward = group.stream().filter(item -> signedQuantity(item).signum() < 0)
                .sorted(Comparator.comparingInt(item -> item.sourceRowNumber)).toList();
            var inward = group.stream().filter(item -> signedQuantity(item).signum() > 0)
                .sorted(Comparator.comparingInt(item -> item.sourceRowNumber)).toList();
            if (outward.isEmpty() && inward.isEmpty()) {
                result.add(deferredCandidate(tenantId, sessionId, voucher, group.getFirst(),
                    DomainEnums.StockJournalClassification.ZERO_EFFECT_STOCK_JOURNAL,
                    DomainEnums.TransferResolutionStatus.DEFERRED_ADJUSTMENT,
                    "All stock-journal quantities are zero."));
                continue;
            }
            var usedInward = new boolean[inward.size()];
            for (var source : outward) {
                var match = -1;
                for (int index = 0; index < inward.size(); index++) {
                    if (!usedInward[index] && signedQuantity(source).abs().compareTo(signedQuantity(inward.get(index)).abs()) == 0) {
                        match = index;
                        break;
                    }
                }
                if (match < 0) {
                    unmatched.add(source);
                    continue;
                }
                usedInward[match] = true;
                result.add(pairedCandidate(tenantId, sessionId, voucher, source, inward.get(match)));
            }
            for (int index = 0; index < inward.size(); index++) {
                if (!usedInward[index]) unmatched.add(inward.get(index));
            }
        }
        if (!unmatched.isEmpty()) {
            var manufacturing = grouped.size() > 1 || manufacturingEvidence(voucher, sourceItems);
            result.clear();
            result.add(deferredCandidate(tenantId, sessionId, voucher, unmatched.getFirst(),
                manufacturing ? DomainEnums.StockJournalClassification.MIXED_MANUFACTURING_JOURNAL
                    : DomainEnums.StockJournalClassification.UNBALANCED_STOCK_JOURNAL,
                manufacturing ? DomainEnums.TransferResolutionStatus.DEFERRED_MANUFACTURING
                    : DomainEnums.TransferResolutionStatus.REVIEW_REQUIRED,
                manufacturing
                    ? "Different or unmatched inward/outward products indicate a manufacturing journal."
                    : "Outward and inward quantities could not be balanced into transfer pairs."));
        }
        return result;
    }

    private SmartStagedTransferCandidate explicitCandidate(
        UUID tenantId, UUID sessionId, SmartStagedVoucher voucher, SmartStagedVoucherItem item
    ) {
        var candidate = baseCandidate(tenantId, sessionId, voucher, item);
        candidate.sourceItemId = item.id;
        candidate.destinationItemId = item.id;
        candidate.sourceWarehouseName = value(item, "Source Godown");
        candidate.destinationWarehouseName = value(item, "Destination Godown");
        candidate.sourceWarehouseId = warehouseId(tenantId, candidate.sourceWarehouseName);
        candidate.destinationWarehouseId = warehouseId(tenantId, candidate.destinationWarehouseName);
        candidate.matchedProductId = item.matchedProductId;
        candidate.rawOutwardQuantity = signedQuantity(item).abs();
        candidate.rawInwardQuantity = signedQuantity(item).abs();
        candidate.normalizedQuantity = signedQuantity(item).abs();
        finishTransferClassification(tenantId, candidate, item, item);
        return candidate;
    }

    private SmartStagedTransferCandidate pairedCandidate(
        UUID tenantId,
        UUID sessionId,
        SmartStagedVoucher voucher,
        SmartStagedVoucherItem source,
        SmartStagedVoucherItem destination
    ) {
        var candidate = baseCandidate(tenantId, sessionId, voucher, source);
        candidate.sourceItemId = source.id;
        candidate.destinationItemId = destination.id;
        candidate.sourceWarehouseName = source.warehouseName;
        candidate.sourceWarehouseId = source.matchedWarehouseId;
        candidate.destinationWarehouseName = destination.warehouseName;
        candidate.destinationWarehouseId = destination.matchedWarehouseId;
        candidate.matchedProductId = Objects.equals(source.matchedProductId, destination.matchedProductId)
            ? source.matchedProductId : null;
        candidate.rawOutwardQuantity = signedQuantity(source);
        candidate.rawInwardQuantity = signedQuantity(destination);
        candidate.normalizedQuantity = signedQuantity(source).abs();
        candidate.evidenceJson.put("sourceLineSign", signedQuantity(source).signum());
        candidate.evidenceJson.put("destinationLineSign", signedQuantity(destination).signum());
        finishTransferClassification(tenantId, candidate, source, destination);
        return candidate;
    }

    private void finishTransferClassification(
        UUID tenantId,
        SmartStagedTransferCandidate candidate,
        SmartStagedVoucherItem source,
        SmartStagedVoucherItem destination
    ) {
        var reasons = new ArrayList<String>();
        if (candidate.normalizedQuantity == null || candidate.normalizedQuantity.signum() <= 0) {
            reasons.add("TRANSFER_QUANTITY_INVALID");
        }
        if (candidate.matchedProductId == null) reasons.add("TRANSFER_PRODUCT_UNRESOLVED");
        if (candidate.sourceWarehouseName == null || candidate.sourceWarehouseName.isBlank()) {
            reasons.add("TRANSFER_SOURCE_WAREHOUSE_MISSING");
        }
        if (candidate.destinationWarehouseName == null || candidate.destinationWarehouseName.isBlank()) {
            reasons.add("TRANSFER_DESTINATION_WAREHOUSE_MISSING");
        }
        if (candidate.sourceWarehouseId == null) reasons.add("TRANSFER_SOURCE_WAREHOUSE_MISSING");
        if (candidate.destinationWarehouseId == null) reasons.add("TRANSFER_DESTINATION_WAREHOUSE_MISSING");
        if (candidate.sourceWarehouseId != null
            && candidate.sourceWarehouseId.equals(candidate.destinationWarehouseId)) {
            reasons.add("TRANSFER_SOURCE_EQUALS_DESTINATION");
        }
        if (!normalize(source.unitCode).equals(normalize(destination.unitCode))) reasons.add("TRANSFER_UNIT_MISMATCH");
        if (quantityMismatch(source) || quantityMismatch(destination)) reasons.add("BILLED_ACTUAL_QUANTITY_MISMATCH");
        if (!normalize(value(source, "Batch")).equals(normalize(value(destination, "Batch")))) {
            reasons.add("TRANSFER_BATCH_REVIEW_REQUIRED");
        }
        candidate.classification = DomainEnums.StockJournalClassification.WAREHOUSE_TRANSFER;
        candidate.decisionReasons = reasons.isEmpty()
            ? "Balanced same-product outward and inward lines with resolved distinct godowns."
            : String.join(", ", reasons);
        candidate.classificationConfidence = reasons.isEmpty() ? new BigDecimal("0.99") : new BigDecimal("0.45");
        candidate.resolutionStatus = reasons.isEmpty()
            ? DomainEnums.TransferResolutionStatus.AUTO_CONFIRMED
            : DomainEnums.TransferResolutionStatus.REVIEW_REQUIRED;
        if (candidate.matchedProductId != null && candidate.normalizedQuantity != null
            && candidate.sourceWarehouseId != null && candidate.destinationWarehouseId != null) {
            candidate.transferFingerprint = transferFingerprint(tenantId, candidate, candidate.matchedProductId,
                candidate.sourceWarehouseId, candidate.destinationWarehouseId, candidate.normalizedQuantity);
            candidate.transferLineFingerprint = transferLineFingerprint(tenantId, candidate, candidate.matchedProductId);
        }
    }

    private SmartStagedTransferCandidate deferredCandidate(
        UUID tenantId,
        UUID sessionId,
        SmartStagedVoucher voucher,
        SmartStagedVoucherItem item,
        DomainEnums.StockJournalClassification classification,
        DomainEnums.TransferResolutionStatus status,
        String reason
    ) {
        var candidate = baseCandidate(tenantId, sessionId, voucher, item);
        candidate.classification = classification;
        candidate.classificationConfidence = new BigDecimal("0.90");
        candidate.resolutionStatus = status;
        candidate.decisionReasons = reason;
        return candidate;
    }

    private SmartStagedTransferCandidate baseCandidate(
        UUID tenantId, UUID sessionId, SmartStagedVoucher voucher, SmartStagedVoucherItem item
    ) {
        var candidate = new SmartStagedTransferCandidate();
        candidate.tenantId = tenantId;
        candidate.importSessionId = sessionId;
        candidate.stagedVoucherId = voucher.id;
        candidate.sessionFileId = voucher.sessionFileId;
        candidate.sourceRowNumber = item == null ? voucher.sourceRowNumber : item.sourceRowNumber;
        candidate.voucherExternalId = voucher.externalId;
        candidate.voucherNumber = voucher.voucherNumber;
        candidate.voucherDate = voucher.voucherDate;
        candidate.productName = item == null ? null : item.productName;
        candidate.normalizedProductName = item == null ? null : item.normalizedProductName;
        candidate.matchedProductId = item == null ? null : item.matchedProductId;
        candidate.unitCode = item == null ? null : item.unitCode;
        candidate.batchReference = item == null ? null : firstNonBlank(value(item, "Batch"), value(item, "Tracking Number"));
        candidate.createdBy = TenantContext.userId();
        candidate.updatedBy = TenantContext.userId();
        candidate.evidenceJson.put("voucherType", voucher.voucherType);
        candidate.evidenceJson.put("voucherExternalId", voucher.externalId);
        candidate.evidenceJson.put("voucherNumber", voucher.voucherNumber);
        candidate.evidenceJson.put("voucherDate", voucher.voucherDate);
        if (item != null) candidate.evidenceJson.put("sourceMetadata", item.rawMetadataJson);
        return candidate;
    }

    private void confirmTransfer(
        UUID tenantId,
        SmartStagedTransferCandidate candidate,
        SmartImportDtos.TransferReviewResolutionRequest request
    ) {
        if (!CONFIRMATION.equals(request.confirmation())) {
            throw ApiErrors.badRequest("Enter POST STOCK TRANSFER to confirm warehouse transfer posting");
        }
        if (request.productId() == null || request.sourceWarehouseId() == null
            || request.destinationWarehouseId() == null || request.quantity() == null
            || request.quantity().signum() <= 0) {
            throw ApiErrors.badRequest("Product, source warehouse, destination warehouse, and positive quantity are required");
        }
        var product = products.findByTenantIdAndId(tenantId, request.productId())
            .orElseThrow(() -> ApiErrors.badRequest("Selected product is not available in this workspace"));
        var source = warehouses.findByTenantIdAndId(tenantId, request.sourceWarehouseId())
            .orElseThrow(() -> ApiErrors.badRequest("Selected source warehouse is not available in this workspace"));
        var destination = warehouses.findByTenantIdAndId(tenantId, request.destinationWarehouseId())
            .orElseThrow(() -> ApiErrors.badRequest("Selected destination warehouse is not available in this workspace"));
        if (source.id.equals(destination.id)) {
            throw ApiErrors.badRequest("Source and destination warehouses must be different");
        }
        candidate.productName = product.name;
        candidate.normalizedProductName = normalize(product.name);
        candidate.matchedProductId = product.id;
        candidate.sourceWarehouseName = source.name;
        candidate.sourceWarehouseId = source.id;
        candidate.destinationWarehouseName = destination.name;
        candidate.destinationWarehouseId = destination.id;
        candidate.rawOutwardQuantity = request.quantity().abs().negate();
        candidate.rawInwardQuantity = request.quantity().abs();
        candidate.normalizedQuantity = request.quantity().abs();
        candidate.classification = DomainEnums.StockJournalClassification.WAREHOUSE_TRANSFER;
        candidate.classificationConfidence = BigDecimal.ONE;
        candidate.resolutionStatus = DomainEnums.TransferResolutionStatus.USER_CONFIRMED;
        candidate.decisionReasons = append(candidate.decisionReasons, "Warehouse transfer confirmed by user.");
        candidate.transferFingerprint = transferFingerprint(tenantId, candidate, product.id,
            source.id, destination.id, candidate.normalizedQuantity);
        candidate.transferLineFingerprint = transferLineFingerprint(tenantId, candidate, product.id);
        audit.logCurrent("SMART_IMPORT_WAREHOUSE_TRANSFER_MANUALLY_RESOLVED",
            "SmartStagedTransferCandidate", candidate.id, Map.of(
                "sessionId", candidate.importSessionId,
                "sourceWarehouseId", source.id,
                "destinationWarehouseId", destination.id,
                "productId", product.id,
                "quantity", candidate.normalizedQuantity,
                "resolutionSource", "USER"));
    }

    private boolean isStockJournal(SmartStagedVoucher voucher, DomainEnums.DetectedFileType fileType) {
        var type = normalize(voucher.voucherType);
        return fileType == DomainEnums.DetectedFileType.STOCK_JOURNAL
            || type.contains("STOCK JOURNAL") || type.contains("STOCKJOURNAL");
    }

    private DomainEnums.DetectedFileType effectiveType(ImportSessionFile file) {
        return file.selectedFileType == null ? file.detectedFileType : file.selectedFileType;
    }

    private boolean manufacturingEvidence(SmartStagedVoucher voucher, List<SmartStagedVoucherItem> sourceItems) {
        var text = normalize(voucher.voucherType) + " " + normalize(String.valueOf(voucher.rawMetadataJson));
        for (var item : sourceItems) text += " " + normalize(String.valueOf(item.rawMetadataJson));
        return containsAny(text, "PRODUCTION", "CONSUMPTION", "BOM", "SCRAP", "BY PRODUCT",
            "BY-PRODUCT", "YIELD", "WIP", "MANUFACTUR");
    }

    private boolean quantityMismatch(SmartStagedVoucherItem item) {
        return item.billedQuantity != null && item.actualQuantity != null
            && item.billedQuantity.abs().compareTo(item.actualQuantity.abs()) != 0;
    }

    private BigDecimal signedQuantity(SmartStagedVoucherItem item) {
        if (item.rawQuantity != null && item.rawQuantity.signum() != 0) return item.rawQuantity;
        if (item.actualQuantity != null && item.actualQuantity.signum() != 0) return item.actualQuantity;
        if (item.billedQuantity != null && item.billedQuantity.signum() != 0) return item.billedQuantity;
        return ZERO;
    }

    private UUID warehouseId(UUID tenantId, String name) {
        if (name == null || name.isBlank()) return null;
        return warehouses.findByTenantIdAndNameIgnoreCase(tenantId, name.trim()).map(row -> row.id).orElse(null);
    }

    private String value(SmartStagedVoucherItem item, String key) {
        if (item == null || item.rawMetadataJson == null) return "";
        return Objects.toString(item.rawMetadataJson.get(key), "").trim();
    }

    private Map<String, Object> response(SmartStagedTransferCandidate candidate) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", candidate.id);
        result.put("importSessionId", candidate.importSessionId);
        result.put("stagedVoucherId", candidate.stagedVoucherId);
        result.put("sourceFileId", candidate.sessionFileId);
        result.put("sourceRowNumber", candidate.sourceRowNumber);
        result.put("voucherExternalId", nullSafe(candidate.voucherExternalId));
        result.put("voucherNumber", nullSafe(candidate.voucherNumber));
        result.put("voucherDate", candidate.voucherDate == null ? "" : candidate.voucherDate);
        result.put("productId", candidate.matchedProductId == null ? "" : candidate.matchedProductId);
        result.put("productName", nullSafe(candidate.productName));
        result.put("unitCode", nullSafe(candidate.unitCode));
        result.put("sourceWarehouseId", candidate.sourceWarehouseId == null ? "" : candidate.sourceWarehouseId);
        result.put("sourceWarehouseName", nullSafe(candidate.sourceWarehouseName));
        result.put("destinationWarehouseId", candidate.destinationWarehouseId == null ? "" : candidate.destinationWarehouseId);
        result.put("destinationWarehouseName", nullSafe(candidate.destinationWarehouseName));
        result.put("rawOutwardQuantity", candidate.rawOutwardQuantity == null ? ZERO : candidate.rawOutwardQuantity);
        result.put("rawInwardQuantity", candidate.rawInwardQuantity == null ? ZERO : candidate.rawInwardQuantity);
        result.put("normalizedQuantity", candidate.normalizedQuantity == null ? ZERO : candidate.normalizedQuantity);
        result.put("classification", candidate.classification);
        result.put("classificationConfidence", candidate.classificationConfidence);
        result.put("decisionReasons", nullSafe(candidate.decisionReasons));
        result.put("resolutionStatus", candidate.resolutionStatus);
        result.put("sourceSnapshotDate", candidate.sourceSnapshotDate == null ? "" : candidate.sourceSnapshotDate);
        result.put("destinationSnapshotDate", candidate.destinationSnapshotDate == null ? "" : candidate.destinationSnapshotDate);
        result.put("evidence", candidate.evidenceJson);
        return result;
    }

    private void requireSession(UUID tenantId, UUID sessionId) {
        sessions.findByTenantIdAndId(tenantId, sessionId)
            .orElseThrow(() -> ApiErrors.notFound("Import session not found"));
    }

    private String append(String existing, String value) {
        return existing == null || existing.isBlank() ? value : existing + " " + value;
    }

    private boolean containsAny(String value, String... needles) {
        for (var needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private String firstNonBlank(String... values) {
        for (var value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private String normalizedDecimal(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
