package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.PurchaseInvoice;
import com.stockpilot.ai.domain.PurchaseInvoiceItem;
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
public class PurchaseService {
    private final Repositories.PurchaseInvoiceRepository invoices;
    private final Repositories.PurchaseInvoiceItemRepository items;
    private final Repositories.ProductRepository products;
    private final Repositories.SupplierRepository suppliers;
    private final Repositories.WarehouseRepository warehouses;
    private final StockLedgerService stockLedger;
    private final AuditService auditService;

    public PurchaseService(
        Repositories.PurchaseInvoiceRepository invoices,
        Repositories.PurchaseInvoiceItemRepository items,
        Repositories.ProductRepository products,
        Repositories.SupplierRepository suppliers,
        Repositories.WarehouseRepository warehouses,
        StockLedgerService stockLedger,
        AuditService auditService
    ) {
        this.invoices = invoices;
        this.items = items;
        this.products = products;
        this.suppliers = suppliers;
        this.warehouses = warehouses;
        this.stockLedger = stockLedger;
        this.auditService = auditService;
    }

    public Page<ApiDtos.InvoiceResponse> list(Pageable pageable) {
        return invoices.findByTenantId(TenantContext.tenantId(), pageable).map(this::response);
    }

    public ApiDtos.InvoiceResponse get(UUID id) {
        return response(invoices.findByTenantIdAndId(TenantContext.tenantId(), id).orElseThrow(() -> ApiErrors.notFound("Purchase invoice not found")));
    }

    @Transactional
    public ApiDtos.InvoiceResponse create(ApiDtos.PurchaseInvoiceRequest request) {
        var tenantId = TenantContext.tenantId();
        if (invoices.existsByTenantIdAndInvoiceNumberIgnoreCase(tenantId, request.invoiceNumber())) {
            throw ApiErrors.conflict("Purchase invoice number already exists");
        }
        if (request.supplierId() != null) {
            suppliers.findByTenantIdAndId(tenantId, request.supplierId()).orElseThrow(() -> ApiErrors.notFound("Supplier not found"));
        }
        warehouses.findByTenantIdAndId(tenantId, request.warehouseId()).orElseThrow(() -> ApiErrors.notFound("Warehouse not found"));
        var productById = new java.util.LinkedHashMap<UUID, com.stockpilot.ai.domain.Product>();
        for (var line : request.items()) {
            var product = products.findByTenantIdAndId(tenantId, line.productId()).orElseThrow(() -> ApiErrors.notFound("Product not found"));
            productById.put(product.id, product);
        }
        stockLedger.lockStockKeys(productById.keySet().stream()
            .map(productId -> stockLedger.stockKey(tenantId, productId, request.warehouseId()))
            .toList());
        var invoice = new PurchaseInvoice();
        invoice.tenantId = tenantId;
        invoice.supplierId = request.supplierId();
        invoice.warehouseId = request.warehouseId();
        invoice.invoiceNumber = request.invoiceNumber();
        invoice.invoiceDate = request.invoiceDate();
        invoices.save(invoice);

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (var line : request.items()) {
            var product = productById.get(line.productId());
            var lineSubtotal = line.quantity().multiply(line.rate());
            var taxRate = line.taxPercentage() == null ? nvl(product.gstPercentage) : line.taxPercentage();
            var lineTax = lineSubtotal.multiply(taxRate).divide(BigDecimal.valueOf(100));
            var item = new PurchaseInvoiceItem();
            item.tenantId = tenantId;
            item.purchaseInvoiceId = invoice.id;
            item.productId = product.id;
            item.quantity = line.quantity();
            item.unitId = line.unitId() == null ? product.baseUnitId : line.unitId();
            item.rate = line.rate();
            item.taxPercentage = taxRate;
            item.taxAmount = lineTax;
            item.lineTotal = lineSubtotal.add(lineTax);
            items.save(item);
            subtotal = subtotal.add(lineSubtotal);
            tax = tax.add(lineTax);
            product.defaultPurchasePrice = stockLedger.toBaseRate(tenantId, product, item.unitId, line.rate());
            products.save(product);
            stockLedger.createMovement(tenantId, product.id, request.warehouseId(), DomainEnums.MovementType.PURCHASE, line.quantity(), item.unitId, line.rate(), "PURCHASE_INVOICE", invoice.id, request.invoiceDate().atStartOfDay().toInstant(ZoneOffset.UTC), "Purchase " + request.invoiceNumber());
        }
        invoice.subtotal = subtotal;
        invoice.taxAmount = tax;
        invoice.totalAmount = subtotal.add(tax);
        invoices.save(invoice);
        auditService.logCurrent("PURCHASE_INVOICE_CREATED", "PurchaseInvoice", invoice.id, Map.of("invoiceNumber", invoice.invoiceNumber, "total", invoice.totalAmount));
        return response(invoice);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private ApiDtos.InvoiceResponse response(PurchaseInvoice invoice) {
        return new ApiDtos.InvoiceResponse(invoice.id, invoice.invoiceNumber, invoice.invoiceDate, invoice.subtotal, invoice.taxAmount, invoice.totalAmount);
    }
}
