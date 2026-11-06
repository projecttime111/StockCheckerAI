package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class OwnerActionService {
    private final DashboardService dashboard;
    private final ReorderEngineService reorder;
    private final DataQualityService dataQuality;
    private final Repositories.ImportBatchRepository batches;
    private final Repositories.PurchaseOrderRepository purchaseOrders;
    private final SensitiveDataService sensitive;
    private final PermissionService permissions;

    public OwnerActionService(
        DashboardService dashboard,
        ReorderEngineService reorder,
        DataQualityService dataQuality,
        Repositories.ImportBatchRepository batches,
        Repositories.PurchaseOrderRepository purchaseOrders, SensitiveDataService sensitive, PermissionService permissions
    ) {
        this.dashboard = dashboard;
        this.reorder = reorder;
        this.dataQuality = dataQuality;
        this.batches = batches;
        this.purchaseOrders = purchaseOrders;
        this.sensitive = sensitive;
        this.permissions = permissions;
    }

    public List<ApiDtos.ActionCard> today() {
        var tenantId = TenantContext.tenantId();
        var actions = new ArrayList<ApiDtos.ActionCard>();
        var summary = dashboard.summary();
        var reorderRows = reorder.suggestions();
        if (!reorderRows.isEmpty()) {
            var impact = reorderRows.stream().map(row -> row.recommendedQuantity().multiply(BigDecimal.TEN)).reduce(BigDecimal.ZERO, BigDecimal::add);
            actions.add(new ApiDtos.ActionCard("Reorder urgent products", reorderRows.size() + " products may hit reorder thresholds soon", impact, "/forecasts", "View reorder"));
        }
        var outstanding = dashboard.outstandingReceivables();
        if (permissions.has("customers.view_outstanding") && outstanding.compareTo(BigDecimal.ZERO) > 0) {
            actions.add(new ApiDtos.ActionCard("Follow up overdue customers", "Receivables need owner attention", outstanding, "/customers", "Open customers"));
        }
        var deadValue = dashboard.deadStock().stream().map(ApiDtos.DeadStockResponse::stockValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sensitive.canViewCost() && deadValue.compareTo(BigDecimal.ZERO) > 0) {
            actions.add(new ApiDtos.ActionCard("Clear dead stock", deadValue + " blocked in slow-moving stock", deadValue, "/dead-stock", "View dead stock"));
        }
        var importErrors = batches.findByTenantId(tenantId, PageRequest.of(0, 20)).stream().mapToInt(batch -> batch.errorCount).sum();
        if (importErrors > 0) {
            actions.add(new ApiDtos.ActionCard("Fix import errors", importErrors + " import validation issues are waiting", BigDecimal.ZERO, "/integrations/tally", "Review imports"));
        }
        var quality = dataQuality.summary();
        if (quality.productsWithMissingFields() > 0) {
            actions.add(new ApiDtos.ActionCard("Update missing product fields", quality.productsWithMissingFields() + " products need HSN, GST, unit, cost, or category cleanup", BigDecimal.ZERO, "/data-quality", "Clean products"));
        }
        var pending = purchaseOrders.findByTenantIdAndStatus(tenantId, "DRAFT").size();
        if (pending > 0) {
            actions.add(new ApiDtos.ActionCard("Confirm pending purchase orders", pending + " draft purchase orders are ready to review", BigDecimal.ZERO, "/purchases", "Open purchases"));
        }
        if (sensitive.canViewProfit() && (summary.monthlySales().compareTo(BigDecimal.ZERO) != 0 || summary.grossProfit().compareTo(BigDecimal.ZERO) != 0)) {
            actions.add(new ApiDtos.ActionCard("Review margin drop", "Check whether current profit is moving because of cost, discount, or sales mix", summary.grossProfit(), "/dashboard", "Why profit changed"));
        }
        return actions.stream().limit(8).toList();
    }
}
