package com.stockpilot.ai.repo;

import com.stockpilot.ai.domain.PlatformClientProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PlatformClientProfileRepository extends JpaRepository<PlatformClientProfile, UUID> {
    Optional<PlatformClientProfile> findByTenantId(UUID tenantId);
}
