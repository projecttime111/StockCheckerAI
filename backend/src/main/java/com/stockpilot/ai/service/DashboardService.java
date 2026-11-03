package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.SalesInvoice;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Service
public class DashboardService {
    private final Repositories.ProductRepository products;
    private final Repositories.SalesInvoiceRepository salesInvoices;
    private final Repositories.SalesInvoiceItemRepository salesItems;
    private final Repositories.CustomerRepository customers;
    private final Repositories.SupplierRepository suppliers;
    private final Repositories.StockMovementRepository movements;
    private final StockLedgerService stockLedger;
    private final DataQualityService dataQuality;
    private final OutstandingService outstanding;

    public DashboardService(
        Repositories.ProductRepository products,
        Repositories.SalesInvoiceRepository salesInvoices,
        Repositories.SalesInvoiceItemRepository salesItems,
        Repositories.CustomerRepository customers,
        Repositories.SupplierRepository suppliers,
        Repositories.StockMovementRepository movements,
        StockLedgerService stockLedger,
        DataQualityService dataQuality,
        OutstandingService outstanding
    ) {
        this.products = products;
        this.salesInvoices = salesInvoices;
        this.salesItems = salesItems;
        this.customers = customers;
        this.suppliers = suppliers;
        this.movements = movements;
        this.stockLedger = stockLedger;
        this.dataQuality = dataQuality;
        this.outstanding = outstanding;
    }

