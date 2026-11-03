package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.JwtService;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.service.AuthTokenService;
import com.stockpilot.ai.service.StockLedgerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LaunchAuditRegressionTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired AuthTokenService tokens;
    @Autowired Repositories.UserRepository users;
    @Autowired Repositories.MembershipRepository memberships;
    @Autowired Repositories.SalesInvoiceRepository sales;
    @Autowired Repositories.PurchaseInvoiceRepository purchases;
    @Autowired Repositories.OutstandingSnapshotRepository snapshots;
    @Autowired Repositories.CustomerPaymentRepository receipts;
    @Autowired Repositories.SupplierPaymentRepository payments;
    @Autowired Repositories.FinancialAdjustmentRepository adjustments;
    @Autowired Repositories.CustomerRepository customers;
    @Autowired Repositories.SupplierRepository suppliers;
    @Autowired Repositories.ProductRepository products;
    @Autowired Repositories.UnitRepository units;
    @Autowired Repositories.UnitConversionRepository conversions;
    @Autowired Repositories.StockMovementRepository movements;
    @Autowired Repositories.ImportSessionRepository sessions;
    @Autowired Repositories.ImportSessionFileRepository files;
    @Autowired Repositories.SmartImportCommitRepository commits;
    @Autowired Repositories.SmartImportEffectRepository effects;
    @Autowired StockLedgerService ledger;

    @Test
    void f2CustomerScreenExportPortalAndDashboardUseTheSameSnapshotCutoff() throws Exception {
        var auth = register();
        var token = auth.path("accessToken").asText();
        var tenant = UUID.fromString(auth.path("tenantId").asText());
        var customer = UUID.fromString(postJson(token, "/api/customers", "{\"name\":\"Audit Customer\"}").path("id").asText());
        var warehouse = warehouse(token);
        var cutoff = LocalDate.of(2026, 8, 31);
        for (var date : new LocalDate[]{cutoff.minusDays(1), cutoff, cutoff.plusDays(1)}) {
            var invoice = new SalesInvoice();
            invoice.tenantId = tenant; invoice.customerId = customer; invoice.warehouseId = warehouse;
            invoice.invoiceNumber = "AUDIT-" + date; invoice.invoiceDate = date; invoice.totalAmount = new BigDecimal("100");
            sales.saveAndFlush(invoice);
            var payment = new CustomerPayment();
            payment.tenantId = tenant; payment.customerId = customer; payment.paymentDate = date; payment.amount = new BigDecimal("20");
            receipts.saveAndFlush(payment);
            var credit = new FinancialAdjustment();
            credit.tenantId = tenant; credit.customerId = customer; credit.voucherDate = date; credit.voucherNumber = "CN-" + date;
            credit.adjustmentType = DomainEnums.FinancialAdjustmentType.CREDIT_NOTE; credit.totalAmount = new BigDecimal("10");
            adjustments.saveAndFlush(credit);
        }
        var snapshot = new OutstandingSnapshot();
        snapshot.tenantId = tenant; snapshot.customerId = customer; snapshot.partyType = DomainEnums.OutstandingPartyType.CUSTOMER;
        snapshot.snapshotDate = cutoff; snapshot.outstandingAmount = new BigDecimal("500");
        saveSnapshot(snapshot);
        assertThat(getJson(token, "/api/customers/" + customer + "/outstanding").path("outstanding").decimalValue()).isEqualByComparingTo("570");
        var csv = csv(token, "customers");
        assertThat(new BigDecimal(csv.lines().filter(line -> line.startsWith(customer.toString())).findFirst().orElseThrow().split(",")[6])).isEqualByComparingTo("570");
        var portal = roleToken(tenant, DomainEnums.Role.CUSTOMER_USER, customer);
        assertThat(getJson(portal, "/api/portal/outstanding").path("outstanding").decimalValue()).isEqualByComparingTo("570");
        assertThat(getJson(token, "/api/dashboard/summary").path("outstandingReceivables").decimalValue()).isEqualByComparingTo("570");
        var other = register().path("accessToken").asText();
        mvc.perform(get("/api/customers/" + customer + "/outstanding").header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
        assertThat(csv(other, "customers")).doesNotContain(customer.toString());
    }

    @Test
    void f2SupplierExportPreservesOpeningColumnAndAddsCurrentPayable() throws Exception {
        var auth = register();
        var token = auth.path("accessToken").asText();
        var tenant = UUID.fromString(auth.path("tenantId").asText());
        var supplier = UUID.fromString(postJson(token, "/api/suppliers", "{\"name\":\"Audit Supplier\",\"openingBalance\":50}").path("id").asText());
        var supplierRecord = suppliers.findByTenantIdAndId(tenant, supplier).orElseThrow();
        supplierRecord.openingBalance = new BigDecimal("50");
        suppliers.saveAndFlush(supplierRecord);
        var snapshot = new OutstandingSnapshot();
        snapshot.tenantId = tenant; snapshot.supplierId = supplier; snapshot.partyType = DomainEnums.OutstandingPartyType.SUPPLIER;
        snapshot.snapshotDate = LocalDate.of(2026, 8, 31); snapshot.outstandingAmount = new BigDecimal("400");
        saveSnapshot(snapshot);
        var warehouse = warehouse(token);
        for (var date : new LocalDate[]{snapshot.snapshotDate, snapshot.snapshotDate.plusDays(1)}) {
            var invoice = new PurchaseInvoice();
            invoice.tenantId = tenant; invoice.supplierId = supplier; invoice.warehouseId = warehouse;
            invoice.invoiceNumber = "P-" + date; invoice.invoiceDate = date; invoice.totalAmount = new BigDecimal("100");
            purchases.saveAndFlush(invoice);
            var payment = new SupplierPayment();
            payment.tenantId = tenant; payment.supplierId = supplier; payment.paymentDate = date; payment.amount = new BigDecimal("20");
            payments.saveAndFlush(payment);
            var debit = new FinancialAdjustment();
            debit.tenantId = tenant; debit.supplierId = supplier; debit.voucherDate = date; debit.voucherNumber = "DN-" + date;
            debit.adjustmentType = DomainEnums.FinancialAdjustmentType.DEBIT_NOTE; debit.totalAmount = new BigDecimal("10");
            adjustments.saveAndFlush(debit);
        }
        var csv = csv(token, "suppliers");
        assertThat(csv.lines().findFirst().orElseThrow()).endsWith("opening_balance,current_payable");
        var row = csv.lines().filter(line -> line.startsWith(supplier.toString())).findFirst().orElseThrow().split(",");
        assertThat(new BigDecimal(row[6])).isEqualByComparingTo("50");
        assertThat(new BigDecimal(row[7])).isEqualByComparingTo("470");
    }

    @Test
    void f5PasswordResetRevokesOldTokensButNotOtherAccountsOrNewLogins() throws Exception {
        var auth = register();
        var oldToken = auth.path("accessToken").asText();
        var otherToken = register().path("accessToken").asText();
        var user = users.findById(UUID.fromString(auth.path("userId").asText())).orElseThrow();
        var rawReset = UUID.randomUUID().toString();
        user.passwordResetTokenHash = tokens.hash(rawReset);
        user.passwordResetTokenExpiresAt = Instant.now().plusSeconds(300);
        users.saveAndFlush(user);
        postJson(null, "/api/auth/reset-password", "{\"token\":\"%s\",\"newPassword\":\"NewPassword123!\"}".formatted(rawReset));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldToken)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").header("Authorization", "Bearer " + oldToken)).andExpect(status().isUnauthorized());
        assertThat(getJson(otherToken, "/api/auth/me")).isNotNull();
        var login = postJson(null, "/api/auth/login", "{\"email\":\"%s\",\"password\":\"NewPassword123!\"}".formatted(user.email));
        assertThat(getJson(login.path("accessToken").asText(), "/api/auth/me")).isNotNull();
        postJson(null, "/api/auth/forgot-password", "{\"email\":\"%s\"}".formatted(user.email));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void f6InvoiceUploadCannotReturnInventedExtraction() throws Exception {
        var token = register().path("accessToken").asText();
        var response = mvc.perform(multipart("/api/imports/invoice-upload")
                .file(new MockMultipartFile("file", "receipt.png", "image/png", new byte[]{1, 2, 3}))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("INVOICE_EXTRACTION_UNAVAILABLE").doesNotContain("Ravi Traders", "confidence", "storageKey");
    }

    @Test
    void f2AssistantAlsoUsesSnapshotBalances() throws Exception {
        var auth = register();
        var token = auth.path("accessToken").asText();
        var tenant = UUID.fromString(auth.path("tenantId").asText());
        var customer = UUID.fromString(postJson(token, "/api/customers", "{\"name\":\"Snapshot Customer\"}").path("id").asText());
        var snapshot = new OutstandingSnapshot();
        snapshot.tenantId = tenant; snapshot.customerId = customer; snapshot.partyType = DomainEnums.OutstandingPartyType.CUSTOMER;
        snapshot.snapshotDate = LocalDate.of(2026, 8, 31); snapshot.outstandingAmount = new BigDecimal("570");
        saveSnapshot(snapshot);
        var answer = postJson(token, "/api/ai/assistant/chat", "{\"message\":\"customer outstanding\"}");
        assertThat(answer.path("evidence").path("customerOutstanding").get(0).path("Snapshot Customer").decimalValue()).isEqualByComparingTo("570");
    }

    @ParameterizedTest
    @CsvSource({"12,24,10", "0.5,1,240", "1,2,120"})
    void f3ConvertedPurchaseAndSaleKeepCostAndStockValueConsistent(String multiplier, String baseStock, String baseCost) throws Exception {
        var auth = register();
        var token = auth.path("accessToken").asText();
        var tenant = UUID.fromString(auth.path("tenantId").asText());
        var warehouse = warehouse(token);
        var created = postJson(token, "/api/products", "{\"name\":\"Pack product\",\"sku\":\"PACK\",\"unitCode\":\"PCS\"}");
        var product = products.findByTenantIdAndId(tenant, UUID.fromString(created.path("id").asText())).orElseThrow();
        var sourceUnit = new UnitOfMeasure();
        sourceUnit.tenantId = tenant; sourceUnit.name = "Pack"; sourceUnit.code = "PACK";
        units.saveAndFlush(sourceUnit);
        var conversion = new UnitConversion();
        conversion.tenantId = tenant; conversion.productId = product.id; conversion.fromUnitId = sourceUnit.id;
        conversion.toUnitId = product.baseUnitId; conversion.multiplier = new BigDecimal(multiplier);
        conversions.saveAndFlush(conversion);
        var purchase = postJson(token, "/api/purchases", """
            {"warehouseId":"%s","invoiceNumber":"PACK-P","invoiceDate":"2026-08-30","items":[{"productId":"%s","unitId":"%s","quantity":2,"rate":120,"taxPercentage":0}]}
            """.formatted(warehouse, product.id, sourceUnit.id));
        assertThat(purchase.path("totalAmount").decimalValue()).isEqualByComparingTo("240");
        var stock = getJson(token, "/api/stock/current?productId=" + product.id).get(0);
        assertThat(stock.path("currentStock").decimalValue()).isEqualByComparingTo(baseStock);
        assertThat(stock.path("stockValue").decimalValue()).isEqualByComparingTo("240");
        assertThat(products.findByTenantIdAndId(tenant, product.id).orElseThrow().defaultPurchasePrice).isEqualByComparingTo(baseCost);
        var movement = movements.findFirstByTenantIdAndProductIdAndMovementTypeOrderByMovementDateDesc(tenant, product.id, DomainEnums.MovementType.PURCHASE).orElseThrow();
        assertThat(movement.rate).isEqualByComparingTo("120"); // API rate remains in the source unit.
        assertThat(movement.totalValue).isEqualByComparingTo("240");
        postJson(token, "/api/sales", """
            {"warehouseId":"%s","invoiceNumber":"PACK-S","invoiceDate":"2026-08-31","items":[{"productId":"%s","unitId":"%s","quantity":1,"rate":200,"taxPercentage":0}]}
            """.formatted(warehouse, product.id, sourceUnit.id));
        var sale = movements.findFirstByTenantIdAndProductIdAndMovementTypeOrderByMovementDateDesc(tenant, product.id, DomainEnums.MovementType.SALE).orElseThrow();
        assertThat(sale.totalValue).isEqualByComparingTo("120");
        assertThat(getJson(token, "/api/stock/current?productId=" + product.id).get(0).path("stockValue").decimalValue()).isEqualByComparingTo("120");
        assertThat(getJson(token, "/api/dashboard/summary").path("totalStockValue").decimalValue()).isEqualByComparingTo("120");
        TenantContext.set(tenant, UUID.fromString(auth.path("userId").asText()), DomainEnums.Role.OWNER);
        try {
            var returned = ledger.createMovement(tenant, product.id, warehouse, DomainEnums.MovementType.RETURN_IN,
                BigDecimal.ONE, sourceUnit.id, new BigDecimal("120"), "AUDIT_RETURN", UUID.randomUUID(), Instant.now(), "Verified return");
            assertThat(returned.baseQuantity).isEqualByComparingTo(multiplier);
            assertThat(returned.totalValue).isEqualByComparingTo("120");
            var returnedOut = ledger.createMovement(tenant, product.id, warehouse, DomainEnums.MovementType.RETURN_OUT,
                BigDecimal.ONE, sourceUnit.id, new BigDecimal("120"), "AUDIT_RETURN", UUID.randomUUID(), Instant.now(), "Verified supplier return");
            assertThat(returnedOut.baseQuantity).isEqualByComparingTo(new BigDecimal(multiplier).negate());
            assertThat(returnedOut.totalValue).isEqualByComparingTo("120");
            var imported = ledger.createImportOpeningBalanceMovement(tenant, product.id, warehouse, BigDecimal.ONE,
                sourceUnit.id, new BigDecimal("120"), UUID.randomUUID(), Instant.now(), "Imported opening balance");
            assertThat(imported.totalValue).isEqualByComparingTo("120");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void f12CsvNeutralizesTextFormulasWithoutChangingNumericValues() throws Exception {
        var auth = register();
        var token = auth.path("accessToken").asText();
        var tenant = UUID.fromString(auth.path("tenantId").asText());
        for (var name : new String[]{"=1+1", "+SUM(1)", "-1+1", "@SUM(1)", "\t=1+1", "  =1+1", "Normal, quoted \"name\""}) {
            var result = postJson(token, "/api/customers", json.writeValueAsString(java.util.Map.of("name", name)));
            var customer = customers.findByTenantIdAndId(tenant, UUID.fromString(result.path("id").asText())).orElseThrow();
            customer.openingBalance = new BigDecimal("-25");
            customers.saveAndFlush(customer);
        }
        var csv = csv(token, "customers");
        assertThat(csv).contains(",'=1+1,", ",'+SUM(1),", ",'-1+1,", ",'@SUM(1),", ",'\t=1+1,", ",'  =1+1,");
        assertThat(csv).contains("\"Normal, quoted \"\"name\"\"\"").doesNotContain(",'=25", ",'-25");
        assertThat(csv.lines().skip(1)).allMatch(line -> line.endsWith("-25.00") || line.endsWith("-25"));
    }

    @Test
    void f11TallyStatusIncludesCompletedSmartImportsWithoutCountingRepeatedEffectsOrOtherTenants() throws Exception {
        var auth = register();
        var tenant = UUID.fromString(auth.path("tenantId").asText());
        var session = new ImportSession();
        session.tenantId = tenant; session.name = "Completed audit import";
        sessions.saveAndFlush(session);
        var commit = new SmartImportCommit();
        commit.tenantId = tenant; commit.importSessionId = session.id; commit.dryRunId = UUID.randomUUID();
        commit.status = DomainEnums.SmartImportCommitStatus.COMMITTED;
        commit.startedAt = Instant.parse("2026-08-31T10:00:00Z"); commit.finishedAt = commit.startedAt.plusSeconds(1);
        commits.saveAndFlush(commit);
        for (var type : new DomainEnums.SmartImportEffectEntityType[]{DomainEnums.SmartImportEffectEntityType.PRODUCT,
            DomainEnums.SmartImportEffectEntityType.CUSTOMER, DomainEnums.SmartImportEffectEntityType.SUPPLIER,
            DomainEnums.SmartImportEffectEntityType.SALES_INVOICE, DomainEnums.SmartImportEffectEntityType.PURCHASE_INVOICE}) {
            var id = UUID.randomUUID();
            for (int i = 0; i < 2; i++) {
                var effect = new SmartImportEffect();
                effect.tenantId = tenant; effect.importSessionId = session.id; effect.commitId = commit.id;
                effect.entityType = type; effect.entityId = id; effect.action = DomainEnums.SmartImportEffectAction.CREATED;
                effects.saveAndFlush(effect);
            }
        }
        var result = getJson(auth.path("accessToken").asText(), "/api/integrations/tally/status");
        assertThat(result.path("status").asText()).isEqualTo("CONNECTED_BY_EXPORT");
        assertThat(result.path("lastSuccessfulImportDate").asText()).isEqualTo("2026-08-31");
        for (var key : new String[]{"productsImported", "customersImported", "suppliersImported", "salesVouchersImported", "purchaseVouchersImported"}) {
            assertThat(result.path(key).asLong()).as(key).isEqualTo(1);
        }
        var other = getJson(register().path("accessToken").asText(), "/api/integrations/tally/status");
        assertThat(other.path("status").asText()).isEqualTo("NO_SUCCESSFUL_IMPORT");
        assertThat(other.path("productsImported").asLong()).isZero();
    }

    private void saveSnapshot(OutstandingSnapshot snapshot) {
        var session = new ImportSession();
        session.tenantId = snapshot.tenantId; session.name = "Snapshot test source";
        sessions.saveAndFlush(session);
        var file = new ImportSessionFile();
        file.tenantId = snapshot.tenantId; file.importSessionId = session.id;
        file.originalFileName = "outstanding.xml"; file.storageKey = "audit-fixture"; file.fileHash = UUID.randomUUID().toString();
        files.saveAndFlush(file);
        snapshot.sourceImportSessionId = session.id; snapshot.sourceFileId = file.id;
        snapshot.sourceRowNumber = 1; snapshot.sourceFingerprint = UUID.randomUUID().toString();
        snapshots.saveAndFlush(snapshot);
    }

    private JsonNode register() throws Exception {
        return postJson(null, "/api/auth/register", """
            {"businessName":"Audit regression","businessMode":"HYBRID","email":"audit-%s@example.com","password":"Password123!","fullName":"Audit Owner"}
            """.formatted(UUID.randomUUID()));
    }

    private UUID warehouse(String token) throws Exception {
        return UUID.fromString(postJson(token, "/api/warehouses", "{\"name\":\"Audit Warehouse\",\"code\":\"AUDIT\"}").path("id").asText());
    }

    private String roleToken(UUID tenant, DomainEnums.Role role, UUID customer) {
        var user = new UserAccount();
        user.email = UUID.randomUUID() + "@example.com"; user.fullName = "Audit Role"; user.passwordHash = "unused";
        users.saveAndFlush(user);
        var membership = new UserTenantMembership();
        membership.tenantId = tenant; membership.userId = user.id; membership.customerId = customer; membership.role = role;
        memberships.saveAndFlush(membership);
        return jwt.issue(user.id, tenant, user.email, role);
    }

    private JsonNode postJson(String token, String path, String body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode getJson(String token, String path) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String csv(String token, String report) throws Exception {
        return mvc.perform(get("/api/reports/export/" + report).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
