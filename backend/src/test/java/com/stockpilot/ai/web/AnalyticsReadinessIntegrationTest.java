package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.*;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalyticsReadinessIntegrationTest {
    @Autowired Repositories.TenantRepository tenants;
    @Autowired Repositories.UserRepository users;
    @Autowired Repositories.MembershipRepository memberships;
    @Autowired Repositories.ProductRepository products;
    @Autowired Repositories.UnitRepository units;
    @Autowired Repositories.UnitConversionRepository conversions;
    @Autowired Repositories.WarehouseRepository warehouses;
    @Autowired Repositories.SalesInvoiceRepository invoices;
    @Autowired Repositories.SalesInvoiceItemRepository invoiceItems;
    @Autowired Repositories.StockMovementRepository movements;
    @Autowired Repositories.PurchaseOrderRepository purchaseOrders;
    @Autowired Repositories.PurchaseOrderItemRepository purchaseItems;
    @Autowired Repositories.SalesOrderRepository salesOrders;
    @Autowired Repositories.SalesOrderItemRepository orderItems;
    @Autowired SalesHistoryService history;
    @Autowired ForecastService forecasts;
    @Autowired DashboardService dashboard;
    @Autowired DeadStockActionService deadStock;
    @Autowired ReorderEngineService reorder;
    @Autowired ProfitInsightService profit;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void invoiceOnlyHistoryFeedsEveryDemandViewAndMovementBackedSalesAreCountedOnce() {
        var f = fixture();
        var invoice = sale(f, new BigDecimal("5"), f.unit.id, today.minusDays(1), new BigDecimal("100"));
        opening(f, new BigDecimal("20"));
        var first = history.history(f.tenant.id);
        assertThat(first.quantity(f.product.id, f.warehouse.id, today.minusDays(89), today)).isEqualByComparingTo("5");
        context(f);
        assertThat(forecasts.run().getFirst().next30DaysDemand()).isPositive();
        assertThat(dashboard.deadStock()).isEmpty();
        assertThat(deadStock.list()).isEmpty();
        saleMovement(f, new BigDecimal("5"), invoice.id, today.minusDays(1));
        saleMovement(f, new BigDecimal("2"), null, today);
        assertThat(history.history(f.tenant.id).quantity(f.product.id, null, today.minusDays(89), today)).isEqualByComparingTo("7");
        var other = fixture();
        sale(other, new BigDecimal("999"), other.unit.id, today, new BigDecimal("999"));
        context(f);
        assertThat(history.history(f.tenant.id).sales()).allMatch(row -> row.productId().equals(f.product.id));
        assertThat(history.history(f.tenant.id).quantity(f.product.id, null, today.minusDays(89), today)).isEqualByComparingTo("7");
    }

    @Test void existingStockoutIsUrgentAndUnavailableHistoryIsExplicit() {
        var f = fixture();
        sale(f, BigDecimal.ONE, f.unit.id, today, new BigDecimal("20"));
        context(f);
        var forecast = forecasts.run().getFirst();
        assertThat(forecast.stockoutDate()).isEqualTo(today);
        assertThat(forecast.salesHistoryAvailable()).isTrue();
        assertThat(reorder.suggestions().getFirst().expectedStockoutDate()).isEqualTo(today);
        var empty = fixture();
        context(empty);
        assertThat(forecasts.run().getFirst().salesHistoryAvailable()).isFalse();
        assertThat(reorder.suggestions().getFirst().reason()).contains("demand is unknown");
    }

    @Test void pendingPurchasesStayInTheirWarehouseAndTodaysDemandIsIncluded() {
        var f = fixture();
        var otherWarehouse = new Warehouse(); otherWarehouse.tenantId = f.tenant.id; otherWarehouse.name = "Branch B";
        warehouses.saveAndFlush(otherWarehouse);
        sale(f, new BigDecimal("5"), f.unit.id, today, new BigDecimal("100"));
        var po = new PurchaseOrder(); po.tenantId = f.tenant.id; purchaseOrders.saveAndFlush(po);
        var pi = new PurchaseOrderItem(); pi.tenantId = f.tenant.id; pi.purchaseOrderId = po.id;
        pi.productId = f.product.id; pi.warehouseId = f.warehouse.id; pi.quantity = new BigDecimal("100"); pi.unitId = f.unit.id;
        purchaseItems.saveAndFlush(pi);
        var so = new SalesOrder(); so.tenantId = f.tenant.id; salesOrders.saveAndFlush(so);
        var si = new SalesOrderItem(); si.tenantId = f.tenant.id; si.salesOrderId = so.id; si.productId = f.product.id;
        si.quantity = new BigDecimal("7"); si.unitId = f.unit.id; orderItems.saveAndFlush(si);
        context(f);
        var rows = reorder.suggestions();
        var branchA = rows.stream().filter(row -> row.warehouseId().equals(f.warehouse.id)).findFirst().orElseThrow();
        var branchB = rows.stream().filter(row -> row.warehouseId().equals(otherWarehouse.id)).findFirst().orElseThrow();
        assertThat(branchA.pendingPurchaseQuantity()).isEqualByComparingTo("100");
        assertThat(branchB.pendingPurchaseQuantity()).isZero();
        assertThat(branchA.last7DaysDemand()).isEqualByComparingTo("5");
        assertThat(branchA.last30DaysDemand()).isEqualByComparingTo("5");
        assertThat(branchB.recommendedQuantity()).isPositive();
        assertThat(rows.stream().map(row -> row.pendingSalesQuantity()).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("7");
        assertThat(branchA.reason()).contains("Unallocated sales demand");
    }

    @Test void bestMarginAndDashboardUseTheSameBaseUnitCost() {
        var f = fixture();
        var box = new UnitOfMeasure(); box.tenantId = f.tenant.id; box.code = "BOX"; box.name = "Box"; units.saveAndFlush(box);
        var conversion = new UnitConversion(); conversion.tenantId = f.tenant.id; conversion.productId = f.product.id;
        conversion.fromUnitId = box.id; conversion.toUnitId = f.unit.id; conversion.multiplier = new BigDecimal("12"); conversions.saveAndFlush(conversion);
        sale(f, BigDecimal.ONE, box.id, today, new BigDecimal("180"));
        context(f);
        assertThat(dashboard.grossProfit(today, today)).isEqualByComparingTo("60");
        var rows = (List<Map<String, Object>>) profit.bestMarginProducts().get("topProducts");
        assertThat((BigDecimal) rows.getFirst().get("grossProfit")).isEqualByComparingTo("60");
        assertThat(history.history(f.tenant.id).quantity(f.product.id, null, today, today)).isEqualByComparingTo("12");
    }

    @Test void restrictedRolesCannotReadCostsProfitsOrAssistantSummaryEvidence() throws Exception {
        var f = fixture(); opening(f, new BigDecimal("10")); sale(f, BigDecimal.ONE, f.unit.id, today, new BigDecimal("20"));
        for (var role : List.of(DomainEnums.Role.WAREHOUSE_STAFF, DomainEnums.Role.SALES_STAFF, DomainEnums.Role.VIEWER, DomainEnums.Role.STAFF)) {
            var token = token(f, role);
            var product = getJson(token, "/api/products/" + f.product.id);
            assertThat(product.path("defaultPurchasePrice").isNull()).as(role.name()).isTrue();
            var summary = getJson(token, "/api/dashboard/summary");
            assertThat(summary.path("grossProfit").isNull()).as(role.name()).isTrue();
            assertThat(summary.path("totalStockValue").isNull()).as(role.name()).isTrue();
            assertThat(getJson(token, "/api/stock/current").get(0).path("stockValue").isNull()).isTrue();
            mvc.perform(get("/api/dashboard/profit-loss").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
        var staff = token(f, DomainEnums.Role.STAFF);
        var response = json.readTree(mvc.perform(post("/api/ai/assistant/chat").header("Authorization", "Bearer " + staff)
            .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"show summary\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(response.path("evidence").path("summary").path("data").path("grossProfit").isNull()).isTrue();
        assertThat(response.path("response").asText()).doesNotContain("gross profit");
        assertThat(getJson(token(f, DomainEnums.Role.OWNER), "/api/dashboard/summary").path("grossProfit").decimalValue()).isEqualByComparingTo("10");
        assertThat(getJson(token(f, DomainEnums.Role.ACCOUNTANT), "/api/dashboard/summary").path("grossProfit").decimalValue()).isEqualByComparingTo("10");
        var other = fixture();
        mvc.perform(get("/api/products/" + f.product.id).header("Authorization", "Bearer " + token(other, DomainEnums.Role.OWNER))).andExpect(status().isNotFound());
    }

    @Test void deadStockProductMetricsAndAuditExportsRedactCostValues() throws Exception {
        var f = fixture(); opening(f, BigDecimal.TEN);
        sale(f, BigDecimal.ONE, f.unit.id, today.minusDays(100), new BigDecimal("20"));
        var warehouseToken = token(f, DomainEnums.Role.WAREHOUSE_STAFF);
        var dead = getJson(warehouseToken, "/api/dead-stock");
        assertThat(dead.get(0).path("stockValue").isNull()).isTrue();
        assertThat(dead.get(0).path("blockedCapital").isNull()).isTrue();
        assertThat(getJson(warehouseToken, "/api/dashboard/dead-stock").get(0).path("stockValue").isNull()).isTrue();
        assertThat(getJson(warehouseToken, "/api/dashboard/top-products").get(0).path("stockValue").isNull()).isTrue();
        var auditor = token(f, DomainEnums.Role.AUDITOR);
        var csv = mvc.perform(get("/api/reports/export/products").header("Authorization", "Bearer " + auditor))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var line = csv.lines().filter(row -> row.startsWith(f.product.id.toString())).findFirst().orElseThrow();
        assertThat(line.split(",", -1)[6]).isEmpty();
        mvc.perform(get("/api/reports/export/purchases").header("Authorization", "Bearer " + auditor)).andExpect(status().isForbidden());
    }

    @Test void concurrentFirstForecastRunsPublishOnlyOneGeneration() throws Exception {
        var f = fixture(); sale(f, BigDecimal.ONE, f.unit.id, today, new BigDecimal("20"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> {
                context(f);
                try { start.await(); forecasts.run(); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                finally { TenantContext.clear(); }
            }));
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        context(f);
        assertThat(forecasts.results()).hasSize(1);
        assertThat(forecasts.suggestions()).hasSize(1);
    }

    private Fixture fixture() {
        var tenant = new Tenant(); tenant.name = "Analytics " + UUID.randomUUID(); tenants.saveAndFlush(tenant);
        var unit = new UnitOfMeasure(); unit.tenantId = tenant.id; unit.code = "PCS"; unit.name = "Pieces"; units.saveAndFlush(unit);
        var product = new Product(); product.tenantId = tenant.id; product.name = "Analytics product"; product.normalizedName = "ANALYTICS PRODUCT";
        product.baseUnitId = unit.id; product.defaultPurchasePrice = BigDecimal.TEN; product.defaultSalesPrice = new BigDecimal("20"); product.safetyStock = BigDecimal.TEN;
        products.saveAndFlush(product);
        var warehouse = new Warehouse(); warehouse.tenantId = tenant.id; warehouse.name = "Branch A"; warehouses.saveAndFlush(warehouse);
        return new Fixture(tenant, product, warehouse, unit, UUID.randomUUID());
    }
    private void context(Fixture f) { TenantContext.set(f.tenant.id, f.actor, DomainEnums.Role.OWNER); }
    private SalesInvoice sale(Fixture f, BigDecimal quantity, UUID unit, LocalDate date, BigDecimal revenue) {
        var invoice = new SalesInvoice(); invoice.tenantId = f.tenant.id; invoice.warehouseId = f.warehouse.id;
        invoice.invoiceNumber = UUID.randomUUID().toString(); invoice.invoiceDate = date; invoice.subtotal = revenue; invoice.totalAmount = revenue; invoices.saveAndFlush(invoice);
        var item = new SalesInvoiceItem(); item.tenantId = f.tenant.id; item.salesInvoiceId = invoice.id; item.productId = f.product.id;
        item.quantity = quantity; item.unitId = unit; item.rate = revenue.divide(quantity, 6, java.math.RoundingMode.HALF_UP);
        item.costRate = BigDecimal.TEN; item.lineTotal = revenue; invoiceItems.saveAndFlush(item); return invoice;
    }
    private void opening(Fixture f, BigDecimal quantity) {
        var row = new StockMovement(); row.tenantId = f.tenant.id; row.productId = f.product.id; row.warehouseId = f.warehouse.id;
        row.movementType = DomainEnums.MovementType.OPENING_BALANCE; row.unitId = f.unit.id; row.quantity = quantity; row.baseQuantity = quantity; movements.saveAndFlush(row);
    }
    private void saleMovement(Fixture f, BigDecimal quantity, UUID invoice, LocalDate date) {
        var row = new StockMovement(); row.tenantId = f.tenant.id; row.productId = f.product.id; row.warehouseId = f.warehouse.id;
        row.movementType = DomainEnums.MovementType.SALE; row.unitId = f.unit.id; row.quantity = quantity; row.baseQuantity = quantity.negate();
        row.referenceType = invoice == null ? "LEGACY_IMPORT" : "SALES_INVOICE"; row.referenceId = invoice;
        row.movementDate = date.atStartOfDay().toInstant(ZoneOffset.UTC); movements.saveAndFlush(row);
    }
    private String token(Fixture f, DomainEnums.Role role) {
        var user = new UserAccount(); user.email = UUID.randomUUID() + "@example.com"; user.fullName = "Analytics user"; user.passwordHash = "unused"; users.saveAndFlush(user);
        var member = new UserTenantMembership(); member.tenantId = f.tenant.id; member.userId = user.id; member.role = role; memberships.saveAndFlush(member);
        return jwt.issue(user.id, f.tenant.id, user.email, role);
    }
    private com.fasterxml.jackson.databind.JsonNode getJson(String token, String path) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private record Fixture(Tenant tenant, Product product, Warehouse warehouse, UnitOfMeasure unit, UUID actor) { }
}
