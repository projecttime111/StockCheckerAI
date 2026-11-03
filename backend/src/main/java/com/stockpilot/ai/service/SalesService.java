package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.SalesInvoice;
import com.stockpilot.ai.domain.SalesInvoiceItem;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
public class SalesService {
    private final Repositories.SalesInvoiceRepository invoices;
    private final Repositories.SalesInvoiceItemRepository items;
    private final Repositories.ProductRepository products;
    private final Repositories.CustomerRepository customers;
    private final Repositories.WarehouseRepository warehouses;
    private final StockLedgerService stockLedger;
    private final AuditService auditService;

    public SalesService(
        Repositories.SalesInvoiceRepository invoices,
        Repositories.SalesInvoiceItemRepository items,
        Repositories.ProductRepository products,
        Repositories.CustomerRepository customers,
        Repositories.WarehouseRepository warehouses,
        StockLedgerService stockLedger,
        AuditService auditService
    ) {
        this.invoices = invoices;
        this.items = items;
        this.products = products;
        this.customers = customers;
        this.warehouses = warehouses;
        this.stockLedger = stockLedger;
        this.auditService = auditService;
    }

    public Page<ApiDtos.InvoiceResponse> list(Pageable pageable) {
        return invoices.findByTenantId(TenantContext.tenantId(), pageable).map(this::response);
    }

    public ApiDtos.InvoiceResponse get(UUID id) {
        return response(invoices.findByTenantIdAndId(TenantContext.tenantId(), id).orElseThrow(() -> ApiErrors.notFound("Sales invoice not found")));
    }

    @Transactional
    public ApiDtos.InvoiceResponse create(ApiDtos.SalesInvoiceRequest request) {
        var tenantId = TenantContext.tenantId();
        if (invoices.existsByTenantIdAndInvoiceNumberIgnoreCase(tenantId, request.invoiceNumber())) {
            throw ApiErrors.conflict("Sales invoice number already exists");
        }
        if (request.customerId() != null) {
            customers.findByTenantIdAndId(tenantId, request.customerId()).orElseThrow(() -> ApiErrors.notFound("Customer not found"));
        }
        warehouses.findByTenantIdAndId(tenantId, request.warehouseId()).orElseThrow(() -> ApiErrors.notFound("Warehouse not found"));
        var requestedBaseByProduct = new java.util.LinkedHashMap<UUID, BigDecimal>();
        var productById = new java.util.LinkedHashMap<UUID, com.stockpilot.ai.domain.Product>();
        for (var line : request.items()) {
            var product = products.findByTenantIdAndId(tenantId, line.productId()).orElseThrow(() -> ApiErrors.notFound("Product not found"));
            var unitId = line.unitId() == null ? product.baseUnitId : line.unitId();
            var baseQuantity = stockLedger.toBaseQuantity(tenantId, product, unitId, line.quantity()).abs();
            requestedBaseByProduct.merge(product.id, baseQuantity, BigDecimal::add);
            productById.put(product.id, product);
        }
        stockLedger.lockStockKeys(requestedBaseByProduct.keySet().stream()
            .map(productId -> stockLedger.stockKey(tenantId, productId, request.warehouseId()))
            .toList());
        if (!stockLedger.negativeStockAllowed(tenantId)) {
            for (var entry : requestedBaseByProduct.entrySet()) {
                var available = stockLedger.currentStock(tenantId, entry.getKey(), request.warehouseId());
                if (available.compareTo(entry.getValue()) < 0) {
                    throw ApiErrors.badRequest("Insufficient stock for " + productById.get(entry.getKey()).name);
                }
            }
        }
        var invoice = new SalesInvoice();
        invoice.tenantId = tenantId;
        invoice.customerId = request.customerId();
        invoice.warehouseId = request.warehouseId();
        invoice.invoiceNumber = request.invoiceNumber();
        invoice.invoiceDate = request.invoiceDate();
        invoices.save(invoice);

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (var line : request.items()) {
            var product = productById.get(line.productId());
            var lineSubtotal = line.quantity().multiply(line.rate());
            var lineDiscount = line.discountAmount() == null ? BigDecimal.ZERO : line.discountAmount();
            if (lineDiscount.compareTo(lineSubtotal) > 0) {
                throw ApiErrors.badRequest("Discount cannot exceed line subtotal for " + product.name);
            }
            var taxable = lineSubtotal.subtract(lineDiscount).max(BigDecimal.ZERO);
            var taxRate = line.taxPercentage() == null ? nvl(product.gstPercentage) : line.taxPercentage();
            var lineTax = taxable.multiply(taxRate).divide(BigDecimal.valueOf(100));
            var item = new SalesInvoiceItem();
            item.tenantId = tenantId;
            item.salesInvoiceId = invoice.id;
            item.productId = product.id;
            item.quantity = line.quantity();
            item.unitId = line.unitId() == null ? product.baseUnitId : line.unitId();
            item.rate = line.rate();
            item.costRate = nvl(product.defaultPurchasePrice);
            item.taxPercentage = taxRate;
            item.discountAmount = lineDiscount;
            item.taxAmount = lineTax;
            item.lineTotal = taxable.add(lineTax);
            items.save(item);
            subtotal = subtotal.add(lineSubtotal);
            tax = tax.add(lineTax);
            discount = discount.add(lineDiscount);
            var sourceUnitCost = item.costRate.multiply(stockLedger.toBaseQuantity(tenantId, product, item.unitId, BigDecimal.ONE));
            stockLedger.createMovement(tenantId, product.id, request.warehouseId(), DomainEnums.MovementType.SALE, line.quantity(), item.unitId, sourceUnitCost, "SALES_INVOICE", invoice.id, request.invoiceDate().atStartOfDay().toInstant(ZoneOffset.UTC), "Sale " + request.invoiceNumber());
        }
        invoice.subtotal = subtotal;
        invoice.taxAmount = tax;
        invoice.discountAmount = discount;
        invoice.totalAmount = subtotal.subtract(discount).add(tax);
        invoices.save(invoice);
        auditService.logCurrent("SALES_INVOICE_CREATED", "SalesInvoice", invoice.id, Map.of("invoiceNumber", invoice.invoiceNumber, "total", invoice.totalAmount));
        return response(invoice);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private ApiDtos.InvoiceResponse response(SalesInvoice invoice) {
        return new ApiDtos.InvoiceResponse(invoice.id, invoice.invoiceNumber, invoice.invoiceDate, invoice.subtotal, invoice.taxAmount, invoice.totalAmount);
    }
}
