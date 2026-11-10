package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.exception.ApiException;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ImportService {
    private static final Set<String> SUPPORTED_IMPORT_EXTENSIONS = Set.of("csv", "xlsx", "xls", "json", "xml");
    private static final String NEGATIVE_STOCK_POLICY_KEY = "negativeStockPolicy";
    private static final String VOUCHER_STOCK_IMPACT_MODE_KEY = "voucherStockImpactMode";
    private static final String VOUCHER_STOCK_IMPACT_CONFIRMED_KEY = "confirmStockMovementsAfterSnapshot";
    private static final String DOUBLE_COUNT_WARNING = "You already imported a closing stock snapshot. Importing historical purchase/sales vouchers as stock movements may double-count inventory. Choose invoice-only mode unless these vouchers are after the snapshot date.";

    private final Map<DomainEnums.SourceType, ErpImportAdapter> adapters;
    private final ObjectStorageService storage;
    private final Repositories.ImportBatchRepository batches;
    private final Repositories.ImportFileRepository files;
    private final Repositories.ImportErrorRepository errors;
    private final Repositories.ImportEffectRepository effects;
    private final Repositories.ImportMappingTemplateRepository templates;
    private final Repositories.StagingProductRepository stagingProducts;
    private final Repositories.StagingCustomerRepository stagingCustomers;
    private final Repositories.StagingSupplierRepository stagingSuppliers;
    private final Repositories.StagingStockMovementRepository stagingStockMovements;
    private final Repositories.ProductRepository products;
    private final Repositories.ProductBarcodeRepository barcodes;
    private final Repositories.CategoryRepository categories;
    private final Repositories.UnitRepository units;
    private final Repositories.CustomerRepository customers;
    private final Repositories.SupplierRepository suppliers;
    private final Repositories.WarehouseRepository warehouses;
    private final Repositories.SalesInvoiceRepository salesInvoices;
    private final Repositories.SalesInvoiceItemRepository salesInvoiceItems;
    private final Repositories.PurchaseInvoiceRepository purchaseInvoices;
    private final Repositories.PurchaseInvoiceItemRepository purchaseInvoiceItems;
    private final CatalogService catalogService;
    private final StockLedgerService stockLedger;
    private final AuditService auditService;
    private final EntityManager entityManager;
    private final com.stockpilot.ai.repo.LegacyImportRawRowRepository rawRows;
    private final InvoiceIdentityService invoiceIdentity;
    private final ProductIntegrityService productIntegrity;
    private final boolean devToolsEnabled;
    private final long maxUploadBytes;

    public ImportService(
        List<ErpImportAdapter> adapters,
        ObjectStorageService storage,
        Repositories.ImportBatchRepository batches,
        Repositories.ImportFileRepository files,
        Repositories.ImportErrorRepository errors,
        Repositories.ImportEffectRepository effects,
        Repositories.ImportMappingTemplateRepository templates,
        Repositories.StagingProductRepository stagingProducts,
        Repositories.StagingCustomerRepository stagingCustomers,
        Repositories.StagingSupplierRepository stagingSuppliers,
        Repositories.StagingStockMovementRepository stagingStockMovements,
        Repositories.ProductRepository products,
        Repositories.ProductBarcodeRepository barcodes,
        Repositories.CategoryRepository categories,
        Repositories.UnitRepository units,
        Repositories.CustomerRepository customers,
        Repositories.SupplierRepository suppliers,
        Repositories.WarehouseRepository warehouses,
        Repositories.SalesInvoiceRepository salesInvoices,
        Repositories.SalesInvoiceItemRepository salesInvoiceItems,
        Repositories.PurchaseInvoiceRepository purchaseInvoices,
        Repositories.PurchaseInvoiceItemRepository purchaseInvoiceItems,
        CatalogService catalogService,
        StockLedgerService stockLedger,
        AuditService auditService,
        EntityManager entityManager,
        com.stockpilot.ai.repo.LegacyImportRawRowRepository rawRows,
        InvoiceIdentityService invoiceIdentity,
        ProductIntegrityService productIntegrity,
        @Value("${app.dev-tools.enabled:false}") boolean devToolsEnabled,
        @Value("${app.import.max-upload-size:500MB}") DataSize maxUploadSize
    ) {
        this.adapters = adapters.stream().collect(Collectors.toMap(ErpImportAdapter::sourceType, Function.identity()));
        this.storage = storage;
        this.batches = batches;
        this.files = files;
        this.errors = errors;
        this.effects = effects;
        this.templates = templates;
        this.stagingProducts = stagingProducts;
        this.stagingCustomers = stagingCustomers;
        this.stagingSuppliers = stagingSuppliers;
        this.stagingStockMovements = stagingStockMovements;
        this.products = products;
        this.barcodes = barcodes;
        this.categories = categories;
        this.units = units;
        this.customers = customers;
        this.suppliers = suppliers;
        this.warehouses = warehouses;
        this.salesInvoices = salesInvoices;
        this.salesInvoiceItems = salesInvoiceItems;
        this.purchaseInvoices = purchaseInvoices;
        this.purchaseInvoiceItems = purchaseInvoiceItems;
        this.catalogService = catalogService;
        this.stockLedger = stockLedger;
        this.auditService = auditService;
        this.entityManager = entityManager;
        this.rawRows = rawRows;
        this.invoiceIdentity = invoiceIdentity;
        this.productIntegrity = productIntegrity;
        this.devToolsEnabled = devToolsEnabled;
        this.maxUploadBytes = maxUploadSize.toBytes();
    }

    @Transactional
    public ApiDtos.ImportUploadResponse upload(DomainEnums.SourceType requestedSource, MultipartFile upload) {
        validateUpload(upload, SUPPORTED_IMPORT_EXTENSIONS, maxUploadBytes);
        var tenantId = TenantContext.tenantId();
        var batch = new ImportBatch();
        batch.tenantId = tenantId;
        batch.sourceType = requestedSource;
        batch.status = DomainEnums.ImportStatus.UPLOADED;
        batch.originalFileName = upload.getOriginalFilename();
        batches.save(batch);

        try {
            var key = tenantId + "/" + batch.id + "/" + sanitize(upload.getOriginalFilename());
            var storageKey = storage.store(key, upload);
            var importFile = new ImportFile();
            importFile.tenantId = tenantId;
            importFile.importBatchId = batch.id;
            importFile.fileName = upload.getOriginalFilename();
            importFile.contentType = upload.getContentType();
            importFile.storageKey = storageKey;
            importFile.sizeBytes = upload.getSize();
            files.save(importFile);

            var adapter = adapterFor(requestedSource, upload.getOriginalFilename());
            batch.sourceType = adapter.sourceType();
            try (var input = storage.read(storageKey)) {
                var parsed = adapter.parse(new ErpImportAdapter.ImportRequest(tenantId, batch.id, upload.getOriginalFilename(), input));
                var preview = new ErpImportAdapter.ImportPreview(CanonicalImportNormalizer.normalize(parsed.rows(), adapter.sourceType().name()));
                int sourceRow = 1;
                for (var values : preview.rows()) {
                    var raw = new LegacyImportRawRow();
                    raw.tenantId = tenantId; raw.importBatchId = batch.id; raw.rowNumber = sourceRow++;
                    raw.rawMetadata = new LinkedHashMap<>(values);
                    rawRows.save(raw);
                }
                stageRows(tenantId, batch.id, batch.sourceType, preview.rows());
                batch.rowCount = preview.rows().size();
                batch.importPurpose = inferImportPurpose(tenantId, batch.id);
                initializeVoucherStockImpactContext(batch, null, false);
                batch.status = DomainEnums.ImportStatus.PARSED;
                batches.save(batch);
            }
            auditService.logCurrent("IMPORT_UPLOADED", "ImportBatch", batch.id, Map.of(
                "sourceType", batch.sourceType.name(),
                "fileName", batch.originalFileName == null ? "" : batch.originalFileName,
                "rowCount", batch.rowCount
            ));
            return new ApiDtos.ImportUploadResponse(batch.id, batch.status.name(), batch.rowCount);
        } catch (Exception ex) {
            batch.status = DomainEnums.ImportStatus.FAILED;
            batch.errorCount = 1;
            batches.save(batch);
            errors.save(error(tenantId, batch.id, 0, "FILE", "file", "IMPORT_PARSE_FAILED", safeImportError(ex), "ERROR", upload.getOriginalFilename(), "Check the file format and supported extension."));
            throw ApiErrors.badRequest(safeImportError(ex));
        }
    }

    public Page<ImportBatch> list(Pageable pageable) {
        return batches.findByTenantId(TenantContext.tenantId(), pageable);
    }

    public ImportBatch get(UUID batchId) {
        return batches.findByTenantIdAndId(TenantContext.tenantId(), batchId).orElseThrow(() -> ApiErrors.notFound("Import batch not found"));
    }

    public ImportBatch withVoucherStockImpactContext(ImportBatch batch) {
        if (batch != null && batch.importPurpose == DomainEnums.ImportPurpose.TRANSACTION_IMPORT) {
            initializeVoucherStockImpactContext(batch, null, voucherStockImpactConfirmed(batch));
        }
        return batch;
    }

    public List<Map<String, Object>> preview(UUID batchId) {
        var tenantId = TenantContext.tenantId();
        var batch = get(batchId);
        var negativeStockPolicy = resolveNegativeStockPolicy(batch, null);
        var voucherStockImpactMode = resolveVoucherStockImpactMode(batch, null);
        var latestSnapshot = latestCommittedStockSnapshot(tenantId);
        var tenantAllowsNegativeStock = stockLedger.negativeStockAllowed(tenantId);
        var rows = new ArrayList<Map<String, Object>>();
        stagingProducts.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId).forEach(row -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("type", "PRODUCT");
                map.put("importPurpose", batch.importPurpose == null ? DomainEnums.ImportPurpose.MASTER_IMPORT.name() : batch.importPurpose.name());
                map.put("rowNumber", row.rowNumber);
                map.put("productName", row.productName);
                map.put("sku", row.sku);
                map.put("category", row.category);
                map.put("brand", row.brand);
                map.put("unitCode", row.unitCode);
                map.put("openingStock", row.openingStock);
                map.put("purchasePrice", row.purchasePrice);
                map.put("salesPrice", row.salesPrice);
                map.put("warehouseName", row.warehouseName);
                if (isStockSnapshotRow(batch, row)) {
                    addStockSnapshotPreview(tenantId, row, negativeStockPolicy, tenantAllowsNegativeStock, map);
                }
                map.put("rawMetadata", row.rawMetadata);
                rows.add(map);
            });
        stagingCustomers.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId).forEach(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", "CUSTOMER");
            map.put("rowNumber", row.rowNumber);
            map.put("name", row.name);
            map.put("phone", row.phone);
            map.put("email", row.email);
            map.put("gstin", row.gstin);
            map.put("rawMetadata", row.rawMetadata);
            rows.add(map);
        });
        stagingSuppliers.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId).forEach(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", "SUPPLIER");
            map.put("rowNumber", row.rowNumber);
            map.put("name", row.name);
            map.put("phone", row.phone);
            map.put("email", row.email);
            map.put("gstin", row.gstin);
            map.put("rawMetadata", row.rawMetadata);
            rows.add(map);
        });
        stagingStockMovements.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId).forEach(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", "STOCK_MOVEMENT");
            map.put("rowNumber", row.rowNumber);
            if (isTallyPurchaseRow(batch, row)) {
                map.put("voucherNumber", invoiceNumber(row.rawMetadata));
                map.put("voucherDate", row.movementDate);
                map.put("partyName", raw(row.rawMetadata, "partyName", "Party Name", "Supplier", "Ledger Name"));
            }
            map.put("productName", row.productName);
            map.put("warehouseName", row.warehouseName);
            map.put("movementType", row.movementType);
            map.put("voucherStockImpactMode", voucherStockImpactMode.name());
            map.put("latestSnapshotDate", latestSnapshot.map(snapshot -> snapshot.date().toString()).orElse(""));
            map.put("stockMovementAction", voucherStockMovementAction(voucherStockImpactMode, latestSnapshot.map(LatestStockSnapshot::date).orElse(null), row));
            map.put("quantity", row.quantity);
            map.put("rate", row.rate);
            if (isTallyPurchaseRow(batch, row)) {
                var rateSource = purchaseRateSource(row);
                map.put("rate", row.rate);
                map.put("unit", raw(row.rawMetadata, "Unit"));
                map.put("rawRate", raw(row.rawMetadata, "Raw Rate", "Rate"));
                map.put("parsedRate", row.rate);
                map.put("amount", raw(row.rawMetadata, "Raw Amount", "Amount"));
                map.put("rateSource", rateSource);
                map.put("action", purchaseImportAction(row));
            }
            map.put("movementDate", row.movementDate);
            map.put("rawMetadata", row.rawMetadata);
            rows.add(map);
        });
        var stagedNumbers = rows.stream().map(row -> (Integer) row.get("rowNumber")).collect(Collectors.toSet());
        for (var raw : rawRows.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId)) {
            if (!stagedNumbers.contains(raw.rowNumber)) rows.add(new LinkedHashMap<>(Map.of(
                "type", "UNMAPPED", "rowNumber", raw.rowNumber, "rawMetadata", raw.rawMetadata)));
        }
        rows.sort(Comparator.comparing(row -> (Integer) row.get("rowNumber")));
        return rows;
    }

    @Transactional
    public ImportBatch applyMapping(UUID batchId, ApiDtos.ImportMappingRequest request) {
        var batch = get(batchId);
        var previousPolicy = resolveNegativeStockPolicy(batch, null);
        var previousVoucherMode = resolveVoucherStockImpactMode(batch, null);
        var previousVoucherConfirmation = voucherStockImpactConfirmed(batch);
        batch.mappingJson = new LinkedHashMap<>(request.mapping());
        batch.mappingJson.put(NEGATIVE_STOCK_POLICY_KEY, previousPolicy.name());
        batch.mappingJson.put(VOUCHER_STOCK_IMPACT_MODE_KEY, previousVoucherMode.name());
        batch.mappingJson.put(VOUCHER_STOCK_IMPACT_CONFIRMED_KEY, previousVoucherConfirmation);
        batch.status = DomainEnums.ImportStatus.MAPPED;
        if (batch.committedAt != null || batch.status == DomainEnums.ImportStatus.ROLLED_BACK) {
            throw ApiErrors.conflict("Committed imports cannot be remapped; upload a new batch");
        }
        var originals = rawRows.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(batch.tenantId, batchId);
        if (!originals.isEmpty()) {
            for (var entity : List.of("StagingProduct", "StagingCustomer", "StagingSupplier", "StagingStockMovement")) {
                entityManager.createQuery("delete from " + entity + " where tenantId = :tenant and importBatchId = :batch")
                    .setParameter("tenant", batch.tenantId).setParameter("batch", batchId).executeUpdate();
            }
            var mappedRows = new ArrayList<Map<String, String>>();
            for (var original : originals) {
                var mapped = new LinkedHashMap<>(original.rawMetadata);
                request.mapping().forEach((target, source) -> {
                    var value = lookup(original.rawMetadata, source);
                    if (value != null) mapped.put("name".equals(target) ? "partyName" : target, value);
                });
                mappedRows.add(mapped);
            }
            stageRows(batch.tenantId, batchId, batch.sourceType, mappedRows);
        } else {
            // Previously uploaded batches predate raw-row retention.
            var products = stagingProducts.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(batch.tenantId, batchId);
            products.forEach(row -> remap(row, request.mapping())); stagingProducts.saveAll(products);
            var customers = stagingCustomers.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(batch.tenantId, batchId);
            customers.forEach(row -> remap(row, request.mapping())); stagingCustomers.saveAll(customers);
            var suppliers = stagingSuppliers.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(batch.tenantId, batchId);
            suppliers.forEach(row -> remap(row, request.mapping())); stagingSuppliers.saveAll(suppliers);
            var movements = stagingStockMovements.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(batch.tenantId, batchId);
            movements.forEach(row -> remap(row, request.mapping())); stagingStockMovements.saveAll(movements);
        }
        batch.importPurpose = inferImportPurpose(batch.tenantId, batchId);
        initializeVoucherStockImpactContext(batch, previousVoucherMode, previousVoucherConfirmation);
        return batches.save(batch);
    }

    @Transactional
    public ImportBatch validate(UUID batchId) {
        return validate(batchId, null, null, false);
    }

    @Transactional
    public ImportBatch validate(UUID batchId, DomainEnums.NegativeStockImportPolicy requestedPolicy) {
        return validate(batchId, requestedPolicy, null, false);
    }

    @Transactional
    public ImportBatch validate(
        UUID batchId,
        DomainEnums.NegativeStockImportPolicy requestedPolicy,
        DomainEnums.VoucherStockImpactMode requestedVoucherMode,
        boolean confirmStockMovementsAfterSnapshot
    ) {
        var batch = get(batchId);
        var tenantId = TenantContext.tenantId();
        var negativeStockPolicy = resolveNegativeStockPolicy(batch, requestedPolicy);
        var voucherStockImpactMode = resolveVoucherStockImpactMode(batch, requestedVoucherMode);
        var latestSnapshot = latestCommittedStockSnapshot(tenantId);
        var tenantAllowsNegativeStock = stockLedger.negativeStockAllowed(tenantId);
        errors.deleteByTenantIdAndImportBatchId(tenantId, batchId);
        int errorCount = 0;
        var invalidRows = new HashSet<Integer>();
        var productRows = stagingProducts.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId);
        var customerRows = stagingCustomers.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId);
        var supplierRows = stagingSuppliers.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId);
        var movementRows = stagingStockMovements.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId);
        updateVoucherStockImpactContext(batch, movementRows, voucherStockImpactMode, confirmStockMovementsAfterSnapshot, latestSnapshot);
        var mappedRowNumbers = new HashSet<Integer>();
        productRows.forEach(row -> mappedRowNumbers.add(row.rowNumber));
        customerRows.forEach(row -> mappedRowNumbers.add(row.rowNumber));
        supplierRows.forEach(row -> mappedRowNumbers.add(row.rowNumber));
        movementRows.forEach(row -> mappedRowNumbers.add(row.rowNumber));
        for (var raw : rawRows.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId)) {
            if (!mappedRowNumbers.contains(raw.rowNumber)) {
                errors.save(error(tenantId, batchId, raw.rowNumber, "mapping", "UNMAPPED_ROW",
                    "No entity could be identified. Map the source columns before committing."));
                invalidRows.add(raw.rowNumber); errorCount++;
            }
        }

        var seenSkus = new HashSet<String>();
        var seenBarcodes = new HashSet<String>();
        var seenNamesWithoutCodes = new HashSet<String>();
        var warnedAutoCreateWarehouses = new HashSet<String>();
        for (var row : productRows) {
            var rowErrors = new ArrayList<ImportError>();
            if (hasRaw(row, "GST Mapping Error")) rowErrors.add(error(tenantId, batchId, row.rowNumber, "PRODUCT", "gstPercentage", "INVALID_GST_MAPPING", raw(row, "GST Mapping Error"), "ERROR", raw(row,"GST Rate Evidence"), "Correct the effective GST duty rates in the export."));
            if (row.productName == null || row.productName.isBlank()) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "PRODUCT", "productName", "PRODUCT_NAME_REQUIRED", "Product name is required", "ERROR", raw(row, "productName", "Product Name", "Item Name"), "Map item name/product name to productName."));
            }
            if (row.unitCode == null || row.unitCode.isBlank()) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "PRODUCT", "unitCode", "UNIT_MISSING", "Unit is required", "ERROR", raw(row, "unitCode", "Unit", "Units", "Base Unit"), "Add a unit such as PCS, BOX, KG, or LTR."));
            }
            var sku = normalizedValue(row.sku);
            if (sku != null && !seenSkus.add(sku)) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "PRODUCT", "sku", "DUPLICATE_SKU", "SKU appears more than once in this import batch", "ERROR", row.sku, "Keep one row per SKU or make SKUs unique."));
            }
            var barcode = normalizedValue(row.barcode);
            if (barcode != null && !seenBarcodes.add(barcode)) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "PRODUCT", "barcode", "DUPLICATE_BARCODE", "Barcode appears more than once in this import batch", "ERROR", row.barcode, "Keep one row per barcode or correct duplicate barcodes."));
            }
            var nameKey = normalizedValue(row.productName);
            if (nameKey != null && sku == null && barcode == null && !seenNamesWithoutCodes.add(nameKey)) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "PRODUCT", "productName", "DUPLICATE_PRODUCT_NAME", "Product name appears more than once without SKU/barcode", "ERROR", row.productName, "Add SKU/barcode or merge duplicate product rows."));
            }
            if (isTallyStockReportRow(row)) {
                var snapshotMatch = stockSnapshotMatch(tenantId, row, findExistingProduct(tenantId, row));
                if ("POSSIBLE_DUPLICATE_REVIEW".equals(snapshotMatch.status())) {
                    rowErrors.add(error(
                        tenantId,
                        batchId,
                        row.rowNumber,
                        "PRODUCT",
                        "productName",
                        "POSSIBLE_DUPLICATE_REVIEW",
                        "Possible duplicate product requires review before stock snapshot commit",
                        "ERROR",
                        row.productName,
                        snapshotMatch.reviewWarning()
                    ));
                }
            }
            if (row.openingStock != null && row.openingStock.compareTo(BigDecimal.ZERO) < 0) {
                rowErrors.add(negativeOpeningStockIssue(tenantId, batchId, row, negativeStockPolicy, tenantAllowsNegativeStock));
            } else if (row.openingStock == null && hasRaw(row, "openingStock", "Opening Stock", "Opening Qty", "Qty", "Quantity")) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "PRODUCT", "openingStock", "INVALID_QUANTITY", "Opening stock is not a valid number", "ERROR", raw(row, "openingStock", "Opening Stock", "Opening Qty", "Qty", "Quantity"), "Use a numeric quantity like 12 or 12.500."));
            }
            if (row.warehouseName != null && !row.warehouseName.isBlank() && warehouses.findByTenantIdAndNameIgnoreCase(tenantId, row.warehouseName).isEmpty()) {
                if (canAutoCreateImportWarehouse(batch)) {
                    addAutoCreateWarehouseWarning(tenantId, batchId, row.rowNumber, row.warehouseName, warnedAutoCreateWarehouses, rowErrors);
                } else {
                    rowErrors.add(error(tenantId, batchId, row.rowNumber, "PRODUCT", "warehouseName", "UNKNOWN_WAREHOUSE", "Unknown warehouse: " + row.warehouseName, "ERROR", row.warehouseName, "Create the warehouse first or fix the godown/warehouse name."));
                }
            }
            if (row.gstPercentage != null && (row.gstPercentage.compareTo(BigDecimal.ZERO) < 0 || row.gstPercentage.compareTo(BigDecimal.valueOf(100)) > 0)) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "PRODUCT", "gstPercentage", "INVALID_GST_PERCENTAGE", "GST percentage must be between 0 and 100", "ERROR", raw(row, "GST %", "GST", "Tax Rate"), "Use a percentage between 0 and 100."));
            } else if (row.gstPercentage == null && hasRaw(row, "gstPercentage", "GST %", "GST", "Tax Rate")) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "PRODUCT", "gstPercentage", "INVALID_GST_PERCENTAGE", "GST percentage is not a valid number", "ERROR", raw(row, "gstPercentage", "GST %", "GST", "Tax Rate"), "Use a numeric percentage such as 5, 12, or 18."));
            }
            if (row.hsnCode != null && !row.hsnCode.isBlank() && !row.hsnCode.trim().matches("^[0-9A-Za-z]{4,8}$")) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "PRODUCT", "hsnCode", "INVALID_HSN", "HSN/SAC should be 4 to 8 alphanumeric characters", "WARNING", row.hsnCode, "Review the HSN/SAC code if GST reports depend on it."));
            }
            if (row.category == null || row.category.isBlank()) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "PRODUCT", "category", "CATEGORY_MISSING", "Product category is missing", "WARNING", null, "Add a category to improve dashboards and cleanup insights."));
            }
            rowErrors = deduplicateIssues(rowErrors);
            if (!rowErrors.isEmpty()) {
                if (hasErrors(rowErrors)) {
                    invalidRows.add(row.rowNumber);
                    errorCount += errorSeverityCount(rowErrors);
                }
                errors.saveAll(rowErrors);
            }
        }
        var seenCustomersByGstin = new HashMap<String, String>();
        var seenCustomersByNamePhone = new HashSet<String>();
        for (var row : customerRows) {
            var rowErrors = validateCustomerRow(tenantId, batchId, row, seenCustomersByGstin, seenCustomersByNamePhone);
            rowErrors = deduplicateIssues(rowErrors);
            if (!rowErrors.isEmpty()) {
                if (hasErrors(rowErrors)) {
                    invalidRows.add(row.rowNumber);
                    errorCount += errorSeverityCount(rowErrors);
                }
                errors.saveAll(rowErrors);
            }
        }
        var seenSuppliersByGstin = new HashMap<String, String>();
        var seenSuppliersByNamePhone = new HashSet<String>();
        for (var row : supplierRows) {
            var rowErrors = validateSupplierRow(tenantId, batchId, row, seenSuppliersByGstin, seenSuppliersByNamePhone);
            rowErrors = deduplicateIssues(rowErrors);
            if (!rowErrors.isEmpty()) {
                if (hasErrors(rowErrors)) {
                    invalidRows.add(row.rowNumber);
                    errorCount += errorSeverityCount(rowErrors);
                }
                errors.saveAll(rowErrors);
            }
        }
        var seenInvoiceRows = new HashMap<String, StagingStockMovement>();
        for (var row : movementRows) {
            var rowErrors = new ArrayList<ImportError>();
            DomainEnums.MovementType parsedType = null;
            var cancelled = cancelledVoucher(row.rawMetadata);
            if (row.productName == null || row.productName.isBlank()) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "productName", "PRODUCT_NAME_REQUIRED", "Product name is required", "ERROR", raw(row.rawMetadata, "Item Name", "Product Name", "Stock Item"), "Map the stock item/product name column."));
            }
            if (row.movementType == null || row.movementType.isBlank()) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "movementType", "MOVEMENT_TYPE_REQUIRED", "Movement type is required", "ERROR", raw(row.rawMetadata, "Voucher Type", "Invoice Type", "movementType"), "Map voucher type or movement type."));
            } else {
                try {
                    parsedType = DomainEnums.MovementType.valueOf(row.movementType);
                } catch (IllegalArgumentException ex) {
                    rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "movementType", "BAD_MOVEMENT_TYPE", "Movement type is invalid", "ERROR", row.movementType, "Use PURCHASE, SALE, RETURN_IN, RETURN_OUT, ADJUSTMENT, or OPENING_BALANCE."));
                }
            }
            var invoiceNumber = invoiceNumber(row.rawMetadata);
            if (parsedType != null && parsedType != DomainEnums.MovementType.ADJUSTMENT && parsedType != DomainEnums.MovementType.OPENING_BALANCE && (invoiceNumber == null || invoiceNumber.isBlank())) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "invoiceNumber", "INVOICE_NUMBER_REQUIRED", "Voucher/invoice number is required", "ERROR", raw(row.rawMetadata, "Voucher Number", "Voucher No", "Invoice Number"), "Map voucher number, bill number, or invoice number."));
            }
            if (invoiceNumber != null && row.movementDate != null
                && (parsedType == DomainEnums.MovementType.SALE || parsedType == DomainEnums.MovementType.PURCHASE)) {
                var key = parsedType + "|" + invoiceKey(row, parsedType == DomainEnums.MovementType.PURCHASE);
                var prior = seenInvoiceRows.putIfAbsent(key, row);
                if (prior != null && (!Objects.equals(prior.movementDate, row.movementDate)
                    || !Objects.equals(normalizedValue(raw(prior.rawMetadata, "partyName", "Party Name", "Customer", "Supplier", "Ledger Name")),
                        normalizedValue(raw(row.rawMetadata, "partyName", "Party Name", "Customer", "Supplier", "Ledger Name"))))) {
                    rowErrors.add(error(tenantId, batchId, row.rowNumber, "invoiceNumber", "DUPLICATE_INVOICE",
                        "Invoice number is reused with conflicting dates or parties in this financial year"));
                }
            }
            if (parsedType != null && invoiceNumber != null && existingInvoiceConflict(tenantId, parsedType, invoiceNumber, row)) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "invoiceNumber", "DUPLICATE_INVOICE", parsedType + " invoice already exists: " + invoiceNumber, "ERROR", invoiceNumber, "Use a new invoice number or remove rows already imported."));
            }
            var partyName = raw(row.rawMetadata, "partyName", "Party Name", "Customer", "Supplier", "Ledger Name");
            if (parsedType != null
                && (parsedType == DomainEnums.MovementType.SALE || parsedType == DomainEnums.MovementType.PURCHASE)
                && (partyName == null || partyName.isBlank())) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "partyName", "PARTY_REQUIRED", "Customer/supplier is required for sales and purchase vouchers", "ERROR", partyName, "Map party/customer/supplier name from the voucher export."));
            }
            if (row.movementDate == null) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "movementDate", "INVOICE_DATE_REQUIRED", "Voucher/invoice date is required or invalid", "ERROR", raw(row.rawMetadata, "Voucher Date", "Invoice Date", "Date"), "Use YYYY-MM-DD or Tally YYYYMMDD date format."));
            } else if (row.movementDate.isBefore(LocalDate.now(ZoneOffset.UTC).minusYears(2))) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "movementDate", "BACKDATED_VOUCHER", "Voucher is more than two years old", "WARNING", row.movementDate.toString(), "Review whether this historical voucher should be imported."));
            }
            if (cancelled) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "status", "CANCELLED_VOUCHER_SKIPPED", "Cancelled/deleted Tally voucher will be skipped during commit", "WARNING", raw(row.rawMetadata, "Cancelled", "ISCANCELLED", "ISDELETED"), "No action needed unless this voucher should be active."));
            }
            if (row.quantity == null || row.quantity.compareTo(BigDecimal.ZERO) == 0) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "quantity", "INVALID_QUANTITY", "Quantity must be non-zero", "ERROR", raw(row.rawMetadata, "Qty", "Quantity", "Billed Quantity", "Actual Quantity"), "Use a positive numeric quantity."));
            } else if (parsedType != null && parsedType != DomainEnums.MovementType.ADJUSTMENT && row.quantity.compareTo(BigDecimal.ZERO) < 0) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "quantity", "INVALID_QUANTITY", "Quantity must be positive for " + parsedType, "ERROR", row.quantity.toPlainString(), "Use a positive quantity; movement direction comes from voucher type."));
            }
            addRateValidationIssues(batch, tenantId, batchId, row, parsedType, rowErrors);
            if (parsedType == DomainEnums.MovementType.RETURN_OUT && batch.sourceType == DomainEnums.SourceType.TALLY_XML) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "movementType", "PURCHASE_RETURN_REQUIRES_REVIEW", "Tally purchase return will be imported as stock return-out", "WARNING", raw(row.rawMetadata, "Voucher Type"), "Review the debit note in Tally; StockPilot imports only its inventory movement."));
            }
            if (row.warehouseName != null && !row.warehouseName.isBlank() && warehouses.findByTenantIdAndNameIgnoreCase(tenantId, row.warehouseName).isEmpty()) {
                if (canAutoCreateImportWarehouse(batch)) {
                    addAutoCreateWarehouseWarning(tenantId, batchId, row.rowNumber, row.warehouseName, warnedAutoCreateWarehouses, rowErrors);
                } else {
                    rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "warehouseName", "UNKNOWN_WAREHOUSE", "Unknown warehouse: " + row.warehouseName, "ERROR", row.warehouseName, "Create the warehouse first or correct the godown name."));
                }
            }
            rowErrors = deduplicateIssues(rowErrors);
            if (!rowErrors.isEmpty()) {
                if (hasErrors(rowErrors)) {
                    invalidRows.add(row.rowNumber);
                    errorCount += errorSeverityCount(rowErrors);
                }
                errors.saveAll(rowErrors);
            }
        }
        var stockImpactIssues = deduplicateIssues(voucherStockImpactIssues(
            tenantId,
            batchId,
            batch,
            movementRows,
            voucherStockImpactMode,
            confirmStockMovementsAfterSnapshot,
            latestSnapshot
        ));
        if (!stockImpactIssues.isEmpty()) {
            if (hasErrors(stockImpactIssues)) {
                invalidRows.add(0);
                errorCount += errorSeverityCount(stockImpactIssues);
            }
            errors.saveAll(stockImpactIssues);
        }
        if (!stockLedger.negativeStockAllowed(tenantId)) {
            var stockRelevantRows = movementRows.stream()
                .filter(row -> shouldCreateVoucherStockMovement(voucherStockImpactMode, latestSnapshot.map(LatestStockSnapshot::date).orElse(null), row))
                .toList();
            var stockErrors = deduplicateIssues(projectedStockErrors(tenantId, batchId, productRows, stockRelevantRows));
            if (!stockErrors.isEmpty()) {
                stockErrors.forEach(error -> invalidRows.add(error.rowNumber));
                errorCount += errorSeverityCount(stockErrors);
                errors.saveAll(stockErrors);
            }
        }
        batch.validCount = (int) mappedRowNumbers.stream().filter(row -> !invalidRows.contains(row)).count();
        batch.errorCount = errorCount;
        batch.status = errorCount > 0 ? DomainEnums.ImportStatus.VALIDATION_FAILED : DomainEnums.ImportStatus.VALIDATED;
        var saved = batches.save(batch);
        auditService.logCurrent(errorCount > 0 ? "IMPORT_VALIDATION_FAILED" : "IMPORT_VALIDATED", "ImportBatch", batch.id, Map.of(
            "errors", errorCount,
            "warnings", warningCount(tenantId, batchId),
            "negativeStockPolicy", negativeStockPolicy.name(),
            "voucherStockImpactMode", voucherStockImpactMode.name()
        ));
        return saved;
    }

    public List<ImportError> errors(UUID batchId) {
        get(batchId);
        return errors.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(TenantContext.tenantId(), batchId);
    }

    private boolean canAutoCreateImportWarehouse(ImportBatch batch) {
        return batch.sourceType == DomainEnums.SourceType.TALLY
            || batch.sourceType == DomainEnums.SourceType.TALLY_XML
            || batch.sourceType == DomainEnums.SourceType.TALLY_EXCEL;
    }

    private void addAutoCreateWarehouseWarning(
        UUID tenantId,
        UUID batchId,
        int rowNumber,
        String warehouseName,
        Set<String> warnedWarehouses,
        List<ImportError> rowErrors
    ) {
        var key = normalizedValue(warehouseName);
        if (key == null || !warnedWarehouses.add(key)) {
            return;
        }
        rowErrors.add(error(
            tenantId,
            batchId,
            rowNumber,
            "WAREHOUSE",
            "warehouseName",
            "WAREHOUSE_WILL_BE_CREATED",
            "Tally godown will be created as warehouse: " + warehouseName,
            "WARNING",
            warehouseName,
            "StockPilot will create this warehouse during commit and use it for stock ledger movements."
        ));
    }

    private Warehouse warehouseForCommitRow(UUID tenantId, UUID batchId, ImportBatch batch, String warehouseName, Warehouse defaultWarehouse) {
        if (warehouseName == null || warehouseName.isBlank()) {
            return defaultWarehouse;
        }
        var existing = warehouses.findByTenantIdAndNameIgnoreCase(tenantId, warehouseName);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!canAutoCreateImportWarehouse(batch)) {
            return defaultWarehouse;
        }
        var warehouse = new Warehouse();
        warehouse.tenantId = tenantId;
        warehouse.name = warehouseName.trim();
        warehouse.code = warehouseCode(warehouse.name);
        var saved = warehouses.save(warehouse);
        recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.WAREHOUSE, saved.id, true, Map.of("name", saved.name));
        return saved;
    }

    private String warehouseCode(String name) {
        var code = name == null ? "" : name.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (code.isBlank()) {
            return "WH";
        }
        return code.length() <= 40 ? code : code.substring(0, 40);
    }

    @Transactional
    public Map<String, Object> commit(UUID batchId) {
        return commit(batchId, null, null, false);
    }

    @Transactional
    public Map<String, Object> commit(UUID batchId, DomainEnums.NegativeStockImportPolicy requestedPolicy) {
        return commit(batchId, requestedPolicy, null, false);
    }

    @Transactional
    public Map<String, Object> commit(
        UUID batchId,
        DomainEnums.NegativeStockImportPolicy requestedPolicy,
        DomainEnums.VoucherStockImpactMode requestedVoucherMode,
        boolean confirmStockMovementsAfterSnapshot
    ) {
        var existing = get(batchId);
        if (existing.status == DomainEnums.ImportStatus.COMMITTED) {
            throw ApiErrors.conflict("Import batch is already committed");
        }
        var batch = validate(batchId, requestedPolicy, requestedVoucherMode, confirmStockMovementsAfterSnapshot);
        var negativeStockPolicy = resolveNegativeStockPolicy(batch, null);
        if (batch.errorCount > 0) {
            throw ApiErrors.badRequest("Fix validation errors before committing import");
        }
        var tenantId = TenantContext.tenantId();
        var voucherStockImpactMode = resolveVoucherStockImpactMode(batch, null);
        var latestSnapshot = latestCommittedStockSnapshot(tenantId);
        var latestSnapshotDate = latestSnapshot.map(LatestStockSnapshot::date).orElse(null);
        var tenantAllowsNegativeStock = stockLedger.negativeStockAllowed(tenantId);
        var validationIssues = errors.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId);
        int purchaseRatesDerivedFromAmount = issueRowCount(validationIssues, "PURCHASE_RATE_DERIVED_FROM_AMOUNT");
        int zeroCostPurchaseItemsImported = issueRowCount(validationIssues, "ZERO_COST_PURCHASE_ITEM");
        int purchaseRatesSignNormalized = issueRowCount(validationIssues, "PURCHASE_RATE_SIGN_NORMALIZED");
        int invalidPurchaseRateRowsBlocked = issueRowCount(validationIssues, "INVALID_PURCHASE_RATE");
        int committedProducts = 0;
        int productsCreated = 0;
        int productsMatchedExisting = 0;
        int productsUpdated = 0;
        int committedParties = 0;
        int openingBalanceMovementsCreated = 0;
        int voucherStockMovementsCreated = 0;
        int stockSnapshotRowsProcessed = 0;
        int stockSnapshotRowsUnchanged = 0;
        int stockSnapshotAdjustmentsCreated = 0;
        int stockSnapshotPositiveAdjustments = 0;
        int stockSnapshotNegativeAdjustments = 0;
        int negativeStockRowsImported = 0;
        int negativeStockRowsSkipped = 0;
        int salesInvoicesCreated = 0;
        int salesInvoiceItemsCreated = 0;
        int purchaseInvoicesCreated = 0;
        int purchaseInvoiceItemsCreated = 0;
        int stockMovementsSkippedDueToInvoiceOnly = 0;
        int stockMovementsSkippedBeforeSnapshotDate = 0;
        var negativeStockAllowed = stockLedger.negativeStockAllowed(tenantId);
        var commitProjection = new HashMap<String, BigDecimal>();
        batch.status = DomainEnums.ImportStatus.COMMITTING;
        batches.save(batch);
        var defaultWarehouseExisting = warehouses.findByTenantIdAndNameIgnoreCase(tenantId, "Main Godown");
        var defaultWarehouse = defaultWarehouseExisting.orElseGet(() -> {
            var warehouse = new Warehouse();
            warehouse.tenantId = tenantId;
            warehouse.name = "Main Godown";
            warehouse.code = "MAIN";
            return warehouses.save(warehouse);
        });
        if (defaultWarehouseExisting.isEmpty()) {
            recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.WAREHOUSE, defaultWarehouse.id, true, Map.of("name", defaultWarehouse.name));
        }
        for (var row : stagingProducts.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId)) {
            if (row.committed) {
                continue;
            }
            var existingProduct = findExistingProduct(tenantId, row);
            var categoryWasMissing = row.category != null && !row.category.isBlank() && categories.findByTenantIdAndNameIgnoreCase(tenantId, row.category).isEmpty();
            var unitWasMissing = row.unitCode != null && !row.unitCode.isBlank() && units.findByTenantIdAndCodeIgnoreCase(tenantId, row.unitCode).isEmpty();
            var product = findOrCreateProduct(tenantId, row);
            if (existingProduct.isEmpty()) {
                recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.PRODUCT, product.id, true, Map.of("name", product.name));
                productsCreated++;
            } else {
                recordUpdatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.PRODUCT, product.id, Map.of("name", product.name));
                productsMatchedExisting++;
                productsUpdated++;
            }
            if (categoryWasMissing && product.categoryId != null) {
                recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.CATEGORY, product.categoryId, true, Map.of("name", row.category));
            }
            if (unitWasMissing && product.baseUnitId != null) {
                recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.UNIT, product.baseUnitId, true, Map.of("code", row.unitCode));
            }
            if (row.barcode != null && !row.barcode.isBlank() && barcodes.findByTenantIdAndBarcode(tenantId, row.barcode).isEmpty()) {
                var barcode = new ProductBarcode();
                barcode.tenantId = tenantId;
                barcode.productId = product.id;
                barcode.barcode = row.barcode;
                barcodes.save(barcode);
            }
            var warehouse = warehouseForCommitRow(tenantId, batchId, batch, row.warehouseName, defaultWarehouse);
            if (isStockSnapshotRow(batch, row)) {
                stockSnapshotRowsProcessed++;
                var snapshot = applyStockSnapshot(
                    tenantId,
                    batchId,
                    row,
                    product,
                    warehouse,
                    commitProjection,
                    negativeStockPolicy,
                    tenantAllowsNegativeStock
                );
                if (snapshot.unchanged()) {
                    stockSnapshotRowsUnchanged++;
                }
                if (snapshot.movementCreated()) {
                    stockSnapshotAdjustmentsCreated++;
                    if (snapshot.positiveAdjustment()) {
                        stockSnapshotPositiveAdjustments++;
                    }
                    if (snapshot.negativeAdjustment()) {
                        stockSnapshotNegativeAdjustments++;
                    }
                }
                if (snapshot.negativeImported()) {
                    negativeStockRowsImported++;
                }
                if (snapshot.negativeSkipped()) {
                    negativeStockRowsSkipped++;
                }
            } else if (row.openingStock != null && row.openingStock.compareTo(BigDecimal.ZERO) > 0) {
                var projectionKey = stockProjectionKey(product.name, warehouse.name);
                stockLedger.lockStockKey(tenantId, product.id, warehouse.id);
                var projectedStock = commitProjection.computeIfAbsent(projectionKey, ignored -> stockLedger.currentStock(tenantId, product.id, warehouse.id));
                var movement = stockLedger.createMovement(tenantId, product.id, warehouse.id, DomainEnums.MovementType.OPENING_BALANCE, row.openingStock, product.baseUnitId, nvl(row.purchasePrice), "IMPORT_BATCH", batchId, Instant.now(), "Opening stock import");
                recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.STOCK_MOVEMENT, movement.id, true, stockMovementEffect(movement));
                commitProjection.put(projectionKey, projectedStock.add(row.openingStock));
                openingBalanceMovementsCreated++;
            } else if (row.openingStock != null && row.openingStock.compareTo(BigDecimal.ZERO) < 0) {
                if (negativeStockPolicy == DomainEnums.NegativeStockImportPolicy.IMPORT_AS_IS) {
                    if (!tenantAllowsNegativeStock) {
                        throw ApiErrors.badRequest("Tenant must allow negative stock before importing negative opening stock");
                    }
                    var projectionKey = stockProjectionKey(product.name, warehouse.name);
                    stockLedger.lockStockKey(tenantId, product.id, warehouse.id);
                    var projectedStock = commitProjection.computeIfAbsent(projectionKey, ignored -> stockLedger.currentStock(tenantId, product.id, warehouse.id));
                    var movement = stockLedger.createImportOpeningBalanceMovement(tenantId, product.id, warehouse.id, row.openingStock, product.baseUnitId, nvl(row.purchasePrice), batchId, Instant.now(), "Negative opening stock imported from Tally report");
                    recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.STOCK_MOVEMENT, movement.id, true, stockMovementEffect(movement));
                    commitProjection.put(projectionKey, projectedStock.add(row.openingStock));
                    openingBalanceMovementsCreated++;
                    negativeStockRowsImported++;
                } else if (negativeStockPolicy == DomainEnums.NegativeStockImportPolicy.SKIP_STOCK_MOVEMENT) {
                    negativeStockRowsSkipped++;
                }
            }
            row.committed = true;
            stagingProducts.save(row);
            committedProducts++;
        }
        for (var customer : stagingCustomers.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId)) {
            if (customer.committed || customer.name == null || customer.name.isBlank()) {
                continue;
            }
            var existingCustomer = findExistingCustomer(tenantId, customer);
            var created = existingCustomer.orElseGet(() -> {
                var c = new Customer();
                c.tenantId = tenantId;
                c.name = customer.name;
                c.phone = customer.phone;
                c.email = customer.email;
                c.gstin = customer.gstin;
                return customers.save(c);
            });
            if (existingCustomer.isEmpty()) {
                recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.CUSTOMER, created.id, true, Map.of("name", created.name));
            }
            customer.committed = true;
            stagingCustomers.save(customer);
            committedParties++;
        }
        for (var supplier : stagingSuppliers.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId)) {
            if (supplier.committed || supplier.name == null || supplier.name.isBlank()) {
                continue;
            }
            var existingSupplier = findExistingSupplier(tenantId, supplier);
            var created = existingSupplier.orElseGet(() -> {
                var s = new Supplier();
                s.tenantId = tenantId;
                s.name = supplier.name;
                s.phone = supplier.phone;
                s.email = supplier.email;
                s.gstin = supplier.gstin;
                return suppliers.save(s);
            });
            if (existingSupplier.isEmpty()) {
                recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.SUPPLIER, created.id, true, Map.of("name", created.name));
            }
            supplier.committed = true;
            stagingSuppliers.save(supplier);
            committedParties++;
        }
        var movementRows = stagingStockMovements.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId);
        int ledgerLinesSkipped = tallyLedgerLinesSkipped(movementRows);
        var salesByInvoice = new HashMap<String, SalesInvoice>();
        var purchaseByInvoice = new HashMap<String, PurchaseInvoice>();
        for (var row : sortedMovementRows(movementRows)) {
            if (row.committed) {
                continue;
            }
            if (cancelledVoucher(row.rawMetadata)) {
                row.committed = true;
                stagingStockMovements.save(row);
                continue;
            }
            var existingProduct = products.findByTenantIdAndNameIgnoreCase(tenantId, row.productName);
            var product = existingProduct.orElseGet(() -> {
                var staging = new StagingProduct();
                staging.tenantId = tenantId;
                staging.importBatchId = batchId;
                staging.rowNumber = row.rowNumber;
                staging.productName = row.productName;
                staging.unitCode = "PCS";
                staging.purchasePrice = nvl(row.rate);
                return findOrCreateProduct(tenantId, staging);
            });
            if (existingProduct.isEmpty()) {
                recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.PRODUCT, product.id, true, Map.of("name", product.name));
            }
            var warehouse = warehouseForCommitRow(tenantId, batchId, batch, row.warehouseName, defaultWarehouse);
            var type = DomainEnums.MovementType.valueOf(row.movementType);
            if (type == DomainEnums.MovementType.PURCHASE && row.rate != null && row.rate.compareTo(BigDecimal.ZERO) > 0) {
                product.defaultPurchasePrice = row.rate;
                products.save(product);
            }
            var referenceType = "IMPORT_BATCH";
            var referenceId = batchId;
            var invoiceNumber = invoiceNumber(row.rawMetadata);
            if (type == DomainEnums.MovementType.SALE && invoiceNumber != null) {
                var invoice = salesByInvoice.computeIfAbsent(invoiceKey(row, false), ignored -> createImportedSalesInvoice(tenantId, row, warehouse.id));
                recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.SALES_INVOICE, invoice.id, false, Map.of("invoiceNumber", invoice.invoiceNumber));
                addImportedSalesItem(tenantId, invoice, product, row, warehouse.id);
                salesInvoiceItemsCreated++;
                referenceType = "SALES_INVOICE";
                referenceId = invoice.id;
                salesInvoicesCreated = salesByInvoice.size();
            } else if (type == DomainEnums.MovementType.PURCHASE && invoiceNumber != null) {
                var invoice = purchaseByInvoice.computeIfAbsent(invoiceKey(row, true), ignored -> createImportedPurchaseInvoice(tenantId, row, warehouse.id));
                recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.PURCHASE_INVOICE, invoice.id, false, Map.of("invoiceNumber", invoice.invoiceNumber));
                addImportedPurchaseItem(tenantId, invoice, product, row);
                purchaseInvoiceItemsCreated++;
                referenceType = "PURCHASE_INVOICE";
                referenceId = invoice.id;
                purchaseInvoicesCreated = purchaseByInvoice.size();
            }
            if (!shouldCreateVoucherStockMovement(voucherStockImpactMode, latestSnapshotDate, row)) {
                if (voucherStockImpactMode == DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY) {
                    stockMovementsSkippedDueToInvoiceOnly++;
                } else {
                    stockMovementsSkippedBeforeSnapshotDate++;
                }
                row.committed = true;
                stagingStockMovements.save(row);
                continue;
            }
            var projectionKey = stockProjectionKey(product.name, warehouse.name);
            stockLedger.lockStockKey(tenantId, product.id, warehouse.id);
            var projectedStock = commitProjection.computeIfAbsent(projectionKey, ignored -> stockLedger.currentStock(tenantId, product.id, warehouse.id));
            var signedQuantity = StockLedgerService.signedQuantity(type, stockLedger.toBaseQuantity(tenantId, product, product.baseUnitId, row.quantity));
            if (!negativeStockAllowed && projectedStock.add(signedQuantity).compareTo(BigDecimal.ZERO) < 0) {
                throw ApiErrors.badRequest("Import row " + row.rowNumber + " would make stock negative for " + product.name + " in " + warehouse.name);
            }
            var movement = stockLedger.createMovement(
                tenantId,
                product.id,
                warehouse.id,
                type,
                row.quantity,
                product.baseUnitId,
                nvl(row.rate),
                referenceType,
                referenceId,
                row.movementDate == null ? Instant.now() : row.movementDate.atStartOfDay().toInstant(ZoneOffset.UTC),
                "Imported " + row.movementType.toLowerCase(Locale.ROOT).replace('_', ' ')
            );
            recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.STOCK_MOVEMENT, movement.id, true, stockMovementEffect(movement));
            commitProjection.put(projectionKey, projectedStock.add(signedQuantity));
            row.committed = true;
            stagingStockMovements.save(row);
            voucherStockMovementsCreated++;
        }
        batch.status = DomainEnums.ImportStatus.COMMITTED;
        batch.committedAt = Instant.now();
        if (batch.importPurpose == DomainEnums.ImportPurpose.STOCK_SNAPSHOT) {
            batch.mappingJson.putIfAbsent("snapshotDate", batch.committedAt.atZone(ZoneOffset.UTC).toLocalDate().toString());
        }
        putSnapshotSummary(
            batch,
            stockSnapshotRowsProcessed,
            stockSnapshotRowsUnchanged,
            stockSnapshotAdjustmentsCreated,
            stockSnapshotPositiveAdjustments,
            stockSnapshotNegativeAdjustments
        );
        putVoucherStockImpactSummary(
            batch,
            voucherStockImpactMode,
            latestSnapshotDate,
            voucherStockMovementsCreated,
            stockMovementsSkippedDueToInvoiceOnly,
            stockMovementsSkippedBeforeSnapshotDate,
            salesInvoiceItemsCreated,
            purchaseInvoiceItemsCreated
        );
        batches.save(batch);
        var totalStockMovementsCreated = openingBalanceMovementsCreated + voucherStockMovementsCreated + stockSnapshotAdjustmentsCreated;
        auditService.logCurrent("IMPORT_COMMITTED", "ImportBatch", batch.id, Map.ofEntries(
            Map.entry("products", committedProducts),
            Map.entry("productsCreated", productsCreated),
            Map.entry("productsMatchedExisting", productsMatchedExisting),
            Map.entry("parties", committedParties),
            Map.entry("movements", totalStockMovementsCreated),
            Map.entry("openingBalanceMovements", openingBalanceMovementsCreated),
            Map.entry("voucherStockMovements", voucherStockMovementsCreated),
            Map.entry("stockSnapshotRowsProcessed", stockSnapshotRowsProcessed),
            Map.entry("stockSnapshotRowsUnchanged", stockSnapshotRowsUnchanged),
            Map.entry("stockSnapshotAdjustmentsCreated", stockSnapshotAdjustmentsCreated),
            Map.entry("negativeStockPolicy", negativeStockPolicy.name()),
            Map.entry("negativeStockRowsImported", negativeStockRowsImported),
            Map.entry("negativeStockRowsSkipped", negativeStockRowsSkipped),
            Map.entry("salesInvoices", salesInvoicesCreated),
            Map.entry("purchaseInvoices", purchaseInvoicesCreated),
            Map.entry("purchaseInvoiceItems", purchaseInvoiceItemsCreated),
            Map.entry("purchaseRatesDerivedFromAmount", purchaseRatesDerivedFromAmount),
            Map.entry("zeroCostPurchaseItemsImported", zeroCostPurchaseItemsImported),
            Map.entry("purchaseRatesSignNormalized", purchaseRatesSignNormalized),
            Map.entry("ledgerLinesSkipped", ledgerLinesSkipped),
            Map.entry("voucherStockImpactMode", voucherStockImpactMode.name()),
            Map.entry("stockMovementsSkippedDueToInvoiceOnly", stockMovementsSkippedDueToInvoiceOnly),
            Map.entry("stockMovementsSkippedBeforeSnapshotDate", stockMovementsSkippedBeforeSnapshotDate)
        ));
        if (voucherStockImpactMode == DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_AND_STOCK_MOVEMENTS
            && latestSnapshot.isPresent()
            && confirmStockMovementsAfterSnapshot) {
            auditService.logCurrent("VOUCHER_STOCK_IMPACT_OVERRIDE", "ImportBatch", batch.id, Map.of(
                "voucherStockImpactMode", voucherStockImpactMode.name(),
                "latestSnapshotDate", latestSnapshotDate == null ? "" : latestSnapshotDate.toString(),
                "warning", DOUBLE_COUNT_WARNING
            ));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("batchId", batch.id);
        result.put("committedProducts", committedProducts);
        result.put("productsCreated", productsCreated);
        result.put("productsMatchedExisting", productsMatchedExisting);
        result.put("productsUpdated", productsUpdated);
        result.put("committedParties", committedParties);
        result.put("committedMovements", totalStockMovementsCreated);
        result.put("stockMovementsCreated", totalStockMovementsCreated);
        result.put("openingBalanceMovementsCreated", openingBalanceMovementsCreated);
        result.put("voucherStockMovementsCreated", voucherStockMovementsCreated);
        result.put("stockSnapshotRowsProcessed", stockSnapshotRowsProcessed);
        result.put("stockSnapshotRowsUnchanged", stockSnapshotRowsUnchanged);
        result.put("stockSnapshotAdjustmentsCreated", stockSnapshotAdjustmentsCreated);
        result.put("stockSnapshotPositiveAdjustments", stockSnapshotPositiveAdjustments);
        result.put("stockSnapshotNegativeAdjustments", stockSnapshotNegativeAdjustments);
        result.put("negativeStockRowsFound", negativeStockRowsFound(tenantId, batchId));
        result.put("negativeStockRowsImported", negativeStockRowsImported);
        result.put("negativeStockRowsSkipped", negativeStockRowsSkipped);
        result.put("salesInvoicesCreated", salesInvoicesCreated);
        result.put("salesInvoiceItemsCreated", salesInvoiceItemsCreated);
        result.put("purchaseInvoicesCreated", purchaseInvoicesCreated);
        result.put("purchaseInvoiceItemsCreated", purchaseInvoiceItemsCreated);
        result.put("purchaseRatesDerivedFromAmount", purchaseRatesDerivedFromAmount);
        result.put("zeroCostPurchaseItemsImported", zeroCostPurchaseItemsImported);
        result.put("purchaseRatesSignNormalized", purchaseRatesSignNormalized);
        result.put("invalidPurchaseRateRowsBlocked", invalidPurchaseRateRowsBlocked);
        result.put("ledgerLinesSkipped", ledgerLinesSkipped);
        result.put("voucherStockImpactMode", voucherStockImpactMode.name());
        result.put("stockMovementsSkippedDueToInvoiceOnly", stockMovementsSkippedDueToInvoiceOnly);
        result.put("stockMovementsSkippedBeforeSnapshotDate", stockMovementsSkippedBeforeSnapshotDate);
        result.put("latestSnapshotDate", latestSnapshotDate == null ? "" : latestSnapshotDate.toString());
        result.put("warningAboutDoubleCounting", latestSnapshot.isPresent() ? DOUBLE_COUNT_WARNING : "");
        return result;
    }

    private StockSnapshotCommitResult applyStockSnapshot(
        UUID tenantId,
        UUID batchId,
        StagingProduct row,
        Product product,
        Warehouse warehouse,
        Map<String, BigDecimal> commitProjection,
        DomainEnums.NegativeStockImportPolicy negativeStockPolicy,
        boolean tenantAllowsNegativeStock
    ) {
        if (row.openingStock == null) {
            return StockSnapshotCommitResult.noChange();
        }
        var importedQuantity = row.openingStock;
        if (importedQuantity.compareTo(BigDecimal.ZERO) < 0) {
            if (negativeStockPolicy == DomainEnums.NegativeStockImportPolicy.SKIP_STOCK_MOVEMENT) {
            return StockSnapshotCommitResult.skippedNegativeStock();
            }
            if (negativeStockPolicy == DomainEnums.NegativeStockImportPolicy.BLOCK) {
                throw ApiErrors.badRequest("Negative stock quantity found. Choose an import resolution policy or fix the source data.");
            }
            if (!tenantAllowsNegativeStock) {
                throw ApiErrors.badRequest("Tenant must allow negative stock before importing negative stock snapshot");
            }
        }
        var projectionKey = stockProjectionKey(product.id, warehouse.id);
        stockLedger.lockStockKey(tenantId, product.id, warehouse.id);
        var currentStock = commitProjection.computeIfAbsent(projectionKey, ignored -> stockLedger.currentStock(tenantId, product.id, warehouse.id));
        var delta = importedQuantity.subtract(currentStock);
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            return StockSnapshotCommitResult.noChange();
        }
        var movement = stockLedger.createMovement(
            tenantId,
            product.id,
            warehouse.id,
            DomainEnums.MovementType.ADJUSTMENT,
            delta,
            product.baseUnitId,
            nvl(row.purchasePrice),
            "IMPORT_BATCH",
            batchId,
            Instant.now(),
            "Stock snapshot adjustment from imported closing stock"
        );
        recordCreatedEffect(tenantId, batchId, DomainEnums.ImportEffectEntityType.STOCK_MOVEMENT, movement.id, true, stockMovementEffect(movement));
        commitProjection.put(projectionKey, importedQuantity);
        return new StockSnapshotCommitResult(
            true,
            delta.compareTo(BigDecimal.ZERO) > 0,
            delta.compareTo(BigDecimal.ZERO) < 0,
            false,
            importedQuantity.compareTo(BigDecimal.ZERO) < 0,
            false
        );
    }

    private record StockSnapshotCommitResult(
        boolean movementCreated,
        boolean positiveAdjustment,
        boolean negativeAdjustment,
        boolean unchanged,
        boolean negativeImported,
        boolean negativeSkipped
    ) {
        static StockSnapshotCommitResult noChange() {
            return new StockSnapshotCommitResult(false, false, false, true, false, false);
        }

        static StockSnapshotCommitResult skippedNegativeStock() {
            return new StockSnapshotCommitResult(false, false, false, false, false, true);
        }
    }

    private SalesInvoice createImportedSalesInvoice(UUID tenantId, StagingStockMovement row, UUID warehouseId) {
        var invoice = new SalesInvoice();
        invoice.tenantId = tenantId;
        invoice.invoiceNumber = invoiceNumber(row.rawMetadata);
        invoice.invoiceDate = row.movementDate == null ? LocalDate.now(ZoneOffset.UTC) : row.movementDate;
        invoice.warehouseId = warehouseId;
        var partyName = raw(row.rawMetadata, "partyName", "Party Name", "Customer", "Ledger Name");
        if (partyName != null && !partyName.isBlank()) {
            customers.findByTenantIdAndNameIgnoreCase(tenantId, partyName).ifPresent(customer -> invoice.customerId = customer.id);
        }
        invoice.subtotal = BigDecimal.ZERO;
        invoice.taxAmount = BigDecimal.ZERO;
        invoice.discountAmount = BigDecimal.ZERO;
        invoice.totalAmount = BigDecimal.ZERO;
        return salesInvoices.save(invoice);
    }

    private void addImportedSalesItem(UUID tenantId, SalesInvoice invoice, Product product, StagingStockMovement row, UUID warehouseId) {
        var quantity = nvl(row.quantity).abs();
        var rate = nvl(row.rate);
        var item = new SalesInvoiceItem();
        item.tenantId = tenantId;
        item.salesInvoiceId = invoice.id;
        item.warehouseId = warehouseId;
        item.productId = product.id;
        item.quantity = quantity;
        item.unitId = product.baseUnitId;
        item.rate = rate;
        item.costRate = nvl(product.defaultPurchasePrice);
        item.taxPercentage = BigDecimal.ZERO;
        item.taxAmount = BigDecimal.ZERO;
        item.discountAmount = BigDecimal.ZERO;
        item.lineTotal = quantity.multiply(rate);
        salesInvoiceItems.save(item);
        invoice.subtotal = nvl(invoice.subtotal).add(item.lineTotal);
        invoice.totalAmount = nvl(invoice.totalAmount).add(item.lineTotal);
        salesInvoices.save(invoice);
    }

    private PurchaseInvoice createImportedPurchaseInvoice(UUID tenantId, StagingStockMovement row, UUID warehouseId) {
        var invoice = new PurchaseInvoice();
        invoice.tenantId = tenantId;
        invoice.invoiceNumber = invoiceNumber(row.rawMetadata);
        invoice.invoiceDate = row.movementDate == null ? LocalDate.now(ZoneOffset.UTC) : row.movementDate;
        invoice.warehouseId = warehouseId;
        var partyName = raw(row.rawMetadata, "partyName", "Party Name", "Supplier", "Ledger Name");
        if (partyName != null && !partyName.isBlank()) {
            suppliers.findByTenantIdAndNameIgnoreCase(tenantId, partyName).ifPresent(supplier -> invoice.supplierId = supplier.id);
        }
        invoice.subtotal = BigDecimal.ZERO;
        invoice.taxAmount = BigDecimal.ZERO;
        invoice.totalAmount = BigDecimal.ZERO;
        return purchaseInvoices.save(invoice);
    }

    private void addImportedPurchaseItem(UUID tenantId, PurchaseInvoice invoice, Product product, StagingStockMovement row) {
        var quantity = nvl(row.quantity).abs();
        var rate = nvl(row.rate);
        var item = new PurchaseInvoiceItem();
        item.tenantId = tenantId;
        item.purchaseInvoiceId = invoice.id;
        item.productId = product.id;
        item.quantity = quantity;
        item.unitId = product.baseUnitId;
        item.rate = rate;
        item.taxPercentage = BigDecimal.ZERO;
        item.taxAmount = BigDecimal.ZERO;
        item.lineTotal = quantity.multiply(rate);
        purchaseInvoiceItems.save(item);
        invoice.subtotal = nvl(invoice.subtotal).add(item.lineTotal);
        invoice.totalAmount = nvl(invoice.totalAmount).add(item.lineTotal);
        purchaseInvoices.save(invoice);
    }

    public List<ImportMappingTemplate> templates() {
        return templates.findByTenantIdOrderByNameAsc(TenantContext.tenantId());
    }

    public Map<String, Object> reconciliation(UUID batchId) {
        var tenantId = TenantContext.tenantId();
        var batch = get(batchId);
        var file = files.findFirstByTenantIdAndImportBatchId(tenantId, batchId).orElse(null);
        var effectRows = importEffects(tenantId, batchId);
        var batchErrors = errors.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId);
        var errorRows = batchErrors.stream().filter(error -> "ERROR".equalsIgnoreCase(error.severity)).map(error -> error.rowNumber).collect(Collectors.toSet());
        var warningRows = batchErrors.stream().filter(error -> "WARNING".equalsIgnoreCase(error.severity)).map(error -> error.rowNumber).collect(Collectors.toSet());
        var negativeImportedRows = batchErrors.stream().filter(error -> "NEGATIVE_STOCK_IMPORTED".equals(error.errorCode)).map(error -> error.rowNumber).collect(Collectors.toSet());
        var negativeSkippedRows = batchErrors.stream().filter(error -> "NEGATIVE_STOCK_SKIPPED".equals(error.errorCode)).map(error -> error.rowNumber).collect(Collectors.toSet());
        var movementRows = stagingStockMovements.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId);
        int ledgerLinesSkipped = tallyLedgerLinesSkipped(movementRows);
        var committedMovementRows = movementRows.stream().filter(row -> row.committed).toList();
        var createdProductEffects = effectRows.stream()
            .filter(effect -> effect.entityType == DomainEnums.ImportEffectEntityType.PRODUCT && effect.action == DomainEnums.ImportEffectAction.CREATED)
            .count();
        var updatedProductEffects = effectRows.stream()
            .filter(effect -> effect.entityType == DomainEnums.ImportEffectEntityType.PRODUCT && effect.action == DomainEnums.ImportEffectAction.UPDATED)
            .count();
        var openingBalanceMovements = stagingProducts.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId).stream()
            .filter(row -> row.committed && !isStockSnapshotRow(batch, row) && row.openingStock != null && row.openingStock.compareTo(BigDecimal.ZERO) > 0)
            .count() + (batch.importPurpose == DomainEnums.ImportPurpose.STOCK_SNAPSHOT ? 0 : negativeImportedRows.size());
        var stockSnapshotAdjustmentsCreated = snapshotSummaryLong(batch, "stockSnapshotAdjustmentsCreated");
        var activeCommittedMovementRows = committedMovementRows.stream()
            .filter(row -> !cancelledVoucher(row.rawMetadata))
            .toList();
        var salesRows = activeCommittedMovementRows.stream()
            .filter(row -> DomainEnums.MovementType.SALE.name().equals(row.movementType))
            .map(row -> Optional.ofNullable(invoiceNumber(row.rawMetadata)).orElse("row-" + row.rowNumber))
            .collect(Collectors.toSet())
            .size();
        var purchaseRows = activeCommittedMovementRows.stream()
            .filter(row -> DomainEnums.MovementType.PURCHASE.name().equals(row.movementType))
            .map(row -> Optional.ofNullable(invoiceNumber(row.rawMetadata)).orElse("row-" + row.rowNumber))
            .collect(Collectors.toSet())
            .size();
        var purchaseInvoiceItemRows = activeCommittedMovementRows.stream()
            .filter(row -> DomainEnums.MovementType.PURCHASE.name().equals(row.movementType))
            .count();
        var voucherMovementsCreated = batch.mappingJson != null && batch.mappingJson.containsKey("voucherStockMovementsCreated")
            ? snapshotSummaryLong(batch, "voucherStockMovementsCreated")
            : activeCommittedMovementRows.size();
        var salesInvoiceItemsCreated = batch.mappingJson != null && batch.mappingJson.containsKey("salesInvoiceItemsCreated")
            ? snapshotSummaryLong(batch, "salesInvoiceItemsCreated")
            : activeCommittedMovementRows.stream().filter(row -> DomainEnums.MovementType.SALE.name().equals(row.movementType)).count();
        var storedPurchaseInvoiceItems = batch.mappingJson != null && batch.mappingJson.containsKey("purchaseInvoiceItemsCreated")
            ? snapshotSummaryLong(batch, "purchaseInvoiceItemsCreated")
            : purchaseInvoiceItemRows;
        var voucherStockImpactMode = resolveVoucherStockImpactMode(batch, null);
        var latestSnapshotDate = batch.mappingJson == null ? "" : String.valueOf(batch.mappingJson.getOrDefault("latestSnapshotDate", ""));
        return new LinkedHashMap<>(Map.ofEntries(
            Map.entry("batchId", batch.id),
            Map.entry("status", batch.status.name()),
            Map.entry("sourceType", batch.sourceType.name()),
            Map.entry("fileName", file == null ? batch.originalFileName == null ? "" : batch.originalFileName : file.fileName),
            Map.entry("rowsRead", batch.rowCount),
            Map.entry("rowsStaged", stagedRowCount(tenantId, batchId)),
            Map.entry("rowsWithErrors", errorRows.size()),
            Map.entry("rowsWithWarnings", warningRows.size()),
            Map.entry("committedProducts", stagingProducts.countByTenantIdAndImportBatchIdAndCommittedTrue(tenantId, batchId)),
            Map.entry("productsCreated", createdProductEffects),
            Map.entry("productsMatchedExisting", updatedProductEffects),
            Map.entry("productsUpdated", updatedProductEffects),
            Map.entry("customersCreated", stagingCustomers.countByTenantIdAndImportBatchIdAndCommittedTrue(tenantId, batchId)),
            Map.entry("customersUpdated", 0L),
            Map.entry("suppliersCreated", stagingSuppliers.countByTenantIdAndImportBatchIdAndCommittedTrue(tenantId, batchId)),
            Map.entry("suppliersUpdated", 0L),
            Map.entry("salesInvoicesCreated", salesRows),
            Map.entry("salesInvoiceItemsCreated", salesInvoiceItemsCreated),
            Map.entry("purchaseInvoicesCreated", purchaseRows),
            Map.entry("purchaseInvoiceItemsCreated", storedPurchaseInvoiceItems),
            Map.entry("purchaseRatesDerivedFromAmount", issueRowCount(batchErrors, "PURCHASE_RATE_DERIVED_FROM_AMOUNT")),
            Map.entry("zeroCostPurchaseItemsImported", issueRowCount(batchErrors, "ZERO_COST_PURCHASE_ITEM")),
            Map.entry("purchaseRatesSignNormalized", issueRowCount(batchErrors, "PURCHASE_RATE_SIGN_NORMALIZED")),
            Map.entry("invalidPurchaseRateRowsBlocked", issueRowCount(batchErrors, "INVALID_PURCHASE_RATE")),
            Map.entry("ledgerLinesSkipped", ledgerLinesSkipped),
            Map.entry("stockMovementsCreated", voucherMovementsCreated + openingBalanceMovements + stockSnapshotAdjustmentsCreated),
            Map.entry("openingBalanceMovementsCreated", openingBalanceMovements),
            Map.entry("voucherStockMovementsCreated", voucherMovementsCreated),
            Map.entry("voucherStockImpactMode", voucherStockImpactMode.name()),
            Map.entry("stockMovementsSkippedDueToInvoiceOnly", snapshotSummaryLong(batch, "stockMovementsSkippedDueToInvoiceOnly")),
            Map.entry("stockMovementsSkippedBeforeSnapshotDate", snapshotSummaryLong(batch, "stockMovementsSkippedBeforeSnapshotDate")),
            Map.entry("latestSnapshotDate", latestSnapshotDate),
            Map.entry("warningAboutDoubleCounting", batch.mappingJson == null ? "" : String.valueOf(batch.mappingJson.getOrDefault("warningAboutDoubleCounting", ""))),
            Map.entry("stockSnapshotRowsProcessed", snapshotSummaryLong(batch, "stockSnapshotRowsProcessed")),
            Map.entry("stockSnapshotRowsUnchanged", snapshotSummaryLong(batch, "stockSnapshotRowsUnchanged")),
            Map.entry("stockSnapshotAdjustmentsCreated", stockSnapshotAdjustmentsCreated),
            Map.entry("stockSnapshotPositiveAdjustments", snapshotSummaryLong(batch, "stockSnapshotPositiveAdjustments")),
            Map.entry("stockSnapshotNegativeAdjustments", snapshotSummaryLong(batch, "stockSnapshotNegativeAdjustments")),
            Map.entry("trackedEffects", effectRows.size()),
            Map.entry("rollbackStatus", batch.status == DomainEnums.ImportStatus.ROLLED_BACK ? "ROLLED_BACK" : "NOT_ROLLED_BACK"),
            Map.entry("rolledBackAt", batch.rolledBackAt == null ? "" : batch.rolledBackAt.toString()),
            Map.entry("negativeStockRowsFound", negativeStockRowsFound(tenantId, batchId)),
            Map.entry("negativeStockRowsImported", negativeImportedRows.size()),
            Map.entry("negativeStockRowsSkipped", negativeSkippedRows.size()),
            Map.entry("blockingErrors", errorRows.size()),
            Map.entry("warnings", warningRows.size()),
            Map.entry("skippedRows", warningRows.size()),
            Map.entry("duplicateRows", batchErrors.stream().filter(error -> error.errorCode != null && error.errorCode.startsWith("DUPLICATE")).map(error -> error.rowNumber).collect(Collectors.toSet()).size()),
            Map.entry("committedAt", batch.committedAt == null ? "" : batch.committedAt.toString()),
            Map.entry("errorCount", batchErrors.stream().filter(error -> "ERROR".equalsIgnoreCase(error.severity)).count()),
            Map.entry("warningCount", batchErrors.stream().filter(error -> "WARNING".equalsIgnoreCase(error.severity)).count())
        ));
    }

    public Map<String, Object> undoPreview(UUID batchId) {
        var tenantId = TenantContext.tenantId();
        var batch = get(batchId);
        var effectRows = importEffects(tenantId, batchId);
        var productsToDelete = new ArrayList<Map<String, Object>>();
        var customersToDelete = new ArrayList<Map<String, Object>>();
        var suppliersToDelete = new ArrayList<Map<String, Object>>();
        var stockMovementsToReverse = new ArrayList<Map<String, Object>>();
        var categoriesToDeleteIfUnused = new ArrayList<Map<String, Object>>();
        var unitsToDeleteIfUnused = new ArrayList<Map<String, Object>>();
        var warehousesToDeleteIfUnused = new ArrayList<Map<String, Object>>();
        var salesInvoicesToVoidOrDelete = new ArrayList<Map<String, Object>>();
        var purchaseInvoicesToVoidOrDelete = new ArrayList<Map<String, Object>>();
        var warnings = new ArrayList<String>();
        var irreversibleItems = new ArrayList<Map<String, Object>>();

        if (effectRows.isEmpty() && batch.status == DomainEnums.ImportStatus.COMMITTED) {
            warnings.add("This batch was committed before import effect tracking existed. Stock movements can be detected, but products/parties may not be automatically removable.");
        }

        for (var effect : effectRows) {
            if (effect.reversedAt != null || effect.action == DomainEnums.ImportEffectAction.REVERSED) {
                continue;
            }
            if (effect.action == DomainEnums.ImportEffectAction.UPDATED || !effect.reversible) {
                irreversibleItems.add(effectMap(effect, "Created/updated record is not safely reversible in SAFE_REVERSAL"));
                continue;
            }
            switch (effect.entityType) {
                case PRODUCT -> products.findByTenantIdAndId(tenantId, effect.entityId).ifPresent(product -> {
                    if (canDeleteProductAfterSafeRollback(tenantId, batchId, product.id, effectRows)) {
                        productsToDelete.add(Map.of("id", product.id, "name", product.name));
                    } else {
                        warnings.add("Product kept because it has stock/invoice/order references: " + product.name);
                    }
                });
                case CUSTOMER -> customers.findByTenantIdAndId(tenantId, effect.entityId).ifPresent(customer -> {
                    if (customerDependencyCount(tenantId, customer.id) == 0) {
                        customersToDelete.add(Map.of("id", customer.id, "name", customer.name));
                    } else {
                        warnings.add("Customer kept because sales/orders/payments reference it: " + customer.name);
                    }
                });
                case SUPPLIER -> suppliers.findByTenantIdAndId(tenantId, effect.entityId).ifPresent(supplier -> {
                    if (supplierDependencyCount(tenantId, supplier.id) == 0) {
                        suppliersToDelete.add(Map.of("id", supplier.id, "name", supplier.name));
                    } else {
                        warnings.add("Supplier kept because purchases/orders/payments reference it: " + supplier.name);
                    }
                });
                case STOCK_MOVEMENT -> {
                    var movementPreview = stockMovementPreview(tenantId, batchId, effect);
                    stockMovementsToReverse.addAll(movementPreview);
                    if (movementPreview.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("willMakeStockNegative")))) {
                        irreversibleItems.add(effectMap(effect, "Reversal would make stock negative. Undo later sales/adjustments first or enable negative stock for this tenant."));
                    }
                }
                case SALES_INVOICE -> salesInvoices.findByTenantIdAndId(tenantId, effect.entityId)
                    .ifPresent(invoice -> salesInvoicesToVoidOrDelete.add(Map.of("id", invoice.id, "invoiceNumber", invoice.invoiceNumber, "safeModeAction", "not deleted; linked stock movement is reversed")));
                case PURCHASE_INVOICE -> purchaseInvoices.findByTenantIdAndId(tenantId, effect.entityId)
                    .ifPresent(invoice -> purchaseInvoicesToVoidOrDelete.add(Map.of("id", invoice.id, "invoiceNumber", invoice.invoiceNumber, "safeModeAction", "not deleted; linked stock movement is reversed")));
                case CATEGORY -> categories.findByTenantIdAndId(tenantId, effect.entityId).ifPresent(category -> {
                    if (countNative("select count(*) from products where tenant_id = :tenantId and category_id = :entityId", tenantId, category.id) == 0) {
                        categoriesToDeleteIfUnused.add(Map.of("id", category.id, "name", category.name));
                    }
                });
                case UNIT -> units.findByTenantIdAndId(tenantId, effect.entityId).ifPresent(unit -> {
                    if (countNative("select count(*) from products where tenant_id = :tenantId and base_unit_id = :entityId", tenantId, unit.id) == 0) {
                        unitsToDeleteIfUnused.add(Map.of("id", unit.id, "code", unit.code));
                    }
                });
                case WAREHOUSE -> warehouses.findByTenantIdAndId(tenantId, effect.entityId).ifPresent(warehouse -> {
                    if (warehouseDependencyCount(tenantId, warehouse.id) == 0) {
                        warehousesToDeleteIfUnused.add(Map.of("id", warehouse.id, "name", warehouse.name));
                    }
                });
            }
        }

        var canUndo = batch.status == DomainEnums.ImportStatus.COMMITTED && irreversibleItems.isEmpty();
        var reason = "";
        if (batch.status == DomainEnums.ImportStatus.ROLLED_BACK) {
            canUndo = false;
            reason = "Import batch is already rolled back.";
        } else if (batch.status != DomainEnums.ImportStatus.COMMITTED) {
            canUndo = false;
            reason = "Only committed import batches can be rolled back.";
        } else if (!irreversibleItems.isEmpty()) {
            reason = "Some import effects are not safely reversible.";
        }

        return new LinkedHashMap<>(Map.ofEntries(
            Map.entry("batchId", batch.id),
            Map.entry("status", batch.status.name()),
            Map.entry("canUndo", canUndo),
            Map.entry("reasonIfCannotUndo", reason),
            Map.entry("productsToDelete", productsToDelete),
            Map.entry("customersToDelete", customersToDelete),
            Map.entry("suppliersToDelete", suppliersToDelete),
            Map.entry("salesInvoicesToVoidOrDelete", salesInvoicesToVoidOrDelete),
            Map.entry("purchaseInvoicesToVoidOrDelete", purchaseInvoicesToVoidOrDelete),
            Map.entry("stockMovementsToReverse", stockMovementsToReverse),
            Map.entry("stockMovementsToDeleteIfDevMode", stockMovementsToReverse),
            Map.entry("categoriesToDeleteIfUnused", categoriesToDeleteIfUnused),
            Map.entry("unitsToDeleteIfUnused", unitsToDeleteIfUnused),
            Map.entry("warehousesToDeleteIfUnused", warehousesToDeleteIfUnused),
            Map.entry("warnings", warnings),
            Map.entry("irreversibleItems", irreversibleItems),
            Map.entry("effectCount", effectRows.size())
        ));
    }

    @Transactional
    public Map<String, Object> undo(UUID batchId, DomainEnums.ImportUndoStrategy requestedStrategy) {
        var tenantId = TenantContext.tenantId();
        var strategy = requestedStrategy == null ? DomainEnums.ImportUndoStrategy.SAFE_REVERSAL : requestedStrategy;
        var batch = get(batchId);
        if (batch.status == DomainEnums.ImportStatus.ROLLED_BACK) {
            return undoResult(batch, strategy, 0, 0, 0, 0, 0, List.of("Import batch was already rolled back."));
        }
        if (strategy == DomainEnums.ImportUndoStrategy.DEV_HARD_DELETE && !devToolsEnabled) {
            throw ApiErrors.badRequest("DEV_HARD_DELETE is available only when app.dev-tools.enabled=true");
        }
        var preview = undoPreview(batchId);
        if (!Boolean.TRUE.equals(preview.get("canUndo"))) {
            throw ApiErrors.badRequest(String.valueOf(preview.getOrDefault("reasonIfCannotUndo", "Import cannot be rolled back safely")));
        }
        var effectRows = importEffects(tenantId, batchId);
        int reversedMovements = 0;
        int deletedProducts = 0;
        int deletedCustomers = 0;
        int deletedSuppliers = 0;
        int hardDeletedMovements = 0;
        var warnings = new ArrayList<String>();

        if (strategy == DomainEnums.ImportUndoStrategy.DEV_HARD_DELETE) {
            hardDeletedMovements = hardDeleteImportRecords(tenantId, batchId, effectRows);
        } else {
            for (var effect : effectRows) {
                if (effect.entityType == DomainEnums.ImportEffectEntityType.STOCK_MOVEMENT
                    && effect.action == DomainEnums.ImportEffectAction.CREATED
                    && effect.reversedAt == null) {
                    var movement = stockMovements(tenantId, effect.entityId).orElse(null);
                    if (movement == null || movementAlreadyReversed(tenantId, batchId, movement.id)) {
                        markReversed(effect);
                        continue;
                    }
                    stockLedger.createMovement(
                        tenantId,
                        movement.productId,
                        movement.warehouseId,
                        DomainEnums.MovementType.ADJUSTMENT,
                        movement.baseQuantity.negate(),
                        movement.unitId,
                        movement.rate,
                        "IMPORT_ROLLBACK",
                        batchId,
                        Instant.now(),
                        "Undo import " + batchId + "; reverses movement " + movement.id
                    );
                    markReversed(effect);
                    reversedMovements++;
                }
            }
            for (var effect : effectRows) {
                if (effect.reversedAt != null || effect.action != DomainEnums.ImportEffectAction.CREATED) {
                    continue;
                }
                if (effect.entityType == DomainEnums.ImportEffectEntityType.PRODUCT) {
                    deletedProducts += deleteProductIfUnusedForSafeRollback(tenantId, batchId, effect.entityId, effectRows, warnings);
                    markReversed(effect);
                } else if (effect.entityType == DomainEnums.ImportEffectEntityType.CUSTOMER) {
                    deletedCustomers += deleteCustomerIfUnused(tenantId, effect.entityId, warnings);
                    markReversed(effect);
                } else if (effect.entityType == DomainEnums.ImportEffectEntityType.SUPPLIER) {
                    deletedSuppliers += deleteSupplierIfUnused(tenantId, effect.entityId, warnings);
                    markReversed(effect);
                } else if (effect.entityType == DomainEnums.ImportEffectEntityType.CATEGORY) {
                    deleteCategoryIfUnused(tenantId, effect.entityId);
                    markReversed(effect);
                } else if (effect.entityType == DomainEnums.ImportEffectEntityType.UNIT) {
                    deleteUnitIfUnused(tenantId, effect.entityId);
                    markReversed(effect);
                } else if (effect.entityType == DomainEnums.ImportEffectEntityType.WAREHOUSE) {
                    deleteWarehouseIfUnused(tenantId, effect.entityId);
                    markReversed(effect);
                }
            }
        }

        batch.status = DomainEnums.ImportStatus.ROLLED_BACK;
        batch.rolledBackAt = Instant.now();
        batches.save(batch);
        auditService.logCurrent("IMPORT_ROLLED_BACK", "ImportBatch", batch.id, Map.of(
            "strategy", strategy.name(),
            "reversedMovements", reversedMovements,
            "hardDeletedMovements", hardDeletedMovements,
            "deletedProducts", deletedProducts,
            "deletedCustomers", deletedCustomers,
            "deletedSuppliers", deletedSuppliers
        ));
        return undoResult(batch, strategy, reversedMovements, hardDeletedMovements, deletedProducts, deletedCustomers, deletedSuppliers, warnings);
    }

    @Transactional
    public Map<String, Object> replace(UUID batchId) {
        var result = undo(batchId, DomainEnums.ImportUndoStrategy.SAFE_REVERSAL);
        result.put("nextStep", "Upload the replacement Tally/Excel/CSV/XML/JSON file and commit it as a new import batch.");
        return result;
    }

    @Transactional
    public ImportMappingTemplate saveTemplate(ApiDtos.ImportTemplateRequest request) {
        var template = new ImportMappingTemplate();
        template.tenantId = TenantContext.tenantId();
        template.name = request.name();
        template.sourceType = request.sourceType();
        template.mappingJson = new LinkedHashMap<>(request.mapping());
        return templates.save(template);
    }

    private Optional<Product> findExistingProduct(UUID tenantId, StagingProduct row) {
        if (row.sku != null && !row.sku.isBlank()) {
            return products.findByTenantIdAndSkuIgnoreCase(tenantId, row.sku);
        }
        if (row.barcode != null && !row.barcode.isBlank()) {
            var barcodeMatch = barcodes.findByTenantIdAndBarcode(tenantId, row.barcode)
                .flatMap(barcode -> products.findByTenantIdAndId(tenantId, barcode.productId));
            if (barcodeMatch.isPresent()) {
                return barcodeMatch;
            }
        }
        var externalIdMatch = findByTallyExternalId(tenantId, row);
        if (externalIdMatch.isPresent()) {
            return externalIdMatch;
        }
        var normalizedName = CatalogService.normalizeName(row.productName);
        if (isTallyStockReportRow(row) && normalizedName != null) {
            var existingUnitId = unitIdForCode(tenantId, row.unitCode);
            if (existingUnitId.isPresent()) {
                var normalizedMatch = products.findByTenantIdAndNormalizedNameIgnoreCaseAndBaseUnitId(tenantId, normalizedName, existingUnitId.get());
                if (normalizedMatch.isPresent()) {
                    return normalizedMatch;
                }
            }
            return Optional.empty();
        }
        if (row.productName == null || row.productName.isBlank()) {
            return Optional.empty();
        }
        return products.findByTenantIdAndNameIgnoreCase(tenantId, row.productName);
    }

    private void recordCreatedEffect(UUID tenantId, UUID batchId, DomainEnums.ImportEffectEntityType entityType, UUID entityId, boolean reversible, Map<String, Object> newValue) {
        if (entityId == null || effects.existsByTenantIdAndImportBatchIdAndEntityTypeAndEntityIdAndAction(tenantId, batchId, entityType, entityId, DomainEnums.ImportEffectAction.CREATED)) {
            return;
        }
        var effect = new ImportEffect();
        effect.tenantId = tenantId;
        effect.importBatchId = batchId;
        effect.entityType = entityType;
        effect.entityId = entityId;
        effect.action = DomainEnums.ImportEffectAction.CREATED;
        effect.reversible = reversible;
        effect.newValueJson = new LinkedHashMap<>(newValue == null ? Map.of() : newValue);
        effects.save(effect);
    }

    private void recordUpdatedEffect(UUID tenantId, UUID batchId, DomainEnums.ImportEffectEntityType entityType, UUID entityId, Map<String, Object> newValue) {
        if (entityId == null || effects.existsByTenantIdAndImportBatchIdAndEntityTypeAndEntityIdAndAction(tenantId, batchId, entityType, entityId, DomainEnums.ImportEffectAction.UPDATED)) {
            return;
        }
        var effect = new ImportEffect();
        effect.tenantId = tenantId;
        effect.importBatchId = batchId;
        effect.entityType = entityType;
        effect.entityId = entityId;
        effect.action = DomainEnums.ImportEffectAction.UPDATED;
        effect.reversible = false;
        effect.newValueJson = new LinkedHashMap<>(newValue == null ? Map.of() : newValue);
        effects.save(effect);
    }

    private Map<String, Object> stockMovementEffect(StockMovement movement) {
        var details = new LinkedHashMap<String, Object>();
        details.put("productId", movement.productId);
        details.put("warehouseId", movement.warehouseId);
        details.put("movementType", movement.movementType.name());
        details.put("quantity", movement.quantity);
        details.put("baseQuantity", movement.baseQuantity);
        details.put("rate", movement.rate);
        details.put("referenceType", movement.referenceType == null ? "" : movement.referenceType);
        details.put("referenceId", movement.referenceId == null ? "" : movement.referenceId);
        return details;
    }

    private List<ImportEffect> importEffects(UUID tenantId, UUID batchId) {
        var tracked = effects.findByTenantIdAndImportBatchIdOrderByCreatedAtAsc(tenantId, batchId);
        if (!tracked.isEmpty()) {
            return tracked;
        }
        return stockLedgerMovementsForLegacyBatch(tenantId, batchId).stream().map(movement -> {
            var effect = new ImportEffect();
            effect.tenantId = tenantId;
            effect.importBatchId = batchId;
            effect.entityType = DomainEnums.ImportEffectEntityType.STOCK_MOVEMENT;
            effect.entityId = movement.id;
            effect.action = DomainEnums.ImportEffectAction.CREATED;
            effect.reversible = true;
            effect.newValueJson = stockMovementEffect(movement);
            return effect;
        }).toList();
    }

    private List<StockMovement> stockLedgerMovementsForLegacyBatch(UUID tenantId, UUID batchId) {
        return entityManager.createQuery("""
                select movement from StockMovement movement
                where movement.tenantId = :tenantId
                  and movement.referenceType = 'IMPORT_BATCH'
                  and movement.referenceId = :batchId
                order by movement.createdAt asc
                """, StockMovement.class)
            .setParameter("tenantId", tenantId)
            .setParameter("batchId", batchId)
            .getResultList();
    }

    private Optional<StockMovement> stockMovements(UUID tenantId, UUID movementId) {
        var movement = entityManager.find(StockMovement.class, movementId);
        return movement != null && tenantId.equals(movement.tenantId) ? Optional.of(movement) : Optional.empty();
    }

    private List<Map<String, Object>> stockMovementPreview(UUID tenantId, UUID batchId, ImportEffect effect) {
        var movement = stockMovements(tenantId, effect.entityId).orElse(null);
        if (movement == null || movementAlreadyReversed(tenantId, batchId, effect.entityId)) {
            return List.of();
        }
        var productName = products.findByTenantIdAndId(tenantId, movement.productId).map(product -> product.name).orElse("Unknown product");
        var warehouseName = warehouses.findByTenantIdAndId(tenantId, movement.warehouseId).map(warehouse -> warehouse.name).orElse("Unknown warehouse");
        var reversalQuantity = movement.baseQuantity.negate();
        var projectedStock = stockLedger.currentStock(tenantId, movement.productId, movement.warehouseId).add(reversalQuantity);
        var row = new LinkedHashMap<String, Object>();
        row.put("id", movement.id);
        row.put("productName", productName);
        row.put("warehouseName", warehouseName);
        row.put("movementType", movement.movementType.name());
        row.put("baseQuantity", movement.baseQuantity);
        row.put("reversalQuantity", reversalQuantity);
        row.put("projectedStockAfterReversal", projectedStock);
        row.put("willMakeStockNegative", projectedStock.compareTo(BigDecimal.ZERO) < 0 && !stockLedger.negativeStockAllowed(tenantId));
        return List.of(row);
    }

    private boolean movementAlreadyReversed(UUID tenantId, UUID batchId, UUID movementId) {
        return entityManager.createQuery("""
                select count(movement) from StockMovement movement
                where movement.tenantId = :tenantId
                  and movement.referenceType = 'IMPORT_ROLLBACK'
                  and movement.referenceId = :batchId
                  and movement.notes like :needle
                """, Long.class)
            .setParameter("tenantId", tenantId)
            .setParameter("batchId", batchId)
            .setParameter("needle", "%" + movementId + "%")
            .getSingleResult() > 0;
    }

    private boolean canDeleteProductAfterSafeRollback(UUID tenantId, UUID batchId, UUID productId, List<ImportEffect> effectRows) {
        var importMovementIds = effectRows.stream()
            .filter(effect -> effect.entityType == DomainEnums.ImportEffectEntityType.STOCK_MOVEMENT)
            .map(effect -> effect.entityId)
            .collect(Collectors.toSet());
        var movements = stockLedgerMovementsForProduct(tenantId, productId);
        if (!movements.isEmpty()) {
            return false;
        }
        return productReferenceCountOutsideImport(tenantId, productId, importMovementIds, batchId) == 0;
    }

    private int deleteProductIfUnusedForSafeRollback(UUID tenantId, UUID batchId, UUID productId, List<ImportEffect> effectRows, List<String> warnings) {
        var product = products.findByTenantIdAndId(tenantId, productId).orElse(null);
        if (product == null) {
            return 0;
        }
        if (!canDeleteProductAfterSafeRollback(tenantId, batchId, productId, effectRows)) {
            warnings.add("Product kept because stock/invoice/order history still references it: " + product.name);
            return 0;
        }
        barcodes.findByTenantIdAndProductId(tenantId, productId).forEach(barcodes::delete);
        products.delete(product);
        return 1;
    }

    private List<StockMovement> stockLedgerMovementsForProduct(UUID tenantId, UUID productId) {
        return stockLedgerMovementsForProductIncludingRollback(tenantId, productId).stream()
            .filter(movement -> !"IMPORT_ROLLBACK".equals(movement.referenceType))
            .toList();
    }

    private List<StockMovement> stockLedgerMovementsForProductIncludingRollback(UUID tenantId, UUID productId) {
        return entityManager.createQuery("""
                select movement from StockMovement movement
                where movement.tenantId = :tenantId and movement.productId = :productId
                """, StockMovement.class)
            .setParameter("tenantId", tenantId)
            .setParameter("productId", productId)
            .getResultList();
    }

    private long productReferenceCountOutsideImport(UUID tenantId, UUID productId, Set<UUID> importMovementIds, UUID batchId) {
        return countNative("select count(*) from sales_invoice_items where tenant_id = :tenantId and product_id = :entityId", tenantId, productId)
            + countNative("select count(*) from purchase_invoice_items where tenant_id = :tenantId and product_id = :entityId", tenantId, productId)
            + countNative("select count(*) from sales_order_items where tenant_id = :tenantId and product_id = :entityId", tenantId, productId)
            + countNative("select count(*) from purchase_order_items where tenant_id = :tenantId and product_id = :entityId", tenantId, productId);
    }

    private int deleteCustomerIfUnused(UUID tenantId, UUID customerId, List<String> warnings) {
        var customer = customers.findByTenantIdAndId(tenantId, customerId).orElse(null);
        if (customer == null) {
            return 0;
        }
        if (customerDependencyCount(tenantId, customerId) > 0) {
            warnings.add("Customer kept because sales/orders/payments reference it: " + customer.name);
            return 0;
        }
        customers.delete(customer);
        return 1;
    }

    private int deleteSupplierIfUnused(UUID tenantId, UUID supplierId, List<String> warnings) {
        var supplier = suppliers.findByTenantIdAndId(tenantId, supplierId).orElse(null);
        if (supplier == null) {
            return 0;
        }
        if (supplierDependencyCount(tenantId, supplierId) > 0) {
            warnings.add("Supplier kept because purchases/orders/payments reference it: " + supplier.name);
            return 0;
        }
        suppliers.delete(supplier);
        return 1;
    }

    private void deleteCategoryIfUnused(UUID tenantId, UUID categoryId) {
        categories.findByTenantIdAndId(tenantId, categoryId)
            .filter(category -> countNative("select count(*) from products where tenant_id = :tenantId and category_id = :entityId", tenantId, categoryId) == 0)
            .ifPresent(categories::delete);
    }

    private void deleteUnitIfUnused(UUID tenantId, UUID unitId) {
        units.findByTenantIdAndId(tenantId, unitId)
            .filter(unit -> countNative("select count(*) from products where tenant_id = :tenantId and base_unit_id = :entityId", tenantId, unitId) == 0)
            .ifPresent(units::delete);
    }

    private void deleteWarehouseIfUnused(UUID tenantId, UUID warehouseId) {
        warehouses.findByTenantIdAndId(tenantId, warehouseId)
            .filter(warehouse -> warehouseDependencyCount(tenantId, warehouseId) == 0)
            .ifPresent(warehouses::delete);
    }

    private long customerDependencyCount(UUID tenantId, UUID customerId) {
        return countNative("select count(*) from sales_invoices where tenant_id = :tenantId and customer_id = :entityId", tenantId, customerId)
            + countNative("select count(*) from sales_orders where tenant_id = :tenantId and customer_id = :entityId", tenantId, customerId)
            + countNative("select count(*) from customer_payments where tenant_id = :tenantId and customer_id = :entityId", tenantId, customerId)
            + countNative("select count(*) from customer_price_lists where tenant_id = :tenantId and customer_id = :entityId", tenantId, customerId);
    }

    private long supplierDependencyCount(UUID tenantId, UUID supplierId) {
        return countNative("select count(*) from purchase_invoices where tenant_id = :tenantId and supplier_id = :entityId", tenantId, supplierId)
            + countNative("select count(*) from purchase_orders where tenant_id = :tenantId and supplier_id = :entityId", tenantId, supplierId)
            + countNative("select count(*) from supplier_payments where tenant_id = :tenantId and supplier_id = :entityId", tenantId, supplierId);
    }

    private long warehouseDependencyCount(UUID tenantId, UUID warehouseId) {
        return countNative("select count(*) from stock_movements where tenant_id = :tenantId and warehouse_id = :entityId", tenantId, warehouseId)
            + countNative("select count(*) from sales_invoices where tenant_id = :tenantId and warehouse_id = :entityId", tenantId, warehouseId)
            + countNative("select count(*) from purchase_invoices where tenant_id = :tenantId and warehouse_id = :entityId", tenantId, warehouseId);
    }

    private long countNative(String sql, UUID tenantId, UUID entityId) {
        var result = entityManager.createNativeQuery(sql)
            .setParameter("tenantId", tenantId)
            .setParameter("entityId", entityId)
            .getSingleResult();
        return ((Number) result).longValue();
    }

    private int hardDeleteImportRecords(UUID tenantId, UUID batchId, List<ImportEffect> effectRows) {
        var movementIds = effectRows.stream()
            .filter(effect -> effect.entityType == DomainEnums.ImportEffectEntityType.STOCK_MOVEMENT)
            .map(effect -> effect.entityId)
            .toList();
        int deletedMovements = 0;
        for (var movementId : movementIds) {
            deletedMovements += executeDelete("delete from stock_movements where tenant_id = :tenantId and id = :entityId", tenantId, movementId);
        }
        for (var effect : effectRows) {
            if (effect.entityType == DomainEnums.ImportEffectEntityType.SALES_INVOICE) {
                executeDelete("delete from sales_invoice_items where tenant_id = :tenantId and sales_invoice_id = :entityId", tenantId, effect.entityId);
                executeDelete("delete from sales_invoices where tenant_id = :tenantId and id = :entityId", tenantId, effect.entityId);
            } else if (effect.entityType == DomainEnums.ImportEffectEntityType.PURCHASE_INVOICE) {
                executeDelete("delete from purchase_invoice_items where tenant_id = :tenantId and purchase_invoice_id = :entityId", tenantId, effect.entityId);
                executeDelete("delete from purchase_invoices where tenant_id = :tenantId and id = :entityId", tenantId, effect.entityId);
            }
        }
        var warnings = new ArrayList<String>();
        for (var effect : effectRows) {
            if (effect.entityType == DomainEnums.ImportEffectEntityType.PRODUCT) {
                deleteProductIfUnusedForSafeRollback(tenantId, batchId, effect.entityId, effectRows, warnings);
            } else if (effect.entityType == DomainEnums.ImportEffectEntityType.CUSTOMER) {
                deleteCustomerIfUnused(tenantId, effect.entityId, warnings);
            } else if (effect.entityType == DomainEnums.ImportEffectEntityType.SUPPLIER) {
                deleteSupplierIfUnused(tenantId, effect.entityId, warnings);
            } else if (effect.entityType == DomainEnums.ImportEffectEntityType.CATEGORY) {
                deleteCategoryIfUnused(tenantId, effect.entityId);
            } else if (effect.entityType == DomainEnums.ImportEffectEntityType.UNIT) {
                deleteUnitIfUnused(tenantId, effect.entityId);
            } else if (effect.entityType == DomainEnums.ImportEffectEntityType.WAREHOUSE) {
                deleteWarehouseIfUnused(tenantId, effect.entityId);
            }
            markReversed(effect);
        }
        return deletedMovements;
    }

    private int executeDelete(String sql, UUID tenantId, UUID entityId) {
        return entityManager.createNativeQuery(sql)
            .setParameter("tenantId", tenantId)
            .setParameter("entityId", entityId)
            .executeUpdate();
    }

    private void markReversed(ImportEffect effect) {
        if (effect.id == null || effect.createdAt == null) {
            return;
        }
        effect.reversedAt = Instant.now();
        effects.save(effect);
    }

    private Map<String, Object> effectMap(ImportEffect effect, String reason) {
        return Map.of(
            "entityType", effect.entityType.name(),
            "entityId", effect.entityId,
            "action", effect.action.name(),
            "reason", reason
        );
    }

    private Map<String, Object> undoResult(
        ImportBatch batch,
        DomainEnums.ImportUndoStrategy strategy,
        int reversedMovements,
        int hardDeletedMovements,
        int deletedProducts,
        int deletedCustomers,
        int deletedSuppliers,
        List<String> warnings
    ) {
        var result = new LinkedHashMap<String, Object>();
        result.put("batchId", batch.id);
        result.put("status", batch.status.name());
        result.put("strategy", strategy.name());
        result.put("reversedMovements", reversedMovements);
        result.put("hardDeletedMovements", hardDeletedMovements);
        result.put("deletedProducts", deletedProducts);
        result.put("deletedCustomers", deletedCustomers);
        result.put("deletedSuppliers", deletedSuppliers);
        result.put("warnings", warnings);
        return result;
    }

    private void stageRows(UUID tenantId, UUID batchId, DomainEnums.SourceType sourceType, List<Map<String, String>> rows) {
        int index = 1;
        for (var sourceRow : rows) {
            var productName = lookup(sourceRow, "productName", "Product Name", "Item Name", "Name", "Stock Item", "Description");
            var partyName = lookup(sourceRow, "partyName", "Party Name", "Ledger Name", "Customer", "Supplier");
            var movementLike = isMovementRow(sourceType, sourceRow);
            var productLike = productName != null
                || lookup(sourceRow, "sku", "SKU", "Item Code", "Stock Code") != null
                || lookup(sourceRow, "barcode", "Barcode", "EAN", "UPC") != null
                || lookup(sourceRow, "unitCode", "Unit", "Units", "Base Unit", "BASEUNITS") != null
                || lookup(sourceRow, "openingStock", "Opening Stock", "Opening Qty", "Qty", "Quantity") != null;
            if (movementLike) {
                var row = new StagingStockMovement();
                row.tenantId = tenantId;
                row.importBatchId = batchId;
                row.rowNumber = index;
                row.productName = productName;
                row.warehouseName = lookup(sourceRow, "warehouseName", "Warehouse", "Godown", "Location");
                row.movementType = inferMovementType(sourceType, sourceRow);
                row.quantity = decimal(lookup(sourceRow, "quantity", "Qty", "Quantity", "Billed Quantity", "Billed Qty", "Actual Quantity", "Actual Qty", "Opening Stock"));
                row.rate = decimal(lookup(sourceRow, "rate", "Rate", "Unit Price", "Price", "Purchase Price", "Sales Price"));
                row.movementDate = date(lookup(sourceRow, "movementDate", "Voucher Date", "Invoice Date", "Date"));
                row.rawMetadata = new LinkedHashMap<>(sourceRow);
                stagingStockMovements.save(row);
                stageParty(tenantId, batchId, index, sourceRow);
            } else if (productLike) {
                var row = new StagingProduct();
                row.tenantId = tenantId;
                row.importBatchId = batchId;
                row.rowNumber = index;
                row.productName = productName;
                row.sku = lookup(sourceRow, "sku", "SKU", "Item Code", "Stock Code");
                row.category = lookup(sourceRow, "category", "Category", "Stock Group", "Group");
                row.brand = lookup(sourceRow, "brand", "Brand", "Manufacturer");
                row.unitCode = lookup(sourceRow, "unitCode", "Unit", "Units", "Base Unit", "BASEUNITS");
                row.barcode = lookup(sourceRow, "barcode", "Barcode", "EAN", "UPC");
                row.hsnCode = lookup(sourceRow, "hsnCode", "HSN", "HSN Code", "HSNCODE");
                row.gstPercentage = decimal(lookup(sourceRow, "gstPercentage", "GST %", "GST", "Tax Rate"));
                row.openingStock = decimal(lookup(sourceRow, "openingStock", "Opening Stock", "Opening Qty", "Qty", "Quantity"));
                row.purchasePrice = decimal(lookup(sourceRow, "purchasePrice", "Purchase Price", "Cost", "Rate", "Opening Rate"));
                row.salesPrice = decimal(lookup(sourceRow, "salesPrice", "Sales Price", "MRP", "Selling Price"));
                row.warehouseName = lookup(sourceRow, "warehouseName", "Warehouse", "Godown", "Location");
                row.rawMetadata = new LinkedHashMap<>(sourceRow);
                stagingProducts.save(row);
            } else if (partyName != null && !partyName.isBlank()) {
                stageParty(tenantId, batchId, index, sourceRow);
            }
            index++;
        }
    }

    private Product findOrCreateProduct(UUID tenantId, StagingProduct row) {
        var existing = findExistingProduct(tenantId, row);
        var creating = existing.isEmpty();
        var product = existing.orElseGet(Product::new);
        product.tenantId = tenantId;
        if (row.sku != null && !row.sku.isBlank()) {
            product.sku = row.sku.trim();
        } else if (creating) {
            product.sku = null;
        }
        product.name = row.productName.trim();
        product.normalizedName = CatalogService.normalizeName(row.productName);
        if (row.category != null && !row.category.isBlank()) {
            product.categoryId = catalogService.getOrCreateCategory(row.category).id;
        }
        if (row.brand != null && !row.brand.isBlank()) {
            product.brandId = catalogService.getOrCreateBrand(row.brand).id;
        }
        if (row.unitCode != null && !row.unitCode.isBlank()) {
            var importedUnitId = catalogService.getOrCreateUnit(row.unitCode).id;
            if (!creating) productIntegrity.assertBaseUnitChangeAllowed(product, importedUnitId);
            product.baseUnitId = importedUnitId;
        } else if (creating) {
            product.baseUnitId = catalogService.getOrCreateUnit("PCS").id;
        }
        if (row.hsnCode != null && !row.hsnCode.isBlank()) {
            product.hsnCode = row.hsnCode.trim();
        }
        if (row.gstPercentage != null) {
            product.gstPercentage = row.gstPercentage;
        } else if (creating) {
            product.gstPercentage = BigDecimal.ZERO;
        }
        if (row.purchasePrice != null) {
            product.defaultPurchasePrice = row.purchasePrice;
        } else if (creating) {
            product.defaultPurchasePrice = BigDecimal.ZERO;
        }
        if (row.salesPrice != null) {
            product.defaultSalesPrice = row.salesPrice;
        } else if (creating) {
            product.defaultSalesPrice = BigDecimal.ZERO;
        }
        if (product.reorderPoint == null || product.reorderPoint.compareTo(BigDecimal.ZERO) == 0) {
            product.reorderPoint = BigDecimal.TEN;
        }
        if (row.rawMetadata != null && !row.rawMetadata.isEmpty()) {
            if (product.rawMetadata == null) {
                product.rawMetadata = new LinkedHashMap<>();
            }
            product.rawMetadata.putAll(row.rawMetadata);
        }
        return products.save(product);
    }

    private void remap(StagingProduct row, Map<String, String> mapping) {
        row.productName = mapped(row, mapping, "productName", row.productName);
        row.sku = mapped(row, mapping, "sku", row.sku);
        row.category = mapped(row, mapping, "category", row.category);
        row.brand = mapped(row, mapping, "brand", row.brand);
        row.unitCode = mapped(row, mapping, "unitCode", row.unitCode);
        row.openingStock = decimal(mapped(row, mapping, "openingStock", str(row.openingStock)));
        row.purchasePrice = decimal(mapped(row, mapping, "purchasePrice", str(row.purchasePrice)));
        row.salesPrice = decimal(mapped(row, mapping, "salesPrice", str(row.salesPrice)));
        row.warehouseName = mapped(row, mapping, "warehouseName", row.warehouseName);
    }

    private void remap(StagingStockMovement row, Map<String, String> mapping) {
        row.productName = mapped(row.rawMetadata, mapping, "productName", row.productName);
        row.warehouseName = mapped(row.rawMetadata, mapping, "warehouseName", row.warehouseName);
        row.movementType = mapped(row.rawMetadata, mapping, "movementType", row.movementType);
        row.quantity = decimal(mapped(row.rawMetadata, mapping, "quantity", str(row.quantity)));
        row.rate = decimal(mapped(row.rawMetadata, mapping, "rate", str(row.rate)));
        row.movementDate = date(mapped(row.rawMetadata, mapping, "movementDate", row.movementDate == null ? null : row.movementDate.toString()));
    }

    private void remap(StagingCustomer row, Map<String, String> mapping) {
        row.name = mapped(row.rawMetadata, mapping, "name", row.name);
        row.phone = mapped(row.rawMetadata, mapping, "phone", row.phone);
        row.email = mapped(row.rawMetadata, mapping, "email", row.email);
        row.gstin = mapped(row.rawMetadata, mapping, "gstin", row.gstin);
    }

    private void remap(StagingSupplier row, Map<String, String> mapping) {
        row.name = mapped(row.rawMetadata, mapping, "name", row.name);
        row.phone = mapped(row.rawMetadata, mapping, "phone", row.phone);
        row.email = mapped(row.rawMetadata, mapping, "email", row.email);
        row.gstin = mapped(row.rawMetadata, mapping, "gstin", row.gstin);
    }

    private String mapped(StagingProduct row, Map<String, String> mapping, String target, String fallback) {
        return mapped(row.rawMetadata, mapping, target, fallback);
    }

    private String mapped(Map<String, Object> rawMetadata, Map<String, String> mapping, String target, String fallback) {
        var source = mapping.get(target);
        if (source == null || source.isBlank()) {
            return fallback;
        }
        var value = lookup(rawMetadata.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue()))), source);
        return value == null ? fallback : value;
    }

    private void stageParty(UUID tenantId, UUID batchId, int rowNumber, Map<String, String> sourceRow) {
        var partyName = lookup(sourceRow, "partyName", "Party Name", "Ledger Name", "Customer", "Supplier");
        if (partyName == null || partyName.isBlank()) {
            return;
        }
        var group = Optional.ofNullable(lookup(sourceRow, "Ledger Group", "Group", "PARENT")).orElse("").toLowerCase(Locale.ROOT);
        var movementType = inferMovementType(DomainEnums.SourceType.CSV, sourceRow);
        if (group.contains("creditor") || group.contains("supplier") || DomainEnums.MovementType.PURCHASE.name().equals(movementType)) {
            var supplier = new StagingSupplier();
            supplier.tenantId = tenantId;
            supplier.importBatchId = batchId;
            supplier.rowNumber = rowNumber;
            supplier.name = partyName;
            supplier.phone = lookup(sourceRow, "phone", "Phone", "Mobile", "Contact Number");
            supplier.email = lookup(sourceRow, "email", "Email", "Email Address");
            supplier.gstin = lookup(sourceRow, "GSTIN", "GST No", "GST Number");
            supplier.rawMetadata = new LinkedHashMap<>(sourceRow);
            stagingSuppliers.save(supplier);
        } else {
            var customer = new StagingCustomer();
            customer.tenantId = tenantId;
            customer.importBatchId = batchId;
            customer.rowNumber = rowNumber;
            customer.name = partyName;
            customer.phone = lookup(sourceRow, "phone", "Phone", "Mobile", "Contact Number");
            customer.email = lookup(sourceRow, "email", "Email", "Email Address");
            customer.gstin = lookup(sourceRow, "GSTIN", "GST No", "GST Number");
            customer.rawMetadata = new LinkedHashMap<>(sourceRow);
            stagingCustomers.save(customer);
        }
    }

    private boolean isMovementRow(DomainEnums.SourceType sourceType, Map<String, String> row) {
        var sourceEntity = lookup(row, "Source Entity");
        if (sourceEntity != null && sourceEntity.equalsIgnoreCase("VOUCHER")) {
            return true;
        }
        var hasInvoice = lookup(row, "Invoice Number", "Voucher No", "Voucher Number", "VOUCHERNUMBER") != null;
        var hasDate = lookup(row, "Voucher Date", "Invoice Date", "Date", "movementDate") != null;
        var hasVoucherType = lookup(row, "Voucher Type", "Invoice Type", "VCHTYPE", "movementType") != null;
        var hasParty = lookup(row, "Party Name", "Customer", "Supplier") != null;
        var hasProduct = lookup(row, "productName", "Product Name", "Item Name", "Name", "Stock Item", "Description") != null;
        var hasQuantity = lookup(row, "quantity", "Qty", "Quantity", "Billed Quantity", "Billed Qty", "Actual Quantity", "Actual Qty") != null;
        var hasRate = lookup(row, "rate", "Rate", "Unit Price", "Price") != null;
        return hasInvoice || hasDate || hasVoucherType || (hasParty && hasProduct && (hasQuantity || hasRate));
    }

    private String inferMovementType(DomainEnums.SourceType sourceType, Map<String, String> row) {
        var explicit = lookup(row, "movementType", "Movement Type", "Voucher Type", "Invoice Type", "VCHTYPE");
        var supplier = lookup(row, "Supplier");
        var combined = (explicit == null ? "" : explicit) + " " + (supplier == null ? "" : supplier) + " " + sourceType.name();
        var lower = combined.toLowerCase(Locale.ROOT);
        if (lower.contains("debit note") || lower.contains("purchase return") || (lower.contains("purchase") && lower.contains("return"))) {
            return DomainEnums.MovementType.RETURN_OUT.name();
        }
        if (lower.contains("purchase") || lower.contains("supplier")) {
            return DomainEnums.MovementType.PURCHASE.name();
        }
        if (lower.contains("return in")) {
            return DomainEnums.MovementType.RETURN_IN.name();
        }
        if (lower.contains("return out")) {
            return DomainEnums.MovementType.RETURN_OUT.name();
        }
        return DomainEnums.MovementType.SALE.name();
    }

    private List<StagingStockMovement> sortedMovementRows(List<StagingStockMovement> rows) {
        return rows.stream()
            .sorted(Comparator
                .comparing((StagingStockMovement row) -> row.movementDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(row -> row.rowNumber))
            .toList();
    }

    private List<ImportError> validateCustomerRow(UUID tenantId, UUID batchId, StagingCustomer row, Map<String, String> seenGstins, Set<String> seenNamePhones) {
        var rowErrors = new ArrayList<ImportError>();
        if (row.name == null || row.name.isBlank()) {
            rowErrors.add(error(tenantId, batchId, row.rowNumber, "CUSTOMER", "name", "CUSTOMER_NAME_REQUIRED", "Customer name is required", "ERROR", raw(row.rawMetadata, "Customer", "Party Name", "Ledger Name"), "Map the customer/party name column."));
            return rowErrors;
        }
        validateEmail(tenantId, batchId, row.rowNumber, row.email).ifPresent(rowErrors::add);
        var gstin = normalizedValue(row.gstin);
        if (gstin != null) {
            var previousName = seenGstins.putIfAbsent(gstin, normalizedValue(row.name));
            var existing = customers.findByTenantIdAndGstinIgnoreCase(tenantId, row.gstin);
            if (previousName != null && !previousName.equals(normalizedValue(row.name))
                || existing.isPresent() && !Objects.equals(normalizedValue(existing.get().name), normalizedValue(row.name))) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "CUSTOMER", "gstin", "DUPLICATE_GSTIN", "Customer GSTIN belongs to conflicting party names", "ERROR", row.gstin, "Keep one customer per GSTIN or correct the GSTIN."));
            }
            if (!validGstin(row.gstin)) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "CUSTOMER", "gstin", "BAD_GSTIN", "GSTIN should be 15 alphanumeric characters", "ERROR", row.gstin, "Use a 15-character GSTIN or leave it blank if unavailable."));
            }
        } else {
            var namePhone = normalizeKey(row.name + "|" + (row.phone == null ? "" : row.phone));
            if (row.phone != null && !row.phone.isBlank() && !seenNamePhones.add(namePhone)) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "CUSTOMER", "phone", "DUPLICATE_CUSTOMER_NAME_PHONE", "Customer name/phone appears more than once without GSTIN", "WARNING", row.phone, "Merge duplicate customer rows or add GSTIN."));
            }
        }
        return rowErrors;
    }

    private List<ImportError> validateSupplierRow(UUID tenantId, UUID batchId, StagingSupplier row, Map<String, String> seenGstins, Set<String> seenNamePhones) {
        var rowErrors = new ArrayList<ImportError>();
        if (row.name == null || row.name.isBlank()) {
            rowErrors.add(error(tenantId, batchId, row.rowNumber, "SUPPLIER", "name", "SUPPLIER_NAME_REQUIRED", "Supplier name is required", "ERROR", raw(row.rawMetadata, "Supplier", "Party Name", "Ledger Name"), "Map the supplier/party name column."));
            return rowErrors;
        }
        validateEmail(tenantId, batchId, row.rowNumber, row.email).ifPresent(rowErrors::add);
        var gstin = normalizedValue(row.gstin);
        if (gstin != null) {
            var previousName = seenGstins.putIfAbsent(gstin, normalizedValue(row.name));
            var existing = suppliers.findByTenantIdAndGstinIgnoreCase(tenantId, row.gstin);
            if (previousName != null && !previousName.equals(normalizedValue(row.name))
                || existing.isPresent() && !Objects.equals(normalizedValue(existing.get().name), normalizedValue(row.name))) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "SUPPLIER", "gstin", "DUPLICATE_GSTIN", "Supplier GSTIN belongs to conflicting party names", "ERROR", row.gstin, "Keep one supplier per GSTIN or correct the GSTIN."));
            }
            if (!validGstin(row.gstin)) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "SUPPLIER", "gstin", "BAD_GSTIN", "GSTIN should be 15 alphanumeric characters", "ERROR", row.gstin, "Use a 15-character GSTIN or leave it blank if unavailable."));
            }
        } else {
            var namePhone = normalizeKey(row.name + "|" + (row.phone == null ? "" : row.phone));
            if (row.phone != null && !row.phone.isBlank() && !seenNamePhones.add(namePhone)) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "SUPPLIER", "phone", "DUPLICATE_SUPPLIER_NAME_PHONE", "Supplier name/phone appears more than once without GSTIN", "WARNING", row.phone, "Merge duplicate supplier rows or add GSTIN."));
            }
        }
        return rowErrors;
    }

    private Optional<ImportError> validateEmail(UUID tenantId, UUID batchId, int rowNumber, String email) {
        if (email == null || email.isBlank() || email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            return Optional.empty();
        }
        return Optional.of(error(tenantId, batchId, rowNumber, "PARTY", "email", "BAD_EMAIL", "Email format is invalid", "ERROR", email, "Use a valid email like name@example.com or leave it blank."));
    }

    private Optional<Customer> findExistingCustomer(UUID tenantId, StagingCustomer row) {
        var byGstin = normalizedValue(row.gstin) == null ? Optional.<Customer>empty() : customers.findByTenantIdAndGstinIgnoreCase(tenantId, row.gstin);
        return byGstin.isPresent() ? byGstin : customers.findByTenantIdAndNameIgnoreCase(tenantId, row.name);
    }

    private Optional<Supplier> findExistingSupplier(UUID tenantId, StagingSupplier row) {
        var byGstin = normalizedValue(row.gstin) == null ? Optional.<Supplier>empty() : suppliers.findByTenantIdAndGstinIgnoreCase(tenantId, row.gstin);
        return byGstin.isPresent() ? byGstin : suppliers.findByTenantIdAndNameIgnoreCase(tenantId, row.name);
    }

    private String normalizedValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeKey(value);
    }

    private DomainEnums.NegativeStockImportPolicy resolveNegativeStockPolicy(ImportBatch batch, DomainEnums.NegativeStockImportPolicy requestedPolicy) {
        if (batch.mappingJson == null) {
            batch.mappingJson = new LinkedHashMap<>();
        }
        if (requestedPolicy != null) {
            batch.mappingJson.put(NEGATIVE_STOCK_POLICY_KEY, requestedPolicy.name());
            return requestedPolicy;
        }
        var raw = batch.mappingJson.get(NEGATIVE_STOCK_POLICY_KEY);
        if (raw instanceof String value && !value.isBlank()) {
            try {
                return DomainEnums.NegativeStockImportPolicy.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                batch.mappingJson.put(NEGATIVE_STOCK_POLICY_KEY, DomainEnums.NegativeStockImportPolicy.BLOCK.name());
            }
        }
        return DomainEnums.NegativeStockImportPolicy.BLOCK;
    }

    private DomainEnums.VoucherStockImpactMode resolveVoucherStockImpactMode(ImportBatch batch, DomainEnums.VoucherStockImpactMode requestedMode) {
        if (batch.mappingJson == null) {
            batch.mappingJson = new LinkedHashMap<>();
        }
        if (requestedMode != null) {
            batch.mappingJson.put(VOUCHER_STOCK_IMPACT_MODE_KEY, requestedMode.name());
            return requestedMode;
        }
        var raw = batch.mappingJson.get(VOUCHER_STOCK_IMPACT_MODE_KEY);
        if (raw instanceof String value && !value.isBlank()) {
            try {
                return DomainEnums.VoucherStockImpactMode.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                batch.mappingJson.remove(VOUCHER_STOCK_IMPACT_MODE_KEY);
            }
        }
        var defaultMode = batch.importPurpose == DomainEnums.ImportPurpose.TRANSACTION_IMPORT
            && latestCommittedStockSnapshot(batch.tenantId).isPresent()
            ? DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY
            : DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_AND_STOCK_MOVEMENTS;
        batch.mappingJson.put(VOUCHER_STOCK_IMPACT_MODE_KEY, defaultMode.name());
        return defaultMode;
    }

    private boolean voucherStockImpactConfirmed(ImportBatch batch) {
        return batch.mappingJson != null
            && Boolean.parseBoolean(String.valueOf(batch.mappingJson.getOrDefault(VOUCHER_STOCK_IMPACT_CONFIRMED_KEY, false)));
    }

    private Optional<LatestStockSnapshot> latestCommittedStockSnapshot(UUID tenantId) {
        return batches.findFirstByTenantIdAndImportPurposeAndStatusOrderByCommittedAtDesc(
                tenantId,
                DomainEnums.ImportPurpose.STOCK_SNAPSHOT,
                DomainEnums.ImportStatus.COMMITTED
            )
            .map(snapshot -> new LatestStockSnapshot(snapshot, effectiveSnapshotDate(snapshot)));
    }

    private LocalDate effectiveSnapshotDate(ImportBatch snapshot) {
        if (snapshot.mappingJson != null) {
            var raw = snapshot.mappingJson.get("snapshotDate");
            if (raw != null && !String.valueOf(raw).isBlank()) {
                try {
                    return LocalDate.parse(String.valueOf(raw));
                } catch (Exception ignored) {
                    // Older imports fall back to their commit timestamp.
                }
            }
        }
        return snapshot.committedAt == null
            ? LocalDate.now(ZoneOffset.UTC)
            : snapshot.committedAt.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private void initializeVoucherStockImpactContext(ImportBatch batch, DomainEnums.VoucherStockImpactMode requestedMode, boolean confirmed) {
        var mode = resolveVoucherStockImpactMode(batch, requestedMode);
        var movementRows = stagingStockMovements.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(batch.tenantId, batch.id);
        updateVoucherStockImpactContext(batch, movementRows, mode, confirmed, latestCommittedStockSnapshot(batch.tenantId));
    }

    private void updateVoucherStockImpactContext(
        ImportBatch batch,
        List<StagingStockMovement> movementRows,
        DomainEnums.VoucherStockImpactMode mode,
        boolean confirmed,
        Optional<LatestStockSnapshot> latestSnapshot
    ) {
        if (batch.mappingJson == null) {
            batch.mappingJson = new LinkedHashMap<>();
        }
        var dates = movementRows.stream().map(row -> row.movementDate).filter(Objects::nonNull).sorted().toList();
        var hasSnapshot = latestSnapshot.isPresent();
        batch.mappingJson.put(VOUCHER_STOCK_IMPACT_MODE_KEY, mode.name());
        batch.mappingJson.put(VOUCHER_STOCK_IMPACT_CONFIRMED_KEY, confirmed);
        batch.mappingJson.put("hasStockSnapshot", hasSnapshot);
        batch.mappingJson.put("latestSnapshotDate", latestSnapshot.map(snapshot -> snapshot.date().toString()).orElse(""));
        batch.mappingJson.put("recommendedVoucherStockImpactMode", hasSnapshot
            ? DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY.name()
            : DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_AND_STOCK_MOVEMENTS.name());
        batch.mappingJson.put("voucherDateFrom", dates.isEmpty() ? "" : dates.getFirst().toString());
        batch.mappingJson.put("voucherDateTo", dates.isEmpty() ? "" : dates.getLast().toString());
        batch.mappingJson.put("warningAboutDoubleCounting", hasSnapshot && batch.importPurpose == DomainEnums.ImportPurpose.TRANSACTION_IMPORT ? DOUBLE_COUNT_WARNING : "");
    }

    private List<ImportError> voucherStockImpactIssues(
        UUID tenantId,
        UUID batchId,
        ImportBatch batch,
        List<StagingStockMovement> movementRows,
        DomainEnums.VoucherStockImpactMode mode,
        boolean confirmed,
        Optional<LatestStockSnapshot> latestSnapshot
    ) {
        if (batch.importPurpose != DomainEnums.ImportPurpose.TRANSACTION_IMPORT || movementRows.isEmpty() || latestSnapshot.isEmpty()) {
            return List.of();
        }
        var snapshotDate = latestSnapshot.get().date();
        var historicalRows = movementRows.stream()
            .filter(row -> row.movementDate == null || !row.movementDate.isAfter(snapshotDate))
            .map(row -> row.rowNumber)
            .toList();
        var rawValue = "latestSnapshotDate=" + snapshotDate + "; affectedRows=" + historicalRows.size();
        if (mode == DomainEnums.VoucherStockImpactMode.BLOCK_IF_SNAPSHOT_EXISTS) {
            return List.of(error(tenantId, batchId, 0, "IMPORT_BATCH", "voucherStockImpactMode", "STOCK_SNAPSHOT_EXISTS", "A stock snapshot already exists. Choose invoice-only mode or transaction-history mode to avoid double-counting stock.", "ERROR", rawValue, "Choose CREATE_INVOICES_ONLY or APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE."));
        }
        if (mode == DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY) {
            return List.of(error(tenantId, batchId, 0, "IMPORT_BATCH", "voucherStockImpactMode", "VOUCHER_STOCK_MOVEMENTS_SKIPPED", "Closing Stock has already been imported. Voucher invoices will be created without changing stock.", "WARNING", rawValue, "This is the recommended mode for historical vouchers after a closing-stock import."));
        }
        if (mode == DomainEnums.VoucherStockImpactMode.APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE) {
            return historicalRows.isEmpty() ? List.of() : List.of(error(tenantId, batchId, 0, "IMPORT_BATCH", "voucherStockImpactMode", "VOUCHERS_BEFORE_SNAPSHOT_SKIPPED", historicalRows.size() + " voucher row(s) on or before the latest snapshot date will not affect stock.", "WARNING", rawValue, "Only vouchers after " + snapshotDate + " will create stock movements."));
        }
        if (!historicalRows.isEmpty() && !confirmed) {
            return List.of(error(tenantId, batchId, 0, "IMPORT_BATCH", "voucherStockImpactMode", "SNAPSHOT_STOCK_IMPACT_CONFIRMATION_REQUIRED", DOUBLE_COUNT_WARNING, "ERROR", rawValue, "Explicitly confirm transaction-history mode or choose invoice-only mode."));
        }
        return historicalRows.isEmpty() ? List.of() : List.of(error(tenantId, batchId, 0, "IMPORT_BATCH", "voucherStockImpactMode", "VOUCHER_STOCK_DOUBLE_COUNT_RISK", DOUBLE_COUNT_WARNING, "WARNING", rawValue, "The override is audited. Confirm the resulting stock against Tally."));
    }

    private boolean shouldCreateVoucherStockMovement(DomainEnums.VoucherStockImpactMode mode, LocalDate latestSnapshotDate, StagingStockMovement row) {
        return switch (mode) {
            case CREATE_INVOICES_ONLY -> false;
            case APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE -> latestSnapshotDate == null || (row.movementDate != null && row.movementDate.isAfter(latestSnapshotDate));
            case BLOCK_IF_SNAPSHOT_EXISTS -> latestSnapshotDate == null;
            case CREATE_INVOICES_AND_STOCK_MOVEMENTS -> true;
        };
    }

    private String voucherStockMovementAction(DomainEnums.VoucherStockImpactMode mode, LocalDate latestSnapshotDate, StagingStockMovement row) {
        if (shouldCreateVoucherStockMovement(mode, latestSnapshotDate, row)) {
            return "CREATE_STOCK_MOVEMENT";
        }
        return mode == DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY
            ? "CREATE_INVOICE_ONLY"
            : "SKIP_ON_OR_BEFORE_SNAPSHOT_DATE";
    }

    private record LatestStockSnapshot(ImportBatch batch, LocalDate date) {
    }

    private ImportError negativeOpeningStockIssue(
        UUID tenantId,
        UUID batchId,
        StagingProduct row,
        DomainEnums.NegativeStockImportPolicy policy,
        boolean tenantAllowsNegativeStock
    ) {
        if (!isTallyStockReportRow(row)) {
            return error(tenantId, batchId, row.rowNumber, "PRODUCT", "openingStock", "NEGATIVE_QUANTITY", "Opening stock cannot be negative", "ERROR", raw(row, "Opening Stock", "openingStock"), "Use a stock adjustment import if negative correction is intentional.");
        }
        var rawValue = row.openingStock == null ? raw(row, "Opening Stock", "openingStock") : row.openingStock.toPlainString();
        return switch (policy) {
            case BLOCK -> error(
                tenantId,
                batchId,
                row.rowNumber,
                "PRODUCT",
                "openingStock",
                "NEGATIVE_QUANTITY",
                "Negative stock quantity found. Choose an import resolution policy or fix the source data.",
                "ERROR",
                rawValue,
                "Choose Skip negative opening stock or enable tenant negative stock and import as-is."
            );
            case IMPORT_AS_IS -> tenantAllowsNegativeStock
                ? error(
                    tenantId,
                    batchId,
                    row.rowNumber,
                    "PRODUCT",
                    "openingStock",
                    "NEGATIVE_STOCK_IMPORTED",
                    "Negative stock will be imported because tenant allows negative stock.",
                    "WARNING",
                    rawValue,
                    "Review this product after import and reconcile stock with the physical count."
                )
                : error(
                    tenantId,
                    batchId,
                    row.rowNumber,
                    "PRODUCT",
                    "openingStock",
                    "NEGATIVE_STOCK_NOT_ALLOWED",
                    "Tenant must allow negative stock before importing negative opening stock.",
                    "ERROR",
                    rawValue,
                    "Enable negative stock in business settings or choose Skip negative opening stock."
                );
            case SKIP_STOCK_MOVEMENT -> error(
                tenantId,
                batchId,
                row.rowNumber,
                "PRODUCT",
                "openingStock",
                "NEGATIVE_STOCK_SKIPPED",
                "Product will be imported, but negative opening stock movement will be skipped.",
                "WARNING",
                rawValue,
                "Product imported, negative opening stock skipped."
            );
        };
    }

    private DomainEnums.ImportPurpose inferImportPurpose(UUID tenantId, UUID batchId) {
        var productRows = stagingProducts.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId);
        if (productRows.stream().anyMatch(this::isTallyStockReportRow)) {
            return DomainEnums.ImportPurpose.STOCK_SNAPSHOT;
        }
        if (!stagingStockMovements.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId).isEmpty()) {
            return DomainEnums.ImportPurpose.TRANSACTION_IMPORT;
        }
        if (productRows.stream().anyMatch(row -> row.openingStock != null)) {
            return DomainEnums.ImportPurpose.OPENING_BALANCE;
        }
        return DomainEnums.ImportPurpose.MASTER_IMPORT;
    }

    private boolean isStockSnapshotRow(ImportBatch batch, StagingProduct row) {
        return batch.importPurpose == DomainEnums.ImportPurpose.STOCK_SNAPSHOT && isTallyStockReportRow(row);
    }

    private boolean isTallyStockReportRow(StagingProduct row) {
        var source = raw(row, "Source Entity");
        return "DSPSTKCL".equalsIgnoreCase(source == null ? "" : source.trim());
    }

    private void addStockSnapshotPreview(
        UUID tenantId,
        StagingProduct row,
        DomainEnums.NegativeStockImportPolicy negativeStockPolicy,
        boolean tenantAllowsNegativeStock,
        Map<String, Object> map
    ) {
        var importedStock = row.openingStock;
        var warehouse = warehouseForImportRow(tenantId, row.warehouseName);
        var existingProduct = findExistingProduct(tenantId, row);
        var currentStock = existingProduct.isPresent() && warehouse != null
            ? stockLedger.currentStock(tenantId, existingProduct.get().id, warehouse.id)
            : BigDecimal.ZERO;
        var delta = importedStock == null ? null : importedStock.subtract(currentStock);
        var snapshotMatch = stockSnapshotMatch(tenantId, row, existingProduct);
        map.put("snapshotType", "Stock Snapshot Import");
        map.put("currentStock", currentStock);
        map.put("importedStock", importedStock);
        map.put("delta", delta);
        map.put("matchStatus", snapshotMatch.status());
        map.put("action", stockSnapshotAction(importedStock, delta, negativeStockPolicy, tenantAllowsNegativeStock, snapshotMatch.status()));
        if (snapshotMatch.reviewWarning() != null && !snapshotMatch.reviewWarning().isBlank()) {
            map.put("reviewWarning", snapshotMatch.reviewWarning());
        }
    }

    private StockSnapshotMatch stockSnapshotMatch(UUID tenantId, StagingProduct row, Optional<Product> existingProduct) {
        if (existingProduct.isPresent()) {
            return new StockSnapshotMatch("MATCH_EXISTING_PRODUCT", "");
        }
        var normalizedName = CatalogService.normalizeName(row.productName);
        var unitId = unitIdForCode(tenantId, row.unitCode).orElse(null);
        if (normalizedName == null) {
            return new StockSnapshotMatch("CREATE_NEW_PRODUCT", "");
        }
        var sameNameProducts = products.findByTenantIdAndNormalizedNameIgnoreCase(tenantId, normalizedName);
        if (unitId == null && !sameNameProducts.isEmpty()) {
            var unitMessage = row.unitCode == null || row.unitCode.isBlank()
                ? "Missing unit prevents exact matching."
                : "Imported unit may be a different unit or not configured for exact matching.";
            return new StockSnapshotMatch("POSSIBLE_DUPLICATE_REVIEW", unitMessage + " Review existing product units before importing this stock snapshot row.");
        }
        if (unitId != null && sameNameProducts.stream().anyMatch(product -> !unitId.equals(product.baseUnitId))) {
            return new StockSnapshotMatch("POSSIBLE_DUPLICATE_REVIEW", "Same normalized product name exists with a different unit. Review before importing to avoid creating a duplicate product.");
        }
        var possibleMatches = possibleSnapshotDuplicateProducts(tenantId, row, unitId);
        if (!possibleMatches.isEmpty()) {
            return new StockSnapshotMatch("POSSIBLE_DUPLICATE_REVIEW", "Similar existing product found: " + possibleMatches.getFirst().name + ". Exact matches are reused automatically; possible duplicates require review before merging.");
        }
        return new StockSnapshotMatch("CREATE_NEW_PRODUCT", "");
    }

    private record StockSnapshotMatch(String status, String reviewWarning) {
    }

    private String stockSnapshotAction(
        BigDecimal importedStock,
        BigDecimal delta,
        DomainEnums.NegativeStockImportPolicy negativeStockPolicy,
        boolean tenantAllowsNegativeStock,
        String matchStatus
    ) {
        if ("POSSIBLE_DUPLICATE_REVIEW".equals(matchStatus)) {
            return "BLOCKED_POSSIBLE_DUPLICATE_REVIEW";
        }
        if (importedStock != null && importedStock.compareTo(BigDecimal.ZERO) < 0) {
            if (negativeStockPolicy == DomainEnums.NegativeStockImportPolicy.SKIP_STOCK_MOVEMENT) {
                return "SKIP_NEGATIVE_STOCK";
            }
            if (negativeStockPolicy == DomainEnums.NegativeStockImportPolicy.IMPORT_AS_IS && tenantAllowsNegativeStock) {
                return "CREATE_SNAPSHOT_ADJUSTMENT";
            }
            return "BLOCKED_NEGATIVE_STOCK";
        }
        if (delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return "NO_CHANGE";
        }
        return "CREATE_SNAPSHOT_ADJUSTMENT";
    }

    private Optional<UUID> unitIdForCode(UUID tenantId, String unitCode) {
        if (unitCode == null || unitCode.isBlank()) {
            return Optional.empty();
        }
        return units.findByTenantIdAndCodeIgnoreCase(tenantId, unitCode.trim().toUpperCase(Locale.ROOT)).map(unit -> unit.id);
    }

    private Optional<Product> findByTallyExternalId(UUID tenantId, StagingProduct row) {
        var externalId = raw(row, "Tally GUID", "GUID", "MASTERID", "Master ID", "Tally Master ID");
        if (externalId == null || externalId.isBlank()) {
            return Optional.empty();
        }
        var normalizedExternalId = externalId.trim();
        return products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).stream()
            .filter(product -> product.rawMetadata != null)
            .filter(product -> raw(product.rawMetadata, "Tally GUID", "GUID", "MASTERID", "Master ID", "Tally Master ID") != null)
            .filter(product -> normalizedExternalId.equalsIgnoreCase(raw(product.rawMetadata, "Tally GUID", "GUID", "MASTERID", "Master ID", "Tally Master ID").trim()))
            .findFirst();
    }

    private List<Product> possibleSnapshotDuplicateProducts(UUID tenantId, StagingProduct row, UUID importedUnitId) {
        var normalizedName = CatalogService.normalizeName(row.productName);
        if (normalizedName == null || normalizedName.isBlank()) {
            return List.of();
        }
        return products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).stream()
            .filter(product -> product.normalizedName != null && !product.normalizedName.isBlank())
            .filter(product -> importedUnitId == null || importedUnitId.equals(product.baseUnitId))
            .filter(product -> productNameSimilarity(normalizedName, product.normalizedName) >= 0.88)
            .toList();
    }

    private void addRateValidationIssues(
        ImportBatch batch,
        UUID tenantId,
        UUID batchId,
        StagingStockMovement row,
        DomainEnums.MovementType parsedType,
        List<ImportError> rowErrors
    ) {
        if (!isTallyPurchaseRow(batch, row) || (parsedType != DomainEnums.MovementType.PURCHASE && parsedType != DomainEnums.MovementType.RETURN_OUT)) {
            if (row.rate == null || row.rate.compareTo(BigDecimal.ZERO) <= 0) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "rate", "INVALID_RATE", "Rate must be positive", "ERROR", raw(row.rawMetadata, "Rate", "Unit Price", "Price"), "Use a positive numeric rate."));
            }
            return;
        }

        var source = purchaseRateSource(row);
        var auditValue = purchaseRateAuditValue(row);
        switch (source) {
            case "DERIVED_FROM_AMOUNT" -> {
                if (row.rate == null || row.rate.compareTo(BigDecimal.ZERO) <= 0) {
                    rowErrors.add(invalidPurchaseRate(tenantId, batchId, row, auditValue));
                } else {
                    rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "rate", "PURCHASE_RATE_DERIVED_FROM_AMOUNT", "Purchase rate was derived from amount and quantity", "WARNING", auditValue, "Review the calculated rate shown in preview before commit."));
                }
            }
            case "SIGN_NORMALIZED" -> {
                if (row.rate == null || row.rate.compareTo(BigDecimal.ZERO) <= 0) {
                    rowErrors.add(invalidPurchaseRate(tenantId, batchId, row, auditValue));
                } else {
                    rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "rate", "PURCHASE_RATE_SIGN_NORMALIZED", "Negative purchase rate sign was normalized to a positive cost rate", "WARNING", auditValue, "No file change is required; verify the voucher direction in Tally."));
                }
            }
            case "ZERO_COST_ITEM" -> rowErrors.add(error(tenantId, batchId, row.rowNumber, "VOUCHER", "rate", "ZERO_COST_PURCHASE_ITEM", "Item has zero purchase rate/amount. It will be imported as a free or scheme item.", "WARNING", auditValue, "Confirm that this is a genuine free, scheme, or sample quantity."));
            case "RATE_FIELD" -> {
                if (row.rate == null || row.rate.compareTo(BigDecimal.ZERO) <= 0) {
                    rowErrors.add(invalidPurchaseRate(tenantId, batchId, row, auditValue));
                }
            }
            default -> rowErrors.add(invalidPurchaseRate(tenantId, batchId, row, auditValue));
        }
    }

    private ImportError invalidPurchaseRate(UUID tenantId, UUID batchId, StagingStockMovement row, String auditValue) {
        return error(
            tenantId,
            batchId,
            row.rowNumber,
            "VOUCHER",
            "rate",
            "INVALID_PURCHASE_RATE",
            "Purchase rate is missing or invalid and could not be derived from amount and quantity.",
            "ERROR",
            auditValue,
            "Correct the Tally item rate/amount or confirm that the line is a genuine zero-cost item."
        );
    }

    private boolean isTallyPurchaseRow(ImportBatch batch, StagingStockMovement row) {
        return batch != null
            && batch.sourceType == DomainEnums.SourceType.TALLY_XML
            && row != null
            && (DomainEnums.MovementType.PURCHASE.name().equals(row.movementType)
                || DomainEnums.MovementType.RETURN_OUT.name().equals(row.movementType));
    }

    private String purchaseRateSource(StagingStockMovement row) {
        var source = raw(row.rawMetadata, "Rate Source");
        if (source != null && !source.isBlank()) {
            return source.trim().toUpperCase(Locale.ROOT);
        }
        if (row.rate != null && row.rate.compareTo(BigDecimal.ZERO) > 0) {
            return "RATE_FIELD";
        }
        var rawRate = raw(row.rawMetadata, "Raw Rate", "Rate", "Unit Price", "Price");
        var rawAmount = raw(row.rawMetadata, "Raw Amount", "Amount");
        var parsedRate = decimal(rawRate);
        var amount = decimal(rawAmount);
        var quantity = row.quantity == null
            ? decimal(raw(row.rawMetadata, "Raw Quantity", "Qty", "Billed Quantity", "Actual Quantity"))
            : row.quantity;
        if (row.rate != null && row.rate.compareTo(BigDecimal.ZERO) < 0) {
            row.rate = row.rate.abs();
            return rememberPurchaseRateSource(row, "SIGN_NORMALIZED");
        }
        if (parsedRate != null && parsedRate.compareTo(BigDecimal.ZERO) < 0) {
            row.rate = parsedRate.abs();
            return rememberPurchaseRateSource(row, "SIGN_NORMALIZED");
        }
        if (amount != null && amount.compareTo(BigDecimal.ZERO) != 0 && quantity != null && quantity.compareTo(BigDecimal.ZERO) != 0) {
            row.rate = amount.abs().divide(quantity.abs(), 2, RoundingMode.HALF_UP);
            return rememberPurchaseRateSource(row, "DERIVED_FROM_AMOUNT");
        }
        var invalidRate = rawRate != null && !rawRate.isBlank() && parsedRate == null;
        var invalidAmount = rawAmount != null && !rawAmount.isBlank() && amount == null;
        if (!invalidRate && !invalidAmount && quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0
            && (parsedRate == null || parsedRate.compareTo(BigDecimal.ZERO) == 0)
            && (amount == null || amount.compareTo(BigDecimal.ZERO) == 0)) {
            row.rate = BigDecimal.ZERO;
            return rememberPurchaseRateSource(row, "ZERO_COST_ITEM");
        }
        return "INVALID";
    }

    private String rememberPurchaseRateSource(StagingStockMovement row, String source) {
        row.rawMetadata.put("Rate Source", source);
        row.rawMetadata.put("Parsed Rate", row.rate == null ? "" : row.rate.stripTrailingZeros().toPlainString());
        return source;
    }

    private String purchaseImportAction(StagingStockMovement row) {
        if (DomainEnums.MovementType.RETURN_OUT.name().equals(row.movementType)) {
            return "CREATE_PURCHASE_RETURN_MOVEMENT";
        }
        return "ZERO_COST_ITEM".equals(purchaseRateSource(row)) ? "IMPORT_FREE_ITEM" : "CREATE_PURCHASE_STOCK_MOVEMENT";
    }

    private String purchaseRateAuditValue(StagingStockMovement row) {
        return "RATE=" + Optional.ofNullable(raw(row.rawMetadata, "Raw Rate", "Rate")).orElse("")
            + "; AMOUNT=" + Optional.ofNullable(raw(row.rawMetadata, "Raw Amount", "Amount")).orElse("")
            + "; QUANTITY=" + Optional.ofNullable(raw(row.rawMetadata, "Raw Quantity", "Qty", "Billed Quantity", "Actual Quantity")).orElse("");
    }

    private double productNameSimilarity(String left, String right) {
        if (left == null || right == null) {
            return 0.0;
        }
        var a = left.trim().toUpperCase(Locale.ROOT);
        var b = right.trim().toUpperCase(Locale.ROOT);
        if (a.isBlank() || b.isBlank()) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        var max = Math.max(a.length(), b.length());
        return 1.0 - ((double) editDistance(a, b) / (double) max);
    }

    private int editDistance(String left, String right) {
        var previous = new int[right.length() + 1];
        var current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                var cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                    Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost
                );
            }
            var swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private long negativeStockRowsFound(UUID tenantId, UUID batchId) {
        return stagingProducts.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId).stream()
            .filter(row -> row.openingStock != null && row.openingStock.compareTo(BigDecimal.ZERO) < 0)
            .count();
    }

    private boolean validGstin(String value) {
        return value == null || value.isBlank() || value.trim().matches("^[0-9A-Za-z]{15}$");
    }

    private List<ImportError> projectedStockErrors(
        UUID tenantId,
        UUID batchId,
        List<StagingProduct> productRows,
        List<StagingStockMovement> movementRows
    ) {
        var rowErrors = new ArrayList<ImportError>();
        var projected = new HashMap<String, BigDecimal>();
        for (var row : productRows) {
            if (row.productName == null || row.productName.isBlank() || row.openingStock == null) {
                continue;
            }
            var key = stockProjectionKey(row.productName, effectiveWarehouseName(row.warehouseName));
            var current = projected.computeIfAbsent(key, ignored -> currentStockForImportRow(tenantId, row.productName, row.warehouseName));
            if (isTallyStockReportRow(row)) {
                projected.put(key, row.openingStock);
            } else if (row.openingStock.compareTo(BigDecimal.ZERO) > 0) {
                projected.put(key, current.add(row.openingStock));
            }
        }
        for (var row : sortedMovementRows(movementRows)) {
            if (row.productName == null || row.productName.isBlank() || row.quantity == null || row.quantity.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            DomainEnums.MovementType type;
            try {
                type = DomainEnums.MovementType.valueOf(row.movementType);
            } catch (Exception ex) {
                continue;
            }
            var key = stockProjectionKey(row.productName, effectiveWarehouseName(row.warehouseName));
            var current = projected.computeIfAbsent(key, ignored -> currentStockForImportRow(tenantId, row.productName, row.warehouseName));
            var projectedAfter = current.add(StockLedgerService.signedQuantity(type, row.quantity));
            if (projectedAfter.compareTo(BigDecimal.ZERO) < 0) {
                rowErrors.add(error(tenantId, batchId, row.rowNumber, "quantity", "INSUFFICIENT_STOCK", "Imported movement would make stock negative"));
            } else {
                projected.put(key, projectedAfter);
            }
        }
        return rowErrors;
    }

    private BigDecimal currentStockForImportRow(UUID tenantId, String productName, String warehouseName) {
        var lookupRow = new StagingProduct();
        lookupRow.productName = productName;
        lookupRow.warehouseName = warehouseName;
        var product = findExistingProduct(tenantId, lookupRow).orElse(null);
        var warehouse = warehouseForImportRow(tenantId, warehouseName);
        if (product == null || warehouse == null) {
            return BigDecimal.ZERO;
        }
        return stockLedger.currentStock(tenantId, product.id, warehouse.id);
    }

    private Warehouse warehouseForImportRow(UUID tenantId, String warehouseName) {
        if (warehouseName == null || warehouseName.isBlank()) {
            return warehouses.findByTenantIdAndNameIgnoreCase(tenantId, "Main Godown").orElse(null);
        }
        return warehouses.findByTenantIdAndNameIgnoreCase(tenantId, warehouseName).orElse(null);
    }

    private String effectiveWarehouseName(String warehouseName) {
        return warehouseName == null || warehouseName.isBlank() ? "Main Godown" : warehouseName;
    }

    private String stockProjectionKey(String productName, String warehouseName) {
        return normalizeKey(productName) + "|" + normalizeKey(warehouseName);
    }

    private String stockProjectionKey(UUID productId, UUID warehouseId) {
        return productId + "|" + warehouseId;
    }

    private void putSnapshotSummary(
        ImportBatch batch,
        int rowsProcessed,
        int rowsUnchanged,
        int adjustmentsCreated,
        int positiveAdjustments,
        int negativeAdjustments
    ) {
        if (batch.mappingJson == null) {
            batch.mappingJson = new LinkedHashMap<>();
        }
        batch.mappingJson.put("stockSnapshotRowsProcessed", rowsProcessed);
        batch.mappingJson.put("stockSnapshotRowsUnchanged", rowsUnchanged);
        batch.mappingJson.put("stockSnapshotAdjustmentsCreated", adjustmentsCreated);
        batch.mappingJson.put("stockSnapshotPositiveAdjustments", positiveAdjustments);
        batch.mappingJson.put("stockSnapshotNegativeAdjustments", negativeAdjustments);
    }

    private void putVoucherStockImpactSummary(
        ImportBatch batch,
        DomainEnums.VoucherStockImpactMode mode,
        LocalDate latestSnapshotDate,
        int voucherStockMovementsCreated,
        int skippedDueToInvoiceOnly,
        int skippedBeforeSnapshotDate,
        int salesInvoiceItemsCreated,
        int purchaseInvoiceItemsCreated
    ) {
        if (batch.mappingJson == null) {
            batch.mappingJson = new LinkedHashMap<>();
        }
        batch.mappingJson.put(VOUCHER_STOCK_IMPACT_MODE_KEY, mode.name());
        batch.mappingJson.put("voucherStockMovementsCreated", voucherStockMovementsCreated);
        batch.mappingJson.put("stockMovementsSkippedDueToInvoiceOnly", skippedDueToInvoiceOnly);
        batch.mappingJson.put("stockMovementsSkippedBeforeSnapshotDate", skippedBeforeSnapshotDate);
        batch.mappingJson.put("salesInvoiceItemsCreated", salesInvoiceItemsCreated);
        batch.mappingJson.put("purchaseInvoiceItemsCreated", purchaseInvoiceItemsCreated);
        batch.mappingJson.put("latestSnapshotDate", latestSnapshotDate == null ? "" : latestSnapshotDate.toString());
        batch.mappingJson.put("warningAboutDoubleCounting", latestSnapshotDate == null ? "" : DOUBLE_COUNT_WARNING);
    }

    private long snapshotSummaryLong(ImportBatch batch, String key) {
        if (batch.mappingJson == null || !batch.mappingJson.containsKey(key)) {
            return 0L;
        }
        var value = batch.mappingJson.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private boolean hasErrors(List<ImportError> rowErrors) {
        return rowErrors.stream().anyMatch(error -> !"WARNING".equalsIgnoreCase(error.severity));
    }

    private ArrayList<ImportError> deduplicateIssues(List<ImportError> issues) {
        var unique = new LinkedHashMap<String, ImportError>();
        for (var issue : issues) {
            var key = issue.rowNumber + "|" + normalizeKey(issue.entityType) + "|" + normalizeKey(issue.fieldName) + "|" + normalizeKey(issue.errorCode);
            unique.putIfAbsent(key, issue);
        }
        return new ArrayList<>(unique.values());
    }

    private int issueRowCount(List<ImportError> issues, String code) {
        return (int) issues.stream()
            .filter(issue -> code.equals(issue.errorCode))
            .map(issue -> issue.rowNumber)
            .distinct()
            .count();
    }

    private int tallyLedgerLinesSkipped(List<StagingStockMovement> movementRows) {
        var skippedByVoucher = new HashMap<String, Integer>();
        for (var row : movementRows) {
            var rawCount = raw(row.rawMetadata, "Ledger Lines Skipped");
            if (rawCount == null || rawCount.isBlank()) {
                continue;
            }
            try {
                var count = Integer.parseInt(rawCount.trim());
                var voucherKey = Optional.ofNullable(invoiceNumber(row.rawMetadata)).orElse("row-" + row.rowNumber)
                    + "|" + Optional.ofNullable(row.movementDate).map(LocalDate::toString).orElse("");
                skippedByVoucher.merge(voucherKey, count, Math::max);
            } catch (NumberFormatException ignored) {
                // Preserve import progress when a non-standard Tally metadata value is encountered.
            }
        }
        return skippedByVoucher.values().stream().mapToInt(Integer::intValue).sum();
    }

    private int errorSeverityCount(List<ImportError> rowErrors) {
        return (int) rowErrors.stream().filter(error -> !"WARNING".equalsIgnoreCase(error.severity)).count();
    }

    private long warningCount(UUID tenantId, UUID batchId) {
        return errors.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId).stream()
            .filter(error -> "WARNING".equalsIgnoreCase(error.severity))
            .count();
    }

    private long stagedRowCount(UUID tenantId, UUID batchId) {
        return stagingProducts.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId).size()
            + stagingCustomers.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId).size()
            + stagingSuppliers.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId).size()
            + stagingStockMovements.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(tenantId, batchId).size();
    }

    private boolean cancelledVoucher(Map<String, Object> rawMetadata) {
        var status = raw(rawMetadata, "Cancelled", "ISCANCELLED", "ISDELETED", "ACTION", "Voucher Status");
        if (status == null) {
            return false;
        }
        var lower = status.toLowerCase(Locale.ROOT);
        return lower.contains("cancel") || lower.contains("delete") || lower.equals("yes") || lower.equals("true") || lower.equals("1");
    }

    private boolean hasRaw(StagingProduct row, String... keys) {
        var value = raw(row, keys);
        return value != null && !value.isBlank();
    }

    private String raw(StagingProduct row, String... keys) {
        return row == null ? null : raw(row.rawMetadata, keys);
    }

    private String raw(Map<String, Object> rawMetadata, String... keys) {
        if (rawMetadata == null || rawMetadata.isEmpty()) {
            return null;
        }
        for (var key : keys) {
            for (var entry : rawMetadata.entrySet()) {
                if (normalizeKey(entry.getKey()).equals(normalizeKey(key))) {
                    var value = entry.getValue();
                    return value == null ? null : String.valueOf(value);
                }
            }
        }
        return null;
    }

    private ImportError error(UUID tenantId, UUID batchId, int row, String field, String code, String message) {
        return error(tenantId, batchId, row, null, field, code, message, "ERROR", null, suggestedFixFor(code));
    }

    private ImportError error(UUID tenantId, UUID batchId, int row, String entityType, String field, String code, String message, String severity, String rawValue, String suggestedFix) {
        var error = new ImportError();
        error.tenantId = tenantId;
        error.importBatchId = batchId;
        error.rowNumber = row;
        error.entityType = entityType;
        error.fieldName = field;
        error.errorCode = code;
        error.message = message;
        error.severity = severity == null || severity.isBlank() ? "ERROR" : severity;
        error.rawValue = rawValue == null ? null : rawValue.strip();
        error.suggestedFix = suggestedFix == null || suggestedFix.isBlank() ? suggestedFixFor(code) : suggestedFix;
        return error;
    }

    private String suggestedFixFor(String code) {
        if (code == null) {
            return "Review this row and correct the mapped data.";
        }
        return switch (code) {
            case "PRODUCT_NAME_REQUIRED" -> "Map item name/product name to productName.";
            case "UNIT_MISSING" -> "Add a unit such as PCS, BOX, KG, or LTR.";
            case "DUPLICATE_SKU", "DUPLICATE_BARCODE", "DUPLICATE_PRODUCT_NAME" -> "Remove the duplicate row or add a unique SKU/barcode.";
            case "DUPLICATE_GSTIN" -> "Keep one party per GSTIN or correct the GSTIN.";
            case "INVALID_QUANTITY" -> "Use a positive numeric quantity.";
            case "INVALID_RATE" -> "Use a positive numeric rate.";
            case "INVALID_PURCHASE_RATE" -> "Correct the Tally item rate/amount or confirm that the line is a genuine zero-cost item.";
            case "PURCHASE_RATE_DERIVED_FROM_AMOUNT" -> "Review the calculated rate shown in preview before commit.";
            case "PURCHASE_RATE_SIGN_NORMALIZED" -> "Verify the purchase voucher direction in Tally.";
            case "ZERO_COST_PURCHASE_ITEM" -> "Confirm that this is a genuine free, scheme, or sample quantity.";
            case "DUPLICATE_INVOICE" -> "Remove rows already imported or use the correct invoice number.";
            case "UNKNOWN_WAREHOUSE" -> "Create the warehouse/godown first or correct the name.";
            case "INSUFFICIENT_STOCK" -> "Import purchases/opening stock first or enable negative stock for this tenant.";
            case "BACKDATED_VOUCHER" -> "Review whether this historical voucher should be imported.";
            case "CANCELLED_VOUCHER_SKIPPED" -> "No action needed unless the voucher should be active.";
            default -> "Review this row and correct the mapped data.";
        };
    }

    private ErpImportAdapter adapterFor(DomainEnums.SourceType sourceType, String fileName) {
        if (sourceType == DomainEnums.SourceType.TALLY) {
            var inferred = infer(fileName);
            sourceType = switch (inferred) {
                case XML -> DomainEnums.SourceType.TALLY_XML;
                case EXCEL -> DomainEnums.SourceType.TALLY_EXCEL;
                case JSON -> DomainEnums.SourceType.JSON;
                default -> DomainEnums.SourceType.CSV;
            };
        } else if (sourceType == DomainEnums.SourceType.OTHER_ERP
            || (sourceType == DomainEnums.SourceType.CSV && fileName != null && !fileName.toLowerCase(Locale.ROOT).endsWith(".csv"))) {
            sourceType = infer(fileName);
        }
        return Optional.ofNullable(adapters.get(sourceType)).orElseGet(() -> adapters.get(DomainEnums.SourceType.CSV));
    }

    private DomainEnums.SourceType infer(String fileName) {
        var lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return DomainEnums.SourceType.EXCEL;
        }
        if (lower.endsWith(".json")) {
            return DomainEnums.SourceType.JSON;
        }
        if (lower.endsWith(".xml")) {
            return DomainEnums.SourceType.XML;
        }
        return DomainEnums.SourceType.CSV;
    }

    private String lookup(Map<String, String> row, String... keys) {
        for (var key : keys) {
            for (var entry : row.entrySet()) {
                if (normalizeKey(entry.getKey()).equals(normalizeKey(key))) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            var cleaned = value.replace(",", "").replaceAll("[^0-9.\\-]", "");
            return cleaned.isBlank() ? null : new BigDecimal(cleaned);
        } catch (Exception ex) {
            return null;
        }
    }

    private LocalDate date(String value) {
        return ImportDates.parse(value);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String str(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private String sanitize(String value) {
        return (value == null ? "upload.dat" : value).replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public static void validateUpload(MultipartFile upload, Set<String> allowedExtensions, long maxBytes) {
        if (upload == null || upload.isEmpty()) {
            throw ApiErrors.badRequest("Upload file is required");
        }
        if (upload.getSize() > maxBytes) {
            throw ApiErrors.badRequest("Upload file exceeds the allowed size of " + formatBytes(maxBytes));
        }
        var fileName = upload.getOriginalFilename();
        var extension = extension(fileName);
        if (extension.isBlank() || !allowedExtensions.contains(extension)) {
            throw ApiErrors.badRequest("Unsupported upload file type");
        }
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        var clean = fileName.toLowerCase(Locale.ROOT);
        var dot = clean.lastIndexOf('.');
        return dot < 0 || dot == clean.length() - 1 ? "" : clean.substring(dot + 1);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return (bytes / (1024L * 1024L)) + " MB";
        }
        if (bytes >= 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return bytes + " bytes";
    }

    private String safeImportError(Exception ex) {
        if (ex instanceof ApiException apiException) {
            return apiException.getMessage();
        }
        if (ex instanceof IllegalArgumentException && ex.getMessage() != null && !ex.getMessage().isBlank()) {
            return "Import file could not be parsed: " + ex.getMessage().replaceAll("[\\r\\n]", " ");
        }
        return "Import file could not be parsed";
    }

    private String invoiceNumber(Map<String, Object> rawMetadata) {
        if (rawMetadata == null) {
            return null;
        }
        var row = rawMetadata.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> String.valueOf(entry.getValue()), (left, right) -> left, LinkedHashMap::new));
        var value = lookup(row, "invoiceNumber", "Invoice Number", "Voucher No", "Voucher Number", "VOUCHERNUMBER");
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String invoiceKey(StagingStockMovement row, boolean purchase) {
        var date = row.movementDate;
        int year = date == null ? 0 : date.getYear() - (date.getMonthValue() < 4 ? 1 : 0);
        return invoiceNumber(row.rawMetadata).toLowerCase(Locale.ROOT) + "|" + year
            + (purchase ? "|" + normalizedValue(raw(row.rawMetadata, "partyName", "partyName", "Party Name", "Supplier", "Ledger Name")) : "");
    }

    private boolean existingInvoiceConflict(UUID tenantId, DomainEnums.MovementType type, String number, StagingStockMovement row) {
        if (row.movementDate == null) return false;
        if (type == DomainEnums.MovementType.SALE) return invoiceIdentity.matchSale(tenantId, number, row.movementDate).isPresent();
        if (type == DomainEnums.MovementType.PURCHASE) {
            var partyName = raw(row.rawMetadata, "partyName", "partyName", "Party Name", "Supplier", "Ledger Name");
            var supplierId = partyName == null ? null : suppliers.findByTenantIdAndNameIgnoreCase(tenantId, partyName).map(party -> party.id).orElse(null);
            return invoiceIdentity.matchPurchase(tenantId, supplierId, number, row.movementDate).isPresent();
        }
        return false;
    }
}
