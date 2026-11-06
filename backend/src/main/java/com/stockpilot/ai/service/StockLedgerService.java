package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.Product;
import com.stockpilot.ai.domain.StockMovement;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
public class StockLedgerService {
    private final Repositories.StockMovementRepository movements;
    private final Repositories.ProductRepository products;
    private final Repositories.WarehouseRepository warehouses;
    private final Repositories.UnitConversionRepository conversions;
    private final Repositories.TenantRepository tenants;
    private final AuditService auditService;
    private final StockLockService stockLocks;
    private final SensitiveDataService sensitive;
    private final IdempotencyService idempotency;

    public StockLedgerService(
        Repositories.StockMovementRepository movements,
        Repositories.ProductRepository products,
        Repositories.WarehouseRepository warehouses,
        Repositories.UnitConversionRepository conversions,
        Repositories.TenantRepository tenants,
        AuditService auditService,
        StockLockService stockLocks, SensitiveDataService sensitive, IdempotencyService idempotency
    ) {
        this.movements = movements;
        this.products = products;
        this.warehouses = warehouses;
        this.conversions = conversions;
        this.tenants = tenants;
        this.auditService = auditService;
        this.stockLocks = stockLocks;
        this.sensitive = sensitive; this.idempotency = idempotency;
    }

    public BigDecimal currentStock(UUID tenantId, UUID productId, UUID warehouseId) {
        var stock = movements.currentStock(tenantId, productId, warehouseId);
        return stock == null ? BigDecimal.ZERO : stock;
    }

    public BigDecimal stockAt(UUID tenantId, UUID productId, UUID warehouseId, LocalDate date) {
        if (date == null) {
            return currentStock(tenantId, productId, warehouseId);
        }
        var endOfDay = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        var stock = movements.stockAt(tenantId, productId, warehouseId, endOfDay);
        return stock == null ? BigDecimal.ZERO : stock;
    }

