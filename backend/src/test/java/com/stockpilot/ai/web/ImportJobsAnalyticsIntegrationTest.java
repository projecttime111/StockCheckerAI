package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.*;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.repo.*;
import com.stockpilot.ai.service.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ImportJobsAnalyticsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired Repositories.TenantRepository tenants;
    @Autowired Repositories.UserRepository users;
    @Autowired Repositories.MembershipRepository members;
    @Autowired Repositories.ImportSessionRepository sessions;
    @Autowired Repositories.ImportBatchRepository batches;
    @Autowired Repositories.ProductRepository products;
    @Autowired Repositories.UnitRepository units;
    @Autowired Repositories.SalesInvoiceRepository invoices;
    @Autowired Repositories.SalesInvoiceItemRepository items;
    @Autowired Repositories.StockMovementRepository movements;
    @Autowired Repositories.WarehouseRepository warehouses;
    @Autowired ImportJobRepository jobRows;
    @Autowired ImportJobService jobs;
    @Autowired SmartImportSessionService sessionService;
    @Autowired SmartImportStagingService staging;
    @Autowired SmartImportDryRunService dryRuns;
    @Autowired SmartImportCommitService commits;
    @Autowired ForecastService forecasts;
    @Autowired SalesHistoryService sales;
    @Autowired AnalyticsReadinessService readiness;
    @Autowired ProfitInsightService profit;
    @Autowired AccountLifecycleService lifecycle;
    private final List<UUID> ownedJobs=new ArrayList<>();
    private final LocalDate today=LocalDate.now(ZoneOffset.UTC);

    @AfterEach void clear(){TenantContext.clear(); ownedJobs.forEach(jobRows::deleteById);}

    @Test void idempotencyRejectsChangedPayloadAndJobReadsStayTenantScoped() throws Exception {
        var f=fixture();var session=session(f);var request=UUID.randomUUID();
        var first=submit(f,session,"CLASSIFY",request,Map.of());
        assertThat(submit(f,session,"CLASSIFY",request,Map.of()).path("id").asText()).isEqualTo(first.path("id").asText());
        mvc.perform(post(path(session)).header("Authorization","Bearer "+f.token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("operation","STAGE","requestId",request)))).andExpect(status().isConflict());
        var other=fixture();
        mvc.perform(get(path(session)+"/"+first.path("id").asText()).header("Authorization","Bearer "+other.token)).andExpect(status().isNotFound());
        mvc.perform(get(path(session)).header("Authorization","Bearer "+other.token)).andExpect(status().isNotFound());
        context(f);var claimed=jobs.claim("first-worker");jobs.finish(claimed.id,"first-worker",Map.of("processed",true),null);
        assertThat(submit(f,session,"CLASSIFY",request,Map.of()).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(jobRows.findByTenantIdAndImportSessionIdOrderByCreatedAtDesc(f.tenant,session,org.springframework.data.domain.PageRequest.of(0,20))).hasSize(1);
    }

    @Test void expiredLeaseRecoversAndOldWorkerCannotOverwriteNewCompletion() throws Exception {
        var f=fixture();var session=session(f);submit(f,session,"CLASSIFY",UUID.randomUUID(),Map.of());
        var first=jobs.claim("dead-worker");assertThat(first.attempts).isEqualTo(1);
        first.leaseUntil=Instant.now().minusSeconds(1);jobRows.saveAndFlush(first);
        var recovered=jobs.claim("replacement");assertThat(recovered.id).isEqualTo(first.id);assertThat(recovered.attempts).isEqualTo(2);
        jobs.finish(first.id,"dead-worker",Map.of("wrong",true),null);
        assertThat(jobRows.findById(first.id).orElseThrow().status).isEqualTo("RUNNING");
        jobs.finish(first.id,"replacement",Map.of("recovered",true),null);
        context(f);assertThat(jobs.get(session,first.id).get("result")).isEqualTo(Map.of("recovered",true));
    }

    @Test void retriesBackOffAndStopAfterThreeAttempts() throws Exception {
        var f=fixture();var session=session(f);var submitted=submit(f,session,"CLASSIFY",UUID.randomUUID(),Map.of());
        var id=UUID.fromString(submitted.path("id").asText());
        for(int attempt=1;attempt<=3;attempt++){
            var job=jobs.claim("retry-worker");assertThat(job.attempts).isEqualTo(attempt);
            jobs.finish(id,"retry-worker",Map.of(),new IllegalStateException("temporary storage failure"));
            var stored=jobRows.findById(id).orElseThrow();
            if(attempt<3){assertThat(stored.status).isEqualTo("QUEUED");assertThat(jobs.claim("too-soon")).isNull();stored.leaseUntil=Instant.now().minusSeconds(1);jobRows.saveAndFlush(stored);}
            else assertThat(stored.status).isEqualTo("FAILED");
        }
    }

    @Test void revokedPermissionIsCheckedWhenTheWorkerExecutes() throws Exception {
        var f=fixture();var session=session(f);var submitted=submit(f,session,"STAGE",UUID.randomUUID(),Map.of());
        var member=members.findByTenantIdAndUserId(f.tenant,f.user).orElseThrow();member.role=DomainEnums.Role.VIEWER;members.saveAndFlush(member);
        worker().process();
        var stored=jobRows.findById(UUID.fromString(submitted.path("id").asText())).orElseThrow();
        assertThat(stored.status).isEqualTo("FAILED");assertThat(stored.error).contains("permission");
        assertThat(products.findByTenantIdAndActiveTrueOrderByNameAsc(f.tenant)).isEmpty();
        assertThat(sessions.findByTenantIdAndId(f.tenant,session).orElseThrow().status).isEqualTo(DomainEnums.ImportSessionStatus.CREATED);
    }

    @Test void realExcelMovesThroughDurableJobsAndReconcilesWithInvoicesAndDemand() throws Exception {
        var f=fixture();
        postJson(f,"/api/warehouses",Map.of("name","Main Location","code","MAIN"));
        postJson(f,"/api/products",Map.of("name","Sample Item A","unitCode","PCS","defaultPurchasePrice",10,"defaultSalesPrice",15));
        var session=session(f);
        mvc.perform(multipart("/api/import-sessions/"+session+"/files").file(new MockMultipartFile("files","sales.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook()))
            .header("Authorization","Bearer "+f.token)).andExpect(status().isOk());
        execute(f,session,"CLASSIFY",Map.of());execute(f,session,"STAGE",Map.of());
        var preview=execute(f,session,"DRY_RUN",Map.of("strategy","TRANSACTION_HISTORY","stockImpactMode","CREATE_INVOICES_ONLY")).path("result");
        assertThat(preview.path("summary").path("blockingErrors").asInt()).as(preview.toPrettyString()).isZero();
        assertThat(getJson(f,"/api/import-sessions/"+session+"/reconciliation").path("status").asText()).isEqualTo("NOT_COMMITTED");
        var committed=execute(f,session,"COMMIT",Map.of("dryRunId",preview.path("id").asText(),"confirmation","COMMIT IMPORT PLAN"));
        assertThat(committed.path("result").path("analyticsRefresh").asText()).isEqualTo("SUCCEEDED");
        var reconciliation=getJson(f,"/api/import-sessions/"+session+"/reconciliation?size=2");
        assertThat(reconciliation.path("status").asText()).as(reconciliation.toPrettyString()).isEqualTo("MATCHED");
        assertThat(reconciliation.path("totalChecks").asInt()).isEqualTo(4);assertThat(reconciliation.path("checks").size()).isEqualTo(2);
        var total=reconciliation.path("checks").get(1);assertThat(total.path("sourceValue").decimalValue()).isEqualByComparingTo("75");
        assertThat(total.path("committedValue").decimalValue()).isEqualByComparingTo("75");
        assertThat(getJson(f,"/api/import-sessions/"+session+"/reconciliation?page=1&size=2").path("checks").size()).isEqualTo(2);
        assertThat(invoices.findByTenantId(f.tenant)).hasSize(2);
        context(f);var product=products.findByTenantIdAndActiveTrueOrderByNameAsc(f.tenant).getFirst();
        assertThat(sales.history(f.tenant).quantity(product.id,null,today.minusDays(1),today)).isEqualByComparingTo("5");
        assertThat(forecasts.results().getFirst().coverageDays()).isEqualTo(2);
        assertThat(forecasts.results().getFirst().averageDailyDemand()).isEqualByComparingTo("2.5");
        assertThat(readiness.overview().get("forecastStale")).isEqualTo(false);
        readiness.confirm(today.minusDays(1),today,true);
        assertThat(postJson(f,"/api/ai/assistant/chat",Map.of("message","What is my sales summary?","requestId",UUID.randomUUID())).path("conversationId").asText()).isNotBlank();
        context(f);lifecycle.resetCurrentWorkspace("RESET WORKSPACE DATA");
        assertThat(jobRows.findByTenantIdAndImportSessionIdOrderByCreatedAtDesc(f.tenant,session,org.springframework.data.domain.PageRequest.of(0,20))).isEmpty();
        assertThat(getJson(f,"/api/ai/conversations").path("totalElements").asLong()).isZero();
    }

    @Test void confirmedShortAndHistoricalCoverageUsesItsActualDenominator() {
        var f=fixture();context(f);var product=product(f);
        sale(f,product,today.minusDays(9),new BigDecimal("30"),new BigDecimal("300"));
        readiness.confirm(today.minusDays(29),today,true);
        var row=forecasts.run().getFirst();assertThat(row.coverageDays()).isEqualTo(30);assertThat(row.averageDailyDemand()).isEqualByComparingTo("1");
        assertThat(row.coverageConfirmed()).isTrue();
        var oldEnd=today.minusYears(2);sale(f,product,oldEnd.minusDays(9),new BigDecimal("20"),new BigDecimal("200"));
        readiness.confirm(oldEnd.minusDays(9),oldEnd,true);
        row=forecasts.run().getFirst();assertThat(row.coverageDays()).isEqualTo(10);assertThat(row.averageDailyDemand()).isEqualByComparingTo("2");
        assertThat(readiness.overview().get("warnings").toString()).contains("Sales history ends");
        assertThat(forecasts.suggestions().getFirst().reason()).contains("10 observed days");
    }

    @Test void smartImportItemLinkedMovementsDoNotDoubleCountInvoiceDemand() {
        var f=fixture();context(f);var product=product(f);
        sale(f,product,today,new BigDecimal("3"),new BigDecimal("30"));
        var invoice=invoices.findByTenantId(f.tenant).getFirst();var item=items.findByTenantIdAndSalesInvoiceId(f.tenant,invoice.id).getFirst();
        var warehouse=new Warehouse();warehouse.tenantId=f.tenant;warehouse.name="Main";warehouses.saveAndFlush(warehouse);
        invoice.warehouseId=warehouse.id;invoices.saveAndFlush(invoice);
        var movement=new StockMovement();movement.tenantId=f.tenant;movement.productId=product.id;movement.warehouseId=warehouse.id;
        movement.unitId=product.baseUnitId;movement.movementType=DomainEnums.MovementType.SALE;movement.quantity=new BigDecimal("3");movement.baseQuantity=new BigDecimal("-3");
        movement.referenceType="SALES_INVOICE_ITEM";movement.referenceId=item.id;movements.saveAndFlush(movement);
        assertThat(sales.history(f.tenant).quantity(product.id,null,today,today)).isEqualByComparingTo("3");
        assertThat(sales.history(f.tenant).quantity(product.id,warehouse.id,today,today)).isEqualByComparingTo("3");
        var branch=new Warehouse();branch.tenantId=f.tenant;branch.name="Branch";warehouses.saveAndFlush(branch);
        item.warehouseId=branch.id;items.saveAndFlush(item);
        assertThat(sales.history(f.tenant).quantity(product.id,warehouse.id,today,today)).isZero();
        assertThat(sales.history(f.tenant).quantity(product.id,branch.id,today,today)).isEqualByComparingTo("3");
    }

    @Test void readinessMarksCostsAndClassicImportsAndDoesNotLeakCostsToWarehouseRole() throws Exception {
        var f=fixture();context(f);var product=product(f);sale(f,product,today,BigDecimal.ONE,BigDecimal.TEN);
        readiness.confirm(today.minusDays(29),today,true);forecasts.run();
        assertThat(readiness.overview().get("estimatedCostLines")).isEqualTo(1L);
        var batch=new ImportBatch();batch.tenantId=f.tenant;batch.sourceType=DomainEnums.SourceType.EXCEL;batch.committedAt=Instant.now().plusSeconds(1);batches.saveAndFlush(batch);
        assertThat(readiness.overview().get("forecastStale")).isEqualTo(true);assertThat(readiness.overview().get("latestImportAt")).isNotNull();
        var member=members.findByTenantIdAndUserId(f.tenant,f.user).orElseThrow();member.role=DomainEnums.Role.WAREHOUSE_STAFF;members.saveAndFlush(member);
        var warehouse=new Fixture(f.tenant,f.user,jwt.issue(f.user,f.tenant,users.findById(f.user).orElseThrow().email,DomainEnums.Role.WAREHOUSE_STAFF));
        var response=getJson(warehouse,"/api/analytics/readiness");
        assertThat(response.path("estimatedCostLines").isNull()).isTrue();assertThat(response.path("uncostedSalesLines").isNull()).isTrue();
        mvc.perform(put("/api/analytics/coverage").header("Authorization","Bearer "+warehouse.token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("from",today.minusDays(29),"through",today,"complete",true)))).andExpect(status().isForbidden());
        var other=fixture();assertThat(getJson(other,"/api/analytics/readiness").path("status").asText()).isEqualTo("NO_DATA");
    }

    @Test void profitComparesEqualElapsedMonthPeriods() {
        var f=fixture();context(f);var product=product(f);var prior=today.withDayOfMonth(1).minusMonths(1);
        sale(f,product,prior,BigDecimal.ONE,new BigDecimal("100"));sale(f,product,today,BigDecimal.ONE,new BigDecimal("200"));
        if(today.getDayOfMonth()<prior.lengthOfMonth())sale(f,product,prior.withDayOfMonth(prior.lengthOfMonth()),BigDecimal.ONE,new BigDecimal("9999"));
        var evidence=profit.currentMonth().evidence();
        assertThat(evidence.get("previousMonthRevenue")).isEqualTo(new BigDecimal("100.00"));
        assertThat(evidence.get("previousPeriodThrough")).isEqualTo(prior.withDayOfMonth(Math.min(today.getDayOfMonth(),prior.lengthOfMonth())));
        assertThat(evidence.get("currentMonthRevenue")).isEqualTo(new BigDecimal("200.00"));
    }

    private Fixture fixture(){
        var tenant=new Tenant();tenant.name="Jobs "+UUID.randomUUID();tenants.saveAndFlush(tenant);
        var user=new UserAccount();user.email=UUID.randomUUID()+"@example.com";user.fullName="Owner";user.passwordHash="unused";users.saveAndFlush(user);
        var member=new UserTenantMembership();member.tenantId=tenant.id;member.userId=user.id;member.role=DomainEnums.Role.OWNER;members.saveAndFlush(member);
        return new Fixture(tenant.id,user.id,jwt.issue(user.id,tenant.id,user.email,member.role));
    }
    private Product product(Fixture f){var unit=new UnitOfMeasure();unit.tenantId=f.tenant;unit.code="PCS";unit.name="Pieces";units.saveAndFlush(unit);var p=new Product();p.tenantId=f.tenant;p.name="Widget";p.normalizedName="WIDGET";p.baseUnitId=unit.id;p.defaultPurchasePrice=BigDecimal.ONE;products.saveAndFlush(p);return p;}
    private void sale(Fixture f,Product p,LocalDate date,BigDecimal qty,BigDecimal revenue){var invoice=new SalesInvoice();invoice.tenantId=f.tenant;invoice.invoiceDate=date;var warehouse=warehouses.findByTenantIdAndActiveTrueOrderByNameAsc(f.tenant).stream().findFirst().orElseGet(()->{var w=new Warehouse();w.tenantId=f.tenant;w.name="Fixture warehouse";return warehouses.saveAndFlush(w);});invoice.warehouseId=warehouse.id;invoice.invoiceNumber=UUID.randomUUID().toString();invoice.subtotal=revenue;invoice.totalAmount=revenue;invoices.saveAndFlush(invoice);var item=new SalesInvoiceItem();item.tenantId=f.tenant;item.salesInvoiceId=invoice.id;item.productId=p.id;item.unitId=p.baseUnitId;item.quantity=qty;item.rate=revenue.divide(qty,6,java.math.RoundingMode.HALF_UP);item.lineTotal=revenue;item.costRate=BigDecimal.ONE;items.saveAndFlush(item);}
    private UUID session(Fixture f){var s=new ImportSession();s.tenantId=f.tenant;s.name="Durable import";sessions.saveAndFlush(s);return s.id;}
    private void context(Fixture f){TenantContext.set(f.tenant,f.user,DomainEnums.Role.OWNER);}
    private String path(UUID session){return "/api/import-sessions/"+session+"/jobs";}
    private JsonNode submit(Fixture f,UUID session,String operation,UUID request,Map<String,Object> payload)throws Exception {var r=postJson(f,path(session),Map.of("operation",operation,"requestId",request,"payload",payload));var id=UUID.fromString(r.path("id").asText());if(!ownedJobs.contains(id))ownedJobs.add(id);return r;}
    private JsonNode execute(Fixture f,UUID session,String operation,Map<String,Object> payload)throws Exception {var submitted=submit(f,session,operation,UUID.randomUUID(),payload);worker().process();var result=getJson(f,path(session)+"/"+submitted.path("id").asText());assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("SUCCEEDED");return result;}
    private ImportJobWorker worker(){return new ImportJobWorker(jobs,sessionService,staging,dryRuns,commits,forecasts);}
    private JsonNode postJson(Fixture f,String path,Object body)throws Exception{return json.readTree(mvc.perform(post(path).header("Authorization","Bearer "+f.token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private JsonNode getJson(Fixture f,String path)throws Exception{return json.readTree(mvc.perform(get(path).header("Authorization","Bearer "+f.token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private byte[] workbook()throws Exception {try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()){var sheet=workbook.createSheet("Sales");String[] headers={"Voucher Type","Voucher Number","Date","Party Name","Product Name","Quantity","Rate","Amount","Unit","Warehouse"};var header=sheet.createRow(0);for(int c=0;c<headers.length;c++)header.createCell(c).setCellValue(headers[c]);for(int r=1;r<=2;r++){var row=sheet.createRow(r);String[] values={"Sales","XLS-"+r,today.minusDays(2-r).toString(),"Sample Customer","Sample Item A",String.valueOf(r+1),"15",String.valueOf((r+1)*15),"PCS","Main Location"};for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);}workbook.write(out);return out.toByteArray();}}
    private record Fixture(UUID tenant,UUID user,String token){}
}
