package com.stockpilot.ai.repo;

import com.stockpilot.ai.domain.ImportJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {
    Optional<ImportJob> findByTenantIdAndRequestId(UUID tenantId, UUID requestId);
    Optional<ImportJob> findByTenantIdAndImportSessionIdAndId(UUID tenantId, UUID sessionId, UUID id);
    List<ImportJob> findByTenantIdAndImportSessionIdOrderByCreatedAtDesc(UUID tenantId, UUID sessionId, Pageable page);
    List<ImportJob> findByTenantIdAndStatusIn(UUID tenantId, Collection<String> statuses);
    // Explicit SQL is needed because Hibernate's H2 dialect ignores the portable SKIP_LOCKED hint.
    @Query(value="select * from import_jobs where (status = 'QUEUED' and (lease_until is null or lease_until <= :now)) or (status = 'RUNNING' and lease_until < :now) order by created_at limit :maxRows for update skip locked",nativeQuery=true)
    List<ImportJob> claimable(@Param("now") Instant now,@Param("maxRows") int maxRows);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ImportJob j where j.id = :id")
    Optional<ImportJob> locked(@Param("id") UUID id);
}
