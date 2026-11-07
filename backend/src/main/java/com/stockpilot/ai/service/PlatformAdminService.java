package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.PlatformAdminAudit;
import com.stockpilot.ai.domain.PlatformClientProfile;
import com.stockpilot.ai.domain.Tenant;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.PlatformAdminAuditRepository;
import com.stockpilot.ai.repo.PlatformClientProfileRepository;
import com.stockpilot.ai.repo.PlatformReadRepository;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.PlatformDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PlatformAdminService {
    private static final Set<DomainEnums.Role> PLATFORM_ROLES = Set.of(DomainEnums.Role.PLATFORM_SUPER_ADMIN, DomainEnums.Role.PLATFORM_SUPPORT, DomainEnums.Role.PLATFORM_BILLING_ADMIN);
    private static final Set<String> AUDIT_ACTIONS = Set.of("TENANT_SUSPENDED", "TENANT_REACTIVATED", "SUBSCRIPTION_UPDATED", "NOTES_UPDATED");
    private final PlatformReadRepository reads;
    private final PlatformClientProfileRepository profiles;
    private final PlatformAdminAuditRepository audits;
    private final Repositories.UserRepository users;
    private final Repositories.TenantRepository tenants;
    private final PermissionService permissions;
    private final EntityManager entityManager;

    public PlatformAdminService(PlatformReadRepository reads, PlatformClientProfileRepository profiles,
        PlatformAdminAuditRepository audits, Repositories.UserRepository users, Repositories.TenantRepository tenants,
        PermissionService permissions, EntityManager entityManager) {
        this.reads=reads; this.profiles=profiles; this.audits=audits; this.users=users;
        this.tenants=tenants; this.permissions=permissions; this.entityManager=entityManager;
    }

    public PlatformDtos.Dashboard dashboard() { require("platform.system_health.view"); return reads.dashboard(); }

    public PlatformDtos.Page<PlatformDtos.BusinessSummary> businesses(int page, int size, String search, String status, String plan, String subscriptionStatus) {
        require("platform.tenants.view"); validatePage(page,size);
        return reads.businesses(page,size,search(search),enumFilter(status,DomainEnums.TenantStatus.class),
            enumFilter(plan,PlatformClientProfile.Plan.class),enumFilter(subscriptionStatus,PlatformClientProfile.SubscriptionStatus.class));
    }

    public PlatformDtos.BusinessDetail business(UUID tenantId) {
        require("platform.tenants.view");
        var business=requiredBusiness(tenantId);
        String notes = permissions.has("platform.audit.view") ? profiles.findByTenantId(tenantId).map(profile->profile.internalNotes).orElse("") : null;
        return new PlatformDtos.BusinessDetail(business,reads.usage(tenantId),notes);
    }

    public PlatformDtos.Page<PlatformDtos.Member> members(UUID tenantId,int page,int size) {
        require("platform.audit.view"); validatePage(page,size); requiredBusiness(tenantId);
        return reads.members(tenantId,page,size);
    }

    public PlatformDtos.Page<PlatformDtos.ImportSummary> imports(int page,int size,String search,String status,String source,boolean failedOnly,UUID tenantId) {
        require("platform.audit.view"); validatePage(page,size);
        if(tenantId!=null) requiredBusiness(tenantId);
        var statuses=Arrays.stream(DomainEnums.ImportStatus.values()).map(Enum::name).collect(Collectors.toSet());
        statuses.addAll(Arrays.stream(DomainEnums.ImportSessionStatus.values()).map(Enum::name).toList());
        var sources=Arrays.stream(DomainEnums.SourceType.values()).map(Enum::name).collect(Collectors.toSet());
        sources.add("SMART_IMPORT");
        return reads.imports(page,size,search(search),filter(status,statuses),filter(source,sources),failedOnly,tenantId);
    }

    @Transactional
    public PlatformDtos.BusinessDetail updateStatus(UUID tenantId,PlatformDtos.StatusUpdate request) {
        require("platform.tenants.manage"); String reason=reason(request.reason());
        if(request.status()!=DomainEnums.TenantStatus.ACTIVE && request.status()!=DomainEnums.TenantStatus.SUSPENDED)
            throw ApiErrors.badRequest("Status must be ACTIVE or SUSPENDED; deleted businesses cannot be restored here");
        var tenant=lockMutableTenant(tenantId);
        var before=tenant.status;
        if(before==request.status()) throw ApiErrors.conflict("Business already has this status");
        tenant.status=request.status(); tenant.updatedBy=TenantContext.userId();
        audit(tenantId,request.status()==DomainEnums.TenantStatus.SUSPENDED?"TENANT_SUSPENDED":"TENANT_REACTIVATED",reason,
            Map.of("status",before.name()),Map.of("status",tenant.status.name()));
        entityManager.flush();
        return business(tenantId);
    }

    @Transactional
    public PlatformDtos.BusinessDetail updateSubscription(UUID tenantId,PlatformDtos.SubscriptionUpdate request) {
        require("platform.subscriptions.manage"); String reason=reason(request.reason());
        if(request.plan()==null || request.subscriptionStatus()==null) throw ApiErrors.badRequest("Plan and subscription status are required");
        if(request.subscriptionEndsAt()!=null && (request.subscriptionEndsAt().isBefore(Instant.parse("2000-01-01T00:00:00Z")) || request.subscriptionEndsAt().isAfter(Instant.parse("2200-01-01T00:00:00Z"))))
            throw ApiErrors.badRequest("Subscription end must be between 2000 and 2200");
        lockMutableTenant(tenantId);
        var profile=profile(tenantId); var before=subscriptionValues(profile);
        profile.plan=request.plan(); profile.subscriptionStatus=request.subscriptionStatus(); profile.subscriptionEndsAt=request.subscriptionEndsAt();
        profile.updatedBy=TenantContext.userId(); profiles.save(profile);
        audit(tenantId,"SUBSCRIPTION_UPDATED",reason,before,subscriptionValues(profile));
        entityManager.flush();
        return business(tenantId);
    }

    @Transactional
    public PlatformDtos.BusinessDetail updateNotes(UUID tenantId,PlatformDtos.NotesUpdate request) {
        require("platform.tenants.manage"); String reason=reason(request.reason());
        if(request.notes()==null || request.notes().length()>10000) throw ApiErrors.badRequest("Notes must not exceed 10000 characters");
        lockMutableTenant(tenantId);
        var profile=profile(tenantId); int before=profile.internalNotes.length();
        profile.internalNotes=request.notes().trim(); profile.updatedBy=TenantContext.userId(); profiles.save(profile);
        // Do not copy potentially sensitive support notes into the broadly readable audit feed.
        audit(tenantId,"NOTES_UPDATED",reason,Map.of("notesLength",before),Map.of("notesLength",profile.internalNotes.length()));
        entityManager.flush();
        return business(tenantId);
    }

    public PlatformDtos.Page<PlatformDtos.AuditEntry> audit(int page,int size,UUID tenantId,String action) {
        require("platform.audit.view"); validatePage(page,size);
        if(tenantId!=null) requiredBusiness(tenantId);
        String acceptedAction=filter(action,AUDIT_ACTIONS);
        Specification<PlatformAdminAudit> specification=(root,query,cb)-> {
            var predicates=new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if(tenantId!=null) predicates.add(cb.equal(root.get("targetTenantId"),tenantId));
            if(acceptedAction!=null) predicates.add(cb.equal(root.get("action"),acceptedAction));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result=audits.findAll(specification,PageRequest.of(page,size,Sort.by(Sort.Order.desc("createdAt"),Sort.Order.asc("id"))));
        var actorNames=users.findAllById(result.stream().map(row->row.actorUserId).distinct().toList()).stream()
            .collect(Collectors.toMap(row->row.id,row->row.fullName==null?"":row.fullName));
        var tenantNames=tenants.findAllById(result.stream().map(row->row.targetTenantId).distinct().toList()).stream()
            .collect(Collectors.toMap(row->row.id,row->row.name));
        return PlatformReadRepository.page(result.stream().map(row->new PlatformDtos.AuditEntry(row.id,row.targetTenantId,
            tenantNames.get(row.targetTenantId),row.actorUserId,actorNames.get(row.actorUserId),row.action,row.reason,
            row.beforeValues,row.afterValues,row.createdAt)).toList(),page,size,result.getTotalElements());
    }

    public PlatformDtos.SystemHealth system() {
        require("platform.system_health.view");
        var checkedAt=Instant.now(); long start=System.nanoTime();
        // A failed database query is an actual API failure, never a fabricated healthy response.
        boolean available=reads.databaseAvailable(); long elapsed=(System.nanoTime()-start)/1_000_000;
        var status=reads.importStatusCounts();
        return new PlatformDtos.SystemHealth(Instant.now(),new PlatformDtos.DatabaseHealth(available?"UP":"DOWN",elapsed,checkedAt),
            ManagementFactory.getRuntimeMXBean().getUptime()/1000,status.values().stream().mapToLong(Long::longValue).sum(),
            status.getOrDefault("FAILED",0L)+status.getOrDefault("VALIDATION_FAILED",0L),status.getOrDefault("COMMITTING",0L),
            reads.oldestCommitting(),reads.failuresSince(checkedAt.minus(24,ChronoUnit.HOURS)),reads.lastFailure(),status);
    }

    private void require(String permission) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || TenantContext.userId()==null || TenantContext.tenantIdOrNull()!=null
            || TenantContext.role()==null || TenantContext.platformRole()!=TenantContext.role()
            || !PLATFORM_ROLES.contains(TenantContext.role()) || !permissions.has(permission))
            throw ApiErrors.forbidden("This action requires an authorized platform session");
    }
    private PlatformDtos.BusinessSummary requiredBusiness(UUID tenantId) {
        var business=reads.business(tenantId);
        if(business==null) throw ApiErrors.notFound("Business not found");
        return business;
    }
    private Tenant lockMutableTenant(UUID tenantId) {
        // Same tenant-row lock as TenantWriteLockService; administrative reactivation must also lock suspended rows.
        var tenant=entityManager.find(Tenant.class,tenantId,LockModeType.PESSIMISTIC_WRITE);
        if(tenant==null) throw ApiErrors.notFound("Business not found");
        entityManager.refresh(tenant,LockModeType.PESSIMISTIC_WRITE);
        if(tenant.status==DomainEnums.TenantStatus.DELETED) throw ApiErrors.conflict("Deleted businesses cannot be changed");
        return tenant;
    }
    private PlatformClientProfile profile(UUID tenantId) {
        return profiles.findByTenantId(tenantId).orElseGet(()-> {
            var profile=new PlatformClientProfile(); profile.tenantId=tenantId; profile.createdBy=TenantContext.userId(); return profile;
        });
    }
    private Map<String,Object> subscriptionValues(PlatformClientProfile profile) {
        var values=new LinkedHashMap<String,Object>(); values.put("plan",profile.plan.name()); values.put("subscriptionStatus",profile.subscriptionStatus.name());
        values.put("subscriptionEndsAt",profile.subscriptionEndsAt==null?null:profile.subscriptionEndsAt.toString()); return values;
    }
    private void audit(UUID tenant,String action,String reason,Map<String,Object> before,Map<String,Object> after) {
        var entry=new PlatformAdminAudit(); entry.targetTenantId=tenant; entry.actorUserId=TenantContext.userId();
        entry.action=action; entry.reason=reason; entry.beforeValues=before; entry.afterValues=after;
        entry.createdBy=entry.actorUserId; entry.updatedBy=entry.actorUserId; audits.save(entry);
    }
    private static String reason(String reason) {
        if(reason==null || reason.isBlank() || reason.length()>1000) throw ApiErrors.badRequest("A reason of 1 to 1000 characters is required");
        return reason.trim();
    }
    private static void validatePage(int page,int size) {
        if(page<0 || size<1 || size>100) throw ApiErrors.badRequest("Page must be nonnegative and size must be between 1 and 100");
    }
    private static String search(String search) {
        if(search==null || search.isBlank()) return null;
        if(search.length()>200) throw ApiErrors.badRequest("Search must not exceed 200 characters"); return search.trim();
    }
    private static String filter(String value,Set<String> allowed) {
        if(value==null || value.isBlank()) return null; String normalized=value.trim().toUpperCase(Locale.ROOT);
        if(!allowed.contains(normalized)) throw ApiErrors.badRequest("Unsupported filter value: "+value); return normalized;
    }
    private static <E extends Enum<E>> String enumFilter(String value,Class<E> type) {
        return filter(value,Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.toSet()));
    }
}
