package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.JwtService;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.repo.PlatformClientProfileRepository;
import com.stockpilot.ai.repo.Repositories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformAdminIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired Repositories.UserRepository users;
    @Autowired Repositories.TenantRepository tenants;
    @Autowired Repositories.MembershipRepository memberships;
    @Autowired Repositories.ImportBatchRepository batches;
    @Autowired Repositories.ImportFileRepository files;
    @Autowired Repositories.ImportSessionRepository sessions;
    @Autowired Repositories.ImportSessionFileRepository sessionFiles;
    @Autowired PlatformClientProfileRepository profiles;

    @Test void workspaceRolesAndPlatformMembershipCannotEscalate() throws Exception {
        var tenant=tenant();
        for(var role:new DomainEnums.Role[]{DomainEnums.Role.OWNER,DomainEnums.Role.ADMIN}) {
            var account=user(null); var token=workspaceToken(account,tenant,role);
            request(get("/api/platform/businesses"),token,403);
        }
        var impostor=user(null);
        var legacyPlatformMembership=workspaceToken(impostor,tenant,DomainEnums.Role.PLATFORM_SUPER_ADMIN);
        request(get("/api/platform/businesses"),legacyPlatformMembership,401);
        var admin=user(DomainEnums.Role.PLATFORM_SUPER_ADMIN);
        var businessMode=workspaceToken(admin,tenant,DomainEnums.Role.OWNER);
        request(get("/api/platform/businesses"),businessMode,403);
        request(get("/api/products"),platformToken(admin),403);
    }

    @Test void supportCanReadMetadataAcrossTenantsButCannotMutate() throws Exception {
        var first=tenant(); var second=tenant();
        var owner=user(null); workspaceToken(owner,first,DomainEnums.Role.OWNER);
        legacyImport(first,DomainEnums.ImportStatus.FAILED); smartImport(second);
        var token=platformToken(user(DomainEnums.Role.PLATFORM_SUPPORT));
        var page=request(get("/api/platform/businesses").param("search",first.name).param("size","1"),token,200);
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        assertThat(page.path("content").get(0).path("id").asText()).isEqualTo(first.id.toString());
        assertThat(page.path("content").get(0).path("importCount").asInt()).isEqualTo(1);
        var detail=request(get("/api/platform/businesses/"+second.id),token,200);
        assertThat(detail.path("usage").path("importFileCount").asInt()).isEqualTo(1);
        var members=request(get("/api/platform/businesses/"+first.id+"/members"),token,200);
        assertThat(members.path("content").get(0).path("email").asText()).isEqualTo(owner.email);
        request(get("/api/platform/audit"),token,200);
        request(patch("/api/platform/businesses/"+first.id+"/status").content(json(Map.of("status","SUSPENDED","reason","Support cannot suspend"))),token,403);
        request(patch("/api/platform/businesses/"+first.id+"/subscription").content(subscription()),token,403);
        request(patch("/api/platform/businesses/"+first.id+"/notes").content(json(Map.of("notes","No","reason","Support cannot write"))),token,403);
    }

    @Test void billingMayChangeOnlySubscriptionAndCannotReadInternalNotes() throws Exception {
        var tenant=tenant(); var profile=new PlatformClientProfile(); profile.tenantId=tenant.id; profile.internalNotes="Private support investigation"; profiles.save(profile);
        var token=platformToken(user(DomainEnums.Role.PLATFORM_BILLING_ADMIN));
        var updated=request(patch("/api/platform/businesses/"+tenant.id+"/subscription").content(subscription()),token,200);
        assertThat(updated.path("business").path("plan").asText()).isEqualTo("PRO");
        assertThat(updated.path("business").path("subscriptionStatus").asText()).isEqualTo("ACTIVE");
        assertThat(updated.path("internalNotes").isNull()).isTrue();
        assertThat(profiles.findByTenantId(tenant.id).orElseThrow().internalNotes).isEqualTo("Private support investigation");
        for(String path:new String[]{"/imports","/audit","/system","/dashboard","/businesses/"+tenant.id+"/members","/businesses/"+tenant.id+"/imports"}) request(get("/api/platform"+path),token,403);
        request(patch("/api/platform/businesses/"+tenant.id+"/notes").content(json(Map.of("notes","No","reason","Not allowed"))),token,403);
        request(patch("/api/platform/businesses/"+tenant.id+"/status").content(json(Map.of("status","SUSPENDED","reason","Not allowed"))),token,403);
        var support=platformToken(user(DomainEnums.Role.PLATFORM_SUPPORT));
        var audit=request(get("/api/platform/audit").param("tenantId",tenant.id.toString()),support,200);
        assertThat(audit.path("content").get(0).path("afterValues").path("plan").asText()).isEqualTo("PRO");
        assertThat(audit.path("content").get(0).path("reason").asText()).isEqualTo("Manual agreement recorded");
    }

    @Test void suspendRevokesBusinessAccessAndReactivationIsAuditedWithoutTouchingOtherTenant() throws Exception {
        var tenant=tenant(); var other=tenant();
        var owner=user(null); var ownerToken=workspaceToken(owner,tenant,DomainEnums.Role.OWNER);
        var token=platformToken(user(DomainEnums.Role.PLATFORM_SUPER_ADMIN));
        request(patch("/api/platform/businesses/"+tenant.id+"/status").content(json(Map.of("status","SUSPENDED","reason","Requested pause"))),token,200);
        request(get("/api/products"),ownerToken,401);
        assertThat(tenants.findById(other.id).orElseThrow().status).isEqualTo(DomainEnums.TenantStatus.ACTIVE);
        request(patch("/api/platform/businesses/"+tenant.id+"/status").content(json(Map.of("status","ACTIVE","reason","Client resumed"))),token,200);
        request(get("/api/products"),ownerToken,200);
        var audit=request(get("/api/platform/audit").param("tenantId",tenant.id.toString()).param("size","1"),token,200);
        assertThat(audit.path("totalElements").asLong()).isEqualTo(2);
        assertThat(audit.path("totalPages").asLong()).isEqualTo(2);
        assertThat(audit.path("content").get(0).path("action").asText()).isEqualTo("TENANT_REACTIVATED");
        var deleted=tenants.findById(tenant.id).orElseThrow(); deleted.status=DomainEnums.TenantStatus.DELETED; deleted.deletedAt=Instant.now(); tenants.save(deleted);
        request(patch("/api/platform/businesses/"+tenant.id+"/status").content(json(Map.of("status","ACTIVE","reason","Cannot restore deleted"))),token,409);
        request(patch("/api/platform/businesses/"+tenant.id+"/subscription").content(subscription()),token,409);
    }

    @Test void inputValidationAndDefaultProfileFiltersAreConsistent() throws Exception {
        var tenant=tenant(); var token=platformToken(user(DomainEnums.Role.PLATFORM_SUPER_ADMIN));
        var page=request(get("/api/platform/businesses").param("search",tenant.name).param("plan","FREE").param("subscriptionStatus","TRIAL").param("status","ACTIVE"),token,200);
        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        request(get("/api/platform/businesses").param("size","101"),token,400);
        request(get("/api/platform/imports").param("page","-1"),token,400);
        request(get("/api/platform/businesses").param("status","NONEXISTENT"),token,400);
        request(get("/api/platform/businesses").param("page","not-a-number"),token,400);
        request(get("/api/platform/businesses/not-a-uuid"),token,400);
        request(get("/api/platform/imports").param("source","NONEXISTENT"),token,400);
        request(patch("/api/platform/businesses/"+tenant.id+"/status").content(json(Map.of("status","SUSPENDED","reason"," "))),token,400);
        request(patch("/api/platform/businesses/"+tenant.id+"/status").content(json(Map.of("status","DELETED","reason","No destructive API"))),token,400);
        request(patch("/api/platform/businesses/"+tenant.id+"/notes").content(json(Map.of("notes","x".repeat(10001),"reason","Too long"))),token,400);
        request(patch("/api/platform/businesses/"+tenant.id+"/subscription").content("{\"plan\":\"INVALID\",\"subscriptionStatus\":\"ACTIVE\",\"reason\":\"Invalid\"}"),token,400);
        request(get("/api/platform/businesses/"+UUID.randomUUID()),token,404);
    }

    @Test void monitorFiltersBothImportSystemsWithoutExposingBusinessRowsOrStorage() throws Exception {
        var tenant=tenant(); var other=tenant();
        legacyImport(tenant,DomainEnums.ImportStatus.FAILED); legacyImport(tenant,DomainEnums.ImportStatus.COMMITTED);
        smartImport(tenant); smartImport(other);
        var token=platformToken(user(DomainEnums.Role.PLATFORM_SUPPORT));
        var failed=request(get("/api/platform/imports").param("tenantId",tenant.id.toString()).param("failedOnly","true").param("size","1"),token,200);
        assertThat(failed.path("totalElements").asInt()).isEqualTo(2);
        assertThat(failed.path("content").size()).isEqualTo(1);
        var smart=request(get("/api/platform/imports").param("tenantId",tenant.id.toString()).param("source","SMART_IMPORT"),token,200);
        assertThat(smart.path("totalElements").asInt()).isEqualTo(1);
        assertThat(smart.path("content").get(0).path("fileNames").get(0).asText()).isEqualTo("fixture-vouchers.xml");
        assertThat(smart.toString()).doesNotContain("private-storage-key","confidential-row-content","rawMetadata","metadataJson");
        var legacy=request(get("/api/platform/businesses/"+tenant.id+"/imports").param("source","CSV").param("failedOnly","true"),token,200);
        assertThat(legacy.path("totalElements").asInt()).isEqualTo(1);
        assertThat(legacy.path("content").get(0).path("fileNames").get(0).asText()).isEqualTo("fixture.csv");
    }

    @Test void dashboardAndSystemCountsComeFromDatabase() throws Exception {
        var tenant=tenant(); legacyImport(tenant,DomainEnums.ImportStatus.FAILED); smartImport(tenant);
        var token=platformToken(user(DomainEnums.Role.PLATFORM_SUPER_ADMIN));
        var dashboard=request(get("/api/platform/dashboard"),token,200);
        assertThat(dashboard.path("totalBusinesses").asLong()).isEqualTo(jdbc.queryForObject("select count(*) from tenants",Long.class));
        assertThat(dashboard.path("totalImports").asLong()).isEqualTo(jdbc.queryForObject("select (select count(*) from import_batches)+(select count(*) from import_sessions)",Long.class));
        var system=request(get("/api/platform/system"),token,200);
        assertThat(system.path("database").path("status").asText()).isEqualTo("UP");
        assertThat(system.path("failedImports").asLong()).isEqualTo(dashboard.path("failedImports").asLong());
        assertThat(system.path("failuresLast24Hours").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(system.path("lastFailureAt").isNull()).isFalse();
        assertThat(system.has("redis")).isFalse(); assertThat(system.has("billingProvider")).isFalse();
    }

    @Test void notesChangesAreAuditedWithoutDuplicatingSensitiveNotesAndGrantRevocationAppliesImmediately() throws Exception {
        var tenant=tenant(); var admin=user(DomainEnums.Role.PLATFORM_SUPER_ADMIN); var token=platformToken(admin);
        var updated=request(patch("/api/platform/businesses/"+tenant.id+"/notes").content(json(Map.of("notes","Sensitive support notes","reason","Documented follow-up"))),token,200);
        assertThat(updated.path("internalNotes").asText()).isEqualTo("Sensitive support notes");
        var audit=request(get("/api/platform/audit").param("tenantId",tenant.id.toString()).param("action","NOTES_UPDATED"),token,200);
        assertThat(audit.path("totalElements").asInt()).isEqualTo(1);
        assertThat(audit.toString()).doesNotContain("Sensitive support notes");
        assertThat(audit.path("content").get(0).path("actorUserId").asText()).isEqualTo(admin.id.toString());
        admin.platformRole=null; users.save(admin);
        request(get("/api/platform/businesses"),token,401);
    }

    @Test void concurrentSuspensionSerializesOnTenantAndRecordsOneTransition() throws Exception {
        var tenant=tenant(); var token=platformToken(user(DomainEnums.Role.PLATFORM_SUPER_ADMIN));
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> suspend=()-> {
                barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);
                return mvc.perform(patch("/api/platform/businesses/"+tenant.id+"/status")
                    .header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"SUSPENDED\",\"reason\":\"Concurrent pause\"}"))
                    .andReturn().getResponse().getStatus();
            };
            var first=executor.submit(suspend); var second=executor.submit(suspend);
            assertThat(java.util.List.of(first.get(20,java.util.concurrent.TimeUnit.SECONDS),second.get(20,java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200,409);
        }
        assertThat(tenants.findById(tenant.id).orElseThrow().status).isEqualTo(DomainEnums.TenantStatus.SUSPENDED);
        assertThat(jdbc.queryForObject("select count(*) from platform_admin_audit where target_tenant_id=?",Long.class,tenant.id)).isEqualTo(1);
    }

    private Tenant tenant() { var value=new Tenant(); value.name="Platform fixture "+UUID.randomUUID(); return tenants.save(value); }
    private UserAccount user(DomainEnums.Role role) {
        var user=new UserAccount(); user.email="platform-"+UUID.randomUUID()+"@example.com"; user.fullName="Platform test account";
        user.passwordHash="unused-local-test-password"; user.platformRole=role; return users.save(user);
    }
    private String platformToken(UserAccount user) { return jwt.issuePlatform(user.id,user.email,user.platformRole,user.authVersion); }
    private String workspaceToken(UserAccount user,Tenant tenant,DomainEnums.Role role) {
        var membership=new UserTenantMembership(); membership.tenantId=tenant.id; membership.userId=user.id; membership.role=role; memberships.save(membership);
        return jwt.issue(user.id,tenant.id,user.email,role,user.authVersion);
    }
    private void legacyImport(Tenant tenant,DomainEnums.ImportStatus status) {
        var batch=new ImportBatch(); batch.tenantId=tenant.id; batch.sourceType=DomainEnums.SourceType.CSV; batch.status=status;
        batch.originalFileName="fixture.csv"; batch.rowCount=4; batch.errorCount=status==DomainEnums.ImportStatus.FAILED?1:0; batches.save(batch);
        var file=new ImportFile(); file.tenantId=tenant.id; file.importBatchId=batch.id; file.fileName="fixture.csv";
        file.contentType="text/csv"; file.storageKey="private-storage-key"; files.save(file);
    }
    private void smartImport(Tenant tenant) {
        var session=new ImportSession(); session.tenantId=tenant.id; session.name="Tally fixture"; session.status=DomainEnums.ImportSessionStatus.FAILED; sessions.save(session);
        var file=new ImportSessionFile(); file.tenantId=tenant.id; file.importSessionId=session.id; file.originalFileName="fixture-vouchers.xml";
        file.contentType="application/xml"; file.storageKey="private-storage-key"; file.fileHash=UUID.randomUUID().toString().replace("-","").repeat(2);
        file.rowCount=10; file.errorCount=1; file.status=DomainEnums.ImportSessionFileStatus.FAILED; file.metadataJson=Map.of("rawRow","confidential-row-content"); sessionFiles.save(file);
    }
    private String subscription() { return "{\"plan\":\"PRO\",\"subscriptionStatus\":\"ACTIVE\",\"subscriptionEndsAt\":\"2030-01-01T00:00:00Z\",\"reason\":\"Manual agreement recorded\"}"; }
    private String json(Object value) throws Exception { return mapper.writeValueAsString(value); }
    private JsonNode request(MockHttpServletRequestBuilder request,String token,int expectedStatus) throws Exception {
        var body=mvc.perform(request.header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return body.isBlank()?mapper.nullNode():mapper.readTree(body);
    }
}
