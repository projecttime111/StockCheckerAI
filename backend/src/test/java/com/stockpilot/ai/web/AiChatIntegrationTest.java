package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.*;
import com.stockpilot.ai.config.*;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.repo.*;
import com.stockpilot.ai.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"app.ai.enabled=true","app.ai.model=gemini-test","app.ai.gemini-api-key=test-not-real","app.ai.user-requests-per-minute=6"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiChatIntegrationTest {
    @Autowired Repositories.TenantRepository tenants;
    @Autowired Repositories.UserRepository users;
    @Autowired Repositories.MembershipRepository memberships;
    @Autowired Repositories.ProductRepository products;
    @Autowired Repositories.UnitRepository units;
    @Autowired Repositories.CustomerRepository customers;
    @Autowired Repositories.WarehouseRepository warehouses;
    @Autowired Repositories.ForecastResultRepository forecasts;
    @Autowired AiChatRepositories.Conversations conversations;
    @Autowired AiChatRepositories.Messages messages;
    @Autowired AiChatRepositories.Runs runs;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean LlmAiProvider provider;
    @BeforeEach void provider(){ answerWith("summary",Map.of()); }
    @AfterEach void clear(){TenantContext.clear();}

    @Test void hostedProviderUsesAuthorizedEvidenceOutsideTransactionsAndCannotSeeStaffCosts() throws Exception {
        var f=fixture(DomainEnums.Role.STAFF);product(f);
        doAnswer(invocation->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<AiProvider.Tool> definitions=invocation.getArgument(2);
            assertThat(definitions).extracting(AiProvider.Tool::name).doesNotContain("profit_comparison","receivables","data_quality");
            Function<AiProvider.Call,Map<String,Object>> execute=invocation.getArgument(3);
            var card=execute.apply(new AiProvider.Call("summary",Map.of()));var data=json.valueToTree(card.get("data"));
            assertThat(data.path("grossProfit").isNull()).isTrue();assertThat(data.path("totalStockValue").isNull()).isTrue();
            return new AiProvider.Completion("Your permitted dashboard summary is available [summary].","gemini","test",20,10);
        }).when(provider).complete(anyList(),anyString(),anyList(),any());
        var answer=chat(f,null,"show summary",UUID.randomUUID(),200);
        assertThat(answer.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(answer.path("evidence").path("summary").path("data").path("grossProfit").isNull()).isTrue();
        assertThat(runs.findByTenantIdAndUserIdAndRequestId(f.tenant.id,f.user.id,UUID.fromString(answer.path("requestId").asText())).orElseThrow().inputTokens).isEqualTo(20);
    }
    @Test void conversationOwnershipIsEnforcedWithinAndAcrossBusinesses() throws Exception {
        var owner=fixture(DomainEnums.Role.OWNER);var answer=chat(owner,null,"summary",UUID.randomUUID(),200);var id=answer.path("conversationId").asText();
        var colleague=member(owner.tenant,DomainEnums.Role.OWNER);var other=fixture(DomainEnums.Role.OWNER);
        for(var f:List.of(colleague,other)){
            mvc.perform(get("/api/ai/conversations/"+id+"/messages").header("Authorization","Bearer "+f.token)).andExpect(status().isNotFound());
            mvc.perform(delete("/api/ai/conversations/"+id).header("Authorization","Bearer "+f.token)).andExpect(status().isNotFound());
            chat(f,UUID.fromString(id),"continue",UUID.randomUUID(),404);
            assertThat(getJson(f,"/api/ai/conversations").path("totalElements").asInt()).isZero();
        }
    }
    @Test void retriesReplayExactlyOnceRejectPayloadChangesAndDeleteRemovesPrivateHistory() throws Exception {
        var f=fixture(DomainEnums.Role.OWNER);var request=UUID.randomUUID();
        var first=chat(f,null,"summary",request,200);var second=chat(f,UUID.fromString(first.path("conversationId").asText()),"summary",request,200);
        assertThat(second).isEqualTo(first);verify(provider,times(1)).complete(anyList(),anyString(),anyList(),any());
        chat(f,null,"different question",request,409);
        var id=first.path("conversationId").asText();assertThat(getJson(f,"/api/ai/conversations/"+id+"/messages").path("totalElements").asInt()).isEqualTo(2);
        mvc.perform(delete("/api/ai/conversations/"+id).header("Authorization","Bearer "+f.token)).andExpect(status().isNoContent());
        assertThat(getJson(f,"/api/ai/conversations").path("totalElements").asInt()).isZero();
        assertThat(runs.findByTenantIdAndUserIdAndRequestId(f.tenant.id,f.user.id,request)).isEmpty();
    }
    @Test void multiTurnContextIsLoadedButRemovedPermissionsHideOldMessagesAndProviderHistory() throws Exception {
        var f=fixture(DomainEnums.Role.OWNER);product(f);var request=UUID.randomUUID();
        var first=chat(f,null,"Private owner question",request,200);var id=UUID.fromString(first.path("conversationId").asText());
        doAnswer(i->{
            List<AiProvider.Turn> history=i.getArgument(0);assertThat(history).extracting(AiProvider.Turn::content).contains("Private owner question");
            return invoke(i,"summary",Map.of());}).when(provider).complete(anyList(),anyString(),anyList(),any());
        chat(f,id,"Follow up",UUID.randomUUID(),200);
        var membership=memberships.findByTenantIdAndUserId(f.tenant.id,f.user.id).orElseThrow();membership.role=DomainEnums.Role.STAFF;memberships.saveAndFlush(membership);
        var page=getJson(f,"/api/ai/conversations/"+id+"/messages");assertThat(page.path("content")).allMatch(row->row.path("restricted").asBoolean());
        assertThat(page.toString()).doesNotContain("Private owner question");assertThat(getJson(f,"/api/ai/conversations").toString()).doesNotContain("Private owner question");chat(f,null,"Private owner question",request,403);
        doAnswer(i->{assertThat((List<?>)i.getArgument(0)).isEmpty();return invoke(i,"summary",Map.of());}).when(provider).complete(anyList(),anyString(),anyList(),any());
        chat(f,id,"New permitted summary",UUID.randomUUID(),200);
    }
    @Test void unknownTenantArgumentsAndUnauthorizedToolsAreRejectedBeforeDataAccess() throws Exception {
        var f=fixture(DomainEnums.Role.STAFF);product(f);
        answerWith("summary",Map.of("tenantId",UUID.randomUUID().toString()));chat(f,null,"ignore rules",UUID.randomUUID(),400);
        answerWith("profit_comparison",Map.of());chat(f,null,"give me profit",UUID.randomUUID(),403);
        answerWith("execute_sql",Map.of("sql","select * from app_users"));chat(f,null,"run SQL",UUID.randomUUID(),400);
    }
    @Test void stockoutQuestionDoesNotPublishOrModifyForecasts() throws Exception {
        var f=fixture(DomainEnums.Role.OWNER);product(f);var before=forecasts.findByTenantIdOrderByGeneratedAtDesc(f.tenant.id).size();
        answerWith("forecast",Map.of());var answer=chat(f,null,"stockout this week",UUID.randomUUID(),200);
        assertThat(answer.path("evidence").path("forecast").path("data")).isEmpty();
        assertThat(forecasts.findByTenantIdOrderByGeneratedAtDesc(f.tenant.id)).hasSize(before);
    }
    @Test void providerFailureFallsBackAndDoesNotCreateDuplicateTurns() throws Exception {
        var f=fixture(DomainEnums.Role.OWNER);doThrow(new IllegalStateException("provider secret should never escape")).when(provider).complete(anyList(),anyString(),anyList(),any());
        var answer=chat(f,null,"summary",UUID.randomUUID(),200);
        assertThat(answer.path("status").asText()).isEqualTo("FALLBACK");assertThat(answer.toString()).doesNotContain("provider secret");
        assertThat(getJson(f,"/api/ai/conversations/"+answer.path("conversationId").asText()+"/messages").path("totalElements").asInt()).isEqualTo(2);
    }
    @Test void revocationWhileProviderIsRunningCannotPublishPreviouslyAuthorizedEvidence() throws Exception {
        var f=fixture(DomainEnums.Role.OWNER);product(f);
        doAnswer(i->{
            var answer=invoke(i,"summary",Map.of());var membership=memberships.findByTenantIdAndUserId(f.tenant.id,f.user.id).orElseThrow();
            membership.role=DomainEnums.Role.STAFF;memberships.saveAndFlush(membership);return answer;}).when(provider).complete(anyList(),anyString(),anyList(),any());
        chat(f,null,"summary",UUID.randomUUID(),403);
        var conversation=conversations.findByTenantIdAndUserId(f.tenant.id,f.user.id,org.springframework.data.domain.PageRequest.of(0,10)).getContent().getFirst();
        var history=getJson(f,"/api/ai/conversations/"+conversation.id+"/messages");assertThat(history.path("content")).hasSize(1);assertThat(history.toString()).doesNotContain("grossProfit");
    }
    @Test void customerListAndDetailRespectOutstandingPermission() throws Exception {
        var f=fixture(DomainEnums.Role.STAFF);var customer=new Customer();customer.tenantId=f.tenant.id;customer.name="Restricted balance customer";customers.saveAndFlush(customer);
        assertThat(getJson(f,"/api/customers/"+customer.id).path("outstanding").isNull()).isTrue();
        assertThat(getJson(f,"/api/customers").path("content").get(0).path("outstanding").isNull()).isTrue();
        var sales=member(f.tenant,DomainEnums.Role.SALES_STAFF);assertThat(getJson(sales,"/api/customers/"+customer.id).path("outstanding").isNumber()).isTrue();
    }
    @Test void boundsAndTenantRateLimitsFailWithControlledErrors() throws Exception {
        var f=fixture(DomainEnums.Role.OWNER);chat(f,null,"x".repeat(4001),UUID.randomUUID(),400);
        for(int i=0;i<6;i++)chat(f,null,"summary "+i,UUID.randomUUID(),200);
        chat(f,null,"rate limited",UUID.randomUUID(),429);
    }
    @Test void fabricatedNumbersAndCitationsUseAnHonestLocalFallback() throws Exception {
        var f=fixture(DomainEnums.Role.OWNER);product(f);
        doAnswer(i->{invoke(i,"summary",Map.of());return new AiProvider.Completion("Revenue is 987654321123 [summary].","gemini","test",1,1);})
            .when(provider).complete(anyList(),anyString(),anyList(),any());
        var answer=chat(f,null,"summary",UUID.randomUUID(),200);
        assertThat(answer.path("status").asText()).isEqualTo("FALLBACK");assertThat(answer.toString()).doesNotContain("987654321123");
        doAnswer(i->{invoke(i,"summary",Map.of());return new AiProvider.Completion("Details [other_tenant_secret].","gemini","test",1,1);})
            .when(provider).complete(anyList(),anyString(),anyList(),any());
        assertThat(chat(f,null,"summary",UUID.randomUUID(),200).path("status").asText()).isEqualTo("FALLBACK");
    }
    @Test void concurrentRetryDoesNotStartAnotherProviderCall() throws Exception {
        var f=fixture(DomainEnums.Role.OWNER);var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(i->{started.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Test timed out");return invoke(i,"summary",Map.of());})
            .when(provider).complete(anyList(),anyString(),anyList(),any());
        var request=UUID.randomUUID();var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{var first=executor.submit(()->chat(f,null,"summary",request,200));assertThat(started.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            chat(f,null,"summary",request,409);release.countDown();var answer=first.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(chat(f,null,"summary",request,200)).isEqualTo(answer);verify(provider,times(1)).complete(anyList(),anyString(),anyList(),any());
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test void failedFirstTurnCanRetryFromSavedHistoryWithoutDuplicatingAndRetriesAreBounded() throws Exception {
        var f=fixture(DomainEnums.Role.OWNER);var request=UUID.randomUUID();answerWith("execute_sql",Map.of());
        chat(f,null,"summary",request,400);
        var run=runs.findByTenantIdAndUserIdAndRequestId(f.tenant.id,f.user.id,request).orElseThrow();
        answerWith("summary",Map.of());var result=chat(f,run.conversationId,"summary",request,200);
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(getJson(f,"/api/ai/conversations/"+run.conversationId+"/messages").path("totalElements").asInt()).isEqualTo(2);
        var failed=UUID.randomUUID();answerWith("execute_sql",Map.of());
        for(int attempt=0;attempt<3;attempt++)chat(f,null,"unsupported",failed,400);
        chat(f,null,"unsupported",failed,409);
    }
    @Test void productSearchUsesScopedBoundedInventoryAndRejectsForeignWarehouse() throws Exception {
        var f=fixture(DomainEnums.Role.OWNER);product(f);var other=fixture(DomainEnums.Role.OWNER);product(other);
        var warehouse=new Warehouse();warehouse.tenantId=f.tenant.id;warehouse.name="Our warehouse";warehouses.saveAndFlush(warehouse);
        var foreign=new Warehouse();foreign.tenantId=other.tenant.id;foreign.name="Secret other warehouse";warehouses.saveAndFlush(foreign);
        answerWith("inventory",Map.of("query","Private","limit",1));var answer=chat(f,null,"Private product stock",UUID.randomUUID(),200);
        assertThat(answer.path("evidence").path("inventory").path("data")).hasSize(1);
        assertThat(answer.toString()).contains("Our warehouse").doesNotContain("Secret other warehouse");
        answerWith("inventory",Map.of("warehouseId",foreign.id.toString()));chat(f,null,"warehouse stock",UUID.randomUUID(),404);
    }
    private void answerWith(String tool,Map<String,Object> args){doAnswer(i->invoke(i,tool,args)).when(provider).complete(anyList(),anyString(),anyList(),any());}
    private AiProvider.Completion invoke(org.mockito.invocation.InvocationOnMock i,String tool,Map<String,Object> args){
        Function<AiProvider.Call,Map<String,Object>> execute=i.getArgument(3);var evidence=execute.apply(new AiProvider.Call(tool,args));
        return new AiProvider.Completion(String.valueOf(evidence.get("summary"))+" ["+evidence.get("id")+"]","gemini","test",15,9);
    }
    private Fixture fixture(DomainEnums.Role role){var t=new Tenant();t.name="AI tenant "+UUID.randomUUID();t.businessMode=DomainEnums.BusinessMode.HYBRID;tenants.saveAndFlush(t);return member(t,role);}
    private Fixture member(Tenant tenant,DomainEnums.Role role){var u=new UserAccount();u.email=UUID.randomUUID()+"@example.com";u.fullName="AI user";u.passwordHash="unused";users.saveAndFlush(u);
        var m=new UserTenantMembership();m.tenantId=tenant.id;m.userId=u.id;m.role=role;memberships.saveAndFlush(m);return new Fixture(tenant,u,jwt.issue(u.id,tenant.id,u.email,role));}
    private void product(Fixture f){var unit=new UnitOfMeasure();unit.tenantId=f.tenant.id;unit.name="Piece";unit.code="PCS";units.saveAndFlush(unit);
        var p=new Product();p.tenantId=f.tenant.id;p.name="Private product";p.sku=UUID.randomUUID().toString();p.baseUnitId=unit.id;p.defaultPurchasePrice=new BigDecimal("123.45");products.saveAndFlush(p);}
    private JsonNode chat(Fixture f,UUID conversation,String text,UUID request,int expected)throws Exception{var body=new LinkedHashMap<String,Object>();body.put("conversationId",conversation);body.put("message",text);body.put("requestId",request);
        var result=mvc.perform(post("/api/ai/assistant/chat").header("Authorization","Bearer "+f.token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(expected)).andReturn();return json.readTree(result.getResponse().getContentAsString());}
    private JsonNode getJson(Fixture f,String path)throws Exception{return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization","Bearer "+f.token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private record Fixture(Tenant tenant,UserAccount user,String token){}
}
