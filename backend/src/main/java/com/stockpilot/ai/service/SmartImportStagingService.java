package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SmartImportStagingService {
    private final ImportExecutionGuard executionGuard;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int PREVIEW_LIMIT = 100;

    private final Repositories.ImportSessionRepository sessions;
    private final Repositories.ImportSessionFileRepository files;
    private final Repositories.ImportPlanRepository plans;
    private final Repositories.ImportPlanIssueRepository issues;
    private final Repositories.SmartStagedProductRepository stagedProducts;
    private final Repositories.SmartStagedPartyRepository stagedParties;
    private final Repositories.SmartStagedWarehouseRepository stagedWarehouses;
    private final Repositories.SmartStagedUnitRepository stagedUnits;
    private final Repositories.SmartStagedStockSnapshotRepository stagedSnapshots;
    private final Repositories.SmartStagedVoucherRepository stagedVouchers;
    private final Repositories.SmartStagedVoucherItemRepository stagedVoucherItems;
    private final Repositories.SmartStagedTransferCandidateRepository stagedTransfers;
    private final Repositories.SmartStagedCashbookEntryRepository stagedCashbook;
    private final Repositories.SmartStagedStockAgeingRepository stagedAgeing;
    private final Repositories.SmartImportResolutionRepository resolutions;
    private final Repositories.ImportDryRunRepository dryRuns;
    private final Repositories.ImportDryRunItemRepository dryRunItems;
    private final Repositories.ProductRepository products;
    private final Repositories.ProductBarcodeRepository barcodes;
    private final Repositories.UnitRepository units;
    private final Repositories.CustomerRepository customers;
    private final Repositories.SupplierRepository suppliers;
    private final Repositories.WarehouseRepository warehouses;
    private final Repositories.SalesInvoiceRepository salesInvoices;
    private final Repositories.PurchaseInvoiceRepository purchaseInvoices;
    private final Repositories.FinancialAdjustmentRepository financialAdjustments;
    private final SmartImportCanonicalParserService parser;
    private final ImportPlanBuilderService planBuilder;
    private final StockLedgerService stockLedger;
    private final SmartImportReturnService returnService;
    private final SmartImportTransferService transferService;
    private final ObjectStorageService storage;
    private final AuditService audit;
    private final RetailCounterSaleService retailSales;
    private final SmartImportCashbookMatchingService cashbookMatching;
    private final InvoiceIdentityService invoiceIdentity;

    private final SourceDocumentRevisionService sourceRevisions;

    public SmartImportStagingService(
        Repositories.ImportSessionRepository sessions,
        Repositories.ImportSessionFileRepository files,
        Repositories.ImportPlanRepository plans,
        Repositories.ImportPlanIssueRepository issues,
        Repositories.SmartStagedProductRepository stagedProducts,
        Repositories.SmartStagedPartyRepository stagedParties,
        Repositories.SmartStagedWarehouseRepository stagedWarehouses,
        Repositories.SmartStagedUnitRepository stagedUnits,
        Repositories.SmartStagedStockSnapshotRepository stagedSnapshots,
        Repositories.SmartStagedVoucherRepository stagedVouchers,
        Repositories.SmartStagedVoucherItemRepository stagedVoucherItems,
        Repositories.SmartStagedTransferCandidateRepository stagedTransfers,
        Repositories.SmartStagedCashbookEntryRepository stagedCashbook,
        Repositories.SmartStagedStockAgeingRepository stagedAgeing,
        Repositories.SmartImportResolutionRepository resolutions,
        Repositories.ImportDryRunRepository dryRuns,
        Repositories.ImportDryRunItemRepository dryRunItems,
        Repositories.ProductRepository products,
        Repositories.ProductBarcodeRepository barcodes,
        Repositories.UnitRepository units,
        Repositories.CustomerRepository customers,
        Repositories.SupplierRepository suppliers,
        Repositories.WarehouseRepository warehouses,
        Repositories.SalesInvoiceRepository salesInvoices,
        Repositories.PurchaseInvoiceRepository purchaseInvoices,
        Repositories.FinancialAdjustmentRepository financialAdjustments,
        SmartImportCanonicalParserService parser,
        ImportPlanBuilderService planBuilder,
        StockLedgerService stockLedger,
        SmartImportReturnService returnService,
        SmartImportTransferService transferService,
        ObjectStorageService storage,
        AuditService audit,
        RetailCounterSaleService retailSales,
        SmartImportCashbookMatchingService cashbookMatching,
        InvoiceIdentityService invoiceIdentity, SourceDocumentRevisionService sourceRevisions, ImportExecutionGuard executionGuard
    ) {
        this.executionGuard = executionGuard;
        this.sourceRevisions = sourceRevisions;
        this.sessions = sessions;
        this.files = files;
        this.plans = plans;
        this.issues = issues;
        this.stagedProducts = stagedProducts;
        this.stagedParties = stagedParties;
        this.stagedWarehouses = stagedWarehouses;
        this.stagedUnits = stagedUnits;
        this.stagedSnapshots = stagedSnapshots;
        this.stagedVouchers = stagedVouchers;
        this.stagedVoucherItems = stagedVoucherItems;
        this.stagedTransfers = stagedTransfers;
        this.stagedCashbook = stagedCashbook;
        this.stagedAgeing = stagedAgeing;
        this.resolutions = resolutions;
        this.dryRuns = dryRuns;
        this.dryRunItems = dryRunItems;
        this.products = products;
        this.barcodes = barcodes;
        this.units = units;
        this.customers = customers;
        this.suppliers = suppliers;
        this.warehouses = warehouses;
        this.salesInvoices = salesInvoices;
        this.purchaseInvoices = purchaseInvoices;
        this.financialAdjustments = financialAdjustments;
        this.parser = parser;
        this.planBuilder = planBuilder;
        this.stockLedger = stockLedger;
        this.returnService = returnService;
        this.transferService = transferService;
        this.storage = storage;
        this.audit = audit;
        this.retailSales = retailSales;
        this.cashbookMatching = cashbookMatching;
        this.invoiceIdentity = invoiceIdentity;
    }

    @Transactional
    public Map<String, Object> stage(UUID sessionId) {
        return rebuild(sessionId);
    }

    @Transactional
    public Map<String, Object> rebuild(UUID sessionId) {
        executionGuard.lockAndAuthorize("STAGE", sessionId);
        retailSales.lockCashbook(TenantContext.tenantId());
        var session = session(sessionId);
        ensureMutable(session);
        var sessionFiles = files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id);
        if (sessionFiles.isEmpty()) {
            throw ApiErrors.badRequest("Upload files before staging the session");
        }
        if (sessionFiles.stream().anyMatch(file -> file.status == DomainEnums.ImportSessionFileStatus.UPLOADED)) {
            throw ApiErrors.badRequest("Classify uploaded files before staging the session");
        }

        clearStaging(session.tenantId, session.id);
        var parseFailures = new ArrayList<ParseFailure>();
        var productIndex = new LinkedHashMap<String, SmartStagedProduct>();
        var partyIndex = new LinkedHashMap<String, SmartStagedParty>();
        var warehouseIndex = new LinkedHashMap<String, SmartStagedWarehouse>();
        var unitIndex = new LinkedHashMap<String, SmartStagedUnit>();
        var voucherIndex = new LinkedHashMap<String, SmartStagedVoucher>();
        var resolutionMap = resolutions.findByTenantIdAndImportSessionId(session.tenantId, session.id).stream()
            .collect(java.util.stream.Collectors.toMap(row -> row.resolutionKey, row -> row, (left, right) -> right));

        for (var file : sessionFiles) {
            if (file.status == DomainEnums.ImportSessionFileStatus.DUPLICATE || effectiveType(file) == DomainEnums.DetectedFileType.UNKNOWN) {
                continue;
            }
            try {
                var rows = parser.parse(session.tenantId, session.id, file);
                boolean invalidBounds = false;
                for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                    for (var violation : ImportFieldBounds.validate(rows.get(rowIndex))) {
                        parseFailures.add(new ParseFailure(file, rowIndex + 1, "FINAL_FIELD_BOUNDS", violation.message()));
                        invalidBounds = true;
                    }
                }
                if (invalidBounds) continue;
                stageRows(session, file, rows, productIndex, partyIndex, warehouseIndex, unitIndex, voucherIndex,
                    resolutionMap);
            } catch (Exception ex) {
                parseFailures.add(new ParseFailure(file, 0, "STAGING_PARSE_FAILED", safeMessage(ex)));
            }
        }

        matchCanonicalRecords(session);
        planBuilder.build(session);
        var generated = buildReviewItems(session, parseFailures);
        issues.saveAll(generated);
        applySessionReviewState(session);
        audit.logCurrent("SMART_IMPORT_STAGED", "ImportSession", session.id, summary(session));
        return workspace(session.id);
    }

    @Transactional
    public Map<String, Object> overrideFileType(UUID sessionId, UUID fileId, DomainEnums.DetectedFileType selectedType) {
        var session = session(sessionId);
        ensureMutable(session);
        var file = files.findByTenantIdAndImportSessionIdAndId(session.tenantId, session.id, fileId)
            .orElseThrow(() -> ApiErrors.notFound("Import session file not found"));
        file.selectedFileType = Objects.requireNonNull(selectedType, "selectedFileType");
        file.status = DomainEnums.ImportSessionFileStatus.CLASSIFIED;
        file.confidence = BigDecimal.ONE;
        file.detectionReason = "File type manually selected as " + selectedType.name() + ".";
        file.updatedBy = TenantContext.userId();
        files.save(file);
        audit.logCurrent("SMART_IMPORT_FILE_TYPE_OVERRIDDEN", "ImportSessionFile", file.id, Map.of("selectedFileType", selectedType.name()));
        return rebuild(session.id);
    }

    @Transactional
    public Map<String, Object> removeFile(UUID sessionId, UUID fileId) {
        var session = session(sessionId);
        ensureMutable(session);
        var file = files.findByTenantIdAndImportSessionIdAndId(session.tenantId, session.id, fileId)
            .orElseThrow(() -> ApiErrors.notFound("Import session file not found"));
        var removedFileName = file.originalFileName;
        var removedStorageKey = file.storageKey;
        clearStaging(session.tenantId, session.id);
        issues.deleteByTenantIdAndImportSessionId(session.tenantId, session.id);
        plans.findByTenantIdAndImportSessionId(session.tenantId, session.id).ifPresent(plans::delete);
        files.delete(file);
        files.flush();
        if (removedStorageKey != null && !removedStorageKey.isBlank()) {
            try {
                storage.delete(removedStorageKey);
            } catch (IOException ex) {
                audit.logCurrent("SMART_IMPORT_FILE_STORAGE_DELETE_FAILED", "ImportSessionFile", fileId, Map.of(
                    "fileName", removedFileName,
                    "storageKey", removedStorageKey,
                    "error", safeMessage(ex)
                ));
            }
        }
        var remaining = files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id);
        if (remaining.isEmpty()) {
            session.status = DomainEnums.ImportSessionStatus.CREATED;
            session.recommendedStrategy = DomainEnums.ImportStrategy.UNKNOWN;
            sessions.save(session);
        } else if (remaining.stream().anyMatch(row -> row.status == DomainEnums.ImportSessionFileStatus.UPLOADED)) {
            session.status = DomainEnums.ImportSessionStatus.FILES_UPLOADED;
            session.recommendedStrategy = DomainEnums.ImportStrategy.UNKNOWN;
            sessions.save(session);
        } else {
            session.recommendedStrategy = DomainEnums.ImportStrategy.UNKNOWN;
            planBuilder.rebuildClassificationIssues(session, remaining);
        }
        audit.logCurrent("SMART_IMPORT_FILE_REMOVED", "ImportSessionFile", fileId, Map.of(
            "fileName", removedFileName,
            "remainingFileCount", remaining.size()
        ));
        return workspace(session.id);
    }

    @Transactional
    public Map<String, Object> resolve(UUID sessionId, UUID itemId, String action, UUID targetId, DomainEnums.DetectedFileType selectedFileType) {
        var session = session(sessionId);
        ensureMutable(session);
        var item = issues.findByTenantIdAndImportSessionIdAndId(session.tenantId, session.id, itemId)
            .orElseThrow(() -> ApiErrors.notFound("Review item not found"));
        if ("INVALID_RATE".equals(item.code) || "ZERO_RATE_ITEM".equals(item.code)) {
            throw ApiErrors.badRequest("Use the voucher rate review endpoint for this item");
        }
        var normalizedAction = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        var allowed = item.availableChoices.stream().map(choice -> String.valueOf(choice.get("action"))).toList();
        if (!allowed.contains(normalizedAction)) {
            throw ApiErrors.badRequest("This review item does not support the selected resolution");
        }
        if ("OVERRIDE_FILE_TYPE".equals(normalizedAction)) {
            if (selectedFileType == null || item.affectedFileId == null) {
                throw ApiErrors.badRequest("Select a file type for this resolution");
            }
            return overrideFileType(session.id, item.affectedFileId, selectedFileType);
        }

        var resolutionKey = String.valueOf(item.contextJson.getOrDefault("resolutionKey", "ISSUE:" + item.id));
        validateResolutionTarget(session.tenantId, normalizedAction, targetId);
        var resolution = resolutions.findByTenantIdAndImportSessionIdAndResolutionKey(session.tenantId, session.id, resolutionKey)
            .orElseGet(SmartImportResolution::new);
        resolution.tenantId = session.tenantId;
        resolution.importSessionId = session.id;
        resolution.resolutionKey = resolutionKey;
        resolution.action = normalizedAction;
        resolution.targetId = targetId;
        resolution.detailsJson = new LinkedHashMap<>(Map.of("sourceIssueCode", item.code));
        resolution.updatedBy = TenantContext.userId();
        if (resolution.createdBy == null) {
            resolution.createdBy = TenantContext.userId();
        }
        resolutions.save(resolution);
        item.resolved = true;
        item.resolutionJson = new LinkedHashMap<>();
        item.resolutionJson.put("action", normalizedAction);
        if (targetId != null) {
            item.resolutionJson.put("targetId", targetId.toString());
        }
        issues.save(item);
        audit.logCurrent("SMART_IMPORT_REVIEW_RESOLVED", "ImportPlanIssue", item.id, item.resolutionJson);
        return rebuild(session.id);
    }

    @Transactional
    public void lockRateReview(UUID sessionId) {
        retailSales.lockCashbook(TenantContext.tenantId());
        ensureMutable(session(sessionId));
    }

    @Transactional
    public Map<String, Object> applyVoucherExclusions(UUID sessionId, List<ImportPlanIssue> selected, String reason) {
        lockRateReview(sessionId);
        var session = session(sessionId);
        var vouchers = stagedVouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(session.tenantId, session.id);
        var selectedIds = selected.stream().map(issue -> UUID.fromString(
            Objects.toString(issue.contextJson.get("voucherId")))).collect(Collectors.toSet());
        var excluded = vouchers.stream().filter(row -> selectedIds.contains(row.id)).toList();
        if (excluded.size() != selectedIds.size()) throw ApiErrors.conflict("Staged vouchers changed. Refresh the review queue.");
        for (var voucher : excluded) {
            voucher.rawMetadataJson.put("excludedFromCommit", true);
            voucher.rawMetadataJson.put("exclusionReason", reason == null ? "" : reason.trim());
            voucher.rawMetadataJson.put("voucherResolutionKey", voucherResolutionKey(voucher));
            voucher.reviewStatus = DomainEnums.SmartReviewStatus.IGNORED;
        }
        stagedVouchers.saveAll(excluded);

        var items = stagedVoucherItems.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var removedItems = items.stream().filter(row -> selectedIds.contains(row.stagedVoucherId)).toList();
        var retainedItems = items.stream().filter(row -> !selectedIds.contains(row.stagedVoucherId)).toList();
        var removedIds = new HashSet<UUID>(selectedIds);
        removedItems.forEach(row -> removedIds.add(row.id));
        var excludedSources = new HashSet<String>();
        removedItems.forEach(row -> excludedSources.add(row.sessionFileId + ":" + row.sourceRowNumber));
        excluded.forEach(row -> excludedSources.add(row.sessionFileId + ":" + row.sourceRowNumber));

        // A previous dry run must never be committed after changing voucher eligibility.
        dryRunItems.deleteByTenantIdAndImportSessionId(session.tenantId, session.id);
        dryRuns.deleteByTenantIdAndImportSessionId(session.tenantId, session.id);
        stagedVoucherItems.deleteAll(removedItems);
        pruneExcludedCandidates(session, vouchers, retainedItems, excludedSources, removedIds);
        var obsoleteIssues = issues.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id).stream()
            .filter(issue -> removedIds.stream().anyMatch(id -> id.toString().equals(
                Objects.toString(issue.contextJson.get("stagingId"), ""))
                || id.toString().equals(Objects.toString(issue.contextJson.get("voucherId"), ""))))
            .toList();
        issues.deleteAll(obsoleteIssues);
        applySessionReviewState(session);
        return workspace(session.id);
    }

    @Transactional
    public Map<String, Object> applyVoucherRates(UUID sessionId, List<ImportPlanIssue> selected) {
        lockRateReview(sessionId);
        var session = session(sessionId);
        var vouchers = stagedVouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(session.tenantId, session.id);
        var byId = vouchers.stream().collect(Collectors.toMap(row -> row.id, row -> row));
        var affectedFingerprints = new HashSet<String>();
        for (var issue : selected) {
            var itemId = UUID.fromString(Objects.toString(issue.contextJson.get("stagingId")));
            var item = stagedVoucherItems.findByTenantIdAndImportSessionIdAndId(session.tenantId, session.id, itemId)
                .orElseThrow(() -> ApiErrors.conflict("Staged item changed. Refresh the review queue."));
            var voucher = byId.get(item.stagedVoucherId);
            if (voucher == null || excludedVoucher(voucher)) throw ApiErrors.conflict("This voucher is no longer available for rate review.");
            var resolution = resolutions.findByTenantIdAndImportSessionIdAndResolutionKey(session.tenantId, session.id,
                rateResolutionKey(item.sessionFileId, item.sourceRowNumber)).orElseThrow();
            var before = stagedLineValue(item);
            affectedFingerprints.add(voucher.fingerprint);
            applyRateResolution(item, resolution);
            if (item.rate == null || item.rate.signum() < 0 || "INVALID".equalsIgnoreCase(item.rateSource)) {
                throw ApiErrors.badRequest("The saved resolution cannot be applied to this staged line.");
            }
            var delta = stagedLineValue(item).subtract(before);
            voucher.totalAmount = nvl(voucher.totalAmount).add(delta);
            voucher.rawMetadataJson.put("inventoryLineAmountTotal",
                metadataDecimal(voucher.rawMetadataJson, "inventoryLineAmountTotal").add(delta).toPlainString());
            voucher.fingerprint = fingerprint(session.tenantId, voucher);
            affectedFingerprints.add(voucher.fingerprint);
            stagedVoucherItems.save(item);
        }
        // Recheck both old and new duplicate groups without reparsing files or replacing staging IDs.
        var parties = stagedParties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var seen = new HashSet<String>();
        var changed = new ArrayList<SmartStagedVoucher>();
        for (var voucher : vouchers) {
            if (excludedVoucher(voucher)) continue;
            var repeated = voucher.fingerprint != null && !seen.add(voucher.fingerprint);
            if (!affectedFingerprints.contains(voucher.fingerprint)) continue;
            var party = parties.stream().filter(row -> Objects.equals(row.normalizedName, voucher.normalizedPartyName)).findFirst().orElse(null);
            updateVoucherMatch(session.tenantId, voucher, party, repeated);
            changed.add(voucher);
        }
        stagedVouchers.saveAll(changed);
        var changedIds = changed.stream().map(row -> row.id.toString()).collect(Collectors.toSet());
        var obsolete = issues.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id).stream()
            .filter(issue -> "VOUCHER".equals(issue.contextJson.get("entityType"))
                && changedIds.contains(Objects.toString(issue.contextJson.get("stagingId"), ""))).toList();
        issues.deleteAll(obsolete);
        issues.deleteAll(selected);
        for (var voucher : changed) {
            if (voucher.reviewStatus != DomainEnums.SmartReviewStatus.PENDING) continue;
            var conflict = voucher.matchStatus == DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW;
            issues.save(reviewIssue(session, voucher.sessionFileId, voucher.sourceRowNumber,
                conflict ? DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED : DomainEnums.ImportPlanIssueSeverity.ERROR,
                conflict ? "DUPLICATE_VOUCHER_CONFLICT" : "MISSING_REQUIRED_IDENTITY",
                conflict ? "Voucher identity or amount requires review: " + nullSafe(voucher.voucherNumber)
                    : "Voucher is missing number, date, or party identity.",
                "Review the voucher identity before commit.", List.of(),
                Map.of("entityType", "VOUCHER", "stagingId", voucher.id.toString())));
        }
        dryRunItems.deleteByTenantIdAndImportSessionId(session.tenantId, session.id);
        dryRuns.deleteByTenantIdAndImportSessionId(session.tenantId, session.id);
        applySessionReviewState(session);
        return workspace(session.id);
    }

    private BigDecimal stagedLineValue(SmartStagedVoucherItem item) {
        return item.amount != null ? item.amount.abs()
            : item.quantity != null && item.rate != null ? item.quantity.multiply(item.rate).abs() : ZERO;
    }

    private void pruneExcludedCandidates(
        ImportSession session, List<SmartStagedVoucher> vouchers, List<SmartStagedVoucherItem> retainedItems,
        Set<String> excludedSources, Set<UUID> removedIds
    ) {
        var snapshots = stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var ageing = stagedAgeing.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var usedProducts = new HashSet<String>();
        retainedItems.forEach(row -> usedProducts.add(productKey(row.normalizedProductName, row.unitCode)));
        snapshots.forEach(row -> usedProducts.add(productKey(row.normalizedProductName, row.unitCode)));
        ageing.forEach(row -> usedProducts.add(productKey(row.normalizedProductName, row.unitCode)));
        var sourceFiles = files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id);
        // Master declarations can share a candidate first encountered in a voucher file.
        var hasInventoryMaster = sourceFiles.stream().anyMatch(file -> effectiveType(file) == DomainEnums.DetectedFileType.INVENTORY_MASTER
            || effectiveType(file) == DomainEnums.DetectedFileType.MIXED_TALLY_MASTERS);
        var hasPartyMaster = sourceFiles.stream().anyMatch(file -> effectiveType(file) == DomainEnums.DetectedFileType.ACCOUNTING_MASTER
            || effectiveType(file) == DomainEnums.DetectedFileType.MIXED_TALLY_MASTERS);
        var productRows = stagedProducts.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var orphanProducts = productRows.stream().filter(row -> !hasInventoryMaster
            && excludedSources.contains(row.sessionFileId + ":" + row.sourceRowNumber)
            && !usedProducts.contains(productKey(row.normalizedName, row.unitCode))).toList();
        orphanProducts.forEach(row -> removedIds.add(row.id));
        stagedProducts.deleteAll(orphanProducts);

        var usedParties = new HashSet<String>();
        vouchers.stream().filter(row -> !excludedVoucher(row)).forEach(row -> usedParties.add(row.normalizedPartyName));
        stagedCashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(session.tenantId, session.id)
            .forEach(row -> usedParties.add(normalizeName(row.partyName)));
        var orphanParties = stagedParties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id).stream()
            .filter(row -> !hasPartyMaster && row.sourceType != DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS
                && excludedSources.contains(row.sessionFileId + ":" + row.sourceRowNumber)
                && !usedParties.contains(row.normalizedName)).toList();
        orphanParties.forEach(row -> removedIds.add(row.id));
        stagedParties.deleteAll(orphanParties);

        var usedUnits = new HashSet<String>();
        var usedWarehouses = new HashSet<String>();
        retainedItems.forEach(row -> { usedUnits.add(row.unitCode); usedWarehouses.add(normalizeName(row.warehouseName)); });
        snapshots.forEach(row -> { usedUnits.add(row.unitCode); usedWarehouses.add(normalizeName(row.warehouseName)); });
        ageing.forEach(row -> usedUnits.add(row.unitCode));
        productRows.stream().filter(row -> !removedIds.contains(row.id)).forEach(row -> usedUnits.add(row.unitCode));
        var orphanUnits = stagedUnits.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id).stream()
            .filter(row -> !hasInventoryMaster && excludedSources.contains(row.sessionFileId + ":" + row.sourceRowNumber)
                && !usedUnits.contains(row.normalizedCode)).toList();
        orphanUnits.forEach(row -> removedIds.add(row.id));
        stagedUnits.deleteAll(orphanUnits);
        var orphanWarehouses = stagedWarehouses.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id).stream()
            .filter(row -> !hasInventoryMaster && excludedSources.contains(row.sessionFileId + ":" + row.sourceRowNumber)
                && !usedWarehouses.contains(row.normalizedName)).toList();
        orphanWarehouses.forEach(row -> removedIds.add(row.id));
        stagedWarehouses.deleteAll(orphanWarehouses);
    }

    public List<Map<String, Object>> reviewItems(UUID sessionId) {
        var session = session(sessionId);
        var names = files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id).stream()
            .collect(java.util.stream.Collectors.toMap(file -> file.id, file -> file.originalFileName));
        return issues.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id).stream()
            .filter(issue -> issue.severity != DomainEnums.ImportPlanIssueSeverity.INFO)
            .map(issue -> reviewItem(issue, names.get(issue.affectedFileId)))
            .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> workspace(UUID sessionId) {
        var session = session(sessionId);
        var response = new LinkedHashMap<String, Object>();
        response.put("summary", summary(session));
        response.put("matching", matchingSummary(session));
        response.put("products", stagedProducts.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id).stream().limit(PREVIEW_LIMIT).map(this::productRow).toList());
        response.put("parties", stagedParties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id).stream().limit(PREVIEW_LIMIT).map(this::partyRow).toList());
        response.put("warehouses", stagedWarehouses.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id).stream().limit(PREVIEW_LIMIT).map(this::warehouseRow).toList());
        response.put("units", stagedUnits.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id).stream().limit(PREVIEW_LIMIT).map(this::unitRow).toList());
        response.put("snapshots", stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id).stream().limit(PREVIEW_LIMIT).map(this::snapshotRow).toList());
        response.put("vouchers", stagedVouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(session.tenantId, session.id).stream().limit(PREVIEW_LIMIT).map(this::voucherRow).toList());
        response.put("voucherItems", stagedVoucherItems.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id).stream().limit(PREVIEW_LIMIT).map(this::voucherItemRow).toList());
        response.put("transferCandidates", stagedTransfers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(
            session.tenantId, session.id).stream().limit(PREVIEW_LIMIT).map(this::transferRow).toList());
        response.put("cashbookEntries", stagedCashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(session.tenantId, session.id).stream().limit(PREVIEW_LIMIT).map(this::cashbookRow).toList());
        response.put("stockAgeing", stagedAgeing.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id).stream().limit(PREVIEW_LIMIT).map(this::ageingRow).toList());
        response.put("reviewItems", reviewItems(session.id));
        response.put("previewLimit", PREVIEW_LIMIT);
        return response;
    }

    private void stageRows(
        ImportSession session,
        ImportSessionFile file,
        List<Map<String, String>> rows,
        Map<String, SmartStagedProduct> productIndex,
        Map<String, SmartStagedParty> partyIndex,
        Map<String, SmartStagedWarehouse> warehouseIndex,
        Map<String, SmartStagedUnit> unitIndex,
        Map<String, SmartStagedVoucher> voucherIndex,
        Map<String, SmartImportResolution> resolutionMap
    ) {
        var type = effectiveType(file);
        for (int index = 0; index < rows.size(); index++) {
            var rowNumber = index + 1;
            var row = rows.get(index);
            switch (type) {
                case INVENTORY_MASTER -> stageInventoryRow(session, file, type, rowNumber, row, productIndex, warehouseIndex, unitIndex);
                case ACCOUNTING_MASTER -> stagePartyCandidate(session, file, type, rowNumber, row, partyType(type, row), partyIndex);
                case MIXED_TALLY_MASTERS -> stageMixedMasterRow(
                    session, file, rowNumber, row, productIndex, partyIndex, warehouseIndex, unitIndex);
                case STOCK_SNAPSHOT -> stageSnapshotRow(session, file, type, rowNumber, row, productIndex, warehouseIndex, unitIndex);
                case SALES_VOUCHERS, PURCHASE_VOUCHERS, CREDIT_NOTES, DEBIT_NOTES, STOCK_JOURNAL ->
                    stageVoucherRow(session, file, type, rowNumber, row, productIndex, partyIndex, warehouseIndex,
                        unitIndex, voucherIndex, resolutionMap);
                case MIXED_TALLY_VOUCHERS -> stageMixedVoucherRow(
                    session, file, rowNumber, row, productIndex, partyIndex, warehouseIndex, unitIndex, voucherIndex,
                    resolutionMap);
                case CASH_BOOK -> stageCashbookRow(session, file, rowNumber, row);
                case DEBTOR_CREDITOR_ANALYSIS -> stagePartyCandidate(session, file, type, rowNumber, row, partyType(type, row), partyIndex);
                case STOCK_AGEING -> stageAgeingRow(session, file, type, rowNumber, row, productIndex, unitIndex);
                case UNKNOWN -> { }
            }
        }
    }

    private void stageMixedMasterRow(
        ImportSession session, ImportSessionFile file, int rowNumber, Map<String, String> row,
        Map<String, SmartStagedProduct> productIndex, Map<String, SmartStagedParty> partyIndex,
        Map<String, SmartStagedWarehouse> warehouseIndex, Map<String, SmartStagedUnit> unitIndex
    ) {
        var entity = normalize(lookup(row, "Source Entity"));
        if (entity.equals("STOCKITEM")) {
            stageInventoryRow(session, file, DomainEnums.DetectedFileType.INVENTORY_MASTER, rowNumber, row,
                productIndex, warehouseIndex, unitIndex);
        } else if (entity.equals("LEDGER")) {
            var resolvedPartyType = partyType(DomainEnums.DetectedFileType.ACCOUNTING_MASTER, row);
            if (resolvedPartyType != DomainEnums.SmartPartyType.UNKNOWN) {
                stagePartyCandidate(session, file, DomainEnums.DetectedFileType.ACCOUNTING_MASTER, rowNumber, row,
                    resolvedPartyType, partyIndex);
            }
        }
    }

    private void stageMixedVoucherRow(
        ImportSession session, ImportSessionFile file, int rowNumber, Map<String, String> row,
        Map<String, SmartStagedProduct> productIndex, Map<String, SmartStagedParty> partyIndex,
        Map<String, SmartStagedWarehouse> warehouseIndex, Map<String, SmartStagedUnit> unitIndex,
        Map<String, SmartStagedVoucher> voucherIndex, Map<String, SmartImportResolution> resolutionMap
    ) {
        var entity = normalize(lookup(row, "Source Entity"));
        if (entity.equals("STOCKITEM")) {
            stageInventoryRow(session, file, DomainEnums.DetectedFileType.INVENTORY_MASTER, rowNumber, row,
                productIndex, warehouseIndex, unitIndex);
            return;
        }
        if (entity.equals("LEDGER")) {
            var resolvedPartyType = partyType(DomainEnums.DetectedFileType.ACCOUNTING_MASTER, row);
            if (resolvedPartyType != DomainEnums.SmartPartyType.UNKNOWN) {
                stagePartyCandidate(session, file, DomainEnums.DetectedFileType.ACCOUNTING_MASTER, rowNumber, row,
                    resolvedPartyType, partyIndex);
            }
            return;
        }

        var rowType = voucherFileType(lookup(row, "Voucher Type", "Type"));
        switch (rowType) {
            case SALES_VOUCHERS, PURCHASE_VOUCHERS, CREDIT_NOTES, DEBIT_NOTES, STOCK_JOURNAL ->
                stageVoucherRow(session, file, rowType, rowNumber, row, productIndex, partyIndex,
                    warehouseIndex, unitIndex, voucherIndex, resolutionMap);
            case CASH_BOOK -> stageCashbookRow(session, file, rowNumber, row);
            default -> {
                // Contra, journal, and unknown voucher types remain classified but are not posted as sales or purchases.
            }
        }
    }

    private DomainEnums.DetectedFileType voucherFileType(String rawVoucherType) {
        var voucherType = normalize(rawVoucherType);
        if (voucherType.contains("CREDIT NOTE") || voucherType.contains("CREDITNOTE")) {
            return DomainEnums.DetectedFileType.CREDIT_NOTES;
        }
        if (voucherType.contains("DEBIT NOTE") || voucherType.contains("DEBITNOTE")) {
            return DomainEnums.DetectedFileType.DEBIT_NOTES;
        }
        if (voucherType.contains("STOCK JOURNAL") || voucherType.contains("STOCKJOURNAL")) {
            return DomainEnums.DetectedFileType.STOCK_JOURNAL;
        }
        if (voucherType.contains("SALES") || voucherType.equals("SALE")) {
            return DomainEnums.DetectedFileType.SALES_VOUCHERS;
        }
        if (voucherType.contains("PURCHASE")) {
            return DomainEnums.DetectedFileType.PURCHASE_VOUCHERS;
        }
        if (voucherType.contains("RECEIPT") || voucherType.contains("PAYMENT")) {
            return DomainEnums.DetectedFileType.CASH_BOOK;
        }
        return DomainEnums.DetectedFileType.UNKNOWN;
    }

    private void stageInventoryRow(
        ImportSession session, ImportSessionFile file, DomainEnums.DetectedFileType type, int rowNumber, Map<String, String> row,
        Map<String, SmartStagedProduct> productIndex, Map<String, SmartStagedWarehouse> warehouseIndex, Map<String, SmartStagedUnit> unitIndex
    ) {
        stageProductCandidate(session, file, type, rowNumber, row, productIndex);
        stageUnitCandidate(session, file, rowNumber, lookup(row, "Unit", "Unit Code", "Base Unit", "BASEUNITS"), unitIndex);
        stageWarehouseCandidate(session, file, rowNumber, lookup(row, "Warehouse", "Godown", "Godown Name"), warehouseIndex);
    }

    private void stageSnapshotRow(
        ImportSession session, ImportSessionFile file, DomainEnums.DetectedFileType type, int rowNumber, Map<String, String> row,
        Map<String, SmartStagedProduct> productIndex, Map<String, SmartStagedWarehouse> warehouseIndex, Map<String, SmartStagedUnit> unitIndex
    ) {
        var product = stageProductCandidate(session, file, type, rowNumber, row, productIndex);
        var unitCode = firstNonBlank(lookup(row, "Unit", "Unit Code", "Base Unit"), product == null ? null : product.unitCode);
        var warehouseName = lookup(row, "Warehouse", "Godown", "Godown Name");
        stageUnitCandidate(session, file, rowNumber, unitCode, unitIndex);
        stageWarehouseCandidate(session, file, rowNumber, warehouseName, warehouseIndex);
        var snapshot = new SmartStagedStockSnapshot();
        own(snapshot, session, file, rowNumber);
        snapshot.productName = lookup(row, "Item Name", "Product Name", "Name");
        snapshot.normalizedProductName = normalizeName(snapshot.productName);
        snapshot.unitCode = normalizeUnit(unitCode);
        snapshot.warehouseName = blankToNull(warehouseName);
        snapshot.snapshotDate = file.dateRangeEnd;
        snapshot.importedStock = nvl(decimal(lookup(row, "Opening Stock", "Closing Qty", "Closing Quantity", "Quantity", "Qty")));
        snapshot.rate = decimal(lookup(row, "Purchase Price", "Closing Rate", "Rate"));
        snapshot.stockValue = decimal(lookup(row, "Stock Value", "Closing Value", "Value", "Amount"));
        snapshot.rawMetadataJson = metadata(row);
        stagedSnapshots.save(snapshot);
    }

    private void stageVoucherRow(
        ImportSession session, ImportSessionFile file, DomainEnums.DetectedFileType type, int rowNumber, Map<String, String> row,
        Map<String, SmartStagedProduct> productIndex, Map<String, SmartStagedParty> partyIndex,
        Map<String, SmartStagedWarehouse> warehouseIndex, Map<String, SmartStagedUnit> unitIndex,
        Map<String, SmartStagedVoucher> voucherIndex, Map<String, SmartImportResolution> resolutionMap
    ) {
        var voucherType = firstNonBlank(lookup(row, "Voucher Type", "Type"), defaultVoucherType(type));
        var voucherNumber = lookup(row, "Voucher Number", "Voucher No", "Invoice Number", "Invoice No");
        var voucherDate = date(lookup(row, "Voucher Date", "Date", "Invoice Date"));
        var partyName = lookup(row, "Party Name", "Customer", "Supplier", "Ledger Name");
        var voucherKey = file.id + "|" + normalize(voucherType) + "|" + normalize(voucherNumber) + "|" + voucherDate + "|" + normalizeName(partyName);
        if (voucherNumber == null || voucherNumber.isBlank()) {
            voucherKey += "|ROW:" + rowNumber;
        }
        var stableVoucherKey = voucherResolutionKey(file.id, voucherType, voucherNumber, voucherDate, partyName, rowNumber);
        var voucherResolution = resolutionMap.get(stableVoucherKey);
        var rateResolution = resolutionMap.get(rateResolutionKey(file.id, rowNumber));
        var voucher = voucherIndex.get(voucherKey);
        if (voucher == null) {
            voucher = new SmartStagedVoucher();
            own(voucher, session, file, rowNumber);
            voucher.voucherType = voucherType;
            voucher.voucherNumber = blankToNull(voucherNumber);
            voucher.voucherDate = voucherDate;
            voucher.partyName = blankToNull(partyName);
            voucher.normalizedPartyName = normalizeName(partyName);
            voucher.totalAmount = ZERO;
            voucher.externalId = blankToNull(lookup(row, "Source Record ID", "Tally GUID", "GUID", "External ID", "Record ID", "MASTERID", "Master ID"));
            voucher.rawMetadataJson = metadata(row);
            voucher.fingerprint = fingerprint(session.tenantId, voucher);
            stagedVouchers.save(voucher);
            voucherIndex.put(voucherKey, voucher);
        }
        if (voucherResolution != null && "EXCLUDE_VOUCHER".equals(voucherResolution.action)) {
            voucher.rawMetadataJson.put("excludedFromCommit", true);
            voucher.rawMetadataJson.put("exclusionReason", detail(voucherResolution, "reason"));
            voucher.rawMetadataJson.put("voucherResolutionKey", stableVoucherKey);
            voucher.reviewStatus = DomainEnums.SmartReviewStatus.IGNORED;
            voucher.fingerprint = fingerprint(session.tenantId, voucher);
            stagedVouchers.save(voucher);
            return;
        }
        var lineAmount = decimal(lookup(row, "Amount", "Line Amount", "Total Amount"));
        mergeVoucherFinancials(session.tenantId, voucher, row);
        stagePartyCandidate(session, file, type, rowNumber, row, partyType(type, row), partyIndex);

        var productName = lookup(row, "Item Name", "Product Name", "Stock Item", "STOCKITEMNAME");
        if (productName == null || productName.isBlank()) {
            voucher.fingerprint = fingerprint(session.tenantId, voucher);
            stagedVouchers.save(voucher);
            return;
        }
        if (rateResolution != null && "TREAT_AS_FINANCIAL_ADJUSTMENT".equals(rateResolution.action)) {
            var amount = lineAmount == null ? ZERO : lineAmount.abs();
            var resolvedAmount = decimal(Objects.toString(
                voucher.rawMetadataJson.get("resolvedFinancialAdjustmentAmount"), ""));
            resolvedAmount = nvl(resolvedAmount).add(amount);
            voucher.rawMetadataJson.put("resolvedFinancialAdjustmentAmount", resolvedAmount.toPlainString());
            voucher.rawMetadataJson.put("resolvedFinancialAdjustment", true);
            voucher.rawMetadataJson.put("financialAdjustmentReason", detail(rateResolution, "reason"));
            @SuppressWarnings("unchecked")
            var lines = voucher.rawMetadataJson.get("resolvedFinancialAdjustmentLines") instanceof List<?> existing
                ? new ArrayList<>((List<Map<String, Object>>) existing) : new ArrayList<Map<String, Object>>();
            lines.add(linked("sourceRow", rowNumber, "description", productName, "amount", amount));
            voucher.rawMetadataJson.put("resolvedFinancialAdjustmentLines", lines);
            voucher.totalAmount = resolvedAmount.add(metadataDecimal(voucher.rawMetadataJson, "inventoryLineAmountTotal"));
            voucher.fingerprint = fingerprint(session.tenantId, voucher);
            stagedVouchers.save(voucher);
            return;
        }
        var product = stageProductCandidate(session, file, type, rowNumber, row, productIndex);
        var unitCode = firstNonBlank(lookup(row, "Unit", "Unit Code"), product == null ? null : product.unitCode);
        var warehouseName = lookup(row, "Warehouse", "Godown", "Godown Name");
        stageUnitCandidate(session, file, rowNumber, unitCode, unitIndex);
        stageWarehouseCandidate(session, file, rowNumber, warehouseName, warehouseIndex);
        var item = new SmartStagedVoucherItem();
        own(item, session, file, rowNumber);
        item.stagedVoucherId = voucher.id;
        item.productName = productName;
        item.normalizedProductName = normalizeName(productName);
        item.unitCode = normalizeUnit(unitCode);
        item.rawQuantity = decimal(lookup(row, "Raw Quantity", "Qty", "Quantity"));
        item.billedQuantity = decimal(lookup(row, "Billed Quantity"));
        item.actualQuantity = decimal(lookup(row, "Actual Quantity"));
        item.quantity = abs(item.billedQuantity != null ? item.billedQuantity
            : item.actualQuantity != null ? item.actualQuantity : item.rawQuantity);
        item.rate = decimal(lookup(row, "Rate", "Parsed Rate", "Unit Price", "Price"));
        item.amount = lineAmount;
        item.warehouseName = blankToNull(warehouseName);
        item.rateSource = firstNonBlank(lookup(row, "Rate Source"), item.rate == null ? "INVALID" : item.rate.signum() == 0 ? "ZERO_COST_ITEM" : "RATE_FIELD");
        item.rawMetadataJson = metadata(row);
        applyRateResolution(item, rateResolution);
        stagedVoucherItems.save(item);
        var calculatedAmount = lineAmount == null && item.quantity != null && item.rate != null
            ? item.quantity.multiply(item.rate) : lineAmount;
        var inventoryTotal = metadataDecimal(voucher.rawMetadataJson, "inventoryLineAmountTotal")
            .add(nvl(calculatedAmount).abs());
        voucher.rawMetadataJson.put("inventoryLineAmountTotal", inventoryTotal.toPlainString());
        var resolvedAmount = metadataDecimal(voucher.rawMetadataJson, "resolvedFinancialAdjustmentAmount");
        voucher.totalAmount = resolvedAmount.signum() > 0
            ? inventoryTotal.add(resolvedAmount) : nvl(voucher.totalAmount).add(nvl(calculatedAmount).abs());
        voucher.fingerprint = fingerprint(session.tenantId, voucher);
        stagedVouchers.save(voucher);
    }

    private void mergeVoucherFinancials(UUID tenantId, SmartStagedVoucher voucher, Map<String, String> row) {
        var taxLines = Optional.ofNullable(integer(lookup(row, "Tax Line Count"))).orElse(0);
        var discountLines = Optional.ofNullable(integer(lookup(row, "Discount Line Count"))).orElse(0);
        var freightLines = Optional.ofNullable(integer(lookup(row, "Freight Line Count"))).orElse(0);
        var roundOffLines = Optional.ofNullable(integer(lookup(row, "Round Off Line Count"))).orElse(0);
        var otherLines = Optional.ofNullable(integer(lookup(row, "Other Charge Line Count"))).orElse(0);
        if (taxLines + discountLines + freightLines + roundOffLines + otherLines == 0
            || voucher.taxLineCount + voucher.discountLineCount + voucher.freightLineCount
                + voucher.roundOffLineCount + voucher.otherChargeLineCount > 0) return;
        voucher.taxAmount = nvl(decimal(lookup(row, "Tax Amount"))).abs();
        voucher.discountAmount = nvl(decimal(lookup(row, "Discount Amount"))).abs();
        voucher.freightAmount = nvl(decimal(lookup(row, "Freight Amount"))).abs();
        voucher.roundOffAmount = nvl(decimal(lookup(row, "Round Off Amount")));
        voucher.otherChargesAmount = nvl(decimal(lookup(row, "Other Charges Amount"))).abs();
        voucher.taxLineCount = taxLines;
        voucher.discountLineCount = discountLines;
        voucher.freightLineCount = freightLines;
        voucher.roundOffLineCount = roundOffLines;
        voucher.otherChargeLineCount = otherLines;
        voucher.totalAmount = nvl(voucher.totalAmount).add(voucher.taxAmount).subtract(voucher.discountAmount)
            .add(voucher.freightAmount).add(voucher.roundOffAmount).add(voucher.otherChargesAmount);
        voucher.rawMetadataJson.put("ledgerAdjustmentDetails", lookup(row, "Ledger Adjustment Details"));
        voucher.rawMetadataJson.put("financialComponentsCaptured", true);
        voucher.fingerprint = fingerprint(tenantId, voucher);
    }

    private void applyRateResolution(SmartStagedVoucherItem item, SmartImportResolution resolution) {
        if (resolution == null) return;
        switch (resolution.action) {
            case "CONFIRM_ZERO_VALUE" -> {
                if (item.quantity != null && item.quantity.signum() != 0
                    && (item.amount == null || item.amount.signum() == 0)) {
                    item.rate = ZERO;
                    item.rateSource = "USER_CONFIRMED_ZERO_VALUE";
                }
            }
            case "DERIVE_RATE" -> {
                if (item.quantity != null && item.quantity.signum() != 0
                    && item.amount != null && item.amount.signum() != 0) {
                    item.rate = item.amount.abs().divide(item.quantity.abs(), 6, java.math.RoundingMode.HALF_UP);
                    item.rateSource = "USER_DERIVED_FROM_AMOUNT";
                }
            }
            case "ENTER_VERIFIED_RATE" -> {
                var verified = decimal(detail(resolution, "verifiedRate"));
                if (verified != null && verified.signum() > 0) {
                    item.rate = verified;
                    item.rateSource = "USER_VERIFIED_RATE";
                }
            }
            default -> { }
        }
        if (!"INVALID".equalsIgnoreCase(item.rateSource)) {
            item.rawMetadataJson.put("rateResolutionAction", resolution.action);
            item.rawMetadataJson.put("rateResolutionReason", detail(resolution, "reason"));
            item.rawMetadataJson.put("originalRateSource", lookupMetadata(item.rawMetadataJson, "Rate Source"));
        }
    }

    private String rateResolutionKey(UUID fileId, int sourceRowNumber) {
        return "VOUCHER_ITEM_RATE:" + fileId + ":" + sourceRowNumber;
    }

    private String voucherResolutionKey(
        UUID fileId,
        String voucherType,
        String voucherNumber,
        LocalDate voucherDate,
        String partyName,
        int sourceRowNumber
    ) {
        var identity = normalize(voucherType) + "|" + normalize(voucherNumber) + "|" + voucherDate + "|"
            + normalizeName(partyName);
        if (voucherNumber == null || voucherNumber.isBlank()) identity += "|ROW:" + sourceRowNumber;
        return "VOUCHER_EXCLUSION:" + fileId + ":" + identity;
    }

    private String voucherResolutionKey(SmartStagedVoucher voucher) {
        return voucherResolutionKey(voucher.sessionFileId, voucher.voucherType, voucher.voucherNumber,
            voucher.voucherDate, voucher.partyName, voucher.sourceRowNumber);
    }

    private String detail(SmartImportResolution resolution, String key) {
        return resolution == null || resolution.detailsJson == null
            ? "" : Objects.toString(resolution.detailsJson.get(key), "");
    }

    private BigDecimal metadataDecimal(Map<String, Object> metadata, String key) {
        return nvl(decimal(Objects.toString(metadata == null ? null : metadata.get(key), "")));
    }

    private String lookupMetadata(Map<String, Object> metadata, String key) {
        return Objects.toString(metadata == null ? null : metadata.get(key), "");
    }

    private boolean excludedVoucher(SmartStagedVoucher voucher) {
        return voucher != null && voucher.rawMetadataJson != null
            && Boolean.parseBoolean(Objects.toString(voucher.rawMetadataJson.get("excludedFromCommit"), "false"));
    }

    private List<String> rateEligibleActions(SmartStagedVoucherItem item, SmartStagedVoucher voucher) {
        var actions = new ArrayList<String>();
        var hasQuantity = item.quantity != null && item.quantity.signum() != 0;
        var hasAmount = item.amount != null && item.amount.signum() != 0;
        if (hasQuantity && !hasAmount && (item.rate == null || item.rate.signum() == 0)) {
            actions.add("CONFIRM_ZERO_VALUE");
        }
        if (hasQuantity && hasAmount) actions.add("DERIVE_RATE");
        var noteType = normalize(voucher.voucherType);
        if (!hasQuantity && hasAmount && (noteType.contains("DEBIT") || noteType.contains("CREDIT"))) {
            actions.add("TREAT_AS_FINANCIAL_ADJUSTMENT");
        }
        if (hasQuantity) actions.add("ENTER_VERIFIED_RATE");
        actions.add("EXCLUDE_VOUCHER");
        actions.add("KEEP_UNRESOLVED");
        return actions;
    }

    private List<Map<String, Object>> rateChoices(List<String> eligibleActions) {
        var choices = new ArrayList<Map<String, Object>>();
        if (eligibleActions.contains("CONFIRM_ZERO_VALUE")) {
            choices.add(choice("CONFIRM_ZERO_VALUE", "Confirm zero-valued / free item", null));
        }
        if (eligibleActions.contains("DERIVE_RATE")) {
            choices.add(choice("DERIVE_RATE", "Derive rate from amount and quantity", null));
        }
        if (eligibleActions.contains("TREAT_AS_FINANCIAL_ADJUSTMENT")) {
            choices.add(choice("TREAT_AS_FINANCIAL_ADJUSTMENT", "Treat as financial adjustment", null));
        }
        if (eligibleActions.contains("ENTER_VERIFIED_RATE")) {
            choices.add(choice("ENTER_VERIFIED_RATE", "Enter verified source rate", null));
        }
        choices.add(choice("EXCLUDE_VOUCHER", "Exclude entire voucher from this commit", null));
        choices.add(choice("KEEP_UNRESOLVED", "Keep unresolved", null));
        return choices;
    }

    private Map<String, Object> rateReviewContext(
        SmartStagedVoucherItem item,
        SmartStagedVoucher voucher,
        String resolutionKey,
        List<String> eligibleActions
    ) {
        var derivedRate = item.amount != null && item.amount.signum() != 0
            && item.quantity != null && item.quantity.signum() != 0
            ? item.amount.abs().divide(item.quantity.abs(), 6, java.math.RoundingMode.HALF_UP) : null;
        return linked(
            "resolutionKey", resolutionKey,
            "voucherResolutionKey", voucherResolutionKey(voucher),
            "rateMatchKey", rateMatchKey(item, voucher),
            "entityType", "VOUCHER_ITEM",
            "stagingId", item.id,
            "voucherId", voucher.id,
            "voucherType", voucher.voucherType,
            "voucherNumber", voucher.voucherNumber,
            "voucherDate", voucher.voucherDate,
            "partyName", voucher.partyName,
            "productName", item.productName,
            "quantity", item.quantity,
            "amount", item.amount,
            "rate", item.rate,
            "rateSource", item.rateSource,
            "derivedRate", derivedRate,
            "eligibleActions", eligibleActions
        );
    }

    private String rateMatchKey(SmartStagedVoucherItem item, SmartStagedVoucher voucher) {
        var shape = item.quantity != null && item.quantity.signum() != 0
            ? item.amount != null && item.amount.signum() != 0 ? "QUANTITY_AND_AMOUNT" : "QUANTITY_ONLY"
            : item.amount != null && item.amount.signum() != 0 ? "AMOUNT_ONLY" : "VALUES_MISSING";
        return normalize(voucher.voucherType) + "|" + normalizeName(item.productName) + "|" + shape;
    }

    private void stageCashbookRow(ImportSession session, ImportSessionFile file, int rowNumber, Map<String, String> row) {
        var entry = new SmartStagedCashbookEntry();
        own(entry, session, file, rowNumber);
        entry.entryDate = date(lookup(row, "Date", "Voucher Date", "Entry Date"));
        entry.partyName = blankToNull(lookup(row, "Party Name", "Ledger Name", "Account"));
        entry.gstin = blankToNull(lookup(row, "GSTIN", "GST No", "GST Number"));
        var signedAmount = decimal(lookup(row, "Amount", "Value"));
        entry.amount = abs(signedAmount);
        var type = normalize(lookup(row, "Voucher Type", "Direction", "Type"));
        entry.direction = type.contains("RECEIPT") ? DomainEnums.SmartCashbookDirection.RECEIPT
            : type.contains("PAYMENT") ? DomainEnums.SmartCashbookDirection.PAYMENT
            : signedAmount != null && signedAmount.signum() < 0 ? DomainEnums.SmartCashbookDirection.PAYMENT
            : DomainEnums.SmartCashbookDirection.UNKNOWN;
        entry.referenceNumber = blankToNull(lookup(row, "Reference Number", "Reference", "Voucher Number", "Bill Ref", "Bill Number"));
        entry.paymentMode = paymentMode(lookup(row, "Payment Mode", "Mode", "Instrument Type", "Transaction Type"), entry.partyName);
        entry.fingerprint = cashbookFingerprint(session.tenantId, entry);
        entry.rawMetadataJson = metadata(row);
        stagedCashbook.save(entry);
    }

    private void stageAgeingRow(
        ImportSession session, ImportSessionFile file, DomainEnums.DetectedFileType type, int rowNumber, Map<String, String> row,
        Map<String, SmartStagedProduct> productIndex, Map<String, SmartStagedUnit> unitIndex
    ) {
        var product = stageProductCandidate(session, file, type, rowNumber, row, productIndex);
        var item = new SmartStagedStockAgeing();
        own(item, session, file, rowNumber);
        item.productName = lookup(row, "Item Name", "Product Name", "Name");
        item.normalizedProductName = normalizeName(item.productName);
        item.unitCode = normalizeUnit(firstNonBlank(lookup(row, "Unit", "Unit Code"), product == null ? null : product.unitCode));
        item.quantity = decimal(lookup(row, "Quantity", "Qty", "Stock"));
        item.ageingBucket = blankToNull(lookup(row, "Ageing Bucket", "Aging Bucket", "Bucket"));
        item.daysOld = integer(lookup(row, "Days", "Days Old", "Age"));
        item.stockValue = decimal(lookup(row, "Value", "Stock Value", "Amount"));
        item.rawMetadataJson = metadata(row);
        stagedAgeing.save(item);
        stageUnitCandidate(session, file, rowNumber, item.unitCode, unitIndex);
    }

    private SmartStagedProduct stageProductCandidate(
        ImportSession session, ImportSessionFile file, DomainEnums.DetectedFileType type, int rowNumber,
        Map<String, String> row, Map<String, SmartStagedProduct> index
    ) {
        var rawName = lookup(row, "Item Name", "Product Name", "Stock Item", "Name", "STOCKITEMNAME");
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        var unitCode = normalizeUnit(lookup(row, "Unit", "Unit Code", "Base Unit", "BASEUNITS"));
        var key = productKey(normalizeName(rawName), unitCode);
        var existing = index.get(key);
        if (existing != null) {
            return existing;
        }
        var product = new SmartStagedProduct();
        own(product, session, file, rowNumber);
        product.sourceType = type;
        product.rawName = rawName.trim();
        product.normalizedName = normalizeName(rawName);
        product.unitCode = unitCode;
        product.sku = blankToNull(lookup(row, "SKU", "Stock Keeping Unit", "Item Code"));
        product.barcode = blankToNull(lookup(row, "Barcode", "EAN", "UPC"));
        product.categoryName = blankToNull(lookup(row, "Category", "Stock Group", "Parent"));
        product.brandName = blankToNull(lookup(row, "Brand"));
        product.hsn = blankToNull(lookup(row, "HSN", "HSN Code", "HSNCODE"));
        product.gstPercent = decimal(lookup(row, "GST %", "GST Percentage", "Tax Rate"));
        product.purchasePrice = decimal(lookup(row, "Purchase Price", "Opening Rate", "Rate"));
        product.salesPrice = decimal(lookup(row, "Sales Price", "Selling Price", "MRP"));
        product.externalId = SmartImportProductIdentity.fromSource(type, row);
        product.rawMetadataJson = metadata(row);
        stagedProducts.save(product);
        index.put(key, product);
        return product;
    }

    private SmartStagedParty stagePartyCandidate(
        ImportSession session, ImportSessionFile file, DomainEnums.DetectedFileType sourceType, int rowNumber, Map<String, String> row,
        DomainEnums.SmartPartyType partyType, Map<String, SmartStagedParty> index
    ) {
        var rawName = lookup(row, "Party Name", "Customer", "Supplier", "Ledger Name", "LEDGERNAME", "name");
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        var gstin = blankToNull(lookup(row, "GSTIN", "GST No", "GST Number", "PARTYGSTIN"));
        var baseKey = partyType + "|" + normalizeName(rawName);
        var key = baseKey;
        var existing = index.get(key);
        if (existing != null && gstin != null && existing.gstin != null
            && !normalize(gstin).equals(normalize(existing.gstin))) {
            key = baseKey + "|GSTIN:" + normalize(gstin);
            existing = index.get(key);
        }
        if (existing != null) {
            if (existing.gstin == null && gstin != null) existing.gstin = gstin;
            if (existing.phone == null) existing.phone = blankToNull(lookup(row, "Phone", "Mobile", "Phone Number"));
            if (existing.email == null) existing.email = blankToNull(lookup(row, "Email", "Email Address"));
            if (sourceType == DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS) {
                existing.sourceType = sourceType;
                existing.openingBalance = decimal(lookup(row, "Opening Balance", "Outstanding", "Balance"));
                var parsedSnapshotDate = date(lookup(row, "Snapshot Date", "As On Date", "Date"));
                existing.snapshotDate = parsedSnapshotDate == null ? file.dateRangeEnd : parsedSnapshotDate;
            }
            existing.rawMetadataJson.putAll(metadata(row));
            stagedParties.save(existing);
            return existing;
        }
        var party = new SmartStagedParty();
        own(party, session, file, rowNumber);
        party.sourceType = sourceType;
        party.partyType = partyType;
        party.rawName = rawName.trim();
        party.normalizedName = normalizeName(rawName);
        party.gstin = gstin;
        party.phone = blankToNull(lookup(row, "Phone", "Mobile", "Phone Number"));
        party.email = blankToNull(lookup(row, "Email", "Email Address"));
        party.openingBalance = decimal(lookup(row, "Opening Balance", "Outstanding", "Balance"));
        var parsedSnapshotDate = date(lookup(row, "Snapshot Date", "As On Date", "Date"));
        party.snapshotDate = parsedSnapshotDate == null ? file.dateRangeEnd : parsedSnapshotDate;
        party.rawMetadataJson = metadata(row);
        stagedParties.save(party);
        index.put(key, party);
        if (!key.equals(baseKey) && !index.containsKey(baseKey)) index.put(baseKey, party);
        return party;
    }

    private SmartStagedWarehouse stageWarehouseCandidate(
        ImportSession session, ImportSessionFile file, int rowNumber, String rawName, Map<String, SmartStagedWarehouse> index
    ) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        var normalized = normalizeName(rawName);
        var existing = index.get(normalized);
        if (existing != null) {
            return existing;
        }
        var warehouse = new SmartStagedWarehouse();
        own(warehouse, session, file, rowNumber);
        warehouse.sourceWarehouseName = rawName.trim();
        warehouse.normalizedName = normalized;
        stagedWarehouses.save(warehouse);
        index.put(normalized, warehouse);
        return warehouse;
    }

    private SmartStagedUnit stageUnitCandidate(
        ImportSession session, ImportSessionFile file, int rowNumber, String rawCode, Map<String, SmartStagedUnit> index
    ) {
        var normalized = normalizeUnit(rawCode);
        if (normalized == null) {
            return null;
        }
        var existing = index.get(normalized);
        if (existing != null) {
            return existing;
        }
        var unit = new SmartStagedUnit();
        own(unit, session, file, rowNumber);
        unit.sourceUnitCode = rawCode.trim();
        unit.normalizedCode = normalized;
        stagedUnits.save(unit);
        index.put(normalized, unit);
        return unit;
    }

    private void matchCanonicalRecords(ImportSession session) {
        var tenantId = session.tenantId;
        var resolutionMap = resolutions.findByTenantIdAndImportSessionId(tenantId, session.id).stream()
            .collect(java.util.stream.Collectors.toMap(row -> row.resolutionKey, row -> row, (left, right) -> right));
        var existingProducts = products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId);
        var existingWarehouses = warehouses.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId);

        var unitsForSession = stagedUnits.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, session.id);
        for (var row : unitsForSession) {
            var match = units.findByTenantIdAndCodeIgnoreCase(tenantId, row.normalizedCode);
            row.matchedUnitId = match.map(unit -> unit.id).orElse(null);
            row.matchStatus = match.isPresent() ? DomainEnums.SmartMatchStatus.MATCH_EXISTING : DomainEnums.SmartMatchStatus.CREATE_NEW;
            row.reviewStatus = DomainEnums.SmartReviewStatus.AUTO_RESOLVED;
        }
        stagedUnits.saveAll(unitsForSession);

        var productsForSession = stagedProducts.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, session.id);
        for (var row : productsForSession) {
            matchProduct(tenantId, row, existingProducts, resolutionMap.get(productResolutionKey(row.normalizedName, row.unitCode)));
        }
        stagedProducts.saveAll(productsForSession);
        var productIndex = productsForSession.stream().collect(java.util.stream.Collectors.toMap(
            row -> productKey(row.normalizedName, row.unitCode), row -> row, (left, right) -> left, LinkedHashMap::new));

        var partiesForSession = stagedParties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, session.id);
        for (var row : partiesForSession) {
            matchParty(tenantId, row);
        }
        stagedParties.saveAll(partiesForSession);

        var warehousesForSession = stagedWarehouses.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, session.id);
        for (var row : warehousesForSession) {
            matchWarehouse(tenantId, row, existingWarehouses, resolutionMap.get(warehouseResolutionKey(row.normalizedName)));
        }
        stagedWarehouses.saveAll(warehousesForSession);
        var warehouseIndex = warehousesForSession.stream().collect(java.util.stream.Collectors.toMap(
            row -> row.normalizedName, row -> row, (left, right) -> left, LinkedHashMap::new));

        matchSnapshots(session, productIndex, warehouseIndex);
        matchVouchers(session, productIndex, partiesForSession, warehouseIndex);
        returnService.classifySession(session.tenantId, session.id);
        transferService.classifySession(session.tenantId, session.id);
        matchCashbook(session, partiesForSession);
        matchAgeing(session, productIndex);
    }

    private void matchProduct(
        UUID tenantId,
        SmartStagedProduct row,
        List<Product> existingProducts,
        SmartImportResolution resolution
    ) {
        if (row.normalizedName == null || row.normalizedName.isBlank()) {
            row.matchStatus = DomainEnums.SmartMatchStatus.UNKNOWN;
            row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
            return;
        }
        if (resolution != null && "MAP_PRODUCT".equals(resolution.action) && resolution.targetId != null) {
            var target = products.findByTenantIdAndId(tenantId, resolution.targetId).orElse(null);
            if (target != null) {
                productMatched(row, target, DomainEnums.SmartReviewStatus.RESOLVED);
                return;
            }
        }
        if (resolution != null && "CREATE_PRODUCT_LATER".equals(resolution.action)) {
            row.matchStatus = DomainEnums.SmartMatchStatus.CREATE_NEW;
            row.matchedProductId = null;
            row.reviewStatus = DomainEnums.SmartReviewStatus.RESOLVED;
            return;
        }

        var external = findByExternalId(existingProducts, SmartImportProductIdentity.externalId(row));
        if (external != null) {
            productMatched(row, external, DomainEnums.SmartReviewStatus.AUTO_RESOLVED);
            return;
        }
        if (row.sku != null) {
            var match = products.findByTenantIdAndSkuIgnoreCase(tenantId, row.sku).orElse(null);
            if (match != null) {
                productMatched(row, match, DomainEnums.SmartReviewStatus.AUTO_RESOLVED);
                return;
            }
        }
        if (row.barcode != null) {
            var barcode = barcodes.findByTenantIdAndBarcode(tenantId, row.barcode).orElse(null);
            if (barcode != null) {
                var match = products.findByTenantIdAndId(tenantId, barcode.productId).orElse(null);
                if (match != null) {
                    productMatched(row, match, DomainEnums.SmartReviewStatus.AUTO_RESOLVED);
                    return;
                }
            }
        }
        if (row.unitCode != null) {
            var unit = units.findByTenantIdAndCodeIgnoreCase(tenantId, row.unitCode).orElse(null);
            if (unit != null) {
                var match = products.findByTenantIdAndNormalizedNameIgnoreCaseAndBaseUnitId(tenantId, row.normalizedName, unit.id).orElse(null);
                if (match != null) {
                    productMatched(row, match, DomainEnums.SmartReviewStatus.AUTO_RESOLVED);
                    return;
                }
            }
        }

        var candidates = possibleProducts(tenantId, existingProducts, row.normalizedName, row.unitCode);
        if (candidates.size() > 1) {
            row.matchStatus = DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW;
            row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
        } else if (candidates.size() == 1) {
            row.matchStatus = DomainEnums.SmartMatchStatus.POSSIBLE_DUPLICATE_REVIEW;
            row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
        } else if (row.unitCode == null) {
            row.matchStatus = DomainEnums.SmartMatchStatus.UNKNOWN;
            row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
        } else {
            row.matchStatus = DomainEnums.SmartMatchStatus.CREATE_NEW;
            row.reviewStatus = DomainEnums.SmartReviewStatus.AUTO_RESOLVED;
        }
    }

    private void matchParty(UUID tenantId, SmartStagedParty row) {
        var customerMatches = new LinkedHashMap<UUID, Customer>();
        var supplierMatches = new LinkedHashMap<UUID, Supplier>();
        if (row.gstin != null) {
            customers.findByTenantIdAndGstinIgnoreCase(tenantId, row.gstin).ifPresent(match -> customerMatches.put(match.id, match));
            suppliers.findByTenantIdAndGstinIgnoreCase(tenantId, row.gstin).ifPresent(match -> supplierMatches.put(match.id, match));
        }
        if (row.normalizedName != null) {
            customers.findByTenantIdAndNameIgnoreCase(tenantId, row.normalizedName).ifPresent(match -> customerMatches.put(match.id, match));
            suppliers.findByTenantIdAndNameIgnoreCase(tenantId, row.normalizedName).ifPresent(match -> supplierMatches.put(match.id, match));
        }
        if (row.phone != null) {
            customers.findByTenantIdAndPhone(tenantId, row.phone).forEach(match -> customerMatches.put(match.id, match));
            suppliers.findByTenantIdAndPhone(tenantId, row.phone).forEach(match -> supplierMatches.put(match.id, match));
        }
        if (row.email != null) {
            customers.findByTenantIdAndEmailIgnoreCase(tenantId, row.email).forEach(match -> customerMatches.put(match.id, match));
            suppliers.findByTenantIdAndEmailIgnoreCase(tenantId, row.email).forEach(match -> supplierMatches.put(match.id, match));
        }

        if (row.partyType == DomainEnums.SmartPartyType.CUSTOMER) {
            supplierMatches.clear();
        } else if (row.partyType == DomainEnums.SmartPartyType.SUPPLIER) {
            customerMatches.clear();
        }
        var total = customerMatches.size() + supplierMatches.size();
        if (total == 1) {
            row.matchedCustomerId = customerMatches.isEmpty() ? null : customerMatches.keySet().iterator().next();
            row.matchedSupplierId = supplierMatches.isEmpty() ? null : supplierMatches.keySet().iterator().next();
            row.matchStatus = DomainEnums.SmartMatchStatus.MATCH_EXISTING;
            row.reviewStatus = DomainEnums.SmartReviewStatus.AUTO_RESOLVED;
        } else if (total > 1) {
            row.matchStatus = DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW;
            row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
        } else if (row.normalizedName == null || row.partyType == DomainEnums.SmartPartyType.UNKNOWN) {
            row.matchStatus = DomainEnums.SmartMatchStatus.UNKNOWN;
            row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
        } else {
            row.matchStatus = DomainEnums.SmartMatchStatus.CREATE_NEW;
            row.reviewStatus = DomainEnums.SmartReviewStatus.AUTO_RESOLVED;
        }
    }

    private void matchWarehouse(
        UUID tenantId,
        SmartStagedWarehouse row,
        List<Warehouse> existingWarehouses,
        SmartImportResolution resolution
    ) {
        if (resolution != null && "MAP_WAREHOUSE".equals(resolution.action) && resolution.targetId != null) {
            var target = warehouses.findByTenantIdAndId(tenantId, resolution.targetId).orElse(null);
            if (target != null) {
                warehouseMatched(row, target.id, DomainEnums.SmartReviewStatus.RESOLVED);
                return;
            }
        }
        if (resolution != null && "CREATE_WAREHOUSE_LATER".equals(resolution.action)) {
            row.matchStatus = DomainEnums.SmartMatchStatus.CREATE_NEW;
            row.matchedWarehouseId = null;
            row.reviewStatus = DomainEnums.SmartReviewStatus.RESOLVED;
            return;
        }
        var match = existingWarehouses.stream().filter(candidate -> normalizeName(candidate.name).equals(row.normalizedName)).findFirst().orElse(null);
        if (match != null) {
            warehouseMatched(row, match.id, DomainEnums.SmartReviewStatus.AUTO_RESOLVED);
        } else {
            row.matchStatus = DomainEnums.SmartMatchStatus.CREATE_NEW;
            row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
        }
    }

    private void matchSnapshots(
        ImportSession session,
        Map<String, SmartStagedProduct> productIndex,
        Map<String, SmartStagedWarehouse> warehouseIndex
    ) {
        var rows = stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        for (var row : rows) {
            var product = productIndex.get(productKey(row.normalizedProductName, row.unitCode));
            var warehouse = row.warehouseName == null ? null : warehouseIndex.get(normalizeName(row.warehouseName));
            row.matchedProductId = product == null ? null : product.matchedProductId;
            row.matchedWarehouseId = warehouse == null ? null : warehouse.matchedWarehouseId;
            row.matchStatus = product == null ? DomainEnums.SmartMatchStatus.UNKNOWN : product.matchStatus;
            if (product == null || product.reviewStatus == DomainEnums.SmartReviewStatus.PENDING
                || (warehouse != null && warehouse.reviewStatus == DomainEnums.SmartReviewStatus.PENDING)
                || row.importedStock.signum() < 0) {
                row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
                row.action = DomainEnums.SmartSnapshotAction.REVIEW_REQUIRED;
                row.currentStock = row.matchedProductId == null ? ZERO : stockLedger.currentStock(session.tenantId, row.matchedProductId, row.matchedWarehouseId);
                row.deltaPreview = row.importedStock.subtract(row.currentStock);
                continue;
            }
            row.currentStock = row.matchedProductId == null ? ZERO : stockLedger.currentStock(session.tenantId, row.matchedProductId, row.matchedWarehouseId);
            row.deltaPreview = row.importedStock.subtract(row.currentStock);
            row.reviewStatus = DomainEnums.SmartReviewStatus.AUTO_RESOLVED;
            if (row.matchedProductId == null) {
                row.action = DomainEnums.SmartSnapshotAction.CREATE_NEW_PRODUCT;
            } else if (row.deltaPreview.compareTo(ZERO) == 0) {
                row.action = DomainEnums.SmartSnapshotAction.NO_CHANGE;
            } else {
                row.action = DomainEnums.SmartSnapshotAction.CREATE_SNAPSHOT_ADJUSTMENT;
            }
        }
        stagedSnapshots.saveAll(rows);
    }

    private void matchVouchers(
        ImportSession session,
        Map<String, SmartStagedProduct> productIndex,
        List<SmartStagedParty> partiesForSession,
        Map<String, SmartStagedWarehouse> warehouseIndex
    ) {
        var snapshots = stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var latestSnapshotDate = snapshots.stream().map(row -> row.snapshotDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        var hasSnapshot = !snapshots.isEmpty();
        var documentContents = new HashMap<String, String>();
        var voucherItems = stagedVoucherItems.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id)
            .stream().collect(Collectors.groupingBy(item -> item.stagedVoucherId));
        var vouchers = stagedVouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(session.tenantId, session.id);
        for (var row : vouchers) {
            if (excludedVoucher(row)) {
                row.matchedPartyId = null;
                row.matchStatus = DomainEnums.SmartMatchStatus.CREATE_NEW;
                row.reviewStatus = DomainEnums.SmartReviewStatus.IGNORED;
                continue;
            }
            var party = partiesForSession.stream().filter(candidate -> Objects.equals(candidate.normalizedName, row.normalizedPartyName)).findFirst().orElse(null);
            row.matchedPartyId = party == null ? null : firstNonNull(party.matchedCustomerId, party.matchedSupplierId);
            row.stockImpactModeSuggestion = !hasSnapshot
                ? DomainEnums.SmartVoucherStockImpactSuggestion.CREATE_INVOICES_AND_STOCK_MOVEMENTS
                : latestSnapshotDate != null && row.voucherDate != null && row.voucherDate.isAfter(latestSnapshotDate)
                    ? DomainEnums.SmartVoucherStockImpactSuggestion.APPLY_AFTER_SNAPSHOT_DATE
                    : DomainEnums.SmartVoucherStockImpactSuggestion.CREATE_INVOICES_ONLY;
            var identity = normalize(row.voucherType) + "|" + normalize(row.voucherNumber) + "|" + row.voucherDate + "|" + normalizeName(row.partyName);
            var contents = Objects.toString(row.rawMetadataJson.get("Source Company"), "") + "|" + row.totalAmount + "|" + voucherItems.getOrDefault(row.id, List.of()).stream()
                .map(item -> normalizeName(item.productName) + "|" + normalizeUnit(item.unitCode) + "|"
                    + nvl(item.quantity).stripTrailingZeros() + "|" + nvl(item.rate).stripTrailingZeros() + "|"
                    + nvl(item.amount).stripTrailingZeros() + "|" + normalizeName(item.warehouseName))
                .sorted().collect(Collectors.joining(";"));
            var previous = documentContents.putIfAbsent(identity, contents);
            var repeatedInSession = previous != null && previous.equals(contents);
            updateVoucherMatch(session.tenantId, row, party, repeatedInSession);
            if (previous != null && !previous.equals(contents)) {
                row.matchStatus = DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW;
                row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
            }
        }
        stagedVouchers.saveAll(vouchers);

        var items = stagedVoucherItems.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var transfers = stagedTransfers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(
            session.tenantId, session.id);
        for (var row : items) {
            var product = productIndex.get(productKey(row.normalizedProductName, row.unitCode));
            var warehouse = row.warehouseName == null ? null : warehouseIndex.get(normalizeName(row.warehouseName));
            row.matchedProductId = product == null ? null : product.matchedProductId;
            row.matchedWarehouseId = warehouse == null ? null : warehouse.matchedWarehouseId;
            row.matchStatus = product == null ? DomainEnums.SmartMatchStatus.UNKNOWN : product.matchStatus;
            row.reviewStatus = product == null || product.reviewStatus == DomainEnums.SmartReviewStatus.PENDING
                || (warehouse != null && warehouse.reviewStatus == DomainEnums.SmartReviewStatus.PENDING)
                ? DomainEnums.SmartReviewStatus.PENDING : DomainEnums.SmartReviewStatus.AUTO_RESOLVED;
        }
        stagedVoucherItems.saveAll(items);
        // Assess complete matched line contents after product/warehouse matching, not header totals alone.
        for (var voucher : vouchers) {
            if (!sourceRevisions.supported(voucher) || excludedVoucher(voucher)) continue;
            var revision = sourceRevisions.assess(voucher);
            if ("UNCHANGED".equals(revision.outcome())) {
                voucher.matchStatus=DomainEnums.SmartMatchStatus.SKIP_DUPLICATE;
                voucher.reviewStatus=DomainEnums.SmartReviewStatus.AUTO_RESOLVED;
            } else if (!"NEW".equals(revision.outcome())) {
                voucher.matchStatus=DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW;
                voucher.reviewStatus=DomainEnums.SmartReviewStatus.PENDING;
            }
        }
        stagedVouchers.saveAll(vouchers);
    }

    private void updateVoucherMatch(UUID tenantId, SmartStagedVoucher row, SmartStagedParty party, boolean repeatedInSession) {
        var existingStatus = existingVoucherStatus(tenantId, row);
        if (repeatedInSession || existingStatus == ExistingVoucherStatus.EXACT_DUPLICATE) {
            row.matchStatus = DomainEnums.SmartMatchStatus.SKIP_DUPLICATE;
            row.reviewStatus = DomainEnums.SmartReviewStatus.AUTO_RESOLVED;
        } else if (existingStatus == ExistingVoucherStatus.AMBIGUOUS_CONFLICT) {
            row.matchStatus = DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW;
            row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
        } else if (row.voucherNumber == null || row.voucherDate == null || row.partyName == null) {
            row.matchStatus = DomainEnums.SmartMatchStatus.UNKNOWN;
            row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
        } else if (party != null && party.reviewStatus == DomainEnums.SmartReviewStatus.PENDING) {
            row.matchStatus = DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW;
            row.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
        } else {
            row.matchStatus = DomainEnums.SmartMatchStatus.CREATE_NEW;
            row.reviewStatus = DomainEnums.SmartReviewStatus.AUTO_RESOLVED;
        }
    }

    private void matchCashbook(ImportSession session, List<SmartStagedParty> partiesForSession) {
        cashbookMatching.refresh(session.tenantId, session.id);
    }

    private void matchAgeing(ImportSession session, Map<String, SmartStagedProduct> productIndex) {
        var rows = stagedAgeing.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        for (var row : rows) {
            var product = productIndex.get(productKey(row.normalizedProductName, row.unitCode));
            row.matchedProductId = product == null ? null : product.matchedProductId;
            row.matchStatus = product == null ? DomainEnums.SmartMatchStatus.UNKNOWN : product.matchStatus;
            row.reviewStatus = product != null && product.reviewStatus != DomainEnums.SmartReviewStatus.PENDING
                ? DomainEnums.SmartReviewStatus.AUTO_RESOLVED : DomainEnums.SmartReviewStatus.PENDING;
        }
        stagedAgeing.saveAll(rows);
    }

    private List<ImportPlanIssue> buildReviewItems(ImportSession session, List<ParseFailure> parseFailures) {
        var generated = new ArrayList<ImportPlanIssue>();
        var existingProducts = products.findByTenantIdAndActiveTrueOrderByNameAsc(session.tenantId);
        var existingWarehouses = warehouses.findByTenantIdAndActiveTrueOrderByNameAsc(session.tenantId);
        var resolutionMap = resolutions.findByTenantIdAndImportSessionId(session.tenantId, session.id).stream()
            .collect(java.util.stream.Collectors.toMap(row -> row.resolutionKey, row -> row, (left, right) -> right));

        var phaseIssues = issues.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id);
        for (var issue : phaseIssues) {
            if ("UNKNOWN_FILE_TYPE".equals(issue.code) || "UNCERTAIN_FILE_CLASSIFICATION".equals(issue.code)) {
                issue.availableChoices = fileTypeChoices();
                issue.contextJson.put("resolutionKey", "FILE_TYPE:" + issue.affectedFileId);
                issue.contextJson.put("entityType", "FILE");
            }
        }
        issues.saveAll(phaseIssues);

        for (var failure : parseFailures) {
            generated.add(reviewIssue(session, failure.file.id, failure.rowNumber, DomainEnums.ImportPlanIssueSeverity.ERROR,
                failure.code, "The classified file could not be parsed into canonical staging: " + failure.message,
                "Correct the source file or override its file type, then rebuild staging.",
                fileTypeChoices(), Map.of("resolutionKey", "FILE_TYPE:" + failure.file.id, "entityType", "FILE")));
        }

        for (var row : stagedProducts.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id)) {
            if (row.reviewStatus != DomainEnums.SmartReviewStatus.PENDING) continue;
            var key = productResolutionKey(row.normalizedName, row.unitCode);
            if (ignored(resolutionMap, key)) continue;
            var candidates = possibleProducts(session.tenantId, existingProducts, row.normalizedName, row.unitCode);
            var choices = candidates.stream().limit(10).map(candidate -> choice("MAP_PRODUCT", "Map to " + candidate.name, candidate.id)).toList();
            var mutableChoices = new ArrayList<Map<String, Object>>(choices);
            mutableChoices.add(choice("CREATE_PRODUCT_LATER", "Create as a new product later", null));
            var code = row.matchStatus == DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW ? "AMBIGUOUS_PRODUCT_MATCH"
                : row.matchStatus == DomainEnums.SmartMatchStatus.POSSIBLE_DUPLICATE_REVIEW ? "POSSIBLE_DUPLICATE_PRODUCT"
                : "MISSING_PRODUCT_IDENTITY";
            generated.add(reviewIssue(session, row.sessionFileId, row.sourceRowNumber, DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED,
                code, "Product requires review: " + nullSafe(row.rawName),
                "Map it to an existing product or confirm that a new product should be created in a later phase.", mutableChoices,
                Map.of("resolutionKey", key, "entityType", "PRODUCT", "stagingId", row.id.toString())));
        }

        for (var row : stagedParties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id)) {
            if (row.reviewStatus != DomainEnums.SmartReviewStatus.PENDING) continue;
            var key = "PARTY:" + row.partyType + "|" + nullSafe(row.normalizedName);
            if (ignored(resolutionMap, key)) continue;
            var code = row.matchStatus == DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW ? "AMBIGUOUS_PARTY" : "UNKNOWN_PARTY";
            generated.add(reviewIssue(session, row.sessionFileId, row.sourceRowNumber, DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED,
                code, "Party requires review: " + nullSafe(row.rawName),
                "Confirm the party type and identity before final commit is introduced.", List.of(choice("IGNORE_WARNING", "Acknowledge for this planning session", null)),
                Map.of("resolutionKey", key, "entityType", "PARTY", "stagingId", row.id.toString())));
        }

        for (var row : stagedWarehouses.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id)) {
            if (row.reviewStatus != DomainEnums.SmartReviewStatus.PENDING) continue;
            var key = warehouseResolutionKey(row.normalizedName);
            if (ignored(resolutionMap, key)) continue;
            var choices = new ArrayList<Map<String, Object>>();
            existingWarehouses.stream().limit(20).forEach(warehouse -> choices.add(choice("MAP_WAREHOUSE", "Map to " + warehouse.name, warehouse.id)));
            choices.add(choice("CREATE_WAREHOUSE_LATER", "Create this warehouse later", null));
            generated.add(reviewIssue(session, row.sessionFileId, row.sourceRowNumber, DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED,
                "UNKNOWN_WAREHOUSE", "Warehouse/godown is not mapped: " + nullSafe(row.sourceWarehouseName),
                "Map it to an existing warehouse or mark it for creation in the commit phase.", choices,
                Map.of("resolutionKey", key, "entityType", "WAREHOUSE", "stagingId", row.id.toString())));
        }

        for (var row : stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id)) {
            if (row.importedStock.signum() < 0) {
                generated.add(reviewIssue(session, row.sessionFileId, row.sourceRowNumber, DomainEnums.ImportPlanIssueSeverity.ERROR,
                    "NEGATIVE_STOCK", "Imported snapshot quantity is negative for " + nullSafe(row.productName),
                    "Review the Tally quantity and tenant negative-stock policy before commit is implemented.", List.of(),
                    Map.of("resolutionKey", "SNAPSHOT_NEGATIVE:" + row.id, "entityType", "STOCK_SNAPSHOT", "stagingId", row.id.toString())));
            }
        }

        var stagedVoucherRows = stagedVouchers
            .findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(session.tenantId, session.id);
        var stockJournalVoucherIds = stagedVoucherRows.stream()
            .filter(row -> normalize(row.voucherType).contains("STOCK JOURNAL")
                || normalize(row.voucherType).contains("STOCKJOURNAL"))
            .map(row -> row.id)
            .collect(Collectors.toSet());
        var voucherById = stagedVoucherRows.stream().collect(Collectors.toMap(row -> row.id, row -> row));
        for (var row : stagedVoucherRows) {
            if (excludedVoucher(row)) continue;
            if (stockJournalVoucherIds.contains(row.id)) {
                // Stock journals are inventory documents; party identity is not part of transfer validation.
                continue;
            }
            if (sourceRevisions.needsReview(row)) {
                var revision=sourceRevisions.assess(row);
                generated.add(reviewIssue(session,row.sessionFileId,row.sourceRowNumber,DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED,
                    "SOURCE_DOCUMENT_REVISION", "Source document changed or was cancelled: " + nullSafe(row.voucherNumber),
                    revision.eligible()?"Review the complete source revision, then run a new dry run.":revision.blockingReason(),List.of(),
                    Map.of("resolutionKey","SOURCE_REVISION:"+row.id,"entityType","VOUCHER","stagingId",row.id.toString(),"revisionPreview",sourceRevisions.view(row))));
                continue;
            }
            if (row.matchStatus == DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW) {
                var key = "VOUCHER_DUPLICATE:" + row.fingerprint;
                if (!ignored(resolutionMap, key)) {
                    generated.add(reviewIssue(session, row.sessionFileId, row.sourceRowNumber, DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED,
                        "DUPLICATE_VOUCHER_CONFLICT", "Voucher number exists with different date, party, or amount: " + nullSafe(row.voucherNumber),
                        "Review the conflicting voucher identity before commit.", List.of(choice("IGNORE_WARNING", "Keep this voucher blocked", null)),
                        Map.of("resolutionKey", key, "entityType", "VOUCHER", "stagingId", row.id.toString())));
                }
            } else if (row.matchStatus == DomainEnums.SmartMatchStatus.UNKNOWN) {
                generated.add(reviewIssue(session, row.sessionFileId, row.sourceRowNumber, DomainEnums.ImportPlanIssueSeverity.ERROR,
                    "MISSING_REQUIRED_IDENTITY", "Voucher is missing number, date, or party identity.",
                    "Correct the source export or file classification before commit.", List.of(),
                    Map.of("resolutionKey", "VOUCHER_IDENTITY:" + row.id, "entityType", "VOUCHER", "stagingId", row.id.toString())));
            }
        }

        for (var row : stagedVoucherItems.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id)) {
            if (stockJournalVoucherIds.contains(row.stagedVoucherId)) {
                // A warehouse transfer can be quantity-only and does not require an invoice/cost rate.
                continue;
            }
            var voucher = voucherById.get(row.stagedVoucherId);
            if (voucher == null || excludedVoucher(voucher)) continue;
            var key = rateResolutionKey(row.sessionFileId, row.sourceRowNumber);
            var persisted = resolutionMap.get(key);
            var userResolved = persisted != null && ("CONFIRM_ZERO_VALUE".equals(persisted.action)
                || "DERIVE_RATE".equals(persisted.action) || "ENTER_VERIFIED_RATE".equals(persisted.action));
            if (userResolved && row.rate != null && row.rate.signum() >= 0
                && !"INVALID".equalsIgnoreCase(row.rateSource)) continue;
            var eligibleActions = rateEligibleActions(row, voucher);
            var choices = rateChoices(eligibleActions);
            var context = rateReviewContext(row, voucher, key, eligibleActions);
            if ("ZERO_COST_ITEM".equalsIgnoreCase(row.rateSource) || (row.rate != null && row.rate.signum() == 0)) {
                generated.add(reviewIssue(session, row.sessionFileId, row.sourceRowNumber,
                    DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED,
                    "ZERO_RATE_ITEM", "Voucher item has a zero rate: " + nullSafe(row.productName),
                    "Confirm a genuine free, scheme, or sample item, or choose another supported resolution.",
                    choices, context));
            } else if (row.rate == null || row.rate.signum() < 0 || "INVALID".equalsIgnoreCase(row.rateSource)) {
                generated.add(reviewIssue(session, row.sessionFileId, row.sourceRowNumber, DomainEnums.ImportPlanIssueSeverity.ERROR,
                    "INVALID_RATE", "Voucher item has an invalid rate: " + nullSafe(row.productName),
                    "Resolve this as a derived, verified, zero-valued, financial-only, or excluded voucher row.",
                    choices, context));
            }
        }

        for (var row : stagedCashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(session.tenantId, session.id)) {
            if (row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.UNMATCHED_REVIEW
                || row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.LOW_CONFIDENCE_REVIEW) {
                if (row.resolutionStatus == DomainEnums.CashbookResolutionStatus.RETAIL_SALE_RECORDED) continue;
                var key = "CASHBOOK:" + row.id;
                if (!ignored(resolutionMap, key)) {
                    generated.add(reviewIssue(session, row.sessionFileId, row.sourceRowNumber, DomainEnums.ImportPlanIssueSeverity.WARNING,
                        row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.LOW_CONFIDENCE_REVIEW
                            ? "CASHBOOK_LOW_CONFIDENCE" : "CASHBOOK_UNMATCHED_ENTRY",
                        "Cashbook entry needs review: " + nullSafe(row.partyName),
                        "Low-confidence entries are kept for review and are not posted as payments.",
                        List.of(choice("IGNORE_WARNING", "Keep in the review queue without posting", null)),
                        Map.of("resolutionKey", key, "entityType", "CASHBOOK", "stagingId", row.id.toString())));
                }
            }
        }

        for (var row : stagedAgeing.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id)) {
            if (row.matchedProductId == null) {
                var key = "AGEING:" + row.id;
                if (!ignored(resolutionMap, key)) {
                    generated.add(reviewIssue(session, row.sessionFileId, row.sourceRowNumber, DomainEnums.ImportPlanIssueSeverity.WARNING,
                        "STOCK_AGEING_UNMATCHED_PRODUCT", "Stock-ageing row is not matched to an existing product: " + nullSafe(row.productName),
                        "Map the product or keep the row as comparison-only data.", List.of(choice("IGNORE_WARNING", "Keep as comparison data", null)),
                        Map.of("resolutionKey", key, "entityType", "STOCK_AGEING", "stagingId", row.id.toString())));
                }
            }
        }
        return generated;
    }

    private void applySessionReviewState(ImportSession session) {
        var allIssues = issues.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id);
        var needsReview = allIssues.stream().anyMatch(issue -> !issue.resolved
            && (issue.severity == DomainEnums.ImportPlanIssueSeverity.ERROR || issue.severity == DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED));
        session.status = needsReview ? DomainEnums.ImportSessionStatus.NEEDS_REVIEW : DomainEnums.ImportSessionStatus.STAGED;
        sessions.save(session);
        plans.findByTenantIdAndImportSessionId(session.tenantId, session.id).ifPresent(plan -> {
            plan.status = needsReview ? DomainEnums.ImportPlanStatus.NEEDS_REVIEW : DomainEnums.ImportPlanStatus.READY;
            plan.planJson.put("phase", "CANONICAL_STAGING");
            plan.planJson.put("commitEnabled", false);
            plan.planJson.put("stagingComplete", true);
            plans.save(plan);
        });
    }

    private Map<String, Object> summary(ImportSession session) {
        var products = stagedProducts.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var parties = stagedParties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var warehouses = stagedWarehouses.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var units = stagedUnits.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var snapshots = stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var vouchers = stagedVouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(session.tenantId, session.id);
        var items = stagedVoucherItems.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var transfers = stagedTransfers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(
            session.tenantId, session.id);
        var cashbook = stagedCashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(session.tenantId, session.id);
        var ageing = stagedAgeing.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        return Map.ofEntries(
            Map.entry("productsStaged", products.size()), Map.entry("partiesStaged", parties.size()),
            Map.entry("warehousesStaged", warehouses.size()), Map.entry("unitsStaged", units.size()),
            Map.entry("stockSnapshotsStaged", snapshots.size()), Map.entry("vouchersStaged", vouchers.size()),
            Map.entry("undatedStockSnapshots", snapshots.stream().filter(row -> row.snapshotDate == null).count()),
            Map.entry("undatedOutstandingSnapshots", parties.stream()
                .filter(row -> row.sourceType == DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS)
                .filter(row -> row.snapshotDate == null).count()),
            Map.entry("voucherItemsStaged", items.size()),
            Map.entry("stockJournalsDetected", transfers.stream().map(row -> row.stagedVoucherId).distinct().count()),
            Map.entry("warehouseTransfersDetected", transfers.stream()
                .filter(row -> row.classification == DomainEnums.StockJournalClassification.WAREHOUSE_TRANSFER).count()),
            Map.entry("warehouseTransfersReviewRequired", transfers.stream()
                .filter(row -> row.resolutionStatus == DomainEnums.TransferResolutionStatus.REVIEW_REQUIRED).count()),
            Map.entry("creditNotesStaged", vouchers.stream().filter(row -> normalize(row.voucherType).contains("CREDIT")).count()),
            Map.entry("debitNotesStaged", vouchers.stream().filter(row -> normalize(row.voucherType).contains("DEBIT")).count()),
            Map.entry("ledgerAdjustmentLinesStaged", vouchers.stream().mapToInt(row -> row.taxLineCount + row.discountLineCount
                + row.freightLineCount + row.roundOffLineCount + row.otherChargeLineCount).sum()),
            Map.entry("debtorRowsStaged", parties.stream().filter(row -> row.sourceType == DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS
                && row.partyType == DomainEnums.SmartPartyType.CUSTOMER).count()),
            Map.entry("creditorRowsStaged", parties.stream().filter(row -> row.sourceType == DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS
                && row.partyType == DomainEnums.SmartPartyType.SUPPLIER).count()),
            Map.entry("cashbookEntriesStaged", cashbook.size()),
            Map.entry("stockAgeingRowsStaged", ageing.size())
        );
    }

    private Map<String, Object> matchingSummary(ImportSession session) {
        var products = stagedProducts.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var parties = stagedParties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var warehouses = stagedWarehouses.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(session.tenantId, session.id);
        var vouchers = stagedVouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(session.tenantId, session.id);
        var cashbook = stagedCashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(session.tenantId, session.id);
        return Map.ofEntries(
            Map.entry("productsToCreate", count(products, DomainEnums.SmartMatchStatus.CREATE_NEW)),
            Map.entry("productsMatchedExisting", count(products, DomainEnums.SmartMatchStatus.MATCH_EXISTING)),
            Map.entry("possibleDuplicateProducts", products.stream().filter(row -> row.matchStatus == DomainEnums.SmartMatchStatus.POSSIBLE_DUPLICATE_REVIEW || row.matchStatus == DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW).count()),
            Map.entry("partiesToCreate", count(parties, DomainEnums.SmartMatchStatus.CREATE_NEW)),
            Map.entry("partiesMatchedExisting", count(parties, DomainEnums.SmartMatchStatus.MATCH_EXISTING)),
            Map.entry("warehousesToCreateOrMap", warehouses.stream().filter(row -> row.matchStatus != DomainEnums.SmartMatchStatus.MATCH_EXISTING).count()),
            Map.entry("warehousesMatchedExisting", count(warehouses, DomainEnums.SmartMatchStatus.MATCH_EXISTING)),
            Map.entry("vouchersLikelyDuplicates", count(vouchers, DomainEnums.SmartMatchStatus.SKIP_DUPLICATE)),
            Map.entry("cashbookRowsMatched", cashbook.stream().filter(this::cashbookReady).count()),
            Map.entry("cashbookRowsUnmatched", cashbook.stream().filter(row -> row.resolutionStatus == DomainEnums.CashbookResolutionStatus.UNRESOLVED && row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.UNMATCHED_REVIEW).count()),
            Map.entry("cashbookRowsReviewRequired", cashbook.stream().filter(row -> row.resolutionStatus == DomainEnums.CashbookResolutionStatus.UNRESOLVED && row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.LOW_CONFIDENCE_REVIEW).count()),
            Map.entry("retailCounterSaleRowsResolved", cashbook.stream().filter(row -> row.resolutionStatus == DomainEnums.CashbookResolutionStatus.RETAIL_SALE_RECORDED).count()),
            Map.entry("paymentsToCreate", cashbook.stream().filter(this::cashbookReady).count())
        );
    }

    private long count(Collection<?> rows, DomainEnums.SmartMatchStatus status) {
        return rows.stream().filter(row -> matchStatus(row) == status).count();
    }

    private DomainEnums.SmartMatchStatus matchStatus(Object row) {
        if (row instanceof SmartStagedProduct value) return value.matchStatus;
        if (row instanceof SmartStagedParty value) return value.matchStatus;
        if (row instanceof SmartStagedWarehouse value) return value.matchStatus;
        if (row instanceof SmartStagedVoucher value) return value.matchStatus;
        return DomainEnums.SmartMatchStatus.UNKNOWN;
    }

    private void clearStaging(UUID tenantId, UUID sessionId) {
        dryRunItems.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
        dryRuns.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
        stagedTransfers.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
        stagedVoucherItems.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
        stagedSnapshots.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
        stagedCashbook.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
        stagedAgeing.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
        stagedVouchers.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
        stagedProducts.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
        stagedParties.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
        stagedWarehouses.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
        stagedUnits.deleteByTenantIdAndImportSessionId(tenantId, sessionId);
    }

    private void validateResolutionTarget(UUID tenantId, String action, UUID targetId) {
        if ("MAP_PRODUCT".equals(action) && (targetId == null || products.findByTenantIdAndId(tenantId, targetId).isEmpty())) {
            throw ApiErrors.badRequest("Select a product from the current tenant");
        }
        if ("MAP_WAREHOUSE".equals(action) && (targetId == null || warehouses.findByTenantIdAndId(tenantId, targetId).isEmpty())) {
            throw ApiErrors.badRequest("Select a warehouse from the current tenant");
        }
    }

    private ImportPlanIssue reviewIssue(
        ImportSession session, UUID fileId, int rowNumber, DomainEnums.ImportPlanIssueSeverity severity,
        String code, String message, String suggestedAction, List<Map<String, Object>> choices, Map<String, Object> context
    ) {
        var issue = new ImportPlanIssue();
        issue.tenantId = session.tenantId;
        issue.importSessionId = session.id;
        issue.affectedFileId = fileId;
        issue.affectedRows = rowNumber <= 0 ? new ArrayList<>() : new ArrayList<>(List.of(rowNumber));
        issue.severity = severity;
        issue.code = code;
        issue.message = message;
        issue.suggestedAction = suggestedAction;
        issue.availableChoices = new ArrayList<>(choices);
        issue.contextJson = new LinkedHashMap<>(context);
        return issue;
    }

    private Map<String, Object> reviewItem(ImportPlanIssue issue, String fileName) {
        var response = new LinkedHashMap<String, Object>();
        response.put("id", issue.id);
        response.put("severity", issue.severity);
        response.put("code", issue.code);
        response.put("message", issue.message);
        response.put("affectedFileId", issue.affectedFileId == null ? "" : issue.affectedFileId);
        response.put("affectedFile", fileName == null ? "Session" : fileName);
        response.put("affectedRows", issue.affectedRows);
        response.put("suggestedAction", nullSafe(issue.suggestedAction));
        response.put("availableChoices", issue.availableChoices);
        response.put("context", issue.contextJson);
        response.put("resolved", issue.resolved);
        response.put("resolution", issue.resolutionJson);
        return response;
    }

    private List<Map<String, Object>> fileTypeChoices() {
        return Arrays.stream(DomainEnums.DetectedFileType.values())
            .filter(type -> type != DomainEnums.DetectedFileType.UNKNOWN)
            .map(type -> {
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("action", "OVERRIDE_FILE_TYPE");
                choice.put("label", type.name().replace('_', ' '));
                choice.put("selectedFileType", type.name());
                return choice;
            }).toList();
    }

    private Map<String, Object> choice(String action, String label, UUID targetId) {
        var choice = new LinkedHashMap<String, Object>();
        choice.put("action", action);
        choice.put("label", label);
        if (targetId != null) choice.put("targetId", targetId.toString());
        return choice;
    }

    private boolean ignored(Map<String, SmartImportResolution> resolutions, String key) {
        var resolution = resolutions.get(key);
        return resolution != null && "IGNORE_WARNING".equals(resolution.action);
    }

    private List<Product> possibleProducts(UUID tenantId, List<Product> existingProducts, String normalizedName, String unitCode) {
        if (normalizedName == null || normalizedName.isBlank()) return List.of();
        return existingProducts.stream()
            .filter(product -> product.normalizedName != null)
            .filter(product -> normalizeUnitOfProduct(tenantId, product).equals(nullSafe(unitCode)) || product.normalizedName.equalsIgnoreCase(normalizedName) || similarity(product.normalizedName, normalizedName) >= 0.72)
            .filter(product -> product.normalizedName.equalsIgnoreCase(normalizedName) || similarity(product.normalizedName, normalizedName) >= 0.72)
            .toList();
    }

    private String normalizeUnitOfProduct(UUID tenantId, Product product) {
        if (product.baseUnitId == null) return "";
        return units.findByTenantIdAndId(tenantId, product.baseUnitId).map(unit -> nullSafe(normalizeUnit(unit.code))).orElse("");
    }

    private Product findByExternalId(List<Product> existingProducts, String externalId) {
        if (externalId == null) return null;
        for (var product : existingProducts) {
            if (product.rawMetadata == null) continue;
            var candidate = SmartImportProductIdentity.fromProductMetadata(product.rawMetadata);
            if (candidate != null && externalId.equalsIgnoreCase(candidate.trim())) return product;
        }
        return null;
    }

    private void productMatched(SmartStagedProduct row, Product product, DomainEnums.SmartReviewStatus reviewStatus) {
        row.matchedProductId = product.id;
        row.matchStatus = DomainEnums.SmartMatchStatus.MATCH_EXISTING;
        row.reviewStatus = reviewStatus;
    }

    private void warehouseMatched(SmartStagedWarehouse row, UUID warehouseId, DomainEnums.SmartReviewStatus reviewStatus) {
        row.matchedWarehouseId = warehouseId;
        row.matchStatus = DomainEnums.SmartMatchStatus.MATCH_EXISTING;
        row.reviewStatus = reviewStatus;
    }

    private ExistingVoucherStatus existingVoucherStatus(UUID tenantId, SmartStagedVoucher voucher) {
        if (voucher.voucherNumber == null || voucher.voucherDate == null) return ExistingVoucherStatus.NONE;
        var normalized = normalize(voucher.voucherType);
        if (normalized.contains("DEBIT")) {
            return financialAdjustments.findByTenantIdAndAdjustmentTypeAndVoucherNumberIgnoreCase(
                    tenantId, DomainEnums.FinancialAdjustmentType.DEBIT_NOTE, voucher.voucherNumber)
                .map(adjustment -> sameVoucher(adjustment.voucherDate, adjustment.supplierId, adjustment.totalAmount, voucher)
                    ? ExistingVoucherStatus.EXACT_DUPLICATE : ExistingVoucherStatus.AMBIGUOUS_CONFLICT)
                .orElse(ExistingVoucherStatus.NONE);
        }
        if (normalized.contains("CREDIT")) {
            return financialAdjustments.findByTenantIdAndAdjustmentTypeAndVoucherNumberIgnoreCase(
                    tenantId, DomainEnums.FinancialAdjustmentType.CREDIT_NOTE, voucher.voucherNumber)
                .map(adjustment -> sameVoucher(adjustment.voucherDate, adjustment.customerId, adjustment.totalAmount, voucher)
                    ? ExistingVoucherStatus.EXACT_DUPLICATE : ExistingVoucherStatus.AMBIGUOUS_CONFLICT)
                .orElse(ExistingVoucherStatus.NONE);
        }
        if (normalized.contains("PURCHASE")) {
            return invoiceIdentity.matchPurchase(tenantId, voucher.matchedPartyId, voucher.voucherNumber, voucher.voucherDate)
                .map(invoice -> sameVoucher(invoice.invoiceDate, invoice.supplierId, invoice.totalAmount, voucher)
                    ? ExistingVoucherStatus.EXACT_DUPLICATE : ExistingVoucherStatus.AMBIGUOUS_CONFLICT)
                .orElse(ExistingVoucherStatus.NONE);
        }
        if (normalized.contains("SALES") || normalized.contains("SALE")) {
            return invoiceIdentity.matchSale(tenantId, voucher.voucherNumber, voucher.voucherDate)
                .map(invoice -> sameVoucher(invoice.invoiceDate, invoice.customerId, invoice.totalAmount, voucher)
                    ? ExistingVoucherStatus.EXACT_DUPLICATE : ExistingVoucherStatus.AMBIGUOUS_CONFLICT)
                .orElse(ExistingVoucherStatus.NONE);
        }
        return ExistingVoucherStatus.NONE;
    }

    private boolean sameVoucher(LocalDate date, UUID partyId, BigDecimal total, SmartStagedVoucher voucher) {
        return Objects.equals(date, voucher.voucherDate)
            && partyId != null && voucher.matchedPartyId != null
            && Objects.equals(partyId, voucher.matchedPartyId)
            && nvl(total).compareTo(nvl(voucher.totalAmount)) == 0;
    }

    private enum ExistingVoucherStatus { NONE, EXACT_DUPLICATE, AMBIGUOUS_CONFLICT }

    private String fingerprint(UUID tenantId, SmartStagedVoucher voucher) {
        var raw = tenantId + "|" + normalize(voucher.voucherType) + "|" + normalize(voucher.voucherNumber) + "|"
            + voucher.voucherDate + "|" + nullSafe(voucher.normalizedPartyName) + "|" + nvl(voucher.totalAmount).stripTrailingZeros().toPlainString();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String cashbookFingerprint(UUID tenantId, SmartStagedCashbookEntry entry) {
        var raw = tenantId + "|" + entry.entryDate + "|" + normalizeName(entry.partyName) + "|"
            + nvl(entry.amount).stripTrailingZeros().toPlainString() + "|" + entry.direction + "|"
            + normalize(entry.referenceNumber);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private DomainEnums.PaymentMode paymentMode(String rawMode, String partyName) {
        var normalized = normalize(firstNonBlank(rawMode, partyName));
        if (normalized.contains("UPI")) return DomainEnums.PaymentMode.UPI;
        if (normalized.contains("CHEQUE") || normalized.contains("CHECK")) return DomainEnums.PaymentMode.CHEQUE;
        if (normalized.contains("BANK") || normalized.contains("NEFT") || normalized.contains("RTGS")
            || normalized.contains("IMPS")) return DomainEnums.PaymentMode.BANK;
        if (normalized.contains("CASH")) return DomainEnums.PaymentMode.CASH;
        return DomainEnums.PaymentMode.OTHER;
    }

    private DomainEnums.SmartPartyType partyType(DomainEnums.DetectedFileType sourceType, Map<String, String> row) {
        if (sourceType == DomainEnums.DetectedFileType.SALES_VOUCHERS || sourceType == DomainEnums.DetectedFileType.CREDIT_NOTES) {
            return DomainEnums.SmartPartyType.CUSTOMER;
        }
        if (sourceType == DomainEnums.DetectedFileType.PURCHASE_VOUCHERS || sourceType == DomainEnums.DetectedFileType.DEBIT_NOTES) {
            return DomainEnums.SmartPartyType.SUPPLIER;
        }
        var group = normalize(lookup(row, "Party Type", "Ledger Group", "Parent", "Group"));
        if (group.contains("CUSTOMER") || group.contains("DEBTOR") || group.contains("RECEIVABLE")) return DomainEnums.SmartPartyType.CUSTOMER;
        if (group.contains("SUPPLIER") || group.contains("CREDITOR") || group.contains("PAYABLE")) return DomainEnums.SmartPartyType.SUPPLIER;
        return DomainEnums.SmartPartyType.UNKNOWN;
    }

    private String defaultVoucherType(DomainEnums.DetectedFileType type) {
        return switch (type) {
            case SALES_VOUCHERS -> "Sales";
            case PURCHASE_VOUCHERS -> "Purchase";
            case CREDIT_NOTES -> "Credit Note";
            case DEBIT_NOTES -> "Debit Note";
            case STOCK_JOURNAL -> "Stock Journal";
            default -> type.name();
        };
    }

    private ImportSession session(UUID sessionId) {
        return sessions.findByTenantIdAndId(TenantContext.tenantId(), sessionId)
            .orElseThrow(() -> ApiErrors.notFound("Import session not found"));
    }

    private void ensureMutable(ImportSession session) {
        if (session.status == DomainEnums.ImportSessionStatus.CANCELLED) throw ApiErrors.conflict("Cancelled import sessions cannot be changed");
        if (session.status == DomainEnums.ImportSessionStatus.COMMITTING || session.status == DomainEnums.ImportSessionStatus.COMMITTED) {
            throw ApiErrors.conflict("This import session can no longer be changed");
        }
    }

    private void own(TenantOwnedEntity entity, ImportSession session, ImportSessionFile file, int rowNumber) {
        entity.tenantId = session.tenantId;
        entity.createdBy = TenantContext.userId();
        entity.updatedBy = TenantContext.userId();
        if (entity instanceof SmartStagedProduct row) { row.importSessionId = session.id; row.sessionFileId = file.id; row.sourceRowNumber = rowNumber; }
        else if (entity instanceof SmartStagedParty row) { row.importSessionId = session.id; row.sessionFileId = file.id; row.sourceRowNumber = rowNumber; }
        else if (entity instanceof SmartStagedWarehouse row) { row.importSessionId = session.id; row.sessionFileId = file.id; row.sourceRowNumber = rowNumber; }
        else if (entity instanceof SmartStagedUnit row) { row.importSessionId = session.id; row.sessionFileId = file.id; row.sourceRowNumber = rowNumber; }
        else if (entity instanceof SmartStagedStockSnapshot row) { row.importSessionId = session.id; row.sessionFileId = file.id; row.sourceRowNumber = rowNumber; }
        else if (entity instanceof SmartStagedVoucher row) { row.importSessionId = session.id; row.sessionFileId = file.id; row.sourceRowNumber = rowNumber; }
        else if (entity instanceof SmartStagedVoucherItem row) { row.importSessionId = session.id; row.sessionFileId = file.id; row.sourceRowNumber = rowNumber; }
        else if (entity instanceof SmartStagedCashbookEntry row) { row.importSessionId = session.id; row.sessionFileId = file.id; row.sourceRowNumber = rowNumber; }
        else if (entity instanceof SmartStagedStockAgeing row) { row.importSessionId = session.id; row.sessionFileId = file.id; row.sourceRowNumber = rowNumber; }
    }

    private Map<String, Object> productRow(SmartStagedProduct row) {
        return linked("id", row.id, "fileId", row.sessionFileId, "row", row.sourceRowNumber, "name", row.rawName,
            "normalizedName", row.normalizedName, "unitCode", row.unitCode, "sku", row.sku, "category", row.categoryName,
            "matchedProductId", row.matchedProductId, "matchStatus", row.matchStatus, "reviewStatus", row.reviewStatus);
    }

    private Map<String, Object> partyRow(SmartStagedParty row) {
        return linked("id", row.id, "fileId", row.sessionFileId, "row", row.sourceRowNumber, "name", row.rawName,
            "partyType", row.partyType, "sourceType", row.sourceType, "gstin", row.gstin,
            "outstandingAmount", row.openingBalance, "snapshotDate", row.snapshotDate,
            "matchedCustomerId", row.matchedCustomerId,
            "matchedSupplierId", row.matchedSupplierId, "matchStatus", row.matchStatus, "reviewStatus", row.reviewStatus);
    }

    private Map<String, Object> warehouseRow(SmartStagedWarehouse row) {
        return linked("id", row.id, "fileId", row.sessionFileId, "name", row.sourceWarehouseName,
            "matchedWarehouseId", row.matchedWarehouseId, "matchStatus", row.matchStatus, "reviewStatus", row.reviewStatus);
    }

    private Map<String, Object> unitRow(SmartStagedUnit row) {
        return linked("id", row.id, "fileId", row.sessionFileId, "code", row.sourceUnitCode,
            "matchedUnitId", row.matchedUnitId, "matchStatus", row.matchStatus, "reviewStatus", row.reviewStatus);
    }

    private Map<String, Object> snapshotRow(SmartStagedStockSnapshot row) {
        return linked("id", row.id, "fileId", row.sessionFileId, "row", row.sourceRowNumber, "productName", row.productName,
            "unitCode", row.unitCode, "warehouseName", row.warehouseName, "snapshotDate", row.snapshotDate,
            "currentStock", row.currentStock, "importedStock", row.importedStock, "deltaPreview", row.deltaPreview,
            "matchedProductId", row.matchedProductId, "matchedWarehouseId", row.matchedWarehouseId,
            "matchStatus", row.matchStatus, "reviewStatus", row.reviewStatus, "action", row.action);
    }

    private Map<String, Object> voucherRow(SmartStagedVoucher row) {
        return linked("id", row.id, "fileId", row.sessionFileId, "row", row.sourceRowNumber, "voucherType", row.voucherType,
            "voucherNumber", row.voucherNumber, "voucherDate", row.voucherDate, "partyName", row.partyName,
            "totalAmount", row.totalAmount, "matchedPartyId", row.matchedPartyId, "matchStatus", row.matchStatus,
            "reviewStatus", row.reviewStatus, "stockImpactModeSuggestion", row.stockImpactModeSuggestion,
            "taxAmount", row.taxAmount, "discountAmount", row.discountAmount, "freightAmount", row.freightAmount,
            "roundOffAmount", row.roundOffAmount, "otherChargesAmount", row.otherChargesAmount,
            "taxLineCount", row.taxLineCount, "discountLineCount", row.discountLineCount,
            "freightLineCount", row.freightLineCount, "roundOffLineCount", row.roundOffLineCount,
            "otherChargeLineCount", row.otherChargeLineCount, "returnClassification", row.returnClassification,
            "returnResolutionStatus", row.returnResolutionStatus, "returnConfidence", row.returnConfidence,
            "returnDetectionReason", row.returnDetectionReason);
    }

    private Map<String, Object> voucherItemRow(SmartStagedVoucherItem row) {
        return linked("id", row.id, "voucherId", row.stagedVoucherId, "fileId", row.sessionFileId, "row", row.sourceRowNumber,
            "productName", row.productName, "unitCode", row.unitCode, "quantity", row.quantity, "rate", row.rate,
            "amount", row.amount, "warehouseName", row.warehouseName, "rateSource", row.rateSource,
            "matchedProductId", row.matchedProductId, "matchedWarehouseId", row.matchedWarehouseId,
            "matchStatus", row.matchStatus, "reviewStatus", row.reviewStatus,
            "rawQuantity", row.rawQuantity, "billedQuantity", row.billedQuantity,
            "actualQuantity", row.actualQuantity, "returnDirection", row.returnDirection,
            "returnDisposition", row.returnDisposition, "returnResolutionStatus", row.returnResolutionStatus,
            "returnConfidence", row.returnConfidence, "returnDetectionReason", row.returnDetectionReason);
    }

    private Map<String, Object> transferRow(SmartStagedTransferCandidate row) {
        return linked("id", row.id, "voucherId", row.stagedVoucherId, "fileId", row.sessionFileId,
            "row", row.sourceRowNumber, "voucherNumber", row.voucherNumber, "voucherDate", row.voucherDate,
            "productName", row.productName, "productId", row.matchedProductId, "unitCode", row.unitCode,
            "sourceWarehouseName", row.sourceWarehouseName, "sourceWarehouseId", row.sourceWarehouseId,
            "destinationWarehouseName", row.destinationWarehouseName,
            "destinationWarehouseId", row.destinationWarehouseId,
            "rawOutwardQuantity", row.rawOutwardQuantity, "rawInwardQuantity", row.rawInwardQuantity,
            "normalizedQuantity", row.normalizedQuantity, "classification", row.classification,
            "classificationConfidence", row.classificationConfidence, "decisionReasons", row.decisionReasons,
            "resolutionStatus", row.resolutionStatus, "sourceSnapshotDate", row.sourceSnapshotDate,
            "destinationSnapshotDate", row.destinationSnapshotDate);
    }

    private Map<String, Object> cashbookRow(SmartStagedCashbookEntry row) {
        return linked("id", row.id, "fileId", row.sessionFileId, "row", row.sourceRowNumber, "date", row.entryDate,
            "partyName", row.partyName, "amount", row.amount, "direction", row.direction,
            "matchedPartyId", row.matchedPartyId, "matchedInvoiceId", row.matchedInvoiceId,
            "matchedPartyType", row.matchedPartyType, "cashbookMatchStatus", row.cashbookMatchStatus,
            "paymentMode", row.paymentMode, "referenceNumber", row.referenceNumber,
            "matchConfidence", row.matchConfidence, "matchReason", row.matchReason,
            "resolutionStatus", row.resolutionStatus, "manualResolutionAction", row.manualResolutionAction,
            "customerPaymentId", row.customerPaymentId, "supplierPaymentId", row.supplierPaymentId,
            "resolvedAt", row.resolvedAt, "resolutionNote", row.resolutionNote,
            "matchStatus", row.matchStatus, "reviewStatus", row.reviewStatus);
    }

    private boolean cashbookReady(SmartStagedCashbookEntry row) {
        return SmartImportCashbookMatchingService.ready(row);
    }

    private Map<String, Object> ageingRow(SmartStagedStockAgeing row) {
        return linked("id", row.id, "fileId", row.sessionFileId, "row", row.sourceRowNumber, "productName", row.productName,
            "unitCode", row.unitCode, "quantity", row.quantity, "ageingBucket", row.ageingBucket, "daysOld", row.daysOld,
            "value", row.stockValue, "matchedProductId", row.matchedProductId, "matchStatus", row.matchStatus, "reviewStatus", row.reviewStatus);
    }

    private Map<String, Object> linked(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1] == null ? "" : values[index + 1]);
        }
        return result;
    }

    private String lookup(Map<String, String> row, String... aliases) {
        for (var alias : aliases) {
            var expected = normalizeKey(alias);
            for (var entry : row.entrySet()) {
                if (normalizeKey(entry.getKey()).equals(expected) && entry.getValue() != null && !entry.getValue().isBlank()) {
                    return entry.getValue().trim();
                }
            }
        }
        return null;
    }

    private String raw(Map<String, Object> row, String... aliases) {
        for (var alias : aliases) {
            var expected = normalizeKey(alias);
            for (var entry : row.entrySet()) {
                if (normalizeKey(entry.getKey()).equals(expected) && entry.getValue() != null) return String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    private Map<String, Object> metadata(Map<String, String> row) {
        var result = new LinkedHashMap<String, Object>();
        result.putAll(row);
        return result;
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var cleaned = value.replace(",", "").replaceAll("[^0-9.\\-]", "");
            return cleaned.isBlank() || "-".equals(cleaned) ? null : new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDate date(String value) {
        return ImportDates.parse(value);
    }

    private Integer integer(String value) {
        var number = decimal(value);
        return number == null ? null : number.setScale(0, RoundingMode.DOWN).intValue();
    }

    private BigDecimal abs(BigDecimal value) {
        return value == null ? null : value.abs();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private String normalizeName(String value) {
        return CatalogService.normalizeName(value);
    }

    private String normalizeUnit(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private String normalizeKey(String value) {
        return normalize(value).replaceAll("[^A-Z0-9]", "");
    }

    private String productKey(String normalizedName, String unitCode) {
        return nullSafe(normalizedName) + "|" + nullSafe(unitCode);
    }

    private String productResolutionKey(String normalizedName, String unitCode) {
        return "PRODUCT:" + productKey(normalizedName, unitCode);
    }

    private String warehouseResolutionKey(String normalizedName) {
        return "WAREHOUSE:" + nullSafe(normalizedName);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (var value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private UUID firstNonNull(UUID first, UUID second) {
        return first == null ? second : first;
    }

    private String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double similarity(String left, String right) {
        var a = normalizeKey(left);
        var b = normalizeKey(right);
        if (a.equals(b)) return 1.0;
        if (a.isBlank() || b.isBlank()) return 0;
        var previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            var current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                var cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return 1.0 - ((double) previous[b.length()] / Math.max(a.length(), b.length()));
    }

    private DomainEnums.DetectedFileType effectiveType(ImportSessionFile file) {
        return file.selectedFileType == null ? file.detectedFileType : file.selectedFileType;
    }

    private String safeMessage(Exception ex) {
        var message = ex.getMessage();
        return message == null || message.isBlank() ? "invalid file content" : message.replaceAll("[\\r\\n]+", " ");
    }

    private record ParseFailure(ImportSessionFile file, int rowNumber, String code, String message) { }
}
