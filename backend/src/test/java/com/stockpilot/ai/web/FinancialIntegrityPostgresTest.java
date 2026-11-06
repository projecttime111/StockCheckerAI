package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.domain.UnitConversion;
import com.stockpilot.ai.domain.UnitOfMeasure;
import com.stockpilot.ai.repo.Repositories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "STOCKPILOT_AUDIT_PG_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55434/stockpilot_audit")
@ActiveProfiles({"test", "audit-postgres"})
@Import(LaunchAuditPostgresIntegrationTest.DatabaseGuard.class)
class FinancialIntegrityPostgresTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) { LaunchAuditPostgresIntegrationTest.isolatedDatabase(properties); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Repositories.ProductRepository products;
    @Autowired Repositories.UnitRepository units;
    @Autowired Repositories.UnitConversionRepository conversions;
    @Autowired Repositories.SalesInvoiceItemRepository saleItems;
    @Autowired Repositories.PurchaseInvoiceItemRepository purchaseItems;

    @Test
    void recurringUnitCostSurvivesReloadAndSubsequentSale() throws Exception {
        var fixture = fixture();
        var product = products.findByTenantIdAndId(fixture.tenant, fixture.product).orElseThrow();
        var pack = new UnitOfMeasure(); pack.tenantId = fixture.tenant; pack.name = "Three pack"; pack.code = "PACK";
        units.saveAndFlush(pack);
        var conversion = new UnitConversion(); conversion.tenantId = fixture.tenant; conversion.productId = product.id;
        conversion.fromUnitId = pack.id; conversion.toUnitId = product.baseUnitId; conversion.multiplier = new BigDecimal("3");
        conversions.saveAndFlush(conversion);
        postJson(fixture.token, "/api/purchases", invoice(fixture, "PACK-P", "{\"productId\":\"%s\",\"unitId\":\"%s\",\"quantity\":1,\"rate\":100,\"taxPercentage\":0}".formatted(product.id, pack.id)));
        assertThat(products.findByTenantIdAndId(fixture.tenant, product.id).orElseThrow().defaultPurchasePrice).isEqualByComparingTo("33.333333333333");
        var sale = postJson(fixture.token, "/api/sales", invoice(fixture, "PACK-S", "{\"productId\":\"%s\",\"quantity\":1,\"rate\":50,\"taxPercentage\":0}".formatted(product.id)));
        assertThat(saleItems.findByTenantIdAndSalesInvoiceId(fixture.tenant, UUID.fromString(sale.path("id").asText())).getFirst().costRate).isEqualByComparingTo("33.333333333333");
        var stock = getJson(fixture.token, "/api/stock/current?productId=" + product.id).get(0);
        assertThat(stock.path("currentStock").decimalValue()).isEqualByComparingTo("2");
        assertThat(stock.path("stockValue").decimalValue()).isEqualByComparingTo("66.67");
    }

