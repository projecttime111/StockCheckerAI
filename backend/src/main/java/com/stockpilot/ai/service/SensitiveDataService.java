package com.stockpilot.ai.service;

import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;

/** Redaction at response boundaries; never substitute hidden values into business calculations. */
@Service
public class SensitiveDataService {
    private final PermissionService permissions;
    public SensitiveDataService(PermissionService permissions) { this.permissions = permissions; }
    public boolean canViewCost() { return permissions.has("stock.view_cost") || permissions.has("purchases.view_cost"); }
    public boolean canViewProfit() { return permissions.has("insights.profit.view") || permissions.has("sales.view_margin"); }
    public BigDecimal cost(BigDecimal value) { return canViewCost() ? value : null; }
    public BigDecimal profit(BigDecimal value) { return canViewProfit() ? value : null; }
    public java.util.Map<String, Object> auditDetails(java.util.Map<String, Object> details) {
        return canViewCost() && canViewProfit() ? (details == null ? java.util.Map.of() : details) : java.util.Map.of("restricted", true);
    }
    public ApiDtos.DashboardSummary summary(ApiDtos.DashboardSummary s) {
        return new ApiDtos.DashboardSummary(cost(s.totalStockValue()), s.monthlySales(), profit(s.grossProfit()),
            s.lowStockCount(), cost(s.deadStockValue()), permissions.has("customers.view_outstanding") ? s.outstandingReceivables() : null, profit(s.grossMarginPercent()),
            s.totalProducts(), s.totalCustomers(), s.totalSuppliers(), s.importDataQualityScore());
    }
    public ApiDtos.DeadStockResponse deadStock(ApiDtos.DeadStockResponse s) {
        return new ApiDtos.DeadStockResponse(s.productId(), s.productName(), s.stockQuantity(), cost(s.stockValue()),
            s.lastSoldDate(), s.suggestedAction(), s.explanation());
    }
    public ApiDtos.DeadStockActionResponse deadStockAction(ApiDtos.DeadStockActionResponse s) {
        return new ApiDtos.DeadStockActionResponse(s.productId(), s.productName(), s.warehouseName(), s.quantity(),
            cost(s.stockValue()), s.lastSoldDate(), s.daysSinceLastSale(), s.averageMonthlySale(), cost(s.blockedCapital()), s.recommendedAction());
    }
    public ApiDtos.DashboardProductMetric productMetric(ApiDtos.DashboardProductMetric s) {
        return new ApiDtos.DashboardProductMetric(s.productId(), s.productName(), s.quantity(), s.revenue(), s.stockQuantity(), cost(s.stockValue()), s.lastSoldDate());
    }
}
