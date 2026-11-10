package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.repo.AnalyticsRepository;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@Service
public class ReorderEngineService {
    private final Repositories.ProductRepository products;
    private final Repositories.WarehouseRepository warehouses;
    private final Repositories.PurchaseOrderRepository purchaseOrders;
    private final Repositories.PurchaseOrderItemRepository purchaseOrderItems;
    private final Repositories.SupplierRepository suppliers;
    private final StockLedgerService stockLedger;
    private final AuditService audit;
    private final SalesHistoryService salesHistory;
    private final AnalyticsRepository analytics;

    public ReorderEngineService(
        Repositories.ProductRepository products,
        Repositories.WarehouseRepository warehouses,
        Repositories.PurchaseOrderRepository purchaseOrders,
        Repositories.PurchaseOrderItemRepository purchaseOrderItems,
        Repositories.SupplierRepository suppliers,
        StockLedgerService stockLedger,
        AuditService audit, SalesHistoryService salesHistory, AnalyticsRepository analytics
    ) {
        this.products = products;
        this.warehouses = warehouses;
        this.purchaseOrders = purchaseOrders;
        this.purchaseOrderItems = purchaseOrderItems;
        this.suppliers = suppliers;
        this.stockLedger = stockLedger;
        this.audit = audit;
        this.salesHistory = salesHistory;
        this.analytics = analytics;
    }

