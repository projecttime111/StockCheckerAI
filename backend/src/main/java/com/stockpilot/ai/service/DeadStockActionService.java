package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class DeadStockActionService {
    private final Repositories.ProductRepository products;
    private final Repositories.WarehouseRepository warehouses;
    private final StockLedgerService stockLedger;
    private final AuditService audit;
    private final SalesHistoryService salesHistory;

    public DeadStockActionService(
        Repositories.ProductRepository products,
        Repositories.WarehouseRepository warehouses,
        StockLedgerService stockLedger,
        AuditService audit, SalesHistoryService salesHistory
    ) {
        this.products = products;
        this.warehouses = warehouses;
        this.stockLedger = stockLedger;
        this.audit = audit;
        this.salesHistory = salesHistory;
    }

    public List<ApiDtos.DeadStockActionResponse> list() {
        var tenantId = TenantContext.tenantId();
        var today = LocalDate.now(ZoneOffset.UTC);
        var rows = new ArrayList<ApiDtos.DeadStockActionResponse>();
        var history = salesHistory.history(tenantId);
        for (var product : products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)) {
            for (var warehouse : warehouses.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)) {
                var stock = stockLedger.currentStock(tenantId, product.id, warehouse.id);
                if (stock.compareTo(BigDecimal.ZERO) <= 0) continue;
                var lastSale = history.lastSale(product.id, warehouse.id, today);
                var daysSince = lastSale == null ? 9999 : java.time.temporal.ChronoUnit.DAYS.between(lastSale, today);
                if (daysSince < 90) continue;
                var sold90 = history.quantity(product.id, warehouse.id, today.minusDays(89), today);
                var avgMonthly = sold90.divide(BigDecimal.valueOf(3), 3, RoundingMode.HALF_UP);
                var value = stock.multiply(stockLedger.latestCost(tenantId, product.id, product.defaultPurchasePrice));
                var action = actionFor(daysSince, avgMonthly);
                rows.add(new ApiDtos.DeadStockActionResponse(product.id, product.name, warehouse.name, stock, value, lastSale, daysSince, avgMonthly, value, action));
            }
        }
        rows.sort(Comparator.comparing(ApiDtos.DeadStockActionResponse::blockedCapital).reversed());
        return rows;
    }

    @Transactional
    public Map<String, Object> recordAction(UUID productId, ApiDtos.DeadStockActionRequest request) {
        var tenantId = TenantContext.tenantId();
        var product = products.findByTenantIdAndId(tenantId, productId).orElseThrow(() -> ApiErrors.notFound("Product not found"));
        var allowed = Set.of("apply discount", "bundle with fast-moving item", "transfer to another warehouse", "stop reordering", "return to supplier", "mark as clearance item");
        if (!allowed.contains(request.action().toLowerCase(Locale.ROOT))) {
            throw ApiErrors.badRequest("Unsupported dead-stock action");
        }
        audit.logCurrent("DEAD_STOCK_ACTION", "Product", product.id, Map.of("action", request.action(), "notes", request.notes() == null ? "" : request.notes()));
        return Map.of("productId", product.id, "action", request.action(), "status", "RECORDED");
    }

    private String actionFor(long daysSince, BigDecimal avgMonthly) {
        if (daysSince > 180) return "mark as clearance item";
        if (avgMonthly.compareTo(BigDecimal.ONE) < 0) return "bundle with fast-moving item";
        return "apply discount";
    }
}
