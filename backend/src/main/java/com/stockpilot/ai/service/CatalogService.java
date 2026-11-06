package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

@Service
public class CatalogService {
    private final Repositories.ProductRepository products;
    private final Repositories.CategoryRepository categories;
    private final Repositories.BrandRepository brands;
    private final Repositories.UnitRepository units;
    private final Repositories.ProductBarcodeRepository barcodes;
    private final StockLedgerService stockLedger;
    private final ProductIntegrityService integrity;
    private final TenantWriteLockService locks;
    private final SensitiveDataService sensitive;

    public CatalogService(
        Repositories.ProductRepository products,
        Repositories.CategoryRepository categories,
        Repositories.BrandRepository brands,
        Repositories.UnitRepository units,
        Repositories.ProductBarcodeRepository barcodes,
        StockLedgerService stockLedger, ProductIntegrityService integrity, TenantWriteLockService locks, SensitiveDataService sensitive
    ) {
        this.products = products;
        this.categories = categories;
        this.brands = brands;
        this.units = units;
        this.barcodes = barcodes;
        this.stockLedger = stockLedger;
        this.integrity = integrity; this.locks = locks; this.sensitive = sensitive;
    }

    public Page<ApiDtos.ProductResponse> list(String query, Pageable pageable) {
        var tenantId = TenantContext.tenantId();
        var page = query == null || query.isBlank()
            ? products.findByTenantIdAndActiveTrue(tenantId, pageable)
            : products.findByTenantIdAndActiveTrueAndNameContainingIgnoreCase(tenantId, query, pageable);
        return page.map(this::response);
    }

    public ApiDtos.ProductResponse get(UUID id) {
        return response(findProduct(id));
    }

    public Product findProduct(UUID id) {
        return products.findByTenantIdAndId(TenantContext.tenantId(), id).orElseThrow(() -> ApiErrors.notFound("Product not found"));
    }

    @Transactional
    public ApiDtos.ProductResponse create(ApiDtos.ProductRequest request) {
        var product = new Product();
        product.tenantId = TenantContext.tenantId();
        apply(product, request);
        return response(products.save(product));
    }

    @Transactional
    public ApiDtos.ProductResponse update(UUID id, ApiDtos.ProductRequest request) {
        locks.lock(TenantContext.tenantId());
        var product = findProduct(id);
        apply(product, request);
        return response(products.save(product));
    }

    @Transactional
    public void delete(UUID id) {
        var product = findProduct(id);
        product.active = false;
        products.save(product);
    }

    public ApiDtos.ProductResponse byBarcode(String barcode) {
        var tenantId = TenantContext.tenantId();
        var productBarcode = barcodes.findByTenantIdAndBarcode(tenantId, barcode).orElseThrow(() -> ApiErrors.notFound("Barcode not found"));
        return response(products.findByTenantIdAndId(tenantId, productBarcode.productId).orElseThrow());
    }

    private void apply(Product product, ApiDtos.ProductRequest request) {
        product.sku = blankToNull(request.sku());
        if (product.sku != null) {
            products.findByTenantIdAndSkuIgnoreCase(product.tenantId, product.sku)
                .filter(existing -> !existing.id.equals(product.id))
                .ifPresent(existing -> {
                    throw ApiErrors.conflict("Product SKU already exists");
                });
        }
        product.name = request.name().trim();
        product.normalizedName = normalizeName(request.name());
        product.hsnCode = blankToNull(request.hsnCode());
        product.gstPercentage = nvl(request.gstPercentage());
        product.defaultPurchasePrice = nvl(request.defaultPurchasePrice());
        product.defaultSalesPrice = nvl(request.defaultSalesPrice());
        product.reorderPoint = nvl(request.reorderPoint());
        product.safetyStock = nvl(request.safetyStock());
        product.leadTimeDays = request.leadTimeDays() == null ? 7 : request.leadTimeDays();
        product.minimumOrderQuantity = nvl(request.minimumOrderQuantity());
        var newUnitId = getOrCreateUnit(request.unitCode()).id;
        integrity.assertBaseUnitChangeAllowed(product, newUnitId);
        product.baseUnitId = newUnitId;
        product.categoryId = request.categoryName() == null || request.categoryName().isBlank() ? null : getOrCreateCategory(request.categoryName()).id;
        product.brandId = request.brandName() == null || request.brandName().isBlank() ? null : getOrCreateBrand(request.brandName()).id;
        var saved = products.save(product);
        if (request.barcode() != null && !request.barcode().isBlank()) {
            var barcodeValue = request.barcode().trim();
            var existing = barcodes.findByTenantIdAndBarcode(product.tenantId, barcodeValue);
            if (existing.isPresent() && !existing.get().productId.equals(saved.id)) {
                throw ApiErrors.conflict("Product barcode already exists");
            }
            if (existing.isEmpty()) {
                var barcode = new ProductBarcode();
                barcode.tenantId = product.tenantId;
                barcode.productId = saved.id;
                barcode.barcode = barcodeValue;
                barcodes.save(barcode);
            }
        }
    }

    ProductCategory getOrCreateCategory(String name) {
        var tenantId = TenantContext.tenantId();
        return categories.findByTenantIdAndNameIgnoreCase(tenantId, name.trim()).orElseGet(() -> {
            var category = new ProductCategory();
            category.tenantId = tenantId;
            category.name = name.trim();
            return categories.save(category);
        });
    }

    Brand getOrCreateBrand(String name) {
        var tenantId = TenantContext.tenantId();
        return brands.findByTenantIdAndNameIgnoreCase(tenantId, name.trim()).orElseGet(() -> {
            var brand = new Brand();
            brand.tenantId = tenantId;
            brand.name = name.trim();
            return brands.save(brand);
        });
    }

    UnitOfMeasure getOrCreateUnit(String code) {
        var tenantId = TenantContext.tenantId();
        var normalized = code == null || code.isBlank() ? "PCS" : code.trim().toUpperCase(Locale.ROOT);
        return units.findByTenantIdAndCodeIgnoreCase(tenantId, normalized).orElseGet(() -> {
            var unit = new UnitOfMeasure();
            unit.tenantId = tenantId;
            unit.code = normalized;
            unit.name = normalized;
            unit.baseUnit = true;
            return units.save(unit);
        });
    }

    public ApiDtos.ProductResponse response(Product product) {
        var tenantId = product.tenantId;
        var category = product.categoryId == null ? null : categories.findByTenantIdAndId(tenantId, product.categoryId).map(c -> c.name).orElse(null);
        var brand = product.brandId == null ? null : brands.findByTenantIdAndId(tenantId, product.brandId).map(b -> b.name).orElse(null);
        var unit = product.baseUnitId == null ? null : units.findByTenantIdAndId(tenantId, product.baseUnitId).map(u -> u.code).orElse(null);
        var barcode = barcodes.findByTenantIdAndProductId(tenantId, product.id).stream().findFirst().map(b -> b.barcode).orElse(null);
        var stock = stockLedger.currentStock(tenantId, product.id, null);
        return new ApiDtos.ProductResponse(
            product.id,
            product.sku,
            product.name,
            product.normalizedName,
            category,
            brand,
            unit,
            barcode,
            product.hsnCode,
            product.gstPercentage,
            sensitive.cost(product.defaultPurchasePrice),
            product.defaultSalesPrice,
            product.reorderPoint,
            stock,
            product.active
        );
    }

    static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String normalizeName(String name) {
        return name == null ? null : name.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
