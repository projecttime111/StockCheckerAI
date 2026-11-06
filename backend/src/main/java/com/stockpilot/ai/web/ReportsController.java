package com.stockpilot.ai.web;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.service.AuditService;
import com.stockpilot.ai.service.DashboardService;
import com.stockpilot.ai.service.DeadStockActionService;
import com.stockpilot.ai.service.ReorderEngineService;
import com.stockpilot.ai.service.StockLedgerService;
import com.stockpilot.ai.service.OutstandingService;
import com.stockpilot.ai.service.SensitiveDataService;
import com.stockpilot.ai.service.PermissionService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportsController {
    private final StockLedgerService stockLedger;
    private final DashboardService dashboard;
    private final DeadStockActionService deadStock;
    private final ReorderEngineService reorder;
    private final Repositories.ProductRepository products;
    private final Repositories.CustomerRepository customers;
    private final Repositories.SupplierRepository suppliers;
    private final Repositories.SalesInvoiceRepository salesInvoices;
    private final Repositories.PurchaseInvoiceRepository purchaseInvoices;
    private final OutstandingService outstanding;
    private final Repositories.AuditLogRepository auditLogs;
    private final AuditService auditService;
    private final SensitiveDataService sensitive;
    private final PermissionService permissions;

    public ReportsController(
        StockLedgerService stockLedger,
        DashboardService dashboard,
        DeadStockActionService deadStock,
        ReorderEngineService reorder,
        Repositories.ProductRepository products,
        Repositories.CustomerRepository customers,
        Repositories.SupplierRepository suppliers,
        Repositories.SalesInvoiceRepository salesInvoices,
        Repositories.PurchaseInvoiceRepository purchaseInvoices,
        OutstandingService outstanding,
        Repositories.AuditLogRepository auditLogs,
        AuditService auditService, SensitiveDataService sensitive, PermissionService permissions
    ) {
        this.stockLedger = stockLedger;
        this.dashboard = dashboard;
        this.deadStock = deadStock;
        this.reorder = reorder;
        this.products = products;
        this.customers = customers;
        this.suppliers = suppliers;
        this.salesInvoices = salesInvoices;
        this.purchaseInvoices = purchaseInvoices;
        this.outstanding = outstanding;
        this.auditLogs = auditLogs;
        this.auditService = auditService;
        this.sensitive = sensitive;
        this.permissions = permissions;
    }

    @GetMapping("/export/{report}")
    @PreAuthorize("@permissionService.has('reports.export')")
    public ResponseEntity<byte[]> export(@PathVariable String report, @RequestParam(defaultValue = "csv") String format) {
        if (!"csv".equalsIgnoreCase(format)) {
            throw ApiErrors.badRequest("Only CSV export is available in the MVP");
        }
        var slug = report.toLowerCase(Locale.ROOT).replace('_', '-');
        var required = switch (slug) {
            case "products" -> "products.view";
            case "customers" -> "customers.view_outstanding";
            case "suppliers" -> "suppliers.view";
            case "purchases", "purchase-invoices" -> "purchases.view_cost";
            case "sales", "sales-invoices" -> "sales.view";
            case "audit", "audit-logs" -> "audit.view";
            case "reorder", "reorder-suggestions" -> "reorder.view";
            case "dead-stock" -> "dead_stock.view";
            default -> "stock.view";
        };
        if (!permissions.has(required)) throw ApiErrors.forbidden("You do not have permission to export this report");
        var csv = switch (slug) {
            case "current-stock", "stock" -> currentStockCsv();
            case "low-stock" -> lowStockCsv();
            case "dead-stock" -> deadStockCsv();
            case "reorder", "reorder-suggestions" -> reorderCsv();
            case "products" -> productsCsv();
            case "customers" -> customersCsv();
            case "suppliers" -> suppliersCsv();
            case "sales", "sales-invoices" -> salesCsv();
            case "purchases", "purchase-invoices" -> purchasesCsv();
            case "top-products" -> productMetricCsv("product_id,product_name,sold_quantity,revenue,current_stock,stock_value,last_sold_date", dashboard.topProducts());
            case "slow-moving-products" -> productMetricCsv("product_id,product_name,recent_sale_quantity,revenue,current_stock,stock_value,last_sold_date", dashboard.slowMovingProducts());
            case "audit-logs", "audit" -> auditCsv();
            default -> throw ApiErrors.notFound("Report not found");
        };
        auditService.logCurrent("REPORT_EXPORTED", "Report", null, Map.of("report", slug, "format", format));
        var headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename(slug + ".csv").build());
        return ResponseEntity.ok().headers(headers).body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private String currentStockCsv() {
        var builder = new StringBuilder("product_id,product_name,warehouse_id,warehouse_name,current_stock,stock_value\n");
        stockLedger.currentStockReport(null, null).forEach(row -> append(builder, row.productId(), row.productName(), row.warehouseId(), row.warehouseName(), row.currentStock(), row.stockValue()));
        return builder.toString();
    }

    private String lowStockCsv() {
        var builder = new StringBuilder("product_id,product_name,current_stock,reorder_point\n");
        dashboard.lowStock().forEach(row -> append(builder, row.productId(), row.productName(), row.currentStock(), row.reorderPoint()));
        return builder.toString();
    }

    private String deadStockCsv() {
        var builder = new StringBuilder("product_id,product_name,warehouse,quantity,stock_value,last_sold_date,days_since_last_sale,average_monthly_sale,blocked_capital,recommended_action\n");
        deadStock.list().forEach(row -> append(builder, row.productId(), row.productName(), row.warehouseName(), row.quantity(), sensitive.cost(row.stockValue()), row.lastSoldDate(), row.daysSinceLastSale(), row.averageMonthlySale(), sensitive.cost(row.blockedCapital()), row.recommendedAction()));
        return builder.toString();
    }

    private String reorderCsv() {
        var builder = new StringBuilder("product_id,product_name,warehouse,current_stock,average_daily_demand,last_7_days,last_30_days,expected_stockout,supplier_lead_time,safety_stock,minimum_order_quantity,pending_purchase,pending_sales,recommended_quantity,reason\n");
        reorder.suggestions().forEach(row -> append(builder, row.productId(), row.productName(), row.warehouseName(), row.currentStock(), row.averageDailyDemand(), row.last7DaysDemand(), row.last30DaysDemand(), row.expectedStockoutDate(), row.supplierLeadTimeDays(), row.safetyStock(), row.minimumOrderQuantity(), row.pendingPurchaseQuantity(), row.pendingSalesQuantity(), row.recommendedQuantity(), row.reason()));
        return builder.toString();
    }

    private String productsCsv() {
        var builder = new StringBuilder("product_id,sku,name,normalized_name,hsn,gst_percent,purchase_price,sales_price,reorder_point,active\n");
        products.findByTenantIdAndActiveTrueOrderByNameAsc(TenantContext.tenantId()).forEach(row -> append(builder, row.id, row.sku, row.name, row.normalizedName, row.hsnCode, row.gstPercentage, sensitive.cost(row.defaultPurchasePrice), row.defaultSalesPrice, row.reorderPoint, row.active));
        return builder.toString();
    }

    private String customersCsv() {
        var tenantId = TenantContext.tenantId();
        var builder = new StringBuilder("customer_id,name,phone,email,gstin,credit_limit,outstanding\n");
        customers.findByTenantIdOrderByNameAsc(tenantId).forEach(row -> append(builder, row.id, row.name, row.phone, row.email, row.gstin, row.creditLimit, outstanding.customerOutstanding(tenantId, row.id)));
        return builder.toString();
    }

    private String suppliersCsv() {
        var tenantId = TenantContext.tenantId();
        var builder = new StringBuilder("supplier_id,name,phone,email,gstin,credit_days,opening_balance,current_payable\n");
        suppliers.findByTenantIdOrderByNameAsc(tenantId).forEach(row -> append(builder, row.id, row.name, row.phone, row.email, row.gstin, row.creditDays, row.openingBalance, outstanding.supplierPayable(tenantId, row.id)));
        return builder.toString();
    }

    private String salesCsv() {
        var builder = new StringBuilder("invoice_id,invoice_number,invoice_date,customer_id,warehouse_id,subtotal,tax_amount,discount_amount,total_amount\n");
        salesInvoices.findByTenantId(TenantContext.tenantId()).forEach(row -> append(builder, row.id, row.invoiceNumber, row.invoiceDate, row.customerId, row.warehouseId, row.subtotal, row.taxAmount, row.discountAmount, row.totalAmount));
        return builder.toString();
    }

    private String purchasesCsv() {
        var builder = new StringBuilder("invoice_id,invoice_number,invoice_date,supplier_id,warehouse_id,subtotal,tax_amount,total_amount\n");
        purchaseInvoices.findByTenantId(TenantContext.tenantId()).forEach(row -> append(builder, row.id, row.invoiceNumber, row.invoiceDate, row.supplierId, row.warehouseId, row.subtotal, row.taxAmount, row.totalAmount));
        return builder.toString();
    }

    private String productMetricCsv(String header, java.util.List<com.stockpilot.ai.web.dto.ApiDtos.DashboardProductMetric> rows) {
        var builder = new StringBuilder(header).append('\n');
        rows.forEach(row -> append(builder, row.productId(), row.productName(), row.quantity(), row.revenue(), row.stockQuantity(), sensitive.cost(row.stockValue()), row.lastSoldDate()));
        return builder.toString();
    }

    private String auditCsv() {
        var builder = new StringBuilder("audit_id,created_at,actor_user_id,action,entity_type,entity_id,details\n");
        auditLogs.findByTenantIdOrderByCreatedAtDesc(TenantContext.tenantId()).forEach(row -> append(builder, row.id, row.createdAt, row.actorUserId, row.action, row.entityType, row.entityId, sensitive.auditDetails(row.detailsJson)));
        return builder.toString();
    }

    private void append(StringBuilder builder, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(cell(values[i]));
        }
        builder.append('\n');
    }

    private String cell(Object value) {
        if (value == null) {
            return "";
        }
        var text = String.valueOf(value);
        // Quoting CSV syntax alone does not stop spreadsheet formula evaluation.
        if (value instanceof CharSequence && !text.isEmpty()) {
            var significant = text.stripLeading();
            if ((!significant.isEmpty() && "=+-@".indexOf(significant.charAt(0)) >= 0)
                || text.chars().anyMatch(c -> c == '\t' || c == '\r' || c == '\n')) {
                text = "'" + text;
            }
        }
        if (text.contains("\"") || text.contains(",") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
