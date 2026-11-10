package com.stockpilot.ai.web;
import com.fasterxml.jackson.databind.*;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.repo.Repositories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.mock.mockito.SpyBean;
import com.stockpilot.ai.service.SourceDocumentRevisionService;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class SourceDocumentRevisionIntegrationTest {
 @SpyBean SourceDocumentRevisionService sourceRevisions;
 @AfterEach void resetSpy() { reset(sourceRevisions); }
 @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
 @Autowired Repositories.SalesInvoiceRepository invoices;
 @Autowired Repositories.MembershipRepository members;
 @Autowired Repositories.SmartStagedVoucherItemRepository stagedItems;
 @Autowired Repositories.SalesInvoiceItemRepository items;
 @Autowired Repositories.CustomerPaymentRepository payments;
 @Autowired Repositories.StockMovementRepository movements;
 @Autowired Repositories.ProductRepository products;
 @Autowired Repositories.WarehouseRepository warehouses;
 @Autowired Repositories.StockSnapshotBaselineRepository baselines;
 private JsonNode commit(String token,String session) throws Exception {var preview=postJson(token,"/api/import-sessions/"+session+"/dry-run","{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");return postJson(token,"/api/import-sessions/"+session+"/commit",commitBody(preview));}
 private JsonNode revision(String token,String session) throws Exception {return getJson(token,"/api/import-sessions/"+session+"/document-revisions").path("items").get(0);}
 private void review(String token,String session,JsonNode item) throws Exception { postJson(token,"/api/import-sessions/"+session+"/document-revisions/"+item.path("voucherId").asText()+"/review",mapper.writeValueAsString(Map.of("action",item.path("outcome").asText().equals("CANCELLED")?"CANCEL_DOCUMENT":"APPLY_CORRECTION","reason","Corrected source export verified against invoice","expectedSourceHash",item.path("sourceHash").asText(),"expectedDestinationHash",item.path("destinationHash").asText(),"confirmation","APPLY DOCUMENT REVISION"))); }
 @Test void sameTotalLineChangeNeedsReviewThenCorrectsAndReplaysOnce() throws Exception {
  var owner=register();var token=owner.path("accessToken").asText();var tenant=UUID.fromString(owner.path("tenantId").asText());prepare(token);
  assertThat(commit(token,smartSession(token,saleXml())).path("status").asText()).isEqualTo("COMMITTED");
  var changed=saleXml().replace("2 PCS","3 PCS").replace("15/PCS","10/PCS");var session=smartSession(token,changed);var preview=revision(token,session);
  assertThat(preview.path("outcome").asText()).isEqualTo("CHANGED");assertThat(preview.path("eligible").asBoolean()).as(preview.toString()).isTrue();
  var dry=postJson(token,"/api/import-sessions/"+session+"/dry-run","{\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}"); assertThat(dry.path("summary").path("reviewRequiredCount").asInt()).isPositive();
  review(token,session,preview);assertThat(commit(token,session).path("status").asText()).isEqualTo("COMMITTED");
  var invoice=invoices.findByTenantId(tenant).getFirst();assertThat(invoice.totalAmount).isEqualByComparingTo("30");assertThat(items.findByTenantIdAndSalesInvoiceId(tenant,invoice.id)).singleElement().satisfies(i->assertThat(i.quantity).isEqualByComparingTo("3"));
  assertThat(commit(token,smartSession(token,changed)).path("status").asText()).isEqualTo("COMMITTED");assertThat(invoices.findByTenantId(tenant)).hasSize(1);
 }
 @Test void cancellationArchivesAndRemovesAmountsOnlyAfterReview() throws Exception {
  var owner=register();var token=owner.path("accessToken").asText();var tenant=UUID.fromString(owner.path("tenantId").asText());prepare(token);commit(token,smartSession(token,saleXml()));
  var session=smartSession(token,saleXml().replace("<VOUCHER>","<VOUCHER><ISCANCELLED>Yes</ISCANCELLED>"));var preview=revision(token,session);assertThat(preview.path("outcome").asText()).isEqualTo("CANCELLED");review(token,session,preview);assertThat(commit(token,session).path("status").asText()).isEqualTo("COMMITTED");
  var invoice=invoices.findByTenantId(tenant).getFirst();assertThat(invoice.totalAmount).isZero();assertThat(invoice.financialMetadata.get("sourceDocumentState")).isEqualTo("CANCELLED");assertThat(items.findByTenantIdAndSalesInvoiceId(tenant,invoice.id)).isEmpty();
 }
 @Test void paidInvoiceAndOtherTenantCannotApproveRevision() throws Exception {
  var owner=register();var token=owner.path("accessToken").asText();var tenant=UUID.fromString(owner.path("tenantId").asText());prepare(token);commit(token,smartSession(token,saleXml()));
  var invoice=invoices.findByTenantId(tenant).getFirst();var payment=new CustomerPayment();payment.tenantId=tenant;payment.customerId=invoice.customerId;payment.salesInvoiceId=invoice.id;payment.amount=BigDecimal.ONE;payment.paymentDate=java.time.LocalDate.of(2026,6,15);payments.save(payment);
  var session=smartSession(token,saleXml().replace("2 PCS","3 PCS"));var preview=revision(token,session);assertThat(preview.path("eligible").asBoolean()).isFalse();assertThat(preview.path("blockingReason").asText()).contains("payments");
  var other=register().path("accessToken").asText();mvc.perform(get("/api/import-sessions/"+session+"/document-revisions").header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
 }
 @Test void reviewedStockRevisionReversesAndReplacesOnceAndCancellationRestoresStock() throws Exception {
  var owner=register();var token=owner.path("accessToken").asText();var tenant=UUID.fromString(owner.path("tenantId").asText());prepare(token);
  var product=products.findByTenantIdAndActiveTrueOrderByNameAsc(tenant).getFirst();var warehouse=warehouses.findByTenantIdAndNameIgnoreCase(tenant,"Main Location").orElseThrow();
  var opening=new StockMovement();opening.tenantId=tenant;opening.productId=product.id;opening.warehouseId=warehouse.id;opening.unitId=product.baseUnitId;opening.movementType=DomainEnums.MovementType.OPENING_BALANCE;opening.quantity=BigDecimal.TEN;opening.baseQuantity=BigDecimal.TEN;opening.movementDate=java.time.Instant.parse("2026-06-14T00:00:00Z");movements.save(opening);
  var baseline=new StockSnapshotBaseline();baseline.tenantId=tenant;baseline.productId=product.id;baseline.warehouseId=warehouse.id;baseline.adjustmentWarehouseId=warehouse.id;baseline.snapshotDate=java.time.LocalDate.of(2026,6,14);baseline.importedQuantity=BigDecimal.TEN;baseline.adjustmentMovementId=opening.id;baselines.save(baseline);
  var first=smartSession(token,saleXml());var dry=postJson(token,"/api/import-sessions/"+first+"/dry-run","{\"stockImpactMode\":\"APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE\"}");
  assertThat(postJson(token,"/api/import-sessions/"+first+"/commit",commitBody(dry)).path("status").asText()).isEqualTo("COMMITTED");
  var changed=saleXml().replace("2 PCS","3 PCS").replace("15/PCS","10/PCS");var correction=smartSession(token,changed);var preview=revision(token,correction);assertThat(preview.path("stockMovementCount").asInt()).isEqualTo(1);assertThat(preview.path("eligible").asBoolean()).as(preview.toString()).isTrue();review(token,correction,preview);
  assertThat(commit(token,correction).path("status").asText()).isEqualTo("COMMITTED");
  assertThat(getJson(token,"/api/products").path("content").get(0).path("currentStock").decimalValue()).isEqualByComparingTo("7");
  var cancellation=smartSession(token,changed.replace("<VOUCHER>","<VOUCHER><ISCANCELLED>Yes</ISCANCELLED>"));review(token,cancellation,revision(token,cancellation));assertThat(commit(token,cancellation).path("status").asText()).isEqualTo("COMMITTED");
  assertThat(getJson(token,"/api/products").path("content").get(0).path("currentStock").decimalValue()).isEqualByComparingTo("10");
 }
 @Test void companyConflictsAndExternalIdentityRenumberingAreBlocked() throws Exception {
  var owner=register();var token=owner.path("accessToken").asText();prepare(token);
  var xml=saleXml().replace("<BODY>","<BODY><SVCURRENTCOMPANY>Company A</SVCURRENTCOMPANY>").replace("<VOUCHER>","<VOUCHER GUID='stable-voucher'><ALTERID>1</ALTERID>");
  assertThat(commit(token,smartSession(token,xml)).path("status").asText()).isEqualTo("COMMITTED");
  var otherCompany=revision(token,smartSession(token,xml.replace("Company A","Company B")));assertThat(otherCompany.path("eligible").asBoolean()).isFalse();assertThat(otherCompany.path("blockingReason").asText()).contains("company");
  var renamed=revision(token,smartSession(token,xml.replace("S-001","S-002").replace("<ALTERID>1","<ALTERID>2")));assertThat(renamed.path("outcome").asText()).isEqualTo("CHANGED");assertThat(renamed.path("eligible").asBoolean()).isFalse();
 }
 @Test void revisionFailureRollsBackAndRetryAppliesOnlyOnce() throws Exception {
  var owner=register();var token=owner.path("accessToken").asText();var tenant=UUID.fromString(owner.path("tenantId").asText());prepare(token);commit(token,smartSession(token,saleXml()));
  var session=smartSession(token,saleXml().replace("2 PCS","3 PCS").replace("15/PCS","10/PCS"));review(token,session,revision(token,session));
  doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("simulated failure after revision writes");}).when(sourceRevisions).applyIfReviewed(any(),any());
  assertThat(commit(token,session).path("status").asText()).isEqualTo("FAILED");
  var invoice=invoices.findByTenantId(tenant).getFirst();assertThat(items.findByTenantIdAndSalesInvoiceId(tenant,invoice.id)).singleElement().satisfies(i->assertThat(i.quantity).isEqualByComparingTo("2"));
  reset(sourceRevisions);assertThat(commit(token,session).path("status").asText()).isEqualTo("COMMITTED");assertThat(items.findByTenantIdAndSalesInvoiceId(tenant,invoice.id)).singleElement().satisfies(i->assertThat(i.quantity).isEqualByComparingTo("3"));
 }
 @Test void classicAndSmartMastersPersistExplicitPriceAndCombinedGst() throws Exception {
  var xml="<ENVELOPE><STOCKITEM NAME='GST Widget'><BASEUNITS>PCS</BASEUNITS><PARENT>Goods</PARENT><RATEOFVAT>12.5</RATEOFVAT><STANDARDPRICELIST.LIST><DATE>20250101</DATE><RATE>125.50/PCS</RATE></STANDARDPRICELIST.LIST><GSTDETAILS.LIST><APPLICABLEFROM>20250101</APPLICABLEFROM><RATEDETAILS.LIST><GSTRATEDUTYHEAD>CGST</GSTRATEDUTYHEAD><GSTRATE>9</GSTRATE></RATEDETAILS.LIST><RATEDETAILS.LIST><GSTRATEDUTYHEAD>SGST/UTGST</GSTRATEDUTYHEAD><GSTRATE>9</GSTRATE></RATEDETAILS.LIST></GSTDETAILS.LIST></STOCKITEM></ENVELOPE>";
  for(boolean smart:new boolean[]{false,true}) {
   var owner=register();var token=owner.path("accessToken").asText();var tenant=UUID.fromString(owner.path("tenantId").asText());
   if(smart) {
    var session=smartSession(token,xml);var preview=postJson(token,"/api/import-sessions/"+session+"/dry-run","{\"strategy\":\"SNAPSHOT_FIRST\",\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
    assertThat(postJson(token,"/api/import-sessions/"+session+"/commit",commitBody(preview)).path("status").asText()).isEqualTo("COMMITTED");
   }
   else {
    var batch=mapper.readTree(mvc.perform(multipart("/api/imports/upload").file(new MockMultipartFile("file","masters.xml","application/xml",xml.getBytes(StandardCharsets.UTF_8))).param("sourceType","TALLY_XML").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("batchId").asText();
    assertThat(postJson(token,"/api/imports/"+batch+"/validate","{}").path("errorCount").asInt()).isZero();postJson(token,"/api/imports/"+batch+"/commit","{}");
   }
   assertThat(products.findByTenantIdAndActiveTrueOrderByNameAsc(tenant)).singleElement().satisfies(p->{assertThat(p.defaultSalesPrice).isEqualByComparingTo("125.50");assertThat(p.gstPercentage).isEqualByComparingTo("18");});
  }
 }
 @Test void snapshotReconciliationUsesIndependentSourceQuantityAndThousandthTolerance() throws Exception {
  var owner=register();var token=owner.path("accessToken").asText();var tenant=UUID.fromString(owner.path("tenantId").asText());prepare(token);
  var xml=new String(new ClassPathResource("import-fixtures/smart-session/changed_closing_stock_snapshot_120.xml").getInputStream().readAllBytes(),StandardCharsets.UTF_8);
  var session=smartSession(token,xml);var preview=postJson(token,"/api/import-sessions/"+session+"/dry-run","{\"strategy\":\"SNAPSHOT_FIRST\",\"snapshotDate\":\"2026-06-30\",\"snapshotDateConfirmed\":true,\"stockImpactMode\":\"CREATE_INVOICES_ONLY\"}");
  assertThat(postJson(token,"/api/import-sessions/"+session+"/commit",commitBody(preview)).path("status").asText()).isEqualTo("COMMITTED");
  var product=products.findByTenantIdAndActiveTrueOrderByNameAsc(tenant).getFirst();var baseline=baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenant,product.id).getFirst();
  baseline.importedQuantity=new BigDecimal("999");baselines.save(baseline);
  var report=getJson(token,"/api/import-sessions/"+session+"/reconciliation");assertThat(report.path("status").asText()).as(report.toString()).isEqualTo("MATCHED");
  assertThat(report.path("checks").get(0).path("sourceValue").decimalValue()).isEqualByComparingTo("120");
  var adjustment=new StockMovement();adjustment.tenantId=tenant;adjustment.productId=product.id;adjustment.warehouseId=baseline.adjustmentWarehouseId;adjustment.unitId=product.baseUnitId;adjustment.movementType=DomainEnums.MovementType.ADJUSTMENT;adjustment.quantity=new BigDecimal("0.001");adjustment.baseQuantity=new BigDecimal("0.001");adjustment.movementDate=java.time.Instant.parse("2026-06-30T12:00:00Z");movements.save(adjustment);
  assertThat(getJson(token,"/api/import-sessions/"+session+"/reconciliation").path("status").asText()).isEqualTo("DIFFERENCES");
 }
 @Test void revisionPreviewsRequireValidationPermissionAndRedactPurchaseCosts() throws Exception {
  var owner=register();var token=owner.path("accessToken").asText();var tenant=UUID.fromString(owner.path("tenantId").asText());prepare(token);
  var purchase=saleXml().replace("Sales","Purchase").replace("S-001","P-001").replace("Sample Customer","Sample Supplier");commit(token,smartSession(token,purchase));
  var session=smartSession(token,purchase.replace("2 PCS","3 PCS"));
  var member=members.findByTenantId(tenant).getFirst();member.role=DomainEnums.Role.STAFF;members.save(member);
  mvc.perform(get("/api/import-sessions/"+session+"/document-revisions").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
  member.role=DomainEnums.Role.MANAGER;members.save(member);var preview=revision(token,session);
  assertThat(preview.path("financialValuesRedacted").asBoolean()).isTrue();assertThat(preview.path("existingTotal").isNull()).isTrue();assertThat(preview.path("proposedTotal").isNull()).isTrue();
  assertThat(preview.path("existingLines").get(0).has("rate")).isFalse();assertThat(preview.path("proposedLines").get(0).has("amount")).isFalse();
 }
 @Test void reviewRejectsDestinationEditsAndMappingChangesSincePreview() throws Exception {
  var owner=register();var token=owner.path("accessToken").asText();var tenant=UUID.fromString(owner.path("tenantId").asText());prepare(token);commit(token,smartSession(token,saleXml()));
  var session=smartSession(token,saleXml().replace("2 PCS","3 PCS"));var preview=revision(token,session);
  var invoice=invoices.findByTenantId(tenant).getFirst();invoice.totalAmount=new BigDecimal("31");invoices.save(invoice);
  var body=mapper.writeValueAsString(Map.of("action","APPLY_CORRECTION","reason","Verified source","expectedSourceHash",preview.path("sourceHash").asText(),"expectedDestinationHash",preview.path("destinationHash").asText(),"confirmation","APPLY DOCUMENT REVISION"));
  var endpoint="/api/import-sessions/"+session+"/document-revisions/"+preview.path("voucherId").asText()+"/review";
  mvc.perform(post(endpoint).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
  preview=revision(token,session);body=mapper.writeValueAsString(Map.of("action","APPLY_CORRECTION","reason","Verified source","expectedSourceHash",preview.path("sourceHash").asText(),"expectedDestinationHash",preview.path("destinationHash").asText(),"confirmation","APPLY DOCUMENT REVISION"));
  var replacement=postJson(token,"/api/products","{\"name\":\"Other Existing Product\",\"unitCode\":\"PCS\",\"defaultPurchasePrice\":5}");
  var item=stagedItems.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenant,UUID.fromString(session)).getFirst();item.matchedProductId=UUID.fromString(replacement.path("id").asText());stagedItems.save(item);
  mvc.perform(post(endpoint).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
 }
 @Test void revisionListUsesBoundedServerPages() throws Exception {
  var owner=register();var token=owner.path("accessToken").asText();prepare(token);
  assertThat(commit(token,smartSession(token,saleXml(),saleXml().replace("S-001","S-002"))).path("status").asText()).isEqualTo("COMMITTED");
  var first=saleXml().replace("2 PCS","3 PCS");var session=smartSession(token,first,first.replace("S-001","S-002"));
  var page0=getJson(token,"/api/import-sessions/"+session+"/document-revisions?size=1&page=0");
  var page1=getJson(token,"/api/import-sessions/"+session+"/document-revisions?size=1&page=1");
  assertThat(page0.path("totalElements").asInt()).isEqualTo(2);assertThat(page0.path("totalPages").asInt()).isEqualTo(2);assertThat(page0.path("items").size()).isEqualTo(1);
  assertThat(page0.path("items").get(0).path("voucherId").asText()).isNotEqualTo(page1.path("items").get(0).path("voucherId").asText());
  assertThat(getJson(token,"/api/import-sessions/"+session+"/document-revisions?size=999").path("size").asInt()).isEqualTo(50);
 }
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
