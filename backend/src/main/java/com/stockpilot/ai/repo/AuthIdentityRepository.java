package com.stockpilot.ai.repo;

import com.stockpilot.ai.domain.Tenant;
import com.stockpilot.ai.domain.UserAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface AuthIdentityRepository extends Repository<UserAccount, UUID> {
    Optional<UserAccount> findByExternalClerkId(String externalClerkId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.id = :id")
    Optional<UserAccount> lockUser(@Param("id") UUID id);
    @Query("select t from Tenant t where t.clerkOrganizationId = :orgId")
    Optional<Tenant> findTenantByClerkOrganizationId(@Param("orgId") String orgId);
    @Query("select u from UserAccount u where u.id = :userId and exists (select m.id from UserTenantMembership m where m.tenantId = :tenantId and m.userId = u.id)")
    Optional<UserAccount> findWorkspaceUser(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);
}
