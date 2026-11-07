package com.stockpilot.ai.service;

import com.stockpilot.ai.config.JwtService;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.UserAccount;
import com.stockpilot.ai.domain.UserTenantMembership;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.AuthIdentityRepository;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthSessionService {
    private final Repositories.UserRepository users;
    private final Repositories.MembershipRepository memberships;
    private final Repositories.TenantRepository tenants;
    private final AuthIdentityRepository identities;
    private final PermissionService permissions;
    private final JwtService jwt;
    private final WorkspaceInvitationService invitations;
    public AuthSessionService(Repositories.UserRepository users, Repositories.MembershipRepository memberships,
        Repositories.TenantRepository tenants, AuthIdentityRepository identities, PermissionService permissions,
        JwtService jwt, WorkspaceInvitationService invitations) {
        this.users = users; this.memberships = memberships; this.tenants = tenants; this.identities = identities;
        this.permissions = permissions; this.jwt = jwt; this.invitations = invitations;
    }
    public ApiDtos.AuthResponse defaultSession(UserAccount user) {
        if (AuthRoles.isPlatform(user.platformRole)) return platformResponse(user, false);
        return memberships.findByUserId(user.id).stream().filter(m -> !AuthRoles.isPlatform(m.role))
            .filter(m -> tenants.findByIdAndStatus(m.tenantId, DomainEnums.TenantStatus.ACTIVE).isPresent())
            .findFirst().map(m -> response(user, m, false))
            .orElseGet(() -> new ApiDtos.AuthResponse(jwt.issueAccountOnly(user.id, user.email, user.authVersion), null, user.id, null, user.email, user.fullName));
    }
    public void establishClerk(UserAccount account, ClerkJwtVerifier.VerifiedClerkIdentity identity, String mode) {
        if (mode != null && !List.of("auto", "workspace", "platform").contains(mode)) throw ApiErrors.badRequest("Invalid authentication mode");
        TenantContext.setIdentity(AuthRoles.isPlatform(account.platformRole) ? account.platformRole : null, "clerk", identity.organizationId());
        TenantContext.setClerkOrganizationRole(identity.organizationRole());
        if ("platform".equals(mode) || ((mode == null || "auto".equals(mode)) && identity.organizationId() == null && AuthRoles.isPlatform(account.platformRole))) {
            if (!AuthRoles.isPlatform(account.platformRole)) throw ApiErrors.forbidden("Platform administrator access is required");
            TenantContext.setPlatform(account.id, account.platformRole); TenantContext.setAccessState("PLATFORM_READY"); return;
        }
        TenantContext.setAccountOnly(account.id);
        TenantContext.setAccessState("ORGANIZATION_REQUIRED");
        if (identity.organizationId() == null) return;
        var tenant = identities.findTenantByClerkOrganizationId(identity.organizationId());
        if (tenant.isEmpty()) { TenantContext.setAccessState("ORGANIZATION_UNLINKED"); return; }
        if (tenant.get().status != DomainEnums.TenantStatus.ACTIVE) throw ApiErrors.forbidden("This workspace is suspended or deleted");
        var membership = memberships.findByTenantIdAndUserId(tenant.get().id, account.id)
            .or(() -> invitations.resolveClerkMembership(tenant.get(), account));
        if (membership.isEmpty() || AuthRoles.isPlatform(membership.get().role)) { TenantContext.setAccessState("MEMBERSHIP_REQUIRED"); return; }
        TenantContext.set(tenant.get().id, account.id, membership.get().role); TenantContext.setAccessState("WORKSPACE_READY");
    }
    public Map<String, Object> currentSession() {
        var account = currentAccount();
        var result = new LinkedHashMap<String, Object>();
        result.put("userId", account.id); result.put("email", account.email); result.put("fullName", account.fullName);
        result.put("emailVerified", account.emailVerified); result.put("tenantId", TenantContext.tenantIdOrNull());
        result.put("role", TenantContext.role()); result.put("platformRole", AuthRoles.isPlatform(account.platformRole) ? account.platformRole : null);
        result.put("permissions", permissions.currentPermissions()); result.put("workspaces", workspaces(account.id));
        result.put("authProvider", TenantContext.authProvider()); result.put("clerkOrganizationId", TenantContext.clerkOrganizationId());
        result.put("clerkLinked", account.externalClerkId != null);
        result.put("accessState", TenantContext.accessState() == null ? (AuthRoles.isPlatform(TenantContext.role()) ? "PLATFORM_READY"
            : TenantContext.tenantIdOrNull() == null ? "WORKSPACE_REQUIRED" : "WORKSPACE_READY") : TenantContext.accessState());
        return result;
    }
    public List<Map<String, Object>> workspaces(UUID userId) {
        return memberships.findByUserId(userId).stream().filter(m -> !AuthRoles.isPlatform(m.role))
            .flatMap(m -> tenants.findByIdAndStatus(m.tenantId, DomainEnums.TenantStatus.ACTIVE).map(t -> {
                Map<String, Object> row = new LinkedHashMap<>(); row.put("tenantId", t.id); row.put("name", t.name);
                row.put("businessMode", t.businessMode); row.put("role", m.role); row.put("customerId", m.customerId);
                row.put("clerkOrganizationId", t.clerkOrganizationId); return row;
            }).stream()).toList();
    }
    public ApiDtos.AuthResponse switchWorkspace(UUID tenantId) {
        var user = currentAccount();
        var tenant = tenants.findByIdAndStatus(tenantId, DomainEnums.TenantStatus.ACTIVE).orElseThrow(() -> ApiErrors.notFound("Active workspace not found"));
        var membership = memberships.findByTenantIdAndUserId(tenantId, user.id).filter(m -> !AuthRoles.isPlatform(m.role))
            .orElseThrow(() -> ApiErrors.forbidden("You are not a member of this workspace"));
        boolean clerk = "clerk".equals(TenantContext.authProvider());
        if (clerk && (tenant.clerkOrganizationId == null || !tenant.clerkOrganizationId.equals(TenantContext.clerkOrganizationId()))) {
            throw ApiErrors.conflict("Select this workspace's organization in Clerk before switching");
        }
        return response(user, membership, clerk);
    }
    public ApiDtos.AuthResponse switchPlatform() { return platformResponse(currentAccount(), "clerk".equals(TenantContext.authProvider())); }
    public ApiDtos.AuthResponse currentAuthResponse() {
        var account = currentAccount();
        boolean clerk = "clerk".equals(TenantContext.authProvider());
        if (AuthRoles.isPlatform(TenantContext.role())) return platformResponse(account, clerk);
        if (TenantContext.tenantIdOrNull() != null) return switchWorkspace(TenantContext.tenantId());
        return new ApiDtos.AuthResponse(clerk ? null : jwt.issueAccountOnly(account.id, account.email, account.authVersion),
            null, account.id, null, account.email, account.fullName);
    }
    private ApiDtos.AuthResponse platformResponse(UserAccount user, boolean clerk) {
        if (!AuthRoles.isPlatform(user.platformRole)) throw ApiErrors.forbidden("Platform administrator access is required");
        return new ApiDtos.AuthResponse(clerk ? null : jwt.issuePlatform(user.id, user.email, user.platformRole, user.authVersion),
            null, user.id, user.platformRole, user.email, user.fullName);
    }
    private ApiDtos.AuthResponse response(UserAccount user, UserTenantMembership membership, boolean clerk) {
        return new ApiDtos.AuthResponse(clerk ? null : jwt.issue(user.id, membership.tenantId, user.email, membership.role, user.authVersion),
            membership.tenantId, user.id, membership.role, user.email, user.fullName);
    }
    private UserAccount currentAccount() {
        return users.findById(TenantContext.userId()).filter(u -> u.active && u.emailVerified).orElseThrow(() -> ApiErrors.forbidden("Account is unavailable"));
    }
}
