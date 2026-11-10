package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.JwtService;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.UserAccount;
import com.stockpilot.ai.domain.UserTenantMembership;
import com.stockpilot.ai.domain.SalesInvoice;
import com.stockpilot.ai.domain.PurchaseInvoice;
import com.stockpilot.ai.domain.StockMovement;
import com.stockpilot.ai.domain.StockSnapshotBaseline;
import com.stockpilot.ai.domain.ExternalRecordMapping;
import com.stockpilot.ai.repo.Repositories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Match the production dry-run constraints from V11 and V12, not Hibernate's defaults.
@Sql(statements = """
    alter table import_dry_run_items
    add constraint if not exists import_dry_run_items_dry_run_id_fkey
    foreign key (dry_run_id) references import_dry_runs(id) on delete cascade;
    alter table import_dry_runs alter column input_fingerprint varchar(64);
    """, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class SmartImportSessionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Repositories.UserRepository users;
    @Autowired Repositories.MembershipRepository memberships;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired Repositories.StockMovementRepository stockMovements;
    @Autowired Repositories.ProductRepository products;
    @Autowired Repositories.SalesInvoiceRepository salesInvoices;
    @Autowired Repositories.SalesInvoiceItemRepository salesInvoiceItems;
    @Autowired Repositories.PurchaseInvoiceRepository purchaseInvoices;
    @Autowired Repositories.PurchaseInvoiceItemRepository purchaseInvoiceItems;
    @Autowired Repositories.FinancialAdjustmentRepository financialAdjustments;
    @Autowired Repositories.FinancialAdjustmentItemRepository financialAdjustmentItems;
    @Autowired Repositories.SmartStagedVoucherRepository stagedVouchers;
    @Autowired Repositories.SmartStagedVoucherItemRepository stagedVoucherItems;
    @Autowired Repositories.ImportDryRunRepository dryRuns;
    @Autowired Repositories.ImportDryRunItemRepository dryRunItems;
    @Autowired Repositories.ImportSessionFileRepository importSessionFiles;
    @Autowired Repositories.SmartImportCommitRepository smartCommits;
    @Autowired Repositories.SmartImportEffectRepository smartEffects;
    @Autowired Repositories.ExternalRecordMappingRepository externalMappings;
    @Autowired Repositories.AuditLogRepository auditLogs;
    @Autowired Repositories.TenantRepository tenants;
    @Autowired Repositories.WarehouseRepository warehouses;
    @Autowired Repositories.CustomerPaymentRepository customerPayments;
    @Autowired Repositories.SupplierPaymentRepository supplierPayments;
    @Autowired Repositories.OutstandingSnapshotRepository outstandingSnapshots;
    @Autowired Repositories.SmartStagedCashbookEntryRepository stagedCashbook;
    @Autowired Repositories.CustomerRepository customers;
    @Autowired Repositories.SupplierRepository suppliers;
    @Autowired Repositories.StockSnapshotBaselineRepository snapshotBaselines;
    @Autowired Repositories.RetailCounterSaleRepository retailSales;
    @Autowired Repositories.SmartStagedProductRepository stagedProducts;

    @Test
    void productMasterAliasesKeepEverySourceNameAvailableDuringCommit() throws Exception {
        var registration = register("product-alias-commit");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"PRODUCT-ALIAS-WH\"}");
        var masters = new MockMultipartFile("files", "product-alias-masters.xml", "application/xml", """
            <ENVELOPE><BODY><DATA><COLLECTION>
            <STOCKITEM NAME="Sample Item A" GUID="stock-alias-001"><BASEUNITS>PCS</BASEUNITS></STOCKITEM>
            <STOCKITEM NAME="SampleItem A" GUID="stock-alias-001"><BASEUNITS>PCS</BASEUNITS></STOCKITEM>
            </COLLECTION></DATA></BODY></ENVELOPE>
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var sales = new MockMultipartFile("files", "alias-sales.xml", "application/xml",
            new String(fixture("sales.xml").getBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("Sample Item A", "SampleItem A").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var workspace = uploadClassifyAndStage(token, "Product source aliases", masters, sales);
        var path = "/api/import-sessions/" + workspace.path("testSessionId").asText();
        var preview = postJson(token, path + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        var result = postJson(token, path + "/commit", commitBody(preview.path("id").asText()));
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("COMMITTED");
        assertThat(products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)).hasSize(1);
        var product = products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).getFirst();
        assertThat(product.name).isEqualTo("Sample Item A");
        var invoice = salesInvoices.findByTenantIdAndInvoiceNumberIgnoreCase(tenantId, "S-001").orElseThrow();
        assertThat(salesInvoiceItems.findByTenantIdAndSalesInvoiceId(tenantId, invoice.id))
            .singleElement().satisfies(item -> assertThat(item.productId).isEqualTo(product.id));
    }

    @Test
    void voucherGuidCannotMergeDifferentProductsIncludingAlreadyStagedLegacyRows() throws Exception {
        var registration = register("voucher-product-identity");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var vouchers = new MockMultipartFile("files", "different-items.xml", "application/xml", """
            <ENVELOPE><BODY><DATA><TALLYMESSAGE><VOUCHER VCHTYPE="Sales" GUID="parent-voucher-not-a-product">
            <DATE>20260615</DATE><VOUCHERTYPENAME>Sales</VOUCHERTYPENAME><VOUCHERNUMBER>MULTI-001</VOUCHERNUMBER>
            <PARTYLEDGERNAME>Walk In Customer</PARTYLEDGERNAME>
            <ALLINVENTORYENTRIES.LIST><STOCKITEMNAME>Notebook A</STOCKITEMNAME><ACTUALQTY>2 PCS</ACTUALQTY><RATE>10/PCS</RATE><AMOUNT>20</AMOUNT></ALLINVENTORYENTRIES.LIST>
            <ALLINVENTORYENTRIES.LIST><STOCKITEMNAME>Notebook B</STOCKITEMNAME><ACTUALQTY>3 PCS</ACTUALQTY><RATE>15/PCS</RATE><AMOUNT>45</AMOUNT></ALLINVENTORYENTRIES.LIST>
            <ALLINVENTORYENTRIES.LIST><STOCKITEMNAME>Pen C</STOCKITEMNAME><ACTUALQTY>4 PCS</ACTUALQTY><RATE>5/PCS</RATE><AMOUNT>20</AMOUNT></ALLINVENTORYENTRIES.LIST>
            </VOUCHER></TALLYMESSAGE></DATA></BODY></ENVELOPE>
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var workspace = uploadClassifyAndStage(token, "Voucher identity isolation", vouchers);
        var sessionId = UUID.fromString(workspace.path("testSessionId").asText());
        var rows = stagedProducts.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId);
        assertThat(rows).hasSize(3).allSatisfy(row -> assertThat(row.externalId).isNull());
        // Reproduce sessions staged before this fix, without rebuilding or losing review choices.
        rows.forEach(row -> row.externalId = "parent-voucher-not-a-product");
        stagedProducts.saveAll(rows);
        var path = "/api/import-sessions/" + sessionId;
        var preview = postJson(token, path + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        var result = postJson(token, path + "/commit", commitBody(preview.path("id").asText()));
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("COMMITTED");
        assertThat(products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)).hasSize(3);
        var invoice = salesInvoices.findByTenantIdAndInvoiceNumberIgnoreCase(tenantId, "MULTI-001").orElseThrow();
        assertThat(invoice.totalAmount).isEqualByComparingTo("85");
        var items = salesInvoiceItems.findByTenantIdAndSalesInvoiceId(tenantId, invoice.id);
        assertThat(items).hasSize(3);
        assertThat(items.stream().map(item -> item.productId).distinct().count()).isEqualTo(3);
        assertThat(externalMappings.findFirstByTenantIdAndSourceSystemAndEntityTypeAndExternalId(
            tenantId, "SMART_IMPORT", "PRODUCT", "parent-voucher-not-a-product")).isEmpty();
    }

    @Test
    void gstinMergedMasterAliasesRemainUsableForInvoicesNotesOutstandingAndPayments() throws Exception {
        var registration = register("party-alias-commit");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"ALIAS-WH\"}");
        postJson(token, "/api/warehouses", "{\"name\":\"Main Godown\",\"code\":\"ALIAS-PURCHASE-WH\"}");
        var workspace = uploadClassifyAndStage(token, "GSTIN aliases",
            fixture("gstin_party_aliases.xml"), fixture("sales.xml"), fixture("purchase.xml"),
            fixture("credit_note.xml"), fixture("debit_note.xml"), fixture("debtors_creditors.xml"),
            fixture("cashbook_matched.xml"));
        var path = "/api/import-sessions/" + workspace.path("testSessionId").asText();
        var preview = postJson(token, path + "/dry-run",
            "{\"strategy\":\"TRANSACTION_HISTORY\",\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        assertThat(preview.path("summary").path("blockingErrors").asInt()).isZero();
        var committed = postJson(token, path + "/commit", commitBody(preview.path("id").asText()));
        assertThat(committed.path("status").asText()).as(committed.toPrettyString()).isEqualTo("COMMITTED");
        var customer = customers.findByTenantIdOrderByNameAsc(tenantId);
        var supplier = suppliers.findByTenantIdOrderByNameAsc(tenantId);
        assertThat(customer).singleElement().satisfies(row -> assertThat(row.name).isEqualTo("Sample-Customer"));
        assertThat(supplier).singleElement().satisfies(row -> assertThat(row.name).isEqualTo("Sample-Supplier"));
        assertThat(salesInvoices.findByTenantIdAndInvoiceNumberIgnoreCase(tenantId, "S-001").orElseThrow().customerId)
            .isEqualTo(customer.getFirst().id);
        assertThat(purchaseInvoices.findByTenantIdAndInvoiceNumberIgnoreCase(tenantId, "P-001").orElseThrow().supplierId)
            .isEqualTo(supplier.getFirst().id);
        for (var count : java.util.List.of("salesInvoicesCreated", "purchaseInvoicesCreated", "creditNotesCreated",
                "debitNotesCreated", "customerPaymentsCreated", "supplierPaymentsCreated")) {
            assertThat(committed.path("summary").path(count).asInt()).as(count).isEqualTo(1);
        }
        assertThat(committed.path("summary").path("outstandingSnapshotsCreated").asInt()).isEqualTo(2);
        assertThat(getJson(token, path + "/commit-result").path("status").asText()).isEqualTo("COMMITTED");
    }

    @Test
    void batchedCommitRetainsInvoiceTotalsAndRemainsIdempotent() throws Exception {
        var registration = register("batch-commit");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var workspace = uploadClassifyAndStage(token, "Batched vouchers", repeatedSales(125));
        var path = "/api/import-sessions/" + workspace.path("testSessionId").asText();
        var preview = postJson(token, path + "/dry-run",
            "{\"strategy\":\"TRANSACTION_HISTORY\",\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        var committed = postJson(token, path + "/commit", commitBody(preview.path("id").asText()));
        assertThat(committed.path("status").asText()).as(committed.toPrettyString()).isEqualTo("COMMITTED");
        assertThat(committed.path("summary").path("salesInvoicesCreated").asInt()).isEqualTo(125);
        assertThat(committed.path("summary").path("salesInvoiceItemsCreated").asInt()).isEqualTo(125);
        assertThat(salesInvoices.findByTenantId(tenantId))
            .hasSize(125).allSatisfy(row -> assertThat(row.totalAmount).isEqualByComparingTo("30"));
        var retried = postJson(token, path + "/commit", commitBody(preview.path("id").asText()));
        assertThat(retried.path("idempotentRetry").asBoolean()).isTrue();
        assertThat(salesInvoices.findByTenantId(tenantId)).hasSize(125);
    }

    @Test
    void lateVoucherFailureRollsBackEarlierFlushedBatchesAndPreservesStaging() throws Exception {
        var registration = register("late-batch-rollback");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var workspace = uploadClassifyAndStage(token, "Late batch failure", repeatedSales(80));
        var sessionId = UUID.fromString(workspace.path("testSessionId").asText());
        var path = "/api/import-sessions/" + sessionId;
        var preview = postJson(token, path + "/dry-run",
            "{\"strategy\":\"TRANSACTION_HISTORY\",\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        var last = stagedVouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(tenantId, sessionId).getLast();
        var conflict = new ExternalRecordMapping();
        conflict.tenantId = tenantId;
        conflict.sourceSystem = "SMART_IMPORT";
        conflict.entityType = "SALES_VOUCHER";
        conflict.normalizedKey = last.fingerprint;
        conflict.localEntityType = "SALES_INVOICE";
        conflict.localEntityId = UUID.randomUUID();
        conflict.lastSeenAt = java.time.Instant.now();
        externalMappings.save(conflict);
        var failed = postJson(token, path + "/commit", commitBody(preview.path("id").asText()));
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("summary").path("transactionRolledBack").asBoolean()).isTrue();
        assertThat(failed.path("summary").path("failureReason").asText()).contains("existing external mapping");
        assertThat(salesInvoices.findByTenantId(tenantId)).isEmpty();
        assertThat(products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)).isEmpty();
        assertThat(customers.findByTenantIdOrderByNameAsc(tenantId)).isEmpty();
        assertThat(smartEffects.findByTenantIdAndCommitIdOrderByCreatedAtAsc(tenantId, UUID.fromString(failed.path("id").asText()))).isEmpty();
        assertThat(stagedVouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(tenantId, sessionId)).hasSize(80);
        assertThat(getJson(token, path + "/commit-result").path("status").asText()).isEqualTo("FAILED");
    }

    private MockMultipartFile repeatedSales(int count) {
        var xml = new StringBuilder("<ENVELOPE><BODY><DATA><TALLYMESSAGE>");
        for (int i = 1; i <= count; i++) {
            xml.append("""
                <VOUCHER VCHTYPE="Sales"><DATE>20260615</DATE><VOUCHERTYPENAME>Sales</VOUCHERTYPENAME>
                <VOUCHERNUMBER>BATCH-%03d</VOUCHERNUMBER><PARTYLEDGERNAME>Batch Customer</PARTYLEDGERNAME>
                <ALLINVENTORYENTRIES.LIST><STOCKITEMNAME>Batch Item</STOCKITEMNAME>
                <ACTUALQTY>2 PCS</ACTUALQTY><RATE>15/PCS</RATE><AMOUNT>30</AMOUNT></ALLINVENTORYENTRIES.LIST>
                </VOUCHER>
                """.formatted(i));
        }
        xml.append("</TALLYMESSAGE></DATA></BODY></ENVELOPE>");
        return new MockMultipartFile("files", "batch-sales.xml", "application/xml",
            xml.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void undatedExportsRequireIndependentConfirmedDatesWithoutLosingPreviousPreview() throws Exception {
        var token = register("missing-snapshot-dates").get("accessToken").asText();
        var workspace = uploadClassifyAndStage(token, "Undated reports",
            fixture("closing_stock_undated.xml"), fixture("sundry_debtors_report.xml"));
        var sessionId = workspace.get("testSessionId").asText();
        var path = "/api/import-sessions/" + sessionId + "/dry-run";
        assertThat(workspace.path("summary").path("undatedStockSnapshots").asInt()).isEqualTo(1);
        assertThat(workspace.path("summary").path("undatedOutstandingSnapshots").asInt()).isEqualTo(2);
        var valid = confirmedSnapshotDates("2026-08-31", "2026-08-30");
        for (var invalid : java.util.List.of("{}",
            valid.replace("\"snapshotDateConfirmed\":true", "\"snapshotDateConfirmed\":false"),
            valid.replace("\"outstandingSnapshotDateConfirmed\":true", "\"outstandingSnapshotDateConfirmed\":false"),
            "{\"snapshotDate\":\"2026-08-31\",\"snapshotDateConfirmed\":true}")) {
            mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        var saved = postJson(token, path, valid);
        mvc.perform(post(path).header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isBadRequest());
        assertThat(getJson(token, path).get("id")).isEqualTo(saved.get("id"));
    }

    @Test
    void confirmedDatesAreIdenticalInPreviewAndFinalStockAndOutstandingRecords() throws Exception {
        var registration = register("confirmed-snapshot-dates");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"DATE-WH\"}");
        var workspace = uploadClassifyAndStage(token, "Historical undated reports",
            fixture("closing_stock_undated.xml"), fixture("sundry_debtors_report.xml"), fixture("sundry_creditors_report.xml"));
        var sessionId = workspace.get("testSessionId").asText();
        var path = "/api/import-sessions/" + sessionId;
        var dryRun = postJson(token, path + "/dry-run", confirmedSnapshotDates("2026-08-31", "2026-08-30"));
        assertThat(dryRun.path("summary").path("blockingErrors").asInt()).isZero();
        assertThat(dryRun.path("summary").path("snapshotDatePolicyVersion").asInt()).isEqualTo(1);
        assertThat(getJson(token, path + "/dry-run/items?itemType=STOCK_SNAPSHOT")
            .path("content").get(0).path("preview").path("snapshotDate").asText()).isEqualTo("2026-08-31");
        var outstandingPreview = getJson(token, path + "/dry-run/items?itemType=OUTSTANDING_SNAPSHOT").path("content");
        assertThat(outstandingPreview).isNotEmpty();
        outstandingPreview.forEach(item -> assertThat(item.path("preview").path("snapshotDate").asText()).isEqualTo("2026-08-30"));
        var committed = postJson(token, path + "/commit", commitBody(dryRun.path("id").asText()));
        assertThat(committed.path("status").asText()).isEqualTo("COMMITTED");
        assertThat(committed.path("summary").path("requestedSnapshotDate").asText()).isEqualTo("2026-08-31");
        assertThat(snapshotBaselines.findByTenantIdAndSourceImportSessionId(tenantId, UUID.fromString(sessionId)))
            .singleElement().satisfies(row -> assertThat(row.snapshotDate).isEqualTo(LocalDate.of(2026, 8, 31)));
        assertThat(stockMovements.findAll().stream().filter(row -> tenantId.equals(row.tenantId)).toList())
            .singleElement().satisfies(row -> assertThat(row.movementDate).isEqualTo(java.time.Instant.parse("2026-08-31T23:59:59Z")));
        assertThat(outstandingSnapshots.findByTenantIdAndSourceImportSessionIdOrderBySourceRowNumberAsc(tenantId, UUID.fromString(sessionId)))
            .hasSize(outstandingPreview.size()).allSatisfy(row -> assertThat(row.snapshotDate).isEqualTo(LocalDate.of(2026, 8, 30)));
    }

    @Test
    void sourceStockDateTakesPrecedenceOverManuallyConfirmedFallback() throws Exception {
        var registration = register("source-snapshot-date");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"SOURCE-DATE-WH\"}");
        var workspace = uploadClassifyAndStage(token, "Dated report", fixture("closing_stock_full_company.xml"));
        var sessionId = workspace.get("testSessionId").asText();
        var path = "/api/import-sessions/" + sessionId;
        var dryRun = postJson(token, path + "/dry-run", confirmedSnapshotDates("2026-08-31", "2026-08-31"));
        assertThat(getJson(token, path + "/dry-run/items?itemType=STOCK_SNAPSHOT")
            .path("content").get(0).path("preview").path("snapshotDate").asText()).isEqualTo("2026-06-30");
        assertThat(postJson(token, path + "/commit", commitBody(dryRun.path("id").asText())).path("status").asText()).isEqualTo("COMMITTED");
        assertThat(snapshotBaselines.findByTenantIdAndSourceImportSessionId(tenantId, UUID.fromString(sessionId)))
            .singleElement().satisfies(row -> assertThat(row.snapshotDate).isEqualTo(LocalDate.of(2026, 6, 30)));
    }

    @Test
    void legacyOrUnconfirmedPreviewCannotCommitEvenWithZeroBlockingErrors() throws Exception {
        var registration = register("unsafe-date-preview");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var workspace = uploadClassifyAndStage(token, "Legacy preview", fixture("closing_stock_undated.xml"));
        var sessionId = workspace.get("testSessionId").asText();
        var path = "/api/import-sessions/" + sessionId;
        var preview = postJson(token, path + "/dry-run", confirmedSnapshotDates("2026-08-31", "2026-08-31"));
        var persisted = dryRuns.findByTenantIdAndImportSessionIdAndId(tenantId, UUID.fromString(sessionId), UUID.fromString(preview.path("id").asText())).orElseThrow();
        persisted.summaryJson.remove("snapshotDatePolicyVersion");
        dryRuns.save(persisted);
        mvc.perform(post(path + "/commit").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(commitBody(persisted.id.toString()))).andExpect(status().isBadRequest());
        persisted.summaryJson.put("snapshotDatePolicyVersion", 1);
        persisted.summaryJson.put("snapshotDateConfirmed", false);
        dryRuns.save(persisted);
        mvc.perform(post(path + "/commit").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(commitBody(persisted.id.toString()))).andExpect(status().isBadRequest());
        assertThat(smartCommits.findByTenantIdAndImportSessionId(tenantId, UUID.fromString(sessionId))).isEmpty();
        assertThat(products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)).isEmpty();
    }

    @Test
    void changingConfirmedDateChangesFingerprintAndRejectsOldOrTamperedPreview() throws Exception {
        var registration = register("changed-date-preview");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var workspace = uploadClassifyAndStage(token, "Date changes", fixture("closing_stock_undated.xml"), fixture("sundry_debtors_report.xml"));
        var sessionId = workspace.get("testSessionId").asText();
        var path = "/api/import-sessions/" + sessionId;
        var first = postJson(token, path + "/dry-run", confirmedSnapshotDates("2026-08-31", "2026-08-31"));
        var firstSaved = dryRuns.findByTenantIdAndImportSessionIdAndId(tenantId, UUID.fromString(sessionId), UUID.fromString(first.path("id").asText())).orElseThrow();
        var second = postJson(token, path + "/dry-run", confirmedSnapshotDates("2026-08-31", "2026-08-30"));
        var secondSaved = dryRuns.findByTenantIdAndImportSessionIdAndId(tenantId, UUID.fromString(sessionId), UUID.fromString(second.path("id").asText())).orElseThrow();
        assertThat(secondSaved.inputFingerprint).isNotEqualTo(firstSaved.inputFingerprint).hasSize(64);
        mvc.perform(post(path + "/commit").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(commitBody(firstSaved.id.toString()))).andExpect(status().isBadRequest());
        secondSaved.summaryJson.put("requestedSnapshotDate", "2026-08-29");
        dryRuns.save(secondSaved);
        mvc.perform(post(path + "/commit").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(commitBody(secondSaved.id.toString()))).andExpect(status().isConflict());
        var otherToken = register("other-date-tenant").get("accessToken").asText();
        mvc.perform(post(path + "/dry-run").header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON).content(confirmedSnapshotDates("2026-08-31", "2026-08-31"))).andExpect(status().isNotFound());
    }

    @Test
    void confirmedStockDateAlsoControlsPostSnapshotMovementsInPreviewAndCommit() throws Exception {
        var registration = register("confirmed-date-movements");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var product = postJson(token, "/api/products", """
            {"sku":"CONFIRM-DATE-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"DATE-MOVEMENT-WH\"}");
        var workspace = uploadClassifyAndStage(token, "Undated snapshot with later sale",
            fixture("closing_stock_undated.xml"), fixture("sales_after_snapshot.xml"));
        var path = "/api/import-sessions/" + workspace.get("testSessionId").asText();
        var dryRun = postJson(token, path + "/dry-run", """
            {"strategy":"HYBRID_RECONCILIATION","stockImpactMode":"APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE",
             "snapshotDate":"2026-06-30","snapshotDateConfirmed":true}
            """);
        assertThat(dryRun.path("summary").path("salesStockMovementsPlanned").asInt()).isEqualTo(1);
        var committed = postJson(token, path + "/commit", commitBody(dryRun.path("id").asText()));
        assertThat(committed.path("status").asText()).isEqualTo("COMMITTED");
        assertThat(committed.path("summary").path("salesStockMovementsCreated").asInt()).isEqualTo(1);
        assertThat(stockMovements.currentStock(tenantId, UUID.fromString(product.path("id").asText()),
            UUID.fromString(warehouse.path("id").asText()))).isEqualByComparingTo("98");
    }

    private String confirmedSnapshotDates(String stock, String outstanding) {
        return """
            {"stockImpactMode":"CREATE_INVOICES_ONLY","snapshotDate":"%s","snapshotDateConfirmed":true,
             "outstandingSnapshotDate":"%s","outstandingSnapshotDateConfirmed":true}
            """.formatted(stock, outstanding);
    }

    @Test
    void retailCounterSalesRecordAllCollectionMethodsWithoutInventingItemsOrChangingStock() throws Exception {
        for (var method : DomainEnums.RetailCollectionMethod.values()) {
            var registration = register("retail-" + method);
            var token = registration.get("accessToken").asText();
            var tenantId = UUID.fromString(registration.get("tenantId").asText());
            var review = stageUnmatchedCashbook(token, "Retail collection");
            var before = java.util.List.of(stockMovements.count(), products.count(), salesInvoices.count(),
                customerPayments.count(), supplierPayments.count(), purchaseInvoices.count(), customers.count());
            var result = postJson(token, retailPath(review), retailBody(method.name()));
            assertThat(result.get("retailSaleRecorded").asBoolean()).isTrue();
            assertThat(result.get("paymentPosted").asBoolean()).isFalse();
            assertThat(result.get("resolutionStatus").asText()).isEqualTo("RETAIL_SALE_RECORDED");
            assertThat(java.util.List.of(stockMovements.count(), products.count(), salesInvoices.count(),
                customerPayments.count(), supplierPayments.count(), purchaseInvoices.count(), customers.count())).isEqualTo(before);
            var sales = getJson(token, "/api/sales/retail-counter?size=25");
            assertThat(sales.get("totalElements").asInt()).isEqualTo(1);
            assertThat(sales.get("content").get(0).get("amount").decimalValue()).isEqualByComparingTo("500");
            assertThat(sales.get("content").get(0).get("collectionMethod").asText()).isEqualTo(method.name());
            assertThat(getJson(token, "/api/import-sessions/" + review.get("sessionId").asText() + "/cashbook-review")).isEmpty();
            assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(log -> log.action))
                .contains("RETAIL_COUNTER_SALE_RECORDED", "CASHBOOK_REVIEW_RESOLVED")
                .doesNotContain("PAYMENT_MANUALLY_MATCHED");
        }
    }

    @Test
    void retailConfirmationRejectsMissingAcknowledgementsAndInvalidSourceValues() throws Exception {
        var registration = register("retail-validation");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var review = stageUnmatchedCashbook(token, "Confirmations");
        var valid = retailBody("CASH");
        for (var invalid : java.util.List.of(valid.replace("RECORD RETAIL SALES", ""),
            valid.replace("\"receivedFromRetailSales\":true", "\"receivedFromRetailSales\":false"),
            valid.replace("\"notAlreadyRecorded\":true", "\"notAlreadyRecorded\":false"),
            valid.replace("\"collectionMethod\":\"CASH\"", "\"collectionMethod\":null"))) {
            mvc.perform(post(retailPath(review)).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        var row = stagedCashbook.findById(UUID.fromString(review.get("entryId").asText())).orElseThrow();
        for (var amount : java.util.List.of("0", "-500", "0.001")) {
            row.amount = new BigDecimal(amount);
            stagedCashbook.save(row);
            mvc.perform(post(retailPath(review)).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isBadRequest());
        }
        assertThat(retailSales.countByTenantId(tenantId)).isZero();
    }

    @Test
    void retailPaymentDirectionRequiresExplanationAndPreservesSource() throws Exception {
        var token = register("retail-direction").get("accessToken").asText();
        var review = stageUnmatchedCashbook(token, "Direction review");
        var row = stagedCashbook.findById(UUID.fromString(review.get("entryId").asText())).orElseThrow();
        row.direction = DomainEnums.SmartCashbookDirection.PAYMENT;
        stagedCashbook.save(row);
        var body = retailBody("CASH_AND_UPI");
        mvc.perform(post(retailPath(review)).header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(body.replace("Daily shop collections", "")))
            .andExpect(status().isBadRequest());
        postJson(token, retailPath(review), body);
        var sale = getJson(token, "/api/sales/retail-counter").get("content").get(0);
        assertThat(sale.get("sourceDirection").asText()).isEqualTo("PAYMENT");
        assertThat(sale.get("note").asText()).isEqualTo("Daily shop collections");
        assertThat(stagedCashbook.findById(row.id).orElseThrow().direction).isEqualTo(DomainEnums.SmartCashbookDirection.PAYMENT);
    }

    @Test
    void retailRecordsSurviveRebuildRepeatedClickAndReimportAndAreSkippedByFinalCommit() throws Exception {
        var registration = register("retail-dedup");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var review = stageUnmatchedCashbook(token, "Original collection");
        var sessionId = review.get("sessionId").asText();
        postJson(token, retailPath(review), retailBody("UPI"));
        var retry = postJson(token, retailPath(review), retailBody("UPI"));
        assertThat(retry.get("duplicateRetailSaleSkipped").asBoolean()).isTrue();
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        assertThat(getJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review")).isEmpty();
        var next = stageUnmatchedCashbook(token, "Same source uploaded again");
        var nextId = next.get("sessionId").asText();
        assertThat(getJson(token, "/api/import-sessions/" + nextId + "/cashbook-review")).isEmpty();
        var dryRun = postJson(token, "/api/import-sessions/" + nextId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        assertThat(dryRun.get("summary").get("retailCounterSaleRowsResolved").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("cashbookRowsUnmatched").asInt()).isZero();
        var commit = postJson(token, "/api/import-sessions/" + nextId + "/commit", commitBody(dryRun.get("id").asText()));
        assertThat(commit.get("summary").get("retailCounterSaleRowsResolved").asInt()).isEqualTo(1);
        assertThat(commit.get("summary").get("customerPaymentsCreated").asInt()).isZero();
        assertThat(commit.get("summary").get("supplierPaymentsCreated").asInt()).isZero();
        assertThat(retailSales.countByTenantId(tenantId)).isEqualTo(1);
    }

    @Test
    void retailAndPaymentPostingCannotCountTheSameFingerprintTwice() throws Exception {
        var token = register("retail-payment-conflict").get("accessToken").asText();
        var customer = postJson(token, "/api/customers", "{\"name\":\"Named customer\",\"creditLimit\":5000}");
        var first = stageUnmatchedCashbook(token, "First copy");
        var second = stageUnmatchedCashbook(token, "Second copy");
        postJson(token, retailPath(first), retailBody("CASH"));
        mvc.perform(post(retailPath(second)).header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(cashbookResolution("MAP_CUSTOMER", customer.get("id").asText())))
            .andExpect(status().isConflict());

        var token2 = register("payment-retail-conflict").get("accessToken").asText();
        var customer2 = postJson(token2, "/api/customers", "{\"name\":\"Another customer\",\"creditLimit\":5000}");
        var payment = stageUnmatchedCashbook(token2, "Payment first");
        var retail = stageUnmatchedCashbook(token2, "Retail second");
        postJson(token2, retailPath(payment), cashbookResolution("MAP_CUSTOMER", customer2.get("id").asText()));
        mvc.perform(post(retailPath(retail)).header("Authorization", "Bearer " + token2)
            .contentType(MediaType.APPLICATION_JSON).content(retailBody("CASH"))).andExpect(status().isConflict());
    }

    @Test
    void retailResolutionAfterCommitUpdatesReconciliation() throws Exception {
        var token = register("retail-post-commit").get("accessToken").asText();
        var review = stageUnmatchedCashbook(token, "Review after commit");
        var sessionId = review.get("sessionId").asText();
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));
        postJson(token, retailPath(review), retailBody("UPI"));
        var summary = getJson(token, "/api/import-sessions/" + sessionId + "/commit-result").get("summary");
        assertThat(summary.get("cashbookRowsUnmatched").asInt()).isZero();
        assertThat(summary.get("retailCounterSaleRowsResolved").asInt()).isEqualTo(1);
    }

    @Test
    void retailSalesAreTenantScopedAndCustomerAndReadOnlyUsersCannotPost() throws Exception {
        var registration = register("retail-security");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var other = register("retail-other").get("accessToken").asText();
        var review = stageUnmatchedCashbook(token, "Restricted retail review");
        mvc.perform(post(retailPath(review)).header("Authorization", "Bearer " + other)
            .contentType(MediaType.APPLICATION_JSON).content(retailBody("CASH"))).andExpect(status().isNotFound());
        for (var role : java.util.List.of(DomainEnums.Role.CUSTOMER_USER, DomainEnums.Role.VIEWER)) {
            var roleToken = tokenForRole(tenantId, role);
            mvc.perform(post(retailPath(review)).header("Authorization", "Bearer " + roleToken)
                .contentType(MediaType.APPLICATION_JSON).content(retailBody("CASH"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/sales/retail-counter").header("Authorization", "Bearer " + tokenForRole(tenantId, DomainEnums.Role.CUSTOMER_USER)))
            .andExpect(status().isForbidden());
        postJson(token, retailPath(review), retailBody("CASH"));
        assertThat(getJson(other, "/api/sales/retail-counter").get("totalElements").asInt()).isZero();
    }

    @Test
    void bulkRetailRecordsExplicitSelectionAndSkipsDuplicatesWithoutBusinessSideEffects() throws Exception {
        var registration = register("retail-bulk");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var sessionId = createSession(token, "Bulk Cash and GPay").get("id").asText();
        upload(token, sessionId, fixture("cashbook_bulk_retail.csv"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var review = getJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review");
        assertThat(review.size()).isEqualTo(3);
        var selectedIds = new java.util.ArrayList<String>();
        review.forEach(row -> {
            if (row.get("partyName").asText().contains("Counter")) {
                selectedIds.add(row.get("id").asText());
            }
        });
        assertThat(selectedIds).hasSize(2);
        var before = java.util.List.of(stockMovements.count(), products.count(), salesInvoices.count(),
            customerPayments.count(), supplierPayments.count(), purchaseInvoices.count(), customers.count());

        var result = postJson(token, bulkRetailPath(sessionId),
            bulkRetailBody(selectedIds, "CASH_AND_UPI", "Verified counter collections"));

        assertThat(result.get("requestedCount").asInt()).isEqualTo(2);
        assertThat(result.get("recordedCount").asInt()).isEqualTo(2);
        assertThat(result.get("duplicateCount").asInt()).isZero();
        assertThat(result.get("failedCount").asInt()).isZero();
        assertThat(result.get("recordedAmount").decimalValue()).isEqualByComparingTo("650.75");
        assertThat(result.get("results").findValuesAsText("status")).containsOnly("RECORDED");
        assertThat(java.util.List.of(stockMovements.count(), products.count(), salesInvoices.count(),
            customerPayments.count(), supplierPayments.count(), purchaseInvoices.count(), customers.count())).isEqualTo(before);
        assertThat(retailSales.countByTenantId(tenantId)).isEqualTo(2);
        var remaining = getJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review");
        assertThat(remaining.size()).isEqualTo(1);
        assertThat(remaining.get(0).get("partyName").asText()).isEqualTo("Owner Transfer");

        var retry = postJson(token, bulkRetailPath(sessionId),
            bulkRetailBody(selectedIds, "CASH_AND_UPI", "Verified counter collections"));
        assertThat(retry.get("recordedCount").asInt()).isZero();
        assertThat(retry.get("duplicateCount").asInt()).isEqualTo(2);
        assertThat(retry.get("failedCount").asInt()).isZero();
        assertThat(retailSales.countByTenantId(tenantId)).isEqualTo(2);
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(log -> log.action))
            .contains("CASHBOOK_BULK_RETAIL_RESOLVED", "RETAIL_COUNTER_SALE_RECORDED", "CASHBOOK_REVIEW_RESOLVED");
    }

    @Test
    void bulkRetailReportsPartialFailureAndLeavesFailedRowsForReview() throws Exception {
        var registration = register("retail-bulk-partial");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var sessionId = createSession(token, "Partial bulk retail").get("id").asText();
        upload(token, sessionId, fixture("cashbook_bulk_retail.csv"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var review = getJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review");
        var selectedIds = java.util.List.of(review.get(0).get("id").asText(), review.get(1).get("id").asText());

        var result = postJson(token, bulkRetailPath(sessionId), bulkRetailBody(selectedIds, "CASH", ""));

        assertThat(result.get("recordedCount").asInt()).isEqualTo(1);
        assertThat(result.get("duplicateCount").asInt()).isZero();
        assertThat(result.get("failedCount").asInt()).isEqualTo(1);
        assertThat(result.get("partialSuccess").asBoolean()).isTrue();
        assertThat(result.get("recordedAmount").decimalValue()).isEqualByComparingTo("150.25");
        assertThat(result.get("results").findValuesAsText("status")).containsExactly("RECORDED", "FAILED");
        var failed = result.get("results").get(1);
        assertThat(failed.get("sourceRowNumber").asInt()).isEqualTo(2);
        assertThat(failed.get("message").asText()).contains("Explain why");
        assertThat(retailSales.countByTenantId(tenantId)).isEqualTo(1);
        assertThat(getJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review").size()).isEqualTo(2);

        var recovered = postJson(token, bulkRetailPath(sessionId),
            bulkRetailBody(java.util.List.of(failed.get("entryId").asText()), "UPI", "Verified GPay shop collection"));
        assertThat(recovered.get("recordedCount").asInt()).isEqualTo(1);
        assertThat(recovered.get("failedCount").asInt()).isZero();
        assertThat(retailSales.countByTenantId(tenantId)).isEqualTo(2);
    }

    @Test
    void bulkRetailIsTenantScopedPermissionCheckedAndRejectsAmbiguousSelection() throws Exception {
        var registration = register("retail-bulk-security");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var sessionId = createSession(token, "Protected bulk retail").get("id").asText();
        upload(token, sessionId, fixture("cashbook_bulk_retail.csv"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var entryId = getJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review").get(0).get("id").asText();
        var body = bulkRetailBody(java.util.List.of(entryId), "CASH", "Verified cash sale");
        var other = register("retail-bulk-other").get("accessToken").asText();

        mvc.perform(post(bulkRetailPath(sessionId)).header("Authorization", "Bearer " + other)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        for (var role : java.util.List.of(DomainEnums.Role.CUSTOMER_USER, DomainEnums.Role.VIEWER)) {
            mvc.perform(post(bulkRetailPath(sessionId))
                .header("Authorization", "Bearer " + tokenForRole(tenantId, role))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        }
        mvc.perform(post(bulkRetailPath(sessionId)).header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.replace("RECORD 1 RETAIL SALES", "RECORD RETAIL SALES")))
            .andExpect(status().isBadRequest());
        mvc.perform(post(bulkRetailPath(sessionId)).header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(bulkRetailBody(java.util.List.of(entryId, entryId), "CASH", "Duplicate selection")))
            .andExpect(status().isBadRequest());
        assertThat(retailSales.countByTenantId(tenantId)).isZero();
    }

    private String retailBody(String method) {
        return """
            {"action":"RECORD_RETAIL_SALE","collectionMethod":"%s","confirmation":"RECORD RETAIL SALES",
             "receivedFromRetailSales":true,"notAlreadyRecorded":true,"note":"Daily shop collections"}
            """.formatted(method);
    }

    private String bulkRetailPath(String sessionId) {
        return "/api/import-sessions/" + sessionId + "/cashbook-review/retail-sales/bulk";
    }

    private String bulkRetailBody(java.util.List<String> entryIds, String method, String note) throws Exception {
        var body = mapper.createObjectNode();
        var ids = body.putArray("entryIds");
        entryIds.forEach(ids::add);
        body.put("collectionMethod", method);
        body.put("confirmation", "RECORD " + entryIds.size() + " RETAIL SALES");
        body.put("note", note);
        body.put("receivedFromRetailSales", true);
        body.put("notAlreadyRecorded", true);
        return mapper.writeValueAsString(body);
    }

    @Test
    void concurrentRetailResolutionsAcrossSessionsCreateOneRecord() throws Exception {
        var registration = register("retail-concurrent");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var first = stageUnmatchedCashbook(token, "Concurrent first");
        var second = stageUnmatchedCashbook(token, "Concurrent second");
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return postJson(token, retailPath(first), retailBody("CASH")); });
            var b = executor.submit(() -> { start.await(); return postJson(token, retailPath(second), retailBody("CASH")); });
            start.countDown();
            var results = java.util.List.of(a.get(20, java.util.concurrent.TimeUnit.SECONDS), b.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().filter(result -> result.get("duplicateRetailSaleSkipped").asBoolean()).count()).isEqualTo(1);
            assertThat(retailSales.countByTenantId(tenantId)).isEqualTo(1);
        }
    }

    @Test
    void retailRecordedAfterAnotherSessionsDryRunCannotBecomeAnAutomaticPayment() throws Exception {
        var registration = register("retail-auto-payment");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var review = stageUnmatchedCashbook(token, "Retail source");
        postJson(token, "/api/customers", "{\"name\":\"Unknown Customer\",\"creditLimit\":5000}");
        var other = stageUnmatchedCashbook(token, "Now automatically matched");
        var sessionId = other.get("sessionId").asText();
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        assertThat(dryRun.get("summary").get("customerPaymentsPreviewed").asInt()).isEqualTo(1);
        postJson(token, retailPath(review), retailBody("CASH"));
        var result = postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));
        assertThat(result.get("status").asText()).isEqualTo("FAILED");
        assertThat(result.get("summary").get("failureReason").asText()).contains("reconciliation differs");
        dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        result = postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));
        assertThat(result.get("summary").get("customerPaymentsCreated").asInt()).isZero();
        assertThat(result.get("summary").get("retailCounterSaleRowsResolved").asInt()).isEqualTo(1);
        assertThat(customerPayments.countByTenantIdAndSourceImportSessionId(tenantId, UUID.fromString(sessionId))).isZero();
    }

    private String retailPath(JsonNode review) {
        return "/api/import-sessions/" + review.get("sessionId").asText() + "/cashbook-review/" + review.get("entryId").asText() + "/resolve";
    }

    @Test
    void stockJournalDefaultModeDefersTransferWithoutInventoryEffect() throws Exception {
        var registration = register("smart-transfer-default-defer");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var setup = createTransferInventory(token, tenantId);
        var movementCount = stockMovements.count();
        var sessionId = createSession(token, "Deferred stock journal").get("id").asText();
        upload(token, sessionId, fixture("stock_journal_transfer.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var workspace = postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        assertThat(workspace.get("summary").get("stockJournalsDetected").asInt()).isEqualTo(1);
        assertThat(workspace.get("summary").get("warehouseTransfersDetected").asInt()).isEqualTo(1);
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"stockJournalImpactMode\":\"DEFER_STOCK_JOURNALS\"}");

        assertThat(dryRun.get("summary").get("stockJournalImpactMode").asText()).isEqualTo("DEFER_STOCK_JOURNALS");
        assertThat(dryRun.get("summary").get("transferOutMovementsPlanned").asInt()).isZero();
        assertThat(dryRun.get("summary").get("transferInMovementsPlanned").asInt()).isZero();
        assertThat(stockMovements.count()).isEqualTo(movementCount);
        assertTransferStock(tenantId, setup, "100", "40");
    }

    @Test
    void confirmedPostSnapshotTransferCreatesAtomicPairAndKeepsCompanyStockZeroNet() throws Exception {
        var registration = register("smart-transfer-commit");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var setup = createTransferInventory(token, tenantId);
        var sessionId = createSession(token, "Confirmed transfer").get("id").asText();
        upload(token, sessionId, fixture("stock_journal_transfer.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {"stockJournalImpactMode":"APPLY_CONFIRMED_WAREHOUSE_TRANSFERS_AFTER_SNAPSHOT_DATE","negativeStockPolicy":"BLOCK"}
            """);
        assertThat(dryRun.get("summary").get("warehouseTransfersEligible").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("transferOutMovementsPlanned").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("transferInMovementsPlanned").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("netCompanyStockEffect").decimalValue()).isEqualByComparingTo("0");

        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(dryRun.get("id").asText()));
        assertThat(committed.get("summary").get("warehouseTransfersCommitted").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("transferOutMovementsCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("transferInMovementsCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("netCompanyStockQuantityChange").decimalValue()).isEqualByComparingTo("0");
        assertTransferStock(tenantId, setup, "80", "60");

        var movements = stockMovements.findAll().stream()
            .filter(row -> tenantId.equals(row.tenantId)
                && (row.movementType == DomainEnums.MovementType.TRANSFER_OUT
                    || row.movementType == DomainEnums.MovementType.TRANSFER_IN))
            .toList();
        assertThat(movements).hasSize(2);
        assertThat(movements).extracting(row -> row.transferGroupId).doesNotContainNull().containsOnly(movements.getFirst().transferGroupId);
        assertThat(movements.get(0).linkedMovementId).isEqualTo(movements.get(1).id);
        assertThat(movements.get(1).linkedMovementId).isEqualTo(movements.get(0).id);
    }

    @Test
    void repeatedTransferUploadIsIdempotentAcrossSessions() throws Exception {
        var registration = register("smart-transfer-idempotent");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var setup = createTransferInventory(token, tenantId);

        for (int attempt = 0; attempt < 2; attempt++) {
            var sessionId = createSession(token, "Transfer attempt " + attempt).get("id").asText();
            upload(token, sessionId, fixture("stock_journal_transfer.xml"));
            postJson(token, "/api/import-sessions/" + sessionId + "/classify");
            postJson(token, "/api/import-sessions/" + sessionId + "/stage");
            var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
                {"stockJournalImpactMode":"APPLY_CONFIRMED_WAREHOUSE_TRANSFERS_AFTER_SNAPSHOT_DATE","negativeStockPolicy":"BLOCK"}
                """);
            var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
                commitBody(dryRun.get("id").asText()));
            if (attempt == 1) {
                assertThat(committed.get("summary").get("duplicateWarehouseTransfersSkipped").asInt()).isEqualTo(1);
                assertThat(committed.get("summary").get("transferPairsCreated").asInt()).isZero();
            }
        }
        assertTransferStock(tenantId, setup, "80", "60");
    }

    @Test
    void manufacturingStockJournalIsPreservedAndDeferred() throws Exception {
        var token = register("smart-transfer-manufacturing").get("accessToken").asText();
        var sessionId = createSession(token, "Manufacturing journal").get("id").asText();
        upload(token, sessionId, fixture("stock_journal_manufacturing.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var workspace = postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        assertThat(workspace.get("transferCandidates").findValuesAsText("classification"))
            .contains("MIXED_MANUFACTURING_JOURNAL");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {"stockJournalImpactMode":"APPLY_CONFIRMED_WAREHOUSE_TRANSFERS_AFTER_SNAPSHOT_DATE"}
            """);
        assertThat(dryRun.get("summary").get("manufacturingJournalsDeferred").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("transferOutMovementsPlanned").asInt()).isZero();
        assertThat(dryRun.get("summary").get("transferInMovementsPlanned").asInt()).isZero();
    }

    @Test
    void transferNegativeStockPolicyBlocksOrSkipsTheCompletePair() throws Exception {
        for (var policy : new String[] {"BLOCK", "SKIP_STOCK_MOVEMENT"}) {
            var registration = register("smart-transfer-negative-" + policy.toLowerCase());
            var token = registration.get("accessToken").asText();
            var tenantId = UUID.fromString(registration.get("tenantId").asText());
            var setup = createTransferInventory(token, tenantId);
            saveMovementAt(tenantId, setup.product(), setup.source(), "-90",
                java.time.Instant.parse("2026-04-01T12:00:00Z"));
            var sessionId = createSession(token, "Negative source " + policy).get("id").asText();
            upload(token, sessionId, fixture("stock_journal_transfer.xml"));
            postJson(token, "/api/import-sessions/" + sessionId + "/classify");
            postJson(token, "/api/import-sessions/" + sessionId + "/stage");

            var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
                {"stockJournalImpactMode":"APPLY_CONFIRMED_WAREHOUSE_TRANSFERS_AFTER_SNAPSHOT_DATE","negativeStockPolicy":"%s"}
                """.formatted(policy));
            assertThat(dryRun.get("summary").get("transferOutMovementsPlanned").asInt()).isZero();
            assertThat(dryRun.get("summary").get("transferInMovementsPlanned").asInt()).isZero();
            if ("BLOCK".equals(policy)) {
                assertThat(dryRun.get("summary").get("warehouseTransfersBlockedByNegativeStock").asInt()).isEqualTo(1);
                assertThat(dryRun.get("summary").get("blockingErrors").asInt()).isEqualTo(1);
            } else {
                assertThat(dryRun.get("summary").get("warehouseTransfersSkippedByPolicy").asInt()).isEqualTo(1);
                var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
                    commitBody(dryRun.get("id").asText()));
                assertThat(committed.get("summary").get("transferPairsCreated").asInt()).isZero();
            }
            assertTransferStock(tenantId, setup, "10", "40");
        }
    }

    @Test
    void transferReviewRequiresExactConfirmationAndBlocksCustomerUserAndOtherTenant() throws Exception {
        var registration = register("smart-transfer-review-security");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        createTransferInventory(token, tenantId);
        var sessionId = createSession(token, "Transfer review security").get("id").asText();
        upload(token, sessionId, fixture("stock_journal_transfer.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var review = getJson(token, "/api/import-sessions/" + sessionId + "/transfer-review");
        var candidate = review.get(0);

        mvc.perform(post("/api/import-sessions/{sessionId}/transfer-review/{candidateId}/resolve",
                sessionId, candidate.get("id").asText())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferResolution(candidate, "WRONG CONFIRMATION")))
            .andExpect(status().isBadRequest());

        var resolved = postJson(token,
            "/api/import-sessions/" + sessionId + "/transfer-review/" + candidate.get("id").asText() + "/resolve",
            transferResolution(candidate, "POST STOCK TRANSFER"));
        assertThat(resolved.get("resolutionStatus").asText()).isEqualTo("USER_CONFIRMED");
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(log -> log.action))
            .contains("SMART_IMPORT_WAREHOUSE_TRANSFER_MANUALLY_RESOLVED");

        var otherToken = register("smart-transfer-review-other").get("accessToken").asText();
        mvc.perform(get("/api/import-sessions/{sessionId}/transfer-review", sessionId)
                .header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isNotFound());

        var customerToken = tokenForRole(tenantId, DomainEnums.Role.CUSTOMER_USER);
        mvc.perform(get("/api/import-sessions/{sessionId}/transfer-review", sessionId)
                .header("Authorization", "Bearer " + customerToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void createsAndListsImportSession() throws Exception {
        var registration = register("smart-create");
        var token = registration.get("accessToken").asText();

        var session = createSession(token, "Quarter-end Tally files");
        assertThat(session.get("name").asText()).isEqualTo("Quarter-end Tally files");
        assertThat(session.get("status").asText()).isEqualTo("CREATED");

        var list = getJson(token, "/api/import-sessions?size=20");
        assertThat(list.get("content").findValuesAsText("id")).contains(session.get("id").asText());
    }

    @Test
    void uploadsMultipleFilesAndClassifiesThemWithoutCreatingBusinessData() throws Exception {
        var token = register("smart-multi").get("accessToken").asText();
        var sessionId = createSession(token, "Mixed exports").get("id").asText();

        upload(token, sessionId, fixture("sales.xml"), fixture("purchase.xml"));
        var classified = postJson(token, "/api/import-sessions/" + sessionId + "/classify");

        assertThat(classified.get("files")).hasSize(2);
        assertThat(classified.get("files").findValuesAsText("detectedFileType"))
            .containsExactlyInAnyOrder("SALES_VOUCHERS", "PURCHASE_VOUCHERS");
    }

    @Test
    void classifiesAndRoutesMixedTallyExportsAndOutstandingRowsByTheirActualContent() throws Exception {
        var token = register("smart-mixed-tally").get("accessToken").asText();
        var sessionId = createSession(token, "Combined Tally exports").get("id").asText();

        upload(token, sessionId,
            fixture("mixed_masters.xml"),
            fixture("mixed_vouchers.xml"),
            fixture("sundry_debtors_report.xml"),
            fixture("sundry_creditors_report.xml"));
        var classified = postJson(token, "/api/import-sessions/" + sessionId + "/classify");

        assertThat(classified.get("files").findValuesAsText("detectedFileType"))
            .contains("MIXED_TALLY_MASTERS", "MIXED_TALLY_VOUCHERS", "DEBTOR_CREDITOR_ANALYSIS");
        var workspace = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        assertThat(workspace.get("summary").get("productsStaged").asInt()).isEqualTo(2);
        assertThat(workspace.get("summary").get("partiesStaged").asInt()).isEqualTo(5);
        assertThat(workspace.get("summary").get("vouchersStaged").asInt()).isEqualTo(4);
        assertThat(workspace.get("summary").get("voucherItemsStaged").asInt()).isEqualTo(2);
        assertThat(workspace.get("summary").get("cashbookEntriesStaged").asInt()).isEqualTo(2);
        assertThat(workspace.get("summary").get("debtorRowsStaged").asInt()).isEqualTo(2);
        assertThat(workspace.get("summary").get("creditorRowsStaged").asInt()).isEqualTo(1);
        assertThat(workspace.get("vouchers").findValuesAsText("voucherType"))
            .containsExactlyInAnyOrder("Sales", "Purchase", "Credit Note", "Debit Note");
        assertThat(workspace.get("cashbookEntries").findValuesAsText("direction"))
            .containsExactlyInAnyOrder("RECEIPT", "PAYMENT");
        assertThat(workspace.get("parties").findValuesAsText("name"))
            .contains("Report Customer One", "Report Customer Two", "Report Supplier One");
    }

    @Test
    void warnsForDuplicateFileHashAndReusesClassification() throws Exception {
        var token = register("smart-duplicate").get("accessToken").asText();
        var sessionId = createSession(token, "Duplicate check").get("id").asText();

        var uploaded = upload(token, sessionId, fixture("closing_stock_full_company.xml"), fixture("duplicate_file.xml"));
        assertThat(uploaded.get("issues").findValuesAsText("code")).contains("DUPLICATE_FILE");
        var classified = postJson(token, "/api/import-sessions/" + sessionId + "/classify");

        assertThat(classified.get("files").findValuesAsText("status")).contains("DUPLICATE");
        assertThat(classified.get("files").findValuesAsText("detectedFileType"))
            .containsOnly("STOCK_SNAPSHOT");
        assertThat(classified.get("issues").findValuesAsText("code")).contains("DUPLICATE_FILE");
    }

    @Test
    void buildsSnapshotFirstPlanForSnapshotOnly() throws Exception {
        var token = register("smart-snapshot").get("accessToken").asText();
        var sessionId = createSession(token, "Snapshot").get("id").asText();
        upload(token, sessionId, fixture("closing_stock_full_company.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");

        var plan = postJson(token, "/api/import-sessions/" + sessionId + "/plan");
        assertThat(plan.get("strategy").asText()).isEqualTo("SNAPSHOT_FIRST");
        assertThat(plan.get("details").get("commitEnabled").asBoolean()).isFalse();
    }

    @Test
    void buildsTransactionHistoryPlanWithoutSnapshot() throws Exception {
        var token = register("smart-history").get("accessToken").asText();
        var sessionId = createSession(token, "Transaction history").get("id").asText();
        upload(token, sessionId, fixture("sales.xml"), fixture("purchase.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");

        var plan = postJson(token, "/api/import-sessions/" + sessionId + "/plan");
        assertThat(plan.get("strategy").asText()).isEqualTo("TRANSACTION_HISTORY");
    }

    @Test
    void buildsHybridPlanAndWarningWhenSnapshotAndTransactionsExist() throws Exception {
        var token = register("smart-hybrid").get("accessToken").asText();
        var sessionId = createSession(token, "Hybrid set").get("id").asText();
        upload(token, sessionId, fixture("closing_stock_full_company.xml"), fixture("sales.xml"), fixture("purchase.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");

        var plan = postJson(token, "/api/import-sessions/" + sessionId + "/plan");
        assertThat(plan.get("strategy").asText()).isEqualTo("HYBRID_RECONCILIATION");
        assertThat(plan.get("issues").findValuesAsText("code")).contains("TRANSACTION_FILES_WITH_SNAPSHOT");
    }

    @Test
    void unknownFileMovesSessionToNeedsReview() throws Exception {
        var token = register("smart-unknown").get("accessToken").asText();
        var sessionId = createSession(token, "Unknown input").get("id").asText();
        upload(token, sessionId, fixture("unknown_file.xml"));

        var classified = postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        assertThat(classified.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(classified.get("issues").findValuesAsText("code"))
            .contains("UNKNOWN_FILE_TYPE", "REVIEW_REQUIRED_FOR_UNCERTAIN_FILES");
    }

    @Test
    void tenantCannotAccessAnotherTenantSession() throws Exception {
        var tokenA = register("smart-tenant-a").get("accessToken").asText();
        var sessionId = createSession(tokenA, "Tenant A private files").get("id").asText();
        var tokenB = register("smart-tenant-b").get("accessToken").asText();

        mvc.perform(get("/api/import-sessions/{id}", sessionId).header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isNotFound());
    }

    @Test
    void customerUserCannotAccessSmartImportSessions() throws Exception {
        var registration = register("smart-customer-owner");
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var customerToken = tokenForRole(tenantId, DomainEnums.Role.CUSTOMER_USER);

        mvc.perform(get("/api/import-sessions").header("Authorization", "Bearer " + customerToken))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/import-sessions")
                .header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Forbidden\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void stagesSnapshotAndCalculatesDeltaWithoutCreatingMovement() throws Exception {
        var registration = register("smart-stage-snapshot");
        var token = registration.get("accessToken").asText();
        var product = postJson(token, "/api/products", """
            {"sku":"SAMPLE-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses", "{\"name\":\"Snapshot Warehouse\",\"code\":\"SNAP\"}");
        postJson(token, "/api/stock/adjustment", """
            {"productId":"%s","warehouseId":"%s","quantityDelta":40,"rate":10,"notes":"Snapshot preview test"}
            """.formatted(product.get("id").asText(), warehouse.get("id").asText()));
        var movementCount = stockMovements.count();

        var workspace = uploadClassifyAndStage(token, "Snapshot stage", fixture("closing_stock_full_company.xml"));
        assertThat(workspace.get("summary").get("stockSnapshotsStaged").asInt()).isEqualTo(1);
        assertThat(workspace.get("snapshots").get(0).get("currentStock").decimalValue()).isEqualByComparingTo("40");
        assertThat(workspace.get("snapshots").get(0).get("deltaPreview").decimalValue()).isEqualByComparingTo("60");
        assertThat(stockMovements.count()).isEqualTo(movementCount);
    }

    @Test
    void stagesSalesPurchaseVouchersAndSuggestsInvoiceOnlyWithSnapshot() throws Exception {
        var token = register("smart-stage-vouchers").get("accessToken").asText();
        var workspace = uploadClassifyAndStage(token, "Voucher stage",
            fixture("closing_stock_full_company.xml"), fixture("sales.xml"), fixture("purchase.xml"));

        assertThat(workspace.get("summary").get("vouchersStaged").asInt()).isEqualTo(2);
        assertThat(workspace.get("summary").get("voucherItemsStaged").asInt()).isEqualTo(2);
        assertThat(workspace.get("vouchers").findValuesAsText("stockImpactModeSuggestion"))
            .containsOnly("CREATE_INVOICES_ONLY");
    }

    @Test
    void stagesDebtorCreditorPartiesAndMatchesCustomerByGstin() throws Exception {
        var token = register("smart-stage-parties").get("accessToken").asText();
        postJson(token, "/api/customers", """
            {"name":"Existing Customer Alias","gstin":"27ABCDE1234F1Z5","creditLimit":10000}
            """);
        var workspace = uploadClassifyAndStage(token, "Party stage", fixture("debtors_creditors.xml"));

        assertThat(workspace.get("summary").get("partiesStaged").asInt()).isEqualTo(2);
        assertThat(workspace.get("parties").findValuesAsText("matchStatus")).contains("MATCH_EXISTING");
    }

    @Test
    void stagesCashbookAndStockAgeingRows() throws Exception {
        var token = register("smart-stage-comparison").get("accessToken").asText();
        var workspace = uploadClassifyAndStage(token, "Comparison stage", fixture("cashbook.xml"), fixture("stock_ageing.csv"));

        assertThat(workspace.get("summary").get("cashbookEntriesStaged").asInt()).isEqualTo(2);
        assertThat(workspace.get("summary").get("stockAgeingRowsStaged").asInt()).isEqualTo(2);
        assertThat(workspace.get("reviewItems").findValuesAsText("code"))
            .contains("CASHBOOK_UNMATCHED_ENTRY", "STOCK_AGEING_UNMATCHED_PRODUCT");
    }

    @Test
    void exactProductNameAndUnitMatchExistingProduct() throws Exception {
        var token = register("smart-product-match").get("accessToken").asText();
        postJson(token, "/api/products", """
            {"sku":"EXACT-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var workspace = uploadClassifyAndStage(token, "Exact product", fixture("inventory_master.xml"));

        assertThat(workspace.get("products").findValuesAsText("name")).contains("Sample Item A");
        assertThat(workspace.get("products").findValuesAsText("matchStatus")).contains("MATCH_EXISTING");
    }

    @Test
    void similarProductIsMarkedForDuplicateReview() throws Exception {
        var token = register("smart-product-duplicate").get("accessToken").asText();
        postJson(token, "/api/products", """
            {"sku":"MAGGI-EXISTING","name":"MAGGI MASALA 70","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var workspace = uploadClassifyAndStage(token, "Duplicate-like product", fixture("duplicate_products.csv"));

        assertThat(workspace.get("products").findValuesAsText("matchStatus")).contains("POSSIBLE_DUPLICATE_REVIEW");
        assertThat(workspace.get("reviewItems").findValuesAsText("code")).contains("POSSIBLE_DUPLICATE_PRODUCT");
    }

    @Test
    void warehouseMatchingFindsExactAndReviewsUnknownGodown() throws Exception {
        var token = register("smart-warehouse-match").get("accessToken").asText();
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"MAINLOC\"}");
        var workspace = uploadClassifyAndStage(token, "Warehouse matching", fixture("sales.xml"), fixture("purchase.xml"));

        assertThat(workspace.get("matching").get("warehousesMatchedExisting").asInt()).isEqualTo(1);
        assertThat(workspace.get("reviewItems").findValuesAsText("code")).contains("UNKNOWN_WAREHOUSE");
    }

    @Test
    void fileTypeOverrideClearsAndRestagesFile() throws Exception {
        var token = register("smart-type-override").get("accessToken").asText();
        var sessionId = createSession(token, "Override type").get("id").asText();
        var uploaded = upload(token, sessionId, fixture("stock_ageing.csv"));
        var fileId = uploaded.get("files").get(0).get("id").asText();
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var first = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        assertThat(first.get("summary").get("stockAgeingRowsStaged").asInt()).isEqualTo(2);

        var overridden = mapper.readTree(mvc.perform(patch("/api/import-sessions/{sessionId}/files/{fileId}/type", sessionId, fileId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"selectedFileType\":\"INVENTORY_MASTER\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(overridden.get("summary").get("stockAgeingRowsStaged").asInt()).isZero();
        assertThat(overridden.get("summary").get("productsStaged").asInt()).isEqualTo(2);
    }

    @Test
    void removingSessionFileDeletesItAndClearsDerivedWorkspace() throws Exception {
        var token = register("smart-remove-file").get("accessToken").asText();
        var sessionId = createSession(token, "Remove stale file").get("id").asText();
        var uploaded = upload(token, sessionId, fixture("closing_stock_full_company.xml"), fixture("sales.xml"), fixture("purchase.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var staged = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        assertThat(staged.get("summary").get("vouchersStaged").asInt()).isEqualTo(2);

        String purchaseFileId = null;
        for (var file : uploaded.get("files")) {
            if ("purchase.xml".equals(file.get("originalFileName").asText())) {
                purchaseFileId = file.get("id").asText();
                break;
            }
        }
        assertThat(purchaseFileId).isNotNull();

        var afterDelete = mapper.readTree(mvc.perform(delete("/api/import-sessions/{sessionId}/files/{fileId}", sessionId, purchaseFileId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(afterDelete.get("summary").get("vouchersStaged").asInt()).isZero();

        var details = getJson(token, "/api/import-sessions/" + sessionId);
        assertThat(details.get("files").findValuesAsText("originalFileName")).doesNotContain("purchase.xml");
        assertThat(details.get("files")).hasSize(2);
        assertThat(details.get("status").asText()).isEqualTo("CLASSIFIED");

        var restaged = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        assertThat(restaged.get("summary").get("vouchersStaged").asInt()).isEqualTo(1);
    }

    @Test
    void rebuildReplacesCanonicalStagingInsteadOfAppending() throws Exception {
        var token = register("smart-rebuild").get("accessToken").asText();
        var sessionId = createSession(token, "Rebuild").get("id").asText();
        upload(token, sessionId, fixture("inventory_master.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var first = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var second = postJson(token, "/api/import-sessions/" + sessionId + "/rebuild");
        assertThat(second.get("summary").get("productsStaged").asInt())
            .isEqualTo(first.get("summary").get("productsStaged").asInt());
    }

    @Test
    void warehouseReviewResolutionPersistsAcrossRebuild() throws Exception {
        var token = register("smart-review-resolution").get("accessToken").asText();
        var target = postJson(token, "/api/warehouses", "{\"name\":\"Mapped Purchase Godown\",\"code\":\"MAP-PUR\"}");
        var sessionId = createSession(token, "Review resolution").get("id").asText();
        upload(token, sessionId, fixture("purchase.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var first = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        JsonNode reviewItem = null;
        for (var item : first.get("reviewItems")) {
            if ("UNKNOWN_WAREHOUSE".equals(item.get("code").asText())) {
                reviewItem = item;
                break;
            }
        }
        assertThat(reviewItem).isNotNull();

        var resolved = postJson(token,
            "/api/import-sessions/" + sessionId + "/review-items/" + reviewItem.get("id").asText() + "/resolve",
            "{\"action\":\"MAP_WAREHOUSE\",\"targetId\":\"" + target.get("id").asText() + "\"}");

        assertThat(resolved.get("matching").get("warehousesMatchedExisting").asInt()).isEqualTo(1);
        assertThat(resolved.get("reviewItems").findValuesAsText("code")).doesNotContain("UNKNOWN_WAREHOUSE");
        var rebuilt = postJson(token, "/api/import-sessions/" + sessionId + "/rebuild");
        assertThat(rebuilt.get("matching").get("warehousesMatchedExisting").asInt()).isEqualTo(1);
    }

    @Test
    void invalidXmlControlCharactersAreSanitizedForClassificationAndStaging() throws Exception {
        var token = register("smart-invalid-xml").get("accessToken").asText();
        var sessionId = createSession(token, "Invalid XML").get("id").asText();
        upload(token, sessionId, fixture("invalid_control_chars.xml"));
        var classified = postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        assertThat(classified.get("files").get(0).get("detectedFileType").asText()).isEqualTo("INVENTORY_MASTER");

        var staged = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        assertThat(staged.get("summary").get("productsStaged").asInt()).isEqualTo(1);
        assertThat(staged.get("products").get(0).get("name").asText()).isEqualTo("Control Character Item");
    }

    @Test
    void stagingAndReviewRemainTenantScopedAndPortalDenied() throws Exception {
        var registrationA = register("smart-stage-tenant-a");
        var tokenA = registrationA.get("accessToken").asText();
        var sessionId = createSession(tokenA, "Private staging").get("id").asText();
        upload(tokenA, sessionId, fixture("sales.xml"));
        postJson(tokenA, "/api/import-sessions/" + sessionId + "/classify");
        postJson(tokenA, "/api/import-sessions/" + sessionId + "/stage");

        var tokenB = register("smart-stage-tenant-b").get("accessToken").asText();
        mvc.perform(get("/api/import-sessions/{id}/staging", sessionId).header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isNotFound());

        var customerToken = tokenForRole(UUID.fromString(registrationA.get("tenantId").asText()), DomainEnums.Role.CUSTOMER_USER);
        mvc.perform(get("/api/import-sessions/{id}/review-items", sessionId).header("Authorization", "Bearer " + customerToken))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/import-sessions/{id}/stage", sessionId).header("Authorization", "Bearer " + customerToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void dryRunSimulatesStagedSessionWithoutWritingFinalBusinessData() throws Exception {
        var registration = register("smart-dry-run-safe");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var sessionId = createSession(token, "Dry run safety").get("id").asText();
        upload(token, sessionId, fixture("sales.xml"), fixture("purchase.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var productCount = products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).size();
        var salesCount = salesInvoices.findByTenantId(tenantId).size();
        var purchaseCount = purchaseInvoices.findByTenantId(tenantId).size();
        var movementCount = stockMovements.count();

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");

        assertThat(dryRun.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(dryRun.get("summary").get("dryRunOnly").asBoolean()).isTrue();
        assertThat(dryRun.get("summary").get("finalBusinessWrites").asInt()).isZero();
        var persisted = getJson(token, "/api/import-sessions/" + sessionId + "/dry-run");
        assertThat(persisted.get("id")).isEqualTo(dryRun.get("id"));
        assertThat(persisted.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(persisted.get("summary")).isEqualTo(dryRun.get("summary"));
        assertThat(dryRuns.findByTenantIdAndImportSessionIdAndId(
            tenantId, UUID.fromString(sessionId), UUID.fromString(dryRun.get("id").asText()))
            .orElseThrow().inputFingerprint).matches("[0-9a-f]{64}");
        assertThat(dryRunItems.findByTenantIdAndDryRunIdOrderByCreatedAtAsc(
            tenantId, UUID.fromString(dryRun.get("id").asText()))).isNotEmpty();
        assertThat(products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)).hasSize(productCount);
        assertThat(salesInvoices.findByTenantId(tenantId)).hasSize(salesCount);
        assertThat(purchaseInvoices.findByTenantId(tenantId)).hasSize(purchaseCount);
        assertThat(stockMovements.count()).isEqualTo(movementCount);
    }

    @Test
    void snapshotDryRunUsesDateAwareDeltaAndNoChangeForSameQuantity() throws Exception {
        var registration = register("smart-dry-run-snapshot-same");
        var token = registration.get("accessToken").asText();
        var product = postJson(token, "/api/products", """
            {"sku":"SNAP-100","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"MAIN-100\"}");
        saveMovementAt(UUID.fromString(registration.get("tenantId").asText()), product, warehouse, "100", java.time.Instant.parse("2026-06-29T12:00:00Z"));
        var movementCount = stockMovements.count();
        var sessionId = createSession(token, "Same snapshot").get("id").asText();
        upload(token, sessionId, fixture("closing_stock_full_company.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{}");
        var itemPage = getJson(token, "/api/import-sessions/" + sessionId + "/dry-run/items?itemType=STOCK_SNAPSHOT");

        assertThat(dryRun.get("summary").get("snapshotNoChangeRows").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("snapshotAdjustmentsPreviewed").asInt()).isZero();
        assertThat(itemPage.get("content").get(0).get("action").asText()).isEqualTo("NO_CHANGE");
        assertThat(itemPage.get("content").get(0).get("preview").get("deltaAtSnapshotDate").decimalValue()).isEqualByComparingTo("0");
        assertThat(stockMovements.count()).isEqualTo(movementCount);
    }

    @Test
    void changedSnapshotDryRunPreviewsOnlyRequiredDelta() throws Exception {
        var registration = register("smart-dry-run-snapshot-change");
        var token = registration.get("accessToken").asText();
        var product = postJson(token, "/api/products", """
            {"sku":"SNAP-120","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"MAIN-120\"}");
        saveMovementAt(UUID.fromString(registration.get("tenantId").asText()), product, warehouse, "100", java.time.Instant.parse("2026-06-29T12:00:00Z"));
        var sessionId = createSession(token, "Changed snapshot").get("id").asText();
        upload(token, sessionId, fixture("changed_closing_stock_snapshot_120.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{}");
        var itemPage = getJson(token, "/api/import-sessions/" + sessionId + "/dry-run/items?itemType=STOCK_SNAPSHOT");

        assertThat(dryRun.get("summary").get("snapshotAdjustmentsPreviewed").asInt()).isEqualTo(1);
        assertThat(itemPage.get("content").get(0).get("preview").get("deltaAtSnapshotDate").decimalValue()).isEqualByComparingTo("20");
        assertThat(itemPage.get("content").get(0).get("preview").get("projectedCurrentStockAfterApplyingLaterTransactions").decimalValue()).isEqualByComparingTo("120");
    }

    @Test
    void hybridDryRunSkipsBeforeSnapshotAndAppliesAfterSnapshotVoucherStock() throws Exception {
        var token = register("smart-dry-run-hybrid").get("accessToken").asText();
        var product = postJson(token, "/api/products", """
            {"sku":"HYB-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"HYB-MAIN\"}");
        postJson(token, "/api/stock/adjustment", """
            {"productId":"%s","warehouseId":"%s","quantityDelta":100,"rate":10,"notes":"Hybrid dry-run stock"}
            """.formatted(product.get("id").asText(), warehouse.get("id").asText()));
        var sessionId = createSession(token, "Hybrid dry run").get("id").asText();
        upload(token, sessionId, fixture("closing_stock_full_company.xml"), fixture("sales.xml"), fixture("sales_after_snapshot.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {"strategy":"HYBRID_RECONCILIATION","stockImpactMode":"APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE"}
            """);

        assertThat(dryRun.get("summary").get("salesInvoicesPreviewed").asInt()).isEqualTo(2);
        assertThat(dryRun.get("summary").get("stockMovementsSkippedBeforeSnapshotDate").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(dryRun.get("summary").get("stockMovementsPreviewed").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void transactionHistoryDefaultsToInvoiceOnlyUntilAnApplicableSnapshotExists() throws Exception {
        var token = register("smart-dry-run-history").get("accessToken").asText();
        var product = postJson(token, "/api/products", """
            {"sku":"HIST-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var salesWarehouse = postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"HIST-SALE\"}");
        postJson(token, "/api/warehouses", "{\"name\":\"Main Godown\",\"code\":\"HIST-PUR\"}");
        postJson(token, "/api/stock/adjustment", """
            {"productId":"%s","warehouseId":"%s","quantityDelta":20,"rate":10,"notes":"Transaction history stock"}
            """.formatted(product.get("id").asText(), salesWarehouse.get("id").asText()));
        var sessionId = createSession(token, "Transaction dry run").get("id").asText();
        upload(token, sessionId, fixture("purchase.xml"), fixture("sales.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{}");

        assertThat(dryRun.get("strategy").asText()).isEqualTo("TRANSACTION_HISTORY");
        assertThat(dryRun.get("summary").get("purchaseInvoicesPreviewed").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("salesInvoicesPreviewed").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("stockMovementsPreviewed").asInt()).isZero();
        assertThat(dryRun.get("summary").get("stockMovementsSkippedDueToInvoiceOnly").asInt()).isEqualTo(2);
    }

    @Test
    void dryRunRequiresReconciliationWhenExistingInvoiceHasNoMatchingLines() throws Exception {
        var registration = register("smart-dry-run-voucher-duplicate");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/products", """
            {"sku":"DUP-VOUCHER-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"DUP-VOUCHER-WH\"}");
        var customer = postJson(token, "/api/customers", "{\"name\":\"Sample Customer\",\"creditLimit\":0}");
        var existing = new SalesInvoice();
        existing.tenantId = tenantId;
        existing.customerId = UUID.fromString(customer.get("id").asText());
        existing.invoiceNumber = "S-001";
        existing.invoiceDate = java.time.LocalDate.of(2026, 6, 15);
        existing.totalAmount = java.math.BigDecimal.valueOf(30);
        salesInvoices.save(existing);
        var sessionId = createSession(token, "Duplicate voucher dry run").get("id").asText();
        upload(token, sessionId, fixture("sales.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");

        assertThat(dryRun.get("summary").get("duplicateVouchersSkipped").asInt()).isZero();
        assertThat(dryRun.get("summary").get("salesInvoicesPreviewed").asInt()).isZero();
        assertThat(dryRun.get("summary").get("blockingErrors").asInt()).isPositive();
        mvc.perform(post("/api/import-sessions/" + sessionId + "/commit")
            .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .content(commitBody(dryRun.get("id").asText()))).andExpect(status().isBadRequest());
    }

    @Test
    void rerunningDryRunReplacesPreviousRunAndItems() throws Exception {
        var registration = register("smart-dry-run-replace");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var sessionId = createSession(token, "Replace dry run").get("id").asText();
        upload(token, sessionId, fixture("inventory_master.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        var first = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        var second = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");

        assertThat(second.get("id").asText()).isNotEqualTo(first.get("id").asText());
        assertThat(second.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(dryRuns.findById(UUID.fromString(first.get("id").asText()))).isEmpty();
        assertThat(dryRunItems.findByTenantIdAndDryRunIdOrderByCreatedAtAsc(
            tenantId, UUID.fromString(first.get("id").asText()))).isEmpty();
        var persisted = getJson(token, "/api/import-sessions/" + sessionId + "/dry-run");
        assertThat(persisted.get("id")).isEqualTo(second.get("id"));
        assertThat(persisted.get("summary")).isEqualTo(second.get("summary"));
        assertThat(dryRuns.findFirstByTenantIdAndImportSessionIdOrderByStartedAtDesc(tenantId, UUID.fromString(sessionId))).isPresent();
        var itemPage = getJson(token, "/api/import-sessions/" + sessionId + "/dry-run/items?size=100");
        assertThat(itemPage.get("content").size()).isGreaterThan(0);
        assertThat(itemPage.get("content").findValuesAsText("id")).doesNotHaveDuplicates();
    }

    @Test
    void dryRunIsTenantScopedAndCustomerUserIsDenied() throws Exception {
        var registrationA = register("smart-dry-run-tenant-a");
        var tokenA = registrationA.get("accessToken").asText();
        var sessionId = createSession(tokenA, "Tenant A dry run").get("id").asText();
        upload(tokenA, sessionId, fixture("inventory_master.xml"));
        postJson(tokenA, "/api/import-sessions/" + sessionId + "/classify");
        postJson(tokenA, "/api/import-sessions/" + sessionId + "/stage");
        postJson(tokenA, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");

        var tokenB = register("smart-dry-run-tenant-b").get("accessToken").asText();
        mvc.perform(get("/api/import-sessions/{id}/dry-run", sessionId).header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isNotFound());
        var customerToken = tokenForRole(UUID.fromString(registrationA.get("tenantId").asText()), DomainEnums.Role.CUSTOMER_USER);
        mvc.perform(post("/api/import-sessions/{id}/dry-run", sessionId)
                .header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void negativeSnapshotDryRunAppliesBlockAndSkipPoliciesWithoutChangingStock() throws Exception {
        var token = register("smart-dry-run-negative").get("accessToken").asText();
        postJson(token, "/api/products", """
            {"sku":"NEG-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"NEG-MAIN\"}");
        var sessionId = createSession(token, "Negative snapshot dry run").get("id").asText();
        upload(token, sessionId, fixture("negative_closing_stock.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var movementCount = stockMovements.count();

        var blocked = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"negativeStockPolicy\":\"BLOCK\"}");
        assertThat(blocked.get("summary").get("negativeStockRowsBlocked").asInt()).isEqualTo(1);
        assertThat(blocked.get("summary").get("blockingErrors").asInt()).isGreaterThanOrEqualTo(1);

        var skipped = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"negativeStockPolicy\":\"SKIP_STOCK_MOVEMENT\"}");
        assertThat(skipped.get("summary").get("negativeStockRowsSkipped").asInt()).isEqualTo(1);
        assertThat(stockMovements.count()).isEqualTo(movementCount);
    }

    @Test
    void dryRunReportsZeroCostItemsAndSkippedLedgerLinesAsWarnings() throws Exception {
        var token = register("smart-dry-run-rates").get("accessToken").asText();
        var sessionId = createSession(token, "Rate dry run").get("id").asText();
        upload(token, sessionId, fixture("zero_cost_purchase.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{}");

        assertThat(dryRun.get("summary").get("zeroCostItems").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("ledgerLinesSkipped").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("invalidRateRows").asInt()).isZero();
        assertThat(dryRun.get("summary").get("warnings").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void dryRunReportsUnmatchedCashbookAndAgeingRowsForReview() throws Exception {
        var token = register("smart-dry-run-comparison").get("accessToken").asText();
        var sessionId = createSession(token, "Comparison dry run").get("id").asText();
        upload(token, sessionId, fixture("cashbook.xml"), fixture("stock_ageing.csv"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");

        assertThat(dryRun.get("summary").get("cashbookRowsUnmatched").asInt()).isEqualTo(2);
        assertThat(dryRun.get("summary").get("ageingRowsUnmatched").asInt()).isEqualTo(2);
        assertThat(dryRun.get("summary").get("reviewRequiredCount").asInt()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void safeCommitRequiresCompletedFreshDryRunAndExactConfirmation() throws Exception {
        var token = register("smart-commit-preflight").get("accessToken").asText();
        var sessionId = createSession(token, "Commit preflight").get("id").asText();

        mvc.perform(post("/api/import-sessions/{id}/commit", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRunId\":\"" + UUID.randomUUID() + "\",\"confirmation\":\"COMMIT IMPORT PLAN\"}"))
            .andExpect(status().isBadRequest());

        upload(token, sessionId, fixture("inventory_master.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");

        mvc.perform(post("/api/import-sessions/{id}/commit", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRunId\":\"" + dryRun.get("id").asText() + "\",\"confirmation\":\"commit\"}"))
            .andExpect(status().isBadRequest());

        var tenantId = UUID.fromString(getJson(token, "/api/auth/me").get("tenantId").asText());
        var file = importSessionFiles.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(tenantId, UUID.fromString(sessionId)).getFirst();
        file.detectionReason = file.detectionReason + " changed";
        importSessionFiles.save(file);
        mvc.perform(post("/api/import-sessions/{id}/commit", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(commitBody(dryRun.get("id").asText())))
            .andExpect(status().isConflict());
    }

    @Test
    void safeCommitCreatesMasterDataAndIsIdempotent() throws Exception {
        var registration = register("smart-commit-master");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var sessionId = createSession(token, "Master foundation").get("id").asText();
        upload(token, sessionId, fixture("inventory_master.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");

        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));
        assertThat(committed.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(committed.get("summary").get("productsCreated").asInt()).isEqualTo(2);
        assertThat(products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)).hasSize(2);
        var effectCount = smartEffects.findByTenantIdAndCommitIdOrderByCreatedAtAsc(
            tenantId, UUID.fromString(committed.get("id").asText())).size();
        assertThat(effectCount).isGreaterThan(0);

        var retry = postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));
        assertThat(retry.get("idempotentRetry").asBoolean()).isTrue();
        assertThat(products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)).hasSize(2);
        assertThat(smartEffects.findByTenantIdAndCommitIdOrderByCreatedAtAsc(
            tenantId, UUID.fromString(committed.get("id").asText()))).hasSize(effectCount);
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(log -> log.action))
            .contains("SMART_IMPORT_COMMIT_STARTED", "SMART_IMPORT_COMMITTED", "SMART_IMPORT_EFFECT_CREATED");
    }

    @Test
    void snapshotCommitUsesDeltaAndRepeatedSnapshotCreatesNoMovement() throws Exception {
        var registration = register("smart-commit-snapshot");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var product = postJson(token, "/api/products", """
            {"sku":"COMMIT-SNAP","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"COMMIT-MAIN\"}");
        saveMovementAt(tenantId, product, warehouse, "40", java.time.Instant.parse("2026-06-29T12:00:00Z"));

        var firstSession = createSession(token, "First snapshot commit").get("id").asText();
        upload(token, firstSession, fixture("closing_stock_full_company.xml"));
        postJson(token, "/api/import-sessions/" + firstSession + "/classify");
        postJson(token, "/api/import-sessions/" + firstSession + "/stage");
        var firstDryRun = postJson(token, "/api/import-sessions/" + firstSession + "/dry-run", "{}");
        var firstCommit = postJson(token, "/api/import-sessions/" + firstSession + "/commit", commitBody(firstDryRun.get("id").asText()));
        assertThat(firstCommit.get("summary").get("snapshotMovementsCreated").asInt()).isEqualTo(1);
        assertThat(firstCommit.get("summary").get("snapshotPositiveAdjustments").asInt()).isEqualTo(1);
        assertThat(stockMovements.currentStock(tenantId, UUID.fromString(product.get("id").asText()), UUID.fromString(warehouse.get("id").asText())))
            .isEqualByComparingTo("100");
        var movementCount = stockMovements.count();

        var secondSession = createSession(token, "Repeated snapshot commit").get("id").asText();
        upload(token, secondSession, fixture("closing_stock_full_company.xml"));
        postJson(token, "/api/import-sessions/" + secondSession + "/classify");
        postJson(token, "/api/import-sessions/" + secondSession + "/stage");
        var secondDryRun = postJson(token, "/api/import-sessions/" + secondSession + "/dry-run", "{}");
        var secondCommit = postJson(token, "/api/import-sessions/" + secondSession + "/commit", commitBody(secondDryRun.get("id").asText()));
        assertThat(secondCommit.get("summary").get("snapshotRowsNoChange").asInt()).isEqualTo(1);
        assertThat(secondCommit.get("summary").get("snapshotMovementsCreated").asInt()).isZero();
        assertThat(stockMovements.count()).isEqualTo(movementCount);
    }

    @Test
    void phase4B1CommitsSalesAndPurchaseInvoicesWithoutStockMovementsAndIsIdempotent() throws Exception {
        var registration = register("smart-commit-future-phase");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/products", """
            {"sku":"VOUCHER-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"V-SALE\"}");
        postJson(token, "/api/warehouses", "{\"name\":\"Main Godown\",\"code\":\"V-PUR\"}");
        var sessionId = createSession(token, "Future phase rows").get("id").asText();
        upload(token, sessionId, fixture("sales.xml"), fixture("purchase.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        var movementCount = stockMovements.count();

        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));
        assertThat(committed.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(committed.get("summary").get("salesInvoicesCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("salesInvoiceItemsCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("purchaseInvoicesCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("purchaseInvoiceItemsCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("voucherStockMovementsCreated").asInt()).isZero();
        assertThat(committed.get("summary").get("stockMovementsSkippedDueToInvoiceOnly").asInt()).isEqualTo(2);
        assertThat(committed.get("summary").get("partiesCreated").asInt()).isEqualTo(2);
        assertThat(salesInvoices.findByTenantId(tenantId)).hasSize(1);
        assertThat(purchaseInvoices.findByTenantId(tenantId)).hasSize(1);
        assertThat(salesInvoiceItems.findByTenantId(tenantId)).hasSize(1);
        assertThat(purchaseInvoiceItems.findByTenantId(tenantId)).hasSize(1);
        assertThat(stockMovements.count()).isEqualTo(movementCount);

        var retry = postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));
        assertThat(retry.get("idempotentRetry").asBoolean()).isTrue();
        assertThat(salesInvoices.findByTenantId(tenantId)).hasSize(1);
        assertThat(purchaseInvoices.findByTenantId(tenantId)).hasSize(1);
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(log -> log.action))
            .contains("SMART_IMPORT_SALES_INVOICES_COMMITTED", "SMART_IMPORT_PURCHASE_INVOICES_COMMITTED");
    }

    @Test
    void repeatedInvoiceOnlyVoucherImportSkipsExactDuplicates() throws Exception {
        var registration = register("smart-commit-voucher-duplicate");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/products", """
            {"sku":"VOUCHER-DUP","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"V-DUP-WH\"}");
        postJson(token, "/api/warehouses", "{\"name\":\"Main Godown\",\"code\":\"V-DUP-PUR\"}");

        var firstSession = createSession(token, "First voucher history").get("id").asText();
        upload(token, firstSession, fixture("sales.xml"), fixture("purchase.xml"));
        postJson(token, "/api/import-sessions/" + firstSession + "/classify");
        postJson(token, "/api/import-sessions/" + firstSession + "/stage");
        var firstDryRun = postJson(token, "/api/import-sessions/" + firstSession + "/dry-run",
            "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        postJson(token, "/api/import-sessions/" + firstSession + "/commit", commitBody(firstDryRun.get("id").asText()));

        var secondSession = createSession(token, "Repeated voucher history").get("id").asText();
        upload(token, secondSession, fixture("sales.xml"), fixture("purchase.xml"));
        postJson(token, "/api/import-sessions/" + secondSession + "/classify");
        postJson(token, "/api/import-sessions/" + secondSession + "/stage");
        var secondDryRun = postJson(token, "/api/import-sessions/" + secondSession + "/dry-run",
            "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        var secondCommit = postJson(token, "/api/import-sessions/" + secondSession + "/commit",
            commitBody(secondDryRun.get("id").asText()));

        assertThat(secondCommit.get("summary").get("duplicateSalesVouchersSkipped").asInt()).isEqualTo(1);
        assertThat(secondCommit.get("summary").get("duplicatePurchaseVouchersSkipped").asInt()).isEqualTo(1);
        assertThat(secondCommit.get("summary").get("salesInvoicesCreated").asInt()).isZero();
        assertThat(secondCommit.get("summary").get("purchaseInvoicesCreated").asInt()).isZero();
        assertThat(salesInvoices.findByTenantId(tenantId)).hasSize(1);
        assertThat(salesInvoiceItems.findByTenantId(tenantId)).hasSize(1);
        assertThat(purchaseInvoices.findByTenantId(tenantId)).hasSize(1);
        assertThat(purchaseInvoiceItems.findByTenantId(tenantId)).hasSize(1);
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(log -> log.action))
            .contains("SMART_IMPORT_DUPLICATE_VOUCHERS_SKIPPED");
    }

    @Test
    void phase4B2ACommitsCreditAndDebitNotesAsFinancialAdjustmentsWithoutStockMovements() throws Exception {
        var registration = register("smart-financial-notes");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/products", """
            {"sku":"NOTE-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"NOTE-SALE\"}");
        postJson(token, "/api/warehouses", "{\"name\":\"Main Godown\",\"code\":\"NOTE-PURCHASE\"}");
        var sessionId = createSession(token, "Credit and debit notes").get("id").asText();
        upload(token, sessionId, fixture("credit_note.xml"), fixture("debit_note.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var staging = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        assertThat(staging.get("summary").get("creditNotesStaged").asInt()).isEqualTo(1);
        assertThat(staging.get("summary").get("debitNotesStaged").asInt()).isEqualTo(1);
        assertThat(staging.get("summary").get("ledgerAdjustmentLinesStaged").asInt()).isEqualTo(8);
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        assertThat(dryRun.get("summary").get("creditNotesPreviewed").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("debitNotesPreviewed").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("taxLinesCaptured").asInt()).isEqualTo(3);
        assertThat(dryRun.get("summary").get("discountLinesCaptured").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("freightLinesCaptured").asInt()).isEqualTo(2);
        assertThat(dryRun.get("summary").get("roundOffLinesCaptured").asInt()).isEqualTo(2);
        var movementCount = stockMovements.count();

        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));

        assertThat(committed.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(committed.get("summary").get("creditNotesCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("creditNoteItemsCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("debitNotesCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("debitNoteItemsCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("stockMovementsCreatedFromReturns").asInt()).isZero();
        assertThat(committed.get("summary").get("returnStockMovementsDeferred").asInt()).isEqualTo(2);
        assertThat(financialAdjustments.findByTenantIdOrderByVoucherDateDesc(tenantId)).hasSize(2)
            .extracting(row -> row.adjustmentType)
            .containsExactlyInAnyOrder(DomainEnums.FinancialAdjustmentType.CREDIT_NOTE, DomainEnums.FinancialAdjustmentType.DEBIT_NOTE);
        assertThat(financialAdjustmentItems.findByTenantId(tenantId)).hasSize(2);
        assertThat(stockMovements.count()).isEqualTo(movementCount);
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(log -> log.action))
            .contains("SMART_IMPORT_CREDIT_NOTES_COMMITTED", "SMART_IMPORT_DEBIT_NOTES_COMMITTED",
                "SMART_IMPORT_LEDGER_ADJUSTMENTS_CAPTURED");

        var retry = postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));
        assertThat(retry.get("idempotentRetry").asBoolean()).isTrue();
        assertThat(financialAdjustments.findByTenantIdOrderByVoucherDateDesc(tenantId)).hasSize(2);
        assertThat(financialAdjustmentItems.findByTenantId(tenantId)).hasSize(2);
    }

    @Test
    void creditNoteIdentityAndCommitRemainTenantIsolated() throws Exception {
        var first = register("smart-note-tenant-a");
        var second = register("smart-note-tenant-b");

        for (var registration : java.util.List.of(first, second)) {
            var token = registration.get("accessToken").asText();
            postJson(token, "/api/products", """
                {"sku":"TENANT-NOTE","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
                """);
            postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"TENANT-NOTE-WH\"}");
            var sessionId = createSession(token, "Tenant-isolated credit note").get("id").asText();
            upload(token, sessionId, fixture("credit_note.xml"));
            postJson(token, "/api/import-sessions/" + sessionId + "/classify");
            postJson(token, "/api/import-sessions/" + sessionId + "/stage");
            var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
                "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
            var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
                commitBody(dryRun.get("id").asText()));
            assertThat(committed.get("summary").get("creditNotesCreated").asInt()).isEqualTo(1);
            assertThat(committed.get("summary").get("duplicateCreditNotesSkipped").asInt()).isZero();
        }

        var firstTenant = UUID.fromString(first.get("tenantId").asText());
        var secondTenant = UUID.fromString(second.get("tenantId").asText());
        assertThat(financialAdjustments.findByTenantIdOrderByVoucherDateDesc(firstTenant)).hasSize(1);
        assertThat(financialAdjustments.findByTenantIdOrderByVoucherDateDesc(secondTenant)).hasSize(1);
        assertThat(financialAdjustments.findByTenantIdOrderByVoucherDateDesc(firstTenant).getFirst().id)
            .isNotEqualTo(financialAdjustments.findByTenantIdOrderByVoucherDateDesc(secondTenant).getFirst().id);
    }

    @Test
    void invoiceOnlyCommitStoresTaxDiscountFreightAndRoundOffOnSalesAndPurchaseInvoices() throws Exception {
        var registration = register("smart-invoice-financial-components");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/products", """
            {"sku":"FIN-COMP","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"FIN-SALE\"}");
        postJson(token, "/api/warehouses", "{\"name\":\"Main Godown\",\"code\":\"FIN-PURCHASE\"}");
        var sessionId = createSession(token, "Invoice financial components").get("id").asText();
        upload(token, sessionId, fixture("sales_financial_components.xml"), fixture("purchase_financial_components.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        var movementCount = stockMovements.count();
        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));

        var sale = salesInvoices.findByTenantIdAndInvoiceNumberIgnoreCase(tenantId, "S-FIN-001").orElseThrow();
        assertThat(sale.taxAmount).isEqualByComparingTo("5.40");
        assertThat(sale.discountAmount).isEqualByComparingTo("1.00");
        assertThat(sale.freightAmount).isEqualByComparingTo("3.50");
        assertThat(sale.roundOffAmount).isEqualByComparingTo("0.30");
        assertThat(sale.totalAmount).isEqualByComparingTo("38.20");
        var purchase = purchaseInvoices.findByTenantIdAndInvoiceNumberIgnoreCase(tenantId, "P-FIN-001").orElseThrow();
        assertThat(purchase.taxAmount).isEqualByComparingTo("5.40");
        assertThat(purchase.discountAmount).isEqualByComparingTo("0.50");
        assertThat(purchase.freightAmount).isEqualByComparingTo("3.50");
        assertThat(purchase.roundOffAmount).isEqualByComparingTo("0.10");
        assertThat(purchase.totalAmount).isEqualByComparingTo("38.50");
        assertThat(committed.get("summary").get("taxLinesCaptured").asInt()).isEqualTo(2);
        assertThat(committed.get("summary").get("discountLinesCaptured").asInt()).isEqualTo(2);
        assertThat(committed.get("summary").get("freightLinesCaptured").asInt()).isEqualTo(2);
        assertThat(committed.get("summary").get("roundOffLinesCaptured").asInt()).isEqualTo(2);
        assertThat(stockMovements.count()).isEqualTo(movementCount);
    }

    @Test
    void repeatedCreditAndDebitNotesAreSkippedWithoutDuplicateItems() throws Exception {
        var registration = register("smart-note-duplicates");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/products", """
            {"sku":"NOTE-DUP","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"NOTE-DUP-S\"}");
        postJson(token, "/api/warehouses", "{\"name\":\"Main Godown\",\"code\":\"NOTE-DUP-P\"}");
        for (var index = 0; index < 2; index++) {
            var sessionId = createSession(token, "Note duplicate pass " + index).get("id").asText();
            upload(token, sessionId, fixture("credit_note.xml"), fixture("debit_note.xml"));
            postJson(token, "/api/import-sessions/" + sessionId + "/classify");
            postJson(token, "/api/import-sessions/" + sessionId + "/stage");
            var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
                "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
            var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));
            if (index == 1) {
                assertThat(committed.get("summary").get("duplicateCreditNotesSkipped").asInt()).isEqualTo(1);
                assertThat(committed.get("summary").get("duplicateDebitNotesSkipped").asInt()).isEqualTo(1);
                assertThat(committed.get("summary").get("creditNotesCreated").asInt()).isZero();
                assertThat(committed.get("summary").get("debitNotesCreated").asInt()).isZero();
            }
        }
        assertThat(financialAdjustments.findByTenantIdOrderByVoucherDateDesc(tenantId)).hasSize(2);
        assertThat(financialAdjustmentItems.findByTenantId(tenantId)).hasSize(2);
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(log -> log.action))
            .contains("SMART_IMPORT_DUPLICATE_NOTES_SKIPPED");
    }

    @Test
    void smartImportRejectsUnrestrictedHistoricalStockMovementMode() throws Exception {
        var registration = register("smart-commit-unsupported-mode");
        var token = registration.get("accessToken").asText();
        postJson(token, "/api/products", """
            {"sku":"VOUCHER-MODE","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"V-MODE-WH\"}");
        var sessionId = createSession(token, "Unsupported stock mode").get("id").asText();
        upload(token, sessionId, fixture("sales.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        mvc.perform(post("/api/import-sessions/{id}/dry-run", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategy\":\"TRANSACTION_HISTORY\",\"stockImpactMode\":\"CREATE_INVOICES_AND_STOCK_MOVEMENTS\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void postSnapshotModeCommitsOnlyStrictlyLaterSalesAndPurchases() throws Exception {
        var registration = register("smart-post-snapshot-commit");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var product = postJson(token, "/api/products", """
            {"sku":"POST-SNAP-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"POST-SNAP-WH\"}");
        saveMovementAt(tenantId, product, warehouse, "100", java.time.Instant.parse("2026-06-29T12:00:00Z"));

        var sessionId = createSession(token, "Post snapshot commit").get("id").asText();
        upload(token, sessionId,
            fixture("closing_stock_full_company.xml"),
            fixture("sales.xml"),
            fixture("sales_on_snapshot.xml"),
            fixture("sales_after_snapshot.xml"),
            fixture("purchase_after_snapshot.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {"strategy":"HYBRID_RECONCILIATION","stockImpactMode":"APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE","negativeStockPolicy":"BLOCK"}
            """);

        assertThat(dryRun.get("summary").get("salesStockMovementsPlanned").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("purchaseStockMovementsPlanned").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("voucherItemsCoveredBySnapshot").asInt()).isEqualTo(2);
        assertThat(dryRun.get("summary").get("sameDateVoucherItemsDeferred").asInt()).isEqualTo(1);

        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(dryRun.get("id").asText()));

        assertThat(committed.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(committed.get("summary").get("salesStockMovementsCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("purchaseStockMovementsCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("voucherItemsCoveredBySnapshot").asInt()).isEqualTo(2);
        assertThat(stockMovements.currentStock(
            tenantId, UUID.fromString(product.get("id").asText()), UUID.fromString(warehouse.get("id").asText())))
            .isEqualByComparingTo("108");
        var postSnapshotSale = salesInvoices
            .findByTenantIdAndInvoiceNumberIgnoreCase(tenantId, "S-POST-001")
            .orElseThrow();
        var postSnapshotSaleItem = salesInvoiceItems
            .findByTenantIdAndSalesInvoiceId(tenantId, postSnapshotSale.id)
            .getFirst();
        assertThat(stockMovements.findByTenantIdAndReferenceTypeAndReferenceId(
            tenantId, "SALES_INVOICE_ITEM", postSnapshotSaleItem.id)).singleElement()
            .satisfies(movement -> {
                assertThat(movement.movementType).isEqualTo(DomainEnums.MovementType.SALE);
                assertThat(movement.sourceFingerprint).isNotBlank();
                assertThat(movement.snapshotBaselineId).isNotNull();
                assertThat(movement.decisionReason).isEqualTo("AFTER_SNAPSHOT");
            });
    }

    @Test
    void confirmedPhysicalReturnsAfterSnapshotCreateReturnInAndReturnOut() throws Exception {
        var registration = register("smart-physical-returns");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var product = postJson(token, "/api/products", """
            {"sku":"RETURN-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses",
            "{\"name\":\"Main Location\",\"code\":\"RETURN-WH\"}");
        postJson(token, "/api/customers", "{\"name\":\"Sample Customer\",\"creditLimit\":10000}");
        postJson(token, "/api/suppliers", "{\"name\":\"Sample Supplier\",\"creditDays\":30}");
        saveMovementAt(tenantId, product, warehouse, "100",
            java.time.Instant.parse("2026-06-29T12:00:00Z"));

        var sessionId = createSession(token, "Physical returns").get("id").asText();
        upload(token, sessionId, fixture("closing_stock_full_company.xml"),
            fixture("credit_return_after_snapshot.xml"), fixture("debit_return_after_snapshot.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var staged = postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        assertThat(staged.get("vouchers").findValuesAsText("returnClassification"))
            .contains("PHYSICAL_SALES_RETURN", "PHYSICAL_PURCHASE_RETURN");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {
              "strategy":"HYBRID_RECONCILIATION",
              "stockImpactMode":"CREATE_INVOICES_ONLY",
              "returnStockImpactMode":"APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE",
              "negativeStockPolicy":"BLOCK"
            }
            """);
        assertThat(dryRun.get("summary").get("returnInMovementsPlanned").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("returnOutMovementsPlanned").asInt()).isEqualTo(1);

        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(dryRun.get("id").asText()));

        assertThat(committed.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(committed.get("summary").get("returnInMovementsCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("returnOutMovementsCreated").asInt()).isEqualTo(1);
        assertThat(stockMovements.findByTenantIdAndProductIdOrderByMovementDateDesc(
            tenantId, UUID.fromString(product.get("id").asText())).stream()
            .filter(movement -> movement.movementType == DomainEnums.MovementType.RETURN_IN
                || movement.movementType == DomainEnums.MovementType.RETURN_OUT))
            .extracting(movement -> movement.movementType)
            .containsExactlyInAnyOrder(DomainEnums.MovementType.RETURN_IN, DomainEnums.MovementType.RETURN_OUT);
        assertThat(stockMovements.currentStock(
            tenantId, UUID.fromString(product.get("id").asText()), UUID.fromString(warehouse.get("id").asText())))
            .isEqualByComparingTo("99");

        var repeatedSessionId = createSession(token, "Repeated physical returns").get("id").asText();
        upload(token, repeatedSessionId, fixture("closing_stock_full_company.xml"),
            fixture("credit_return_after_snapshot.xml"), fixture("debit_return_after_snapshot.xml"));
        postJson(token, "/api/import-sessions/" + repeatedSessionId + "/classify");
        postJson(token, "/api/import-sessions/" + repeatedSessionId + "/stage");
        var repeatedDryRun = postJson(token, "/api/import-sessions/" + repeatedSessionId + "/dry-run", """
            {
              "strategy":"HYBRID_RECONCILIATION",
              "stockImpactMode":"CREATE_INVOICES_ONLY",
              "returnStockImpactMode":"APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE",
              "negativeStockPolicy":"BLOCK"
            }
            """);
        var repeatedCommit = postJson(token, "/api/import-sessions/" + repeatedSessionId + "/commit",
            commitBody(repeatedDryRun.get("id").asText()));

        assertThat(repeatedCommit.get("summary").get("returnInMovementsCreated").asInt()).isZero();
        assertThat(repeatedCommit.get("summary").get("returnOutMovementsCreated").asInt()).isZero();
        assertThat(repeatedCommit.get("summary").get("duplicateReturnMovementsSkipped").asInt()).isEqualTo(2);
        assertThat(stockMovements.findByTenantIdAndProductIdOrderByMovementDateDesc(
            tenantId, UUID.fromString(product.get("id").asText())).stream()
            .filter(movement -> movement.movementType == DomainEnums.MovementType.RETURN_IN
                || movement.movementType == DomainEnums.MovementType.RETURN_OUT)).hasSize(2);
    }

    @Test
    void returnStockEffectsOnOrBeforeSnapshotRemainFinancialOnly() throws Exception {
        var registration = register("smart-covered-returns");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var product = postJson(token, "/api/products", """
            {"sku":"RETURN-COVERED","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses",
            "{\"name\":\"Main Location\",\"code\":\"RETURN-COVERED-WH\"}");
        postJson(token, "/api/customers", "{\"name\":\"Sample Customer\",\"creditLimit\":10000}");
        postJson(token, "/api/suppliers", "{\"name\":\"Sample Supplier\",\"creditDays\":30}");
        saveMovementAt(tenantId, product, warehouse, "100",
            java.time.Instant.parse("2026-06-29T12:00:00Z"));

        var sessionId = createSession(token, "Covered physical returns").get("id").asText();
        upload(token, sessionId, fixture("closing_stock_full_company.xml"),
            fixture("credit_return_on_snapshot.xml"), fixture("debit_return_before_snapshot.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {
              "strategy":"HYBRID_RECONCILIATION",
              "stockImpactMode":"CREATE_INVOICES_ONLY",
              "returnStockImpactMode":"APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE",
              "negativeStockPolicy":"BLOCK"
            }
            """);

        assertThat(dryRun.get("summary").get("returnInMovementsPlanned").asInt()).isZero();
        assertThat(dryRun.get("summary").get("returnOutMovementsPlanned").asInt()).isZero();
        assertThat(dryRun.get("summary").get("returnItemsCoveredBySnapshot").asInt()).isEqualTo(2);
        assertThat(dryRun.get("summary").get("sameDateReturnItemsDeferred").asInt()).isEqualTo(1);

        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(dryRun.get("id").asText()));
        assertThat(committed.get("summary").get("returnsCoveredBySnapshot").asInt()).isEqualTo(2);
        assertThat(committed.get("summary").get("sameDateReturnsDeferred").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("stockMovementsCreatedFromReturns").asInt()).isZero();
        assertThat(stockMovements.currentStock(
            tenantId, UUID.fromString(product.get("id").asText()), UUID.fromString(warehouse.get("id").asText())))
            .isEqualByComparingTo("100");
    }

    @Test
    void ambiguousReturnRequiresTenantScopedConfirmedReviewAndBlocksCustomerUser() throws Exception {
        var registration = register("smart-return-review");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var product = postJson(token, "/api/products", """
            {"sku":"RETURN-REVIEW","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses",
            "{\"name\":\"Return Counter\",\"code\":\"RETURN-REVIEW-WH\"}");
        postJson(token, "/api/customers", "{\"name\":\"Sample Customer\",\"creditLimit\":10000}");
        saveMovementAt(tenantId, product, warehouse, "100",
            java.time.Instant.parse("2026-06-29T12:00:00Z"));

        var sessionId = createSession(token, "Ambiguous return review").get("id").asText();
        upload(token, sessionId, fixture("closing_stock_full_company.xml"), fixture("ambiguous_credit_return.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var review = getJson(token, "/api/import-sessions/" + sessionId + "/return-review");
        assertThat(review).hasSize(1);
        var itemId = review.get(0).get("id").asText();

        var customerToken = tokenForRole(tenantId, DomainEnums.Role.CUSTOMER_USER);
        mvc.perform(get("/api/import-sessions/{id}/return-review", sessionId)
                .header("Authorization", "Bearer " + customerToken))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/import-sessions/{id}/return-review/{itemId}/resolve", sessionId, itemId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"action":"CONFIRM_PHYSICAL_SALES_RETURN","productId":"%s","warehouseId":"%s",
                     "quantity":4,"disposition":"RESTOCK_TO_AVAILABLE","confirmation":"WRONG"}
                    """.formatted(product.get("id").asText(), warehouse.get("id").asText())))
            .andExpect(status().isBadRequest());

        var other = register("smart-return-review-other");
        var otherToken = other.get("accessToken").asText();
        var otherProduct = postJson(otherToken, "/api/products", """
            {"sku":"OTHER-RETURN","name":"Other Product","unitCode":"PCS","defaultPurchasePrice":1,"defaultSalesPrice":2,"reorderPoint":1}
            """);
        var otherWarehouse = postJson(otherToken, "/api/warehouses",
            "{\"name\":\"Other Warehouse\",\"code\":\"OTHER-RETURN-WH\"}");
        mvc.perform(post("/api/import-sessions/{id}/return-review/{itemId}/resolve", sessionId, itemId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"action":"CONFIRM_PHYSICAL_SALES_RETURN","productId":"%s","warehouseId":"%s",
                     "quantity":4,"disposition":"RESTOCK_TO_AVAILABLE","confirmation":"POST RETURN STOCK"}
                    """.formatted(otherProduct.get("id").asText(), otherWarehouse.get("id").asText())))
            .andExpect(status().isBadRequest());

        postJson(token, "/api/import-sessions/" + sessionId + "/return-review/" + itemId + "/resolve", """
            {"action":"CONFIRM_PHYSICAL_SALES_RETURN","productId":"%s","warehouseId":"%s",
             "quantity":4,"disposition":"RESTOCK_TO_AVAILABLE","confirmation":"POST RETURN STOCK"}
            """.formatted(product.get("id").asText(), warehouse.get("id").asText()));
        assertThat(getJson(token, "/api/import-sessions/" + sessionId + "/return-review")).isEmpty();

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {
              "strategy":"HYBRID_RECONCILIATION",
              "stockImpactMode":"CREATE_INVOICES_ONLY",
              "returnStockImpactMode":"APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE",
              "negativeStockPolicy":"BLOCK"
            }
            """);
        assertThat(dryRun.get("summary").get("returnInMovementsPlanned").asInt()).isEqualTo(1);
        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(dryRun.get("id").asText()));
        assertThat(committed.get("summary").get("returnItemsResolvedManually").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("returnInMovementsCreated").asInt()).isEqualTo(1);
    }

    @Test
    void purchaseReturnNegativeStockCanBeBlockedOrFinanciallyCommittedWithMovementSkipped() throws Exception {
        var registration = register("smart-negative-return");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var product = postJson(token, "/api/products", """
            {"sku":"RETURN-NEGATIVE","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses",
            "{\"name\":\"Main Location\",\"code\":\"RETURN-NEGATIVE-WH\"}");
        postJson(token, "/api/suppliers", "{\"name\":\"Sample Supplier\",\"creditDays\":30}");
        saveMovementAt(tenantId, product, warehouse, "100",
            java.time.Instant.parse("2026-06-29T12:00:00Z"));

        var sessionId = createSession(token, "Negative purchase return").get("id").asText();
        upload(token, sessionId, fixture("closing_stock_full_company.xml"), fixture("debit_return_negative_stock.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var blocked = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {
              "strategy":"HYBRID_RECONCILIATION",
              "stockImpactMode":"CREATE_INVOICES_ONLY",
              "returnStockImpactMode":"APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE",
              "negativeStockPolicy":"BLOCK"
            }
            """);
        assertThat(blocked.get("summary").get("returnMovementsBlockedByNegativeStock").asInt()).isEqualTo(1);
        assertThat(blocked.get("summary").get("blockingErrors").asInt()).isGreaterThan(0);

        var skipped = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {
              "strategy":"HYBRID_RECONCILIATION",
              "stockImpactMode":"CREATE_INVOICES_ONLY",
              "returnStockImpactMode":"APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE",
              "negativeStockPolicy":"SKIP_STOCK_MOVEMENT"
            }
            """);
        assertThat(skipped.get("summary").get("blockingErrors").asInt()).isZero();
        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(skipped.get("id").asText()));
        assertThat(committed.get("summary").get("debitNotesCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("negativeStockReturnMovementsSkipped").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("returnOutMovementsCreated").asInt()).isZero();
        assertThat(stockMovements.currentStock(
            tenantId, UUID.fromString(product.get("id").asText()), UUID.fromString(warehouse.get("id").asText())))
            .isEqualByComparingTo("100");
    }

    @Test
    void postSnapshotModeBlocksWhenNoApplicableSnapshotExists() throws Exception {
        var registration = register("smart-post-snapshot-missing");
        var token = registration.get("accessToken").asText();
        postJson(token, "/api/products", """
            {"sku":"NO-SNAPSHOT-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"NO-SNAPSHOT-WH\"}");
        var sessionId = createSession(token, "Missing snapshot").get("id").asText();
        upload(token, sessionId, fixture("sales_after_snapshot.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {"strategy":"TRANSACTION_HISTORY","stockImpactMode":"APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE"}
            """);

        assertThat(dryRun.get("summary").get("voucherItemsWithoutSnapshot").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("blockingErrors").asInt()).isEqualTo(1);
        var items = getJson(token,
            "/api/import-sessions/" + sessionId + "/dry-run/items?itemType=STOCK_MOVEMENT");
        assertThat(items.get("content").get(0).get("errorCode").asText())
            .isEqualTo("NO_APPLICABLE_STOCK_SNAPSHOT");
    }

    @Test
    void postSnapshotModeBlocksUnreviewedVoucherWarehouse() throws Exception {
        var registration = register("smart-post-snapshot-warehouse-review");
        var token = registration.get("accessToken").asText();
        postJson(token, "/api/products", """
            {"sku":"WAREHOUSE-REVIEW-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        postJson(token, "/api/warehouses",
            "{\"name\":\"Snapshot Warehouse\",\"code\":\"SNAPSHOT-ONLY-WH\"}");
        var sessionId = createSession(token, "Unreviewed voucher warehouse").get("id").asText();
        upload(token, sessionId, fixture("closing_stock_full_company.xml"), fixture("sales_after_snapshot.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {"strategy":"HYBRID_RECONCILIATION","stockImpactMode":"APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE"}
            """);

        assertThat(dryRun.get("summary").get("voucherItemsBlockedByWarehouse").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("blockingErrors").asInt()).isGreaterThanOrEqualTo(1);
        var items = getJson(token,
            "/api/import-sessions/" + sessionId + "/dry-run/items?itemType=STOCK_MOVEMENT");
        assertThat(items.get("content").get(0).get("errorCode").asText())
            .isEqualTo("UNRESOLVED_VOUCHER_WAREHOUSE");
    }

    @Test
    void postSnapshotSkipNegativePolicyCreatesInvoiceWithoutMovement() throws Exception {
        var registration = register("smart-post-snapshot-negative-skip");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var product = postJson(token, "/api/products", """
            {"sku":"POST-NEG-SKIP","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses",
            "{\"name\":\"Main Location\",\"code\":\"POST-NEG-SKIP-WH\"}");
        var sessionId = createSession(token, "Post snapshot negative skip").get("id").asText();
        upload(token, sessionId, fixture("closing_stock_zero.xml"), fixture("sales_after_snapshot.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {"strategy":"HYBRID_RECONCILIATION","stockImpactMode":"APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE","negativeStockPolicy":"SKIP_STOCK_MOVEMENT"}
            """);

        assertThat(dryRun.get("summary").get("movementsSkippedByPolicy").asInt()).isEqualTo(1);
        assertThat(dryRun.get("summary").get("blockingErrors").asInt()).isZero();
        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(dryRun.get("id").asText()));

        assertThat(committed.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(committed.get("summary").get("salesInvoicesCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("negativeStockMovementsSkipped").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("salesStockMovementsCreated").asInt()).isZero();
        assertThat(stockMovements.currentStock(
            tenantId, UUID.fromString(product.get("id").asText()), UUID.fromString(warehouse.get("id").asText())))
            .isEqualByComparingTo("0");
    }

    @Test
    void stockChangeAfterDryRunRollsBackPostSnapshotInvoiceAndMovement() throws Exception {
        var registration = register("smart-post-snapshot-stale-stock");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var product = postJson(token, "/api/products", """
            {"sku":"POST-STALE-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses",
            "{\"name\":\"Main Location\",\"code\":\"POST-STALE-WH\"}");
        saveMovementAt(tenantId, product, warehouse, "100",
            java.time.Instant.parse("2026-06-29T12:00:00Z"));
        var sessionId = createSession(token, "Stale stock assumption").get("id").asText();
        upload(token, sessionId, fixture("closing_stock_full_company.xml"), fixture("sales_after_snapshot.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", """
            {"strategy":"HYBRID_RECONCILIATION","stockImpactMode":"APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE"}
            """);
        saveMovementAt(tenantId, product, warehouse, "1",
            java.time.Instant.parse("2026-07-01T12:00:00Z"));

        var failed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(dryRun.get("id").asText()));

        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("summary").get("transactionRolledBack").asBoolean()).isTrue();
        assertThat(salesInvoices.findByTenantIdAndInvoiceNumberIgnoreCase(tenantId, "S-POST-001")).isEmpty();
        assertThat(stockMovements.currentStock(
            tenantId, UUID.fromString(product.get("id").asText()), UUID.fromString(warehouse.get("id").asText())))
            .isEqualByComparingTo("101");
    }

    @Test
    void repeatedPostSnapshotUploadInNewSessionDoesNotDuplicateStockEffect() throws Exception {
        var registration = register("smart-post-snapshot-replay");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var product = postJson(token, "/api/products", """
            {"sku":"POST-REPLAY-A","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var warehouse = postJson(token, "/api/warehouses",
            "{\"name\":\"Main Location\",\"code\":\"POST-REPLAY-WH\"}");
        saveMovementAt(tenantId, product, warehouse, "100",
            java.time.Instant.parse("2026-06-29T12:00:00Z"));

        var firstSession = createSession(token, "First post snapshot upload").get("id").asText();
        upload(token, firstSession, fixture("closing_stock_full_company.xml"), fixture("sales_after_snapshot.xml"));
        postJson(token, "/api/import-sessions/" + firstSession + "/classify");
        postJson(token, "/api/import-sessions/" + firstSession + "/stage");
        var firstDryRun = postJson(token, "/api/import-sessions/" + firstSession + "/dry-run", """
            {"strategy":"HYBRID_RECONCILIATION","stockImpactMode":"APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE"}
            """);
        var firstCommit = postJson(token, "/api/import-sessions/" + firstSession + "/commit",
            commitBody(firstDryRun.get("id").asText()));
        assertThat(firstCommit.get("summary").get("salesStockMovementsCreated").asInt()).isEqualTo(1);

        var secondSession = createSession(token, "Repeated post snapshot upload").get("id").asText();
        upload(token, secondSession, fixture("closing_stock_full_company.xml"), fixture("sales_after_snapshot.xml"));
        postJson(token, "/api/import-sessions/" + secondSession + "/classify");
        postJson(token, "/api/import-sessions/" + secondSession + "/stage");
        var secondDryRun = postJson(token, "/api/import-sessions/" + secondSession + "/dry-run", """
            {"strategy":"HYBRID_RECONCILIATION","stockImpactMode":"APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE"}
            """);
        var secondCommit = postJson(token, "/api/import-sessions/" + secondSession + "/commit",
            commitBody(secondDryRun.get("id").asText()));

        assertThat(secondCommit.get("summary").get("duplicateSalesVouchersSkipped").asInt()).isEqualTo(1);
        assertThat(secondCommit.get("summary").get("salesStockMovementsCreated").asInt()).isZero();
        assertThat(stockMovements.findByTenantIdAndProductIdOrderByMovementDateDesc(
            tenantId, UUID.fromString(product.get("id").asText())).stream()
            .filter(movement -> movement.movementType == DomainEnums.MovementType.SALE))
            .singleElement();
        assertThat(stockMovements.currentStock(
            tenantId, UUID.fromString(product.get("id").asText()), UUID.fromString(warehouse.get("id").asText())))
            .isEqualByComparingTo("98");
    }

    @Test
    void unresolvedCustomerAndSupplierBlockInvoiceOnlyCommit() throws Exception {
        for (var fixtureName : java.util.List.of("sales.xml", "purchase.xml", "credit_note.xml", "debit_note.xml")) {
            var registration = register("smart-unresolved-party-" + fixtureName.replace(".xml", ""));
            var token = registration.get("accessToken").asText();
            postJson(token, "/api/products", """
                {"sku":"UNRESOLVED-PARTY","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
                """);
            var warehouseName = fixtureName.startsWith("sales") || fixtureName.startsWith("credit") ? "Main Location" : "Main Godown";
            postJson(token, "/api/warehouses", "{\"name\":\"" + warehouseName + "\",\"code\":\"UNRES-" + fixtureName.charAt(0) + "\"}");
            var sessionId = createSession(token, "Unresolved party " + fixtureName).get("id").asText();
            upload(token, sessionId, fixture(fixtureName));
            postJson(token, "/api/import-sessions/" + sessionId + "/classify");
            postJson(token, "/api/import-sessions/" + sessionId + "/stage");
            var voucher = stagedVouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(
                UUID.fromString(registration.get("tenantId").asText()), UUID.fromString(sessionId)).getFirst();
            voucher.matchStatus = DomainEnums.SmartMatchStatus.UNKNOWN;
            voucher.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
            stagedVouchers.save(voucher);
            var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
                "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");

            mvc.perform(post("/api/import-sessions/{id}/commit", sessionId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(commitBody(dryRun.get("id").asText())))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void unresolvedCreditNoteProductBlocksFinancialAdjustmentCommit() throws Exception {
        var registration = register("smart-note-unresolved-product");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/products", """
            {"sku":"NOTE-UNRES","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"NOTE-UNRES-WH\"}");
        var sessionId = createSession(token, "Unresolved note product").get("id").asText();
        upload(token, sessionId, fixture("credit_note.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var item = stagedVoucherItems.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(
            tenantId, UUID.fromString(sessionId)).getFirst();
        item.matchStatus = DomainEnums.SmartMatchStatus.UNKNOWN;
        item.reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
        stagedVoucherItems.save(item);
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");

        mvc.perform(post("/api/import-sessions/{id}/commit", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(commitBody(dryRun.get("id").asText())))
            .andExpect(status().isBadRequest());
        assertThat(financialAdjustments.findByTenantIdOrderByVoucherDateDesc(tenantId)).isEmpty();
    }

    @Test
    void safeCommitBlocksUnresolvedReviewAndCustomerUser() throws Exception {
        var registration = register("smart-commit-access");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/products", """
            {"sku":"MAGGI-REVIEW","name":"MAGGI MASALA 70","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var sessionId = createSession(token, "Review blocked").get("id").asText();
        upload(token, sessionId, fixture("duplicate_products.csv"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");

        mvc.perform(post("/api/import-sessions/{id}/commit", sessionId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(commitBody(dryRun.get("id").asText())))
            .andExpect(status().isBadRequest());

        var customerToken = tokenForRole(tenantId, DomainEnums.Role.CUSTOMER_USER);
        mvc.perform(post("/api/import-sessions/{id}/commit", sessionId)
                .header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON).content(commitBody(dryRun.get("id").asText())))
            .andExpect(status().isForbidden());
    }

    @Test
    void failedFoundationWriteRollsBackAllBusinessChanges() throws Exception {
        var registration = register("smart-commit-rollback");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var sessionId = createSession(token, "Rollback foundation").get("id").asText();
        upload(token, sessionId, fixture("inventory_master.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        var productCount = products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).size();

        var conflict = new ExternalRecordMapping();
        conflict.tenantId = tenantId;
        conflict.sourceSystem = "SMART_IMPORT";
        conflict.entityType = "PRODUCT";
        conflict.normalizedKey = "SAMPLE ITEM A|PCS";
        conflict.localEntityType = "PRODUCT";
        conflict.localEntityId = UUID.randomUUID();
        conflict.lastSeenAt = java.time.Instant.now();
        externalMappings.save(conflict);

        var failed = postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("summary").get("transactionRolledBack").asBoolean()).isTrue();
        assertThat(products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)).hasSize(productCount);
        var commit = smartCommits.findByTenantIdAndImportSessionId(tenantId, UUID.fromString(sessionId)).orElseThrow();
        assertThat(smartEffects.findByTenantIdAndCommitIdOrderByCreatedAtAsc(tenantId, commit.id)).isEmpty();
    }

    @Test
    void snapshotCommitHonorsSkipAndImportAsIsNegativeStockPolicies() throws Exception {
        var skipRegistration = register("smart-commit-negative-skip");
        var skipToken = skipRegistration.get("accessToken").asText();
        var skipTenant = UUID.fromString(skipRegistration.get("tenantId").asText());
        var skipProduct = postJson(skipToken, "/api/products", """
            {"sku":"NEG-SKIP","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var skipWarehouse = postJson(skipToken, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"NEG-SKIP-WH\"}");
        var skipSession = createSession(skipToken, "Skip negative snapshot").get("id").asText();
        upload(skipToken, skipSession, fixture("negative_closing_stock.xml"));
        postJson(skipToken, "/api/import-sessions/" + skipSession + "/classify");
        postJson(skipToken, "/api/import-sessions/" + skipSession + "/stage");
        var skipDryRun = postJson(skipToken, "/api/import-sessions/" + skipSession + "/dry-run", "{\"negativeStockPolicy\":\"SKIP_STOCK_MOVEMENT\"}");
        var skipped = postJson(skipToken, "/api/import-sessions/" + skipSession + "/commit", commitBody(skipDryRun.get("id").asText()));
        assertThat(skipped.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(skipped.get("summary").get("negativeStockRowsSkipped").asInt()).isEqualTo(1);
        assertThat(stockMovements.currentStock(skipTenant, UUID.fromString(skipProduct.get("id").asText()), UUID.fromString(skipWarehouse.get("id").asText())))
            .isEqualByComparingTo("0");

        var importRegistration = register("smart-commit-negative-import");
        var importToken = importRegistration.get("accessToken").asText();
        var importTenant = UUID.fromString(importRegistration.get("tenantId").asText());
        var tenant = tenants.findById(importTenant).orElseThrow();
        tenant.allowNegativeStock = true;
        tenants.save(tenant);
        var importProduct = postJson(importToken, "/api/products", """
            {"sku":"NEG-IMPORT","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var importWarehouse = postJson(importToken, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"NEG-IMPORT-WH\"}");
        var importSession = createSession(importToken, "Import negative snapshot").get("id").asText();
        upload(importToken, importSession, fixture("negative_closing_stock.xml"));
        postJson(importToken, "/api/import-sessions/" + importSession + "/classify");
        postJson(importToken, "/api/import-sessions/" + importSession + "/stage");
        var importDryRun = postJson(importToken, "/api/import-sessions/" + importSession + "/dry-run", "{\"negativeStockPolicy\":\"IMPORT_AS_IS\"}");
        var imported = postJson(importToken, "/api/import-sessions/" + importSession + "/commit", commitBody(importDryRun.get("id").asText()));
        assertThat(imported.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(imported.get("summary").get("snapshotMovementsCreated").asInt()).isEqualTo(1);
        assertThat(stockMovements.currentStock(importTenant, UUID.fromString(importProduct.get("id").asText()), UUID.fromString(importWarehouse.get("id").asText())))
            .isEqualByComparingTo("-5");
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(importTenant).stream().map(log -> log.action))
            .contains("NEGATIVE_STOCK_ALLOWED");
    }

    @Test
    void safeCommitCreatesReviewedTallyGodownAndRemainsTenantScoped() throws Exception {
        var registration = register("smart-commit-warehouse");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var sessionId = createSession(token, "Create Tally godown").get("id").asText();
        upload(token, sessionId, fixture("purchase.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var workspace = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        JsonNode warehouseIssue = null;
        for (var item : workspace.get("reviewItems")) {
            if ("UNKNOWN_WAREHOUSE".equals(item.get("code").asText())) warehouseIssue = item;
        }
        assertThat(warehouseIssue).isNotNull();
        postJson(token, "/api/import-sessions/" + sessionId + "/review-items/" + warehouseIssue.get("id").asText() + "/resolve",
            "{\"action\":\"CREATE_WAREHOUSE_LATER\"}");
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");

        var otherToken = register("smart-commit-other-tenant").get("accessToken").asText();
        mvc.perform(post("/api/import-sessions/{id}/commit", sessionId)
                .header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON).content(commitBody(dryRun.get("id").asText())))
            .andExpect(status().isNotFound());

        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));
        assertThat(committed.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(committed.get("summary").get("warehousesCreated").asInt()).isEqualTo(1);
        assertThat(warehouses.findByTenantIdAndNameIgnoreCase(tenantId, "Main Godown")).isPresent();
    }

    @Test
    void debtorAndCreditorAnalysisCreatesOutstandingSnapshotsWithoutFakeInvoices() throws Exception {
        var registration = register("smart-outstanding-snapshots");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var sessionId = createSession(token, "Outstanding snapshots").get("id").asText();
        upload(token, sessionId, fixture("debtors_creditors.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var workspace = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        assertThat(workspace.get("summary").get("debtorRowsStaged").asInt()).isEqualTo(1);
        assertThat(workspace.get("summary").get("creditorRowsStaged").asInt()).isEqualTo(1);
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(dryRun.get("id").asText()));

        assertThat(committed.get("summary").get("outstandingSnapshotsCreated").asInt()).isEqualTo(2);
        assertThat(committed.get("summary").get("debtorRowsProcessed").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("creditorRowsProcessed").asInt()).isEqualTo(1);
        assertThat(outstandingSnapshots.findByTenantIdAndSourceImportSessionIdOrderBySourceRowNumberAsc(
            tenantId, UUID.fromString(sessionId))).extracting(row -> row.outstandingAmount.toPlainString())
            .containsExactly("2500.00", "1800.00");
        assertThat(salesInvoices.findByTenantId(tenantId)).isEmpty();
        assertThat(purchaseInvoices.findByTenantId(tenantId)).isEmpty();
    }

    @Test
    void cashbookLinksReceiptsAndPaymentsToInvoiceOnlyDocumentsWithoutChangingStock() throws Exception {
        var registration = register("smart-cashbook-invoice-match");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/products", """
            {"sku":"CASH-MATCH","name":"Sample Item A","unitCode":"PCS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        postJson(token, "/api/warehouses", "{\"name\":\"Main Location\",\"code\":\"CASH-SALE\"}");
        postJson(token, "/api/warehouses", "{\"name\":\"Main Godown\",\"code\":\"CASH-PURCHASE\"}");
        var sessionId = createSession(token, "Invoices and matched cashbook").get("id").asText();
        upload(token, sessionId, fixture("cashbook_matched.xml"), fixture("debtors_creditors.xml"),
            fixture("purchase.xml"), fixture("sales.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var workspace = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        assertThat(workspace.get("cashbookEntries").findValuesAsText("cashbookMatchStatus"))
            .containsExactlyInAnyOrder("MATCHED_SALES_INVOICE", "MATCHED_PURCHASE_INVOICE");
        // A payment's bank reference need not equal its matched invoice number.
        var matchedRows = stagedCashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(
            tenantId, UUID.fromString(sessionId));
        assertThat(matchedRows).allSatisfy(row -> assertThat(row.matchedStagedVoucherId).isNotNull());
        matchedRows.forEach(row -> row.referenceNumber = "BANK-TRANSACTION-" + row.sourceRowNumber);
        stagedCashbook.saveAllAndFlush(matchedRows);
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        assertThat(dryRun.get("summary").get("cashbookRowsReady").asInt()).isEqualTo(2);
        var movementCount = stockMovements.count();
        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(dryRun.get("id").asText()));

        assertThat(committed.get("summary").get("customerPaymentsCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("supplierPaymentsCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("paymentsLinkedToSalesInvoices").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("paymentsLinkedToPurchaseInvoices").asInt()).isEqualTo(1);
        assertThat(customerPayments.countByTenantIdAndSourceImportSessionId(tenantId, UUID.fromString(sessionId))).isEqualTo(1);
        assertThat(supplierPayments.countByTenantIdAndSourceImportSessionId(tenantId, UUID.fromString(sessionId))).isEqualTo(1);
        assertThat(customerPayments.findByTenantIdAndCustomerIdOrderByPaymentDateDesc(tenantId,
            customerPayments.findAll().stream().filter(row -> tenantId.equals(row.tenantId)).findFirst().orElseThrow().customerId)
            .getFirst().salesInvoiceId).isNotNull();
        assertThat(supplierPayments.findAll().stream().filter(row -> tenantId.equals(row.tenantId)).findFirst().orElseThrow().purchaseInvoiceId)
            .isNotNull();
        assertThat(stockMovements.count()).isEqualTo(movementCount);
    }

    @Test
    void unmatchedCashbookRowsRemainVisibleAndAreNotSilentlyPosted() throws Exception {
        var registration = register("smart-cashbook-unmatched-commit");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var sessionId = createSession(token, "Unmatched cashbook").get("id").asText();
        upload(token, sessionId, fixture("cashbook_unmatched.csv"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var workspace = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        assertThat(workspace.get("cashbookEntries").get(0).get("cashbookMatchStatus").asText())
            .isEqualTo("UNMATCHED_REVIEW");
        // Simulate a persisted ready label written by the old number-only matcher.
        var legacy = stagedCashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(
            tenantId, UUID.fromString(sessionId)).getFirst();
        legacy.cashbookMatchStatus = DomainEnums.CashbookMatchStatus.MATCHED_SALES_INVOICE;
        legacy.matchedPartyType = DomainEnums.SmartPartyType.CUSTOMER;
        legacy.reviewStatus = DomainEnums.SmartReviewStatus.AUTO_RESOLVED;
        stagedCashbook.saveAndFlush(legacy);
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        assertThat(dryRun.get("summary").get("reviewRequiredCount").asInt()).isZero();
        assertThat(dryRun.get("summary").get("cashbookRowsReady").asInt()).isZero();
        assertThat(getJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review")).hasSize(1);
        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(dryRun.get("id").asText()));

        assertThat(committed.get("summary").get("cashbookRowsUnmatched").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("warnings").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("cashbookMessage").asText()).contains("1 cashbook rows were not posted");
        assertThat(committed.get("summary").get("customerPaymentsCreated").asInt()).isZero();
        assertThat(stagedCashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(
            tenantId, UUID.fromString(sessionId))).hasSize(1);
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(log -> log.action))
            .contains("SMART_IMPORT_CASHBOOK_REVIEW_REQUIRED");
        legacy = stagedCashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(
            tenantId, UUID.fromString(sessionId)).getFirst();
        legacy.cashbookMatchStatus = DomainEnums.CashbookMatchStatus.MATCHED_SALES_INVOICE;
        legacy.reviewStatus = DomainEnums.SmartReviewStatus.AUTO_RESOLVED;
        stagedCashbook.saveAndFlush(legacy);
        var rechecked = postJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review/recheck");
        assertThat(rechecked.get("unpostedReviewCount").asInt()).isEqualTo(1);
        assertThat(rechecked.get("businessRecordsChanged").asInt()).isZero();
        assertThat(getJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review")).hasSize(1);
        postJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review/" + legacy.id + "/resolve",
            "{\"action\":\"IGNORE\",\"note\":\"Expense, not a customer payment\"}");
        rechecked = postJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review/recheck");
        assertThat(rechecked.get("unpostedReviewCount").asInt()).isZero();
        assertThat(customerPayments.countByTenantIdAndSourceImportSessionId(tenantId, UUID.fromString(sessionId))).isZero();
    }

    @Test
    void ambiguousCashbookInvoiceMatchRequiresReviewInsteadOfGuessing() throws Exception {
        var token = register("smart-cashbook-low-confidence").get("accessToken").asText();
        var sessionId = createSession(token, "Ambiguous cashbook match").get("id").asText();
        upload(token, sessionId, fixture("cashbook_ambiguous.csv"), fixture("sales_ambiguous_amount.xml"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var workspace = postJson(token, "/api/import-sessions/" + sessionId + "/stage");

        assertThat(workspace.get("cashbookEntries").get(0).get("cashbookMatchStatus").asText())
            .isEqualTo("LOW_CONFIDENCE_REVIEW");
        assertThat(workspace.get("cashbookEntries").get(0).get("matchedInvoiceId").asText()).isBlank();
        assertThat(workspace.get("reviewItems").findValuesAsText("code"))
            .contains("CASHBOOK_LOW_CONFIDENCE");
    }

    @Test
    void repeatedCashbookImportSkipsDuplicatePaymentsAcrossSessions() throws Exception {
        var registration = register("smart-cashbook-idempotent");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        postJson(token, "/api/customers", "{\"name\":\"Sample Customer\",\"creditLimit\":10000}");
        postJson(token, "/api/suppliers", "{\"name\":\"Sample Supplier\",\"creditDays\":30}");

        for (var pass = 0; pass < 2; pass++) {
            var sessionId = createSession(token, "Cashbook pass " + pass).get("id").asText();
            upload(token, sessionId, fixture("cashbook_party_payments.csv"));
            postJson(token, "/api/import-sessions/" + sessionId + "/classify");
            postJson(token, "/api/import-sessions/" + sessionId + "/stage");
            var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
                "{\"strategy\":\"TRANSACTION_HISTORY\"}");
            var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
                commitBody(dryRun.get("id").asText()));
            if (pass == 0) {
                assertThat(committed.get("summary").get("customerPaymentsCreated").asInt()).isEqualTo(1);
                assertThat(committed.get("summary").get("supplierPaymentsCreated").asInt()).isEqualTo(1);
            } else {
                assertThat(committed.get("summary").get("duplicatePaymentsSkipped").asInt()).isEqualTo(2);
                assertThat(committed.get("summary").get("customerPaymentsCreated").asInt()).isZero();
                assertThat(committed.get("summary").get("supplierPaymentsCreated").asInt()).isZero();
            }
        }
        assertThat(customerPayments.findAll().stream().filter(row -> tenantId.equals(row.tenantId))).hasSize(1);
        assertThat(supplierPayments.findAll().stream().filter(row -> tenantId.equals(row.tenantId))).hasSize(1);
    }

    @Test
    void unmatchedCashbookCanBeResolvedToCustomerPaymentAndUpdatesCommittedReconciliation() throws Exception {
        var registration = register("cashbook-review-customer");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var customer = postJson(token, "/api/customers", "{\"name\":\"Resolved Customer\",\"creditLimit\":5000}");
        var review = stageUnmatchedCashbook(token, "Customer review");
        var sessionId = review.get("sessionId").asText();
        var entryId = review.get("entryId").asText();
        var candidates = getJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review");
        assertThat(candidates.get(0).get("candidates").findValuesAsText("reason")).isNotEmpty();

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        postJson(token, "/api/import-sessions/" + sessionId + "/commit", commitBody(dryRun.get("id").asText()));
        var resolved = postJson(token, "/api/import-sessions/" + sessionId + "/cashbook-review/" + entryId + "/resolve",
            cashbookResolution("MAP_CUSTOMER", customer.get("id").asText()));

        assertThat(resolved.get("paymentPosted").asBoolean()).isTrue();
        assertThat(customerPayments.countByTenantIdAndSourceImportSessionId(tenantId, UUID.fromString(sessionId))).isEqualTo(1);
        var reconciliation = getJson(token, "/api/import-sessions/" + sessionId + "/commit-result");
        assertThat(reconciliation.get("summary").get("cashbookRowsManuallyResolved").asInt()).isEqualTo(1);
        assertThat(reconciliation.get("summary").get("cashbookRowsUnmatched").asInt()).isZero();
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(log -> log.action))
            .contains("CASHBOOK_REVIEW_RESOLVED", "PAYMENT_MANUALLY_MATCHED");
    }

    @Test
    void unmatchedCashbookCanBeResolvedToSupplierPayment() throws Exception {
        var registration = register("cashbook-review-supplier");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var supplier = postJson(token, "/api/suppliers", "{\"name\":\"Resolved Supplier\",\"creditDays\":30}");
        var review = stageUnmatchedCashbook(token, "Supplier review");
        var resolved = postJson(token, "/api/import-sessions/" + review.get("sessionId").asText()
                + "/cashbook-review/" + review.get("entryId").asText() + "/resolve",
            cashbookResolution("MAP_SUPPLIER", supplier.get("id").asText()));

        assertThat(resolved.get("paymentPosted").asBoolean()).isTrue();
        assertThat(supplierPayments.findAll().stream().filter(row -> tenantId.equals(row.tenantId)))
            .singleElement().satisfies(payment -> assertThat(payment.supplierId)
                .isEqualTo(UUID.fromString(supplier.get("id").asText())));
    }

    @Test
    void unmatchedCashbookCanBeResolvedToSalesInvoice() throws Exception {
        var registration = register("cashbook-review-sales-invoice");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var customer = postJson(token, "/api/customers", "{\"name\":\"Invoice Customer\",\"creditLimit\":5000}");
        var invoice = salesInvoice(tenantId, UUID.fromString(customer.get("id").asText()), "MAN-S-1");
        var review = stageUnmatchedCashbook(token, "Sales invoice review");
        postJson(token, "/api/import-sessions/" + review.get("sessionId").asText()
                + "/cashbook-review/" + review.get("entryId").asText() + "/resolve",
            cashbookResolution("MAP_SALES_INVOICE", invoice.id.toString()));

        assertThat(customerPayments.findAll().stream().filter(row -> tenantId.equals(row.tenantId)))
            .singleElement().satisfies(payment -> assertThat(payment.salesInvoiceId).isEqualTo(invoice.id));
    }

    @Test
    void unmatchedCashbookCanBeResolvedToPurchaseInvoice() throws Exception {
        var registration = register("cashbook-review-purchase-invoice");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var supplier = postJson(token, "/api/suppliers", "{\"name\":\"Invoice Supplier\",\"creditDays\":30}");
        var invoice = purchaseInvoice(tenantId, UUID.fromString(supplier.get("id").asText()), "MAN-P-1");
        var review = stageUnmatchedCashbook(token, "Purchase invoice review");
        postJson(token, "/api/import-sessions/" + review.get("sessionId").asText()
                + "/cashbook-review/" + review.get("entryId").asText() + "/resolve",
            cashbookResolution("MAP_PURCHASE_INVOICE", invoice.id.toString()));

        assertThat(supplierPayments.findAll().stream().filter(row -> tenantId.equals(row.tenantId)))
            .singleElement().satisfies(payment -> assertThat(payment.purchaseInvoiceId).isEqualTo(invoice.id));
    }

    @Test
    void manualCashbookResolutionPreventsDuplicatePaymentsAcrossSessions() throws Exception {
        var registration = register("cashbook-review-duplicate");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var customer = postJson(token, "/api/customers", "{\"name\":\"Duplicate Customer\",\"creditLimit\":5000}");
        for (var pass = 0; pass < 2; pass++) {
            var review = stageUnmatchedCashbook(token, "Duplicate resolution " + pass);
            var resolved = postJson(token, "/api/import-sessions/" + review.get("sessionId").asText()
                    + "/cashbook-review/" + review.get("entryId").asText() + "/resolve",
                cashbookResolution("MAP_CUSTOMER", customer.get("id").asText()));
            assertThat(resolved.get("duplicatePaymentSkipped").asBoolean()).isEqualTo(pass == 1);
        }
        assertThat(customerPayments.findAll().stream().filter(row -> tenantId.equals(row.tenantId))).hasSize(1);
    }

    @Test
    void cashbookReviewIsTenantScopedAndCustomerUserIsBlocked() throws Exception {
        var registration = register("cashbook-review-security");
        var ownerToken = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var customer = postJson(ownerToken, "/api/customers", "{\"name\":\"Secure Customer\",\"creditLimit\":5000}");
        var review = stageUnmatchedCashbook(ownerToken, "Secure review");
        var sessionId = review.get("sessionId").asText();
        var entryId = review.get("entryId").asText();
        var otherToken = register("cashbook-review-other-tenant").get("accessToken").asText();
        var customerToken = tokenForRole(tenantId, DomainEnums.Role.CUSTOMER_USER);

        mvc.perform(post("/api/import-sessions/{id}/cashbook-review/recheck", sessionId)
            .header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
        mvc.perform(post("/api/import-sessions/{id}/cashbook-review/recheck", sessionId)
            .header("Authorization", "Bearer " + customerToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/import-sessions/{id}/cashbook-review", sessionId)
                .header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
        mvc.perform(post("/api/import-sessions/{id}/cashbook-review/{entryId}/resolve", sessionId, entryId)
                .header("Authorization", "Bearer " + otherToken).contentType(MediaType.APPLICATION_JSON)
                .content(cashbookResolution("MAP_CUSTOMER", customer.get("id").asText())))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/import-sessions/{id}/cashbook-review", sessionId)
                .header("Authorization", "Bearer " + customerToken)).andExpect(status().isForbidden());
        mvc.perform(post("/api/import-sessions/{id}/cashbook-review/{entryId}/resolve", sessionId, entryId)
                .header("Authorization", "Bearer " + customerToken).contentType(MediaType.APPLICATION_JSON)
                .content(cashbookResolution("MAP_CUSTOMER", customer.get("id").asText())))
            .andExpect(status().isForbidden());
    }

    @Test
    void ignoredCashbookEntryCreatesNoPayment() throws Exception {
        var registration = register("cashbook-review-ignore");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var review = stageUnmatchedCashbook(token, "Ignore review");
        postJson(token, "/api/import-sessions/" + review.get("sessionId").asText()
                + "/cashbook-review/" + review.get("entryId").asText() + "/resolve",
            "{\"action\":\"IGNORE\",\"note\":\"Non-business transfer\"}");

        assertThat(customerPayments.findAll().stream().filter(row -> tenantId.equals(row.tenantId))).isEmpty();
        assertThat(supplierPayments.findAll().stream().filter(row -> tenantId.equals(row.tenantId))).isEmpty();
        assertThat(getJson(token, "/api/import-sessions/" + review.get("sessionId").asText() + "/cashbook-review")).isEmpty();
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(log -> log.action))
            .contains("CASHBOOK_ENTRY_IGNORED");
    }

    @Test
    void zeroValueRateResolutionSupportsExactBulkPreviewAndSurvivesRebuild() throws Exception {
        var registration = register("rate-review-zero-bulk");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var workspace = uploadClassifyAndStage(token, "Zero value review", fixture("voucher_rate_review.xml"));
        var sessionId = workspace.get("testSessionId").asText();
        var issue = findRateIssue(workspace, "S-FREE-1");

        var preview = postJson(token, "/api/import-sessions/" + sessionId + "/rate-review/bulk/preview", """
            {"sourceIssueId":"%s","action":"CONFIRM_ZERO_VALUE"}
            """.formatted(issue.get("id").asText()));
        assertThat(preview.get("matchingRows").asInt()).isEqualTo(2);
        assertThat(preview.get("matchingVouchers").asInt()).isEqualTo(2);
        assertThat(preview.get("confirmation").asText()).isEqualTo("APPLY TO 2 MATCHING ROWS");

        mvc.perform(post("/api/import-sessions/{id}/rate-review/bulk/resolve", sessionId)
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sourceIssueId":"%s","action":"CONFIRM_ZERO_VALUE","expectedCount":2,"confirmation":"APPLY TO 1 MATCHING ROWS"}
                    """.formatted(issue.get("id").asText())))
            .andExpect(status().isBadRequest());

        var resolved = postJson(token, "/api/import-sessions/" + sessionId + "/rate-review/bulk/resolve", """
            {"sourceIssueId":"%s","action":"CONFIRM_ZERO_VALUE","expectedCount":2,"confirmation":"APPLY TO 2 MATCHING ROWS"}
            """.formatted(issue.get("id").asText()));
        assertThat(rateIssuesForProduct(resolved, "Free Sample Pen")).isZero();
        assertThat(resolved.get("voucherItems").findValuesAsText("rateSource").stream()
            .filter("USER_CONFIRMED_ZERO_VALUE"::equals).count()).isEqualTo(2);

        var rebuilt = postJson(token, "/api/import-sessions/" + sessionId + "/rebuild");
        assertThat(rateIssuesForProduct(rebuilt, "Free Sample Pen")).isZero();
        assertThat(auditLogs.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(log -> log.action))
            .contains("SMART_IMPORT_RATE_REVIEW_BULK_RESOLVED");
    }

    @Test
    void deriveVerifiedAndKeepUnresolvedRateActionsValidateSourceEvidence() throws Exception {
        var registration = register("rate-review-derived-manual");
        var token = registration.get("accessToken").asText();
        var workspace = uploadClassifyAndStage(token, "Derived and manual rates", fixture("voucher_rate_review.xml"));
        var sessionId = workspace.get("testSessionId").asText();

        var derivedIssue = findRateIssue(workspace, "S-DERIVE-1");
        var afterDerive = postJson(token, "/api/import-sessions/" + sessionId + "/rate-review/"
            + derivedIssue.get("id").asText() + "/resolve", """
            {"action":"DERIVE_RATE","confirmation":"DERIVE RATE"}
            """);
        var derivedItem = findVoucherItem(afterDerive, "Derived Rate Item");
        assertThat(derivedItem.get("rate").decimalValue()).isEqualByComparingTo("100");
        assertThat(derivedItem.get("rateSource").asText()).isEqualTo("USER_DERIVED_FROM_AMOUNT");
        assertThat(derivedItem.get("id")).isEqualTo(findVoucherItem(workspace, "Derived Rate Item").get("id"));

        var manualIssue = findRateIssue(afterDerive, "P-MANUAL-1");
        mvc.perform(post("/api/import-sessions/{id}/rate-review/{issueId}/resolve", sessionId,
                manualIssue.get("id").asText()).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"action":"ENTER_VERIFIED_RATE","verifiedRate":42.50,"reason":"","confirmation":"APPLY VERIFIED RATE"}
                    """))
            .andExpect(status().isBadRequest());

        var kept = postJson(token, "/api/import-sessions/" + sessionId + "/rate-review/"
            + manualIssue.get("id").asText() + "/resolve", "{\"action\":\"KEEP_UNRESOLVED\"}");
        assertThat(findRateIssue(kept, "P-MANUAL-1")).isNotNull();

        var afterManual = postJson(token, "/api/import-sessions/" + sessionId + "/rate-review/"
            + manualIssue.get("id").asText() + "/resolve", """
            {"action":"ENTER_VERIFIED_RATE","verifiedRate":42.50,"reason":"Checked original Tally purchase voucher","confirmation":"APPLY VERIFIED RATE"}
            """);
        var manualItem = findVoucherItem(afterManual, "Verified Rate Item");
        assertThat(manualItem.get("rate").decimalValue()).isEqualByComparingTo("42.5");
        assertThat(manualItem.get("rateSource").asText()).isEqualTo("USER_VERIFIED_RATE");
        assertThat(manualItem.get("id")).isEqualTo(findVoucherItem(workspace, "Verified Rate Item").get("id"));
        assertThat(rateIssuesForProduct(afterManual, "Verified Rate Item")).isZero();
        var updatedVoucher = stagedVouchers.findById(UUID.fromString(manualItem.get("voucherId").asText())).orElseThrow();
        assertThat(updatedVoucher.totalAmount).isEqualByComparingTo("170");
        var rebuilt = postJson(token, "/api/import-sessions/" + sessionId + "/rebuild");
        assertThat(findVoucherItem(rebuilt, "Verified Rate Item").get("rate").decimalValue()).isEqualByComparingTo("42.5");
    }

    @Test
    void bulkVerifiedRatesUpdateMultipleLinesPerVoucherWithoutRestagingAndInvalidateDryRun() throws Exception {
        var token = register("verified-rate-bulk").get("accessToken").asText();
        var workspace = uploadClassifyAndStage(token, "Bulk verified rates", fixture("verified_rate_bulk.xml"));
        var sessionId = workspace.get("testSessionId").asText();
        var issueId = findRateIssue(workspace, "RATE-1").get("id").asText();
        postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        var preview = postJson(token, "/api/import-sessions/" + sessionId + "/rate-review/bulk/preview", """
            {"sourceIssueId":"%s","action":"ENTER_VERIFIED_RATE","verifiedRate":7.80}
            """.formatted(issueId));
        assertThat(preview.get("matchingRows").asInt()).isEqualTo(3);
        assertThat(preview.get("matchingVouchers").asInt()).isEqualTo(2);
        var resolved = postJson(token, "/api/import-sessions/" + sessionId + "/rate-review/bulk/resolve", """
            {"sourceIssueId":"%s","action":"ENTER_VERIFIED_RATE","verifiedRate":7.80,
             "reason":"Verified against original Tally vouchers","expectedCount":3,"confirmation":"APPLY TO 3 MATCHING ROWS"}
            """.formatted(issueId));
        assertThat(rateIssuesForProduct(resolved, "Rate Pen")).isZero();
        assertThat(resolved.get("voucherItems").findValuesAsText("id")).containsExactlyElementsOf(workspace.get("voucherItems").findValuesAsText("id"));
        for (var item : resolved.get("voucherItems")) {
            assertThat(item.get("rate").decimalValue()).isEqualByComparingTo("7.8");
            assertThat(item.get("rateSource").asText()).isEqualTo("USER_VERIFIED_RATE");
        }
        for (var voucher : resolved.get("vouchers")) {
            assertThat(voucher.get("totalAmount").decimalValue()).isEqualByComparingTo(
                "RATE-1".equals(voucher.get("voucherNumber").asText()) ? "39" : "31.2");
        }
        assertThat(getJson(token, "/api/import-sessions/" + sessionId + "/dry-run").path("available").asBoolean()).isFalse();
        var reloaded = getJson(token, "/api/import-sessions/" + sessionId + "/staging");
        assertThat(rateIssuesForProduct(reloaded, "Rate Pen")).isZero();
        var rebuilt = postJson(token, "/api/import-sessions/" + sessionId + "/rebuild");
        assertThat(rateIssuesForProduct(rebuilt, "Rate Pen")).isZero();
        for (var voucher : rebuilt.get("vouchers")) {
            assertThat(voucher.get("totalAmount").decimalValue()).isEqualByComparingTo(
                "RATE-1".equals(voucher.get("voucherNumber").asText()) ? "39" : "31.2");
        }
    }

    @Test
    void amountOnlyDebitNoteCanBeReviewedAndCommittedAsFinancialAdjustment() throws Exception {
        var registration = register("rate-review-financial-adjustment");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var beforeProducts = products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).size();
        var workspace = uploadClassifyAndStage(token, "Amount-only note", fixture("amount_only_debit_note.xml"));
        var sessionId = workspace.get("testSessionId").asText();
        var issue = findRateIssue(workspace, "DN-6");

        var resolved = postJson(token, "/api/import-sessions/" + sessionId + "/rate-review/"
            + issue.get("id").asText() + "/resolve", """
            {"action":"TREAT_AS_FINANCIAL_ADJUSTMENT","reason":"Advance discount has no inventory quantity","confirmation":"TREAT AS FINANCIAL ADJUSTMENT"}
            """);
        assertThat(resolved.get("summary").get("voucherItemsStaged").asInt()).isZero();
        assertThat(resolved.get("summary").get("productsStaged").asInt()).isZero();

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"strategy\":\"TRANSACTION_HISTORY\",\"returnStockImpactMode\":\"FINANCIAL_ONLY\"}");
        assertThat(dryRun.get("summary").get("blockingErrors").asInt()).isZero();
        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(dryRun.get("id").asText()));
        assertThat(committed.get("summary").get("financialAdjustmentLinesResolved").asInt()).isEqualTo(1);
        assertThat(financialAdjustments.findByTenantIdOrderByVoucherDateDesc(tenantId)).singleElement()
            .satisfies(note -> {
                assertThat(note.adjustmentType).isEqualTo(DomainEnums.FinancialAdjustmentType.DEBIT_NOTE);
                assertThat(note.totalAmount).isEqualByComparingTo("153.60");
            });
        assertThat(products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)).hasSize(beforeProducts);
        assertThat(stockMovements.findAll().stream().filter(row -> tenantId.equals(row.tenantId))).isEmpty();
    }

    @Test
    void excludedRateVoucherIsSkippedWithoutCreatingBusinessRecords() throws Exception {
        var registration = register("rate-review-exclude");
        var token = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var workspace = uploadClassifyAndStage(token, "Excluded rate voucher", fixture("excluded_invalid_rate_sale.xml"));
        var sessionId = workspace.get("testSessionId").asText();
        var issue = findRateIssue(workspace, "S-EXCLUDE-1");

        var resolved = postJson(token, "/api/import-sessions/" + sessionId + "/rate-review/"
            + issue.get("id").asText() + "/resolve", """
            {"action":"EXCLUDE_VOUCHER","reason":"Source voucher requires correction","confirmation":"EXCLUDE VOUCHER"}
            """);
        assertThat(resolved.get("vouchers").get(0).get("id")).isEqualTo(workspace.get("vouchers").get(0).get("id"));
        assertThat(rateIssuesForProduct(resolved, "Unknown Price Item")).isZero();
        assertThat(resolved.get("summary").get("voucherItemsStaged").asInt()).isZero();
        assertThat(resolved.get("summary").get("productsStaged").asInt()).isZero();

        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run",
            "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        assertThat(dryRun.get("summary").get("vouchersExcludedByUser").asInt()).isEqualTo(1);
        var committed = postJson(token, "/api/import-sessions/" + sessionId + "/commit",
            commitBody(dryRun.get("id").asText()));
        assertThat(committed.get("summary").get("vouchersExcludedByUser").asInt()).isEqualTo(1);
        assertThat(salesInvoices.findAll().stream().filter(row -> tenantId.equals(row.tenantId))).isEmpty();
        assertThat(products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)).isEmpty();
    }

    @Test
    void bulkExclusionClearsAllVoucherErrorsWithoutRestagingAndPreservesSharedCandidates() throws Exception {
        var registration = register("rate-bulk-exclusion");
        var token = registration.get("accessToken").asText();
        var workspace = uploadClassifyAndStage(token, "Bulk exclusion", fixture("bulk_excluded_rate_vouchers.xml"));
        var sessionId = workspace.get("testSessionId").asText();
        var issueId = findRateIssue(workspace, "BULK-1").get("id").asText();
        var untouchedIssueId = findRateIssue(workspace, "RETAIN-2").get("id").asText();
        postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        var preview = postJson(token, "/api/import-sessions/" + sessionId + "/rate-review/bulk/preview", """
            {"sourceIssueId":"%s","action":"EXCLUDE_VOUCHER"}
            """.formatted(issueId));
        assertThat(preview.get("matchingRows").asInt()).isEqualTo(2);
        var resolved = postJson(token, "/api/import-sessions/" + sessionId + "/rate-review/bulk/resolve", """
            {"sourceIssueId":"%s","action":"EXCLUDE_VOUCHER","reason":"Checked source vouchers",
             "expectedCount":2,"confirmation":"APPLY TO 2 MATCHING ROWS"}
            """.formatted(issueId));
        assertThat(rateIssuesForProduct(resolved, "Shared Item")).isZero();
        assertThat(rateIssuesForProduct(resolved, "Excluded Only Item")).isZero();
        assertThat(findRateIssue(resolved, "RETAIN-2").get("id").asText()).isEqualTo(untouchedIssueId);
        assertThat(resolved.get("vouchers").findValuesAsText("id")).containsExactlyElementsOf(workspace.get("vouchers").findValuesAsText("id"));
        assertThat(resolved.get("products").findValuesAsText("name")).contains("Shared Item").doesNotContain("Excluded Only Item");
        assertThat(resolved.get("parties").findValuesAsText("name")).contains("Shared Customer").doesNotContain("Excluded Customer");
        assertThat(getJson(token, "/api/import-sessions/" + sessionId + "/dry-run").path("available").asBoolean()).isFalse();
        var reloaded = getJson(token, "/api/import-sessions/" + sessionId + "/staging");
        assertThat(rateIssuesForProduct(reloaded, "Shared Item")).isZero();
        var rebuilt = postJson(token, "/api/import-sessions/" + sessionId + "/rebuild", "{}");
        assertThat(rateIssuesForProduct(rebuilt, "Shared Item")).isZero();
        assertThat(rateIssuesForProduct(rebuilt, "Excluded Only Item")).isZero();
        assertThat(findRateIssue(rebuilt, "RETAIN-2")).isNotNull();
        var dryRun = postJson(token, "/api/import-sessions/" + sessionId + "/dry-run", "{\"strategy\":\"TRANSACTION_HISTORY\"}");
        assertThat(dryRun.get("summary").get("vouchersExcludedByUser").asInt()).isEqualTo(2);
    }

    @Test
    void rateReviewEndpointsAreTenantScopedAndCustomerUserBlocked() throws Exception {
        var registration = register("rate-review-security");
        var ownerToken = registration.get("accessToken").asText();
        var tenantId = UUID.fromString(registration.get("tenantId").asText());
        var workspace = uploadClassifyAndStage(ownerToken, "Secure rate review", fixture("excluded_invalid_rate_sale.xml"));
        var sessionId = workspace.get("testSessionId").asText();
        var issueId = findRateIssue(workspace, "S-EXCLUDE-1").get("id").asText();
        var body = "{\"action\":\"KEEP_UNRESOLVED\"}";
        var otherToken = register("rate-review-other-tenant").get("accessToken").asText();
        var customerToken = tokenForRole(tenantId, DomainEnums.Role.CUSTOMER_USER);

        mvc.perform(post("/api/import-sessions/{id}/rate-review/{issueId}/resolve", sessionId, issueId)
                .header("Authorization", "Bearer " + otherToken).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/import-sessions/{id}/rate-review/{issueId}/resolve", sessionId, issueId)
                .header("Authorization", "Bearer " + customerToken).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }

    private JsonNode findRateIssue(JsonNode workspace, String voucherNumber) {
        for (var issue : workspace.get("reviewItems")) {
            if (!("INVALID_RATE".equals(issue.get("code").asText())
                || "ZERO_RATE_ITEM".equals(issue.get("code").asText()))) continue;
            if (voucherNumber.equals(issue.path("context").path("voucherNumber").asText())) return issue;
        }
        throw new AssertionError("Rate review issue not found for voucher " + voucherNumber);
    }

    private int rateIssuesForProduct(JsonNode workspace, String productName) {
        var count = 0;
        for (var issue : workspace.get("reviewItems")) {
            if (("INVALID_RATE".equals(issue.get("code").asText())
                || "ZERO_RATE_ITEM".equals(issue.get("code").asText()))
                && productName.equals(issue.path("context").path("productName").asText())) count++;
        }
        return count;
    }

    private JsonNode findVoucherItem(JsonNode workspace, String productName) {
        for (var item : workspace.get("voucherItems")) {
            if (productName.equals(item.path("productName").asText())) return item;
        }
        throw new AssertionError("Voucher item not found for " + productName);
    }

    private TransferInventory createTransferInventory(String token, UUID tenantId) throws Exception {
        var product = postJson(token, "/api/products", """
            {"sku":"TRANSFER-001","name":"Transfer Product","unitCode":"NOS","defaultPurchasePrice":10,"defaultSalesPrice":15,"reorderPoint":5}
            """);
        var source = postJson(token, "/api/warehouses", "{\"name\":\"Warehouse East\",\"code\":\"WH-EAST\"}");
        var destination = postJson(token, "/api/warehouses", "{\"name\":\"Warehouse West\",\"code\":\"WH-WEST\"}");
        saveMovementAt(tenantId, product, source, "100", java.time.Instant.parse("2026-03-30T12:00:00Z"));
        saveMovementAt(tenantId, product, destination, "40", java.time.Instant.parse("2026-03-30T12:00:00Z"));
        saveTransferBaseline(tenantId, product, source, "100");
        saveTransferBaseline(tenantId, product, destination, "40");
        return new TransferInventory(product, source, destination);
    }

    private void saveTransferBaseline(UUID tenantId, JsonNode product, JsonNode warehouse, String quantity) {
        var baseline = new StockSnapshotBaseline();
        baseline.tenantId = tenantId;
        baseline.productId = UUID.fromString(product.get("id").asText());
        baseline.warehouseId = UUID.fromString(warehouse.get("id").asText());
        baseline.adjustmentWarehouseId = baseline.warehouseId;
        baseline.snapshotDate = LocalDate.of(2026, 3, 31);
        baseline.importedQuantity = new BigDecimal(quantity);
        snapshotBaselines.save(baseline);
    }

    private void assertTransferStock(UUID tenantId, TransferInventory setup, String source, String destination) {
        assertThat(stockMovements.currentStock(tenantId,
            UUID.fromString(setup.product().get("id").asText()),
            UUID.fromString(setup.source().get("id").asText()))).isEqualByComparingTo(source);
        assertThat(stockMovements.currentStock(tenantId,
            UUID.fromString(setup.product().get("id").asText()),
            UUID.fromString(setup.destination().get("id").asText()))).isEqualByComparingTo(destination);
    }

    private String transferResolution(JsonNode candidate, String confirmation) {
        return """
            {"action":"CONFIRM_WAREHOUSE_TRANSFER","productId":"%s","sourceWarehouseId":"%s","destinationWarehouseId":"%s","quantity":%s,"confirmation":"%s"}
            """.formatted(
            candidate.get("productId").asText(),
            candidate.get("sourceWarehouseId").asText(),
            candidate.get("destinationWarehouseId").asText(),
            candidate.get("normalizedQuantity").asText(),
            confirmation);
    }

    private record TransferInventory(JsonNode product, JsonNode source, JsonNode destination) { }

    private JsonNode stageUnmatchedCashbook(String token, String name) throws Exception {
        var sessionId = createSession(token, name).get("id").asText();
        upload(token, sessionId, fixture("cashbook_unmatched.csv"));
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var workspace = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        var result = mapper.createObjectNode();
        result.put("sessionId", sessionId);
        result.put("entryId", workspace.get("cashbookEntries").get(0).get("id").asText());
        return result;
    }

    private String cashbookResolution(String action, String targetId) {
        return "{\"action\":\"" + action + "\",\"targetId\":\"" + targetId
            + "\",\"confirmation\":\"POST PAYMENT\"}";
    }

    private SalesInvoice salesInvoice(UUID tenantId, UUID customerId, String number) {
        var invoice = new SalesInvoice();
        invoice.tenantId = tenantId;
        invoice.customerId = customerId;
        invoice.warehouseId = UUID.randomUUID();
        invoice.invoiceNumber = number;
        invoice.invoiceDate = LocalDate.of(2026, 6, 18);
        invoice.subtotal = new BigDecimal("500.00");
        invoice.totalAmount = new BigDecimal("500.00");
        return salesInvoices.save(invoice);
    }

    private PurchaseInvoice purchaseInvoice(UUID tenantId, UUID supplierId, String number) {
        var invoice = new PurchaseInvoice();
        invoice.tenantId = tenantId;
        invoice.supplierId = supplierId;
        invoice.warehouseId = UUID.randomUUID();
        invoice.invoiceNumber = number;
        invoice.invoiceDate = LocalDate.of(2026, 6, 18);
        invoice.subtotal = new BigDecimal("500.00");
        invoice.totalAmount = new BigDecimal("500.00");
        return purchaseInvoices.save(invoice);
    }

    private JsonNode register(String prefix) throws Exception {
        var response = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"businessName":"Smart Import Test","businessMode":"HYBRID","email":"%s-%s@example.com","password":"password123","fullName":"Owner"}
                    """.formatted(prefix, System.nanoTime())))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    private JsonNode createSession(String token, String name) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/import-sessions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private JsonNode upload(String token, String sessionId, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/import-sessions/{id}/files", sessionId);
        for (var file : files) {
            request.file(file);
        }
        request.header("Authorization", "Bearer " + token);
        return mapper.readTree(mvc.perform(request)
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private MockMultipartFile fixture(String name) throws IOException {
        var resource = new ClassPathResource("import-fixtures/smart-session/" + name);
        var contentType = name.endsWith(".csv") ? "text/csv" : "application/xml";
        return new MockMultipartFile("files", name, contentType, resource.getInputStream());
    }

    private JsonNode getJson(String token, String path) throws Exception {
        return mapper.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(String token, String path) throws Exception {
        return mapper.readTree(mvc.perform(post(path).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(String token, String path, String body) throws Exception {
        return mapper.readTree(mvc.perform(post(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private String commitBody(String dryRunId) {
        return "{\"dryRunId\":\"" + dryRunId + "\",\"confirmation\":\"COMMIT IMPORT PLAN\"}";
    }

    private JsonNode uploadClassifyAndStage(String token, String name, MockMultipartFile... files) throws Exception {
        var sessionId = createSession(token, name).get("id").asText();
        upload(token, sessionId, files);
        postJson(token, "/api/import-sessions/" + sessionId + "/classify");
        var workspace = postJson(token, "/api/import-sessions/" + sessionId + "/stage");
        ((com.fasterxml.jackson.databind.node.ObjectNode) workspace).put("testSessionId", sessionId);
        return workspace;
    }

    private String tokenForRole(UUID tenantId, DomainEnums.Role role) {
        var user = new UserAccount();
        user.email = role.name().toLowerCase() + "-smart-" + System.nanoTime() + "@example.com";
        user.fullName = "Smart Import " + role.name();
        user.passwordHash = passwordEncoder.encode("password123");
        users.save(user);

        var membership = new UserTenantMembership();
        membership.tenantId = tenantId;
        membership.userId = user.id;
        membership.role = role;
        memberships.save(membership);
        return jwtService.issue(user.id, tenantId, user.email, role);
    }

    private void saveMovementAt(UUID tenantId, JsonNode product, JsonNode warehouse, String quantity, java.time.Instant movementDate) {
        var movement = new StockMovement();
        movement.tenantId = tenantId;
        movement.productId = UUID.fromString(product.get("id").asText());
        movement.warehouseId = UUID.fromString(warehouse.get("id").asText());
        movement.movementType = DomainEnums.MovementType.ADJUSTMENT;
        movement.quantity = new java.math.BigDecimal(quantity);
        movement.baseQuantity = new java.math.BigDecimal(quantity);
        movement.rate = java.math.BigDecimal.TEN;
        movement.totalValue = movement.baseQuantity.abs().multiply(movement.rate);
        movement.movementDate = movementDate;
        movement.notes = "Dry-run dated stock baseline";
        stockMovements.save(movement);
    }
}
