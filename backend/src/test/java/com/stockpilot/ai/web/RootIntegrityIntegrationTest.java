package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.service.IdempotencyService;
import com.stockpilot.ai.service.ForecastService;
import com.stockpilot.ai.service.SalesHistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RootIntegrityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Repositories.ProductRepository products;
    @Autowired Repositories.SalesOrderRepository orders;
    @Autowired Repositories.SalesOrderItemRepository orderItems;
    @Autowired Repositories.PaymentReminderRepository reminders;
    @Autowired Repositories.CustomerPaymentRepository payments;
    @Autowired IdempotencyService idempotency;
    @Autowired ForecastService forecasts;
    @Autowired Repositories.TenantRepository tenants;
    @Autowired Repositories.UnitRepository units;
    @Autowired Repositories.UnitConversionRepository conversions;
    @Autowired Repositories.AuditLogRepository auditLogs;
    @Autowired Repositories.SalesInvoiceItemRepository salesItems;
    @Autowired Repositories.PurchaseInvoiceItemRepository purchaseItems;
    @Autowired SalesHistoryService salesHistory;

    @AfterEach void clearContext() { TenantContext.clear(); }

    @Test void baseUnitChangesAfterStockHistoryAreRejectedWithoutChangingStockOrProduct() throws Exception {
        var f = fixture();
        var product = product(f, "Unit guarded", "10");
        adjust(f, product, "10");
        var edit = productBody("Unit guarded", "10"); edit.put("unitCode", "BOX");
        request(put("/api/products/" + product), f.token, edit, null, 409);
        var current = getJson(f.token, "/api/products/" + product);
        assertThat(current.path("unitCode").asText()).isEqualTo("PCS");
        assertThat(current.path("currentStock").decimalValue()).isEqualByComparingTo("10");
        edit.put("unitCode", "PCS"); edit.put("name", "Permitted rename");
        request(put("/api/products/" + product), f.token, edit, null, 200);
        assertThat(getJson(f.token, "/api/products/" + product).path("name").asText()).isEqualTo("Permitted rename");
    }

    @Test void mergeRejectsOutstandingOrderLinksAndOtherTenantSources() throws Exception {
        var f = fixture(); var source = product(f, "Source", "10"); var target = product(f, "Target", "10");
        adjust(f, source, "10");
        var order = new SalesOrder(); order.tenantId = f.tenant; orders.saveAndFlush(order);
        var item = new SalesOrderItem(); item.tenantId = f.tenant; item.salesOrderId = order.id;
        item.productId = source; item.quantity = BigDecimal.ONE; orderItems.saveAndFlush(item);
        request(post("/api/data-quality/products/merge"), f.token, mergeBody(source, target), null, 409);
        assertThat(products.findByTenantIdAndId(f.tenant, source).orElseThrow().active).isTrue();
        assertThat(orderItems.findByTenantIdAndSalesOrderId(f.tenant, order.id).getFirst().productId).isEqualTo(source);
        assertThat(getJson(f.token, "/api/products/" + source).path("currentStock").decimalValue()).isEqualByComparingTo("10");
        var other = fixture(); var foreign = product(other, "Other tenant", "10");
        request(post("/api/data-quality/products/merge"), f.token, mergeBody(foreign, target), null, 404);
        assertThat(products.findByTenantIdAndId(other.tenant, foreign).orElseThrow().active).isTrue();
    }

    @Test void anUnusedProductCanChangeBaseUnitDespiteAnAuditEntry() throws Exception {
        var f = fixture(); var product = product(f, "Unused product", "10");
        var audit = new AuditLog(); audit.tenantId = f.tenant; audit.actorUserId = f.user;
        audit.action = "PRODUCT_REVIEWED"; audit.entityType = "Product"; audit.entityId = product;
        auditLogs.saveAndFlush(audit);
        var edit = productBody("Unused product", "10"); edit.put("unitCode", "BOX");
        request(put("/api/products/" + product), f.token, edit, null, 200);
        assertThat(getJson(f.token, "/api/products/" + product).path("unitCode").asText()).isEqualTo("BOX");
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(f.tenant))
            .anyMatch(entry -> entry.id.equals(audit.id) && entry.entityId.equals(product));
    }

    @Test void compatibleMergePreservesStockAndHistoricalInvoiceUnitsAndCosts() throws Exception {
        var f = fixture(); var source = product(f, "Compatible source", "10"); var target = product(f, "Compatible target", "10");
        var baseUnit = products.findByTenantIdAndId(f.tenant, source).orElseThrow().baseUnitId;
        var box = new UnitOfMeasure(); box.tenantId = f.tenant; box.code = "BOX"; box.name = "Box";
        units.saveAndFlush(box);
        var conversion = new UnitConversion(); conversion.tenantId = f.tenant; conversion.productId = source;
        conversion.fromUnitId = box.id; conversion.toUnitId = baseUnit; conversion.multiplier = new BigDecimal("12");
        conversions.saveAndFlush(conversion);
        var purchase = invoice(f, source, null, LocalDate.now().toString(), "MERGE-PURCHASE", true);
        purchase.put("items", List.of(Map.of("productId", source, "unitId", box.id, "quantity", 2, "rate", 120)));
        var purchaseId = UUID.fromString(request(post("/api/purchases"), f.token, purchase, null, 200).path("id").asText());
        var sale = invoice(f, source, null, LocalDate.now().toString(), "MERGE-SALE", false);
        sale.put("items", List.of(Map.of("productId", source, "unitId", box.id, "quantity", 1, "rate", 180)));
        var saleId = UUID.fromString(request(post("/api/sales"), f.token, sale, null, 200).path("id").asText());
        adjust(f, target, "5");
        assertThat(getJson(f.token, "/api/products/" + source).path("currentStock").decimalValue()).isEqualByComparingTo("12");
        assertThat(salesHistory.history(f.tenant).quantity(source, f.warehouse, LocalDate.now(), LocalDate.now())).isEqualByComparingTo("12");

        request(post("/api/data-quality/products/merge"), f.token, mergeBody(source, target), null, 200);

        assertThat(products.findByTenantIdAndId(f.tenant, source).orElseThrow().active).isFalse();
        assertThat(getJson(f.token, "/api/products/" + target).path("currentStock").decimalValue()).isEqualByComparingTo("17");
        var copied = conversions.findByTenantIdAndProductIdAndFromUnitIdAndToUnitId(f.tenant, target, box.id, baseUnit).orElseThrow();
        assertThat(copied.multiplier).isEqualByComparingTo("12");
        var historicalPurchase = purchaseItems.findByTenantIdAndPurchaseInvoiceId(f.tenant, purchaseId).getFirst();
        assertThat(historicalPurchase.productId).isEqualTo(target);
        assertThat(historicalPurchase.unitId).isEqualTo(box.id);
        assertThat(historicalPurchase.quantity).isEqualByComparingTo("2");
        assertThat(historicalPurchase.rate).isEqualByComparingTo("120");
        var historicalSale = salesItems.findByTenantIdAndSalesInvoiceId(f.tenant, saleId).getFirst();
        assertThat(historicalSale.productId).isEqualTo(target);
        assertThat(historicalSale.unitId).isEqualTo(box.id);
        assertThat(historicalSale.quantity).isEqualByComparingTo("1");
        assertThat(historicalSale.costRate).isEqualByComparingTo("10");
        var history = salesHistory.history(f.tenant);
        assertThat(history.quantity(target, f.warehouse, LocalDate.now(), LocalDate.now())).isEqualByComparingTo("12");
        assertThat(history.quantity(source, f.warehouse, LocalDate.now(), LocalDate.now())).isZero();
    }

    @Test void mergeCannotRevalueOpeningStockThroughAnEmptyTargetWithAnotherCost() throws Exception {
        var f = fixture(); var source = product(f, "Stock source", "10"); var target = product(f, "Empty target", "50");
        adjust(f, source, "10");
        request(post("/api/data-quality/products/merge"), f.token, mergeBody(source, target), null, 409);
        assertThat(getJson(f.token, "/api/products/" + source).path("currentStock").decimalValue()).isEqualByComparingTo("10");
        assertThat(getJson(f.token, "/api/products/" + target).path("currentStock").decimalValue()).isZero();
    }

    @Test void supplierAndAprilFiscalYearScopeDocumentIdentityButExactRepeatsStillConflict() throws Exception {
        var f = fixture(); var product = product(f, "Fiscal item", "10");
        var supplierA = UUID.fromString(request(post("/api/suppliers"), f.token, Map.of("name", "Supplier A"), null, 200).path("id").asText());
        var supplierB = UUID.fromString(request(post("/api/suppliers"), f.token, Map.of("name", "Supplier B"), null, 200).path("id").asText());
        request(post("/api/purchases"), f.token, invoice(f, product, supplierA, "2025-04-01", " INV-001 ", true), null, 200);
        request(post("/api/purchases"), f.token, invoice(f, product, supplierB, "2025-05-01", "inv-001", true), null, 200);
        request(post("/api/purchases"), f.token, invoice(f, product, supplierA, "2026-03-31", "inv-001", true), null, 409);
        request(post("/api/purchases"), f.token, invoice(f, product, supplierA, "2026-04-01", "inv-001", true), null, 200);
        request(post("/api/purchases"), f.token, invoice(f, product, supplierA, "2025-03-31", "inv-001", true), null, 200);
        request(post("/api/sales"), f.token, invoice(f, product, null, "2025-04-01", "SO-001", false), null, 200);
        request(post("/api/sales"), f.token, invoice(f, product, null, "2026-03-31", " so-001 ", false), null, 409);
        request(post("/api/sales"), f.token, invoice(f, product, null, "2026-04-01", "so-001", false), null, 200);
        var other = fixture();
        request(post("/api/purchases"), other.token, invoice(other, product(other, "Independent", "10"), supplierA, "2025-04-01", "inv-001", true), null, 404);
    }

    @Test void partialPaymentsAndRetriesPreserveReminderBalanceAndTenantIsolation() throws Exception {
        var f = fixture(); var customer = customer(f);
        var reminder = request(post("/api/customers/" + customer + "/payment-reminders"), f.token,
            Map.of("reminderDate", LocalDate.now().toString(), "amountDue", 1000), null, 200);
        var reminderId = UUID.fromString(reminder.path("id").asText());
        var body = paymentBody(100, reminderId);
        var first = request(post("/api/customers/" + customer + "/payments"), f.token, body, "payment-one", 200);
        var retry = request(post("/api/customers/" + customer + "/payments"), f.token, body, "payment-one", 200);
        assertThat(retry.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(reminders.findByTenantIdAndId(f.tenant, reminderId).orElseThrow().amountDue).isEqualByComparingTo("900");
        assertThat(reminders.findByTenantIdAndId(f.tenant, reminderId).orElseThrow().status).isEqualTo("OPEN");
        request(post("/api/customers/" + customer + "/payments"), f.token, paymentBody(200, reminderId), "payment-one", 409);
        var other = fixture(); var otherCustomer = customer(other);
        request(post("/api/customers/" + customer + "/payments"), other.token, body, "payment-one", 404);
        request(post("/api/customers/" + otherCustomer + "/payments"), other.token, paymentBody(100, reminderId), "foreign-reminder", 404);
        request(post("/api/customers/" + otherCustomer + "/payments"), other.token, paymentBody(100, null), "payment-one", 200);
        var finalBody = paymentBody(900, reminderId);
        var finalPayment = request(post("/api/customers/" + customer + "/payments"), f.token, finalBody, "payment-two", 200);
        assertThat(request(post("/api/customers/" + customer + "/payments"), f.token, finalBody, "payment-two", 200).path("id").asText()).isEqualTo(finalPayment.path("id").asText());
        var completed = reminders.findByTenantIdAndId(f.tenant, reminderId).orElseThrow();
        assertThat(completed.status).isEqualTo("PAID"); assertThat(completed.amountDue).isZero();
        assertThat(payments.findByTenantIdAndCustomerIdOrderByPaymentDateDesc(f.tenant, customer)).hasSize(2);
        assertThat(payments.findByTenantIdAndCustomerIdOrderByPaymentDateDesc(other.tenant, otherCustomer)).hasSize(1);
    }

    @Test void concurrentSameKeyPaymentRetriesCreateExactlyOnePayment() throws Exception {
        var f = fixture(); var customer = customer(f); var body = paymentBody(100, null);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<JsonNode> task = () -> { start.await(); return request(post("/api/customers/" + customer + "/payments"), f.token, body, "concurrent-payment", 200); };
            var one = pool.submit(task); var two = pool.submit(task); start.countDown();
            assertThat(one.get(30, TimeUnit.SECONDS).path("id").asText()).isEqualTo(two.get(30, TimeUnit.SECONDS).path("id").asText());
        }
        assertThat(payments.findByTenantIdAndCustomerIdOrderByPaymentDateDesc(f.tenant, customer)).hasSize(1);
    }

    @Test void idempotencyFingerprintIgnoresMapInsertionOrder() throws Exception {
        var f = fixture(); TenantContext.set(f.tenant, f.user, DomainEnums.Role.OWNER);
        var first = new LinkedHashMap<String, Object>(); first.put("customerId", "customer"); first.put("amount", 100);
        var second = new LinkedHashMap<String, Object>(); second.put("amount", 100); second.put("customerId", "customer");
        var writes = new AtomicInteger();
        var id = UUID.randomUUID();
        UUID saved = idempotency.execute("CANONICAL_MAP_TEST", "stable-key", first, () -> { writes.incrementAndGet(); return id; }, result -> result);
        UUID replayed = idempotency.execute("CANONICAL_MAP_TEST", "stable-key", second, () -> { writes.incrementAndGet(); return UUID.randomUUID(); }, result -> result);
        assertThat(saved).isEqualTo(id);
        assertThat(replayed).isEqualTo(id);
        assertThat(writes.get()).isEqualTo(1);
    }

    @Test void requestsThatPassedAuthenticationCannotWriteAfterWorkspaceDeletion() throws Exception {
        var f = fixture();
        var tenant = tenants.findById(f.tenant).orElseThrow(); tenant.status = DomainEnums.TenantStatus.DELETED; tenants.saveAndFlush(tenant);
        // Represents an in-flight request whose authentication check completed before deletion.
        TenantContext.set(f.tenant, f.user, DomainEnums.Role.OWNER);
        var writes = new AtomicInteger();
        assertThatThrownBy(() -> idempotency.execute("INACTIVE_TEST", "inactive", Map.of("amount", 10),
            () -> { writes.incrementAndGet(); return UUID.randomUUID(); }, id -> id)).isInstanceOf(com.stockpilot.ai.exception.ApiException.class);
        assertThat(writes.get()).isZero();
        assertThatThrownBy(() -> forecasts.run()).isInstanceOf(com.stockpilot.ai.exception.ApiException.class);
    }

    private Fixture fixture() throws Exception {
        var auth = request(post("/api/auth/register"), null, Map.of("businessName", "Integrity Test", "businessMode", "HYBRID", "email", UUID.randomUUID() + "@example.com", "password", "Password123!", "fullName", "Owner"), null, 200);
        var token = auth.path("accessToken").asText();
        var warehouse = request(post("/api/warehouses"), token, Map.of("name", "Test warehouse"), null, 200);
        return new Fixture(token, UUID.fromString(auth.path("tenantId").asText()), UUID.fromString(auth.path("userId").asText()), UUID.fromString(warehouse.path("id").asText()));
    }
    private UUID product(Fixture f, String name, String cost) throws Exception { return UUID.fromString(request(post("/api/products"), f.token, productBody(name, cost), null, 200).path("id").asText()); }
    private Map<String, Object> productBody(String name, String cost) {
        var body = new LinkedHashMap<String, Object>(); body.put("name", name); body.put("unitCode", "PCS"); body.put("defaultPurchasePrice", new BigDecimal(cost)); body.put("defaultSalesPrice", new BigDecimal("60")); return body;
    }
    private void adjust(Fixture f, UUID product, String quantity) throws Exception { request(post("/api/stock/adjustment"), f.token, Map.of("productId", product, "warehouseId", f.warehouse, "quantityDelta", new BigDecimal(quantity), "notes", "Fixture opening"), UUID.randomUUID().toString(), 200); }
    private UUID customer(Fixture f) throws Exception { return UUID.fromString(request(post("/api/customers"), f.token, Map.of("name", "Payment customer"), null, 200).path("id").asText()); }
    private Map<String, Object> mergeBody(UUID source, UUID target) { return Map.of("sourceProductIds", List.of(source), "targetProductId", target, "confirmation", "MERGE PRODUCTS"); }
    private Map<String, Object> invoice(Fixture f, UUID product, UUID party, String date, String number, boolean purchase) {
        var result = new LinkedHashMap<String, Object>(); result.put("warehouseId", f.warehouse); result.put("invoiceDate", date); result.put("invoiceNumber", number);
        if (party != null) result.put(purchase ? "supplierId" : "customerId", party);
        result.put("items", List.of(Map.of("productId", product, "quantity", 1, "rate", purchase ? 10 : 20))); return result;
    }
    private Map<String, Object> paymentBody(int amount, UUID reminderId) { var body = new LinkedHashMap<String, Object>(); body.put("amount", amount); body.put("paymentDate", LocalDate.now().toString()); body.put("mode", "CASH"); if (reminderId != null) body.put("reminderId", reminderId); return body; }
    private JsonNode getJson(String token, String path) throws Exception { return request(get(path), token, null, null, 200); }
    private JsonNode request(MockHttpServletRequestBuilder request, String token, Object body, String key, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (key != null) request.header("Idempotency-Key", key);
        var content = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return content.isBlank() ? json.nullNode() : json.readTree(content);
    }
    private record Fixture(String token, UUID tenant, UUID user, UUID warehouse) { }
}
