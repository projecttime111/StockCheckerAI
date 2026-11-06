package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.Tenant;
import com.stockpilot.ai.exception.ApiErrors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Serializes tenant-wide invariants across JVMs and holds the lock until commit. */
@Service
public class TenantWriteLockService {
    private final EntityManager entityManager;
    public TenantWriteLockService(EntityManager entityManager) { this.entityManager = entityManager; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID tenantId) {
        var tenant = entityManager.find(Tenant.class, tenantId, LockModeType.PESSIMISTIC_WRITE);
        if (tenant == null) {
            throw ApiErrors.notFound("Workspace not found");
        }
        // Authorization may have run before another request deleted the workspace. Refresh
        // while holding its lock so a previously managed entity cannot hide that transition.
        entityManager.refresh(tenant, LockModeType.PESSIMISTIC_WRITE);
        if (tenant.status != com.stockpilot.ai.domain.DomainEnums.TenantStatus.ACTIVE) {
            throw ApiErrors.conflict("Workspace is no longer active");
        }
    }
}
