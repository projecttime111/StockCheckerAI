package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SmartImportCommitExecutor {
    private static final Logger log = LoggerFactory.getLogger(SmartImportCommitExecutor.class);
    private static final int WRITE_BATCH_SIZE = 25;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final String SOURCE_SYSTEM = "SMART_IMPORT";

    private final Repositories.ImportSessionRepository sessions;
    private final Repositories.ImportSessionFileRepository files;
    private final Repositories.ImportDryRunRepository dryRuns;
    private final Repositories.ImportDryRunItemRepository dryRunItems;
    private final Repositories.SmartImportCommitRepository commits;
    private final Repositories.SmartImportEffectRepository effects;
    private final Repositories.ExternalRecordMappingRepository mappings;
    private final Repositories.SmartStagedUnitRepository stagedUnits;
    private final Repositories.SmartStagedWarehouseRepository stagedWarehouses;
    private final Repositories.SmartStagedProductRepository stagedProducts;
    private final Repositories.SmartStagedPartyRepository stagedParties;
    private final Repositories.SmartStagedStockSnapshotRepository stagedSnapshots;
    private final Repositories.SmartStagedVoucherRepository stagedVouchers;
    private final Repositories.SmartStagedVoucherItemRepository stagedVoucherItems;
    private final Repositories.SmartStagedTransferCandidateRepository stagedTransfers;
    private final Repositories.SmartStagedCashbookEntryRepository stagedCashbook;
    private final Repositories.SmartStagedStockAgeingRepository stagedAgeing;
    private final Repositories.UnitRepository units;
    private final Repositories.WarehouseRepository warehouses;
    private final Repositories.ProductRepository products;
    private final Repositories.ProductBarcodeRepository barcodes;
    private final Repositories.CategoryRepository categories;
    private final Repositories.CustomerRepository customers;
    private final Repositories.SupplierRepository suppliers;
    private final Repositories.SalesInvoiceRepository salesInvoices;
    private final Repositories.SalesInvoiceItemRepository salesInvoiceItems;
    private final Repositories.PurchaseInvoiceRepository purchaseInvoices;
    private final Repositories.PurchaseInvoiceItemRepository purchaseInvoiceItems;
    private final Repositories.FinancialAdjustmentRepository financialAdjustments;
    private final Repositories.FinancialAdjustmentItemRepository financialAdjustmentItems;
    private final Repositories.CustomerPaymentRepository customerPayments;
    private final Repositories.SupplierPaymentRepository supplierPayments;
    private final Repositories.OutstandingSnapshotRepository outstandingSnapshots;
    private final Repositories.StockMovementRepository stockMovements;
    private final Repositories.StockSnapshotBaselineRepository snapshotBaselines;
    private final StockLedgerService stockLedger;
    private final AuditService audit;
    private final SmartImportSnapshotEligibilityService snapshotEligibility;
    private final SmartImportTransferService transferService;
    private final RetailCounterSaleService retailSales;
    private final SmartImportSnapshotDateService snapshotDates;
    private final InvoiceIdentityService invoiceIdentity;
    private final EntityManager entityManager;

    private final SourceDocumentRevisionService sourceRevisions;

    public SmartImportCommitExecutor(
        Repositories.ImportSessionRepository sessions,
        Repositories.ImportSessionFileRepository files,
        Repositories.ImportDryRunRepository dryRuns,
        Repositories.ImportDryRunItemRepository dryRunItems,
        Repositories.SmartImportCommitRepository commits,
        Repositories.SmartImportEffectRepository effects,
        Repositories.ExternalRecordMappingRepository mappings,
        Repositories.SmartStagedUnitRepository stagedUnits,
        Repositories.SmartStagedWarehouseRepository stagedWarehouses,
        Repositories.SmartStagedProductRepository stagedProducts,
        Repositories.SmartStagedPartyRepository stagedParties,
        Repositories.SmartStagedStockSnapshotRepository stagedSnapshots,
        Repositories.SmartStagedVoucherRepository stagedVouchers,
        Repositories.SmartStagedVoucherItemRepository stagedVoucherItems,
        Repositories.SmartStagedTransferCandidateRepository stagedTransfers,
        Repositories.SmartStagedCashbookEntryRepository stagedCashbook,
        Repositories.SmartStagedStockAgeingRepository stagedAgeing,
        Repositories.UnitRepository units,
        Repositories.WarehouseRepository warehouses,
        Repositories.ProductRepository products,
        Repositories.ProductBarcodeRepository barcodes,
        Repositories.CategoryRepository categories,
        Repositories.CustomerRepository customers,
        Repositories.SupplierRepository suppliers,
        Repositories.SalesInvoiceRepository salesInvoices,
        Repositories.SalesInvoiceItemRepository salesInvoiceItems,
        Repositories.PurchaseInvoiceRepository purchaseInvoices,
        Repositories.PurchaseInvoiceItemRepository purchaseInvoiceItems,
        Repositories.FinancialAdjustmentRepository financialAdjustments,
        Repositories.FinancialAdjustmentItemRepository financialAdjustmentItems,
        Repositories.CustomerPaymentRepository customerPayments,
        Repositories.SupplierPaymentRepository supplierPayments,
        Repositories.OutstandingSnapshotRepository outstandingSnapshots,
        Repositories.StockMovementRepository stockMovements,
        Repositories.StockSnapshotBaselineRepository snapshotBaselines,
        StockLedgerService stockLedger,
        AuditService audit,
        SmartImportSnapshotEligibilityService snapshotEligibility,
        SmartImportTransferService transferService,
        RetailCounterSaleService retailSales,
        SmartImportSnapshotDateService snapshotDates,
        InvoiceIdentityService invoiceIdentity,
        EntityManager entityManager, SourceDocumentRevisionService sourceRevisions
    ) {
        this.sourceRevisions = sourceRevisions;
        this.sessions = sessions;
        this.files = files;
        this.dryRuns = dryRuns;
        this.dryRunItems = dryRunItems;
        this.commits = commits;
        this.effects = effects;
        this.mappings = mappings;
        this.stagedUnits = stagedUnits;
        this.stagedWarehouses = stagedWarehouses;
        this.stagedProducts = stagedProducts;
        this.stagedParties = stagedParties;
        this.stagedSnapshots = stagedSnapshots;
        this.stagedVouchers = stagedVouchers;
        this.stagedVoucherItems = stagedVoucherItems;
        this.stagedTransfers = stagedTransfers;
        this.stagedCashbook = stagedCashbook;
        this.stagedAgeing = stagedAgeing;
        this.units = units;
        this.warehouses = warehouses;
        this.products = products;
        this.barcodes = barcodes;
        this.categories = categories;
        this.customers = customers;
        this.suppliers = suppliers;
        this.salesInvoices = salesInvoices;
        this.salesInvoiceItems = salesInvoiceItems;
        this.purchaseInvoices = purchaseInvoices;
        this.purchaseInvoiceItems = purchaseInvoiceItems;
        this.financialAdjustments = financialAdjustments;
        this.financialAdjustmentItems = financialAdjustmentItems;
        this.customerPayments = customerPayments;
        this.supplierPayments = supplierPayments;
        this.outstandingSnapshots = outstandingSnapshots;
        this.stockMovements = stockMovements;
        this.snapshotBaselines = snapshotBaselines;
        this.stockLedger = stockLedger;
        this.audit = audit;
        this.snapshotEligibility = snapshotEligibility;
        this.transferService = transferService;
        this.retailSales = retailSales;
        this.snapshotDates = snapshotDates;
        this.invoiceIdentity = invoiceIdentity;
        this.entityManager = entityManager;
    }

    @Transactional
    public Map<String, Object> execute(UUID tenantId, UUID sessionId, UUID commitId) {
        retailSales.lockCashbook(tenantId);
        var commit = commits.findByTenantIdAndImportSessionIdAndId(tenantId, sessionId, commitId)
            .orElseThrow(() -> ApiErrors.notFound("Smart Import commit not found"));
        var session = sessions.findByTenantIdAndId(tenantId, sessionId)
            .orElseThrow(() -> ApiErrors.notFound("Import session not found"));
        var dryRun = dryRuns.findByTenantIdAndImportSessionIdAndId(tenantId, sessionId, commit.dryRunId)
            .orElseThrow(() -> ApiErrors.badRequest("The completed dry run is no longer available"));
        if (commit.status == DomainEnums.SmartImportCommitStatus.COMMITTED) {
            return response(commit, true);
        }
        var dates = snapshotDates.fromSummary(dryRun.summaryJson);
        snapshotDates.validateSession(tenantId, sessionId, dates);

        effects.deleteByTenantIdAndCommitId(tenantId, commit.id);
        session.status = DomainEnums.ImportSessionStatus.COMMITTING;
        session.updatedBy = TenantContext.userId();
        sessions.save(session);
        flushAndClear();

        var counts = new CommitCounts();
        var stockImpactMode = stockImpactMode(dryRun);
        var returnStockImpactMode = returnStockImpactMode(dryRun);
        var stockJournalImpactMode = stockJournalImpactMode(dryRun);
        var stockDryRunItems = dryRunItems.findByTenantIdAndDryRunIdAndItemTypeOrderByCreatedAtAsc(
                tenantId, dryRun.id, DomainEnums.ImportDryRunItemType.STOCK_MOVEMENT).stream()
            .collect(Collectors.toMap(
                this::stockDryRunKey,
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new));
        var transferDryRunItems = dryRunItems.findByTenantIdAndDryRunIdAndItemTypeOrderByCreatedAtAsc(
                tenantId, dryRun.id, DomainEnums.ImportDryRunItemType.WAREHOUSE_TRANSFER).stream()
            .collect(Collectors.toMap(
                item -> item.targetEntityId == null
                    ? item.sourceFileId + ":" + item.sourceRowNumber : item.targetEntityId.toString(),
                Function.identity(), (left, right) -> left, LinkedHashMap::new));
        var fileHashes = files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(tenantId, sessionId).stream()
            .collect(Collectors.toMap(file -> file.id, file -> nullSafe(file.fileHash), (left, right) -> left));
        var unitIndex = commitUnits(tenantId, sessionId, commit, counts, fileHashes);
        var warehouseIndex = commitWarehouses(tenantId, sessionId, commit, counts, fileHashes);
        var productIndex = commitProducts(tenantId, sessionId, commit, counts, fileHashes, unitIndex);
        commitParties(tenantId, sessionId, commit, counts, fileHashes);
        log.info("Smart import commit {}: foundation resolved", commit.id);
        commitOutstandingSnapshots(tenantId, sessionId, commit, counts, fileHashes, dates);
        commitSnapshots(tenantId, sessionId, commit, dryRun, counts, fileHashes, unitIndex, warehouseIndex, productIndex);
        log.info("Smart import commit {}: snapshots processed", commit.id);
        commitVouchers(tenantId, sessionId, commit, dryRun, stockImpactMode, returnStockImpactMode, stockDryRunItems,
            counts, fileHashes, unitIndex, warehouseIndex, productIndex);
        log.info("Smart import commit {}: vouchers processed", commit.id);
        commitTransfers(tenantId, sessionId, commit, dryRun, stockJournalImpactMode,
            transferDryRunItems, counts);
        commitCashbook(tenantId, sessionId, commit, counts);
        verifyCashbookReconciliation(dryRun, counts);
        log.info("Smart import commit {}: transfers and cashbook processed", commit.id);

        counts.set("futurePhaseVoucherRowsSkipped", counts.get("vouchersDeferredForFuturePhase"));
        counts.set("futurePhaseCashbookRowsSkipped",
            counts.get("cashbookRowsUnmatched") + counts.get("cashbookRowsReviewRequired"));
        counts.set("futurePhaseAgeingRowsSkipped",
            stagedAgeing.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId).size());

        var finishedAt = Instant.now();
        var summary = counts.response();
        summary.putAll(dates.summary());
        summary.put("warnings", counts.get("futurePhaseCashbookRowsSkipped"));
        summary.put("blockingErrors", 0);
        summary.put("phase", "4B-2E");
        summary.put("voucherStockImpactMode", stockImpactMode.name());
        summary.put("returnStockImpactMode", returnStockImpactMode.name());
        summary.put("stockJournalImpactMode", stockJournalImpactMode.name());
        summary.put("netCompanyStockQuantityChange", BigDecimal.ZERO);
        summary.put("invoiceOnlyMessage", stockImpactMode == DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY
            ? "Sales/Purchase vouchers were imported as invoice-only and did not change stock."
            : "Only eligible sales and purchase items strictly after their applicable closing-stock snapshot changed stock.");
        summary.put("financialAdjustmentMessage",
            returnStockImpactMode == DomainEnums.ReturnStockImpactMode.FINANCIAL_ONLY
                ? "Credit/debit notes were imported as financial adjustments without return stock movements."
                : "Only confirmed physical return lines strictly after their applicable closing-stock snapshot changed stock.");
        summary.put("cashbookMessage", counts.get("futurePhaseCashbookRowsSkipped") > 0
            ? counts.get("futurePhaseCashbookRowsSkipped") + " cashbook rows were not posted as payments because their party, amount, or matching evidence needs review. Other resolved rows were posted."
            : "Resolved cashbook rows were posted as customer or supplier payments.");
        summary.put("outstandingMessage", "Debtor and creditor rows were stored as dated outstanding snapshots; no fake invoices were created.");
        summary.put("futurePhaseMessage",
            "Manufacturing journals, unilateral stock adjustments, damaged/quarantine stock, and stock-ageing reconciliation remain deferred.");
        summary.put("committedAt", finishedAt);
        summary.put("committedBy", commit.committedBy);
        commit.summaryJson = summary;
        commit.status = DomainEnums.SmartImportCommitStatus.COMMITTED;
        commit.finishedAt = finishedAt;
        commit.updatedBy = TenantContext.userId();
        commits.save(commit);

        session.status = DomainEnums.ImportSessionStatus.COMMITTED;
        session.committedAt = finishedAt;
        session.updatedBy = TenantContext.userId();
        sessions.save(session);
        audit.logCurrent("SMART_IMPORT_COMMITTED", "SmartImportCommit", commit.id, linked(
            "sessionId", sessionId,
            "dryRunId", dryRun.id,
            "productsCreated", counts.get("productsCreated"),
            "snapshotMovementsCreated", counts.get("snapshotMovementsCreated"),
            "salesInvoicesCreated", counts.get("salesInvoicesCreated"),
            "purchaseInvoicesCreated", counts.get("purchaseInvoicesCreated"),
            "creditNotesCreated", counts.get("creditNotesCreated"),
            "debitNotesCreated", counts.get("debitNotesCreated"),
            "customerPaymentsCreated", counts.get("customerPaymentsCreated"),
            "supplierPaymentsCreated", counts.get("supplierPaymentsCreated"),
            "outstandingSnapshotsCreated", counts.get("outstandingSnapshotsCreated"),
            "voucherStockMovementsCreated", counts.get("voucherStockMovementsCreated"),
            "warehouseTransfersCommitted", counts.get("warehouseTransfersCommitted"),
            "transferOutMovementsCreated", counts.get("transferOutMovementsCreated"),
            "transferInMovementsCreated", counts.get("transferInMovementsCreated"),
            "netCompanyStockEffect", BigDecimal.ZERO
        ));
        return response(commit, false);
    }

    private Map<String, UnitOfMeasure> commitUnits(
        UUID tenantId, UUID sessionId, SmartImportCommit commit, CommitCounts counts, Map<UUID, String> fileHashes
    ) {
        var index = units.findByTenantIdOrderByCodeAsc(tenantId).stream()
            .collect(Collectors.toMap(unit -> normalizeUnit(unit.code), Function.identity(), (left, right) -> left, LinkedHashMap::new));
        int processed = 0;
        for (var row : stagedUnits.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId)) {
            flushBatch(processed++);
            var code = normalizeUnit(firstNonBlank(row.normalizedCode, row.sourceUnitCode, "PCS"));
            var existing = row.matchedUnitId == null ? index.get(code)
                : units.findByTenantIdAndId(tenantId, row.matchedUnitId).orElse(null);
            if (existing == null) {
                existing = new UnitOfMeasure();
                own(existing, tenantId);
                existing.code = code;
                existing.name = code;
                existing.baseUnit = true;
                existing = units.save(existing);
                index.put(code, existing);
                counts.inc("unitsCreated");
                effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.UNIT,
                    existing.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked("code", code), true);
            } else {
                counts.inc("unitsMatchedExisting");
                effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.UNIT,
                    existing.id, DomainEnums.SmartImportEffectAction.MATCHED_EXISTING, Map.of(), linked("code", code), false);
            }
            upsertMapping(commit, row.sessionFileId, "UNIT", null, code, "UNIT", existing.id,
                fileHashes.get(row.sessionFileId));
        }
        return index;
    }

    private Map<String, Warehouse> commitWarehouses(
        UUID tenantId, UUID sessionId, SmartImportCommit commit, CommitCounts counts, Map<UUID, String> fileHashes
    ) {
        var index = warehouses.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).stream()
            .collect(Collectors.toMap(warehouse -> normalizeName(warehouse.name), Function.identity(), (left, right) -> left, LinkedHashMap::new));
        int processed = 0;
        for (var row : stagedWarehouses.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId)) {
            flushBatch(processed++);
            requireResolved(row.matchStatus, row.reviewStatus, "warehouse", row.sourceRowNumber);
            var key = normalizeName(firstNonBlank(row.normalizedName, row.sourceWarehouseName));
            if (key.isBlank()) continue;
            var warehouse = row.matchedWarehouseId == null ? index.get(key)
                : warehouses.findByTenantIdAndId(tenantId, row.matchedWarehouseId).orElse(null);
            if (warehouse == null) {
                warehouse = new Warehouse();
                own(warehouse, tenantId);
                warehouse.name = row.sourceWarehouseName == null ? key : row.sourceWarehouseName.trim();
                warehouse.code = importCode("WH", key);
                warehouse.active = true;
                warehouse = warehouses.save(warehouse);
                index.put(key, warehouse);
                counts.inc("warehousesCreated");
                effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.WAREHOUSE,
                    warehouse.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked("name", warehouse.name), true);
            } else {
                counts.inc("warehousesMatchedExisting");
                effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.WAREHOUSE,
                    warehouse.id, DomainEnums.SmartImportEffectAction.MATCHED_EXISTING, Map.of(), linked("name", warehouse.name), false);
            }
            upsertMapping(commit, row.sessionFileId, "WAREHOUSE", null, key, "WAREHOUSE", warehouse.id,
                fileHashes.get(row.sessionFileId));
        }
        return index;
    }

    private Map<String, Product> commitProducts(
        UUID tenantId, UUID sessionId, SmartImportCommit commit, CommitCounts counts, Map<UUID, String> fileHashes,
        Map<String, UnitOfMeasure> unitIndex
    ) {
        var index = new LinkedHashMap<String, Product>();
        for (var product : products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)) {
            index.put(productKey(product.normalizedName, unitCode(tenantId, product)), product);
        }
        int processed = 0;
        for (var row : stagedProducts.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId)) {
            flushBatch(processed++);
            requireResolved(row.matchStatus, row.reviewStatus, "product", row.sourceRowNumber);
            var unit = ensureUnit(tenantId, commit, row.sessionFileId, row.sourceRowNumber, row.unitCode, counts, unitIndex);
            var key = productKey(firstNonBlank(row.normalizedName, normalizeName(row.rawName)), unit.code);
            var product = resolveProduct(tenantId, row, key, unit.id, index);
            if (product == null) {
                product = new Product();
                own(product, tenantId);
                product.sku = blankToNull(row.sku);
                product.name = firstNonBlank(row.rawName, row.normalizedName, "Imported Product").trim();
                product.normalizedName = normalizeName(product.name);
                product.baseUnitId = unit.id;
                product.categoryId = ensureCategory(tenantId, commit, row.sessionFileId, row.sourceRowNumber,
                    firstNonBlank(row.categoryName, "Imported Uncategorized")).id;
                product.hsnCode = blankToNull(row.hsn);
                product.gstPercentage = nvl(row.gstPercent);
                product.defaultPurchasePrice = nvl(row.purchasePrice);
                product.defaultSalesPrice = nvl(row.salesPrice);
                product.rawMetadata = new LinkedHashMap<>(row.rawMetadataJson == null ? Map.of() : row.rawMetadataJson);
                product = products.save(product);
                createBarcodeIfPresent(tenantId, product.id, row.barcode);
                index.put(productKey(product.normalizedName, unit.code), product);
                counts.inc("productsCreated");
                effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.PRODUCT,
                    product.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), productValue(product), true);
            } else {
                var oldValue = productValue(product);
                var updated = fillSafeProductFields(tenantId, commit, row, product);
                if (updated) {
                    products.save(product);
                    counts.inc("productsUpdated");
                    effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.PRODUCT,
                        product.id, DomainEnums.SmartImportEffectAction.UPDATED, oldValue, productValue(product), false);
                } else {
                    counts.inc("productsMatchedExisting");
                    effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.PRODUCT,
                        product.id, DomainEnums.SmartImportEffectAction.MATCHED_EXISTING, Map.of(), productValue(product), false);
                }
                createBarcodeIfPresent(tenantId, product.id, row.barcode);
                index.put(productKey(product.normalizedName, unitCode(tenantId, product)), product);
            }
            // Keep the source name/unit alias even when an existing master identity resolved it.
            index.put(key, product);
            upsertMapping(commit, row.sessionFileId, "PRODUCT", SmartImportProductIdentity.externalId(row), key, "PRODUCT", product.id,
                fileHashes.get(row.sessionFileId));
        }
        return index;
    }

    private void commitParties(
        UUID tenantId, UUID sessionId, SmartImportCommit commit, CommitCounts counts, Map<UUID, String> fileHashes
    ) {
        int processed = 0;
        for (var row : stagedParties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId)) {
            flushBatch(processed++);
            requireResolved(row.matchStatus, row.reviewStatus, "party", row.sourceRowNumber);
            if (row.partyType == DomainEnums.SmartPartyType.UNKNOWN) {
                throw ApiErrors.badRequest("Party type is unresolved at row " + row.sourceRowNumber);
            }
            var key = row.partyType + "|" + normalizeName(row.normalizedName == null ? row.rawName : row.normalizedName);
            if (row.partyType == DomainEnums.SmartPartyType.CUSTOMER) {
                var customer = resolveCustomer(tenantId, row);
                if (customer == null) {
                    customer = new Customer();
                    own(customer, tenantId);
                    customer.name = firstNonBlank(row.rawName, row.normalizedName, "Imported Customer");
                    customer.phone = blankToNull(row.phone);
                    customer.email = blankToNull(row.email);
                    customer.gstin = blankToNull(row.gstin);
                    customer.openingBalance = row.sourceType == DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS
                        ? ZERO : nvl(row.openingBalance);
                    customer = customers.save(customer);
                    counts.inc("partiesCreated");
                    effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.CUSTOMER,
                        customer.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked("name", customer.name), true);
                } else {
                    counts.inc("partiesMatchedExisting");
                    effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.CUSTOMER,
                        customer.id, DomainEnums.SmartImportEffectAction.MATCHED_EXISTING, Map.of(), linked("name", customer.name), false);
                }
                upsertMapping(commit, row.sessionFileId, "CUSTOMER", null, key, "CUSTOMER", customer.id,
                    fileHashes.get(row.sessionFileId));
            } else {
                var supplier = resolveSupplier(tenantId, row);
                if (supplier == null) {
                    supplier = new Supplier();
                    own(supplier, tenantId);
                    supplier.name = firstNonBlank(row.rawName, row.normalizedName, "Imported Supplier");
                    supplier.phone = blankToNull(row.phone);
                    supplier.email = blankToNull(row.email);
                    supplier.gstin = blankToNull(row.gstin);
                    supplier.openingBalance = row.sourceType == DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS
                        ? ZERO : nvl(row.openingBalance);
                    supplier = suppliers.save(supplier);
                    counts.inc("partiesCreated");
                    effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.SUPPLIER,
                        supplier.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked("name", supplier.name), true);
                } else {
                    counts.inc("partiesMatchedExisting");
                    effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.SUPPLIER,
                        supplier.id, DomainEnums.SmartImportEffectAction.MATCHED_EXISTING, Map.of(), linked("name", supplier.name), false);
                }
                upsertMapping(commit, row.sessionFileId, "SUPPLIER", null, key, "SUPPLIER", supplier.id,
                    fileHashes.get(row.sessionFileId));
            }
        }
    }

    private void commitOutstandingSnapshots(
        UUID tenantId, UUID sessionId, SmartImportCommit commit, CommitCounts counts, Map<UUID, String> fileHashes,
        SmartImportSnapshotDateService.Selection dates
    ) {
        int processed = 0;
        for (var row : stagedParties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId)) {
            flushBatch(processed++);
            if (row.sourceType != DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS) continue;
            requireResolved(row.matchStatus, row.reviewStatus, "outstanding party", row.sourceRowNumber);
            var snapshotDate = dates.outstandingDate(row.snapshotDate);
            var amount = nvl(row.openingBalance);
            if (row.partyType == DomainEnums.SmartPartyType.CUSTOMER) {
                counts.inc("debtorRowsProcessed");
                var customer = resolveCustomer(tenantId, row);
                if (customer == null) throw ApiErrors.badRequest("Customer is unresolved for debtor row " + row.sourceRowNumber);
                commitOutstandingSnapshot(tenantId, commit, counts, fileHashes, row, snapshotDate, amount,
                    DomainEnums.OutstandingPartyType.CUSTOMER, customer.id, null);
            } else if (row.partyType == DomainEnums.SmartPartyType.SUPPLIER) {
                counts.inc("creditorRowsProcessed");
                var supplier = resolveSupplier(tenantId, row);
                if (supplier == null) throw ApiErrors.badRequest("Supplier is unresolved for creditor row " + row.sourceRowNumber);
                commitOutstandingSnapshot(tenantId, commit, counts, fileHashes, row, snapshotDate, amount,
                    DomainEnums.OutstandingPartyType.SUPPLIER, null, supplier.id);
            } else {
                throw ApiErrors.badRequest("Party type is unresolved for outstanding row " + row.sourceRowNumber);
            }
        }
        if (counts.get("outstandingSnapshotsCreated") > 0) {
            audit.logCurrent("SMART_IMPORT_OUTSTANDING_SNAPSHOTS_COMMITTED", "SmartImportCommit", commit.id, Map.of(
                "count", counts.get("outstandingSnapshotsCreated"),
                "debtors", counts.get("debtorRowsProcessed"),
                "creditors", counts.get("creditorRowsProcessed")
            ));
        }
    }

    private void commitOutstandingSnapshot(
        UUID tenantId, SmartImportCommit commit, CommitCounts counts, Map<UUID, String> fileHashes,
        SmartStagedParty row, LocalDate snapshotDate, BigDecimal amount,
        DomainEnums.OutstandingPartyType partyType, UUID customerId, UUID supplierId
    ) {
        var partyId = customerId == null ? supplierId : customerId;
        var fingerprint = sha256(tenantId + "|OUTSTANDING|" + partyType + "|" + partyId + "|" + snapshotDate
            + "|" + amount.stripTrailingZeros().toPlainString());
        var existing = outstandingSnapshots.findByTenantIdAndSourceFingerprint(tenantId, fingerprint).orElse(null);
        if (existing != null) {
            counts.inc("duplicateOutstandingSnapshotsSkipped");
            effect(commit, row.sessionFileId, row.sourceRowNumber,
                DomainEnums.SmartImportEffectEntityType.OUTSTANDING_SNAPSHOT, existing.id,
                DomainEnums.SmartImportEffectAction.SKIPPED, Map.of(), linked("reason", "DUPLICATE_SNAPSHOT"), false);
            return;
        }
        var snapshot = new OutstandingSnapshot();
        own(snapshot, tenantId);
        snapshot.partyType = partyType;
        snapshot.customerId = customerId;
        snapshot.supplierId = supplierId;
        snapshot.snapshotDate = snapshotDate;
        snapshot.outstandingAmount = amount;
        snapshot.overdueAmount = decimal(row.rawMetadataJson == null ? null : row.rawMetadataJson.get("Overdue Amount"));
        snapshot.sourceImportSessionId = row.importSessionId;
        snapshot.sourceFileId = row.sessionFileId;
        snapshot.sourceRowNumber = row.sourceRowNumber;
        snapshot.sourceFingerprint = fingerprint;
        snapshot.rawMetadataJson = new LinkedHashMap<>(row.rawMetadataJson == null ? Map.of() : row.rawMetadataJson);
        snapshot = outstandingSnapshots.save(snapshot);
        counts.inc("outstandingSnapshotsCreated");
        effect(commit, row.sessionFileId, row.sourceRowNumber,
            DomainEnums.SmartImportEffectEntityType.OUTSTANDING_SNAPSHOT, snapshot.id,
            DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked(
                "partyType", partyType, "partyId", partyId, "snapshotDate", snapshotDate,
                "outstandingAmount", amount, "sourceHash", fileHashes.get(row.sessionFileId)), true);
    }

    private void commitCashbook(UUID tenantId, UUID sessionId, SmartImportCommit commit, CommitCounts counts) {
        int processed = 0;
        for (var row : stagedCashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(tenantId, sessionId)) {
            flushBatch(processed++);
            if (retailSales.restoreResolution(row)) {
                stagedCashbook.save(row);
                counts.inc("retailCounterSaleRowsResolved");
                continue;
            }
            if (row.resolutionStatus == DomainEnums.CashbookResolutionStatus.IGNORED) {
                counts.inc("cashbookRowsIgnored");
                continue;
            }
            if (row.resolutionStatus == DomainEnums.CashbookResolutionStatus.PAYMENT_POSTED) {
                counts.inc("cashbookRowsMatched");
                counts.inc("cashbookRowsManuallyResolved");
                if (row.customerPaymentId != null) counts.inc("manualCustomerPaymentsCreated");
                if (row.supplierPaymentId != null) counts.inc("manualSupplierPaymentsCreated");
                if (row.matchedInvoiceId != null && row.matchedPartyType == DomainEnums.SmartPartyType.CUSTOMER) {
                    counts.inc("manualPaymentsLinkedToSalesInvoices");
                }
                if (row.matchedInvoiceId != null && row.matchedPartyType == DomainEnums.SmartPartyType.SUPPLIER) {
                    counts.inc("manualPaymentsLinkedToPurchaseInvoices");
                }
                continue;
            }
            if (row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.UNMATCHED_REVIEW) {
                counts.inc("cashbookRowsUnmatched");
                continue;
            }
            if (row.cashbookMatchStatus == DomainEnums.CashbookMatchStatus.LOW_CONFIDENCE_REVIEW) {
                counts.inc("cashbookRowsReviewRequired");
                continue;
            }
            if (!SmartImportCashbookMatchingService.ready(row)) {
                throw ApiErrors.conflict("Cashbook row " + row.sourceRowNumber
                    + " is no longer ready. Run a new dry run and review its matching evidence.");
            }
            if (row.matchedPartyType == DomainEnums.SmartPartyType.CUSTOMER) {
                commitCustomerPayment(tenantId, row, commit, counts);
            } else if (row.matchedPartyType == DomainEnums.SmartPartyType.SUPPLIER) {
                commitSupplierPayment(tenantId, row, commit, counts);
            } else {
                counts.inc("cashbookRowsReviewRequired");
            }
        }
        var matched = counts.get("customerPaymentsCreated") + counts.get("supplierPaymentsCreated");
        if (matched > 0) {
            audit.logCurrent("SMART_IMPORT_CASHBOOK_COMMITTED", "SmartImportCommit", commit.id, Map.of("count", matched));
            audit.logCurrent("SMART_IMPORT_PAYMENTS_MATCHED", "SmartImportCommit", commit.id, Map.of(
                "customerPayments", counts.get("customerPaymentsCreated"),
                "supplierPayments", counts.get("supplierPaymentsCreated"),
                "salesInvoicesLinked", counts.get("paymentsLinkedToSalesInvoices"),
                "purchaseInvoicesLinked", counts.get("paymentsLinkedToPurchaseInvoices")
            ));
        }
        var review = counts.get("cashbookRowsUnmatched") + counts.get("cashbookRowsReviewRequired");
        if (review > 0) {
            audit.logCurrent("SMART_IMPORT_CASHBOOK_REVIEW_REQUIRED", "SmartImportCommit", commit.id, Map.of("count", review));
        }
        if (counts.get("duplicatePaymentsSkipped") > 0) {
            audit.logCurrent("SMART_IMPORT_DUPLICATE_PAYMENTS_SKIPPED", "SmartImportCommit", commit.id,
                Map.of("count", counts.get("duplicatePaymentsSkipped")));
        }
    }

    private void commitCustomerPayment(
        UUID tenantId, SmartStagedCashbookEntry row, SmartImportCommit commit, CommitCounts counts
    ) {
        var customer = row.matchedPartyId == null ? null : customers.findByTenantIdAndId(tenantId, row.matchedPartyId).orElse(null);
        if (customer == null && row.matchedPartyName != null) {
            customer = customerBySourceName(tenantId, row.matchedPartyName);
        }
        if (customer == null) {
            throw ApiErrors.conflict("Verified customer is unresolved for cashbook row " + row.sourceRowNumber
                + ". Run a new dry run; no payment was silently skipped.");
        }
        var fingerprint = firstNonBlank(row.fingerprint, sha256(tenantId + "|CASHBOOK|" + row.id));
        var duplicate = customerPayments.findByTenantIdAndSourceFingerprint(tenantId, fingerprint).orElse(null);
        if (duplicate != null) {
            counts.inc("duplicatePaymentsSkipped");
            counts.inc("cashbookRowsMatched");
            effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.CUSTOMER_PAYMENT,
                duplicate.id, DomainEnums.SmartImportEffectAction.SKIPPED, Map.of(), linked("reason", "DUPLICATE_PAYMENT"), false);
            return;
        }
        var invoice = resolveSalesInvoice(tenantId, row, customer.id);
        var payment = new CustomerPayment();
        own(payment, tenantId);
        payment.customerId = customer.id;
        payment.salesInvoiceId = invoice == null ? null : invoice.id;
        payment.amount = row.amount.abs();
        payment.paymentDate = row.entryDate;
        payment.mode = row.paymentMode == null ? DomainEnums.PaymentMode.OTHER : row.paymentMode;
        payment.referenceNumber = blankToNull(row.referenceNumber);
        payment.sourceImportSessionId = row.importSessionId;
        payment.sourceFileId = row.sessionFileId;
        payment.sourceRowNumber = row.sourceRowNumber;
        payment.sourceFingerprint = fingerprint;
        payment.rawMetadataJson = new LinkedHashMap<>(row.rawMetadataJson == null ? Map.of() : row.rawMetadataJson);
        payment.notes = "Smart Import cashbook receipt";
        payment = customerPayments.save(payment);
        counts.inc("customerPaymentsCreated");
        counts.inc("cashbookRowsMatched");
        if (invoice != null) counts.inc("paymentsLinkedToSalesInvoices");
        effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.CUSTOMER_PAYMENT,
            payment.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked(
                "customerId", customer.id, "salesInvoiceId", payment.salesInvoiceId,
                "amount", payment.amount, "paymentDate", payment.paymentDate), true);
    }

    private void commitSupplierPayment(
        UUID tenantId, SmartStagedCashbookEntry row, SmartImportCommit commit, CommitCounts counts
    ) {
        var supplier = row.matchedPartyId == null ? null : suppliers.findByTenantIdAndId(tenantId, row.matchedPartyId).orElse(null);
        if (supplier == null && row.matchedPartyName != null) {
            supplier = supplierBySourceName(tenantId, row.matchedPartyName);
        }
        if (supplier == null) {
            throw ApiErrors.conflict("Verified supplier is unresolved for cashbook row " + row.sourceRowNumber
                + ". Run a new dry run; no payment was silently skipped.");
        }
        var fingerprint = firstNonBlank(row.fingerprint, sha256(tenantId + "|CASHBOOK|" + row.id));
        var duplicate = supplierPayments.findByTenantIdAndSourceFingerprint(tenantId, fingerprint).orElse(null);
        if (duplicate != null) {
            counts.inc("duplicatePaymentsSkipped");
            counts.inc("cashbookRowsMatched");
            effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.SUPPLIER_PAYMENT,
                duplicate.id, DomainEnums.SmartImportEffectAction.SKIPPED, Map.of(), linked("reason", "DUPLICATE_PAYMENT"), false);
            return;
        }
        var invoice = resolvePurchaseInvoice(tenantId, row, supplier.id);
        var payment = new SupplierPayment();
        own(payment, tenantId);
        payment.supplierId = supplier.id;
        payment.purchaseInvoiceId = invoice == null ? null : invoice.id;
        payment.amount = row.amount.abs();
        payment.paymentDate = row.entryDate;
        payment.mode = row.paymentMode == null ? DomainEnums.PaymentMode.OTHER : row.paymentMode;
        payment.referenceNumber = blankToNull(row.referenceNumber);
        payment.sourceImportSessionId = row.importSessionId;
        payment.sourceFileId = row.sessionFileId;
        payment.sourceRowNumber = row.sourceRowNumber;
        payment.sourceFingerprint = fingerprint;
        payment.rawMetadataJson = new LinkedHashMap<>(row.rawMetadataJson == null ? Map.of() : row.rawMetadataJson);
        payment.notes = "Smart Import cashbook payment";
        payment = supplierPayments.save(payment);
        counts.inc("supplierPaymentsCreated");
        counts.inc("cashbookRowsMatched");
        if (invoice != null) counts.inc("paymentsLinkedToPurchaseInvoices");
        effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.SUPPLIER_PAYMENT,
            payment.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked(
                "supplierId", supplier.id, "purchaseInvoiceId", payment.purchaseInvoiceId,
                "amount", payment.amount, "paymentDate", payment.paymentDate), true);
    }

    private SalesInvoice resolveSalesInvoice(UUID tenantId, SmartStagedCashbookEntry row, UUID customerId) {
        if (row.cashbookMatchStatus != DomainEnums.CashbookMatchStatus.MATCHED_SALES_INVOICE) return null;
        var id = cashbookInvoiceId(tenantId, row, "SALES_VOUCHER");
        var invoice = id == null ? null : salesInvoices.findByTenantIdAndId(tenantId, id).orElse(null);
        if (invoice == null || !customerId.equals(invoice.customerId)) {
            throw ApiErrors.conflict("Sales invoice identity changed for cashbook row " + row.sourceRowNumber + ". Run a new dry run.");
        }
        return invoice;
    }

    private PurchaseInvoice resolvePurchaseInvoice(UUID tenantId, SmartStagedCashbookEntry row, UUID supplierId) {
        if (row.cashbookMatchStatus != DomainEnums.CashbookMatchStatus.MATCHED_PURCHASE_INVOICE) return null;
        var id = cashbookInvoiceId(tenantId, row, "PURCHASE_VOUCHER");
        var invoice = id == null ? null : purchaseInvoices.findByTenantIdAndId(tenantId, id).orElse(null);
        if (invoice == null || !supplierId.equals(invoice.supplierId)) {
            throw ApiErrors.conflict("Purchase invoice identity changed for cashbook row " + row.sourceRowNumber + ". Run a new dry run.");
        }
        return invoice;
    }

    private UUID cashbookInvoiceId(UUID tenantId, SmartStagedCashbookEntry row, String type) {
        if (row.matchedInvoiceId != null) return row.matchedInvoiceId;
        if (row.matchedStagedVoucherId == null) return null;
        var voucher = stagedVouchers.findByTenantIdAndImportSessionIdAndId(
            tenantId, row.importSessionId, row.matchedStagedVoucherId).orElse(null);
        if (voucher == null || excludedVoucher(voucher)) return null;
        return mappedInvoiceId(tenantId, type, voucher);
    }

    private void verifyCashbookReconciliation(ImportDryRun dryRun, CommitCounts counts) {
        var checks = Map.of("customerPaymentsPreviewed", "customerPaymentsCreated",
            "supplierPaymentsPreviewed", "supplierPaymentsCreated",
            "paymentsLinkedToSalesInvoices", "paymentsLinkedToSalesInvoices",
            "paymentsLinkedToPurchaseInvoices", "paymentsLinkedToPurchaseInvoices",
            "duplicatePaymentsSkipped", "duplicatePaymentsSkipped");
        for (var check : checks.entrySet()) {
            var expected = Long.parseLong(Objects.toString(dryRun.summaryJson.get(check.getKey()), "0"));
            if (expected != counts.get(check.getValue())) {
                throw ApiErrors.conflict("Cashbook reconciliation differs from the dry run (" + check.getKey()
                    + "). Nothing was partially committed. Run a new dry run.");
            }
        }
    }

    private void commitSnapshots(
        UUID tenantId, UUID sessionId, SmartImportCommit commit, ImportDryRun dryRun, CommitCounts counts,
        Map<UUID, String> fileHashes, Map<String, UnitOfMeasure> unitIndex,
        Map<String, Warehouse> warehouseIndex, Map<String, Product> productIndex
    ) {
        var policy = negativePolicy(dryRun);
        int processed = 0;
        for (var row : stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId)) {
            flushBatch(processed++);
            counts.inc("snapshotRowsProcessed");
            var imported = nvl(row.importedStock);
            if (imported.signum() < 0 && (row.matchStatus == DomainEnums.SmartMatchStatus.MATCH_EXISTING
                || row.matchStatus == DomainEnums.SmartMatchStatus.CREATE_NEW)) {
                // The remaining pending review is the negative-stock policy itself, already confirmed by the dry run.
            } else {
                requireResolved(row.matchStatus, row.reviewStatus, "stock snapshot", row.sourceRowNumber);
            }
            var unit = ensureUnit(tenantId, commit, row.sessionFileId, row.sourceRowNumber, row.unitCode, counts, unitIndex);
            var productKey = productKey(firstNonBlank(row.normalizedProductName, normalizeName(row.productName)), unit.code);
            var product = row.matchedProductId == null ? productIndex.get(productKey)
                : products.findByTenantIdAndId(tenantId, row.matchedProductId).orElse(null);
            if (product == null) {
                product = new Product();
                own(product, tenantId);
                product.name = firstNonBlank(row.productName, row.normalizedProductName, "Imported Product");
                product.normalizedName = normalizeName(product.name);
                product.baseUnitId = unit.id;
                product.categoryId = ensureCategory(tenantId, commit, row.sessionFileId, row.sourceRowNumber,
                    "Imported Uncategorized").id;
                product.defaultPurchasePrice = nvl(row.rate);
                product.rawMetadata = new LinkedHashMap<>(row.rawMetadataJson == null ? Map.of() : row.rawMetadataJson);
                product = products.save(product);
                productIndex.put(productKey, product);
                counts.inc("productsCreated");
                effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.PRODUCT,
                    product.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), productValue(product), true);
            }
            var warehouse = resolveSnapshotWarehouse(tenantId, commit, row, counts, warehouseIndex);
            var snapshotDate = snapshotDates.fromSummary(dryRun.summaryJson).stockDate(row.snapshotDate);
            if (imported.signum() < 0) {
                counts.inc("negativeStockRowsFound");
                if (policy == DomainEnums.NegativeStockImportPolicy.SKIP_STOCK_MOVEMENT) {
                    counts.inc("negativeStockRowsSkipped");
                    effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.STOCK_MOVEMENT,
                        product.id, DomainEnums.SmartImportEffectAction.SKIPPED, Map.of(), linked("reason", "NEGATIVE_STOCK_SKIPPED"), false);
                    continue;
                }
                if (policy == DomainEnums.NegativeStockImportPolicy.BLOCK || !stockLedger.negativeStockAllowed(tenantId)) {
                    throw ApiErrors.badRequest("Negative snapshot stock is blocked at row " + row.sourceRowNumber);
                }
            }
            var stockAtDate = stockLedger.stockAt(tenantId, product.id, warehouse.id, snapshotDate);
            var delta = imported.subtract(stockAtDate);
            UUID adjustmentMovementId = null;
            if (delta.signum() == 0) {
                counts.inc("snapshotRowsNoChange");
                effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.STOCK_MOVEMENT,
                    product.id, DomainEnums.SmartImportEffectAction.SKIPPED, linked("stockAtSnapshotDate", stockAtDate),
                    linked("importedStock", imported, "delta", ZERO, "action", "NO_CHANGE"), false);
            } else {
                // Stay clear of database timestamp rounding at the day boundary so a repeated snapshot sees this adjustment.
                var movementDate = snapshotDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
                var movement = stockLedger.createMovement(tenantId, product.id, warehouse.id,
                    DomainEnums.MovementType.ADJUSTMENT, delta, product.baseUnitId, nvl(row.rate),
                    "SMART_IMPORT_COMMIT", commit.id, movementDate,
                    "Smart Import stock snapshot adjustment for " + snapshotDate);
                adjustmentMovementId = movement.id;
                counts.inc("snapshotMovementsCreated");
                counts.inc(delta.signum() > 0 ? "snapshotPositiveAdjustments" : "snapshotNegativeAdjustments");
                effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.STOCK_MOVEMENT,
                    movement.id, DomainEnums.SmartImportEffectAction.CREATED, linked("stockAtSnapshotDate", stockAtDate),
                    linked("importedStock", imported, "delta", delta, "warehouseId", warehouse.id,
                        "snapshotDate", snapshotDate), true);
            }
            var baseline = new StockSnapshotBaseline();
            own(baseline, tenantId);
            baseline.productId = product.id;
            baseline.warehouseId = row.matchedWarehouseId != null
                || row.warehouseName != null && !row.warehouseName.isBlank() ? warehouse.id : null;
            baseline.adjustmentWarehouseId = warehouse.id;
            baseline.snapshotDate = snapshotDate;
            baseline.importedQuantity = imported;
            baseline.adjustmentMovementId = adjustmentMovementId;
            baseline.sourceImportSessionId = sessionId;
            baseline.sourceFileId = row.sessionFileId;
            baseline.sourceRowNumber = row.sourceRowNumber;
            snapshotBaselines.save(baseline);
            var mappingKey = product.id + "|" + warehouse.id + "|" + snapshotDate;
            upsertMapping(commit, row.sessionFileId, "STOCK_SNAPSHOT", null, mappingKey,
                "PRODUCT", product.id, fileHashes.get(row.sessionFileId));
        }
    }

    private void commitVouchers(
        UUID tenantId, UUID sessionId, SmartImportCommit commit, ImportDryRun dryRun,
        DomainEnums.VoucherStockImpactMode stockImpactMode,
        DomainEnums.ReturnStockImpactMode returnStockImpactMode,
        Map<String, ImportDryRunItem> stockDryRunItems,
        CommitCounts counts, Map<UUID, String> fileHashes,
        Map<String, UnitOfMeasure> unitIndex, Map<String, Warehouse> warehouseIndex, Map<String, Product> productIndex
    ) {
        if (stockImpactMode == DomainEnums.VoucherStockImpactMode.APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE) {
            audit.logCurrent("SMART_IMPORT_POST_SNAPSHOT_STOCK_MODE_SELECTED", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "mode", stockImpactMode));
        }
        if (returnStockImpactMode == DomainEnums.ReturnStockImpactMode.APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE) {
            audit.logCurrent("SMART_IMPORT_RETURN_MODE_SELECTED", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "mode", returnStockImpactMode));
        }
        var itemsByVoucher = stagedVoucherItems
            .findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId).stream()
            .collect(Collectors.groupingBy(item -> item.stagedVoucherId, LinkedHashMap::new, Collectors.toList()));
        var stockJournalVoucherIds = stagedTransfers
            .findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(tenantId, sessionId).stream()
            .map(candidate -> candidate.stagedVoucherId).collect(Collectors.toSet());
        int processed = 0;
        for (var voucher : stagedVouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(tenantId, sessionId)) {
            flushBatch(processed++);
            if (processed % 250 == 0) log.info("Smart import commit {}: processing voucher {}", commit.id, processed);
            if (stockJournalVoucherIds.contains(voucher.id)) continue;
            if (excludedVoucher(voucher)) {
                counts.inc("vouchersExcludedByUser");
                continue;
            }
            var kind = voucherKind(voucher.voucherType);
            if (kind == VoucherKind.DEFERRED) {
                counts.inc("vouchersDeferredForFuturePhase");
                continue;
            }
            var sourceItems = itemsByVoucher.getOrDefault(voucher.id, List.of());
            if (sourceItems.isEmpty() && (kind == VoucherKind.SALES || kind == VoucherKind.PURCHASE)) {
                throw ApiErrors.badRequest("Voucher " + nullSafe(voucher.voucherNumber) + " has no inventory items to post");
            }
            if (voucher.matchStatus != DomainEnums.SmartMatchStatus.SKIP_DUPLICATE) {
                requireResolved(voucher.matchStatus, voucher.reviewStatus, "voucher", voucher.sourceRowNumber);
            }
            sourceItems.forEach(item -> requireResolved(item.matchStatus, item.reviewStatus, "voucher item", item.sourceRowNumber));
            counts.set("ledgerLinesSkipped", counts.get("ledgerLinesSkipped") + sourceItems.stream()
                .mapToLong(item -> integer(item.rawMetadataJson == null ? null : item.rawMetadataJson.get("Ledger Lines Skipped")))
                .max().orElse(0));
            captureFinancialCounts(counts, voucher);
            switch (kind) {
                case SALES -> commitSalesVoucher(tenantId, voucher, sourceItems, commit, dryRun, stockImpactMode,
                    stockDryRunItems, counts, fileHashes, unitIndex, warehouseIndex, productIndex);
                case PURCHASE -> commitPurchaseVoucher(tenantId, voucher, sourceItems, commit, dryRun, stockImpactMode,
                    stockDryRunItems, counts, fileHashes, unitIndex, warehouseIndex, productIndex);
                case CREDIT_NOTE -> commitFinancialNote(tenantId, voucher, sourceItems, DomainEnums.FinancialAdjustmentType.CREDIT_NOTE,
                    commit, dryRun, returnStockImpactMode, stockDryRunItems, counts, fileHashes,
                    unitIndex, warehouseIndex, productIndex);
                case DEBIT_NOTE -> commitFinancialNote(tenantId, voucher, sourceItems, DomainEnums.FinancialAdjustmentType.DEBIT_NOTE,
                    commit, dryRun, returnStockImpactMode, stockDryRunItems, counts, fileHashes,
                    unitIndex, warehouseIndex, productIndex);
                case DEFERRED -> { }
            }
        }
        if (counts.get("salesInvoicesCreated") > 0) {
            audit.logCurrent("SMART_IMPORT_SALES_INVOICES_COMMITTED", "SmartImportCommit", commit.id,
                Map.of("count", counts.get("salesInvoicesCreated"), "items", counts.get("salesInvoiceItemsCreated")));
        }
        if (counts.get("purchaseInvoicesCreated") > 0) {
            audit.logCurrent("SMART_IMPORT_PURCHASE_INVOICES_COMMITTED", "SmartImportCommit", commit.id,
                Map.of("count", counts.get("purchaseInvoicesCreated"), "items", counts.get("purchaseInvoiceItemsCreated")));
        }
        if (counts.get("creditNotesCreated") > 0) {
            audit.logCurrent("SMART_IMPORT_CREDIT_NOTES_COMMITTED", "SmartImportCommit", commit.id,
                Map.of("count", counts.get("creditNotesCreated"), "items", counts.get("creditNoteItemsCreated")));
        }
        if (counts.get("debitNotesCreated") > 0) {
            audit.logCurrent("SMART_IMPORT_DEBIT_NOTES_COMMITTED", "SmartImportCommit", commit.id,
                Map.of("count", counts.get("debitNotesCreated"), "items", counts.get("debitNoteItemsCreated")));
        }
        var capturedLines = counts.get("taxLinesCaptured") + counts.get("discountLinesCaptured")
            + counts.get("freightLinesCaptured") + counts.get("roundOffLinesCaptured") + counts.get("otherChargeLinesCaptured");
        if (capturedLines > 0) {
            audit.logCurrent("SMART_IMPORT_LEDGER_ADJUSTMENTS_CAPTURED", "SmartImportCommit", commit.id,
                Map.of("count", capturedLines));
        }
        var duplicateCount = counts.get("duplicateSalesVouchersSkipped") + counts.get("duplicatePurchaseVouchersSkipped");
        if (duplicateCount > 0) {
            audit.logCurrent("SMART_IMPORT_DUPLICATE_VOUCHERS_SKIPPED", "SmartImportCommit", commit.id,
                Map.of("count", duplicateCount));
        }
        var duplicateNotes = counts.get("duplicateCreditNotesSkipped") + counts.get("duplicateDebitNotesSkipped");
        if (duplicateNotes > 0) {
            audit.logCurrent("SMART_IMPORT_DUPLICATE_NOTES_SKIPPED", "SmartImportCommit", commit.id,
                Map.of("count", duplicateNotes));
        }
        if (counts.get("vouchersExcludedByUser") > 0) {
            audit.logCurrent("SMART_IMPORT_VOUCHERS_EXCLUDED", "SmartImportCommit", commit.id,
                Map.of("count", counts.get("vouchersExcludedByUser")));
        }
        if (counts.get("salesStockMovementsCreated") > 0) {
            audit.logCurrent("SMART_IMPORT_SALES_STOCK_MOVEMENTS_COMMITTED", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "count", counts.get("salesStockMovementsCreated")));
        }
        if (counts.get("purchaseStockMovementsCreated") > 0) {
            audit.logCurrent("SMART_IMPORT_PURCHASE_STOCK_MOVEMENTS_COMMITTED", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "count", counts.get("purchaseStockMovementsCreated")));
        }
        if (counts.get("voucherItemsCoveredBySnapshot") > 0) {
            audit.logCurrent("SMART_IMPORT_VOUCHER_STOCK_EFFECT_COVERED_BY_SNAPSHOT", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "count", counts.get("voucherItemsCoveredBySnapshot"),
                    "sameDate", counts.get("sameDateVoucherItemsDeferred")));
        }
        if (counts.get("duplicateStockMovementsSkipped") > 0) {
            audit.logCurrent("SMART_IMPORT_DUPLICATE_STOCK_MOVEMENT_SKIPPED", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "count", counts.get("duplicateStockMovementsSkipped")));
        }
        if (counts.get("returnInMovementsCreated") > 0) {
            audit.logCurrent("SMART_IMPORT_RETURN_IN_MOVEMENTS_COMMITTED", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "count", counts.get("returnInMovementsCreated")));
        }
        if (counts.get("returnOutMovementsCreated") > 0) {
            audit.logCurrent("SMART_IMPORT_RETURN_OUT_MOVEMENTS_COMMITTED", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "count", counts.get("returnOutMovementsCreated")));
        }
        if (counts.get("physicalSalesReturnsCommitted") > 0) {
            audit.logCurrent("SMART_IMPORT_PHYSICAL_SALES_RETURNS_COMMITTED", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "count", counts.get("physicalSalesReturnsCommitted")));
        }
        if (counts.get("physicalPurchaseReturnsCommitted") > 0) {
            audit.logCurrent("SMART_IMPORT_PHYSICAL_PURCHASE_RETURNS_COMMITTED", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "count", counts.get("physicalPurchaseReturnsCommitted")));
        }
        if (counts.get("financialOnlyNotesCommitted") > 0) {
            audit.logCurrent("SMART_IMPORT_FINANCIAL_ONLY_RETURN_CONFIRMED", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "count", counts.get("financialOnlyNotesCommitted")));
        }
        if (counts.get("returnsCoveredBySnapshot") > 0) {
            audit.logCurrent("SMART_IMPORT_RETURN_COVERED_BY_SNAPSHOT", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "count", counts.get("returnsCoveredBySnapshot"),
                    "sameDate", counts.get("sameDateReturnsDeferred")));
        }
        if (counts.get("duplicateReturnMovementsSkipped") > 0) {
            audit.logCurrent("SMART_IMPORT_DUPLICATE_RETURN_MOVEMENT_SKIPPED", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "count", counts.get("duplicateReturnMovementsSkipped")));
        }
    }

    private void commitTransfers(
        UUID tenantId,
        UUID sessionId,
        SmartImportCommit commit,
        ImportDryRun dryRun,
        DomainEnums.StockJournalImpactMode mode,
        Map<String, ImportDryRunItem> transferDryRunItems,
        CommitCounts counts
    ) {
        audit.logCurrent("SMART_IMPORT_STOCK_JOURNAL_MODE_SELECTED", "SmartImportCommit", commit.id,
            linked("sessionId", sessionId, "mode", mode));
        var processedVouchers = new HashSet<UUID>();
        int processed = 0;
        for (var candidate : stagedTransfers
            .findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(tenantId, sessionId)) {
            flushBatch(processed++);
            if (processedVouchers.add(candidate.stagedVoucherId)) counts.inc("stockJournalsProcessed");
            if (candidate.resolutionStatus == DomainEnums.TransferResolutionStatus.IGNORED) {
                counts.inc("ignoredStockJournals");
                continue;
            }
            if (transferService.manufacturing(candidate)) {
                counts.inc("manufacturingJournalsDeferred");
                continue;
            }
            if (candidate.classification != DomainEnums.StockJournalClassification.WAREHOUSE_TRANSFER) {
                if (candidate.classification == DomainEnums.StockJournalClassification.UNBALANCED_STOCK_JOURNAL
                    || candidate.resolutionStatus == DomainEnums.TransferResolutionStatus.DEFERRED_ADJUSTMENT) {
                    counts.inc("adjustmentJournalsDeferred");
                } else {
                    counts.inc("ambiguousStockJournalsDeferred");
                }
                continue;
            }
            if (mode == DomainEnums.StockJournalImpactMode.DEFER_STOCK_JOURNALS) {
                counts.inc("unresolvedWarehouseTransfers");
                continue;
            }
            if (!transferService.isConfirmed(candidate)) {
                counts.inc("unresolvedWarehouseTransfers");
                throw ApiErrors.badRequest("Warehouse transfer row " + candidate.sourceRowNumber + " requires review");
            }
            var dryRunItem = transferDryRunItems.get(candidate.id.toString());
            if (dryRunItem == null) {
                throw ApiErrors.conflict("The selected dry run does not contain warehouse transfer row "
                    + candidate.sourceRowNumber);
            }
            if (dryRunItem.action == DomainEnums.ImportDryRunAction.SKIP) {
                switch (nullSafe(dryRunItem.warningCode)) {
                    case "DUPLICATE_WAREHOUSE_TRANSFER" -> counts.inc("duplicateWarehouseTransfersSkipped");
                    case "TRANSFER_ALREADY_COVERED_BY_SNAPSHOT" -> {
                        counts.inc("warehouseTransfersCoveredBySnapshot");
                        var sourceDate = date(dryRunItem.previewJson.get("sourceSnapshotDate"));
                        var destinationDate = date(dryRunItem.previewJson.get("destinationSnapshotDate"));
                        if (Objects.equals(sourceDate, candidate.voucherDate)
                            || Objects.equals(destinationDate, candidate.voucherDate)) {
                            counts.inc("sameDateWarehouseTransfersDeferred");
                        }
                    }
                    case "TRANSFER_NEGATIVE_STOCK_SKIPPED" -> {
                        counts.inc("warehouseTransfersSkippedByPolicy");
                        counts.inc("warehouseTransfersBlockedByNegativeStock");
                    }
                    default -> counts.inc("unresolvedWarehouseTransfers");
                }
                continue;
            }
            if (dryRunItem.action != DomainEnums.ImportDryRunAction.CREATE) {
                throw ApiErrors.conflict("The selected dry run does not authorize warehouse transfer row "
                    + candidate.sourceRowNumber);
            }

            var product = products.findByTenantIdAndId(tenantId, candidate.matchedProductId)
                .orElseThrow(() -> ApiErrors.badRequest("Transfer product is not available in this workspace"));
            var source = warehouses.findByTenantIdAndId(tenantId, candidate.sourceWarehouseId)
                .orElseThrow(() -> ApiErrors.badRequest("Transfer source warehouse is not available in this workspace"));
            var destination = warehouses.findByTenantIdAndId(tenantId, candidate.destinationWarehouseId)
                .orElseThrow(() -> ApiErrors.badRequest("Transfer destination warehouse is not available in this workspace"));
            if (source.id.equals(destination.id)) {
                throw ApiErrors.badRequest("Source and destination warehouses must be different");
            }
            var quantity = nvl(candidate.normalizedQuantity).abs();
            if (quantity.signum() <= 0) throw ApiErrors.badRequest("Transfer quantity must be positive");
            var unit = candidate.unitCode == null ? null
                : units.findByTenantIdAndCodeIgnoreCase(tenantId, candidate.unitCode).orElse(null);
            var unitId = unit == null ? product.baseUnitId : unit.id;
            var baseQuantity = stockLedger.toBaseQuantity(tenantId, product, unitId, quantity).abs();

            var sourceEligibility = snapshotEligibility.decideWarehouseSpecific(
                tenantId, sessionId, candidate.voucherDate, product.id,
                candidate.normalizedProductName, candidate.unitCode, source.id, source.name,
                snapshotDates.fromSummary(dryRun.summaryJson).stockDate());
            var destinationEligibility = snapshotEligibility.decideWarehouseSpecific(
                tenantId, sessionId, candidate.voucherDate, product.id,
                candidate.normalizedProductName, candidate.unitCode, destination.id, destination.name,
                snapshotDates.fromSummary(dryRun.summaryJson).stockDate());
            if (sourceEligibility.blocking() || destinationEligibility.blocking()) {
                counts.inc("warehouseTransfersWithoutBaseline");
                throw ApiErrors.badRequest("Both warehouses require a reliable closing-stock baseline before transfer posting");
            }
            if (!sourceEligibility.eligible() || !destinationEligibility.eligible()) {
                counts.inc("warehouseTransfersCoveredBySnapshot");
                if (sourceEligibility.reasonCode() == SmartImportSnapshotEligibilityService.ReasonCode.ON_SNAPSHOT_DATE
                    || destinationEligibility.reasonCode() == SmartImportSnapshotEligibilityService.ReasonCode.ON_SNAPSHOT_DATE) {
                    counts.inc("sameDateWarehouseTransfersDeferred");
                }
                continue;
            }
            if (!Objects.equals(sourceEligibility.snapshotDate(), date(dryRunItem.previewJson.get("sourceSnapshotDate")))
                || !Objects.equals(destinationEligibility.snapshotDate(), date(dryRunItem.previewJson.get("destinationSnapshotDate")))) {
                throw ApiErrors.conflict("Warehouse snapshot baselines changed after the dry run. Run the dry run again.");
            }

            candidate.transferFingerprint = transferService.transferFingerprint(
                tenantId, candidate, product.id, source.id, destination.id, quantity);
            candidate.transferLineFingerprint = transferService.transferLineFingerprint(tenantId, candidate, product.id);
            stockLedger.lockStockKeys(List.of(
                stockLedger.stockKey(tenantId, product.id, source.id),
                stockLedger.stockKey(tenantId, product.id, destination.id)));

            var pairEffects = stockMovements.findByTenantIdAndTransferPairFingerprint(
                tenantId, candidate.transferFingerprint);
            if (completeTransferPair(pairEffects)) {
                counts.inc("duplicateWarehouseTransfersSkipped");
                audit.logCurrent("SMART_IMPORT_DUPLICATE_TRANSFER_SKIPPED", "SmartStagedTransferCandidate",
                    candidate.id, linked("sessionId", sessionId, "fingerprint", candidate.transferFingerprint));
                continue;
            }
            var lineEffects = stockMovements.findByTenantIdAndTransferLineFingerprint(
                tenantId, candidate.transferLineFingerprint);
            if (!pairEffects.isEmpty() || !lineEffects.isEmpty()) {
                throw ApiErrors.conflict("A conflicting or one-sided historical warehouse transfer requires review");
            }

            var sourceCurrent = stockLedger.currentStock(tenantId, product.id, source.id);
            var destinationCurrent = stockLedger.currentStock(tenantId, product.id, destination.id);
            var expectedSource = decimal(dryRunItem.previewJson.get("sourceCurrentStock"));
            var expectedDestination = decimal(dryRunItem.previewJson.get("destinationCurrentStock"));
            if (expectedSource == null || expectedDestination == null
                || expectedSource.compareTo(sourceCurrent) != 0
                || expectedDestination.compareTo(destinationCurrent) != 0) {
                throw ApiErrors.conflict("Warehouse stock changed after the dry run. Run the dry run again.");
            }
            var projectedSource = sourceCurrent.subtract(baseQuantity);
            if (projectedSource.signum() < 0) {
                var policy = negativePolicy(dryRun);
                if (policy == DomainEnums.NegativeStockImportPolicy.SKIP_STOCK_MOVEMENT) {
                    counts.inc("warehouseTransfersSkippedByPolicy");
                    counts.inc("warehouseTransfersBlockedByNegativeStock");
                    continue;
                }
                if (policy == DomainEnums.NegativeStockImportPolicy.BLOCK
                    || !stockLedger.negativeStockAllowed(tenantId)) {
                    counts.inc("warehouseTransfersBlockedByNegativeStock");
                    audit.logCurrent("SMART_IMPORT_TRANSFER_NEGATIVE_STOCK_BLOCKED",
                        "SmartStagedTransferCandidate", candidate.id, linked(
                            "sessionId", sessionId, "productId", product.id,
                            "sourceWarehouseId", source.id, "projectedSourceStock", projectedSource));
                    throw ApiErrors.badRequest("Warehouse transfer would make source stock negative");
                }
            }

            var groupId = UUID.randomUUID();
            var movementDate = requireTransferDate(candidate).atTime(12, 0).toInstant(ZoneOffset.UTC);
            var out = stockLedger.createMovement(
                tenantId, product.id, source.id, DomainEnums.MovementType.TRANSFER_OUT,
                quantity, unitId, ZERO, "SMART_IMPORT_WAREHOUSE_TRANSFER", groupId, movementDate,
                "Smart Import warehouse transfer " + nullSafe(candidate.voucherNumber));
            out.sourceImportSessionId = sessionId;
            out.sourceFileId = candidate.sessionFileId;
            out.sourceRowNumber = candidate.sourceRowNumber;
            out.sourceFingerprint = sha256(candidate.transferFingerprint + "|TRANSFER_OUT");
            out.snapshotBaselineId = sourceEligibility.snapshotId();
            out.decisionReason = "AFTER_BOTH_WAREHOUSE_SNAPSHOTS";
            out.transferGroupId = groupId;
            out.transferPairFingerprint = candidate.transferFingerprint;
            out.transferLineFingerprint = candidate.transferLineFingerprint;
            stockMovements.save(out);

            var in = stockLedger.createMovement(
                tenantId, product.id, destination.id, DomainEnums.MovementType.TRANSFER_IN,
                quantity, unitId, ZERO, "SMART_IMPORT_WAREHOUSE_TRANSFER", groupId, movementDate,
                "Smart Import warehouse transfer " + nullSafe(candidate.voucherNumber));
            in.sourceImportSessionId = sessionId;
            in.sourceFileId = candidate.sessionFileId;
            in.sourceRowNumber = candidate.sourceRowNumber;
            in.sourceFingerprint = sha256(candidate.transferFingerprint + "|TRANSFER_IN");
            in.snapshotBaselineId = destinationEligibility.snapshotId();
            in.decisionReason = "AFTER_BOTH_WAREHOUSE_SNAPSHOTS";
            in.transferGroupId = groupId;
            in.transferPairFingerprint = candidate.transferFingerprint;
            in.transferLineFingerprint = candidate.transferLineFingerprint;
            out.linkedMovementId = in.id;
            in.linkedMovementId = out.id;
            stockMovements.saveAll(List.of(out, in));

            candidate.sourceSnapshotId = sourceEligibility.snapshotId();
            candidate.destinationSnapshotId = destinationEligibility.snapshotId();
            candidate.sourceSnapshotDate = sourceEligibility.snapshotDate();
            candidate.destinationSnapshotDate = destinationEligibility.snapshotDate();
            candidate.resolutionStatus = DomainEnums.TransferResolutionStatus.STOCK_EFFECT_COMMITTED;
            candidate.updatedBy = TenantContext.userId();
            stagedTransfers.save(candidate);

            counts.inc("warehouseTransfersCommitted");
            counts.inc("transferPairsCreated");
            counts.inc("transferOutMovementsCreated");
            counts.inc("transferInMovementsCreated");
            if (candidate.resolvedBy != null) counts.inc("manuallyResolvedWarehouseTransfers");
            effect(commit, candidate.sessionFileId, candidate.sourceRowNumber,
                DomainEnums.SmartImportEffectEntityType.WAREHOUSE_TRANSFER, groupId,
                DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked(
                    "productId", product.id, "sourceWarehouseId", source.id,
                    "destinationWarehouseId", destination.id, "quantity", quantity,
                    "transferOutMovementId", out.id, "transferInMovementId", in.id,
                    "transferGroupId", groupId, "transferFingerprint", candidate.transferFingerprint,
                    "netCompanyStockEffect", ZERO), true);
            if (projectedSource.signum() < 0) {
                audit.logCurrent("SMART_IMPORT_TRANSFER_NEGATIVE_STOCK_ALLOWED",
                    "SmartStagedTransferCandidate", candidate.id, linked(
                        "sessionId", sessionId, "projectedSourceStock", projectedSource));
            }
        }
        if (counts.get("warehouseTransfersCommitted") > 0) {
            audit.logCurrent("SMART_IMPORT_WAREHOUSE_TRANSFERS_COMMITTED", "SmartImportCommit", commit.id,
                linked("sessionId", sessionId, "count", counts.get("warehouseTransfersCommitted"),
                    "netCompanyStockEffect", ZERO));
            audit.logCurrent("SMART_IMPORT_TRANSFER_OUT_MOVEMENTS_COMMITTED", "SmartImportCommit", commit.id,
                Map.of("sessionId", sessionId, "count", counts.get("transferOutMovementsCreated")));
            audit.logCurrent("SMART_IMPORT_TRANSFER_IN_MOVEMENTS_COMMITTED", "SmartImportCommit", commit.id,
                Map.of("sessionId", sessionId, "count", counts.get("transferInMovementsCreated")));
        }
    }

    private boolean completeTransferPair(List<StockMovement> movements) {
        return movements.size() == 2
            && movements.stream().anyMatch(row -> row.movementType == DomainEnums.MovementType.TRANSFER_OUT)
            && movements.stream().anyMatch(row -> row.movementType == DomainEnums.MovementType.TRANSFER_IN)
            && movements.stream().map(row -> row.transferGroupId).filter(Objects::nonNull).distinct().count() == 1;
    }

    private LocalDate requireTransferDate(SmartStagedTransferCandidate candidate) {
        if (candidate.voucherDate == null) {
            throw ApiErrors.badRequest("Stock-journal voucher date is required at source row "
                + candidate.sourceRowNumber);
        }
        return candidate.voucherDate;
    }

    private void commitSalesVoucher(
        UUID tenantId, SmartStagedVoucher voucher, List<SmartStagedVoucherItem> sourceItems,
        SmartImportCommit commit, ImportDryRun dryRun, DomainEnums.VoucherStockImpactMode stockImpactMode,
        Map<String, ImportDryRunItem> stockDryRunItems, CommitCounts counts, Map<UUID, String> fileHashes,
        Map<String, UnitOfMeasure> unitIndex, Map<String, Warehouse> warehouseIndex, Map<String, Product> productIndex
    ) {
        if (sourceRevisions.applyIfReviewed(voucher,commit)) { counts.inc("sourceDocumentsRevised"); return; }
        var customer = resolveVoucherCustomer(tenantId, voucher);
        if (customer == null) throw ApiErrors.badRequest("Customer is unresolved for sales voucher " + nullSafe(voucher.voucherNumber));
        voucher.matchedPartyId = customer.id;
        var existing = existingSalesInvoice(tenantId, voucher, customer.id);
        if (existing != null) {
            if (!"UNCHANGED".equals(sourceRevisions.assess(voucher).outcome())) throw ApiErrors.conflict("Invoice content changed; run a new revision review");
            counts.inc("duplicateSalesVouchersSkipped");
            effect(commit, voucher.sessionFileId, voucher.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.SALES_INVOICE,
                existing.id, DomainEnums.SmartImportEffectAction.SKIPPED, Map.of(), linked("reason", "DUPLICATE_VOUCHER"), false);
            upsertMapping(commit, voucher.sessionFileId, "SALES_VOUCHER", voucher.externalId, voucher.fingerprint,
                "SALES_INVOICE", existing.id, fileHashes.get(voucher.sessionFileId));
            return;
        }
        var warehouse = resolveVoucherWarehouse(tenantId, commit, voucher, sourceItems, counts, warehouseIndex);
        var invoice = new SalesInvoice();
        own(invoice, tenantId);
        invoice.customerId = customer.id;
        invoice.warehouseId = warehouse.id;
        invoice.invoiceNumber = requireVoucherNumber(voucher);
        invoice.invoiceDate = requireVoucherDate(voucher);
        invoice = salesInvoices.save(invoice);

        var subtotal = ZERO;
        for (var sourceItem : sourceItems) {
            var product = resolveVoucherProduct(tenantId, sourceItem, productIndex);
            var unit = ensureUnit(tenantId, commit, sourceItem.sessionFileId, sourceItem.sourceRowNumber,
                sourceItem.unitCode, counts, unitIndex);
            var quantity = nvl(sourceItem.quantity).abs();
            var rate = voucherRate(sourceItem);
            var lineTotal = voucherLineTotal(sourceItem, quantity, rate);
            var item = new SalesInvoiceItem();
            own(item, tenantId);
            item.salesInvoiceId = invoice.id;
            var lineWarehouse = resolveStockMovementWarehouse(tenantId, sourceItem, warehouseIndex);
            item.warehouseId = lineWarehouse == null ? warehouse.id : lineWarehouse.id;
            item.productId = product.id;
            item.quantity = quantity;
            item.unitId = unit.id;
            item.rate = rate;
            item.costRate = nvl(product.defaultPurchasePrice);
            item.taxPercentage = ZERO;
            item.taxAmount = ZERO;
            item.discountAmount = ZERO;
            item.lineTotal = lineTotal;
            item = salesInvoiceItems.save(item);
            subtotal = subtotal.add(lineTotal);
            counts.inc("salesInvoiceItemsCreated");
            countRateMetadata(counts, sourceItem, false);
            effect(commit, sourceItem.sessionFileId, sourceItem.sourceRowNumber,
                DomainEnums.SmartImportEffectEntityType.SALES_INVOICE_ITEM, item.id,
                DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked(
                    "invoiceId", invoice.id, "productId", product.id, "quantity", quantity, "rate", rate), true);
            commitVoucherStockMovement(tenantId, voucher, sourceItem, item.id, product, unit,
                DomainEnums.MovementType.SALE, "SALES_INVOICE_ITEM", commit, dryRun, stockImpactMode,
                stockDryRunItems, counts, warehouseIndex);
        }
        invoice.subtotal = subtotal;
        invoice.taxAmount = nvl(voucher.taxAmount);
        invoice.discountAmount = nvl(voucher.discountAmount);
        invoice.freightAmount = nvl(voucher.freightAmount);
        invoice.roundOffAmount = nvl(voucher.roundOffAmount);
        invoice.otherChargesAmount = nvl(voucher.otherChargesAmount);
        invoice.totalAmount = financialTotal(subtotal, voucher);
        invoice.financialMetadata = financialMetadata(voucher);
        salesInvoices.save(invoice);
        sourceRevisions.recordCreated(voucher,invoice);
        counts.inc("salesInvoicesCreated");
        effect(commit, voucher.sessionFileId, voucher.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.SALES_INVOICE,
            invoice.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked(
                "invoiceNumber", invoice.invoiceNumber, "invoiceDate", invoice.invoiceDate,
                "customerId", customer.id, "totalAmount", invoice.totalAmount,
                "stockImpact", stockImpactMode.name()), true);
        upsertMapping(commit, voucher.sessionFileId, "SALES_VOUCHER", voucher.externalId, voucher.fingerprint,
            "SALES_INVOICE", invoice.id, fileHashes.get(voucher.sessionFileId));
    }

    private void commitPurchaseVoucher(
        UUID tenantId, SmartStagedVoucher voucher, List<SmartStagedVoucherItem> sourceItems,
        SmartImportCommit commit, ImportDryRun dryRun, DomainEnums.VoucherStockImpactMode stockImpactMode,
        Map<String, ImportDryRunItem> stockDryRunItems, CommitCounts counts, Map<UUID, String> fileHashes,
        Map<String, UnitOfMeasure> unitIndex, Map<String, Warehouse> warehouseIndex, Map<String, Product> productIndex
    ) {
        if (sourceRevisions.applyIfReviewed(voucher,commit)) { counts.inc("sourceDocumentsRevised"); return; }
        var supplier = resolveVoucherSupplier(tenantId, voucher);
        if (supplier == null) throw ApiErrors.badRequest("Supplier is unresolved for purchase voucher " + nullSafe(voucher.voucherNumber));
        voucher.matchedPartyId = supplier.id;
        var existing = existingPurchaseInvoice(tenantId, voucher, supplier.id);
        if (existing != null) {
            if (!"UNCHANGED".equals(sourceRevisions.assess(voucher).outcome())) throw ApiErrors.conflict("Invoice content changed; run a new revision review");
            counts.inc("duplicatePurchaseVouchersSkipped");
            effect(commit, voucher.sessionFileId, voucher.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.PURCHASE_INVOICE,
                existing.id, DomainEnums.SmartImportEffectAction.SKIPPED, Map.of(), linked("reason", "DUPLICATE_VOUCHER"), false);
            upsertMapping(commit, voucher.sessionFileId, "PURCHASE_VOUCHER", voucher.externalId, voucher.fingerprint,
                "PURCHASE_INVOICE", existing.id, fileHashes.get(voucher.sessionFileId));
            return;
        }
        var warehouse = resolveVoucherWarehouse(tenantId, commit, voucher, sourceItems, counts, warehouseIndex);
        var invoice = new PurchaseInvoice();
        own(invoice, tenantId);
        invoice.supplierId = supplier.id;
        invoice.warehouseId = warehouse.id;
        invoice.invoiceNumber = requireVoucherNumber(voucher);
        invoice.invoiceDate = requireVoucherDate(voucher);
        invoice = purchaseInvoices.save(invoice);

        var subtotal = ZERO;
        for (var sourceItem : sourceItems) {
            var product = resolveVoucherProduct(tenantId, sourceItem, productIndex);
            var unit = ensureUnit(tenantId, commit, sourceItem.sessionFileId, sourceItem.sourceRowNumber,
                sourceItem.unitCode, counts, unitIndex);
            var quantity = nvl(sourceItem.quantity).abs();
            var rate = voucherRate(sourceItem);
            var lineTotal = voucherLineTotal(sourceItem, quantity, rate);
            var item = new PurchaseInvoiceItem();
            own(item, tenantId);
            item.purchaseInvoiceId = invoice.id;
            item.productId = product.id;
            item.quantity = quantity;
            item.unitId = unit.id;
            item.rate = rate;
            item.taxPercentage = ZERO;
            item.taxAmount = ZERO;
            item.lineTotal = lineTotal;
            item = purchaseInvoiceItems.save(item);
            subtotal = subtotal.add(lineTotal);
            counts.inc("purchaseInvoiceItemsCreated");
            countRateMetadata(counts, sourceItem, true);
            effect(commit, sourceItem.sessionFileId, sourceItem.sourceRowNumber,
                DomainEnums.SmartImportEffectEntityType.PURCHASE_INVOICE_ITEM, item.id,
                DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked(
                    "invoiceId", invoice.id, "productId", product.id, "quantity", quantity, "rate", rate), true);
            commitVoucherStockMovement(tenantId, voucher, sourceItem, item.id, product, unit,
                DomainEnums.MovementType.PURCHASE, "PURCHASE_INVOICE_ITEM", commit, dryRun, stockImpactMode,
                stockDryRunItems, counts, warehouseIndex);
        }
        invoice.subtotal = subtotal;
        invoice.taxAmount = nvl(voucher.taxAmount);
        invoice.discountAmount = nvl(voucher.discountAmount);
        invoice.freightAmount = nvl(voucher.freightAmount);
        invoice.roundOffAmount = nvl(voucher.roundOffAmount);
        invoice.otherChargesAmount = nvl(voucher.otherChargesAmount);
        invoice.totalAmount = financialTotal(subtotal, voucher);
        invoice.financialMetadata = financialMetadata(voucher);
        purchaseInvoices.save(invoice);
        sourceRevisions.recordCreated(voucher,invoice);
        counts.inc("purchaseInvoicesCreated");
        effect(commit, voucher.sessionFileId, voucher.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.PURCHASE_INVOICE,
            invoice.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked(
                "invoiceNumber", invoice.invoiceNumber, "invoiceDate", invoice.invoiceDate,
                "supplierId", supplier.id, "totalAmount", invoice.totalAmount,
                "stockImpact", stockImpactMode.name()), true);
        upsertMapping(commit, voucher.sessionFileId, "PURCHASE_VOUCHER", voucher.externalId, voucher.fingerprint,
            "PURCHASE_INVOICE", invoice.id, fileHashes.get(voucher.sessionFileId));
    }

    private void commitVoucherStockMovement(
        UUID tenantId,
        SmartStagedVoucher voucher,
        SmartStagedVoucherItem sourceItem,
        UUID invoiceItemId,
        Product product,
        UnitOfMeasure unit,
        DomainEnums.MovementType movementType,
        String referenceType,
        SmartImportCommit commit,
        ImportDryRun dryRun,
        DomainEnums.VoucherStockImpactMode stockImpactMode,
        Map<String, ImportDryRunItem> stockDryRunItems,
        CommitCounts counts,
        Map<String, Warehouse> warehouseIndex
    ) {
        if (stockImpactMode == DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY) {
            counts.inc("stockMovementsSkippedDueToInvoiceOnly");
            counts.inc("invoicesOnlyCommitted");
            skippedMovementEffect(commit, sourceItem, invoiceItemId, "INVOICE_ONLY", null);
            return;
        }

        var warehouse = resolveStockMovementWarehouse(tenantId, sourceItem, warehouseIndex);
        var returnMovement = movementType == DomainEnums.MovementType.RETURN_IN
            || movementType == DomainEnums.MovementType.RETURN_OUT;
        if (warehouse == null) {
            counts.inc("warehouseUnresolved");
            counts.inc("voucherItemsDeferred");
            if (returnMovement) counts.inc("warehouseBlockedReturns");
            throw ApiErrors.badRequest("Warehouse is unresolved for stock-affecting voucher item at source row "
                + sourceItem.sourceRowNumber);
        }
        if (sourceItem.matchedWarehouseId == null) {
            counts.inc("warehouseAutoCreatedOrMapped");
        } else {
            counts.inc("warehouseMatchedForStock");
        }

        var decision = snapshotEligibility.decide(
            tenantId, commit.importSessionId, voucher.voucherDate, product.id,
            firstNonBlank(sourceItem.normalizedProductName, sourceItem.productName), sourceItem.unitCode,
            warehouse.id, warehouse.name, snapshotDates.fromSummary(dryRun.summaryJson).stockDate());
        if (decision.blocking()) {
            counts.inc("voucherItemsWithoutSnapshot");
            if (returnMovement) counts.inc("returnsWithoutSnapshot");
            throw ApiErrors.badRequest(decision.explanation() + " Source row " + sourceItem.sourceRowNumber);
        }
        if (!decision.eligible()) {
            counts.inc("voucherItemsCoveredBySnapshot");
            counts.inc("voucherItemsDeferred");
            if (returnMovement) counts.inc("returnsCoveredBySnapshot");
            if (decision.reasonCode() == SmartImportSnapshotEligibilityService.ReasonCode.ON_SNAPSHOT_DATE) {
                counts.inc("sameDateVoucherItemsDeferred");
                if (returnMovement) counts.inc("sameDateReturnsDeferred");
            }
            skippedMovementEffect(commit, sourceItem, invoiceItemId, decision.reasonCode().name(), decision.snapshotId());
            return;
        }
        if (decision.productLevelFallback()) {
            counts.inc("productLevelSnapshotFallbacks");
        }

        var quantity = nvl(sourceItem.quantity).abs();
        if (quantity.signum() == 0) {
            counts.inc("movementsSkippedByPolicy");
            skippedMovementEffect(commit, sourceItem, invoiceItemId, "ZERO_QUANTITY", decision.snapshotId());
            return;
        }
        var fingerprint = stockEffectFingerprint(
            tenantId, voucher, sourceItem, product.id, warehouse.id, movementType, quantity);
        stockLedger.lockStockKey(tenantId, product.id, warehouse.id);
        var duplicate = stockMovements.findByTenantIdAndSourceFingerprint(tenantId, fingerprint).orElse(null);
        if (duplicate != null) {
            counts.inc("duplicateStockMovementsSkipped");
            if (returnMovement) counts.inc("duplicateReturnMovementsSkipped");
            effect(commit, sourceItem.sessionFileId, sourceItem.sourceRowNumber,
                DomainEnums.SmartImportEffectEntityType.STOCK_MOVEMENT, duplicate.id,
                DomainEnums.SmartImportEffectAction.SKIPPED, Map.of(), linked(
                    "reason", "DUPLICATE_STOCK_EFFECT", "sourceFingerprint", fingerprint), false);
            return;
        }

        var current = stockLedger.currentStock(tenantId, product.id, warehouse.id);
        var dryRunItem = stockDryRunItems.get(sourceItem.id.toString());
        if (dryRunItem != null
            && dryRunItem.action == DomainEnums.ImportDryRunAction.SKIP
            && "NEGATIVE_STOCK_SKIPPED".equals(dryRunItem.warningCode)) {
            counts.inc("negativeStockRowsFound");
            counts.inc("negativeStockMovementsSkipped");
            if (returnMovement) counts.inc("negativeStockReturnMovementsSkipped");
            counts.inc("movementsSkippedByPolicy");
            skippedMovementEffect(commit, sourceItem, invoiceItemId, "NEGATIVE_STOCK_SKIPPED", decision.snapshotId());
            audit.logCurrent("SMART_IMPORT_STOCK_MOVEMENT_SKIPPED", "SmartImportCommit", commit.id, linked(
                "sessionId", commit.importSessionId, "sourceRow", sourceItem.sourceRowNumber,
                "reason", "NEGATIVE_STOCK_SKIPPED"));
            return;
        }
        if (dryRunItem == null || dryRunItem.action != DomainEnums.ImportDryRunAction.CREATE) {
            throw ApiErrors.conflict("The selected dry run does not authorize stock movement for source row "
                + sourceItem.sourceRowNumber);
        }
        var expectedCurrent = decimal(dryRunItem.previewJson.get("currentStock"));
        if (expectedCurrent == null || expectedCurrent.compareTo(current) != 0) {
            throw ApiErrors.conflict("Stock changed after the dry run for " + product.name
                + " in " + warehouse.name + ". Run the dry run again.");
        }

        var baseQuantity = stockLedger.toBaseQuantity(tenantId, product, unit.id, quantity);
        var signedQuantity = StockLedgerService.signedQuantity(movementType, baseQuantity);
        var projected = current.add(signedQuantity);
        var policy = negativePolicy(dryRun);
        if (projected.signum() < 0) {
            counts.inc("negativeStockRowsFound");
            if (policy == DomainEnums.NegativeStockImportPolicy.SKIP_STOCK_MOVEMENT) {
                counts.inc("negativeStockMovementsSkipped");
                if (returnMovement) counts.inc("negativeStockReturnMovementsSkipped");
                counts.inc("movementsSkippedByPolicy");
                skippedMovementEffect(commit, sourceItem, invoiceItemId, "NEGATIVE_STOCK_SKIPPED", decision.snapshotId());
                audit.logCurrent("SMART_IMPORT_STOCK_MOVEMENT_SKIPPED", "SmartImportCommit", commit.id, linked(
                    "sessionId", commit.importSessionId, "sourceRow", sourceItem.sourceRowNumber,
                    "reason", "NEGATIVE_STOCK_SKIPPED"));
                return;
            }
            if (policy == DomainEnums.NegativeStockImportPolicy.BLOCK
                || !stockLedger.negativeStockAllowed(tenantId)) {
                counts.inc("negativeStockMovementsBlocked");
                if (returnMovement) counts.inc("negativeStockReturnMovementsBlocked");
                audit.logCurrent("SMART_IMPORT_NEGATIVE_STOCK_BLOCKED", "SmartImportCommit", commit.id, linked(
                    "sessionId", commit.importSessionId, "sourceRow", sourceItem.sourceRowNumber,
                    "productId", product.id, "warehouseId", warehouse.id));
                if (returnMovement) {
                    audit.logCurrent("SMART_IMPORT_RETURN_NEGATIVE_STOCK_BLOCKED", "SmartImportCommit", commit.id,
                        linked("sessionId", commit.importSessionId, "sourceRow", sourceItem.sourceRowNumber,
                            "productId", product.id, "warehouseId", warehouse.id));
                }
                throw ApiErrors.badRequest(movementType + " would make stock negative for " + product.name);
            }
            counts.inc("negativeStockMovementsImported");
        }

        var movement = stockLedger.createMovement(
            tenantId, product.id, warehouse.id, movementType, quantity, unit.id, voucherRate(sourceItem),
            referenceType, invoiceItemId, requireVoucherDate(voucher).atTime(12, 0).toInstant(ZoneOffset.UTC),
            "Smart Import post-snapshot " + movementType.name().toLowerCase(Locale.ROOT)
                + " for voucher " + requireVoucherNumber(voucher));
        movement.sourceImportSessionId = commit.importSessionId;
        movement.sourceFileId = sourceItem.sessionFileId;
        movement.sourceRowNumber = sourceItem.sourceRowNumber;
        movement.sourceFingerprint = fingerprint;
        movement.snapshotBaselineId = decision.snapshotId();
        movement.decisionReason = decision.reasonCode().name();
        stockMovements.save(movement);
        counts.inc("voucherStockMovementsCreated");
        switch (movementType) {
            case SALE -> counts.inc("salesStockMovementsCreated");
            case PURCHASE -> counts.inc("purchaseStockMovementsCreated");
            case RETURN_IN -> counts.inc("physicalSalesReturnsCommitted");
            case RETURN_OUT -> counts.inc("physicalPurchaseReturnsCommitted");
            default -> { }
        }
        effect(commit, sourceItem.sessionFileId, sourceItem.sourceRowNumber,
            DomainEnums.SmartImportEffectEntityType.STOCK_MOVEMENT, movement.id,
            DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked(
                "movementType", movementType, "productId", product.id, "warehouseId", warehouse.id,
                "quantity", quantity, "baseQuantity", movement.baseQuantity,
                "referenceType", referenceType, "referenceId", invoiceItemId,
                "snapshotBaselineId", decision.snapshotId(), "snapshotDate", decision.snapshotDate(),
                "eligibilityReason", decision.reasonCode(), "sourceFingerprint", fingerprint,
                "importSessionId", commit.importSessionId), true);
        upsertMapping(commit, sourceItem.sessionFileId, "STOCK_EFFECT", null, fingerprint,
            "STOCK_MOVEMENT", movement.id, null);
        if (projected.signum() < 0) {
            audit.logCurrent("SMART_IMPORT_NEGATIVE_STOCK_ALLOWED", "StockMovement", movement.id, linked(
                "sessionId", commit.importSessionId, "projectedStock", projected));
            if (returnMovement) {
                audit.logCurrent("SMART_IMPORT_RETURN_NEGATIVE_STOCK_ALLOWED", "StockMovement", movement.id, linked(
                    "sessionId", commit.importSessionId, "projectedStock", projected));
            }
        }
    }

    private Warehouse resolveStockMovementWarehouse(
        UUID tenantId, SmartStagedVoucherItem sourceItem, Map<String, Warehouse> warehouseIndex
    ) {
        if (sourceItem.matchedWarehouseId != null) {
            var matched = warehouses.findByTenantIdAndId(tenantId, sourceItem.matchedWarehouseId).orElse(null);
            if (matched != null) return matched;
        }
        if (sourceItem.warehouseName == null || sourceItem.warehouseName.isBlank()) return null;
        var indexed = warehouseIndex.get(normalizeName(sourceItem.warehouseName));
        if (indexed != null) return indexed;
        return warehouses.findByTenantIdAndNameIgnoreCase(tenantId, sourceItem.warehouseName).orElse(null);
    }

    private void skippedMovementEffect(
        SmartImportCommit commit,
        SmartStagedVoucherItem sourceItem,
        UUID invoiceItemId,
        String reason,
        UUID snapshotId
    ) {
        effect(commit, sourceItem.sessionFileId, sourceItem.sourceRowNumber,
            DomainEnums.SmartImportEffectEntityType.STOCK_MOVEMENT, invoiceItemId,
            DomainEnums.SmartImportEffectAction.SKIPPED, Map.of(), linked(
                "reason", reason, "snapshotBaselineId", snapshotId,
                "importSessionId", commit.importSessionId), false);
    }

    private void commitFinancialNote(
        UUID tenantId, SmartStagedVoucher voucher, List<SmartStagedVoucherItem> sourceItems,
        DomainEnums.FinancialAdjustmentType adjustmentType, SmartImportCommit commit, ImportDryRun dryRun,
        DomainEnums.ReturnStockImpactMode returnStockImpactMode,
        Map<String, ImportDryRunItem> stockDryRunItems, CommitCounts counts,
        Map<UUID, String> fileHashes, Map<String, UnitOfMeasure> unitIndex,
        Map<String, Warehouse> warehouseIndex, Map<String, Product> productIndex
    ) {
        var customer = adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE
            ? resolveVoucherCustomer(tenantId, voucher) : null;
        var supplier = adjustmentType == DomainEnums.FinancialAdjustmentType.DEBIT_NOTE
            ? resolveVoucherSupplier(tenantId, voucher) : null;
        if (adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE && customer == null) {
            throw ApiErrors.badRequest("Customer is unresolved for credit note " + nullSafe(voucher.voucherNumber));
        }
        if (adjustmentType == DomainEnums.FinancialAdjustmentType.DEBIT_NOTE && supplier == null) {
            throw ApiErrors.badRequest("Supplier is unresolved for debit note " + nullSafe(voucher.voucherNumber));
        }
        var partyId = customer == null ? supplier.id : customer.id;
        voucher.matchedPartyId = partyId;
        var existing = existingFinancialAdjustment(tenantId, voucher, adjustmentType);
        var documentEntityType = adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE
            ? DomainEnums.SmartImportEffectEntityType.CREDIT_NOTE : DomainEnums.SmartImportEffectEntityType.DEBIT_NOTE;
        var itemEntityType = adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE
            ? DomainEnums.SmartImportEffectEntityType.CREDIT_NOTE_ITEM : DomainEnums.SmartImportEffectEntityType.DEBIT_NOTE_ITEM;
        var mappingType = adjustmentType.name();
        var createdAdjustment = existing == null;
        if (!createdAdjustment) {
            counts.inc(adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE
                ? "duplicateCreditNotesSkipped" : "duplicateDebitNotesSkipped");
            effect(commit, voucher.sessionFileId, voucher.sourceRowNumber, documentEntityType, existing.id,
                DomainEnums.SmartImportEffectAction.SKIPPED, Map.of(), linked("reason", "DUPLICATE_NOTE"), false);
            upsertMapping(commit, voucher.sessionFileId, mappingType, voucher.externalId, voucher.fingerprint,
                mappingType, existing.id, fileHashes.get(voucher.sessionFileId));
        }

        var adjustment = createdAdjustment ? new FinancialAdjustment() : existing;
        if (createdAdjustment) {
            own(adjustment, tenantId);
            adjustment.adjustmentType = adjustmentType;
            adjustment.customerId = customer == null ? null : customer.id;
            adjustment.supplierId = supplier == null ? null : supplier.id;
            adjustment.voucherNumber = requireVoucherNumber(voucher);
            adjustment.voucherDate = requireVoucherDate(voucher);
            adjustment.taxAmount = nvl(voucher.taxAmount);
            adjustment.discountAmount = nvl(voucher.discountAmount);
            adjustment.freightAmount = nvl(voucher.freightAmount);
            adjustment.roundOffAmount = nvl(voucher.roundOffAmount);
            adjustment.otherChargesAmount = nvl(voucher.otherChargesAmount);
            adjustment.sourceExternalId = blankToNull(voucher.externalId);
            adjustment.sourceFingerprint = blankToNull(voucher.fingerprint);
            adjustment.rawMetadata = new LinkedHashMap<>(voucher.rawMetadataJson == null ? Map.of() : voucher.rawMetadataJson);
            adjustment.rawMetadata.putAll(financialMetadata(voucher));
        }
        adjustment.returnClassification = voucher.returnClassification;
        adjustment.returnResolutionStatus = voucher.returnResolutionStatus;
        adjustment = financialAdjustments.save(adjustment);

        var resolvedFinancialAmount = resolvedFinancialAdjustmentAmount(voucher);
        var subtotal = resolvedFinancialAmount;
        if (resolvedFinancialAmount.signum() > 0) {
            counts.add("financialAdjustmentLinesResolved", resolvedFinancialAdjustmentLineCount(voucher));
        }
        var existingItems = createdAdjustment ? new ArrayList<FinancialAdjustmentItem>()
            : new ArrayList<>(financialAdjustmentItems.findByTenantIdAndFinancialAdjustmentId(tenantId, adjustment.id));
        for (var sourceItem : sourceItems) {
            var product = resolveVoucherProduct(tenantId, sourceItem, productIndex);
            var unit = ensureUnit(tenantId, commit, sourceItem.sessionFileId, sourceItem.sourceRowNumber,
                sourceItem.unitCode, counts, unitIndex);
            var quantity = nvl(sourceItem.quantity).abs();
            var rate = voucherRate(sourceItem);
            var lineTotal = voucherLineTotal(sourceItem, quantity, rate);
            var item = createdAdjustment ? null : existingItems.stream()
                .filter(candidate -> Objects.equals(candidate.productId, product.id)
                    && nvl(candidate.quantity).compareTo(quantity) == 0)
                .findFirst().orElse(null);
            if (item == null && !createdAdjustment) {
                throw ApiErrors.conflict("Existing financial note items do not match the reviewed return lines");
            }
            if (item == null) {
                item = new FinancialAdjustmentItem();
                own(item, tenantId);
                item.financialAdjustmentId = adjustment.id;
                item.productId = product.id;
                item.unitId = unit.id;
                item.warehouseId = resolveOptionalWarehouse(tenantId, sourceItem, warehouseIndex);
                item.warehouseName = blankToNull(sourceItem.warehouseName);
                item.quantity = quantity;
                item.rate = rate;
                item.lineTotal = lineTotal;
                item.rawMetadata = new LinkedHashMap<>(sourceItem.rawMetadataJson == null ? Map.of() : sourceItem.rawMetadataJson);
                item = financialAdjustmentItems.save(item);
                var itemCountKey = adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE
                    ? "creditNoteItemsCreated" : "debitNoteItemsCreated";
                counts.inc(itemCountKey);
                countRateMetadata(counts, sourceItem, adjustmentType == DomainEnums.FinancialAdjustmentType.DEBIT_NOTE);
                effect(commit, sourceItem.sessionFileId, sourceItem.sourceRowNumber, itemEntityType, item.id,
                    DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked(
                        "adjustmentId", adjustment.id, "productId", product.id, "quantity", quantity, "rate", rate), true);
            } else {
                existingItems.remove(item);
            }
            subtotal = subtotal.add(lineTotal);
            item.returnDirection = sourceItem.returnDirection;
            item.returnDisposition = sourceItem.returnDisposition;
            var physical = sourceItem.returnDisposition == DomainEnums.ReturnDisposition.RESTOCK_TO_AVAILABLE
                && (sourceItem.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.AUTO_CONFIRMED_PHYSICAL
                    || sourceItem.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.USER_CONFIRMED_PHYSICAL);
            if (sourceItem.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.USER_CONFIRMED_PHYSICAL
                || sourceItem.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.USER_CONFIRMED_FINANCIAL_ONLY) {
                counts.inc("returnItemsResolvedManually");
            }
            if (returnStockImpactMode == DomainEnums.ReturnStockImpactMode.APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE
                && physical) {
                var movementType = adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE
                    ? DomainEnums.MovementType.RETURN_IN : DomainEnums.MovementType.RETURN_OUT;
                var warehouseId = resolveOptionalWarehouse(tenantId, sourceItem, warehouseIndex);
                var fingerprint = warehouseId == null ? null : stockEffectFingerprint(
                    tenantId, voucher, sourceItem, product.id, warehouseId, movementType, quantity);
                var existingMovement = fingerprint == null ? null
                    : stockMovements.findByTenantIdAndSourceFingerprint(tenantId, fingerprint).orElse(null);
                commitVoucherStockMovement(tenantId, voucher, sourceItem, item.id, product, unit, movementType,
                    adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE
                        ? "CREDIT_NOTE_ITEM" : "DEBIT_NOTE_ITEM",
                    commit, dryRun, DomainEnums.VoucherStockImpactMode.APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE,
                    stockDryRunItems, counts, warehouseIndex);
                var movement = fingerprint == null ? null
                    : stockMovements.findByTenantIdAndSourceFingerprint(tenantId, fingerprint).orElse(null);
                if (movement != null) {
                    item.stockMovementId = movement.id;
                    item.stockEffectFingerprint = fingerprint;
                    sourceItem.returnResolutionStatus = DomainEnums.ReturnResolutionStatus.STOCK_EFFECT_COMMITTED;
                    stagedVoucherItems.save(sourceItem);
                    if (existingMovement == null) {
                        counts.inc("stockMovementsCreatedFromReturns");
                        counts.inc(adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE
                            ? "returnInMovementsCreated" : "returnOutMovementsCreated");
                    }
                } else {
                    counts.inc("returnStockMovementsDeferred");
                }
            } else {
                counts.inc("returnStockMovementsDeferred");
            }
            financialAdjustmentItems.save(item);
        }
        if (createdAdjustment) {
            adjustment.subtotal = subtotal;
            adjustment.totalAmount = resolvedFinancialAmount.signum() > 0
                ? subtotal : financialTotal(subtotal, voucher);
        }
        adjustment.returnResolutionStatus = sourceItems.stream().anyMatch(item ->
            item.returnResolutionStatus == DomainEnums.ReturnResolutionStatus.STOCK_EFFECT_COMMITTED)
            ? DomainEnums.ReturnResolutionStatus.STOCK_EFFECT_COMMITTED : voucher.returnResolutionStatus;
        financialAdjustments.save(adjustment);
        if (createdAdjustment) {
            counts.inc(adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE
                ? "creditNotesCommitted" : "debitNotesCommitted");
            switch (voucher.returnClassification) {
                case FINANCIAL_ONLY -> counts.inc("financialOnlyNotesCommitted");
                case MIXED_SALES_RETURN, MIXED_PURCHASE_RETURN -> counts.inc("mixedNotesCommitted");
                case AMBIGUOUS_RETURN -> counts.inc("ambiguousReturnsDeferred");
                default -> { }
            }
        }
        if (createdAdjustment) {
            counts.inc(adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE
                ? "creditNotesCreated" : "debitNotesCreated");
            effect(commit, voucher.sessionFileId, voucher.sourceRowNumber, documentEntityType, adjustment.id,
                DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked(
                    "voucherNumber", adjustment.voucherNumber, "voucherDate", adjustment.voucherDate,
                    "partyId", partyId, "totalAmount", adjustment.totalAmount,
                    "stockImpact", returnStockImpactMode.name()), true);
            upsertMapping(commit, voucher.sessionFileId, mappingType, voucher.externalId, voucher.fingerprint,
                mappingType, adjustment.id, fileHashes.get(voucher.sessionFileId));
        }
    }

    private FinancialAdjustment existingFinancialAdjustment(
        UUID tenantId, SmartStagedVoucher voucher, DomainEnums.FinancialAdjustmentType adjustmentType
    ) {
        var mappedId = mappedInvoiceId(tenantId, adjustmentType.name(), voucher);
        var mapped = mappedId == null ? null : financialAdjustments.findByTenantIdAndId(tenantId, mappedId).orElse(null);
        if (mapped == null && voucher.fingerprint != null) {
            mapped = financialAdjustments.findByTenantIdAndAdjustmentTypeAndSourceFingerprint(
                tenantId, adjustmentType, voucher.fingerprint).orElse(null);
        }
        if (mapped != null) {
            requireSameVoucher(mapped.voucherDate,
                adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE ? mapped.customerId : mapped.supplierId,
                mapped.totalAmount, voucher);
            return mapped;
        }
        var numbered = voucher.voucherNumber == null ? null
            : financialAdjustments.findByTenantIdAndAdjustmentTypeAndVoucherNumberIgnoreCase(
                tenantId, adjustmentType, voucher.voucherNumber).orElse(null);
        if (numbered != null) {
            requireSameVoucher(numbered.voucherDate,
                adjustmentType == DomainEnums.FinancialAdjustmentType.CREDIT_NOTE ? numbered.customerId : numbered.supplierId,
                numbered.totalAmount, voucher);
        }
        return numbered;
    }

    private UUID resolveOptionalWarehouse(
        UUID tenantId, SmartStagedVoucherItem item, Map<String, Warehouse> warehouseIndex
    ) {
        if (item.matchedWarehouseId != null
            && warehouses.findByTenantIdAndId(tenantId, item.matchedWarehouseId).isPresent()) return item.matchedWarehouseId;
        var warehouse = warehouseIndex.get(normalizeName(item.warehouseName));
        return warehouse == null ? null : warehouse.id;
    }

    private SalesInvoice existingSalesInvoice(UUID tenantId, SmartStagedVoucher voucher, UUID customerId) {
        var mappedId = mappedInvoiceId(tenantId, "SALES_VOUCHER", voucher);
        var mapped = mappedId == null ? null : salesInvoices.findByTenantIdAndId(tenantId, mappedId).orElse(null);
        if (mapped != null) {
            requireSameVoucher(mapped.invoiceDate, mapped.customerId, mapped.totalAmount, voucher);
            return mapped;
        }
        var numbered = voucher.voucherNumber == null ? null
            : invoiceIdentity.matchSale(tenantId, voucher.voucherNumber, voucher.voucherDate).orElse(null);
        if (numbered != null) requireSameVoucher(numbered.invoiceDate, numbered.customerId, numbered.totalAmount, voucher);
        return numbered;
    }

    private PurchaseInvoice existingPurchaseInvoice(UUID tenantId, SmartStagedVoucher voucher, UUID supplierId) {
        var mappedId = mappedInvoiceId(tenantId, "PURCHASE_VOUCHER", voucher);
        var mapped = mappedId == null ? null : purchaseInvoices.findByTenantIdAndId(tenantId, mappedId).orElse(null);
        if (mapped != null) {
            requireSameVoucher(mapped.invoiceDate, mapped.supplierId, mapped.totalAmount, voucher);
            return mapped;
        }
        var numbered = voucher.voucherNumber == null ? null
            : invoiceIdentity.matchPurchase(tenantId, supplierId, voucher.voucherNumber, voucher.voucherDate).orElse(null);
        if (numbered != null) requireSameVoucher(numbered.invoiceDate, numbered.supplierId, numbered.totalAmount, voucher);
        return numbered;
    }

    private UUID mappedInvoiceId(UUID tenantId, String entityType, SmartStagedVoucher voucher) {
        ExternalRecordMapping mapping = null;
        if (voucher.externalId != null && !voucher.externalId.isBlank()) {
            mapping = mappings.findFirstByTenantIdAndSourceSystemAndEntityTypeAndExternalId(
                tenantId, SOURCE_SYSTEM, entityType, voucher.externalId).orElse(null);
        }
        if (mapping == null && voucher.fingerprint != null && !voucher.fingerprint.isBlank()) {
            mapping = mappings.findByTenantIdAndSourceSystemAndEntityTypeAndNormalizedKey(
                tenantId, SOURCE_SYSTEM, entityType, voucher.fingerprint).orElse(null);
        }
        return mapping == null ? null : mapping.localEntityId;
    }

    private void requireSameVoucher(LocalDate date, UUID partyId, BigDecimal total, SmartStagedVoucher voucher) {
        if (!Objects.equals(date, voucher.voucherDate) || partyId == null || voucher.matchedPartyId == null
            || !Objects.equals(partyId, voucher.matchedPartyId)
            || nvl(total).compareTo(nvl(voucher.totalAmount)) != 0) {
            throw ApiErrors.conflict("Voucher number or external identity already exists with different date, party, or amount: "
                + nullSafe(voucher.voucherNumber));
        }
    }

    private Customer resolveVoucherCustomer(UUID tenantId, SmartStagedVoucher voucher) {
        if (voucher.matchedPartyId != null) {
            var matched = customers.findByTenantIdAndId(tenantId, voucher.matchedPartyId).orElse(null);
            if (matched != null) return matched;
        }
        return customerBySourceName(tenantId, firstNonBlank(voucher.normalizedPartyName, voucher.partyName));
    }

    private Customer customerBySourceName(UUID tenantId, String name) {
        var normalized = normalizeName(name);
        var mappedId = mappedPartyId(tenantId, "CUSTOMER", normalized);
        if (mappedId != null) {
            return customers.findByTenantIdAndId(tenantId, mappedId)
                .orElseThrow(() -> ApiErrors.conflict("Mapped customer is no longer available"));
        }
        var exact = customers.findByTenantIdAndNameIgnoreCase(tenantId, normalized).orElse(null);
        if (exact != null) return exact;
        return customers.findByTenantIdOrderByNameAsc(tenantId).stream()
            .filter(customer -> normalizeName(customer.name).equals(normalized)).findFirst().orElse(null);
    }

    private Supplier resolveVoucherSupplier(UUID tenantId, SmartStagedVoucher voucher) {
        if (voucher.matchedPartyId != null) {
            var matched = suppliers.findByTenantIdAndId(tenantId, voucher.matchedPartyId).orElse(null);
            if (matched != null) return matched;
        }
        return supplierBySourceName(tenantId, firstNonBlank(voucher.normalizedPartyName, voucher.partyName));
    }

    private Supplier supplierBySourceName(UUID tenantId, String name) {
        var normalized = normalizeName(name);
        var mappedId = mappedPartyId(tenantId, "SUPPLIER", normalized);
        if (mappedId != null) {
            return suppliers.findByTenantIdAndId(tenantId, mappedId)
                .orElseThrow(() -> ApiErrors.conflict("Mapped supplier is no longer available"));
        }
        var exact = suppliers.findByTenantIdAndNameIgnoreCase(tenantId, normalized).orElse(null);
        if (exact != null) return exact;
        return suppliers.findByTenantIdOrderByNameAsc(tenantId).stream()
            .filter(supplier -> normalizeName(supplier.name).equals(normalized)).findFirst().orElse(null);
    }

    private UUID mappedPartyId(UUID tenantId, String partyType, String normalizedName) {
        // Master aliases can resolve to one GSTIN even when their display names differ.
        return mappings.findByTenantIdAndSourceSystemAndEntityTypeAndNormalizedKey(
                tenantId, SOURCE_SYSTEM, partyType, partyType + "|" + normalizedName)
            .filter(mapping -> partyType.equals(mapping.localEntityType))
            .map(mapping -> mapping.localEntityId).orElse(null);
    }

    private void flushBatch(int processed) {
        if (processed % WRITE_BATCH_SIZE == 0) flushAndClear();
    }

    private void flushAndClear() {
        // Includes the first row to detach bulk-loaded staging JSON. Flush is NOT a commit:
        // all batches remain in the same transaction and roll back together on failure.
        entityManager.flush();
        entityManager.clear();
    }

    private Product resolveVoucherProduct(UUID tenantId, SmartStagedVoucherItem item, Map<String, Product> productIndex) {
        if (item.matchedProductId != null) {
            var matched = products.findByTenantIdAndId(tenantId, item.matchedProductId).orElse(null);
            if (matched != null) return matched;
        }
        var product = productIndex.get(productKey(item.normalizedProductName, item.unitCode));
        if (product == null) {
            throw ApiErrors.badRequest("Product is unresolved for voucher item at source row " + item.sourceRowNumber);
        }
        return product;
    }

    private Warehouse resolveVoucherWarehouse(
        UUID tenantId, SmartImportCommit commit, SmartStagedVoucher voucher, List<SmartStagedVoucherItem> sourceItems,
        CommitCounts counts, Map<String, Warehouse> warehouseIndex
    ) {
        for (var item : sourceItems) {
            if (item.matchedWarehouseId != null) {
                var matched = warehouses.findByTenantIdAndId(tenantId, item.matchedWarehouseId).orElse(null);
                if (matched != null) return matched;
            }
            var named = warehouseIndex.get(normalizeName(item.warehouseName));
            if (named != null) return named;
        }
        var active = warehouses.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId);
        if (active.size() == 1) return active.getFirst();
        var fallbackKey = "IMPORTED - UNSPECIFIED";
        var fallback = warehouseIndex.get(fallbackKey);
        if (fallback != null) return fallback;
        fallback = active.stream().filter(row -> normalizeName(row.name).equals(fallbackKey)).findFirst().orElse(null);
        if (fallback == null) {
            fallback = new Warehouse();
            own(fallback, tenantId);
            fallback.name = "Imported - Unspecified";
            fallback.code = importCode("WH", fallbackKey);
            fallback.active = true;
            fallback = warehouses.save(fallback);
            counts.inc("warehousesCreated");
            effect(commit, voucher.sessionFileId, voucher.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.WAREHOUSE,
                fallback.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked("name", fallback.name), true);
        }
        warehouseIndex.put(fallbackKey, fallback);
        return fallback;
    }

    private BigDecimal voucherRate(SmartStagedVoucherItem item) {
        if (item.rate != null && item.rate.signum() >= 0) return item.rate;
        if (item.amount != null && item.quantity != null && item.quantity.signum() != 0) {
            return item.amount.abs().divide(item.quantity.abs(), 6, java.math.RoundingMode.HALF_UP);
        }
        throw ApiErrors.badRequest("Rate is unresolved for voucher item at source row " + item.sourceRowNumber);
    }

    private BigDecimal voucherLineTotal(SmartStagedVoucherItem item, BigDecimal quantity, BigDecimal rate) {
        return item.amount != null ? item.amount.abs() : quantity.multiply(rate);
    }

    private boolean excludedVoucher(SmartStagedVoucher voucher) {
        return voucher.rawMetadataJson != null
            && Boolean.parseBoolean(Objects.toString(voucher.rawMetadataJson.get("excludedFromCommit"), "false"));
    }

    private BigDecimal resolvedFinancialAdjustmentAmount(SmartStagedVoucher voucher) {
        if (voucher.rawMetadataJson == null) return ZERO;
        var value = Objects.toString(voucher.rawMetadataJson.get("resolvedFinancialAdjustmentAmount"), "");
        if (value.isBlank()) return ZERO;
        try {
            return new BigDecimal(value).abs();
        } catch (NumberFormatException ignored) {
            return ZERO;
        }
    }

    private int resolvedFinancialAdjustmentLineCount(SmartStagedVoucher voucher) {
        if (voucher.rawMetadataJson == null) return 0;
        return voucher.rawMetadataJson.get("resolvedFinancialAdjustmentLines") instanceof List<?> lines
            ? lines.size() : resolvedFinancialAdjustmentAmount(voucher).signum() > 0 ? 1 : 0;
    }

    private BigDecimal financialTotal(BigDecimal subtotal, SmartStagedVoucher voucher) {
        return nvl(subtotal).subtract(nvl(voucher.discountAmount)).add(nvl(voucher.taxAmount))
            .add(nvl(voucher.freightAmount)).add(nvl(voucher.roundOffAmount)).add(nvl(voucher.otherChargesAmount));
    }

    private Map<String, Object> financialMetadata(SmartStagedVoucher voucher) {
        return linked(
            "taxAmount", nvl(voucher.taxAmount), "discountAmount", nvl(voucher.discountAmount),
            "freightAmount", nvl(voucher.freightAmount), "roundOffAmount", nvl(voucher.roundOffAmount),
            "otherChargesAmount", nvl(voucher.otherChargesAmount), "taxLineCount", voucher.taxLineCount,
            "discountLineCount", voucher.discountLineCount, "freightLineCount", voucher.freightLineCount,
            "roundOffLineCount", voucher.roundOffLineCount, "otherChargeLineCount", voucher.otherChargeLineCount,
            "ledgerAdjustmentDetails", voucher.rawMetadataJson == null ? ""
                : voucher.rawMetadataJson.getOrDefault("ledgerAdjustmentDetails", "")
        );
    }

    private void captureFinancialCounts(CommitCounts counts, SmartStagedVoucher voucher) {
        counts.add("taxLinesCaptured", voucher.taxLineCount);
        counts.add("discountLinesCaptured", voucher.discountLineCount);
        counts.add("freightLinesCaptured", voucher.freightLineCount);
        counts.add("roundOffLinesCaptured", voucher.roundOffLineCount);
        counts.add("otherChargeLinesCaptured", voucher.otherChargeLineCount);
    }

    private void countRateMetadata(CommitCounts counts, SmartStagedVoucherItem item, boolean purchase) {
        var source = nullSafe(item.rateSource).toUpperCase(Locale.ROOT);
        if ("DERIVED_FROM_AMOUNT".equals(source) || "USER_DERIVED_FROM_AMOUNT".equals(source)) {
            counts.inc("rateDerivedRows");
        }
        if ("USER_VERIFIED_RATE".equals(source)) counts.inc("verifiedRateRows");
        if ("ZERO_RATE_ITEM".equals(source) || "USER_CONFIRMED_ZERO_VALUE".equals(source)) {
            counts.inc("zeroRateItems");
        }
        if ("ZERO_COST_ITEM".equals(source)
            || purchase && ("USER_CONFIRMED_ZERO_VALUE".equals(source) || nvl(item.rate).signum() == 0)) {
            counts.inc("zeroCostItems");
        }
    }

    private String requireVoucherNumber(SmartStagedVoucher voucher) {
        if (voucher.voucherNumber == null || voucher.voucherNumber.isBlank()) {
            throw ApiErrors.badRequest("Voucher number is required at source row " + voucher.sourceRowNumber);
        }
        return voucher.voucherNumber.trim();
    }

    private LocalDate requireVoucherDate(SmartStagedVoucher voucher) {
        if (voucher.voucherDate == null) throw ApiErrors.badRequest("Voucher date is required at source row " + voucher.sourceRowNumber);
        return voucher.voucherDate;
    }

    private VoucherKind voucherKind(String voucherType) {
        var normalized = nullSafe(voucherType).toUpperCase(Locale.ROOT);
        if (normalized.contains("CREDIT")) return VoucherKind.CREDIT_NOTE;
        if (normalized.contains("DEBIT")) return VoucherKind.DEBIT_NOTE;
        if (normalized.contains("RETURN")) return VoucherKind.DEFERRED;
        if (normalized.contains("PURCHASE")) return VoucherKind.PURCHASE;
        if (normalized.contains("SALE")) return VoucherKind.SALES;
        return VoucherKind.DEFERRED;
    }

    private Product resolveProduct(
        UUID tenantId, SmartStagedProduct row, String key, UUID unitId, Map<String, Product> index
    ) {
        if (row.matchedProductId != null) {
            return products.findByTenantIdAndId(tenantId, row.matchedProductId)
                .orElseThrow(() -> ApiErrors.badRequest("Matched product is not available in this tenant"));
        }
        var externalId = SmartImportProductIdentity.externalId(row);
        if (externalId != null) {
            var mapped = mappings.findFirstByTenantIdAndSourceSystemAndEntityTypeAndExternalId(
                tenantId, SOURCE_SYSTEM, "PRODUCT", externalId).orElse(null);
            if (mapped != null) {
                var product = products.findByTenantIdAndId(tenantId, mapped.localEntityId).orElse(null);
                if (product != null) return product;
            }
        }
        if (row.barcode != null && !row.barcode.isBlank()) {
            var barcode = barcodes.findByTenantIdAndBarcode(tenantId, row.barcode.trim()).orElse(null);
            if (barcode != null) return products.findByTenantIdAndId(tenantId, barcode.productId).orElse(null);
        }
        if (row.sku != null && !row.sku.isBlank()) {
            var product = products.findByTenantIdAndSkuIgnoreCase(tenantId, row.sku.trim()).orElse(null);
            if (product != null) return product;
        }
        var indexed = index.get(key);
        return indexed == null ? products.findByTenantIdAndNormalizedNameIgnoreCaseAndBaseUnitId(
            tenantId, firstNonBlank(row.normalizedName, normalizeName(row.rawName)), unitId).orElse(null) : indexed;
    }

    private boolean fillSafeProductFields(UUID tenantId, SmartImportCommit commit, SmartStagedProduct row, Product product) {
        var changed = false;
        if ((product.hsnCode == null || product.hsnCode.isBlank()) && row.hsn != null && !row.hsn.isBlank()) {
            product.hsnCode = row.hsn.trim();
            changed = true;
        }
        if (nvl(product.gstPercentage).signum() == 0 && nvl(row.gstPercent).signum() > 0) {
            product.gstPercentage = row.gstPercent;
            changed = true;
        }
        if (nvl(product.defaultSalesPrice).signum() == 0 && nvl(row.salesPrice).signum() > 0) {
            product.defaultSalesPrice = row.salesPrice;
            changed = true;
        }
        if (nvl(product.defaultPurchasePrice).signum() == 0 && nvl(row.purchasePrice).signum() > 0) {
            product.defaultPurchasePrice = row.purchasePrice;
            changed = true;
        }
        if (product.categoryId == null) {
            product.categoryId = ensureCategory(tenantId, commit, row.sessionFileId, row.sourceRowNumber,
                firstNonBlank(row.categoryName, "Imported Uncategorized")).id;
            changed = true;
        }
        return changed;
    }

    private ProductCategory ensureCategory(
        UUID tenantId, SmartImportCommit commit, UUID sourceFileId, Integer rowNumber, String name
    ) {
        var normalized = firstNonBlank(name, "Imported Uncategorized").trim();
        return categories.findByTenantIdAndNameIgnoreCase(tenantId, normalized).orElseGet(() -> {
            var category = new ProductCategory();
            own(category, tenantId);
            category.name = normalized;
            var saved = categories.save(category);
            effect(commit, sourceFileId, rowNumber, DomainEnums.SmartImportEffectEntityType.CATEGORY,
                saved.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked("name", saved.name), true);
            return saved;
        });
    }

    private UnitOfMeasure ensureUnit(
        UUID tenantId, SmartImportCommit commit, UUID sourceFileId, Integer rowNumber, String rawCode,
        CommitCounts counts, Map<String, UnitOfMeasure> index
    ) {
        var code = normalizeUnit(firstNonBlank(rawCode, "PCS"));
        var unit = index.get(code);
        if (unit != null) return unit;
        unit = units.findByTenantIdAndCodeIgnoreCase(tenantId, code).orElse(null);
        if (unit == null) {
            unit = new UnitOfMeasure();
            own(unit, tenantId);
            unit.code = code;
            unit.name = code;
            unit.baseUnit = true;
            unit = units.save(unit);
            counts.inc("unitsCreated");
            effect(commit, sourceFileId, rowNumber, DomainEnums.SmartImportEffectEntityType.UNIT,
                unit.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked("code", code), true);
        }
        index.put(code, unit);
        return unit;
    }

    private Warehouse resolveSnapshotWarehouse(
        UUID tenantId, SmartImportCommit commit, SmartStagedStockSnapshot row,
        CommitCounts counts, Map<String, Warehouse> index
    ) {
        if (row.matchedWarehouseId != null) {
            return warehouses.findByTenantIdAndId(tenantId, row.matchedWarehouseId)
                .orElseThrow(() -> ApiErrors.badRequest("Matched warehouse is not available in this tenant"));
        }
        var key = normalizeName(row.warehouseName);
        if (!key.isBlank() && index.containsKey(key)) return index.get(key);
        if (key.isBlank()) {
            var active = warehouses.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId);
            if (active.size() == 1) return active.getFirst();
            if (active.size() > 1) throw ApiErrors.badRequest("Choose a warehouse for snapshot row " + row.sourceRowNumber);
            key = "MAIN GODOWN";
        }
        var warehouse = new Warehouse();
        own(warehouse, tenantId);
        warehouse.name = row.warehouseName == null || row.warehouseName.isBlank() ? "Main Godown" : row.warehouseName.trim();
        warehouse.code = importCode("WH", key);
        warehouse.active = true;
        warehouse = warehouses.save(warehouse);
        index.put(key, warehouse);
        counts.inc("warehousesCreated");
        effect(commit, row.sessionFileId, row.sourceRowNumber, DomainEnums.SmartImportEffectEntityType.WAREHOUSE,
            warehouse.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked("name", warehouse.name), true);
        return warehouse;
    }

    private Customer resolveCustomer(UUID tenantId, SmartStagedParty row) {
        if (row.matchedCustomerId != null) return customers.findByTenantIdAndId(tenantId, row.matchedCustomerId).orElse(null);
        if (row.gstin != null && !row.gstin.isBlank()) {
            var match = customers.findByTenantIdAndGstinIgnoreCase(tenantId, row.gstin.trim()).orElse(null);
            if (match != null) return match;
        }
        return customerBySourceName(tenantId, firstNonBlank(row.normalizedName, row.rawName));
    }

    private Supplier resolveSupplier(UUID tenantId, SmartStagedParty row) {
        if (row.matchedSupplierId != null) return suppliers.findByTenantIdAndId(tenantId, row.matchedSupplierId).orElse(null);
        if (row.gstin != null && !row.gstin.isBlank()) {
            var match = suppliers.findByTenantIdAndGstinIgnoreCase(tenantId, row.gstin.trim()).orElse(null);
            if (match != null) return match;
        }
        return supplierBySourceName(tenantId, firstNonBlank(row.normalizedName, row.rawName));
    }

    private void createBarcodeIfPresent(UUID tenantId, UUID productId, String rawBarcode) {
        var barcodeValue = blankToNull(rawBarcode);
        if (barcodeValue == null) return;
        var existing = barcodes.findByTenantIdAndBarcode(tenantId, barcodeValue).orElse(null);
        if (existing != null && !existing.productId.equals(productId)) {
            throw ApiErrors.conflict("Product barcode already belongs to another product");
        }
        if (existing == null) {
            var barcode = new ProductBarcode();
            own(barcode, tenantId);
            barcode.productId = productId;
            barcode.barcode = barcodeValue;
            barcodes.save(barcode);
        }
    }

    private void upsertMapping(
        SmartImportCommit commit, UUID sourceFileId, String entityType, String externalId,
        String normalizedKey, String localEntityType, UUID localEntityId, String sourceHash
    ) {
        if ((externalId == null || externalId.isBlank()) && (normalizedKey == null || normalizedKey.isBlank())) return;
        var mapping = externalId == null || externalId.isBlank() ? null
            : mappings.findFirstByTenantIdAndSourceSystemAndEntityTypeAndExternalId(
                commit.tenantId, SOURCE_SYSTEM, entityType, externalId).orElse(null);
        if (mapping == null && normalizedKey != null && !normalizedKey.isBlank()) {
            mapping = mappings.findByTenantIdAndSourceSystemAndEntityTypeAndNormalizedKey(
                commit.tenantId, SOURCE_SYSTEM, entityType, normalizedKey).orElse(null);
        }
        var created = mapping == null;
        if (mapping == null) {
            mapping = new ExternalRecordMapping();
            own(mapping, commit.tenantId);
            mapping.sourceSystem = SOURCE_SYSTEM;
            mapping.entityType = entityType;
            mapping.normalizedKey = normalizedKey;
        } else if (!mapping.localEntityId.equals(localEntityId)) {
            throw ApiErrors.conflict("An existing external mapping points to a different " + localEntityType.toLowerCase(Locale.ROOT));
        }
        mapping.importSessionId = commit.importSessionId;
        mapping.sourceFileId = sourceFileId;
        mapping.externalId = blankToNull(externalId);
        mapping.localEntityType = localEntityType;
        mapping.localEntityId = localEntityId;
        mapping.sourceHash = blankToNull(sourceHash);
        mapping.lastSeenAt = Instant.now();
        mapping.updatedBy = TenantContext.userId();
        mapping = mappings.save(mapping);
        if (created) {
            effect(commit, sourceFileId, null, DomainEnums.SmartImportEffectEntityType.IMPORT_MAPPING,
                mapping.id, DomainEnums.SmartImportEffectAction.CREATED, Map.of(), linked(
                    "entityType", entityType, "normalizedKey", normalizedKey, "localEntityId", localEntityId), true);
        }
    }

    private void effect(
        SmartImportCommit commit, UUID sourceFileId, Integer sourceRowNumber,
        DomainEnums.SmartImportEffectEntityType entityType, UUID entityId,
        DomainEnums.SmartImportEffectAction action, Map<String, Object> oldValue,
        Map<String, Object> newValue, boolean reversible
    ) {
        var effect = new SmartImportEffect();
        own(effect, commit.tenantId);
        effect.importSessionId = commit.importSessionId;
        effect.commitId = commit.id;
        effect.sourceFileId = sourceFileId;
        effect.sourceRowNumber = sourceRowNumber;
        effect.entityType = entityType;
        effect.entityId = entityId;
        effect.action = action;
        effect.oldValueJson = new LinkedHashMap<>(oldValue == null ? Map.of() : oldValue);
        effect.newValueJson = new LinkedHashMap<>(newValue == null ? Map.of() : newValue);
        effect.reversible = reversible;
        effects.save(effect);
        audit.logCurrent("SMART_IMPORT_EFFECT_CREATED", "SmartImportEffect", effect.id, Map.of(
            "commitId", commit.id,
            "entityType", entityType.name(),
            "entityId", entityId,
            "action", action.name()
        ));
    }

    private void requireResolved(
        DomainEnums.SmartMatchStatus matchStatus, DomainEnums.SmartReviewStatus reviewStatus,
        String type, int rowNumber
    ) {
        if (matchStatus == null || matchStatus == DomainEnums.SmartMatchStatus.UNKNOWN
            || matchStatus == DomainEnums.SmartMatchStatus.POSSIBLE_DUPLICATE_REVIEW
            || matchStatus == DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW
            || reviewStatus == DomainEnums.SmartReviewStatus.PENDING) {
            throw ApiErrors.badRequest("Unresolved " + type + " review at source row " + rowNumber);
        }
    }

    private DomainEnums.NegativeStockImportPolicy negativePolicy(ImportDryRun dryRun) {
        try {
            return DomainEnums.NegativeStockImportPolicy.valueOf(String.valueOf(
                dryRun.summaryJson.getOrDefault("negativeStockPolicy", DomainEnums.NegativeStockImportPolicy.BLOCK.name())));
        } catch (IllegalArgumentException ignored) {
            return DomainEnums.NegativeStockImportPolicy.BLOCK;
        }
    }

    private DomainEnums.VoucherStockImpactMode stockImpactMode(ImportDryRun dryRun) {
        try {
            var configured = String.valueOf(dryRun.summaryJson.getOrDefault(
                "stockImpactMode", DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY.name()));
            var mode = DomainEnums.VoucherStockImpactMode.valueOf(configured);
            if (mode == DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY
                || mode == DomainEnums.VoucherStockImpactMode.APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE) {
                return mode;
            }
        } catch (IllegalArgumentException ignored) {
            // Rejected below with the same safe message for malformed or unsupported modes.
        }
        throw ApiErrors.badRequest("Unsupported Smart Import voucher stock-impact mode");
    }

    private DomainEnums.ReturnStockImpactMode returnStockImpactMode(ImportDryRun dryRun) {
        try {
            return DomainEnums.ReturnStockImpactMode.valueOf(String.valueOf(dryRun.summaryJson.getOrDefault(
                "returnStockImpactMode", DomainEnums.ReturnStockImpactMode.FINANCIAL_ONLY.name())));
        } catch (IllegalArgumentException ignored) {
            throw ApiErrors.badRequest("Unsupported Smart Import return stock-impact mode");
        }
    }

    private DomainEnums.StockJournalImpactMode stockJournalImpactMode(ImportDryRun dryRun) {
        try {
            return DomainEnums.StockJournalImpactMode.valueOf(String.valueOf(dryRun.summaryJson.getOrDefault(
                "stockJournalImpactMode", DomainEnums.StockJournalImpactMode.DEFER_STOCK_JOURNALS.name())));
        } catch (IllegalArgumentException ignored) {
            throw ApiErrors.badRequest("Unsupported Smart Import stock-journal impact mode");
        }
    }

    private LocalDate date(Object value) {
        try {
            var text = Objects.toString(value, "");
            return text.isBlank() ? null : LocalDate.parse(text);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String unitCode(UUID tenantId, Product product) {
        return product.baseUnitId == null ? "PCS" : units.findByTenantIdAndId(tenantId, product.baseUnitId)
            .map(unit -> normalizeUnit(unit.code)).orElse("PCS");
    }

    private Map<String, Object> productValue(Product product) {
        return linked("name", product.name, "normalizedName", product.normalizedName, "sku", product.sku,
            "baseUnitId", product.baseUnitId, "categoryId", product.categoryId, "hsnCode", product.hsnCode,
            "gstPercentage", nvl(product.gstPercentage), "defaultPurchasePrice", nvl(product.defaultPurchasePrice));
    }

    private Map<String, Object> response(SmartImportCommit commit, boolean idempotentRetry) {
        var response = new LinkedHashMap<String, Object>();
        response.put("available", true);
        response.put("id", commit.id);
        response.put("importSessionId", commit.importSessionId);
        response.put("dryRunId", commit.dryRunId);
        response.put("status", commit.status);
        response.put("strategy", commit.strategy);
        response.put("startedAt", commit.startedAt);
        response.put("finishedAt", commit.finishedAt == null ? "" : commit.finishedAt);
        response.put("committedAt", commit.finishedAt == null ? "" : commit.finishedAt);
        response.put("committedBy", commit.committedBy);
        response.put("idempotentRetry", idempotentRetry);
        response.put("summary", commit.summaryJson);
        return response;
    }

    private void own(BaseAudit entity, UUID tenantId) {
        if (entity instanceof TenantOwnedEntity owned) owned.tenantId = tenantId;
        entity.createdBy = TenantContext.userId();
        entity.updatedBy = TenantContext.userId();
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private String normalizeUnit(String value) {
        return firstNonBlank(value, "PCS").trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private String productKey(String normalizedName, String unitCode) {
        return normalizeName(normalizedName) + "|" + normalizeUnit(unitCode);
    }

    private String importCode(String prefix, String normalized) {
        return prefix + "-" + Integer.toUnsignedString(normalized.hashCode(), 36).toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (var value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return new BigDecimal(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String stockDryRunKey(ImportDryRunItem item) {
        if (item.targetEntityId != null) return item.targetEntityId.toString();
        var stagedVoucherItemId = item.previewJson.get("stagedVoucherItemId");
        return stagedVoucherItemId == null ? item.sourceFileId + ":" + item.sourceRowNumber
            : stagedVoucherItemId.toString();
    }

    private String stockEffectFingerprint(
        UUID tenantId,
        SmartStagedVoucher voucher,
        SmartStagedVoucherItem item,
        UUID productId,
        UUID warehouseId,
        DomainEnums.MovementType movementType,
        BigDecimal quantity
    ) {
        var voucherIdentity = firstNonBlank(voucher.externalId, voucher.fingerprint,
            normalizeName(voucher.voucherType) + "|" + normalizeName(voucher.voucherNumber)
                + "|" + voucher.voucherDate + "|" + normalizeName(voucher.partyName));
        return sha256(tenantId + "|STOCK_EFFECT|" + voucherIdentity + "|" + movementType + "|"
            + productId + "|" + warehouseId + "|" + normalizeUnit(item.unitCode) + "|"
            + quantity.stripTrailingZeros().toPlainString() + "|" + item.sourceRowNumber);
    }

    private String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private int integer(Object value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private Map<String, Object> linked(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1] == null ? "" : values[index + 1]);
        }
        return result;
    }

    private static final class CommitCounts {
        private static final List<String> KEYS = List.of(
            "productsCreated", "productsMatchedExisting", "productsUpdated",
            "partiesCreated", "partiesMatchedExisting",
            "warehousesCreated", "warehousesMatchedExisting",
            "unitsCreated", "unitsMatchedExisting",
            "snapshotRowsProcessed", "snapshotMovementsCreated", "snapshotRowsNoChange",
            "snapshotPositiveAdjustments", "snapshotNegativeAdjustments",
            "negativeStockRowsFound", "negativeStockRowsSkipped",
            "salesInvoicesCreated", "salesInvoiceItemsCreated",
            "purchaseInvoicesCreated", "purchaseInvoiceItemsCreated",
            "duplicateSalesVouchersSkipped", "duplicatePurchaseVouchersSkipped",
            "creditNotesCreated", "creditNoteItemsCreated", "debitNotesCreated", "debitNoteItemsCreated",
            "duplicateCreditNotesSkipped", "duplicateDebitNotesSkipped",
            "taxLinesCaptured", "discountLinesCaptured", "freightLinesCaptured", "roundOffLinesCaptured", "otherChargeLinesCaptured",
            "stockMovementsCreatedFromReturns", "returnStockMovementsDeferred",
            "creditNotesCommitted", "debitNotesCommitted", "physicalSalesReturnsCommitted",
            "physicalPurchaseReturnsCommitted", "returnInMovementsCreated", "returnOutMovementsCreated",
            "financialOnlyNotesCommitted", "mixedNotesCommitted", "ambiguousReturnsDeferred",
            "damagedReturnsDeferred", "sameDateReturnsDeferred", "returnsCoveredBySnapshot",
            "returnsWithoutSnapshot", "warehouseBlockedReturns",
            "negativeStockReturnMovementsBlocked", "negativeStockReturnMovementsSkipped",
            "duplicateReturnMovementsSkipped", "returnItemsResolvedManually",
            "voucherStockMovementsCreated", "stockMovementsSkippedDueToInvoiceOnly",
            "salesStockMovementsCreated", "purchaseStockMovementsCreated",
            "voucherItemsCoveredBySnapshot", "sameDateVoucherItemsDeferred", "voucherItemsWithoutSnapshot",
            "voucherItemsDeferred", "duplicateStockMovementsSkipped",
            "negativeStockMovementsBlocked", "negativeStockMovementsSkipped", "negativeStockMovementsImported",
            "movementsSkippedByPolicy", "invoicesOnlyCommitted", "productLevelSnapshotFallbacks",
            "warehouseMatchedForStock", "warehouseAutoCreatedOrMapped", "warehouseUnresolved",
            "rateDerivedRows", "verifiedRateRows", "zeroRateItems", "zeroCostItems", "ledgerLinesSkipped",
            "financialAdjustmentLinesResolved", "vouchersExcludedByUser",
            "debtorRowsProcessed", "creditorRowsProcessed", "outstandingSnapshotsCreated",
            "duplicateOutstandingSnapshotsSkipped",
            "customerPaymentsCreated", "supplierPaymentsCreated", "cashbookRowsMatched", "retailCounterSaleRowsResolved",
            "cashbookRowsUnmatched", "cashbookRowsReviewRequired",
            "paymentsLinkedToSalesInvoices", "paymentsLinkedToPurchaseInvoices", "duplicatePaymentsSkipped",
            "cashbookRowsManuallyResolved", "cashbookRowsIgnored",
            "manualCustomerPaymentsCreated", "manualSupplierPaymentsCreated",
            "manualPaymentsLinkedToSalesInvoices", "manualPaymentsLinkedToPurchaseInvoices",
            "stockJournalsProcessed", "warehouseTransfersCommitted", "transferPairsCreated",
            "transferOutMovementsCreated", "transferInMovementsCreated",
            "warehouseTransfersCoveredBySnapshot", "sameDateWarehouseTransfersDeferred",
            "warehouseTransfersWithoutBaseline", "unresolvedWarehouseTransfers",
            "duplicateWarehouseTransfersSkipped", "warehouseTransfersBlockedByNegativeStock",
            "warehouseTransfersSkippedByPolicy", "manufacturingJournalsDeferred",
            "adjustmentJournalsDeferred", "ambiguousStockJournalsDeferred",
            "manuallyResolvedWarehouseTransfers", "ignoredStockJournals",
            "vouchersDeferredForFuturePhase", "futurePhaseVoucherRowsSkipped",
            "futurePhaseCashbookRowsSkipped", "futurePhaseAgeingRowsSkipped"
        );
        private final Map<String, Long> values = new LinkedHashMap<>();

        private CommitCounts() {
            KEYS.forEach(key -> values.put(key, 0L));
        }

        void inc(String key) {
            values.merge(key, 1L, Long::sum);
        }

        void set(String key, long value) {
            values.put(key, value);
        }

        void add(String key, long value) {
            values.merge(key, value, Long::sum);
        }

        long get(String key) {
            return values.getOrDefault(key, 0L);
        }

        LinkedHashMap<String, Object> response() {
            var response = new LinkedHashMap<String, Object>();
            values.forEach(response::put);
            return response;
        }
    }

    private enum VoucherKind { SALES, PURCHASE, CREDIT_NOTE, DEBIT_NOTE, DEFERRED }
}
