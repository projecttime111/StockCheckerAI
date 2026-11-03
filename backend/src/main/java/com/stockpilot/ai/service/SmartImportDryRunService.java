package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SmartImportDryRunService {
    private static final Logger log = LoggerFactory.getLogger(SmartImportDryRunService.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final List<String> SUMMARY_KEYS = List.of(
        "filesProcessed", "duplicateFilesSkipped", "unknownFiles",
        "productsToCreate", "productsMatchedExisting", "possibleDuplicateProducts", "productsReviewRequired",
        "customersToCreate", "suppliersToCreate", "partiesMatchedExisting", "partiesReviewRequired",
        "warehousesToCreate", "warehousesMatchedExisting", "warehousesReviewRequired",
        "stockSnapshotsProcessed", "snapshotAdjustmentsPreviewed", "snapshotNoChangeRows",
        "stockMovementsPreviewed", "stockMovementsSkippedDueToInvoiceOnly", "stockMovementsSkippedBeforeSnapshotDate",
        "salesVoucherItemsEligibleForStock", "purchaseVoucherItemsEligibleForStock",
        "salesStockMovementsPlanned", "purchaseStockMovementsPlanned",
        "voucherItemsCoveredBySnapshot", "sameDateVoucherItemsDeferred", "voucherItemsWithoutSnapshot",
        "voucherItemsBlockedByWarehouse", "movementsBlockedByNegativeStock", "movementsSkippedByPolicy",
        "invoicesOnlyPlanned", "projectedStockChanges", "productLevelSnapshotFallbacks",
        "negativeStockRowsFound", "negativeStockRowsSkipped", "negativeStockRowsBlocked",
        "purchaseInvoicesPreviewed", "salesInvoicesPreviewed", "creditNotesPreviewed", "debitNotesPreviewed",
        "voucherItemsReady", "vouchersBlocked", "duplicateVouchersSkipped", "vouchersExcludedByUser",
        "debtorRowsProcessed", "creditorRowsProcessed", "outstandingSnapshotsPreviewed",
        "cashbookRowsReady", "cashbookRowsMatched", "cashbookRowsUnmatched", "cashbookRowsReviewRequired",
        "customerPaymentsPreviewed", "supplierPaymentsPreviewed", "paymentsLinkedToSalesInvoices", "retailCounterSaleRowsResolved",
        "paymentsLinkedToPurchaseInvoices", "duplicatePaymentsSkipped",
        "ageingRowsMatched", "ageingRowsUnmatched", "ratesDerived", "verifiedRatesApplied", "zeroRateItems", "zeroCostItems",
        "ratesSignNormalized", "ledgerLinesSkipped", "invalidRateRows",
        "taxLinesCaptured", "discountLinesCaptured", "freightLinesCaptured", "roundOffLinesCaptured", "otherChargeLinesCaptured",
        "physicalSalesReturnItemsDetected", "physicalPurchaseReturnItemsDetected", "mixedReturnNotesDetected",
        "financialOnlyNotes", "ambiguousReturnNotes", "returnItemsCoveredBySnapshot",
        "sameDateReturnItemsDeferred", "returnItemsWithoutSnapshot", "returnItemsBlockedByWarehouse",
        "damagedOrNonSaleableReturnsDeferred", "returnInMovementsPlanned", "returnOutMovementsPlanned",
        "returnMovementsBlockedByNegativeStock", "duplicateReturnMovementsSkipped",
        "stockJournalsDetected", "warehouseTransfersDetected", "warehouseTransfersAutoConfirmed",
        "warehouseTransfersReviewRequired", "warehouseTransfersEligible",
        "warehouseTransfersCoveredBySnapshot", "sameDateWarehouseTransfersDeferred",
        "warehouseTransfersMissingBaseline", "warehouseTransfersBlockedByWarehouse",
        "warehouseTransfersBlockedByProduct", "warehouseTransfersBlockedByQuantity",
        "warehouseTransfersBlockedByNegativeStock", "warehouseTransfersSkippedByPolicy",
        "duplicateWarehouseTransfersSkipped", "transferOutMovementsPlanned",
        "transferInMovementsPlanned", "manufacturingJournalsDeferred",
        "adjustmentJournalsDeferred", "ambiguousStockJournalsDeferred",
        "warnings", "blockingErrors", "reviewRequiredCount"
    );

    private final Repositories.ImportSessionRepository sessions;
    private final Repositories.ImportSessionFileRepository files;
    private final Repositories.ImportPlanRepository plans;
    private final Repositories.ImportPlanIssueRepository planIssues;
    private final Repositories.ImportDryRunRepository dryRuns;
    private final Repositories.ImportDryRunItemRepository dryRunItems;
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
    private final Repositories.WarehouseRepository warehouses;
    private final Repositories.ProductRepository products;
    private final Repositories.UnitRepository units;
    private final Repositories.SalesInvoiceRepository salesInvoices;
    private final Repositories.PurchaseInvoiceRepository purchaseInvoices;
    private final Repositories.FinancialAdjustmentRepository financialAdjustments;
    private final Repositories.CustomerPaymentRepository customerPayments;
    private final Repositories.SupplierPaymentRepository supplierPayments;
    private final Repositories.StockMovementRepository stockMovements;
    private final StockLedgerService stockLedger;
    private final AuditService audit;
    private final SmartImportFingerprintService fingerprints;
    private final SmartImportSnapshotDateService snapshotDates;
    private final SmartImportSnapshotEligibilityService snapshotEligibility;
    private final SmartImportReturnService returnService;
    private final SmartImportTransferService transferService;
    private final RetailCounterSaleService retailSales;
    private final EntityManager entityManager;
    private final SmartImportCashbookMatchingService cashbookMatching;

    public SmartImportDryRunService(
        Repositories.ImportSessionRepository sessions,
        Repositories.ImportSessionFileRepository files,
        Repositories.ImportPlanRepository plans,
        Repositories.ImportPlanIssueRepository planIssues,
        Repositories.ImportDryRunRepository dryRuns,
        Repositories.ImportDryRunItemRepository dryRunItems,
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
        Repositories.WarehouseRepository warehouses,
        Repositories.ProductRepository products,
        Repositories.UnitRepository units,
        Repositories.SalesInvoiceRepository salesInvoices,
        Repositories.PurchaseInvoiceRepository purchaseInvoices,
        Repositories.FinancialAdjustmentRepository financialAdjustments,
        Repositories.CustomerPaymentRepository customerPayments,
        Repositories.SupplierPaymentRepository supplierPayments,
        Repositories.StockMovementRepository stockMovements,
        StockLedgerService stockLedger,
        AuditService audit,
        SmartImportFingerprintService fingerprints,
        SmartImportSnapshotDateService snapshotDates,
        SmartImportSnapshotEligibilityService snapshotEligibility,
        SmartImportReturnService returnService,
        SmartImportTransferService transferService,
        RetailCounterSaleService retailSales,
        EntityManager entityManager,
        SmartImportCashbookMatchingService cashbookMatching
    ) {
        this.sessions = sessions;
        this.files = files;
        this.plans = plans;
        this.planIssues = planIssues;
        this.dryRuns = dryRuns;
        this.dryRunItems = dryRunItems;
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
        this.warehouses = warehouses;
        this.products = products;
        this.units = units;
        this.salesInvoices = salesInvoices;
        this.purchaseInvoices = purchaseInvoices;
        this.financialAdjustments = financialAdjustments;
        this.customerPayments = customerPayments;
        this.supplierPayments = supplierPayments;
        this.stockMovements = stockMovements;
        this.stockLedger = stockLedger;
        this.audit = audit;
        this.fingerprints = fingerprints;
        this.snapshotDates = snapshotDates;
        this.snapshotEligibility = snapshotEligibility;
        this.returnService = returnService;
        this.transferService = transferService;
        this.retailSales = retailSales;
        this.entityManager = entityManager;
        this.cashbookMatching = cashbookMatching;
    }

    @Transactional
    public Map<String, Object> run(UUID sessionId, SmartImportDtos.DryRunRequest request) {
        var session = session(sessionId);
        var plan = plans.findByTenantIdAndImportSessionId(session.tenantId, session.id)
            .orElseThrow(() -> ApiErrors.badRequest("Stage the Smart Import session before running a dry run"));
        if (!Boolean.TRUE.equals(plan.planJson.get("stagingComplete"))) {
            throw ApiErrors.badRequest("Stage the Smart Import session before running a dry run");
        }
        var strategy = request != null && request.strategy() != null ? request.strategy() : session.recommendedStrategy;
        if (strategy == null || strategy == DomainEnums.ImportStrategy.UNKNOWN) {
            throw ApiErrors.badRequest("Choose a dry-run strategy before simulating stock impact");
        }
        var stockImpactMode = request != null && request.stockImpactMode() != null
            ? request.stockImpactMode() : defaultStockImpactMode(strategy);
        if (stockImpactMode != DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY
            && stockImpactMode != DomainEnums.VoucherStockImpactMode.APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE) {
            throw ApiErrors.badRequest("Smart Import supports invoice-only or post-snapshot stock movements in this phase");
        }
        var negativePolicy = request != null && request.negativeStockPolicy() != null
            ? request.negativeStockPolicy() : DomainEnums.NegativeStockImportPolicy.BLOCK;
        var returnStockImpactMode = request != null && request.returnStockImpactMode() != null
            ? request.returnStockImpactMode() : DomainEnums.ReturnStockImpactMode.FINANCIAL_ONLY;
        var stockJournalImpactMode = request != null && request.stockJournalImpactMode() != null
            ? request.stockJournalImpactMode() : DomainEnums.StockJournalImpactMode.DEFER_STOCK_JOURNALS;
        var dates = snapshotDates.fromRequest(request);
        snapshotDates.validateSession(session.tenantId, session.id, dates);
        var requestedSnapshotDate = dates.stockDate();
        cashbookMatching.refresh(session.tenantId, session.id);
        cashbookMatching.refreshReviewIssues(session.tenantId, session.id);
        entityManager.clear();

        dryRunItems.deleteByTenantIdAndImportSessionId(session.tenantId, session.id);
        dryRuns.deleteByTenantIdAndImportSessionId(session.tenantId, session.id);

        var dryRun = new ImportDryRun();
        dryRun.tenantId = session.tenantId;
        dryRun.importSessionId = session.id;
        dryRun.strategy = strategy;
        dryRun.status = DomainEnums.ImportDryRunStatus.RUNNING;
        dryRun.startedAt = Instant.now();
        dryRun.createdBy = TenantContext.userId();
        dryRun.updatedBy = TenantContext.userId();
        // Preview stages clear the persistence context. Flush the parent first so its pending
        // insert is not discarded and every generated item has a valid dry_run_id.
        dryRun = dryRuns.saveAndFlush(dryRun);

        var counters = new Counters();
        var generated = new ArrayList<ImportDryRunItem>();
        try {
            var context = new DryRunContext(session, dryRun, strategy, stockImpactMode, returnStockImpactMode,
                stockJournalImpactMode, negativePolicy, requestedSnapshotDate, dates, counters, generated);
            previewFiles(context);
            previewMasterData(context);
            previewSnapshots(context);
            previewVouchers(context);
            previewTransfers(context);
            // Flush baseline evidence before capturing the stale-plan fingerprint. Hibernate assigns
            // updatedAt during flush; capturing first would make a completed dry run stale immediately.
            stagedTransfers.flush();
            // Dry runs load thousands of staging rows with JSON maps. Detach that read-only graph before
            // later queries so Hibernate does not repeatedly deep-compare every map during auto-flush.
            entityManager.clear();
            previewCashbookAndAgeing(context);
            previewUnresolvedPlanIssues(context);

            dryRunItems.saveAll(generated);
            counters.set("warnings", generated.stream().filter(item -> item.warningCode != null).count());
            counters.set("blockingErrors", generated.stream().filter(item -> item.errorCode != null
                || item.action == DomainEnums.ImportDryRunAction.BLOCKED).count());
            counters.set("reviewRequiredCount", generated.stream()
                .filter(item -> item.action == DomainEnums.ImportDryRunAction.REVIEW_REQUIRED).count());
            dryRun.summaryJson = counters.response(strategy, stockImpactMode, returnStockImpactMode,
                stockJournalImpactMode, negativePolicy, requestedSnapshotDate);
            dryRun.summaryJson.putAll(dates.summary());
            dryRun.summaryJson.put("cashbookMatchPolicyVersion", SmartImportCashbookMatchingService.POLICY_VERSION);
            dryRun.inputFingerprint = dryRunFingerprint(session.tenantId, session.id, stockImpactMode,
                returnStockImpactMode, stockJournalImpactMode, negativePolicy, dates);
            dryRun.status = DomainEnums.ImportDryRunStatus.COMPLETED;
            dryRun.finishedAt = Instant.now();
            dryRuns.save(dryRun);
            plan.planJson.put("phase", "TRANSACTION_SAFE_DRY_RUN");
            plan.planJson.put("dryRunAvailable", true);
            plan.planJson.put("commitEnabled", false);
            plans.save(plan);
            audit.logCurrent("SMART_IMPORT_DRY_RUN_COMPLETED", "ImportDryRun", dryRun.id, Map.of(
                "sessionId", session.id,
                "strategy", strategy.name(),
                "snapshotDates", dates.summary(),
                "blockingErrors", counters.get("blockingErrors"),
                "reviewRequired", counters.get("reviewRequiredCount")
            ));
        } catch (RuntimeException ex) {
            log.error("Smart Import dry run failed for tenant {} session {}", session.tenantId, session.id, ex);
            dryRun.status = DomainEnums.ImportDryRunStatus.FAILED;
            dryRun.finishedAt = Instant.now();
            dryRun.summaryJson = new LinkedHashMap<>(Map.of(
                "dryRunOnly", true,
                "error", "Dry run could not be completed safely",
                "strategy", strategy.name()
            ));
            dryRuns.save(dryRun);
            audit.logCurrent("SMART_IMPORT_DRY_RUN_FAILED", "ImportDryRun", dryRun.id, Map.of("sessionId", session.id));
        }
        return response(dryRun);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> latest(UUID sessionId) {
        var session = session(sessionId);
        return dryRuns.findFirstByTenantIdAndImportSessionIdOrderByStartedAtDesc(session.tenantId, session.id)
            .map(this::response)
            .orElseGet(() -> new LinkedHashMap<>(Map.of("available", false, "status", "NOT_RUN")));
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> items(
        UUID sessionId,
        DomainEnums.ImportDryRunItemType itemType,
        DomainEnums.ImportDryRunAction action,
        String issueKind,
        UUID sourceFileId,
        Pageable pageable
    ) {
        var session = session(sessionId);
        var dryRun = dryRuns.findFirstByTenantIdAndImportSessionIdOrderByStartedAtDesc(session.tenantId, session.id)
            .orElseThrow(() -> ApiErrors.notFound("Run a dry run before viewing dry-run items"));
        var normalizedIssueKind = normalizeIssueKind(issueKind);
        return dryRunItems.search(session.tenantId, dryRun.id, itemType, action, normalizedIssueKind, sourceFileId, pageable)
            .map(this::itemResponse);
    }

    private void previewFiles(DryRunContext context) {
        for (var file : files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(context.session.tenantId, context.session.id)) {
            if (file.status == DomainEnums.ImportSessionFileStatus.DUPLICATE) {
                context.counters.inc("duplicateFilesSkipped");
                context.items.add(item(context, DomainEnums.ImportDryRunItemType.FILE, DomainEnums.ImportDryRunAction.SKIP,
                    file.id, null, null, linked("fileName", file.originalFileName, "fileHash", file.fileHash,
                        "reason", "Exact duplicate file hash; only the first copy is simulated."), "DUPLICATE_FILE", null));
            } else if (effectiveType(file) == DomainEnums.DetectedFileType.UNKNOWN || file.status == DomainEnums.ImportSessionFileStatus.FAILED) {
                context.counters.inc("unknownFiles");
                context.items.add(item(context, DomainEnums.ImportDryRunItemType.FILE, DomainEnums.ImportDryRunAction.REVIEW_REQUIRED,
                    file.id, null, null, linked("fileName", file.originalFileName, "reason", file.detectionReason),
                    "UNKNOWN_FILE_TYPE", null));
            } else {
                context.counters.inc("filesProcessed");
            }
        }
    }

    private void previewMasterData(DryRunContext context) {
        for (var row : stagedProducts.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(context.session.tenantId, context.session.id)) {
            var action = action(row.matchStatus);
            if (action == DomainEnums.ImportDryRunAction.CREATE) context.counters.inc("productsToCreate");
            if (action == DomainEnums.ImportDryRunAction.MATCH_EXISTING) context.counters.inc("productsMatchedExisting");
            if (action == DomainEnums.ImportDryRunAction.REVIEW_REQUIRED) {
                context.counters.inc("productsReviewRequired");
                if (row.matchStatus == DomainEnums.SmartMatchStatus.POSSIBLE_DUPLICATE_REVIEW
                    || row.matchStatus == DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW) {
                    context.counters.inc("possibleDuplicateProducts");
                }
            }
            context.items.add(item(context, DomainEnums.ImportDryRunItemType.PRODUCT, action, row.sessionFileId,
                row.sourceRowNumber, row.matchedProductId, linked("name", row.rawName, "normalizedName", row.normalizedName,
                    "unitCode", row.unitCode, "sku", row.sku, "matchStatus", row.matchStatus),
                action == DomainEnums.ImportDryRunAction.REVIEW_REQUIRED ? "PRODUCT_MATCH_REVIEW_REQUIRED" : null, null));
        }

        for (var row : stagedParties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(context.session.tenantId, context.session.id)) {
            var action = action(row.matchStatus);
            var itemType = row.partyType == DomainEnums.SmartPartyType.SUPPLIER
                ? DomainEnums.ImportDryRunItemType.SUPPLIER : DomainEnums.ImportDryRunItemType.CUSTOMER;
            if (action == DomainEnums.ImportDryRunAction.CREATE && itemType == DomainEnums.ImportDryRunItemType.CUSTOMER) context.counters.inc("customersToCreate");
            if (action == DomainEnums.ImportDryRunAction.CREATE && itemType == DomainEnums.ImportDryRunItemType.SUPPLIER) context.counters.inc("suppliersToCreate");
            if (action == DomainEnums.ImportDryRunAction.MATCH_EXISTING) context.counters.inc("partiesMatchedExisting");
            if (action == DomainEnums.ImportDryRunAction.REVIEW_REQUIRED) context.counters.inc("partiesReviewRequired");
            var targetId = row.matchedCustomerId == null ? row.matchedSupplierId : row.matchedCustomerId;
            context.items.add(item(context, itemType, action, row.sessionFileId, row.sourceRowNumber, targetId,
                linked("name", row.rawName, "partyType", row.partyType, "gstin", row.gstin, "matchStatus", row.matchStatus),
                action == DomainEnums.ImportDryRunAction.REVIEW_REQUIRED ? "PARTY_MATCH_REVIEW_REQUIRED" : null, null));
            if (row.sourceType == DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS) {
                if (row.partyType == DomainEnums.SmartPartyType.CUSTOMER) context.counters.inc("debtorRowsProcessed");
                if (row.partyType == DomainEnums.SmartPartyType.SUPPLIER) context.counters.inc("creditorRowsProcessed");
                var snapshotAction = action == DomainEnums.ImportDryRunAction.REVIEW_REQUIRED
                    ? DomainEnums.ImportDryRunAction.REVIEW_REQUIRED : DomainEnums.ImportDryRunAction.CREATE;
                if (snapshotAction == DomainEnums.ImportDryRunAction.CREATE) context.counters.inc("outstandingSnapshotsPreviewed");
                context.items.add(item(context, DomainEnums.ImportDryRunItemType.OUTSTANDING_SNAPSHOT,
                    snapshotAction, row.sessionFileId, row.sourceRowNumber, targetId,
                    linked("partyName", row.rawName, "partyType", row.partyType,
                        "snapshotDate", context.dates.outstandingDate(row.snapshotDate).toString(),
                        "snapshotDateSource", row.snapshotDate == null ? "USER_CONFIRMED" : "SOURCE_EXPORT",
                        "outstandingAmount", row.openingBalance),
                    snapshotAction == DomainEnums.ImportDryRunAction.REVIEW_REQUIRED ? "OUTSTANDING_PARTY_REVIEW_REQUIRED" : null, null));
            }
        }

        for (var row : stagedWarehouses.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(context.session.tenantId, context.session.id)) {
            var action = action(row.matchStatus);
            if (action == DomainEnums.ImportDryRunAction.CREATE) context.counters.inc("warehousesToCreate");
            if (action == DomainEnums.ImportDryRunAction.MATCH_EXISTING) context.counters.inc("warehousesMatchedExisting");
            if (action == DomainEnums.ImportDryRunAction.REVIEW_REQUIRED || row.reviewStatus == DomainEnums.SmartReviewStatus.PENDING) {
                action = DomainEnums.ImportDryRunAction.REVIEW_REQUIRED;
                context.counters.inc("warehousesReviewRequired");
            }
            context.items.add(item(context, DomainEnums.ImportDryRunItemType.WAREHOUSE, action, row.sessionFileId,
                row.sourceRowNumber, row.matchedWarehouseId, linked("name", row.sourceWarehouseName,
                    "normalizedName", row.normalizedName, "matchStatus", row.matchStatus),
                action == DomainEnums.ImportDryRunAction.REVIEW_REQUIRED ? "WAREHOUSE_REVIEW_REQUIRED" : null, null));
        }

        for (var row : stagedUnits.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(context.session.tenantId, context.session.id)) {
            context.items.add(item(context, DomainEnums.ImportDryRunItemType.UNIT, action(row.matchStatus), row.sessionFileId,
                row.sourceRowNumber, row.matchedUnitId, linked("code", row.sourceUnitCode,
                    "normalizedCode", row.normalizedCode, "matchStatus", row.matchStatus), null, null));
        }
    }

    private void previewSnapshots(DryRunContext context) {
        var sessionFiles = files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(context.session.tenantId, context.session.id).stream()
            .collect(Collectors.toMap(file -> file.id, Function.identity()));
        var tenantWarehouses = warehouses.findByTenantIdAndActiveTrueOrderByNameAsc(context.session.tenantId);
        for (var row : stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(context.session.tenantId, context.session.id)) {
            context.counters.inc("stockSnapshotsProcessed");
            var sourceFile = sessionFiles.get(row.sessionFileId);
            var snapshotDate = context.dates.stockDate(row.snapshotDate);
            var warehouseId = row.matchedWarehouseId;
            if (warehouseId == null && (row.warehouseName == null || row.warehouseName.isBlank()) && tenantWarehouses.size() == 1) {
                warehouseId = tenantWarehouses.getFirst().id;
            }
            var unresolvedIdentity = row.matchStatus == DomainEnums.SmartMatchStatus.UNKNOWN
                || row.matchStatus == DomainEnums.SmartMatchStatus.POSSIBLE_DUPLICATE_REVIEW
                || row.matchStatus == DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW;
            if (warehouseId == null && tenantWarehouses.size() > 1) {
                unresolvedIdentity = true;
            }
            var currentStock = row.matchedProductId == null || warehouseId == null ? ZERO
                : stockLedger.currentStock(context.session.tenantId, row.matchedProductId, warehouseId);
            var stockAtDate = row.matchedProductId == null || warehouseId == null ? ZERO
                : stockLedger.stockAt(context.session.tenantId, row.matchedProductId, warehouseId, snapshotDate);
            var imported = nvl(row.importedStock);
            var delta = imported.subtract(stockAtDate);
            var projectedCurrent = currentStock.add(delta);
            var action = delta.signum() == 0 ? DomainEnums.ImportDryRunAction.NO_CHANGE : DomainEnums.ImportDryRunAction.CREATE;
            String warningCode = null;
            String errorCode = null;
            if (unresolvedIdentity) {
                action = DomainEnums.ImportDryRunAction.REVIEW_REQUIRED;
                warningCode = "SNAPSHOT_IDENTITY_REVIEW_REQUIRED";
            } else if (imported.signum() < 0) {
                context.counters.inc("negativeStockRowsFound");
                if (context.negativePolicy == DomainEnums.NegativeStockImportPolicy.SKIP_STOCK_MOVEMENT) {
                    action = DomainEnums.ImportDryRunAction.SKIP;
                    warningCode = "NEGATIVE_STOCK_SKIPPED";
                    context.counters.inc("negativeStockRowsSkipped");
                } else if (context.negativePolicy == DomainEnums.NegativeStockImportPolicy.BLOCK
                    || !stockLedger.negativeStockAllowed(context.session.tenantId)) {
                    action = DomainEnums.ImportDryRunAction.BLOCKED;
                    errorCode = "NEGATIVE_STOCK_BLOCKED";
                    context.counters.inc("negativeStockRowsBlocked");
                } else {
                    warningCode = "NEGATIVE_STOCK_IMPORT_AS_IS";
                }
            }
            if (action == DomainEnums.ImportDryRunAction.NO_CHANGE) context.counters.inc("snapshotNoChangeRows");
            if (action == DomainEnums.ImportDryRunAction.CREATE) context.counters.inc("snapshotAdjustmentsPreviewed");
            context.items.add(item(context, DomainEnums.ImportDryRunItemType.STOCK_SNAPSHOT, action, row.sessionFileId,
                row.sourceRowNumber, row.matchedProductId, linked(
                    "productName", row.productName, "unitCode", row.unitCode, "warehouseName", row.warehouseName,
                    "warehouseId", warehouseId, "snapshotDate", snapshotDate.toString(), "currentStock", currentStock,
                    "snapshotDateSource", row.snapshotDate == null ? "USER_CONFIRMED" : "SOURCE_EXPORT",
                    "stockAtSnapshotDate", stockAtDate, "importedSnapshotQuantity", imported,
                    "deltaAtSnapshotDate", delta, "projectedCurrentStockAfterApplyingLaterTransactions", projectedCurrent,
                    "matchStatus", row.matchStatus, "negativeStockPolicy", context.negativePolicy,
                    "sourceFileHash", sourceFile == null ? null : sourceFile.fileHash), warningCode, errorCode));
        }
    }

    private void previewVouchers(DryRunContext context) {
        var tenantId = context.session.tenantId;
        var stockJournalVoucherIds = stagedTransfers
            .findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(
                tenantId, context.session.id).stream()
            .map(candidate -> candidate.stagedVoucherId).collect(Collectors.toSet());
        var warehouseIndex = dryRunWarehouseIndex(
            warehouses.findByTenantId(tenantId),
            stagedWarehouses.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, context.session.id));
        var existingInvoiceIndex = existingInvoiceIndex(tenantId);
        var vouchers = stagedVouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(
            tenantId, context.session.id);
        var voucherItems = stagedVoucherItems.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(
            tenantId, context.session.id);
        // Everything needed for invoice-only preview is now in memory. Detach the large voucher graph so
        // stock-mode queries do not trigger a deep dirty-check of every staged item and its JSON metadata.
        entityManager.clear();
        var itemsByVoucher = voucherItems.stream().collect(Collectors.groupingBy(item -> item.stagedVoucherId, LinkedHashMap::new, Collectors.toList()));
        var projectedStock = new HashMap<String, BigDecimal>();
        var ledgerLinesByVoucher = new HashMap<UUID, Integer>();

        for (var voucher : vouchers) {
            if (stockJournalVoucherIds.contains(voucher.id)) continue;
            context.counters.add("taxLinesCaptured", voucher.taxLineCount);
            context.counters.add("discountLinesCaptured", voucher.discountLineCount);
            context.counters.add("freightLinesCaptured", voucher.freightLineCount);
            context.counters.add("roundOffLinesCaptured", voucher.roundOffLineCount);
            context.counters.add("otherChargeLinesCaptured", voucher.otherChargeLineCount);
            var invoiceType = invoiceType(voucher.voucherType);
            var sourceItems = itemsByVoucher.getOrDefault(voucher.id, List.of());
            if (excludedVoucher(voucher)) {
                context.counters.inc("vouchersExcludedByUser");
                context.items.add(item(context, invoiceType, DomainEnums.ImportDryRunAction.SKIP,
                    voucher.sessionFileId, voucher.sourceRowNumber, null, linked(
                        "voucherType", voucher.voucherType, "voucherNumber", voucher.voucherNumber,
                        "voucherDate", voucher.voucherDate, "partyName", voucher.partyName,
                        "totalAmount", voucher.totalAmount, "reason",
                        Objects.toString(voucher.rawMetadataJson.get("exclusionReason"), "")),
                    "VOUCHER_EXCLUDED_BY_USER", null));
                continue;
            }
            if (isReturnType(invoiceType)) {
                switch (voucher.returnClassification) {
                    case PHYSICAL_SALES_RETURN -> context.counters.add("physicalSalesReturnItemsDetected", sourceItems.size());
                    case PHYSICAL_PURCHASE_RETURN -> context.counters.add("physicalPurchaseReturnItemsDetected", sourceItems.size());
                    case MIXED_SALES_RETURN -> {
                        context.counters.inc("mixedReturnNotesDetected");
                        context.counters.add("physicalSalesReturnItemsDetected", sourceItems.size());
                    }
                    case MIXED_PURCHASE_RETURN -> {
                        context.counters.inc("mixedReturnNotesDetected");
                        context.counters.add("physicalPurchaseReturnItemsDetected", sourceItems.size());
                    }
                    case FINANCIAL_ONLY -> context.counters.inc("financialOnlyNotes");
                    case AMBIGUOUS_RETURN -> context.counters.inc("ambiguousReturnNotes");
                    default -> { }
                }
            }
            var duplicate = voucher.matchStatus == DomainEnums.SmartMatchStatus.SKIP_DUPLICATE;
            var invoiceAction = voucher.reviewStatus == DomainEnums.SmartReviewStatus.PENDING
                ? DomainEnums.ImportDryRunAction.REVIEW_REQUIRED
                : duplicate || existingInvoice(existingInvoiceIndex, invoiceType, voucher.voucherNumber) ? DomainEnums.ImportDryRunAction.SKIP
                : DomainEnums.ImportDryRunAction.CREATE;
            if (duplicate) context.counters.inc("duplicateVouchersSkipped");
            if (invoiceAction == DomainEnums.ImportDryRunAction.REVIEW_REQUIRED) context.counters.inc("vouchersBlocked");
            if (invoiceAction == DomainEnums.ImportDryRunAction.CREATE) incrementInvoiceCounter(context.counters, invoiceType);
            context.items.add(item(context, invoiceType, invoiceAction, voucher.sessionFileId, voucher.sourceRowNumber,
                null, linked("voucherType", voucher.voucherType, "voucherNumber", voucher.voucherNumber,
                    "voucherDate", voucher.voucherDate, "partyName", voucher.partyName, "totalAmount", voucher.totalAmount,
                    "invoiceItemCount", sourceItems.size(), "fingerprint", voucher.fingerprint, "externalId", voucher.externalId,
                    "stockImpactMode", context.stockImpactMode, "taxAmount", voucher.taxAmount,
                    "discountAmount", voucher.discountAmount, "freightAmount", voucher.freightAmount,
                    "roundOffAmount", voucher.roundOffAmount, "otherChargesAmount", voucher.otherChargesAmount),
                invoiceAction == DomainEnums.ImportDryRunAction.REVIEW_REQUIRED ? "VOUCHER_REVIEW_REQUIRED"
                    : duplicate ? "DUPLICATE_VOUCHER" : null, null));
            var returnStockRetry = isReturnType(invoiceType)
                && context.returnStockImpactMode == DomainEnums.ReturnStockImpactMode.APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE
                && invoiceAction == DomainEnums.ImportDryRunAction.SKIP;
            if (invoiceAction != DomainEnums.ImportDryRunAction.CREATE && !returnStockRetry) continue;

            for (var sourceItem : sourceItems) {
                context.counters.inc("voucherItemsReady");
                var rateSource = normalizeRateSource(sourceItem);
                rateCounters(context.counters, rateSource, invoiceType);
                ledgerLinesByVoucher.merge(voucher.id, integer(sourceItem.rawMetadataJson.get("Ledger Lines Skipped")), Math::max);
                var dryRunWarehouse = resolveDryRunWarehouse(sourceItem, warehouseIndex);
                var decision = stockDecision(context, voucher, sourceItem, invoiceType, dryRunWarehouse);
                var movementAction = decision.blocking ? DomainEnums.ImportDryRunAction.BLOCKED
                    : decision.applyMovement ? DomainEnums.ImportDryRunAction.CREATE : DomainEnums.ImportDryRunAction.SKIP;
                var warningCode = decision.warningCode;
                var errorCode = decision.errorCode;
                if (warningCode == null) {
                    warningCode = switch (rateSource) {
                        case "DERIVED_FROM_AMOUNT" -> "RATE_DERIVED_FROM_AMOUNT";
                        case "USER_DERIVED_FROM_AMOUNT" -> "RATE_DERIVED_AFTER_REVIEW";
                        case "USER_VERIFIED_RATE" -> "VERIFIED_RATE_APPLIED";
                        case "USER_CONFIRMED_ZERO_VALUE" -> "ZERO_VALUE_CONFIRMED";
                        case "SIGN_NORMALIZED" -> "RATE_SIGN_NORMALIZED";
                        case "ZERO_COST_ITEM" -> "ZERO_COST_ITEM";
                        case "ZERO_RATE_ITEM" -> "ZERO_RATE_ITEM";
                        default -> null;
                    };
                }
                if ("INVALID".equals(rateSource)) {
                    movementAction = DomainEnums.ImportDryRunAction.BLOCKED;
                    errorCode = "INVALID_RATE";
                    context.counters.inc("invalidRateRows");
                } else if (sourceItem.reviewStatus == DomainEnums.SmartReviewStatus.PENDING
                    && sourceItem.matchStatus != DomainEnums.SmartMatchStatus.CREATE_NEW) {
                    movementAction = DomainEnums.ImportDryRunAction.REVIEW_REQUIRED;
                    warningCode = "VOUCHER_ITEM_REVIEW_REQUIRED";
                }

                var signedQuantity = signedQuantity(invoiceType, nvl(sourceItem.quantity));
                BigDecimal current = ZERO;
                BigDecimal projected = ZERO;
                if (movementAction == DomainEnums.ImportDryRunAction.CREATE && signedQuantity.signum() == 0) {
                    movementAction = DomainEnums.ImportDryRunAction.SKIP;
                    warningCode = "ZERO_QUANTITY_STOCK_MOVEMENT_SKIPPED";
                    context.counters.inc("movementsSkippedByPolicy");
                } else if (movementAction == DomainEnums.ImportDryRunAction.CREATE) {
                    var productProjectionKey = sourceItem.matchedProductId == null
                        ? normalizeName(sourceItem.productName) : sourceItem.matchedProductId.toString();
                    var warehouseProjectionKey = dryRunWarehouse.id == null
                        ? normalizeName(dryRunWarehouse.name) : dryRunWarehouse.id.toString();
                    var projectionKey = productProjectionKey + "|" + warehouseProjectionKey;
                    current = projectedStock.computeIfAbsent(projectionKey,
                        ignored -> projectedStartingStock(context, sourceItem, dryRunWarehouse, decision));
                    projected = current.add(signedQuantity);
                    if (projected.signum() < 0) {
                        context.counters.inc("negativeStockRowsFound");
                        if (context.negativePolicy == DomainEnums.NegativeStockImportPolicy.SKIP_STOCK_MOVEMENT) {
                            movementAction = DomainEnums.ImportDryRunAction.SKIP;
                            warningCode = "NEGATIVE_STOCK_SKIPPED";
                            context.counters.inc("negativeStockRowsSkipped");
                            context.counters.inc("movementsSkippedByPolicy");
                        } else if (context.negativePolicy == DomainEnums.NegativeStockImportPolicy.BLOCK
                            || !stockLedger.negativeStockAllowed(context.session.tenantId)) {
                            movementAction = DomainEnums.ImportDryRunAction.BLOCKED;
                            errorCode = "NEGATIVE_STOCK_BLOCKED";
                            context.counters.inc("negativeStockRowsBlocked");
                            context.counters.inc("movementsBlockedByNegativeStock");
                        } else {
                            warningCode = "NEGATIVE_STOCK_IMPORT_AS_IS";
                            projectedStock.put(projectionKey, projected);
                        }
                    } else {
                        projectedStock.put(projectionKey, projected);
                    }
                }
                if (movementAction == DomainEnums.ImportDryRunAction.CREATE) {
                    context.counters.inc("stockMovementsPreviewed");
                    context.counters.inc("projectedStockChanges");
                    if (invoiceType == DomainEnums.ImportDryRunItemType.SALES_INVOICE) {
                        context.counters.inc("salesVoucherItemsEligibleForStock");
                        context.counters.inc("salesStockMovementsPlanned");
                    } else if (invoiceType == DomainEnums.ImportDryRunItemType.PURCHASE_INVOICE) {
                        context.counters.inc("purchaseVoucherItemsEligibleForStock");
                        context.counters.inc("purchaseStockMovementsPlanned");
                    } else if (invoiceType == DomainEnums.ImportDryRunItemType.CREDIT_NOTE) {
                        context.counters.inc("returnInMovementsPlanned");
                    } else if (invoiceType == DomainEnums.ImportDryRunItemType.DEBIT_NOTE) {
                        context.counters.inc("returnOutMovementsPlanned");
                    }
                }
                if (decision.coveredBySnapshot) {
                    context.counters.inc("stockMovementsSkippedBeforeSnapshotDate");
                    context.counters.inc("voucherItemsCoveredBySnapshot");
                    if (isReturnType(invoiceType)) context.counters.inc("returnItemsCoveredBySnapshot");
                }
                if (decision.sameDate) {
                    context.counters.inc("sameDateVoucherItemsDeferred");
                    if (isReturnType(invoiceType)) context.counters.inc("sameDateReturnItemsDeferred");
                }
                if (decision.errorCode != null && decision.errorCode.equals("NO_APPLICABLE_STOCK_SNAPSHOT")) {
                    context.counters.inc("voucherItemsWithoutSnapshot");
                    if (isReturnType(invoiceType)) context.counters.inc("returnItemsWithoutSnapshot");
                }
                if (decision.errorCode != null && decision.errorCode.equals("UNRESOLVED_VOUCHER_WAREHOUSE")) {
                    context.counters.inc("voucherItemsBlockedByWarehouse");
                    if (isReturnType(invoiceType)) context.counters.inc("returnItemsBlockedByWarehouse");
                }
                if ("DAMAGED_RETURN_DEFERRED".equals(decision.errorCode)) {
                    context.counters.inc("damagedOrNonSaleableReturnsDeferred");
                }
                if (isReturnType(invoiceType) && "NEGATIVE_STOCK_BLOCKED".equals(errorCode)) {
                    context.counters.inc("returnMovementsBlockedByNegativeStock");
                }
                if (!decision.applyMovement && !decision.coveredBySnapshot && !decision.blocking) {
                    context.counters.inc("stockMovementsSkippedDueToInvoiceOnly");
                    context.counters.inc("invoicesOnlyPlanned");
                }
                if (decision.productLevelFallback) context.counters.inc("productLevelSnapshotFallbacks");
                context.items.add(item(context, DomainEnums.ImportDryRunItemType.STOCK_MOVEMENT, movementAction,
                    sourceItem.sessionFileId, sourceItem.sourceRowNumber, sourceItem.id, linked(
                        "stagedVoucherItemId", sourceItem.id,
                        "voucherNumber", voucher.voucherNumber, "voucherDate", voucher.voucherDate,
                        "productName", sourceItem.productName, "warehouseName", dryRunWarehouse.name,
                        "warehouseId", dryRunWarehouse.id, "warehouseAutoCreated", dryRunWarehouse.autoCreated,
                        "quantity", sourceItem.quantity, "signedQuantity", signedQuantity, "rate", sourceItem.rate,
                        "rawQuantity", sourceItem.rawQuantity, "billedQuantity", sourceItem.billedQuantity,
                        "actualQuantity", sourceItem.actualQuantity, "returnClassification", voucher.returnClassification,
                        "classificationConfidence", voucher.returnConfidence,
                        "detectionReasons", sourceItem.returnDetectionReason,
                        "returnDirection", sourceItem.returnDirection, "disposition", sourceItem.returnDisposition,
                        "userResolutionStatus", sourceItem.returnResolutionStatus,
                        "financialAction", invoiceAction, "physicalStockAction", movementAction,
                        "amount", sourceItem.amount, "rateSource", rateSource, "currentStock", current,
                        "projectedStock", projected, "stockImpactMode", context.stockImpactMode,
                        "movementType", movementType(invoiceType), "stockMovementAction", movementAction,
                        "applicableSnapshotId", decision.snapshotId, "applicableSnapshotDate", decision.snapshotDate,
                        "eligibilityReason", decision.reasonCode, "reason", decision.reason,
                        "productLevelSnapshotFallback", decision.productLevelFallback,
                        "negativeStockPolicy", context.negativePolicy), warningCode, errorCode));
            }
        }
        context.counters.set("ledgerLinesSkipped", ledgerLinesByVoucher.values().stream().mapToLong(Integer::longValue).sum());
    }

    private void previewTransfers(DryRunContext context) {
        var candidates = stagedTransfers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(
            context.session.tenantId, context.session.id);
        context.counters.set("stockJournalsDetected",
            candidates.stream().map(candidate -> candidate.stagedVoucherId).distinct().count());
        var projectedStock = new HashMap<String, BigDecimal>();
        for (var candidate : candidates) {
            var action = DomainEnums.ImportDryRunAction.SKIP;
            String warningCode = null;
            String errorCode = null;
            BigDecimal sourceCurrent = ZERO;
            BigDecimal sourceProjected = ZERO;
            BigDecimal destinationCurrent = ZERO;
            BigDecimal destinationProjected = ZERO;
            BigDecimal baseQuantity = nvl(candidate.normalizedQuantity).abs();
            SmartImportSnapshotEligibilityService.SnapshotEligibilityDecision sourceEligibility = null;
            SmartImportSnapshotEligibilityService.SnapshotEligibilityDecision destinationEligibility = null;

            if (candidate.classification == DomainEnums.StockJournalClassification.WAREHOUSE_TRANSFER) {
                context.counters.inc("warehouseTransfersDetected");
            }
            if (transferService.manufacturing(candidate)) {
                context.counters.inc("manufacturingJournalsDeferred");
                warningCode = "TRANSFER_MANUFACTURING_INDICATORS_FOUND";
            } else if (candidate.resolutionStatus == DomainEnums.TransferResolutionStatus.DEFERRED_ADJUSTMENT
                || candidate.classification == DomainEnums.StockJournalClassification.STOCK_ADJUSTMENT_IN
                || candidate.classification == DomainEnums.StockJournalClassification.STOCK_ADJUSTMENT_OUT
                || candidate.classification == DomainEnums.StockJournalClassification.UNBALANCED_STOCK_JOURNAL) {
                context.counters.inc("adjustmentJournalsDeferred");
                warningCode = "TRANSFER_UNBALANCED";
            } else if (candidate.resolutionStatus == DomainEnums.TransferResolutionStatus.IGNORED) {
                warningCode = "STOCK_JOURNAL_IGNORED";
            } else if (context.stockJournalImpactMode == DomainEnums.StockJournalImpactMode.DEFER_STOCK_JOURNALS) {
                warningCode = "STOCK_JOURNAL_DEFERRED";
            } else if (!transferService.isConfirmed(candidate)) {
                action = DomainEnums.ImportDryRunAction.REVIEW_REQUIRED;
                warningCode = "STOCK_JOURNAL_REVIEW_REQUIRED";
                context.counters.inc("warehouseTransfersReviewRequired");
                context.counters.inc("ambiguousStockJournalsDeferred");
            } else if (candidate.matchedProductId == null
                || products.findByTenantIdAndId(context.session.tenantId, candidate.matchedProductId).isEmpty()) {
                action = DomainEnums.ImportDryRunAction.BLOCKED;
                errorCode = "TRANSFER_PRODUCT_UNRESOLVED";
                context.counters.inc("warehouseTransfersBlockedByProduct");
            } else if (candidate.sourceWarehouseId == null || candidate.destinationWarehouseId == null
                || warehouses.findByTenantIdAndId(context.session.tenantId, candidate.sourceWarehouseId).isEmpty()
                || warehouses.findByTenantIdAndId(context.session.tenantId, candidate.destinationWarehouseId).isEmpty()
                || candidate.sourceWarehouseId.equals(candidate.destinationWarehouseId)) {
                action = DomainEnums.ImportDryRunAction.BLOCKED;
                errorCode = candidate.sourceWarehouseId != null
                    && candidate.sourceWarehouseId.equals(candidate.destinationWarehouseId)
                    ? "TRANSFER_SOURCE_EQUALS_DESTINATION" : "TRANSFER_WAREHOUSE_AMBIGUOUS";
                context.counters.inc("warehouseTransfersBlockedByWarehouse");
            } else if (baseQuantity.signum() <= 0) {
                action = DomainEnums.ImportDryRunAction.BLOCKED;
                errorCode = "TRANSFER_QUANTITY_INVALID";
                context.counters.inc("warehouseTransfersBlockedByQuantity");
            } else {
                sourceEligibility = snapshotEligibility.decideWarehouseSpecific(
                    context.session.tenantId, context.session.id, candidate.voucherDate,
                    candidate.matchedProductId, candidate.normalizedProductName, candidate.unitCode,
                    candidate.sourceWarehouseId, candidate.sourceWarehouseName, context.requestedSnapshotDate);
                destinationEligibility = snapshotEligibility.decideWarehouseSpecific(
                    context.session.tenantId, context.session.id, candidate.voucherDate,
                    candidate.matchedProductId, candidate.normalizedProductName, candidate.unitCode,
                    candidate.destinationWarehouseId, candidate.destinationWarehouseName, context.requestedSnapshotDate);
                candidate.sourceSnapshotId = sourceEligibility.snapshotId();
                candidate.destinationSnapshotId = destinationEligibility.snapshotId();
                candidate.sourceSnapshotDate = sourceEligibility.snapshotDate();
                candidate.destinationSnapshotDate = destinationEligibility.snapshotDate();

                if (sourceEligibility.blocking() || destinationEligibility.blocking()) {
                    action = DomainEnums.ImportDryRunAction.BLOCKED;
                    errorCode = "WAREHOUSE_BASELINE_REQUIRED_FOR_TRANSFER";
                    context.counters.inc("warehouseTransfersMissingBaseline");
                } else if (!sourceEligibility.eligible() || !destinationEligibility.eligible()) {
                    action = DomainEnums.ImportDryRunAction.SKIP;
                    warningCode = "TRANSFER_ALREADY_COVERED_BY_SNAPSHOT";
                    context.counters.inc("warehouseTransfersCoveredBySnapshot");
                    if (sourceEligibility.reasonCode() == SmartImportSnapshotEligibilityService.ReasonCode.ON_SNAPSHOT_DATE
                        || destinationEligibility.reasonCode() == SmartImportSnapshotEligibilityService.ReasonCode.ON_SNAPSHOT_DATE) {
                        context.counters.inc("sameDateWarehouseTransfersDeferred");
                    }
                } else {
                    candidate.transferFingerprint = transferService.transferFingerprint(
                        context.session.tenantId, candidate, candidate.matchedProductId,
                        candidate.sourceWarehouseId, candidate.destinationWarehouseId, candidate.normalizedQuantity);
                    candidate.transferLineFingerprint = transferService.transferLineFingerprint(
                        context.session.tenantId, candidate, candidate.matchedProductId);
                    var pairEffects = stockMovements.findByTenantIdAndTransferPairFingerprint(
                        context.session.tenantId, candidate.transferFingerprint);
                    var lineEffects = stockMovements.findByTenantIdAndTransferLineFingerprint(
                        context.session.tenantId, candidate.transferLineFingerprint);
                    var exactPair = pairEffects.size() == 2
                        && pairEffects.stream().anyMatch(row -> row.movementType == DomainEnums.MovementType.TRANSFER_OUT)
                        && pairEffects.stream().anyMatch(row -> row.movementType == DomainEnums.MovementType.TRANSFER_IN)
                        && pairEffects.stream().map(row -> row.transferGroupId).filter(Objects::nonNull).distinct().count() == 1;
                    if (exactPair) {
                        action = DomainEnums.ImportDryRunAction.SKIP;
                        warningCode = "DUPLICATE_WAREHOUSE_TRANSFER";
                        context.counters.inc("duplicateWarehouseTransfersSkipped");
                    } else if (!pairEffects.isEmpty() || !lineEffects.isEmpty()) {
                        action = DomainEnums.ImportDryRunAction.REVIEW_REQUIRED;
                        errorCode = pairEffects.size() == 1
                            ? "ORPHANED_TRANSFER_EFFECT_REVIEW" : "CONFLICTING_WAREHOUSE_TRANSFER";
                        context.counters.inc("warehouseTransfersReviewRequired");
                    } else {
                        var product = products.findByTenantIdAndId(context.session.tenantId, candidate.matchedProductId)
                            .orElseThrow();
                        var unitId = candidate.unitCode == null ? null
                            : units.findByTenantIdAndCodeIgnoreCase(context.session.tenantId, candidate.unitCode)
                                .map(unit -> unit.id).orElse(null);
                        baseQuantity = stockLedger.toBaseQuantity(
                            context.session.tenantId, product, unitId, candidate.normalizedQuantity).abs();
                        var sourceKey = candidate.matchedProductId + "|" + candidate.sourceWarehouseId;
                        var destinationKey = candidate.matchedProductId + "|" + candidate.destinationWarehouseId;
                        sourceCurrent = projectedStock.computeIfAbsent(sourceKey, ignored -> stockLedger.currentStock(
                            context.session.tenantId, candidate.matchedProductId, candidate.sourceWarehouseId));
                        destinationCurrent = projectedStock.computeIfAbsent(destinationKey, ignored -> stockLedger.currentStock(
                            context.session.tenantId, candidate.matchedProductId, candidate.destinationWarehouseId));
                        sourceProjected = sourceCurrent.subtract(baseQuantity);
                        destinationProjected = destinationCurrent.add(baseQuantity);
                        if (sourceProjected.signum() < 0) {
                            if (context.negativePolicy == DomainEnums.NegativeStockImportPolicy.SKIP_STOCK_MOVEMENT) {
                                action = DomainEnums.ImportDryRunAction.SKIP;
                                warningCode = "TRANSFER_NEGATIVE_STOCK_SKIPPED";
                                context.counters.inc("warehouseTransfersSkippedByPolicy");
                            } else if (context.negativePolicy == DomainEnums.NegativeStockImportPolicy.BLOCK
                                || !stockLedger.negativeStockAllowed(context.session.tenantId)) {
                                action = DomainEnums.ImportDryRunAction.BLOCKED;
                                errorCode = "TRANSFER_NEGATIVE_STOCK_BLOCKED";
                                context.counters.inc("warehouseTransfersBlockedByNegativeStock");
                            } else {
                                action = DomainEnums.ImportDryRunAction.CREATE;
                                warningCode = "TRANSFER_NEGATIVE_STOCK_IMPORT_AS_IS";
                            }
                        } else {
                            action = DomainEnums.ImportDryRunAction.CREATE;
                        }
                        if (action == DomainEnums.ImportDryRunAction.CREATE) {
                            projectedStock.put(sourceKey, sourceProjected);
                            projectedStock.put(destinationKey, destinationProjected);
                            context.counters.inc("warehouseTransfersEligible");
                            context.counters.inc("transferOutMovementsPlanned");
                            context.counters.inc("transferInMovementsPlanned");
                            if (candidate.resolutionStatus == DomainEnums.TransferResolutionStatus.AUTO_CONFIRMED) {
                                context.counters.inc("warehouseTransfersAutoConfirmed");
                            }
                        }
                    }
                }
            }

            stagedTransfers.save(candidate);
            context.items.add(item(context,
                candidate.classification == DomainEnums.StockJournalClassification.WAREHOUSE_TRANSFER
                    ? DomainEnums.ImportDryRunItemType.WAREHOUSE_TRANSFER
                    : DomainEnums.ImportDryRunItemType.STOCK_JOURNAL,
                action, candidate.sessionFileId, candidate.sourceRowNumber, candidate.id, linked(
                    "transferCandidateId", candidate.id,
                    "voucherNumber", candidate.voucherNumber,
                    "voucherDate", candidate.voucherDate,
                    "externalId", candidate.voucherExternalId,
                    "classification", candidate.classification,
                    "confidence", candidate.classificationConfidence,
                    "detectionReasons", candidate.decisionReasons,
                    "productId", candidate.matchedProductId,
                    "productName", candidate.productName,
                    "sourceWarehouseId", candidate.sourceWarehouseId,
                    "sourceWarehouse", candidate.sourceWarehouseName,
                    "destinationWarehouseId", candidate.destinationWarehouseId,
                    "destinationWarehouse", candidate.destinationWarehouseName,
                    "rawOutwardQuantity", candidate.rawOutwardQuantity,
                    "rawInwardQuantity", candidate.rawInwardQuantity,
                    "normalizedQuantity", candidate.normalizedQuantity,
                    "baseQuantity", baseQuantity,
                    "unit", candidate.unitCode,
                    "sourceCurrentStock", sourceCurrent,
                    "projectedSourceStock", sourceProjected,
                    "destinationCurrentStock", destinationCurrent,
                    "projectedDestinationStock", destinationProjected,
                    "companyWideNetEffect", ZERO,
                    "sourceSnapshotId", sourceEligibility == null ? null : sourceEligibility.snapshotId(),
                    "sourceSnapshotDate", sourceEligibility == null || sourceEligibility.snapshotDate() == null
                        ? null : sourceEligibility.snapshotDate().toString(),
                    "destinationSnapshotId", destinationEligibility == null ? null : destinationEligibility.snapshotId(),
                    "destinationSnapshotDate", destinationEligibility == null || destinationEligibility.snapshotDate() == null
                        ? null : destinationEligibility.snapshotDate().toString(),
                    "effectiveEligibilityDate", effectiveBoundary(sourceEligibility, destinationEligibility) == null
                        ? null : effectiveBoundary(sourceEligibility, destinationEligibility).toString(),
                    "transferFingerprint", candidate.transferFingerprint,
                    "transferLineFingerprint", candidate.transferLineFingerprint,
                    "resolutionStatus", candidate.resolutionStatus,
                    "stockJournalImpactMode", context.stockJournalImpactMode,
                    "negativeStockPolicy", context.negativePolicy,
                    "postingDecision", action), warningCode, errorCode));
        }
    }

    private DryRunWarehouse resolveDryRunWarehouse(SmartStagedVoucherItem item, DryRunWarehouseIndex index) {
        if (item.matchedWarehouseId != null) {
            var matched = index.byId.get(item.matchedWarehouseId);
            if (matched != null) return new DryRunWarehouse(matched.id, matched.name, true, false);
        }
        if (item.warehouseName != null && !item.warehouseName.isBlank()) {
            var existing = index.byName.get(normalizeName(item.warehouseName));
            if (existing != null) return new DryRunWarehouse(existing.id, existing.name, true, false);
        }
        return new DryRunWarehouse(item.matchedWarehouseId, item.warehouseName, false, false);
    }

    private DryRunWarehouseIndex dryRunWarehouseIndex(
        List<Warehouse> existingWarehouses,
        List<SmartStagedWarehouse> stagedWarehouseRows
    ) {
        var byId = new HashMap<UUID, DryRunWarehouse>();
        var byName = new HashMap<String, DryRunWarehouse>();
        for (var warehouse : existingWarehouses) {
            var value = new DryRunWarehouse(warehouse.id, warehouse.name, true, false);
            byId.put(warehouse.id, value);
            byName.put(normalizeName(warehouse.name), value);
        }
        for (var staged : stagedWarehouseRows) {
            var usable = staged.matchStatus == DomainEnums.SmartMatchStatus.MATCH_EXISTING
                || staged.matchStatus == DomainEnums.SmartMatchStatus.CREATE_NEW
                    && staged.reviewStatus != DomainEnums.SmartReviewStatus.PENDING;
            if (!usable || staged.sourceWarehouseName == null || staged.sourceWarehouseName.isBlank()) continue;
            var value = new DryRunWarehouse(staged.matchedWarehouseId, staged.sourceWarehouseName, true,
                staged.matchStatus == DomainEnums.SmartMatchStatus.CREATE_NEW);
            if (staged.matchedWarehouseId != null) byId.putIfAbsent(staged.matchedWarehouseId, value);
            byName.putIfAbsent(normalizeName(staged.sourceWarehouseName), value);
        }
        return new DryRunWarehouseIndex(byId, byName);
    }

    private StockDecision stockDecision(
        DryRunContext context,
        SmartStagedVoucher voucher,
        SmartStagedVoucherItem item,
        DomainEnums.ImportDryRunItemType invoiceType,
        DryRunWarehouse warehouse
    ) {
        if (invoiceType == DomainEnums.ImportDryRunItemType.CREDIT_NOTE
            || invoiceType == DomainEnums.ImportDryRunItemType.DEBIT_NOTE) {
            if (context.returnStockImpactMode == DomainEnums.ReturnStockImpactMode.FINANCIAL_ONLY) {
                return StockDecision.skip("RETURN_STOCK_MOVEMENT_DEFERRED",
                    "Return inventory effect is financial-only.");
            }
            if (item.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.USER_CONFIRMED_FINANCIAL_ONLY
                || item.returnDisposition == DomainEnums.ReturnDisposition.FINANCIAL_ONLY) {
                return StockDecision.skip("ZERO_QUANTITY_FINANCIAL_ONLY",
                    "The return line was confirmed as financial-only.");
            }
            if (item.returnDetectionReason != null
                && item.returnDetectionReason.contains("DAMAGED_OR_NON_SALEABLE_REVIEW")) {
                return StockDecision.block("DAMAGED_RETURN_DEFERRED",
                    "Damaged or non-saleable returns cannot be posted to available stock.");
            }
            if (!returnService.isPhysicalConfirmed(item)) {
                return StockDecision.block("RETURN_REVIEW_REQUIRED",
                    "Confirm whether this note line represents physical goods movement.");
            }
            if (item.returnDisposition != DomainEnums.ReturnDisposition.RESTOCK_TO_AVAILABLE) {
                return StockDecision.block("DAMAGED_RETURN_DEFERRED",
                    "Damaged or non-saleable returns cannot be posted to available stock.");
            }
        }
        if (!isReturnType(invoiceType)
            && context.stockImpactMode == DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY) {
            return StockDecision.skip("STOCK_MOVEMENT_SKIPPED_INVOICE_ONLY",
                "Invoice-only mode does not create stock movements.");
        }
        if (!warehouse.resolved) {
            return StockDecision.block("UNRESOLVED_VOUCHER_WAREHOUSE",
                "Resolve the voucher item godown before applying stock.");
        }
        var eligibility = snapshotEligibility.decide(
            context.session.tenantId, context.session.id, voucher.voucherDate,
            item.matchedProductId, firstNonNull(item.normalizedProductName, item.productName), item.unitCode,
            warehouse.id, warehouse.name, context.requestedSnapshotDate);
        if (eligibility.blocking()) {
            return StockDecision.block(eligibility.warningCode(), eligibility.explanation());
        }
        return new StockDecision(
            eligibility.eligible(), false,
            eligibility.reasonCode() == SmartImportSnapshotEligibilityService.ReasonCode.BEFORE_SNAPSHOT
                || eligibility.reasonCode() == SmartImportSnapshotEligibilityService.ReasonCode.ON_SNAPSHOT_DATE,
            eligibility.reasonCode() == SmartImportSnapshotEligibilityService.ReasonCode.ON_SNAPSHOT_DATE,
            eligibility.productLevelFallback(),
            eligibility.warningCode(), null, eligibility.explanation(),
            eligibility.snapshotId(), eligibility.snapshotDate(), eligibility.reasonCode().name());
    }

    private BigDecimal projectedStartingStock(
        DryRunContext context,
        SmartStagedVoucherItem item,
        DryRunWarehouse warehouse,
        StockDecision decision
    ) {
        var current = item.matchedProductId == null || warehouse.id == null ? ZERO
            : stockLedger.currentStock(context.session.tenantId, item.matchedProductId, warehouse.id);
        if (decision.snapshotId == null) return current;
        var staged = stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(
                context.session.tenantId, context.session.id).stream()
            .filter(row -> row.id.equals(decision.snapshotId)).findFirst().orElse(null);
        if (staged == null) return current;
        if (item.matchedProductId == null || warehouse.id == null) return nvl(staged.importedStock);
        var date = staged.snapshotDate == null ? context.requestedSnapshotDate : staged.snapshotDate;
        if (date == null) return current;
        return current.add(nvl(staged.importedStock)
            .subtract(stockLedger.stockAt(context.session.tenantId, item.matchedProductId, warehouse.id, date)));
    }

    private String movementType(DomainEnums.ImportDryRunItemType invoiceType) {
        return invoiceType == DomainEnums.ImportDryRunItemType.SALES_INVOICE
            ? DomainEnums.MovementType.SALE.name()
            : invoiceType == DomainEnums.ImportDryRunItemType.PURCHASE_INVOICE
            ? DomainEnums.MovementType.PURCHASE.name()
            : invoiceType == DomainEnums.ImportDryRunItemType.CREDIT_NOTE
            ? DomainEnums.MovementType.RETURN_IN.name()
            : invoiceType == DomainEnums.ImportDryRunItemType.DEBIT_NOTE
            ? DomainEnums.MovementType.RETURN_OUT.name() : "";
    }

    private void previewCashbookAndAgeing(DryRunContext context) {
        var tenantId = context.session.tenantId;
        var ageingRows = stagedAgeing.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(
            tenantId, context.session.id);
        var cashbookRows = stagedCashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(
            tenantId, context.session.id);
        var fingerprints = cashbookRows.stream().map(row -> row.fingerprint)
            .filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().toList();
        var customerFingerprints = cashbookRows.stream().filter(this::cashbookReady)
            .filter(row -> row.matchedPartyType == DomainEnums.SmartPartyType.CUSTOMER)
            .map(row -> row.fingerprint).filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().toList();
        var supplierFingerprints = cashbookRows.stream().filter(this::cashbookReady)
            .filter(row -> row.matchedPartyType != DomainEnums.SmartPartyType.CUSTOMER)
            .map(row -> row.fingerprint).filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().toList();

        // The rows are read-only for a dry run. Detaching them also prevents each duplicate lookup from
        // dirty-checking every staged cashbook row. Duplicate evidence is loaded once in bounded batches.
        entityManager.clear();
        var retailByFingerprint = retailSales.recordedByFingerprint(tenantId, fingerprints);
        var customerPaymentFingerprints = customerFingerprints.isEmpty() ? Set.<String>of()
            : customerPayments.findByTenantIdAndSourceFingerprintIn(tenantId, customerFingerprints).stream()
                .map(payment -> payment.sourceFingerprint).collect(Collectors.toSet());
        var supplierPaymentFingerprints = supplierFingerprints.isEmpty() ? Set.<String>of()
            : supplierPayments.findByTenantIdAndSourceFingerprintIn(tenantId, supplierFingerprints).stream()
                .map(payment -> payment.sourceFingerprint).collect(Collectors.toSet());

        var seenCustomerPayments = new HashSet<>(customerPaymentFingerprints);
        var seenSupplierPayments = new HashSet<>(supplierPaymentFingerprints);
        for (var row : cashbookRows) {
            var retail = row.fingerprint == null ? null : retailByFingerprint.get(row.fingerprint);
            if (retail != null) {
                context.counters.inc("retailCounterSaleRowsResolved");
                context.items.add(item(context, DomainEnums.ImportDryRunItemType.CASHBOOK_ENTRY,
                    DomainEnums.ImportDryRunAction.SKIP, row.sessionFileId, row.sourceRowNumber, retail.id,
                    linked("date", row.entryDate, "amount", row.amount, "retailCounterSaleId", retail.id,
                        "resolutionStatus", "RETAIL_SALE_RECORDED", "stockEffect", "NONE"), null, null));
                continue;
            }
            if (row.resolutionStatus == DomainEnums.CashbookResolutionStatus.IGNORED
                || row.resolutionStatus == DomainEnums.CashbookResolutionStatus.PAYMENT_POSTED) {
                context.items.add(item(context, DomainEnums.ImportDryRunItemType.CASHBOOK_ENTRY,
                    DomainEnums.ImportDryRunAction.SKIP, row.sessionFileId, row.sourceRowNumber, row.matchedInvoiceId,
                    linked("resolutionStatus", row.resolutionStatus, "amount", row.amount), null, null));
                continue;
            }
            var matched = cashbookReady(row);
            var duplicate = matched && row.fingerprint != null && (row.matchedPartyType == DomainEnums.SmartPartyType.CUSTOMER
                ? !seenCustomerPayments.add(row.fingerprint)
                : !seenSupplierPayments.add(row.fingerprint));
            var action = duplicate ? DomainEnums.ImportDryRunAction.SKIP
                : matched ? DomainEnums.ImportDryRunAction.CREATE : DomainEnums.ImportDryRunAction.SKIP;
            if (matched) {
                context.counters.inc("cashbookRowsReady");
                context.counters.inc("cashbookRowsMatched");
                if (duplicate) context.counters.inc("duplicatePaymentsSkipped");
                else if (row.matchedPartyType == DomainEnums.SmartPartyType.CUSTOMER) context.counters.inc("customerPaymentsPreviewed");
                else context.counters.inc("supplierPaymentsPreviewed");
                if (!duplicate && row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.MATCHED_SALES_INVOICE) {
                    context.counters.inc("paymentsLinkedToSalesInvoices");
                }
                if (!duplicate && row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.MATCHED_PURCHASE_INVOICE) {
                    context.counters.inc("paymentsLinkedToPurchaseInvoices");
                }
            } else if (row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.LOW_CONFIDENCE_REVIEW) {
                context.counters.inc("cashbookRowsReviewRequired");
            } else {
                context.counters.inc("cashbookRowsUnmatched");
            }
            context.items.add(item(context, DomainEnums.ImportDryRunItemType.CASHBOOK_ENTRY,
                action,
                row.sessionFileId, row.sourceRowNumber, row.matchedInvoiceId, linked("date", row.entryDate,
                    "partyName", row.partyName, "amount", row.amount, "direction", row.direction,
                    "paymentMode", row.paymentMode, "referenceNumber", row.referenceNumber,
                    "matchedPartyId", row.matchedPartyId, "matchedPartyType", row.matchedPartyType,
                    "matchedInvoiceId", row.matchedInvoiceId, "matchedStagedVoucherId", row.matchedStagedVoucherId,
                    "matchedPartyName", row.matchedPartyName, "cashbookMatchStatus", row.cashbookMatchStatus,
                    "matchConfidence", row.matchConfidence, "matchReason", row.matchReason),
                duplicate ? "DUPLICATE_PAYMENT_SKIPPED" : matched ? null
                    : row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.LOW_CONFIDENCE_REVIEW
                    ? "CASHBOOK_LOW_CONFIDENCE" : "CASHBOOK_UNMATCHED", null));
        }
        for (var row : ageingRows) {
            var matched = row.matchedProductId != null;
            context.counters.inc(matched ? "ageingRowsMatched" : "ageingRowsUnmatched");
            context.items.add(item(context, DomainEnums.ImportDryRunItemType.STOCK_AGEING,
                matched ? DomainEnums.ImportDryRunAction.MATCH_EXISTING : DomainEnums.ImportDryRunAction.REVIEW_REQUIRED,
                row.sessionFileId, row.sourceRowNumber, row.matchedProductId, linked("productName", row.productName,
                    "unitCode", row.unitCode, "quantity", row.quantity, "ageingBucket", row.ageingBucket,
                    "daysOld", row.daysOld, "stockValue", row.stockValue), matched ? null : "STOCK_AGEING_UNMATCHED", null));
        }
        entityManager.clear();
    }

    private boolean cashbookReady(SmartStagedCashbookEntry row) {
        return SmartImportCashbookMatchingService.ready(row);
    }

    private void previewUnresolvedPlanIssues(DryRunContext context) {
        var representedCodes = context.items.stream()
            .flatMap(item -> java.util.stream.Stream.of(item.warningCode, item.errorCode))
            .filter(Objects::nonNull).collect(Collectors.toSet());
        for (var issue : planIssues.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(context.session.tenantId, context.session.id)) {
            if (issue.resolved || issue.severity == DomainEnums.ImportPlanIssueSeverity.INFO || representedCodes.contains(issue.code)) continue;
            if ("NEGATIVE_STOCK".equals(issue.code)
                && context.negativePolicy == DomainEnums.NegativeStockImportPolicy.SKIP_STOCK_MOVEMENT) continue;
            if ("NEGATIVE_STOCK".equals(issue.code)
                && context.negativePolicy == DomainEnums.NegativeStockImportPolicy.IMPORT_AS_IS
                && stockLedger.negativeStockAllowed(context.session.tenantId)) continue;
            var action = issue.severity == DomainEnums.ImportPlanIssueSeverity.ERROR
                ? DomainEnums.ImportDryRunAction.BLOCKED : issue.severity == DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED
                ? DomainEnums.ImportDryRunAction.REVIEW_REQUIRED : DomainEnums.ImportDryRunAction.SKIP;
            context.items.add(item(context, DomainEnums.ImportDryRunItemType.FILE, action, issue.affectedFileId,
                issue.affectedRows.isEmpty() ? null : issue.affectedRows.getFirst(), null,
                linked("code", issue.code, "message", issue.message, "suggestedAction", issue.suggestedAction),
                issue.severity == DomainEnums.ImportPlanIssueSeverity.ERROR ? null : issue.code,
                issue.severity == DomainEnums.ImportPlanIssueSeverity.ERROR ? issue.code : null));
        }
    }

    private ExistingInvoiceIndex existingInvoiceIndex(UUID tenantId) {
        var sales = salesInvoices.findByTenantId(tenantId).stream()
            .map(invoice -> invoiceKey(invoice.invoiceNumber)).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        var purchases = purchaseInvoices.findByTenantId(tenantId).stream()
            .map(invoice -> invoiceKey(invoice.invoiceNumber)).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        var creditNotes = new HashSet<String>();
        var debitNotes = new HashSet<String>();
        for (var adjustment : financialAdjustments.findByTenantIdOrderByVoucherDateDesc(tenantId)) {
            var key = invoiceKey(adjustment.voucherNumber);
            if (key.isBlank()) continue;
            if (adjustment.adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE) creditNotes.add(key);
            if (adjustment.adjustmentType == DomainEnums.FinancialAdjustmentType.DEBIT_NOTE) debitNotes.add(key);
        }
        return new ExistingInvoiceIndex(sales, purchases, creditNotes, debitNotes);
    }

    private boolean existingInvoice(ExistingInvoiceIndex index, DomainEnums.ImportDryRunItemType type, String number) {
        if (number == null || number.isBlank()) return false;
        var key = invoiceKey(number);
        return switch (type) {
            case SALES_INVOICE -> index.sales.contains(key);
            case PURCHASE_INVOICE -> index.purchases.contains(key);
            case CREDIT_NOTE -> index.creditNotes.contains(key);
            case DEBIT_NOTE -> index.debitNotes.contains(key);
            default -> false;
        };
    }

    private String invoiceKey(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private DomainEnums.ImportDryRunItemType invoiceType(String voucherType) {
        var normalized = voucherType == null ? "" : voucherType.toUpperCase(Locale.ROOT);
        if (normalized.contains("PURCHASE")) return DomainEnums.ImportDryRunItemType.PURCHASE_INVOICE;
        if (normalized.contains("CREDIT")) return DomainEnums.ImportDryRunItemType.CREDIT_NOTE;
        if (normalized.contains("DEBIT")) return DomainEnums.ImportDryRunItemType.DEBIT_NOTE;
        return DomainEnums.ImportDryRunItemType.SALES_INVOICE;
    }

    private boolean isReturnType(DomainEnums.ImportDryRunItemType type) {
        return type == DomainEnums.ImportDryRunItemType.CREDIT_NOTE
            || type == DomainEnums.ImportDryRunItemType.DEBIT_NOTE;
    }

    private BigDecimal signedQuantity(DomainEnums.ImportDryRunItemType type, BigDecimal quantity) {
        return switch (type) {
            case PURCHASE_INVOICE, CREDIT_NOTE -> quantity.abs();
            case SALES_INVOICE, DEBIT_NOTE -> quantity.abs().negate();
            default -> ZERO;
        };
    }

    private void incrementInvoiceCounter(Counters counters, DomainEnums.ImportDryRunItemType type) {
        switch (type) {
            case PURCHASE_INVOICE -> counters.inc("purchaseInvoicesPreviewed");
            case SALES_INVOICE -> counters.inc("salesInvoicesPreviewed");
            case CREDIT_NOTE -> counters.inc("creditNotesPreviewed");
            case DEBIT_NOTE -> counters.inc("debitNotesPreviewed");
            default -> { }
        }
    }

    private String normalizeRateSource(SmartStagedVoucherItem item) {
        if (item.rate == null || item.rate.signum() < 0) return "INVALID";
        if (item.rateSource != null && !item.rateSource.isBlank()) return item.rateSource.toUpperCase(Locale.ROOT);
        if (item.rate != null && item.rate.signum() > 0) return "RATE_FIELD";
        if (item.rate != null && item.rate.signum() == 0) return "ZERO_RATE_ITEM";
        return "INVALID";
    }

    private void rateCounters(Counters counters, String rateSource, DomainEnums.ImportDryRunItemType invoiceType) {
        switch (rateSource) {
            case "DERIVED_FROM_AMOUNT", "USER_DERIVED_FROM_AMOUNT" -> counters.inc("ratesDerived");
            case "SIGN_NORMALIZED" -> counters.inc("ratesSignNormalized");
            case "USER_VERIFIED_RATE" -> counters.inc("verifiedRatesApplied");
            case "ZERO_COST_ITEM" -> counters.inc("zeroCostItems");
            case "ZERO_RATE_ITEM", "USER_CONFIRMED_ZERO_VALUE" -> {
                if (invoiceType == DomainEnums.ImportDryRunItemType.PURCHASE_INVOICE) counters.inc("zeroCostItems");
                else counters.inc("zeroRateItems");
            }
            default -> { }
        }
    }

    private boolean excludedVoucher(SmartStagedVoucher voucher) {
        return voucher.rawMetadataJson != null
            && Boolean.parseBoolean(Objects.toString(voucher.rawMetadataJson.get("excludedFromCommit"), "false"));
    }

    private DomainEnums.ImportDryRunAction action(DomainEnums.SmartMatchStatus matchStatus) {
        if (matchStatus == null) return DomainEnums.ImportDryRunAction.REVIEW_REQUIRED;
        return switch (matchStatus) {
            case CREATE_NEW -> DomainEnums.ImportDryRunAction.CREATE;
            case MATCH_EXISTING -> DomainEnums.ImportDryRunAction.MATCH_EXISTING;
            case SKIP_DUPLICATE -> DomainEnums.ImportDryRunAction.SKIP;
            case POSSIBLE_DUPLICATE_REVIEW, AMBIGUOUS_REVIEW, UNKNOWN -> DomainEnums.ImportDryRunAction.REVIEW_REQUIRED;
        };
    }

    private ImportDryRunItem item(
        DryRunContext context,
        DomainEnums.ImportDryRunItemType itemType,
        DomainEnums.ImportDryRunAction action,
        UUID sourceFileId,
        Integer sourceRowNumber,
        UUID targetEntityId,
        Map<String, Object> preview,
        String warningCode,
        String errorCode
    ) {
        var item = new ImportDryRunItem();
        item.tenantId = context.session.tenantId;
        item.importSessionId = context.session.id;
        item.dryRunId = context.dryRun.id;
        item.itemType = itemType;
        item.action = action;
        item.sourceFileId = sourceFileId;
        item.sourceRowNumber = sourceRowNumber;
        item.targetEntityId = targetEntityId;
        item.previewJson = preview;
        item.warningCode = warningCode;
        item.errorCode = errorCode;
        item.createdBy = TenantContext.userId();
        item.updatedBy = TenantContext.userId();
        return item;
    }

    private Map<String, Object> response(ImportDryRun dryRun) {
        return linked("available", true, "id", dryRun.id, "importSessionId", dryRun.importSessionId,
            "status", dryRun.status, "strategy", dryRun.strategy, "startedAt", dryRun.startedAt,
            "finishedAt", dryRun.finishedAt, "inputFingerprint", dryRun.inputFingerprint,
            "summary", dryRun.summaryJson);
    }

    private Map<String, Object> itemResponse(ImportDryRunItem item) {
        return linked("id", item.id, "itemType", item.itemType, "action", item.action,
            "sourceFileId", item.sourceFileId, "sourceRowNumber", item.sourceRowNumber,
            "targetEntityId", item.targetEntityId, "preview", item.previewJson,
            "warningCode", item.warningCode, "errorCode", item.errorCode, "createdAt", item.createdAt);
    }

    private ImportSession session(UUID sessionId) {
        return sessions.findByTenantIdAndId(TenantContext.tenantId(), sessionId)
            .orElseThrow(() -> ApiErrors.notFound("Import session not found"));
    }

    private DomainEnums.VoucherStockImpactMode defaultStockImpactMode(DomainEnums.ImportStrategy strategy) {
        return switch (strategy) {
            case SNAPSHOT_FIRST -> DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY;
            case HYBRID_RECONCILIATION -> DomainEnums.VoucherStockImpactMode.APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE;
            case TRANSACTION_HISTORY -> DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY;
            case UNKNOWN -> throw ApiErrors.badRequest("Choose a dry-run strategy before simulating stock impact");
        };
    }

    private String dryRunFingerprint(
        UUID tenantId,
        UUID sessionId,
        DomainEnums.VoucherStockImpactMode stockImpactMode,
        DomainEnums.ReturnStockImpactMode returnStockImpactMode,
        DomainEnums.StockJournalImpactMode stockJournalImpactMode,
        DomainEnums.NegativeStockImportPolicy negativePolicy,
        SmartImportSnapshotDateService.Selection dates
    ) {
        return fingerprints.captureDryRun(tenantId, sessionId,
            stockImpactMode.name(),
            returnStockImpactMode.name(),
            stockJournalImpactMode.name(),
            negativePolicy.name(),
            dates.fingerprint());
    }

    private LocalDate effectiveBoundary(
        SmartImportSnapshotEligibilityService.SnapshotEligibilityDecision source,
        SmartImportSnapshotEligibilityService.SnapshotEligibilityDecision destination
    ) {
        if (source == null || source.snapshotDate() == null) {
            return destination == null ? null : destination.snapshotDate();
        }
        if (destination == null || destination.snapshotDate() == null) return source.snapshotDate();
        return source.snapshotDate().isAfter(destination.snapshotDate())
            ? source.snapshotDate() : destination.snapshotDate();
    }

    private String normalizeIssueKind(String issueKind) {
        if (issueKind == null || issueKind.isBlank()) return null;
        var normalized = issueKind.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ERROR", "WARNING", "REVIEW_REQUIRED").contains(normalized)) {
            throw ApiErrors.badRequest("Issue filter must be ERROR, WARNING, or REVIEW_REQUIRED");
        }
        return normalized;
    }

    private DomainEnums.DetectedFileType effectiveType(ImportSessionFile file) {
        return file.selectedFileType == null ? file.detectedFileType : file.selectedFileType;
    }

    private Map<String, Object> linked(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1] == null ? "" : values[index + 1]);
        }
        return result;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (var value : values) if (value != null) return value;
        return null;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private int integer(Object value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private record DryRunWarehouse(UUID id, String name, boolean resolved, boolean autoCreated) { }

    private record DryRunWarehouseIndex(
        Map<UUID, DryRunWarehouse> byId,
        Map<String, DryRunWarehouse> byName
    ) { }

    private record ExistingInvoiceIndex(
        Set<String> sales,
        Set<String> purchases,
        Set<String> creditNotes,
        Set<String> debitNotes
    ) { }

    private record StockDecision(
        boolean applyMovement,
        boolean blocking,
        boolean coveredBySnapshot,
        boolean sameDate,
        boolean productLevelFallback,
        String warningCode,
        String errorCode,
        String reason,
        UUID snapshotId,
        LocalDate snapshotDate,
        String reasonCode
    ) {
        private static StockDecision skip(String warningCode, String reason) {
            return new StockDecision(false, false, false, false, false, warningCode, null,
                reason, null, null, "INVOICE_ONLY");
        }

        private static StockDecision block(String errorCode, String reason) {
            return new StockDecision(false, true, false, false, false, null, errorCode,
                reason, null, null, errorCode);
        }
    }

    private record DryRunContext(
        ImportSession session,
        ImportDryRun dryRun,
        DomainEnums.ImportStrategy strategy,
        DomainEnums.VoucherStockImpactMode stockImpactMode,
        DomainEnums.ReturnStockImpactMode returnStockImpactMode,
        DomainEnums.StockJournalImpactMode stockJournalImpactMode,
        DomainEnums.NegativeStockImportPolicy negativePolicy,
        LocalDate requestedSnapshotDate,
        SmartImportSnapshotDateService.Selection dates,
        Counters counters,
        List<ImportDryRunItem> items
    ) { }

    private static final class Counters {
        private final LinkedHashMap<String, Long> values = new LinkedHashMap<>();

        private Counters() {
            SUMMARY_KEYS.forEach(key -> values.put(key, 0L));
        }

        private void inc(String key) {
            values.compute(key, (ignored, current) -> current == null ? 1L : current + 1L);
        }

        private void set(String key, long value) {
            values.put(key, value);
        }

        private void add(String key, long value) {
            values.merge(key, value, Long::sum);
        }

        private long get(String key) {
            return values.getOrDefault(key, 0L);
        }

        private Map<String, Object> response(
            DomainEnums.ImportStrategy strategy,
            DomainEnums.VoucherStockImpactMode stockImpactMode,
            DomainEnums.ReturnStockImpactMode returnStockImpactMode,
            DomainEnums.StockJournalImpactMode stockJournalImpactMode,
            DomainEnums.NegativeStockImportPolicy negativePolicy,
            LocalDate requestedSnapshotDate
        ) {
            var response = new LinkedHashMap<String, Object>();
            response.putAll(values);
            response.put("strategy", strategy.name());
            response.put("stockImpactMode", stockImpactMode.name());
            response.put("returnStockImpactMode", returnStockImpactMode.name());
            response.put("stockJournalImpactMode", stockJournalImpactMode.name());
            response.put("negativeStockPolicy", negativePolicy.name());
            response.put("requestedSnapshotDate", requestedSnapshotDate == null ? "" : requestedSnapshotDate.toString());
            response.put("netCompanyStockEffect", BigDecimal.ZERO);
            response.put("dryRunOnly", true);
            response.put("finalBusinessWrites", 0);
            return response;
        }
    }
}
