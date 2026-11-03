package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.Customer;
import com.stockpilot.ai.domain.SalesOrder;
import com.stockpilot.ai.domain.SalesOrderItem;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PortalService {
    private final Repositories.TenantRepository tenants;
    private final Repositories.MembershipRepository memberships;
    private final Repositories.CustomerRepository customers;
    private final Repositories.ProductRepository products;
    private final Repositories.CustomerPriceListRepository priceLists;
    private final Repositories.SalesOrderRepository salesOrders;
    private final Repositories.SalesOrderItemRepository salesOrderItems;
    private final Repositories.SalesInvoiceRepository salesInvoices;
    private final OutstandingService outstanding;
    private final StockLedgerService stockLedger;
    private final AuditService audit;

    public PortalService(
        Repositories.TenantRepository tenants,
        Repositories.MembershipRepository memberships,
        Repositories.CustomerRepository customers,
        Repositories.ProductRepository products,
        Repositories.CustomerPriceListRepository priceLists,
        Repositories.SalesOrderRepository salesOrders,
        Repositories.SalesOrderItemRepository salesOrderItems,
        Repositories.SalesInvoiceRepository salesInvoices,
        OutstandingService outstanding,
        StockLedgerService stockLedger,
        AuditService audit
    ) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.customers = customers;
        this.products = products;
        this.priceLists = priceLists;
        this.salesOrders = salesOrders;
        this.salesOrderItems = salesOrderItems;
        this.salesInvoices = salesInvoices;
        this.outstanding = outstanding;
        this.stockLedger = stockLedger;
        this.audit = audit;
    }

    public List<ApiDtos.PortalProduct> products() {
        var tenantId = TenantContext.tenantId();
        var customer = currentCustomer();
        return catalog(tenantId, customer).items();
    }

    public List<ApiDtos.PortalProduct> priceList() {
        return products();
    }

    @Transactional
    public ApiDtos.PortalOrderResponse placeOrder(ApiDtos.PortalOrderRequest request) {
        var tenantId = TenantContext.tenantId();
        var customer = currentCustomer();
        var visibleProducts = catalog(tenantId, customer).items().stream()
            .collect(Collectors.toMap(ApiDtos.PortalProduct::productId, Function.identity()));
        var order = new SalesOrder();
        order.tenantId = tenantId;
        order.customerId = customer.id;
        order.orderNumber = "PORTAL-SO-" + LocalDate.now(ZoneOffset.UTC) + "-" + System.currentTimeMillis();
        order.status = "OPEN";
        salesOrders.save(order);
        BigDecimal total = BigDecimal.ZERO;
        for (var line : request.items()) {
            var portalProduct = Optional.ofNullable(visibleProducts.get(line.productId()))
                .orElseThrow(() -> ApiErrors.notFound("Product not found"));
            var product = products.findByTenantIdAndId(tenantId, line.productId())
                .filter(candidate -> candidate.active)
                .orElseThrow(() -> ApiErrors.notFound("Product not found"));
            var item = new SalesOrderItem();
            item.tenantId = tenantId;
            item.salesOrderId = order.id;
            item.productId = product.id;
            item.quantity = line.quantity();
            item.unitId = product.baseUnitId;
            item.rate = portalProduct.price();
            item.lineTotal = item.quantity.multiply(item.rate);
            salesOrderItems.save(item);
            total = total.add(item.lineTotal);
        }
        order.totalAmount = total;
        salesOrders.save(order);
        audit.logCurrent("PORTAL_SALES_ORDER_CREATED", "SalesOrder", order.id, Map.of("customerId", customer.id, "total", total));
        return new ApiDtos.PortalOrderResponse(order.id, order.orderNumber, order.status, order.totalAmount);
    }

    public List<ApiDtos.PortalOrderResponse> orders() {
        var customer = currentCustomer();
        return salesOrders.findByTenantIdAndCustomerIdOrderByCreatedAtDesc(TenantContext.tenantId(), customer.id).stream()
            .map(order -> new ApiDtos.PortalOrderResponse(order.id, order.orderNumber, order.status, order.totalAmount))
            .toList();
    }

    public Map<String, Object> outstanding() {
        var tenantId = TenantContext.tenantId();
        var customer = currentCustomer();
        return Map.of("customerId", customer.id, "customerName", customer.name,
            "outstanding", outstanding.customerOutstanding(tenantId, customer.id));
    }

    public List<ApiDtos.PortalInvoice> invoices() {
        var tenantId = TenantContext.tenantId();
        var customer = currentCustomer();
        return salesInvoices.findByTenantId(tenantId).stream()
            .filter(invoice -> customer.id.equals(invoice.customerId))
            .map(invoice -> new ApiDtos.PortalInvoice(invoice.id, invoice.invoiceNumber, invoice.invoiceDate, invoice.totalAmount))
            .toList();
    }

    private Customer currentCustomer() {
        var tenantId = TenantContext.tenantId();
        var membership = memberships.findByTenantIdAndUserId(tenantId, TenantContext.userId()).orElseThrow(() -> ApiErrors.forbidden("Portal membership not found"));
        if (membership.customerId == null) {
            throw ApiErrors.forbidden("This login is not linked to a customer portal account");
        }
        return customers.findByTenantIdAndId(tenantId, membership.customerId).orElseThrow(() -> ApiErrors.notFound("Customer not found"));
    }

    private PortalCatalog catalog(UUID tenantId, Customer customer) {
        var assignedPrices = priceLists.findByTenantIdAndCustomerId(tenantId, customer.id);
        if (!assignedPrices.isEmpty()) {
            var pricesByProductId = assignedPrices.stream()
                .collect(Collectors.toMap(price -> price.productId, price -> price.price, (first, ignored) -> first, LinkedHashMap::new));
            var items = pricesByProductId.entrySet().stream()
                .map(entry -> products.findByTenantIdAndId(tenantId, entry.getKey())
                    .filter(product -> product.active)
                    .map(product -> portalProduct(tenantId, product.id, product.name, entry.getValue())))
                .flatMap(Optional::stream)
                .toList();
            return new PortalCatalog(items);
        }

        var tenant = tenants.findById(tenantId).orElseThrow(() -> ApiErrors.notFound("Tenant not found"));
        if (!tenant.portalShowAllActiveProducts) {
            return new PortalCatalog(List.of());
        }
        var items = products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).stream()
            .map(product -> portalProduct(tenantId, product.id, product.name, product.defaultSalesPrice))
            .toList();
        return new PortalCatalog(items);
    }

    private ApiDtos.PortalProduct portalProduct(UUID tenantId, UUID productId, String productName, BigDecimal price) {
        return new ApiDtos.PortalProduct(
            productId,
            productName,
            price == null ? BigDecimal.ZERO : price,
            stockLedger.currentStock(tenantId, productId, null)
        );
    }

    private record PortalCatalog(List<ApiDtos.PortalProduct> items) {
    }
}
