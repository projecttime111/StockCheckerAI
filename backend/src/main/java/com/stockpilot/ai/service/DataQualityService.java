package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.Product;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DataQualityService {
    private final Repositories.ProductRepository products;
    private final Repositories.ProductBarcodeRepository barcodes;
    private final Repositories.SalesInvoiceItemRepository salesItems;
    private final Repositories.PurchaseInvoiceItemRepository purchaseItems;
    private final Repositories.StockMovementRepository movements;
    private final ProductCategorizationService categorization;
    private final CatalogService catalog;
    private final StockLedgerService stockLedger;
    private final AuditService audit;
    private final ProductIntegrityService integrity;
    private final TenantWriteLockService locks;
    private final SalesHistoryService history;

    public DataQualityService(
        Repositories.ProductRepository products,
        Repositories.ProductBarcodeRepository barcodes,
        Repositories.SalesInvoiceItemRepository salesItems,
        Repositories.PurchaseInvoiceItemRepository purchaseItems,
        Repositories.StockMovementRepository movements,
        ProductCategorizationService categorization,
        CatalogService catalog,
        StockLedgerService stockLedger,
        AuditService audit, ProductIntegrityService integrity, TenantWriteLockService locks, SalesHistoryService history
    ) {
        this.products = products;
        this.barcodes = barcodes;
        this.salesItems = salesItems;
        this.purchaseItems = purchaseItems;
        this.movements = movements;
        this.categorization = categorization;
        this.catalog = catalog;
        this.stockLedger = stockLedger;
        this.audit = audit;
        this.integrity = integrity; this.locks = locks; this.history = history;
    }

    public ApiDtos.DataQualitySummary summary() {
        var tenantId = TenantContext.tenantId();
        var productRows = products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId);
        var missing = missingFields().size();
        var duplicateGroups = duplicates().size();
        var salesHistory = history.history(tenantId);
        var noRecentSales = productRows.stream()
            .filter(product -> stockLedger.currentStock(tenantId, product.id, null).compareTo(BigDecimal.ZERO) > 0)
            .filter(product -> Optional.ofNullable(salesHistory.lastSale(product.id, null, LocalDate.now(ZoneOffset.UTC)))
                .map(date -> date.isBefore(LocalDate.now(ZoneOffset.UTC).minusDays(90)))
                .orElse(true))
            .count();
        var purchaseCostProductIds = purchaseItems.findByTenantId(tenantId).stream()
            .filter(item -> item.rate != null && item.rate.compareTo(BigDecimal.ZERO) > 0)
            .map(item -> item.productId)
            .collect(Collectors.toSet());
        var noCost = productRows.stream()
            .filter(product -> product.defaultPurchasePrice == null || product.defaultPurchasePrice.compareTo(BigDecimal.ZERO) <= 0)
            .filter(product -> !purchaseCostProductIds.contains(product.id))
            .count();
        var issueCount = missing + duplicateGroups + noRecentSales + noCost;
        var quality = productRows.isEmpty() ? 100 : Math.max(0, 100 - (int) Math.min(90, issueCount * 100 / Math.max(1, productRows.size())));
        return new ApiDtos.DataQualitySummary(productRows.size(), duplicateGroups, missing, noRecentSales, noCost, quality);
    }

    public List<ApiDtos.DuplicateProductGroup> duplicates() {
        var tenantId = TenantContext.tenantId();
        var grouped = products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).stream()
            .collect(Collectors.groupingBy(product -> duplicateKey(product.name), LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(entry -> {
                var suggestions = entry.getValue().stream().map(this::suggestion).toList();
                return new ApiDtos.DuplicateProductGroup(entry.getKey(), similarity(entry.getValue()), suggestions, suggestions.getFirst());
            })
            .toList();
    }

    public List<ApiDtos.MissingProductFields> missingFields() {
        return products.findByTenantIdAndActiveTrueOrderByNameAsc(TenantContext.tenantId()).stream()
            .map(product -> {
                var issues = new ArrayList<String>();
                if (product.baseUnitId == null) issues.add("unit");
                if (product.hsnCode == null || product.hsnCode.isBlank()) issues.add("hsn");
                if (product.gstPercentage == null || product.gstPercentage.compareTo(BigDecimal.ZERO) <= 0) issues.add("gst");
                if (product.categoryId == null) issues.add("category");
                if (product.defaultPurchasePrice == null || product.defaultPurchasePrice.compareTo(BigDecimal.ZERO) <= 0) issues.add("purchaseCost");
                return issues.isEmpty() ? null : new ApiDtos.MissingProductFields(product.id, product.name, issues, suggestion(product));
            })
            .filter(Objects::nonNull)
            .toList();
    }

    @Transactional
    public ApiDtos.ProductResponse applySuggestion(UUID productId, ApiDtos.ApplyProductSuggestionRequest request) {
        var tenantId = TenantContext.tenantId();
        var product = products.findByTenantIdAndId(tenantId, productId).orElseThrow(() -> ApiErrors.notFound("Product not found"));
        var fallback = suggestion(product);
        product.name = firstNonBlank(request.normalizedName(), fallback.normalizedName(), product.name);
        product.normalizedName = CatalogService.normalizeName(product.name);
        var brand = firstNonBlank(request.brand(), fallback.brand(), null);
        if (brand != null) product.brandId = catalog.getOrCreateBrand(brand).id;
        var category = firstNonBlank(request.category(), fallback.category(), null);
        if (category != null) product.categoryId = catalog.getOrCreateCategory(category).id;
        var unit = firstNonBlank(request.unitCode(), unitCodeFromSize(fallback.unitSize()), null);
        if (product.baseUnitId == null && unit != null) product.baseUnitId = catalog.getOrCreateUnit(unit).id;
        if (request.hsnCode() != null && !request.hsnCode().isBlank()) product.hsnCode = request.hsnCode().trim();
        if (request.gstPercentage() != null) product.gstPercentage = request.gstPercentage();
        products.save(product);
        audit.logCurrent("PRODUCT_CLEANUP_APPLIED", "Product", product.id, Map.of("normalizedName", product.name));
        return catalog.response(product);
    }

    @Transactional
    public Map<String, Object> merge(ApiDtos.MergeProductsRequest request) {
        var tenantId = TenantContext.tenantId();
        if (!"MERGE PRODUCTS".equals(request.confirmation())) {
            throw ApiErrors.badRequest("Type MERGE PRODUCTS to confirm duplicate product merge");
        }
        locks.lock(tenantId);
        var target = products.findByTenantIdAndId(tenantId, request.targetProductId()).orElseThrow(() -> ApiErrors.notFound("Target product not found"));
        var merged = 0;
        for (var sourceId : request.sourceProductIds()) {
            if (sourceId.equals(target.id)) continue;
            var source = products.findByTenantIdAndId(tenantId, sourceId).orElseThrow(() -> ApiErrors.notFound("Source product not found"));
            integrity.prepareMerge(source, target);
            if (stockLedger.latestCost(tenantId, source.id, source.defaultPurchasePrice).compareTo(stockLedger.latestCost(tenantId, target.id, target.defaultPurchasePrice)) != 0) {
                throw ApiErrors.conflict("Products with different stock cost bases must be reconciled before merging");
            }
            products.moveStockMovements(tenantId, source.id, target.id);
            products.moveSalesInvoiceItems(tenantId, source.id, target.id);
            products.movePurchaseInvoiceItems(tenantId, source.id, target.id);
            for (var barcode : barcodes.findByTenantIdAndProductId(tenantId, source.id)) {
                barcode.productId = target.id;
                barcodes.save(barcode);
            }
            source.active = false;
            products.save(source);
            merged++;
        }
        audit.logCurrent("PRODUCTS_MERGED", "Product", target.id, Map.of("mergedCount", merged));
        return Map.of("targetProductId", target.id, "mergedProducts", merged);
    }

    private ApiDtos.ProductCleanupSuggestion suggestion(Product product) {
        var raw = categorization.suggest(product.name);
        var issues = new ArrayList<String>();
        if (product.baseUnitId == null) issues.add("Missing unit");
        if (product.hsnCode == null || product.hsnCode.isBlank()) issues.add("Missing HSN");
        if (product.gstPercentage == null || product.gstPercentage.compareTo(BigDecimal.ZERO) <= 0) issues.add("Missing GST");
        if (product.categoryId == null) issues.add("Missing category");
        if (product.defaultPurchasePrice == null || product.defaultPurchasePrice.compareTo(BigDecimal.ZERO) <= 0) issues.add("Missing purchase cost");
        return new ApiDtos.ProductCleanupSuggestion(product.id, product.name, raw.get("normalizedName"), raw.get("brand"), raw.get("category"), raw.get("unitSize"), issues);
    }

    private String duplicateKey(String name) {
        var suggestion = categorization.suggest(name);
        var brand = suggestion.getOrDefault("brand", "");
        var size = suggestion.getOrDefault("unitSize", "");
        var cleaned = normalizeProductName(suggestion.getOrDefault("normalizedName", name));
        if (!brand.isBlank() && !size.isBlank()) {
            return (brand + "-" + size).toLowerCase(Locale.ROOT);
        }
        return cleaned;
    }

    private String normalizeProductName(String value) {
        return (value == null ? "" : value)
            .toUpperCase(Locale.ROOT)
            .replace("MASLA", "MASALA")
            .replace("NOODLES", "NOODLE")
            .replaceAll("\\b(GM|GRAM|GMS)\\b", "G")
            .replaceAll("[^A-Z0-9]", "");
    }

    private int similarity(List<Product> group) {
        if (group.size() < 2) return 100;
        var first = normalizeProductName(group.getFirst().name);
        var worst = 100;
        for (var product : group) {
            var other = normalizeProductName(product.name);
            var max = Math.max(first.length(), other.length());
            if (max == 0) continue;
            var score = 100 - (levenshtein(first, other) * 100 / max);
            worst = Math.min(worst, score);
        }
        return Math.max(0, worst);
    }

    private int levenshtein(String a, String b) {
        var dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                var cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private String unitCodeFromSize(String unitSize) {
        if (unitSize == null || unitSize.isBlank()) return null;
        var lower = unitSize.toLowerCase(Locale.ROOT);
        if (lower.endsWith("kg")) return "KG";
        if (lower.endsWith("g")) return "G";
        if (lower.endsWith("ml")) return "ML";
        if (lower.endsWith("l")) return "L";
        return "PCS";
    }
}
