package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.JwtService;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.repo.AuthIdentityRepository;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.service.ClerkBackendClient;
import com.stockpilot.ai.service.ClerkJwtVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.platform-admin-emails=explicit-platform-admin@example.com", "app.clerk.legacy-migration-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClerkAuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Repositories.UserRepository users;
    @Autowired Repositories.TenantRepository tenants;
    @Autowired Repositories.MembershipRepository memberships;
    @Autowired AuthIdentityRepository identities;
    @Autowired JwtService jwt;
    @MockBean ClerkJwtVerifier verifier;
    @MockBean ClerkBackendClient clerk;

    @Test void configuredExistingVerifiedAccountGetsGlobalModeOnlyAfterAuthenticationAndGrantRevocationIsImmediate() throws Exception {
        var f = register("explicit-platform-admin@example.com");
        assertThat(users.findById(f.user).orElseThrow().platformRole).isNull();
        var login = request(post("/api/auth/login"), null, Map.of("email", f.email, "password", "Password123!"), 200);
        assertThat(login.path("tenantId").isNull()).isTrue();
        assertThat(login.path("role").asText()).isEqualTo("PLATFORM_SUPER_ADMIN");
        var platform = login.path("accessToken").asText();
        assertThat(request(get("/api/auth/session"), platform, null, 200).path("platformRole").asText()).isEqualTo("PLATFORM_SUPER_ADMIN");
        request(get("/api/dashboard/summary"), platform, null, 403);
        var workspace = request(post("/api/auth/workspaces/" + f.tenant + "/switch"), platform, null, 200);
        assertThat(workspace.path("role").asText()).isEqualTo("OWNER");
        request(post("/api/auth/platform"), workspace.path("accessToken").asText(), null, 200);
        var user = users.findById(f.user).orElseThrow(); user.platformRole = null; users.saveAndFlush(user);
        request(get("/api/auth/session"), platform, null, 401);
    }

    @Test void businessMembershipCannotPromoteItselfToGlobalPlatformAndForeignWorkspaceSwitchIsDenied() throws Exception {
        var one = register(); var other = register();
        request(post("/api/auth/platform"), one.token, null, 403);
        request(post("/api/auth/workspaces/" + other.tenant + "/switch"), one.token, null, 403);
        var membership = memberships.findByTenantIdAndUserId(one.tenant, one.user).orElseThrow();
        membership.role = DomainEnums.Role.PLATFORM_SUPER_ADMIN; memberships.saveAndFlush(membership);
        request(get("/api/auth/session"), one.token, null, 401);
        assertThat(users.findById(one.user).orElseThrow().platformRole).isNull();
    }

    @Test void newClerkUserHasNoBusinessOrPlatformAuthorityUntilProvisioned() throws Exception {
        var subject = subject(); var token = token(subject, null, null); profile(subject, UUID.randomUUID() + "@example.com");
        var session = request(get("/api/auth/session"), token, null, 200);
        assertThat(session.path("authProvider").asText()).isEqualTo("clerk");
        assertThat(session.path("accessState").asText()).isEqualTo("ORGANIZATION_REQUIRED");
        assertThat(session.path("role").isNull()).isTrue(); assertThat(session.path("platformRole").isNull()).isTrue();
        request(get("/api/products"), token, null, 403);
        request(post("/api/tenants"), token, workspaceBody(), 400);
        request(get("/api/auth/session").header("X-StockPilot-Mode", "platform"), token, null, 403);
        assertThat(identities.findByExternalClerkId(subject)).isPresent();
    }

    @Test void matchingEmailRequiresExplicitDualProofLinkAndPreservesExistingAccountId() throws Exception {
        var f = register(); var subject = subject(); var token = token(subject, null, null); profile(subject, f.email);
        var denied = request(get("/api/auth/session"), token, null, 409);
        assertThat(denied.path("error").asText()).isEqualTo("CLERK_ACCOUNT_LINK_REQUIRED");
        assertThat(users.findById(f.user).orElseThrow().externalClerkId).isNull();
        request(post("/api/auth/clerk/link"), f.token, Map.of("clerkToken", token), 200);
        var linked = request(get("/api/auth/session"), token, null, 200);
        assertThat(linked.path("userId").asText()).isEqualTo(f.user.toString());
        assertThat(linked.path("workspaces").size()).isEqualTo(1);
        assertThat(linked.path("tenantId").isNull()).isTrue();
        var refresh = request(post("/api/auth/refresh"), token, null, 200);
        assertThat(refresh.path("accessToken").isNull()).isTrue();
    }

    @Test void accountLinkRejectsDifferentVerifiedEmailAndRebinding() throws Exception {
        var f = register(); var firstSubject = subject(); var first = token(firstSubject, null, null); profile(firstSubject, "different-" + f.email);
        request(post("/api/auth/clerk/link"), f.token, Map.of("clerkToken", first), 403);
        assertThat(users.findById(f.user).orElseThrow().externalClerkId).isNull();
        profile(firstSubject, f.email); request(post("/api/auth/clerk/link"), f.token, Map.of("clerkToken", first), 200);
        var secondSubject = subject(); var second = token(secondSubject, null, null); profile(secondSubject, f.email);
        request(post("/api/auth/clerk/link"), f.token, Map.of("clerkToken", second), 409);
        assertThat(users.findById(f.user).orElseThrow().externalClerkId).isEqualTo(firstSubject);
    }

    @Test void organizationCreationIsExplicitIdempotentAndCannotGrantUninvitedOrganizationAdminAccess() throws Exception {
        var org = organization(); var ownerSubject = subject(); var owner = token(ownerSubject, org, "org:admin"); profile(ownerSubject, UUID.randomUUID() + "@example.com");
        assertThat(request(get("/api/auth/session"), owner, null, 200).path("accessState").asText()).isEqualTo("ORGANIZATION_UNLINKED");
        var workspace = request(post("/api/auth/clerk/workspace"), owner, workspaceBody(), 200);
        assertThat(workspace.path("accessToken").isNull()).isTrue();
        assertThat(request(post("/api/auth/clerk/workspace"), owner, workspaceBody(), 200).path("tenantId")).isEqualTo(workspace.path("tenantId"));
        assertThat(request(get("/api/auth/session"), owner, null, 200).path("role").asText()).isEqualTo("OWNER");
        var uninvitedSubject = subject(); var uninvited = token(uninvitedSubject, org, "org:admin"); profile(uninvitedSubject, UUID.randomUUID() + "@example.com");
        assertThat(request(get("/api/auth/session"), uninvited, null, 200).path("accessState").asText()).isEqualTo("MEMBERSHIP_REQUIRED");
        request(get("/api/products"), uninvited, null, 403);
        request(post("/api/auth/clerk/workspace"), uninvited, workspaceBody(), 403);
        assertThat(identities.findByExternalClerkId(uninvitedSubject).orElseThrow().platformRole).isNull();
    }

    @Test void existingWorkspaceLinkRequiresOwnerProofAndOrganizationUniqueness() throws Exception {
        var first = register(); var second = register(); var org = organization();
        var firstSubject = subject(); var firstClerk = token(firstSubject, org, "org:admin"); profile(firstSubject, first.email);
        request(post("/api/auth/clerk/organization/link"), first.token, Map.of("clerkToken", firstClerk), 200);
        assertThat(request(get("/api/auth/session"), firstClerk, null, 200).path("tenantId").asText()).isEqualTo(first.tenant.toString());
        var secondSubject = subject(); var secondClerk = token(secondSubject, org, "org:admin"); profile(secondSubject, second.email);
        request(post("/api/auth/clerk/organization/link"), second.token, Map.of("clerkToken", secondClerk), 409);
        assertThat(tenants.findById(second.tenant).orElseThrow().clerkOrganizationId).isNull();
        assertThat(users.findById(second.user).orElseThrow().externalClerkId).isNull();
        var membership = memberships.findByTenantIdAndUserId(first.tenant, first.user).orElseThrow(); membership.role = DomainEnums.Role.ADMIN; memberships.saveAndFlush(membership);
        request(post("/api/auth/clerk/organization/link"), first.token, Map.of("clerkToken", firstClerk), 403);
    }

    @Test void clerkWorkspaceSwitchRequiresVerifiedActiveOrganizationAndSuspendedTenantIsRejected() throws Exception {
        var f = register(); var other = register(); var subject = subject(); var org = organization();
        var clerkToken = token(subject, org, "org:admin"); profile(subject, f.email);
        request(post("/api/auth/clerk/organization/link"), f.token, Map.of("clerkToken", clerkToken), 200);
        var extra = new UserTenantMembership(); extra.tenantId = other.tenant; extra.userId = f.user; extra.role = DomainEnums.Role.VIEWER; memberships.saveAndFlush(extra);
        request(post("/api/auth/workspaces/" + other.tenant + "/switch"), clerkToken, null, 409);
        var tenant = tenants.findById(f.tenant).orElseThrow(); tenant.status = DomainEnums.TenantStatus.SUSPENDED; tenants.saveAndFlush(tenant);
        request(get("/api/auth/session"), clerkToken, null, 403);
        request(get("/api/auth/session"), f.token, null, 401);
    }

    @Test void clerkPlatformModeUsesOnlyStoredGlobalGrantAndCanBeRevokedWithoutTokenExpiry() throws Exception {
        var f = register(); var subject = subject(); var token = token(subject, null, null); profile(subject, f.email);
        request(post("/api/auth/clerk/link"), f.token, Map.of("clerkToken", token), 200);
        var user = users.findById(f.user).orElseThrow(); user.platformRole = DomainEnums.Role.PLATFORM_SUPPORT; users.saveAndFlush(user);
        var session = request(get("/api/auth/session"), token, null, 200);
        assertThat(session.path("role").asText()).isEqualTo("PLATFORM_SUPPORT"); assertThat(session.path("tenantId").isNull()).isTrue();
        assertThat(request(post("/api/auth/platform"), token, null, 200).path("accessToken").isNull()).isTrue();
        var workspaceMode = request(get("/api/auth/session").header("X-StockPilot-Mode", "workspace"), token, null, 200);
        assertThat(workspaceMode.path("role").isNull()).isTrue();
        user.platformRole = null; users.saveAndFlush(user);
        request(get("/api/auth/session").header("X-StockPilot-Mode", "platform"), token, null, 403);
    }

    @Test void clerkWorkspaceDeletionNeverExchangesTheSessionForALocalBearerToken() throws Exception {
        var f = register(); var subject = subject(); var token = token(subject, organization(), "org:admin"); profile(subject, f.email);
        request(post("/api/auth/clerk/organization/link"), f.token, Map.of("clerkToken", token), 200);
        var deleted = request(delete("/api/tenants/current"), token, Map.of("confirmation", "DELETE WORKSPACE"), 200);
        assertThat(deleted.path("accessToken").isNull()).isTrue();
        assertThat(tenants.findById(f.tenant).orElseThrow().status).isEqualTo(DomainEnums.TenantStatus.DELETED);
    }

    @Test void invalidClerkProofHasAnAuthenticationErrorWithoutChangingExistingIdentity() throws Exception {
        var f = register(); when(verifier.verify("expired-proof")).thenThrow(new IllegalArgumentException("Expired"));
        request(post("/api/auth/clerk/link"), f.token, Map.of("clerkToken", "expired-proof"), 401);
        assertThat(users.findById(f.user).orElseThrow().externalClerkId).isNull();
    }

    @Test void concurrentOrganizationAdministratorsCannotCreateDuplicateWorkspacesOrServerErrors() throws Exception {
        var org = organization(); var firstSubject = subject(); var secondSubject = subject();
        var first = token(firstSubject, org, "org:admin"); var second = token(secondSubject, org, "org:admin");
        profile(firstSubject, UUID.randomUUID() + "@example.com"); profile(secondSubject, UUID.randomUUID() + "@example.com");
        request(get("/api/auth/session"), first, null, 200); request(get("/api/auth/session"), second, null, 200);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.function.Function<String, java.util.concurrent.Callable<Integer>> create = bearer -> () -> {
                start.await();
                return mvc.perform(post("/api/auth/clerk/workspace").header("Authorization", "Bearer " + bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(workspaceBody())))
                    .andReturn().getResponse().getStatus();
            };
            var one = pool.submit(create.apply(first)); var two = pool.submit(create.apply(second)); start.countDown();
            var statuses = List.of(one.get(30, java.util.concurrent.TimeUnit.SECONDS), two.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(statuses).contains(200).allMatch(code -> code == 200 || code == 403 || code == 409);
            assertThat(statuses.stream().filter(code -> code == 200).count()).isEqualTo(1);
        }
        var tenant = identities.findTenantByClerkOrganizationId(org).orElseThrow();
        assertThat(memberships.findByTenantId(tenant.id)).hasSize(1);
    }

    @Test void accountDeletionChecksOwnedSuspendedWorkspacesEvenInAccountOnlyMode() throws Exception {
        var f = register(); var tenant = tenants.findById(f.tenant).orElseThrow();
        tenant.status = DomainEnums.TenantStatus.SUSPENDED; tenants.saveAndFlush(tenant);
        var account = users.findById(f.user).orElseThrow();
        var accountOnly = jwt.issueAccountOnly(account.id, account.email, account.authVersion);
        request(delete("/api/account"), accountOnly, Map.of("confirmation", "DELETE MY ACCOUNT"), 409);
        assertThat(users.findById(f.user).orElseThrow().active).isTrue();
        assertThat(memberships.findByTenantIdAndUserId(f.tenant, f.user).orElseThrow().role).isEqualTo(DomainEnums.Role.OWNER);
    }

    private Fixture register() throws Exception { return register(UUID.randomUUID() + "@example.com"); }
    private Fixture register(String email) throws Exception {
        var auth = request(post("/api/auth/register"), null, Map.of("businessName", "Auth business", "businessMode", "HYBRID", "email", email, "password", "Password123!", "fullName", "Owner"), 200);
        return new Fixture(auth.path("accessToken").asText(), UUID.fromString(auth.path("userId").asText()), UUID.fromString(auth.path("tenantId").asText()), email);
    }
    private String token(String subject, String org, String role) {
        var token = "test-clerk-" + UUID.randomUUID();
        when(verifier.verify(token)).thenReturn(new ClerkJwtVerifier.VerifiedClerkIdentity(subject, "sess_test", org, role)); return token;
    }
    private void profile(String subject, String email) { when(clerk.verifiedProfile(subject)).thenReturn(new ClerkBackendClient.VerifiedProfile(subject, email, "Clerk user")); }
    private String subject() { return "user_" + UUID.randomUUID().toString().replace("-", ""); }
    private String organization() { return "org_" + UUID.randomUUID().toString().replace("-", ""); }
    private Map<String, Object> workspaceBody() { return Map.of("businessName", "Clerk business", "businessMode", "HYBRID", "currency", "INR", "gstEnabled", true); }
    private JsonNode request(MockHttpServletRequestBuilder request, String token, Object body, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var content = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return content.isBlank() ? json.nullNode() : json.readTree(content);
    }
    private record Fixture(String token, UUID user, UUID tenant, String email) { }
}
