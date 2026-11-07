package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.JwtService;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.repo.WorkspaceInvitationRepository;
import com.stockpilot.ai.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkspaceTeamIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Repositories.UserRepository users;
    @Autowired Repositories.TenantRepository tenants;
    @Autowired Repositories.MembershipRepository memberships;
    @Autowired Repositories.ImportBatchRepository batches;
    @Autowired Repositories.ImportSessionRepository imports;
    @Autowired WorkspaceInvitationRepository invitations;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtService jwt;
    @Autowired AuthTokenService tokens;
    @MockBean EmailService email;
    @MockBean ClerkBackendClient clerk;
    @MockBean ClerkJwtVerifier verifier;
    private final Map<String, String> invitationTokens = new HashMap<>();

    @BeforeEach void captureInvitations() {
        doAnswer(call -> {
            String link = call.getArgument(2);
            invitationTokens.put(call.getArgument(0), link.substring(link.indexOf("?token=") + 7));
            return null;
        }).when(email).sendWorkspaceInvitation(anyString(), anyString(), anyString());
    }

    @Test void ownersCanInviteAndListButStaffCannotManageAndPrivilegedRolesAreRejected() throws Exception {
        var owner = fixture(); var staff = member(owner, DomainEnums.Role.STAFF);
        var invitedEmail = address();
        var invitation = invite(owner, invitedEmail, "SALES_STAFF");
        assertThat(invitation.path("status").asText()).isEqualTo("PENDING");
        assertThat(invitation.has("tokenHash")).isFalse();
        assertThat(invitationTokens.get(invitedEmail)).isNotBlank();
        request(get("/api/workspace/team/invitations"), owner.token, null, 200);
        request(post("/api/workspace/team/invitations"), staff.token, Map.of("email", address(), "role", "VIEWER"), 403);
        request(get("/api/workspace/team/members"), staff.token, null, 403);
        for (var role : List.of("OWNER", "PLATFORM_SUPER_ADMIN", "PLATFORM_SUPPORT", "CUSTOMER_USER")) {
            request(post("/api/workspace/team/invitations"), owner.token, Map.of("email", address(), "role", role), 400);
            request(patch("/api/workspace/team/members/" + staff.user), owner.token, Map.of("role", role), 400);
        }
        var roles = request(get("/api/workspace/team/roles"), owner.token, null, 200);
        assertThat(roles.toString()).contains("SALES_STAFF").doesNotContain("OWNER", "PLATFORM_", "CUSTOMER_USER");
    }

    @Test void teamMutationsAndInvitationRevocationCannotCrossTenantBoundaries() throws Exception {
        var one = fixture(); var other = fixture(); var foreignStaff = member(other, DomainEnums.Role.VIEWER);
        var foreignInvite = invite(other, address(), "STAFF");
        request(patch("/api/workspace/team/members/" + foreignStaff.user), one.token, Map.of("role", "ADMIN"), 404);
        request(delete("/api/workspace/team/members/" + foreignStaff.user), one.token, null, 404);
        request(delete("/api/workspace/team/invitations/" + foreignInvite.path("id").asText()), one.token, null, 404);
        assertThat(memberships.findByTenantIdAndUserId(other.tenant, foreignStaff.user).orElseThrow().role).isEqualTo(DomainEnums.Role.VIEWER);
        assertThat(invitations.findById(UUID.fromString(foreignInvite.path("id").asText())).orElseThrow().status).isEqualTo("PENDING");
        var listed = request(get("/api/workspace/team/members"), one.token, null, 200);
        assertThat(listed.toString()).doesNotContain(foreignStaff.email);
    }

    @Test void ownersAndSelfAccessCannotBeRemovedOrDemotedButOrdinaryMemberChangesApplyImmediately() throws Exception {
        var owner = fixture(); var admin = member(owner, DomainEnums.Role.ADMIN); var staff = member(owner, DomainEnums.Role.STAFF);
        request(delete("/api/workspace/team/members/" + owner.user), owner.token, null, 409);
        request(patch("/api/workspace/team/members/" + admin.user), admin.token, Map.of("role", "VIEWER"), 409);
        request(delete("/api/workspace/team/members/" + owner.user), admin.token, null, 403);
        request(patch("/api/workspace/team/members/" + owner.user), admin.token, Map.of("role", "STAFF"), 403);
        assertThat(memberships.countByTenantIdAndRole(owner.tenant, DomainEnums.Role.OWNER)).isEqualTo(1);
        request(patch("/api/workspace/team/members/" + staff.user), owner.token, Map.of("role", "VIEWER"), 200);
        assertThat(request(get("/api/auth/me"), staff.token, null, 200).path("role").asText()).isEqualTo("VIEWER");
        request(delete("/api/workspace/team/members/" + staff.user), owner.token, null, 200);
        request(get("/api/auth/me"), staff.token, null, 401);
    }

    @Test void invitationRegistrationIsSingleUseVerifiedAndCannotResetAnExistingAccount() throws Exception {
        var owner = fixture(); var invitedEmail = address(); invite(owner, invitedEmail, "SALES_STAFF");
        var raw = invitationTokens.get(invitedEmail);
        var preview = request(post("/api/auth/invitations/preview"), null, Map.of("token", raw), 200);
        assertThat(preview.path("email").asText()).isEqualTo(invitedEmail);
        var body = Map.of("token", raw, "fullName", "Invited colleague", "password", "NewPassword123!");
        var registered = request(post("/api/auth/invitations/register"), null, body, 200);
        assertThat(registered.path("tenantId").asText()).isEqualTo(owner.tenant.toString());
        assertThat(registered.path("role").asText()).isEqualTo("SALES_STAFF");
        assertThat(users.findByEmailIgnoreCase(invitedEmail).orElseThrow().emailVerified).isTrue();
        request(post("/api/auth/invitations/register"), null, body, 409);
        request(post("/api/auth/invitations/preview"), null, Map.of("token", raw), 409);
        var existing = fixture(); var originalHash = users.findById(existing.user).orElseThrow().passwordHash;
        invite(owner, existing.email, "VIEWER");
        request(post("/api/auth/invitations/register"), null, Map.of("token", invitationTokens.get(existing.email), "fullName", "Replacement", "password", "DifferentPassword123!"), 409);
        assertThat(users.findById(existing.user).orElseThrow().passwordHash).isEqualTo(originalHash);
    }

    @Test void existingAccountAcceptanceRequiresMatchingEmailAndSupportsWorkspacelessAccounts() throws Exception {
        var owner = fixture(); var invited = fixture(); var stranger = fixture();
        invite(owner, invited.email, "ACCOUNTANT"); var raw = invitationTokens.get(invited.email);
        request(post("/api/workspace/invitations/accept"), stranger.token, Map.of("token", raw), 403);
        assertThat(invitations.findByTenantIdAndEmailIgnoreCase(owner.tenant, invited.email).orElseThrow().status).isEqualTo("PENDING");
        var account = users.findById(invited.user).orElseThrow();
        account.externalClerkId = "user_" + shortId(); users.saveAndFlush(account);
        var clerkToken = "test-clerk-" + shortId();
        when(verifier.verify(clerkToken)).thenReturn(new ClerkJwtVerifier.VerifiedClerkIdentity(account.externalClerkId, "sess_test", null, null));
        request(post("/api/workspace/invitations/accept"), clerkToken, Map.of("token", raw), 400);
        var accountOnlyToken = jwt.issueAccountOnly(account.id, account.email, account.authVersion);
        var accepted = request(post("/api/workspace/invitations/accept"), accountOnlyToken, Map.of("token", raw), 200);
        assertThat(accepted.path("role").asText()).isEqualTo("ACCOUNTANT");
        assertThat(accepted.path("tenantId").asText()).isEqualTo(owner.tenant.toString());
        request(post("/api/workspace/invitations/accept"), invited.token, Map.of("token", raw), 409);
    }

    @Test void expiredRevokedAndSuspendedWorkspaceInvitationsCannotBeAccepted() throws Exception {
        var owner = fixture(); var invited = fixture();
        var invitation = invite(owner, invited.email, "VIEWER"); var raw = invitationTokens.get(invited.email);
        request(delete("/api/workspace/team/invitations/" + invitation.path("id").asText()), owner.token, null, 200);
        request(post("/api/workspace/invitations/accept"), invited.token, Map.of("token", raw), 409);
        invite(owner, invited.email, "VIEWER"); raw = invitationTokens.get(invited.email);
        var stored = invitations.findByTenantIdAndEmailIgnoreCase(owner.tenant, invited.email).orElseThrow();
        stored.expiresAt = Instant.now().minusSeconds(1); invitations.saveAndFlush(stored);
        request(post("/api/workspace/invitations/accept"), invited.token, Map.of("token", raw), 409);
        invite(owner, invited.email, "VIEWER"); raw = invitationTokens.get(invited.email);
        var tenant = tenants.findById(owner.tenant).orElseThrow(); tenant.status = DomainEnums.TenantStatus.SUSPENDED; tenants.saveAndFlush(tenant);
        request(post("/api/workspace/invitations/accept"), invited.token, Map.of("token", raw), 409);
        assertThat(memberships.findByTenantIdAndUserId(owner.tenant, invited.user)).isEmpty();
    }

    @Test void clerkInvitationClaimRequiresCurrentVerifiedEmailAndUsesOnlyTheLocallyAssignedRole() throws Exception {
        var owner = fixture(); var invited = fixture();
        var tenant = tenants.findById(owner.tenant).orElseThrow(); tenant.clerkOrganizationId = "org_" + shortId(); tenants.saveAndFlush(tenant);
        var account = users.findById(invited.user).orElseThrow(); account.externalClerkId = "user_" + shortId(); users.saveAndFlush(account);
        var stored = new WorkspaceInvitation(); stored.tenantId = owner.tenant; stored.email = invited.email;
        stored.role = DomainEnums.Role.SALES_STAFF; stored.tokenHash = tokens.hash(UUID.randomUUID().toString()); stored.expiresAt = Instant.now().plusSeconds(600);
        invitations.saveAndFlush(stored);
        var clerkToken = "test-clerk-" + shortId();
        when(verifier.verify(clerkToken)).thenReturn(new ClerkJwtVerifier.VerifiedClerkIdentity(account.externalClerkId, "sess_test", tenant.clerkOrganizationId, "org:admin"));
        when(clerk.verifiedProfile(account.externalClerkId)).thenReturn(new ClerkBackendClient.VerifiedProfile(account.externalClerkId, address(), "Changed email"));
        assertThat(request(get("/api/auth/session"), clerkToken, null, 200).path("accessState").asText()).isEqualTo("MEMBERSHIP_REQUIRED");
        assertThat(memberships.findByTenantIdAndUserId(owner.tenant, invited.user)).isEmpty();
        when(clerk.verifiedProfile(account.externalClerkId)).thenReturn(new ClerkBackendClient.VerifiedProfile(account.externalClerkId, invited.email, "Verified colleague"));
        var accepted = request(get("/api/auth/session"), clerkToken, null, 200);
        assertThat(accepted.path("tenantId").asText()).isEqualTo(owner.tenant.toString());
        assertThat(accepted.path("role").asText()).isEqualTo("SALES_STAFF");
        assertThat(invitations.findById(stored.id).orElseThrow().status).isEqualTo("ACCEPTED");
        assertThat(users.findById(invited.user).orElseThrow().platformRole).isNull();
    }

    @Test void businessUsageCountsSmartAndLegacyImportsOnlyWithinItsTenant() throws Exception {
        var owner = fixture(); var other = fixture();
        var smart = new ImportSession(); smart.tenantId = owner.tenant; smart.name = "Smart"; imports.saveAndFlush(smart);
        for (var type : List.of(DomainEnums.SourceType.CSV, DomainEnums.SourceType.TALLY_XML)) {
            var legacy = new ImportBatch(); legacy.tenantId = owner.tenant; legacy.sourceType = type; legacy.originalFileName = "legacy"; batches.saveAndFlush(legacy);
        }
        var foreign = new ImportSession(); foreign.tenantId = other.tenant; foreign.name = "Foreign"; imports.saveAndFlush(foreign);
        assertThat(request(get("/api/workspace/account"), owner.token, null, 200).path("usage").path("imports").asInt()).isEqualTo(3);
    }

    @Test void appAdminCanRecordAccessForExistingClerkMemberButOnlySignedTargetOrganizationCanClaimLocalRole() throws Exception {
        var owner = fixture(); var admin = member(owner, DomainEnums.Role.ADMIN); var recipient = fixture();
        var tenant = tenants.findById(owner.tenant).orElseThrow(); tenant.clerkOrganizationId = "org_" + shortId(); tenants.saveAndFlush(tenant);
        var adminAccount = users.findById(admin.user).orElseThrow(); adminAccount.externalClerkId = "user_" + shortId();
        adminAccount.emailVerified = true; users.saveAndFlush(adminAccount);
        var recipientAccount = users.findById(recipient.user).orElseThrow(); recipientAccount.externalClerkId = "user_" + shortId(); users.saveAndFlush(recipientAccount);
        var adminToken = "clerk-admin-" + shortId();
        when(verifier.verify(adminToken)).thenReturn(new ClerkJwtVerifier.VerifiedClerkIdentity(adminAccount.externalClerkId, "sess_admin", tenant.clerkOrganizationId, "org:member"));
        when(clerk.inviteOrganization(eq(tenant.clerkOrganizationId), eq(recipient.email), eq(adminAccount.externalClerkId), anyString()))
            .thenReturn(new ClerkBackendClient.InvitationDelivery("EXISTING_MEMBER", null));
        var recorded = request(post("/api/workspace/team/invitations"), adminToken, Map.of("email", recipient.email, "role", "SALES_STAFF"), 200);
        assertThat(recorded.path("deliveryStatus").asText()).isEqualTo("EXISTING_MEMBER");
        assertThat(recorded.path("message").asText()).contains("sign in and select this workspace", "No email was sent");
        assertThat(recorded.path("role").asText()).isEqualTo("SALES_STAFF");
        assertThat(Instant.parse(recorded.path("expiresAt").asText())).isBetween(Instant.now().plusSeconds(6 * 86400), Instant.now().plusSeconds(7 * 86400));
        assertThat(memberships.findByTenantIdAndUserId(owner.tenant, recipient.user)).isEmpty();
        verifyNoInteractions(email);
        var recipientToken = "clerk-recipient-" + shortId();
        when(verifier.verify(recipientToken)).thenReturn(new ClerkJwtVerifier.VerifiedClerkIdentity(recipientAccount.externalClerkId, "sess_recipient", null, null));
        request(get("/api/auth/session"), recipientToken, null, 200);
        assertThat(memberships.findByTenantIdAndUserId(owner.tenant, recipient.user)).isEmpty();
        when(verifier.verify(recipientToken)).thenReturn(new ClerkJwtVerifier.VerifiedClerkIdentity(recipientAccount.externalClerkId, "sess_recipient", tenant.clerkOrganizationId, "org:admin"));
        when(clerk.verifiedProfile(recipientAccount.externalClerkId)).thenReturn(new ClerkBackendClient.VerifiedProfile(recipientAccount.externalClerkId, recipient.email, "Verified colleague"));
        assertThat(request(get("/api/auth/session"), recipientToken, null, 200).path("role").asText()).isEqualTo("SALES_STAFF");
        assertThat(invitations.findByTenantIdAndEmailIgnoreCase(owner.tenant, recipient.email).orElseThrow().status).isEqualTo("ACCEPTED");
        assertThat(users.findById(recipient.user).orElseThrow().platformRole).isNull();
    }

    @Test void reusedClerkInvitationKeepsRemoteExpiryAndLocalRoleWhileProviderFailuresRollBack() throws Exception {
        var owner = fixture(); var recipient = fixture();
        var tenant = tenants.findById(owner.tenant).orElseThrow(); tenant.clerkOrganizationId = "org_" + shortId(); tenants.saveAndFlush(tenant);
        var inviter = users.findById(owner.user).orElseThrow(); inviter.externalClerkId = "user_" + shortId(); users.saveAndFlush(inviter);
        var expiry = Instant.ofEpochMilli(Instant.now().plusSeconds(3600).toEpochMilli());
        when(clerk.inviteOrganization(eq(tenant.clerkOrganizationId), eq(recipient.email), eq(inviter.externalClerkId), anyString()))
            .thenReturn(new ClerkBackendClient.InvitationDelivery("SENT", expiry), new ClerkBackendClient.InvitationDelivery("EXISTING_INVITATION", expiry));
        var original = invite(owner, recipient.email, "STAFF");
        var repeated = invite(owner, recipient.email, "AUDITOR");
        assertThat(repeated.path("id").asText()).isEqualTo(original.path("id").asText());
        assertThat(repeated.path("deliveryStatus").asText()).isEqualTo("EXISTING_INVITATION");
        assertThat(repeated.path("message").asText()).contains("no new email was sent");
        assertThat(Instant.parse(repeated.path("expiresAt").asText())).isEqualTo(expiry);
        var stored = invitations.findByTenantIdAndEmailIgnoreCase(owner.tenant, recipient.email).orElseThrow();
        assertThat(stored.role).isEqualTo(DomainEnums.Role.AUDITOR);
        assertThat(stored.expiresAt).isEqualTo(expiry);
        assertThat(stored.status).isEqualTo("PENDING");
        assertThat(memberships.findByTenantIdAndUserId(owner.tenant, recipient.user)).isEmpty();
        when(clerk.inviteOrganization(anyString(), anyString(), anyString(), anyString())).thenThrow(
            new com.stockpilot.ai.exception.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_PROVIDER_UNAVAILABLE", "Identity provider unavailable"));
        request(post("/api/workspace/team/invitations"), owner.token, Map.of("email", recipient.email, "role", "ADMIN"), 503);
        assertThat(invitations.findByTenantIdAndEmailIgnoreCase(owner.tenant, recipient.email).orElseThrow().role).isEqualTo(DomainEnums.Role.AUDITOR);
        var newEmail = address();
        request(post("/api/workspace/team/invitations"), owner.token, Map.of("email", newEmail, "role", "VIEWER"), 503);
        assertThat(invitations.findByTenantIdAndEmailIgnoreCase(owner.tenant, newEmail)).isEmpty();
    }

    private JsonNode invite(Fixture owner, String email, String role) throws Exception { return request(post("/api/workspace/team/invitations"), owner.token, Map.of("email", email, "role", role), 200); }
    private Fixture fixture() throws Exception {
        var email = address();
        var auth = request(post("/api/auth/register"), null, Map.of("businessName", "Team business", "businessMode", "HYBRID", "email", email, "password", "Password123!", "fullName", "Owner"), 200);
        return new Fixture(auth.path("accessToken").asText(), UUID.fromString(auth.path("userId").asText()), UUID.fromString(auth.path("tenantId").asText()), email);
    }
    private Fixture member(Fixture f, DomainEnums.Role role) {
        var user = new UserAccount(); user.email = address(); user.fullName = role.name(); user.passwordHash = passwords.encode("Password123!"); users.saveAndFlush(user);
        var membership = new UserTenantMembership(); membership.tenantId = f.tenant; membership.userId = user.id; membership.role = role; memberships.saveAndFlush(membership);
        return new Fixture(jwt.issue(user.id, f.tenant, user.email, role, user.authVersion), user.id, f.tenant, user.email);
    }
    private String address() { return UUID.randomUUID() + "@example.com"; }
    private String shortId() { return UUID.randomUUID().toString().replace("-", ""); }
    private JsonNode request(MockHttpServletRequestBuilder request, String token, Object body, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var content = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return content.isBlank() ? json.nullNode() : json.readTree(content);
    }
    private record Fixture(String token, UUID user, UUID tenant, String email) { }
}
