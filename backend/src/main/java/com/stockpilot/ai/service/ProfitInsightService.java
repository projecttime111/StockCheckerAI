package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.PurchaseInvoice;
import com.stockpilot.ai.domain.SalesInvoice;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProfitInsightService {
    private final DashboardService dashboard;
    private final Repositories.SalesInvoiceRepository salesInvoices;
    private final Repositories.SalesInvoiceItemRepository salesItems;
    private final Repositories.PurchaseInvoiceRepository purchaseInvoices;
    private final Repositories.PurchaseInvoiceItemRepository purchaseItems;
    private final Repositories.ProductRepository products;
    private final Repositories.CustomerRepository customers;
    private final Repositories.SupplierRepository suppliers;
    private final StockLedgerService stock;

    public ProfitInsightService(
        DashboardService dashboard,
        Repositories.SalesInvoiceRepository salesInvoices,
        Repositories.SalesInvoiceItemRepository salesItems,
        Repositories.PurchaseInvoiceRepository purchaseInvoices,
        Repositories.PurchaseInvoiceItemRepository purchaseItems,
        Repositories.ProductRepository products,
        Repositories.CustomerRepository customers,
        Repositories.SupplierRepository suppliers, StockLedgerService stock
    ) {
        this.dashboard = dashboard;
        this.salesInvoices = salesInvoices;
        this.salesItems = salesItems;
        this.purchaseInvoices = purchaseInvoices;
        this.purchaseItems = purchaseItems;
        this.products = products;
        this.customers = customers;
        this.suppliers = suppliers;
        this.stock = stock;
    }

    public ApiDtos.ProfitDropResponse currentMonth() {
        var today = LocalDate.now(ZoneOffset.UTC);
        var currentStart = today.withDayOfMonth(1);
        var previousStart = currentStart.minusMonths(1);
        var previousEnd = currentStart.minusDays(1);
        return analyze(currentStart, today, previousStart, previousEnd);
    }

    public ApiDtos.ProfitDropResponse analyze(LocalDate currentStart, LocalDate currentEnd, LocalDate previousStart, LocalDate previousEnd) {
        var tenantId = TenantContext.tenantId();
        var currentRevenue = salesInvoices.salesTotal(tenantId, currentStart, currentEnd);
        var previousRevenue = salesInvoices.salesTotal(tenantId, previousStart, previousEnd);
        var currentProfit = dashboard.grossProfit(currentStart, currentEnd);
        var previousProfit = dashboard.grossProfit(previousStart, previousEnd);
        var currentMargin = margin(currentProfit, currentRevenue);
        var previousMargin = margin(previousProfit, previousRevenue);
        var profitChange = currentProfit.subtract(previousProfit);
        var marginChange = currentMargin.subtract(previousMargin);

        var reasons = new ArrayList<ApiDtos.ProfitReason>();
        purchaseCostReason(tenantId, currentStart, currentEnd, previousStart, previousEnd).ifPresent(reasons::add);
        discountReason(tenantId, currentStart, currentEnd, previousStart, previousEnd).ifPresent(reasons::add);
        revenueReason(currentRevenue, previousRevenue).ifPresent(reasons::add);
        if (reasons.isEmpty()) {
            reasons.add(new ApiDtos.ProfitReason("No single major driver was isolated from available sales and purchase data", BigDecimal.ZERO, "Need richer purchase batches, discounts, returns, and customer pricing data for a deeper breakdown."));
        }
        reasons.sort(Comparator.comparing(ApiDtos.ProfitReason::impactAmount).reversed());
        var summary = profitChange.compareTo(BigDecimal.ZERO) < 0
            ? "Profit changed mainly because " + reasons.stream().limit(2).map(ApiDtos.ProfitReason::reason).collect(Collectors.joining(" and ")) + "."
            : "No profit drop is visible for the current month against the previous month.";
        var actions = List.of(
            "Review selling prices for products with increased purchase cost",
            "Reduce discounts on low-margin customers",
            "Use dead-stock bundles to recover blocked working capital",
            "Update missing purchase costs before relying on margin reports"
        );
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("currentMonthRevenue", currentRevenue);
        evidence.put("previousMonthRevenue", previousRevenue);
        evidence.put("currentMonthGrossProfit", currentProfit);
        evidence.put("previousMonthGrossProfit", previousProfit);
        evidence.put("currentMonthMarginPercent", currentMargin);
        evidence.put("previousMonthMarginPercent", previousMargin);
        return new ApiDtos.ProfitDropResponse(summary, profitChange, marginChange, reasons.stream().limit(5).toList(), actions, evidence);
    }

    public Map<String, Object> bestMarginProducts() {
        var tenantId = TenantContext.tenantId();
        var rows = salesItems.findByTenantId(tenantId).stream()
            .collect(Collectors.groupingBy(item -> item.productId, LinkedHashMap::new, Collectors.toList()))
            .entrySet().stream()
            .map(entry -> {
                var revenue = entry.getValue().stream().map(item -> item.lineTotal.subtract(item.taxAmount == null ? BigDecimal.ZERO : item.taxAmount)).reduce(BigDecimal.ZERO, BigDecimal::add);
                var cost = entry.getValue().stream().map(item -> (item.costRate == null ? BigDecimal.ZERO : item.costRate).multiply(baseQuantity(tenantId, item.productId, item.unitId, item.quantity))).reduce(BigDecimal.ZERO, BigDecimal::add);
                var margin = margin(revenue.subtract(cost), revenue);
                var product = products.findByTenantIdAndId(tenantId, entry.getKey()).map(p -> p.name).orElse("Unknown product");
                return Map.of("productName", product, "marginPercent", margin, "grossProfit", MoneyMath.amount(revenue.subtract(cost)));
            })
            .sorted((left, right) -> ((BigDecimal) right.get("marginPercent")).compareTo((BigDecimal) left.get("marginPercent")))
            .limit(5)
            .toList();
        return Map.of("topProducts", rows);
    }

    private Optional<ApiDtos.ProfitReason> purchaseCostReason(UUID tenantId, LocalDate currentStart, LocalDate currentEnd, LocalDate previousStart, LocalDate previousEnd) {
        var current = purchaseRateByProduct(tenantId, currentStart, currentEnd);
        var previous = purchaseRateByProduct(tenantId, previousStart, previousEnd);
        return current.entrySet().stream()
            .filter(entry -> previous.containsKey(entry.getKey()) && entry.getValue().compareTo(previous.get(entry.getKey())) > 0)
            .map(entry -> {
                var product = products.findByTenantIdAndId(tenantId, entry.getKey()).orElse(null);
                var delta = entry.getValue().subtract(previous.get(entry.getKey()));
                var currentSalesQty = soldQuantity(tenantId, entry.getKey(), currentStart, currentEnd);
                var impact = delta.multiply(currentSalesQty);
                var name = product == null ? "A product" : product.name;
                return new ApiDtos.ProfitReason("Purchase cost increased for fast-moving items", impact, name + " cost increased from " + previous.get(entry.getKey()) + " to " + entry.getValue());
            })
            .max(Comparator.comparing(ApiDtos.ProfitReason::impactAmount));
    }

    private Optional<ApiDtos.ProfitReason> discountReason(UUID tenantId, LocalDate currentStart, LocalDate currentEnd, LocalDate previousStart, LocalDate previousEnd) {
        var current = discountByCustomer(tenantId, currentStart, currentEnd);
        var previous = discountByCustomer(tenantId, previousStart, previousEnd);
        return current.entrySet().stream()
            .filter(entry -> entry.getValue().compareTo(previous.getOrDefault(entry.getKey(), BigDecimal.ZERO)) > 0)
            .map(entry -> {
                var customer = customers.findByTenantIdAndId(tenantId, entry.getKey()).map(c -> c.name).orElse("a customer");
                var delta = entry.getValue().subtract(previous.getOrDefault(entry.getKey(), BigDecimal.ZERO));
                return new ApiDtos.ProfitReason("Discounts increased for " + customer, delta, "Discount amount increased from " + previous.getOrDefault(entry.getKey(), BigDecimal.ZERO) + " to " + entry.getValue());
            })
            .max(Comparator.comparing(ApiDtos.ProfitReason::impactAmount));
    }

    private Optional<ApiDtos.ProfitReason> revenueReason(BigDecimal currentRevenue, BigDecimal previousRevenue) {
        if (currentRevenue.compareTo(previousRevenue) >= 0) return Optional.empty();
        return Optional.of(new ApiDtos.ProfitReason("Sales revenue decreased", previousRevenue.subtract(currentRevenue), "Revenue changed from " + previousRevenue + " to " + currentRevenue));
    }

    private Map<UUID, BigDecimal> purchaseRateByProduct(UUID tenantId, LocalDate from, LocalDate to) {
        var invoiceIds = purchaseInvoices.findByTenantId(tenantId).stream()
            .filter(invoice -> !invoice.invoiceDate.isBefore(from) && !invoice.invoiceDate.isAfter(to))
            .map(invoice -> invoice.id)
            .collect(Collectors.toSet());
        return purchaseItems.findByTenantId(tenantId).stream()
            .filter(item -> invoiceIds.contains(item.purchaseInvoiceId))
            .collect(Collectors.groupingBy(item -> item.productId, Collectors.collectingAndThen(Collectors.toList(), rows -> {
                var quantity = rows.stream().map(item -> baseQuantity(tenantId, item.productId, item.unitId, item.quantity)).reduce(BigDecimal.ZERO, BigDecimal::add);
                var value = rows.stream().map(item -> item.quantity.multiply(item.rate)).reduce(BigDecimal.ZERO, BigDecimal::add);
                return quantity.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : value.divide(quantity, 2, RoundingMode.HALF_UP);
            })));
    }

    private BigDecimal soldQuantity(UUID tenantId, UUID productId, LocalDate from, LocalDate to) {
        var invoiceById = salesInvoices.findByTenantIdAndInvoiceDateBetweenOrderByInvoiceDateAsc(tenantId, from, to).stream().map(invoice -> invoice.id).collect(Collectors.toSet());
        return salesItems.findByTenantId(tenantId).stream()
            .filter(item -> productId.equals(item.productId) && invoiceById.contains(item.salesInvoiceId))
            .map(item -> baseQuantity(tenantId, item.productId, item.unitId, item.quantity))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<UUID, BigDecimal> discountByCustomer(UUID tenantId, LocalDate from, LocalDate to) {
        var invoices = salesInvoices.findByTenantIdAndInvoiceDateBetweenOrderByInvoiceDateAsc(tenantId, from, to);
        var discountsByInvoice = new HashMap<UUID, BigDecimal>();
        for (SalesInvoice invoice : invoices) {
            var discount = salesItems.findByTenantIdAndSalesInvoiceId(tenantId, invoice.id).stream()
                .map(item -> item.discountAmount == null ? BigDecimal.ZERO : item.discountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            discountsByInvoice.put(invoice.id, discount);
        }
        return invoices.stream()
            .filter(invoice -> invoice.customerId != null)
            .collect(Collectors.groupingBy(invoice -> invoice.customerId, Collectors.mapping(invoice -> discountsByInvoice.getOrDefault(invoice.id, BigDecimal.ZERO), Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
    }

    private BigDecimal baseQuantity(UUID tenantId, UUID productId, UUID unitId, BigDecimal quantity) {
        var product = products.findByTenantIdAndId(tenantId, productId).orElse(null);
        return product == null ? quantity : stock.toBaseQuantity(tenantId, product, unitId, quantity).abs();
    }

    private BigDecimal margin(BigDecimal profit, BigDecimal revenue) {
        if (revenue == null || revenue.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return profit.multiply(BigDecimal.valueOf(100)).divide(revenue, 2, RoundingMode.HALF_UP);
    }
}