    @Test
    void fractionalLineRoundingReconcilesToPersistedHeaderAndRejectsOverflow() throws Exception {
        var f = fixture();
        var purchaseLine = "{\"productId\":\"%s\",\"quantity\":0.015,\"rate\":1,\"taxPercentage\":18}".formatted(f.product);
        var purchase = postJson(f.token, "/api/purchases", invoice(f, "ROUND-P", purchaseLine + "," + purchaseLine + "," + purchaseLine));
        var purchaseRows = purchaseItems.findByTenantIdAndPurchaseInvoiceId(f.tenant, UUID.fromString(purchase.path("id").asText()));
        assertThat(purchaseRows).hasSize(3);
        assertThat(purchaseRows.stream().map(row -> row.lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(purchase.path("totalAmount").decimalValue()).isEqualByComparingTo("0.06");
        var saleLine = "{\"productId\":\"%s\",\"quantity\":0.015,\"rate\":1,\"discountAmount\":0.01,\"taxPercentage\":18}".formatted(f.product);
        var sale = postJson(f.token, "/api/sales", invoice(f, "ROUND-S", saleLine + "," + saleLine));
        var saleRows = saleItems.findByTenantIdAndSalesInvoiceId(f.tenant, UUID.fromString(sale.path("id").asText()));
        assertThat(saleRows.stream().map(row -> row.lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(sale.path("totalAmount").decimalValue()).isEqualByComparingTo("0.02");
        var overflow = invoice(f, "TOO-LARGE", "{\"productId\":\"%s\",\"quantity\":99999999999,\"rate\":999999999999.99}".formatted(f.product));
        mvc.perform(post("/api/purchases").header("Authorization", "Bearer " + f.token).contentType(MediaType.APPLICATION_JSON).content(overflow)).andExpect(status().isBadRequest());
        var invalidScale = invoice(f, "BAD-SCALE", "{\"productId\":\"%s\",\"quantity\":1,\"rate\":1.001}".formatted(f.product));
        mvc.perform(post("/api/purchases").header("Authorization", "Bearer " + f.token).contentType(MediaType.APPLICATION_JSON).content(invalidScale)).andExpect(status().isBadRequest());
    }

    @Test
    void retriedAdjustmentAndTransferWriteExactlyOneLedgerEffect() throws Exception {
        var f = fixture();
        var adjustment = "{\"productId\":\"%s\",\"warehouseId\":\"%s\",\"quantityDelta\":10,\"rate\":5,\"notes\":\"Test adjustment\"}".formatted(f.product, f.warehouse);
        var first = keyed(f.token, "/api/stock/adjustment", adjustment, "adjust-1");
        var retried = keyed(f.token, "/api/stock/adjustment", adjustment, "adjust-1");
        assertThat(retried.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(retried.path("quantity").decimalValue()).isEqualByComparingTo(first.path("quantity").decimalValue());
        var target = postJson(f.token, "/api/warehouses", "{\"name\":\"Destination\",\"code\":\"DEST\"}").path("id").asText();
        var transfer = "{\"productId\":\"%s\",\"sourceWarehouseId\":\"%s\",\"destinationWarehouseId\":\"%s\",\"quantity\":3,\"notes\":\"Test transfer\"}".formatted(f.product, f.warehouse, target);
        assertThat(keyed(f.token, "/api/stock/transfer", transfer, "transfer-1")).hasSize(2);
        assertThat(keyed(f.token, "/api/stock/transfer", transfer, "transfer-1")).hasSize(2);
        assertThat(getJson(f.token, "/api/stock/current?productId=" + f.product + "&warehouseId=" + f.warehouse).get(0).path("currentStock").decimalValue()).isEqualByComparingTo("7");
        assertThat(getJson(f.token, "/api/stock/current?productId=" + f.product + "&warehouseId=" + target).get(0).path("currentStock").decimalValue()).isEqualByComparingTo("3");
    }

    private Fixture fixture() throws Exception {
        var auth = postJson(null, "/api/auth/register", "{\"businessName\":\"Money test\",\"businessMode\":\"HYBRID\",\"email\":\"money-%s@example.com\",\"password\":\"Password123!\",\"fullName\":\"Test Owner\"}".formatted(UUID.randomUUID()));
        var token = auth.path("accessToken").asText();
        var product = postJson(token, "/api/products", "{\"name\":\"Fractional product\",\"sku\":\"FRACTION\",\"unitCode\":\"PCS\"}");
        var warehouse = postJson(token, "/api/warehouses", "{\"name\":\"Test Warehouse\",\"code\":\"TEST\"}");
        return new Fixture(token, UUID.fromString(auth.path("tenantId").asText()), UUID.fromString(product.path("id").asText()), UUID.fromString(warehouse.path("id").asText()));
    }
    private String invoice(Fixture f, String number, String lines) {
        return "{\"warehouseId\":\"%s\",\"invoiceNumber\":\"%s\",\"invoiceDate\":\"2026-11-01\",\"items\":[%s]}".formatted(f.warehouse, number, lines);
    }
    private JsonNode keyed(String token, String path, String body, String key) throws Exception {
        return json.readTree(mvc.perform(post(path).header("Authorization", "Bearer " + token).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode postJson(String token, String path, String body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode getJson(String token, String path) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private record Fixture(String token, UUID tenant, UUID product, UUID warehouse) {}
}
