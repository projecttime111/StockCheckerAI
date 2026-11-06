package com.stockpilot.ai.web;

import com.stockpilot.ai.service.DashboardService;
import com.stockpilot.ai.service.SensitiveDataService;
import com.stockpilot.ai.service.OwnerActionService;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService dashboard;
    private final OwnerActionService actions;
    private final SensitiveDataService sensitive;

    public DashboardController(DashboardService dashboard, OwnerActionService actions, SensitiveDataService sensitive) {
        this.dashboard = dashboard;
        this.actions = actions;
        this.sensitive = sensitive;
    }

    @GetMapping("/summary")
    @PreAuthorize("@permissionService.has('dashboard.view')")
    public ApiDtos.DashboardSummary summary() {
        return sensitive.summary(dashboard.summary());
    }

    @GetMapping("/sales-trend")
    @PreAuthorize("@permissionService.has('dashboard.view')")
    public List<ApiDtos.TrendPoint> salesTrend() {
        return dashboard.salesTrend();
    }

    @GetMapping("/profit-loss")
    @PreAuthorize("@permissionService.has('dashboard.view') and @sensitiveDataService.canViewProfit()")
    public List<ApiDtos.TrendPoint> profitLoss() {
        return dashboard.profitTrend();
    }

    @GetMapping("/low-stock")
    @PreAuthorize("@permissionService.has('dashboard.view')")
    public List<ApiDtos.LowStockResponse> lowStock() {
        return dashboard.lowStock();
    }

    @GetMapping("/dead-stock")
    @PreAuthorize("@permissionService.has('dashboard.view')")
    public List<ApiDtos.DeadStockResponse> deadStock() {
        return dashboard.deadStock().stream().map(sensitive::deadStock).toList();
    }

    @GetMapping("/outstanding")
    @PreAuthorize("@permissionService.has('dashboard.view') and @permissionService.has('customers.view_outstanding')")
    public Map<String, Object> outstanding() {
        return Map.of("outstandingReceivables", dashboard.outstandingReceivables());
    }

    @GetMapping("/actions")
    @PreAuthorize("@permissionService.has('dashboard.view')")
    public List<ApiDtos.ActionCard> actions() {
        return actions.today();
    }

    @GetMapping("/top-products")
    @PreAuthorize("@permissionService.has('dashboard.view')")
    public List<ApiDtos.DashboardProductMetric> topProducts() {
        return dashboard.topProducts().stream().map(sensitive::productMetric).toList();
    }

    @GetMapping("/slow-moving-products")
    @PreAuthorize("@permissionService.has('dashboard.view')")
    public List<ApiDtos.DashboardProductMetric> slowMovingProducts() {
        return dashboard.slowMovingProducts().stream().map(sensitive::productMetric).toList();
    }
}