    public ApiDtos.DashboardSummary summary() {
        var tenantId = TenantContext.tenantId();
        var today = LocalDate.now(ZoneOffset.UTC);
        var start = today.withDayOfMonth(1);
        var monthlySales = salesInvoices.salesTotal(tenantId, start, today);
        var grossProfit = grossProfit(start, today);
        return new ApiDtos.DashboardSummary(
            totalStockValue(),
            monthlySales,
            grossProfit,
            lowStock().size(),
            deadStock().stream().map(ApiDtos.DeadStockResponse::stockValue).reduce(BigDecimal.ZERO, BigDecimal::add),
            outstandingReceivables(),
            grossMarginPercent(monthlySales, grossProfit),
            products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).size(),
            customers.findByTenantIdOrderByNameAsc(tenantId).size(),
            suppliers.findByTenantIdOrderByNameAsc(tenantId).size(),
            dataQuality.summary().qualityScore()
        );
    }

    public BigDecimal totalStockValue() {
        var tenantId = TenantContext.tenantId();
        return products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).stream()
            .map(product -> stockLedger.currentStock(tenantId, product.id, null).multiply(latestCost(tenantId, product.id, product.defaultPurchasePrice)))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<ApiDtos.LowStockResponse> lowStock() {
        var tenantId = TenantContext.tenantId();
        return products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).stream()
            .map(product -> new ApiDtos.LowStockResponse(product.id, product.name, stockLedger.currentStock(tenantId, product.id, null), product.reorderPoint))
            .filter(row -> row.currentStock().compareTo(row.reorderPoint()) <= 0)
            .toList();
    }

    public List<ApiDtos.DeadStockResponse> deadStock() {
        var tenantId = TenantContext.tenantId();
        var cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(90);
        return products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).stream()
            .map(product -> {
                var stock = stockLedger.currentStock(tenantId, product.id, null);
                var lastSale = movements.findFirstByTenantIdAndProductIdAndMovementTypeOrderByMovementDateDesc(tenantId, product.id, DomainEnums.MovementType.SALE)
                    .map(m -> m.movementDate.atZone(ZoneOffset.UTC).toLocalDate())
                    .orElse(null);
                var value = stock.multiply(latestCost(tenantId, product.id, product.defaultPurchasePrice));
                var explanation = lastSale == null
                    ? "No sales found in the available history while stock is still on hand."
                    : "Last sale was on " + lastSale + ", outside the 90 day movement window.";
                return new ApiDtos.DeadStockResponse(product.id, product.name, stock, value, lastSale, "Discount, bundle, stop reordering, or transfer to a faster branch", explanation);
            })
            .filter(row -> row.stockQuantity().compareTo(BigDecimal.ZERO) > 0)
            .filter(row -> row.lastSoldDate() == null || row.lastSoldDate().isBefore(cutoff))
            .sorted(Comparator.comparing(ApiDtos.DeadStockResponse::stockValue).reversed())
            .toList();
    }

    public BigDecimal outstandingReceivables() {
        var tenantId = TenantContext.tenantId();
        return customers.findByTenantIdOrderByNameAsc(tenantId).stream()
            .map(customer -> outstanding.customerOutstanding(tenantId, customer.id))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<ApiDtos.TrendPoint> salesTrend() {
        var tenantId = TenantContext.tenantId();
        var end = LocalDate.now(ZoneOffset.UTC);
        var start = end.minusDays(29);
        var totals = new LinkedHashMap<LocalDate, BigDecimal>();
        for (int i = 0; i < 30; i++) {
            totals.put(start.plusDays(i), BigDecimal.ZERO);
        }
        for (var invoice : salesInvoices.findByTenantIdAndInvoiceDateBetweenOrderByInvoiceDateAsc(tenantId, start, end)) {
            totals.put(invoice.invoiceDate, totals.getOrDefault(invoice.invoiceDate, BigDecimal.ZERO).add(invoice.totalAmount));
        }
        return totals.entrySet().stream().map(entry -> new ApiDtos.TrendPoint(entry.getKey(), entry.getValue())).toList();
    }

    public List<ApiDtos.TrendPoint> profitTrend() {
        var end = LocalDate.now(ZoneOffset.UTC);
        var start = end.minusDays(29);
        var rows = new java.util.ArrayList<ApiDtos.TrendPoint>();
        for (int i = 0; i < 30; i++) {
            var day = start.plusDays(i);
            rows.add(new ApiDtos.TrendPoint(day, grossProfit(day, day)));
        }
        return rows;
    }

    public List<ApiDtos.DashboardProductMetric> topProducts() {
        var tenantId = TenantContext.tenantId();
        var aggregates = new HashMap<UUID, ProductAggregate>();
        for (var invoice : salesInvoices.findByTenantId(tenantId)) {
            for (var item : salesItems.findByTenantIdAndSalesInvoiceId(tenantId, invoice.id)) {
                var product = products.findByTenantIdAndId(tenantId, item.productId).orElse(null);
                var quantity = product == null ? item.quantity : stockLedger.toBaseQuantity(tenantId, product, item.unitId, item.quantity).abs();
                var aggregate = aggregates.computeIfAbsent(item.productId, ignored -> new ProductAggregate());
                aggregate.quantity = aggregate.quantity.add(quantity);
                aggregate.revenue = aggregate.revenue.add(item.lineTotal == null ? BigDecimal.ZERO : item.lineTotal);
                aggregate.lastSoldDate = aggregate.lastSoldDate == null || invoice.invoiceDate.isAfter(aggregate.lastSoldDate)
                    ? invoice.invoiceDate
                    : aggregate.lastSoldDate;
            }
        }
        return aggregates.entrySet().stream()
            .map(entry -> productMetric(tenantId, entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(ApiDtos.DashboardProductMetric::revenue).reversed())
            .limit(10)
            .toList();
    }

    public List<ApiDtos.DashboardProductMetric> slowMovingProducts() {
        var tenantId = TenantContext.tenantId();
        var cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(30);
        var rows = new ArrayList<ApiDtos.DashboardProductMetric>();
        for (var product : products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)) {
            var aggregate = new ProductAggregate();
            for (var movement : movements.findByTenantIdAndProductIdOrderByMovementDateDesc(tenantId, product.id)) {
                if (movement.movementType == DomainEnums.MovementType.SALE) {
                    var saleDate = movement.movementDate.atZone(ZoneOffset.UTC).toLocalDate();
                    if (aggregate.lastSoldDate == null || saleDate.isAfter(aggregate.lastSoldDate)) {
                        aggregate.lastSoldDate = saleDate;
                    }
                    if (!saleDate.isBefore(cutoff)) {
                        aggregate.quantity = aggregate.quantity.add(movement.baseQuantity.abs());
                    }
                }
            }
            var stock = stockLedger.currentStock(tenantId, product.id, null);
            if (stock.compareTo(BigDecimal.ZERO) > 0 && (aggregate.lastSoldDate == null || aggregate.quantity.compareTo(product.reorderPoint == null ? BigDecimal.ZERO : product.reorderPoint) <= 0)) {
                rows.add(productMetric(tenantId, product.id, aggregate));
            }
        }
        rows.sort(Comparator.comparing(ApiDtos.DashboardProductMetric::stockValue).reversed());
        return rows.stream().limit(10).toList();
    }

    public BigDecimal grossProfit(LocalDate from, LocalDate to) {
        var tenantId = TenantContext.tenantId();
        BigDecimal profit = BigDecimal.ZERO;
        for (SalesInvoice invoice : salesInvoices.findByTenantIdAndInvoiceDateBetweenOrderByInvoiceDateAsc(tenantId, from, to)) {
            for (var item : salesItems.findByTenantIdAndSalesInvoiceId(tenantId, invoice.id)) {
                var product = products.findByTenantIdAndId(tenantId, item.productId).orElse(null);
                var soldBaseQuantity = product == null ? item.quantity : stockLedger.toBaseQuantity(tenantId, product, item.unitId, item.quantity).abs();
                var netRevenue = item.lineTotal.subtract(item.taxAmount == null ? BigDecimal.ZERO : item.taxAmount);
                var costRate = item.costRate == null ? BigDecimal.ZERO : item.costRate;
                profit = profit.add(netRevenue.subtract(costRate.multiply(soldBaseQuantity)));
            }
        }
        return profit;
    }

    private BigDecimal latestCost(java.util.UUID tenantId, java.util.UUID productId, BigDecimal fallback) {
        return stockLedger.latestCost(tenantId, productId, fallback);
    }

    private BigDecimal grossMarginPercent(BigDecimal monthlySales, BigDecimal grossProfit) {
        if (monthlySales == null || monthlySales.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return grossProfit.multiply(BigDecimal.valueOf(100)).divide(monthlySales, 2, RoundingMode.HALF_UP);
    }

    private ApiDtos.DashboardProductMetric productMetric(UUID tenantId, UUID productId, ProductAggregate aggregate) {
        var product = products.findByTenantIdAndId(tenantId, productId).orElse(null);
        var name = product == null ? "Unknown product" : product.name;
        var stock = product == null ? BigDecimal.ZERO : stockLedger.currentStock(tenantId, productId, null);
        var stockValue = product == null ? BigDecimal.ZERO : stock.multiply(latestCost(tenantId, productId, product.defaultPurchasePrice));
        return new ApiDtos.DashboardProductMetric(productId, name, aggregate.quantity, aggregate.revenue, stock, stockValue, aggregate.lastSoldDate);
    }

    private static final class ProductAggregate {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal revenue = BigDecimal.ZERO;
        private LocalDate lastSoldDate;
    }
}