    public List<ApiDtos.SmartReorderSuggestion> suggestions() {
        var tenantId = TenantContext.tenantId();
        var rows = new ArrayList<ApiDtos.SmartReorderSuggestion>();
        var today = LocalDate.now(ZoneOffset.UTC);
        var history = salesHistory.demandHistory(tenantId,today);
        var warehouseRows = warehouses.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).stream()
            .sorted(Comparator.comparing(w -> w.id.toString())).toList();
        var pendingPurchases = analytics.findPendingPurchasesByTenantId(tenantId);
        var pendingSales = analytics.findPendingSalesByTenantId(tenantId);
        for (var product : products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)) {
            var stockByWarehouse = new LinkedHashMap<UUID, BigDecimal>();
            for (var warehouse : warehouseRows) stockByWarehouse.put(warehouse.id, stockLedger.currentStock(tenantId, product.id, warehouse.id));
            var unallocatedSales = pendingSales.stream().filter(item -> product.id.equals(item.productId))
                .map(item -> stockLedger.toBaseQuantity(tenantId, product, item.unitId, item.quantity))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            var allocatedSales = allocateUnassignedSales(unallocatedSales, stockByWarehouse);
            for (var warehouse : warehouseRows) {
                var current = stockByWarehouse.get(warehouse.id);
                var last7 = history.quantity(product.id, warehouse.id, today.minusDays(6), today);
                var last30 = history.quantity(product.id, warehouse.id, today.minusDays(29), today);
                var previous30 = history.quantity(product.id, warehouse.id, today.minusDays(59), today.minusDays(30));
                var avgDaily = history.quantity(product.id, warehouse.id, history.windowStart(today,90), history.windowEnd(today)).divide(BigDecimal.valueOf(history.observationDays(today,90)), 3, RoundingMode.HALF_UP);
                var recentDaily = history.quantity(product.id,warehouse.id,history.windowStart(today,30),history.windowEnd(today)).divide(BigDecimal.valueOf(history.observationDays(today,30)), 3, RoundingMode.HALF_UP);
                var demand = recentDaily.compareTo(BigDecimal.ZERO) > 0 ? recentDaily : avgDaily;
                var pendingPo = pendingPurchases.stream()
                    .filter(item -> product.id.equals(item.productId) && warehouse.id.equals(item.warehouseId))
                    .map(item -> stockLedger.toBaseQuantity(tenantId, product, item.unitId, item.quantity))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                var pendingSo = allocatedSales.getOrDefault(warehouse.id, BigDecimal.ZERO);
                var availableAfterOrders = current.add(pendingPo).subtract(pendingSo);
                var reorderPoint = demand.multiply(BigDecimal.valueOf(product.leadTimeDays)).add(product.safetyStock == null ? BigDecimal.ZERO : product.safetyStock);
                var rawNeed = reorderPoint.add(demand.multiply(BigDecimal.valueOf(30))).subtract(availableAfterOrders);
                if (rawNeed.compareTo(BigDecimal.ZERO) <= 0 && current.compareTo(reorderPoint) > 0) {
                    continue;
                }
                var recommended = rawNeed.signum() <= 0 ? BigDecimal.ZERO : rawNeed.max(product.minimumOrderQuantity == null ? BigDecimal.ZERO : product.minimumOrderQuantity);
                var stockout = ForecastMath.stockoutDate(current, demand, today);
                var trend = previous30.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : last30.subtract(previous30).multiply(BigDecimal.valueOf(100)).divide(previous30, 0, RoundingMode.HALF_UP);
                var reasonPrefix = trend.compareTo(BigDecimal.ZERO) > 0
                    ? "Sales increased " + trend + "% in the last 4 weeks"
                    : "Current stock plus pending purchases is near the reorder point";
                var reason = (history.coverageConfirmed()?"":"Provisional estimate: sales-history coverage is unconfirmed. ")+reasonPrefix
                    + ". Demand window ends " + history.windowEnd(today)
                    + ". Formula: reorder point = avg demand "
                    + demand
                    + " x lead time "
                    + product.leadTimeDays
                    + " + safety stock "
                    + (product.safetyStock == null ? BigDecimal.ZERO : product.safetyStock)
                    + "; recommended = reorder point + 30-day demand - current stock - pending purchases + pending sales, floored by MOQ.";
                if (history.lastSale(product.id, warehouse.id, today) == null) {
                    reason += " No dated sales history is available for this product and warehouse; demand is unknown, not proof of zero sales.";
                }
                if (unallocatedSales.signum() > 0) {
                    reason += " Unallocated sales demand: " + unallocatedSales + " base units across this product's warehouses; "
                        + pendingSo + " allocated here for planning against available stock in stable warehouse order; fulfillment is not assigned.";
                }
                if (pendingPurchases.stream().anyMatch(item -> product.id.equals(item.productId) && item.warehouseId == null)) {
                    reason += " Purchase orders without a destination warehouse are excluded until assigned.";
                }
                rows.add(new ApiDtos.SmartReorderSuggestion(product.id, product.name, warehouse.id, warehouse.name, current, avgDaily, last7, last30, stockout, product.leadTimeDays, product.safetyStock, product.minimumOrderQuantity, pendingPo, pendingSo, recommended.setScale(3, RoundingMode.HALF_UP), reason, history.lastSale(product.id, warehouse.id, today) != null));
            }
        }
        rows.sort(Comparator.comparing(ApiDtos.SmartReorderSuggestion::expectedStockoutDate, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    @Transactional
    public ApiDtos.DraftPurchaseOrderResponse createDraftPurchaseOrder(ApiDtos.DraftPurchaseOrderRequest request) {
        var tenantId = TenantContext.tenantId();
        var candidates = suggestions().stream()
            .filter(row -> request.productIds() == null || request.productIds().isEmpty() || request.productIds().contains(row.productId()))
            .filter(row -> row.recommendedQuantity().compareTo(BigDecimal.ZERO) > 0)
            .toList();
        if (candidates.isEmpty()) {
            throw ApiErrors.badRequest("No reorder suggestions are available for a draft purchase order");
        }
        var supplierId = request.supplierId();
        if (supplierId == null) {
            supplierId = suppliers.findByTenantIdOrderByNameAsc(tenantId).stream().findFirst().map(s -> s.id).orElse(null);
        } else {
            suppliers.findByTenantIdAndId(tenantId, supplierId).orElseThrow(() -> ApiErrors.notFound("Supplier not found"));
        }
        var order = new PurchaseOrder();
        order.tenantId = tenantId;
        order.supplierId = supplierId;
        order.status = "DRAFT";
        order.orderNumber = "DRAFT-PO-" + LocalDate.now(ZoneOffset.UTC) + "-" + System.currentTimeMillis();
        purchaseOrders.save(order);
        BigDecimal total = BigDecimal.ZERO;
        for (var row : candidates) {
            var product = products.findByTenantIdAndId(tenantId, row.productId()).orElseThrow();
            var item = new PurchaseOrderItem();
            item.tenantId = tenantId;
            item.purchaseOrderId = order.id;
            item.productId = product.id;
            item.warehouseId = row.warehouseId();
            item.quantity = row.recommendedQuantity();
            item.unitId = product.baseUnitId;
            item.rate = product.defaultPurchasePrice == null ? BigDecimal.ZERO : product.defaultPurchasePrice;
            item.lineTotal = item.quantity.multiply(item.rate);
            purchaseOrderItems.save(item);
            total = total.add(item.lineTotal);
        }
        order.totalAmount = total;
        purchaseOrders.save(order);
        audit.logCurrent("DRAFT_PURCHASE_ORDER_CREATED", "PurchaseOrder", order.id, Map.of("itemCount", candidates.size(), "total", total));
        return new ApiDtos.DraftPurchaseOrderResponse(order.id, order.orderNumber, order.totalAmount, candidates.size());
    }

    /** Allocate each unassigned order unit exactly once; this is a planning allocation, not fulfillment. */
    static Map<UUID, BigDecimal> allocateUnassignedSales(BigDecimal quantity, LinkedHashMap<UUID, BigDecimal> stock) {
        var result = new LinkedHashMap<UUID, BigDecimal>();
        var remaining = quantity.max(BigDecimal.ZERO);
        for (var entry : stock.entrySet()) {
            var allocated = remaining.min(entry.getValue().max(BigDecimal.ZERO));
            result.put(entry.getKey(), allocated);
            remaining = remaining.subtract(allocated);
        }
        if (remaining.signum() > 0 && !stock.isEmpty()) {
            result.merge(stock.keySet().iterator().next(), remaining, BigDecimal::add);
        }
        return result;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
