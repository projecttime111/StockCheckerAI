package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.JwtService;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.UserAccount;
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
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.platform-admin-emails=clerk-rollout-new@example.com,clerk-rollout-existing@example.com,clerk-rollout-stale@example.com,clerk-rollout-unverified@example.com,clerk-rollout-subject@example.com,clerk-rollout-support@example.com")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClerkRolloutIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Repositories.UserRepository users;
    @Autowired Repositories.TenantRepository tenants;
    @Autowired Repositories.MembershipRepository memberships;
    @Autowired AuthIdentityRepository identities;
    @Autowired JwtService jwt;
    @MockBean ClerkJwtVerifier verifier;
    @MockBean ClerkBackendClient clerk;

    @Test void newVerifiedAllowlistedClerkIdentityCanBootstrapWithoutLocalLoginAndCannotReadBusinessData() throws Exception {
        var subject = subject(); var bearer = token(subject, null, null);
        profile(subject, "clerk-rollout-new@example.com");
        var session = request(get("/api/auth/session"), bearer, null, 200);
        assertThat(session.path("role").asText()).isEqualTo("PLATFORM_SUPER_ADMIN");
        assertThat(session.path("accessState").asText()).isEqualTo("PLATFORM_READY");
        assertThat(session.path("tenantId").isNull()).isTrue();
        var account = identities.findByExternalClerkId(subject).orElseThrow();
        assertThat(account.emailVerified).isTrue();
        assertThat(memberships.findByUserId(account.id)).isEmpty();
        request(get("/api/products"), bearer, null, 403);
        request(get("/api/auth/session"), bearer, null, 200);
        verify(clerk, times(1)).verifiedProfile(subject);
    }

    @Test void existingClerkAccountBootstrapRequiresFreshMatchingVerifiedProfile() throws Exception {
        var subject = subject(); var account = clerkAccount("clerk-rollout-existing@example.com", subject);
        profile(subject, account.email);
        var bearer = token(subject, null, null);
        assertThat(request(get("/api/auth/session"), bearer, null, 200).path("platformRole").asText()).isEqualTo("PLATFORM_SUPER_ADMIN");
        assertThat(users.findById(account.id).orElseThrow().platformRole).isEqualTo(DomainEnums.Role.PLATFORM_SUPER_ADMIN);
        verify(clerk).verifiedProfile(subject);
    }

    @Test void staleCachedEmailOrWrongProfileSubjectCannotBootstrapGlobalAuthority() throws Exception {
        var subject = subject(); var account = clerkAccount("clerk-rollout-stale@example.com", subject);
        profile(subject, "current-other@example.com");
        var session = request(get("/api/auth/session"), token(subject, null, null), null, 200);
        assertThat(session.path("platformRole").isNull()).isTrue();
        assertThat(users.findById(account.id).orElseThrow().platformRole).isNull();
        var otherSubject = subject(); var other = clerkAccount("clerk-rollout-subject@example.com", otherSubject);
        when(clerk.verifiedProfile(otherSubject)).thenReturn(new ClerkBackendClient.VerifiedProfile(subject(), other.email, "Different identity"));
        request(get("/api/auth/session").header("X-StockPilot-Mode", "platform"), token(otherSubject, null, null), null, 403);
        assertThat(users.findById(other.id).orElseThrow().platformRole).isNull();
    }

    @Test void unverifiedProfileFailureCannotCreateAUserOrPlatformGrant() throws Exception {
        var subject = subject();
        when(clerk.verifiedProfile(subject)).thenThrow(new IllegalArgumentException("Verify your primary email address"));
        request(get("/api/auth/session"), token(subject, null, null), null, 401);
        assertThat(identities.findByExternalClerkId(subject)).isEmpty();
        assertThat(users.findByEmailIgnoreCase("clerk-rollout-unverified@example.com")).isEmpty();
    }

    @Test void migrationEndpointsAreDisabledByDefaultAndMatchingEmailNeverSilentlyLinks() throws Exception {
        var local = register(); var subject = subject(); var clerkToken = token(subject, organization(), "org:admin");
        var localUserId = UUID.fromString(local.path("userId").asText());
        var tenantId = UUID.fromString(local.path("tenantId").asText());
        profile(subject, local.path("email").asText());
        var conflict = request(get("/api/auth/session"), clerkToken, null, 409);
        assertThat(conflict.path("error").asText()).isEqualTo("CLERK_ACCOUNT_EXISTS");
        assertThat(conflict.path("message").asText()).contains("Contact support");
        for (var path : new String[]{"/api/auth/clerk/link", "/api/auth/clerk/organization/link"}) {
            var denied = request(post(path), local.path("accessToken").asText(), Map.of("clerkToken", clerkToken), 403);
            assertThat(denied.path("error").asText()).isEqualTo("CLERK_MIGRATION_DISABLED");
        }
        assertThat(users.findById(localUserId).orElseThrow().externalClerkId).isNull();
        assertThat(tenants.findById(tenantId).orElseThrow().clerkOrganizationId).isNull();
        assertThat(identities.findByExternalClerkId(subject)).isEmpty();
        request(get("/api/auth/session"), local.path("accessToken").asText(), null, 200);
    }

    @Test void disablingMigrationDoesNotBreakPreviouslyLinkedAccountsOrWorkspaceMemberships() throws Exception {
        var local = register(); var subject = subject(); var org = organization();
        var userId = UUID.fromString(local.path("userId").asText()); var tenantId = UUID.fromString(local.path("tenantId").asText());
        var account = users.findById(userId).orElseThrow(); account.externalClerkId = subject; users.saveAndFlush(account);
        var tenant = tenants.findById(tenantId).orElseThrow(); tenant.clerkOrganizationId = org; tenants.saveAndFlush(tenant);
        var session = request(get("/api/auth/session"), token(subject, org, "org:member"), null, 200);
        assertThat(session.path("userId").asText()).isEqualTo(userId.toString());
        assertThat(session.path("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(session.path("role").asText()).isEqualTo("OWNER");
        verifyNoInteractions(clerk);
    }

    @Test void existingGlobalRoleIsNotUpgradedByBootstrapAllowlistOrDependentOnFreshProfileCalls() throws Exception {
        var subject = subject(); var account = clerkAccount("clerk-rollout-support@example.com", subject);
        account.platformRole = DomainEnums.Role.PLATFORM_SUPPORT; users.saveAndFlush(account);
        var session = request(get("/api/auth/session"), token(subject, null, null), null, 200);
        assertThat(session.path("role").asText()).isEqualTo("PLATFORM_SUPPORT");
        verifyNoInteractions(clerk);
    }

    private UserAccount clerkAccount(String email, String subject) {
        var account = new UserAccount(); account.email = email; account.fullName = "Clerk account";
        account.passwordHash = "unused-test-password-hash"; account.externalClerkId = subject; account.emailVerified = true;
        return users.saveAndFlush(account);
    }
    private JsonNode register() throws Exception {
        return request(post("/api/auth/register"), null, Map.of("businessName", "Existing business", "businessMode", "HYBRID",
            "email", UUID.randomUUID() + "@example.com", "password", "Password123!", "fullName", "Existing owner"), 200);
    }
    private String token(String subject, String org, String role) {
        var bearer = "test-clerk-rollout-" + UUID.randomUUID();
        when(verifier.verify(bearer)).thenReturn(new ClerkJwtVerifier.VerifiedClerkIdentity(subject, "sess_test", org, role));
        return bearer;
    }
    private void profile(String subject, String email) { when(clerk.verifiedProfile(subject)).thenReturn(new ClerkBackendClient.VerifiedProfile(subject, email, "Verified Clerk user")); }
    private String subject() { return "user_" + UUID.randomUUID().toString().replace("-", ""); }
    private String organization() { return "org_" + UUID.randomUUID().toString().replace("-", ""); }
    private JsonNode request(MockHttpServletRequestBuilder request, String token, Object body, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var content = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return content.isBlank() ? json.nullNode() : json.readTree(content);
    }
}
