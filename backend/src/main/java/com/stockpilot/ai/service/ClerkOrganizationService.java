package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.Tenant;
import com.stockpilot.ai.domain.UserTenantMembership;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.AuthIdentityRepository;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@Service
public class ClerkOrganizationService {
    private final AuthIdentityRepository identities;
    private final Repositories.TenantRepository tenants;
    private final Repositories.MembershipRepository memberships;
    private final ClerkAccountService accounts;
    private final TenantWriteLockService locks;
    private final AuthSessionService sessions;
    private final AuditService audit;
    public ClerkOrganizationService(AuthIdentityRepository identities, Repositories.TenantRepository tenants,
        Repositories.MembershipRepository memberships, ClerkAccountService accounts,
        TenantWriteLockService locks, AuthSessionService sessions, AuditService audit) {
        this.identities = identities; this.tenants = tenants; this.memberships = memberships; this.accounts = accounts;
        this.locks = locks; this.sessions = sessions; this.audit = audit;
    }
    @Transactional
    public ApiDtos.AuthResponse createWorkspace(ApiDtos.WorkspaceCreateRequest request) {
        if (!"clerk".equals(TenantContext.authProvider()) || TenantContext.clerkOrganizationId() == null
            || !"org:admin".equals(TenantContext.clerkOrganizationRole())) throw ApiErrors.forbidden("An authenticated Clerk organization administrator is required");
        var account = identities.lockUser(TenantContext.userId()).orElseThrow(() -> ApiErrors.notFound("Account not found"));
        var organization = TenantContext.clerkOrganizationId();
        var existing = identities.findTenantByClerkOrganizationId(organization);
        if (existing.isPresent()) return sessions.switchWorkspace(existing.get().id);
        if (request.businessName().length() > 160 || request.currency().length() > 8) throw ApiErrors.badRequest("Business name or currency is too long");
        var tenant = new Tenant(); tenant.name = request.businessName().trim(); tenant.businessMode = request.businessMode();
        tenant.currency = request.currency(); tenant.gstEnabled = request.gstEnabled(); tenant.clerkOrganizationId = organization;
        saveOrganizationMapping(tenant);
        var membership = new UserTenantMembership(); membership.tenantId = tenant.id; membership.userId = account.id;
        membership.role = DomainEnums.Role.OWNER; memberships.saveAndFlush(membership);
        audit.log(tenant.id, account.id, "CLERK_WORKSPACE_CREATED", "Tenant", tenant.id, Map.of("organizationId", organization));
        return new ApiDtos.AuthResponse(null, tenant.id, account.id, membership.role, account.email, account.fullName);
    }
    @Transactional
    public Map<String, Object> linkOrganization(String clerkToken) {
        accounts.requireMigrationEnabled();
        if (!"local".equals(TenantContext.authProvider()) || TenantContext.role() != DomainEnums.Role.OWNER) {
            throw ApiErrors.forbidden("Sign in locally as the business owner to link an existing workspace");
        }
        var identity = accounts.verifyLinkProof(clerkToken);
        if (!identity.organizationAdmin()) throw ApiErrors.forbidden("Clerk organization administrator access is required");
        locks.lock(TenantContext.tenantId());
        var tenant = tenants.findByIdAndStatus(TenantContext.tenantId(), DomainEnums.TenantStatus.ACTIVE)
            .orElseThrow(() -> ApiErrors.notFound("Active workspace not found"));
        var account = accounts.link(clerkToken);
        identities.findTenantByClerkOrganizationId(identity.organizationId()).filter(t -> !t.id.equals(tenant.id))
            .ifPresent(t -> { throw ApiErrors.conflict("This Clerk organization is already linked to a workspace"); });
        if (tenant.clerkOrganizationId != null && !tenant.clerkOrganizationId.equals(identity.organizationId())) {
            throw ApiErrors.conflict("This workspace is already linked to another Clerk organization");
        }
        tenant.clerkOrganizationId = identity.organizationId(); saveOrganizationMapping(tenant);
        audit.log(tenant.id, account.id, "CLERK_ORGANIZATION_LINKED", "Tenant", tenant.id, Map.of("organizationId", identity.organizationId()));
        return Map.of("tenantId", tenant.id, "clerkOrganizationId", tenant.clerkOrganizationId);
    }
    private void saveOrganizationMapping(Tenant tenant) {
        try { tenants.saveAndFlush(tenant); }
        catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // A different organization administrator may have completed onboarding while this request waited.
            // The unique mapping is authoritative; rollback this transaction and let the client reload access.
            throw ApiErrors.conflict("This Clerk organization is already linked to a workspace. Refresh your session to continue.");
        }
    }
}
