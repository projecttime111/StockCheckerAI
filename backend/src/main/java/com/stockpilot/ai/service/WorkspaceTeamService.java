package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.repo.PlatformClientProfileRepository;
import com.stockpilot.ai.repo.AuthIdentityRepository;
import com.stockpilot.ai.web.dto.WorkspaceDtos;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class WorkspaceTeamService {
    private final EntityManager em;
    private final Repositories.MembershipRepository memberships;
    private final Repositories.UserRepository users;
    private final Repositories.TenantRepository tenants;
    private final PlatformClientProfileRepository profiles;
    private final TenantWriteLockService locks;
    private final AuditService audit;
    private final AuthIdentityRepository identities;
    public WorkspaceTeamService(EntityManager em, Repositories.MembershipRepository memberships,
        Repositories.UserRepository users, Repositories.TenantRepository tenants, PlatformClientProfileRepository profiles,
        TenantWriteLockService locks, AuditService audit, AuthIdentityRepository identities) {
        this.em = em; this.memberships = memberships; this.users = users; this.tenants = tenants;
        this.profiles = profiles; this.locks = locks; this.audit = audit;
        this.identities = identities;
    }
    @Transactional(readOnly = true)
    public Page<WorkspaceDtos.MemberResponse> members(String search, int page, int size) {
        var tenantId = TenantContext.tenantId();
        var paging = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size)));
        var text = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        if (text.length() > 160) throw ApiErrors.badRequest("Search is too long");
        var predicate = " from UserTenantMembership m, UserAccount u where m.tenantId = :tenantId and u.id = m.userId and (locate(:search,lower(u.fullName)) > 0 or locate(:search,lower(u.email)) > 0)";
        var rows = em.createQuery("select m,u" + predicate + " order by lower(u.fullName),u.id", Object[].class)
            .setParameter("tenantId", tenantId).setParameter("search", text).setFirstResult((int)paging.getOffset()).setMaxResults(paging.getPageSize()).getResultList();
        var count = em.createQuery("select count(m)" + predicate, Long.class).setParameter("tenantId", tenantId).setParameter("search", text).getSingleResult();
        return new PageImpl<>(rows.stream().map(row -> response((UserTenantMembership) row[0], (UserAccount) row[1])).toList(), paging, count);
    }
    @Transactional
    public WorkspaceDtos.MemberResponse changeRole(UUID userId, DomainEnums.Role role) {
        WorkspaceInvitationService.requireAssignable(role);
        var tenantId = TenantContext.tenantId(); locks.lock(tenantId);
        var member = managedMember(userId); var before = member.role;
        member.role = role; memberships.save(member);
        audit.logCurrent("WORKSPACE_MEMBER_ROLE_CHANGED", "UserTenantMembership", member.id, Map.of("userId", userId, "previousRole", before, "role", role));
        return response(member, identities.findWorkspaceUser(tenantId, userId).orElseThrow(() -> ApiErrors.notFound("Workspace member not found")));
    }
    @Transactional
    public void remove(UUID userId) {
        var tenantId = TenantContext.tenantId(); locks.lock(tenantId);
        var member = managedMember(userId);
        memberships.delete(member);
        audit.logCurrent("WORKSPACE_MEMBER_REMOVED", "UserTenantMembership", member.id, Map.of("userId", userId, "role", member.role));
    }
    private UserTenantMembership managedMember(UUID userId) {
        var member = memberships.findByTenantIdAndUserId(TenantContext.tenantId(), userId).orElseThrow(() -> ApiErrors.notFound("Workspace member not found"));
        if (userId.equals(TenantContext.userId())) throw ApiErrors.conflict("You cannot change your own team access here");
        if (member.role == DomainEnums.Role.OWNER || member.role.name().startsWith("PLATFORM_")) throw ApiErrors.forbidden("Owner and platform access cannot be changed through team management");
        return member;
    }
    @Transactional(readOnly = true)
    public Map<String,Object> account() {
        var id = TenantContext.tenantId(); var tenant = tenants.findByIdAndStatus(id, DomainEnums.TenantStatus.ACTIVE)
            .orElseThrow(() -> ApiErrors.notFound("Active workspace not found"));
        var profile = profiles.findByTenantId(id).orElseGet(PlatformClientProfile::new);
        var result = new LinkedHashMap<String,Object>();
        result.put("tenantId", id); result.put("name", tenant.name); result.put("businessMode", tenant.businessMode);
        result.put("currency", tenant.currency); result.put("plan", profile.plan); result.put("subscriptionStatus", profile.subscriptionStatus);
        result.put("subscriptionEndsAt", profile.subscriptionEndsAt); result.put("clerkManaged", tenant.clerkOrganizationId != null);
        var counts = new LinkedHashMap<String,Long>();
        for (var entry : Map.of("members","user_tenant_memberships", "products","products", "warehouses","warehouses", "imports","import_sessions").entrySet()) {
            counts.put(entry.getKey(), ((Number)em.createNativeQuery("select count(*) from " + entry.getValue() + " where tenant_id = :tenantId")
                .setParameter("tenantId", id).getSingleResult()).longValue());
        }
        counts.compute("imports", (key, smartImports) -> smartImports + ((Number)em.createNativeQuery(
            "select count(*) from import_batches where tenant_id = :tenantId").setParameter("tenantId", id).getSingleResult()).longValue());
        result.put("usage", counts); return result;
    }
    private WorkspaceDtos.MemberResponse response(UserTenantMembership member, UserAccount user) {
        return new WorkspaceDtos.MemberResponse(user.id, user.fullName, user.email, member.role, user.active, member.createdAt);
    }
}
