package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.SmartImportCommit;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.repo.LegacyImportRawRowRepository;
import com.stockpilot.ai.service.SmartImportCommitExecutor;
import com.stockpilot.ai.service.SmartImportCommitService;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ImportReliabilityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Repositories.SalesInvoiceRepository invoices;
    @Autowired Repositories.SalesInvoiceItemRepository items;
    @Autowired Repositories.PurchaseInvoiceRepository purchases;
    @Autowired Repositories.CustomerRepository customers;
    @Autowired Repositories.ProductRepository products;
    @Autowired Repositories.SmartImportCommitRepository commits;
    @Autowired Repositories.ImportDryRunRepository dryRuns;
    @Autowired Repositories.MembershipRepository memberships;
    @Autowired LegacyImportRawRowRepository rawRows;
    @Autowired SmartImportCommitService commitService;
    @SpyBean SmartImportCommitExecutor executor;
    @AfterEach void clean() { TenantContext.clear(); reset(executor); }

    @Test void customColumnsRemainVisibleCanBeMappedAndCannotBeAccessedByAnotherTenant() throws Exception {
        var owner=register(); var token=owner.path("accessToken").asText();
        var id=classicUpload(token,"Article,Measure,Initial Qty\nCustom Widget,PCS,12\n");
        var preview=getJson(token,"/api/imports/"+id+"/preview");
        assertThat(preview.get(0).path("type").asText()).isEqualTo("UNMAPPED");
        assertThat(postJson(token,"/api/imports/"+id+"/validate","{}").path("errorCount").asInt()).isPositive();
        var other=register().path("accessToken").asText();
        mvc.perform(get("/api/imports/"+id+"/preview").header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        mvc.perform(post("/api/imports/"+id+"/mapping").header("Authorization","Bearer "+other).contentType(MediaType.APPLICATION_JSON).content("{\"mapping\":{\"productName\":\"Article\"}}"))
            .andExpect(status().isNotFound());
        postJson(token,"/api/imports/"+id+"/mapping","{\"mapping\":{\"productName\":\"Article\",\"unitCode\":\"Measure\",\"openingStock\":\"Initial Qty\"}}");
        assertThat(postJson(token,"/api/imports/"+id+"/validate","{}").path("errorCount").asInt()).isZero();
        postJson(token,"/api/imports/"+id+"/commit","{}");
        var catalog=getJson(token,"/api/products");
        assertThat(catalog.path("content").get(0).path("currentStock").asInt()).isEqualTo(12);
        assertThat(rawRows.findByTenantIdAndImportBatchIdOrderByRowNumberAsc(UUID.fromString(owner.path("tenantId").asText()),UUID.fromString(id))).hasSize(1);
    }

    @Test void repeatedAndExistingGstinReuseOneTenantPartyAndConflictingNamesAreBlocked() throws Exception {
        var owner=register(); var token=owner.path("accessToken").asText(); var tenant=UUID.fromString(owner.path("tenantId").asText());
        var csv="Party Name,GSTIN,Ledger Group\nSame Customer,27ABCDE1234F1Z5,Sundry Debtors\nSame Customer,27ABCDE1234F1Z5,Sundry Debtors\n";
        for (int attempt=0;attempt<2;attempt++) {
            var batch=classicUpload(token,csv);
            assertThat(postJson(token,"/api/imports/"+batch+"/validate","{}").path("errorCount").asInt()).isZero();
            postJson(token,"/api/imports/"+batch+"/commit","{}");
        }
        assertThat(customers.findByTenantIdAndGstinIgnoreCase(tenant,"27ABCDE1234F1Z5")).isPresent();
        assertThat(getJson(token,"/api/customers").path("totalElements").asInt()).isEqualTo(1);
        var bad=classicUpload(token,csv.replace("Same Customer","Conflicting Customer"));
        assertThat(postJson(token,"/api/imports/"+bad+"/validate","{}").path("errorCount").asInt()).isPositive();
    }

    @Test void overlappingExportsInEitherOrderPostOneUnchangedInvoice() throws Exception {
        for (boolean reverse:new boolean[]{false,true}) {
            var owner=register(); var token=owner.path("accessToken").asText(); prepare(token);
            var xml=saleXml(); var second=xml.replace("<BODY>","<BODY>\n ");
            var session=smartSession(token,reverse?second:xml,reverse?xml:second);
            var preview=postJson(token,"/api/import-sessions/"+session+"/dry-run","{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
            var result=postJson(token,"/api/import-sessions/"+session+"/commit",commitBody(preview));
            assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("COMMITTED");
            var reconciliation=getJson(token,"/api/import-sessions/"+session+"/reconciliation");
            assertThat(reconciliation.path("status").asText()).as(reconciliation.toString()).isEqualTo("MATCHED");
            var tenant=UUID.fromString(owner.path("tenantId").asText());
            assertThat(invoices.findByTenantId(tenant)).singleElement().satisfies(invoice -> {
                assertThat(invoice.totalAmount).isEqualByComparingTo("30");
                assertThat(items.findByTenantIdAndSalesInvoiceId(tenant,invoice.id)).hasSize(1);
            });
        }
    }

    @Test void purchaseImportIdentityIncludesSupplierAndFinancialYearAcrossReplays() throws Exception {
        var owner=register(); var token=owner.path("accessToken").asText(); prepare(token);
        postJson(token,"/api/warehouses","{\"name\":\"Main Godown\",\"code\":\"GODOWN\"}");
        var original=new String(new ClassPathResource("import-fixtures/smart-session/purchase.xml").getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        var otherSupplier=original.replace("Sample Supplier","Another Supplier");
        var earlierYear=original.replace("20260610","20250610");
        for(int attempt=0;attempt<2;attempt++) {
            var session=smartSession(token,original,otherSupplier,earlierYear);
            var preview=postJson(token,"/api/import-sessions/"+session+"/dry-run","{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
            var result=postJson(token,"/api/import-sessions/"+session+"/commit",commitBody(preview));
            assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("COMMITTED");
        }
        assertThat(purchases.findByTenantId(UUID.fromString(owner.path("tenantId").asText()))).hasSize(3)
            .allSatisfy(invoice -> assertThat(invoice.totalAmount).isEqualByComparingTo("100"));
    }

    @Test void overlappingDocumentsWithDifferentLinesRequireReviewEvenWhenTotalsMatch() throws Exception {
        var token=register().path("accessToken").asText(); prepare(token);
        var changed=saleXml().replace("2 PCS","3 PCS").replace("15/PCS","10/PCS");
        var session=smartSession(token,saleXml(),changed);
        var workspace=getJson(token,"/api/import-sessions/"+session);
        assertThat(workspace.path("issues").toString()).contains("DUPLICATE_VOUCHER_CONFLICT");
        var preview=postJson(token,"/api/import-sessions/"+session+"/dry-run","{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        assertThat(preview.path("summary").path("reviewRequiredCount").asInt()).isPositive();
    }

    @Test void finalTableBoundsAreReportedWithSourceRowsBeforeCommit() throws Exception {
        var token=register().path("accessToken").asText();
        var xml="<ENVELOPE><STOCKITEM NAME=\""+"X".repeat(221)+"\"><BASEUNITS>PCS</BASEUNITS></STOCKITEM></ENVELOPE>";
        var session=smartSession(token,xml);
        var workspace=getJson(token,"/api/import-sessions/"+session);
        assertThat(workspace.path("issues").toString()).contains("FINAL_FIELD_BOUNDS").contains("220 characters");
        var preview=postJson(token,"/api/import-sessions/"+session+"/dry-run","{\"strategy\":\"TRANSACTION_HISTORY\",\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        assertThat(preview.path("summary").path("blockingErrors").asInt()).isPositive();
    }

    @Test void processInterruptionRollsBackEveryBusinessWriteAndRetryCommitsOnce() throws Exception {
        var owner=register(); var token=owner.path("accessToken").asText(); prepare(token);
        var session=UUID.fromString(smartSession(token,saleXml()));
        var preview=postJson(token,"/api/import-sessions/"+session+"/dry-run","{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        var tenant=UUID.fromString(owner.path("tenantId").asText()); var user=ownerUser(tenant);
        doAnswer(call -> { call.callRealMethod(); throw new SimulatedProcessDeath(); }).when(executor).execute(any(),any(),any());
        TenantContext.set(tenant,user,DomainEnums.Role.OWNER);
        var request=new SmartImportDtos.CommitRequest(UUID.fromString(preview.path("id").asText()),SmartImportCommitService.CONFIRMATION);
        assertThatThrownBy(()->commitService.commit(session,request)).isInstanceOf(SimulatedProcessDeath.class);
        assertThat(invoices.findByTenantId(tenant)).isEmpty();
        assertThat(commits.findByTenantIdAndImportSessionId(tenant,session)).isEmpty();
        reset(executor);
        assertThat(commitService.commit(session,request).get("status").toString()).isEqualTo("COMMITTED");
        assertThat(commitService.commit(session,request).get("idempotentRetry")).isEqualTo(true);
        assertThat(invoices.findByTenantId(tenant)).hasSize(1);
    }

    @Test void strandedLegacyCommitRecoversOnlyUnderOwnershipAndConcurrentRetryPostsOnce() throws Exception {
        var owner=register(); var token=owner.path("accessToken").asText(); prepare(token);
        var session=UUID.fromString(smartSession(token,saleXml()));
        var preview=postJson(token,"/api/import-sessions/"+session+"/dry-run","{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
        var tenant=UUID.fromString(owner.path("tenantId").asText()); var user=ownerUser(tenant);
        var dryRun=UUID.fromString(preview.path("id").asText()); var stranded=new SmartImportCommit();
        stranded.tenantId=tenant; stranded.importSessionId=session; stranded.dryRunId=dryRun;
        stranded.strategy=dryRuns.findById(dryRun).orElseThrow().strategy; commits.save(stranded);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var executions=new AtomicInteger();
        doAnswer(call -> { executions.incrementAndGet(); entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return call.callRealMethod(); })
            .when(executor).execute(any(),any(),any());
        var request=new SmartImportDtos.CommitRequest(dryRun,SmartImportCommitService.CONFIRMATION);
        try (var pool=Executors.newFixedThreadPool(2)) {
            Callable<java.util.Map<String,Object>> action=()->{TenantContext.set(tenant,user,DomainEnums.Role.OWNER);try{return commitService.commit(session,request);}finally{TenantContext.clear();}};
            var first=pool.submit(action); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(action); release.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS).get("status").toString()).isEqualTo("COMMITTED");
            assertThat(second.get(10,TimeUnit.SECONDS).get("idempotentRetry")).isEqualTo(true);
            assertThat(executions).hasValue(1);
            assertThat(invoices.findByTenantId(tenant)).hasSize(1);
        }
    }

    private static class SimulatedProcessDeath extends Error { }
    private UUID ownerUser(UUID tenant) { return memberships.findByTenantId(tenant).getFirst().userId; }
    private void prepare(String token) throws Exception {
        postJson(token,"/api/warehouses","{\"name\":\"Main Location\",\"code\":\"MAIN\"}");
        postJson(token,"/api/products","{\"name\":\"Sample Item A\",\"unitCode\":\"PCS\",\"defaultPurchasePrice\":10,\"defaultSalesPrice\":15}");
    }
    private String saleXml() throws Exception { return new String(new ClassPathResource("import-fixtures/smart-session/sales.xml").getInputStream().readAllBytes(),StandardCharsets.UTF_8); }
    private String smartSession(String token,String... documents) throws Exception {
        var id=postJson(token,"/api/import-sessions","{\"name\":\"Reliability regression\"}").path("id").asText();
        var upload=multipart("/api/import-sessions/"+id+"/files");
        upload.header("Authorization","Bearer "+token);
        for(int i=0;i<documents.length;i++) upload.file(new MockMultipartFile("files","source-"+i+".xml","application/xml",documents[i].getBytes(StandardCharsets.UTF_8)));
        mvc.perform(upload).andExpect(status().isOk());
        postJson(token,"/api/import-sessions/"+id+"/classify","{}"); postJson(token,"/api/import-sessions/"+id+"/stage","{}"); return id;
    }
    private String classicUpload(String token,String csv) throws Exception {
        return mapper.readTree(mvc.perform(multipart("/api/imports/upload").file(new MockMultipartFile("file","source.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8)))
            .param("sourceType","CSV").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("batchId").asText();
    }
    private String commitBody(JsonNode preview) { return "{\"dryRunId\":\""+preview.path("id").asText()+"\",\"confirmation\":\"COMMIT IMPORT PLAN\"}"; }
    private JsonNode register() throws Exception { return postJson(null,"/api/auth/register","{\"businessName\":\"Import regression\",\"businessMode\":\"HYBRID\",\"email\":\"import-"+UUID.randomUUID()+"@example.com\",\"password\":\"password123\",\"fullName\":\"Owner\"}"); }
    private JsonNode postJson(String token,String path,String body) throws Exception { var request=post(path).contentType(MediaType.APPLICATION_JSON).content(body); if(token!=null)request.header("Authorization","Bearer "+token);return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    private JsonNode getJson(String token,String path) throws Exception { return mapper.readTree(mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
}