    public List<ApiDtos.StockResponse> currentStockReport(UUID productId, UUID warehouseId) {
        var tenantId = TenantContext.tenantId();
        var productList = productId == null
            ? products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)
            : List.of(products.findByTenantIdAndId(tenantId, productId).orElseThrow(() -> ApiErrors.notFound("Product not found")));
        var warehouseList = warehouseId == null
            ? warehouses.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)
            : List.of(warehouses.findByTenantIdAndId(tenantId, warehouseId).orElseThrow(() -> ApiErrors.notFound("Warehouse not found")));
        var result = new ArrayList<ApiDtos.StockResponse>();
        for (var product : productList) {
            for (var warehouse : warehouseList) {
                var current = currentStock(tenantId, product.id, warehouse.id);
                if (current.compareTo(BigDecimal.ZERO) != 0 || productId != null || warehouseId != null) {
                    result.add(new ApiDtos.StockResponse(product.id, product.name, warehouse.id, warehouse.name, current, sensitive.cost(MoneyMath.amount(current.multiply(latestCost(tenantId, product))))));
                }
            }
        }
        return result;
    }

    public Page<ApiDtos.MovementResponse> movementPage(Pageable pageable) {
        return movements.findByTenantId(TenantContext.tenantId(), pageable).map(this::response);
    }

    public List<ApiDtos.MovementResponse> productMovements(UUID productId) {
        var tenantId = TenantContext.tenantId();
        requireProduct(tenantId, productId);
        return movements.findByTenantIdAndProductIdOrderByMovementDateDesc(tenantId, productId).stream().map(this::response).toList();
    }

    @Transactional
    public StockMovement createMovement(
        UUID tenantId,
        UUID productId,
        UUID warehouseId,
        DomainEnums.MovementType type,
        BigDecimal quantity,
        UUID unitId,
        BigDecimal rate,
        String referenceType,
        UUID referenceId,
        Instant movementDate,
        String notes
    ) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) == 0) {
            throw ApiErrors.badRequest("Stock movement quantity must be non-zero");
        }
        if (type != DomainEnums.MovementType.ADJUSTMENT && quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiErrors.badRequest("Stock movement quantity must be positive for " + type);
        }
        if (type == DomainEnums.MovementType.ADJUSTMENT && (notes == null || notes.isBlank())) {
            throw ApiErrors.badRequest("Stock adjustment reason is required");
        }
        var product = products.findByTenantIdAndId(tenantId, productId).orElseThrow(() -> ApiErrors.notFound("Product not found"));
        warehouses.findByTenantIdAndId(tenantId, warehouseId).orElseThrow(() -> ApiErrors.notFound("Warehouse not found"));
        var baseQuantity = toBaseQuantity(tenantId, product, unitId, quantity);
        var signed = signedQuantity(type, baseQuantity);
        lockStockKey(tenantId, productId, warehouseId);
        var policy = enforceStockPolicy(tenantId, productId, warehouseId, type, signed);
        var movement = new StockMovement();
        movement.tenantId = tenantId;
        movement.productId = productId;
        movement.warehouseId = warehouseId;
        movement.movementType = type;
        movement.quantity = quantity;
        movement.unitId = unitId == null ? product.baseUnitId : unitId;
        movement.baseQuantity = signed;
        movement.rate = rate == null ? BigDecimal.ZERO : rate;
        movement.totalValue = quantity.abs().multiply(movement.rate);
        movement.referenceType = referenceType;
        movement.referenceId = referenceId;
        movement.movementDate = movementDate == null ? Instant.now() : movementDate;
        movement.notes = notes;
        var saved = movements.save(movement);
        auditMovement(tenantId, saved);
        if (policy.negativeStockAllowed()) {
            auditService.log(tenantId, TenantContext.userId(), "NEGATIVE_STOCK_ALLOWED", "StockMovement", saved.id, java.util.Map.of(
                "movementType", type.name(),
                "productId", productId,
                "warehouseId", warehouseId,
                "baseQuantity", signed,
                "projectedStock", policy.projectedStock()
            ));
        }
        return saved;
    }

    @Transactional
    public StockMovement createImportOpeningBalanceMovement(
        UUID tenantId,
        UUID productId,
        UUID warehouseId,
        BigDecimal quantity,
        UUID unitId,
        BigDecimal rate,
        UUID importBatchId,
        Instant movementDate,
        String notes
    ) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) == 0) {
            throw ApiErrors.badRequest("Stock movement quantity must be non-zero");
        }
        var product = products.findByTenantIdAndId(tenantId, productId).orElseThrow(() -> ApiErrors.notFound("Product not found"));
        warehouses.findByTenantIdAndId(tenantId, warehouseId).orElseThrow(() -> ApiErrors.notFound("Warehouse not found"));
        var baseQuantity = toBaseQuantity(tenantId, product, unitId, quantity);
        if (baseQuantity.compareTo(BigDecimal.ZERO) < 0 && !negativeStockAllowed(tenantId)) {
            throw ApiErrors.badRequest("Tenant must allow negative stock before importing negative opening stock");
        }
        lockStockKey(tenantId, productId, warehouseId);
        var projectedStock = currentStock(tenantId, productId, warehouseId).add(baseQuantity);
        if (baseQuantity.compareTo(BigDecimal.ZERO) < 0 && projectedStock.compareTo(BigDecimal.ZERO) < 0 && !negativeStockAllowed(tenantId)) {
            throw ApiErrors.badRequest("Imported opening balance would make current stock negative");
        }
        var movement = new StockMovement();
        movement.tenantId = tenantId;
        movement.productId = productId;
        movement.warehouseId = warehouseId;
        movement.movementType = DomainEnums.MovementType.OPENING_BALANCE;
        movement.quantity = quantity;
        movement.unitId = unitId == null ? product.baseUnitId : unitId;
        movement.baseQuantity = baseQuantity;
        movement.rate = rate == null ? BigDecimal.ZERO : rate;
        movement.totalValue = quantity.abs().multiply(movement.rate);
        movement.referenceType = "IMPORT_BATCH";
        movement.referenceId = importBatchId;
        movement.movementDate = movementDate == null ? Instant.now() : movementDate;
        movement.notes = notes;
        var saved = movements.save(movement);
        auditMovement(tenantId, saved);
        if (baseQuantity.compareTo(BigDecimal.ZERO) < 0) {
            auditService.log(tenantId, TenantContext.userId(), "NEGATIVE_STOCK_ALLOWED", "StockMovement", saved.id, java.util.Map.of(
                "movementType", DomainEnums.MovementType.OPENING_BALANCE.name(),
                "productId", productId,
                "warehouseId", warehouseId,
                "baseQuantity", baseQuantity,
                "projectedStock", projectedStock,
                "referenceType", "IMPORT_BATCH",
                "referenceId", importBatchId
            ));
        }
        return saved;
    }

    @Transactional
    public ApiDtos.MovementResponse adjust(ApiDtos.StockAdjustmentRequest request) {
        return adjust(request, null);
    }

    @Transactional
    public ApiDtos.MovementResponse adjust(ApiDtos.StockAdjustmentRequest request, String key) {
        var tenantId = TenantContext.tenantId();
        return idempotency.execute("STOCK_ADJUSTMENT", key, request, () -> performAdjustment(request),
            id -> response(movements.findByTenantIdAndId(tenantId, id).orElseThrow(() -> ApiErrors.notFound("Stock movement not found"))));
    }

    private UUID performAdjustment(ApiDtos.StockAdjustmentRequest request) {
        var tenantId = TenantContext.tenantId();
        if (request.quantityDelta().compareTo(BigDecimal.ZERO) == 0) {
            throw ApiErrors.badRequest("Stock adjustment quantity cannot be zero");
        }
        requireProduct(tenantId, request.productId());
        requireWarehouse(tenantId, request.warehouseId());
        var movement = createMovement(
            tenantId,
            request.productId(),
            request.warehouseId(),
            DomainEnums.MovementType.ADJUSTMENT,
            request.quantityDelta(),
            null,
            request.rate(),
            "STOCK_ADJUSTMENT",
            null,
            Instant.now(),
            request.notes()
        );
        auditService.logCurrent("STOCK_ADJUSTMENT", "StockMovement", movement.id, java.util.Map.of("quantityDelta", request.quantityDelta(), "reason", request.notes() == null ? "Unspecified" : request.notes()));
        return movement.id;
    }

    @Transactional
    public List<ApiDtos.MovementResponse> transfer(ApiDtos.StockTransferRequest request) {
        return transfer(request, null);
    }

    @Transactional
    public List<ApiDtos.MovementResponse> transfer(ApiDtos.StockTransferRequest request, String key) {
        var tenantId = TenantContext.tenantId();
        return idempotency.execute("STOCK_TRANSFER", key, request, () -> performTransfer(request),
            reference -> movements.findByTenantIdAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(tenantId, "STOCK_TRANSFER", reference)
                .stream().map(this::response).toList());
    }

    private UUID performTransfer(ApiDtos.StockTransferRequest request) {
        var tenantId = TenantContext.tenantId();
        if (request.sourceWarehouseId().equals(request.destinationWarehouseId())) {
            throw ApiErrors.badRequest("Source and destination warehouses must be different");
        }
        requireProduct(tenantId, request.productId());
        requireWarehouse(tenantId, request.sourceWarehouseId());
        requireWarehouse(tenantId, request.destinationWarehouseId());
        lockStockKeys(List.of(
            stockKey(tenantId, request.productId(), request.sourceWarehouseId()),
            stockKey(tenantId, request.productId(), request.destinationWarehouseId())
        ));
        var available = currentStock(tenantId, request.productId(), request.sourceWarehouseId());
        if (!negativeStockAllowed(tenantId) && available.compareTo(request.quantity()) < 0) {
            throw ApiErrors.badRequest("Insufficient stock in source warehouse");
        }
        var referenceId = UUID.randomUUID();
        var out = createMovement(tenantId, request.productId(), request.sourceWarehouseId(), DomainEnums.MovementType.TRANSFER_OUT, request.quantity(), null, BigDecimal.ZERO, "STOCK_TRANSFER", referenceId, Instant.now(), request.notes());
        var in = createMovement(tenantId, request.productId(), request.destinationWarehouseId(), DomainEnums.MovementType.TRANSFER_IN, request.quantity(), null, BigDecimal.ZERO, "STOCK_TRANSFER", referenceId, Instant.now(), request.notes());
        auditService.logCurrent("STOCK_TRANSFER", "StockMovement", referenceId, java.util.Map.of("quantity", request.quantity()));
        return referenceId;
    }

    public ApiDtos.MovementResponse response(StockMovement movement) {
        return new ApiDtos.MovementResponse(
            movement.id,
            movement.productId,
            movement.warehouseId,
            movement.movementType,
            movement.quantity,
            movement.baseQuantity,
            sensitive.cost(movement.rate),
            movement.movementDate,
            movement.referenceType
        );
    }

    BigDecimal toBaseQuantity(UUID tenantId, Product product, UUID unitId, BigDecimal quantity) {
        if (unitId == null || product.baseUnitId == null || unitId.equals(product.baseUnitId)) {
            return quantity;
        }
        return conversions.findByTenantIdAndProductIdAndFromUnitIdAndToUnitId(tenantId, product.id, unitId, product.baseUnitId)
            .map(conversion -> {
                if (conversion.multiplier == null || conversion.multiplier.signum() <= 0) {
                    throw ApiErrors.badRequest("Unit conversion must be positive for " + product.name);
                }
                return quantity.multiply(conversion.multiplier);
            })
            .orElseThrow(() -> ApiErrors.badRequest("Unit conversion is not configured for " + product.name));
    }

    public static BigDecimal signedQuantity(DomainEnums.MovementType type, BigDecimal baseQuantity) {
        return switch (type) {
            case PURCHASE, RETURN_IN, TRANSFER_IN, OPENING_BALANCE -> baseQuantity.abs();
            case SALE, RETURN_OUT, TRANSFER_OUT -> baseQuantity.abs().negate();
            case ADJUSTMENT -> baseQuantity;
        };
    }

    public StockLockService.StockKey stockKey(UUID tenantId, UUID productId, UUID warehouseId) {
        return new StockLockService.StockKey(tenantId, productId, warehouseId);
    }

    public void lockStockKey(UUID tenantId, UUID productId, UUID warehouseId) {
        stockLocks.lock(tenantId, productId, warehouseId);
    }

    public void lockStockKeys(Collection<StockLockService.StockKey> keys) {
        stockLocks.lockAll(keys);
    }

    public boolean negativeStockAllowed(UUID tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> ApiErrors.notFound("Tenant not found")).allowNegativeStock;
    }

    private BigDecimal latestCost(UUID tenantId, Product product) {
        return latestCost(tenantId, product.id, product.defaultPurchasePrice);
    }

    public BigDecimal latestCost(UUID tenantId, UUID productId, BigDecimal fallback) {
        return movements.findFirstByTenantIdAndProductIdAndMovementTypeOrderByMovementDateDesc(tenantId, productId, DomainEnums.MovementType.PURCHASE)
            .filter(movement -> movement.baseQuantity != null && movement.baseQuantity.signum() != 0)
            .filter(movement -> movement.quantity != null && movement.rate != null)
            .map(movement -> movement.rate.multiply(movement.quantity.abs())
                .divide(movement.baseQuantity.abs(), 12, java.math.RoundingMode.HALF_UP))
            .filter(rate -> rate != null && rate.compareTo(BigDecimal.ZERO) > 0)
            .orElse(fallback == null ? BigDecimal.ZERO : fallback);
    }

    BigDecimal toBaseRate(UUID tenantId, Product product, UUID unitId, BigDecimal sourceRate) {
        return sourceRate.divide(toBaseQuantity(tenantId, product, unitId, BigDecimal.ONE),
            12, java.math.RoundingMode.HALF_UP);
    }

    private StockPolicyResult enforceStockPolicy(UUID tenantId, UUID productId, UUID warehouseId, DomainEnums.MovementType type, BigDecimal signedQuantity) {
        if (signedQuantity.compareTo(BigDecimal.ZERO) >= 0) {
            return new StockPolicyResult(false, null);
        }
        var tenant = tenants.findById(tenantId).orElseThrow(() -> ApiErrors.notFound("Tenant not found"));
        var projected = currentStock(tenantId, productId, warehouseId).add(signedQuantity);
        if (tenant.allowNegativeStock) {
            return new StockPolicyResult(projected.compareTo(BigDecimal.ZERO) < 0, projected);
        }
        if (projected.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiErrors.badRequest(type + " would make current stock negative");
        }
        return new StockPolicyResult(false, projected);
    }

    private void requireProduct(UUID tenantId, UUID productId) {
        products.findByTenantIdAndId(tenantId, productId).orElseThrow(() -> ApiErrors.notFound("Product not found"));
    }

    private void requireWarehouse(UUID tenantId, UUID warehouseId) {
        warehouses.findByTenantIdAndId(tenantId, warehouseId).orElseThrow(() -> ApiErrors.notFound("Warehouse not found"));
    }

    private void auditMovement(UUID tenantId, StockMovement movement) {
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("movementType", movement.movementType.name());
        details.put("productId", movement.productId);
        details.put("warehouseId", movement.warehouseId);
        details.put("baseQuantity", movement.baseQuantity);
        details.put("referenceType", movement.referenceType == null ? "" : movement.referenceType);
        details.put("referenceId", movement.referenceId == null ? "" : movement.referenceId);
        auditService.log(tenantId, TenantContext.userId(), "STOCK_MOVEMENT_CREATED", "StockMovement", movement.id, details);
    }

    private record StockPolicyResult(boolean negativeStockAllowed, BigDecimal projectedStock) {
    }
}
