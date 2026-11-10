package com.stockpilot.ai.repo;
import com.stockpilot.ai.domain.AnalyticsCoverage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AnalyticsCoverageRepository extends JpaRepository<AnalyticsCoverage, UUID> {
    Optional<AnalyticsCoverage> findByTenantId(UUID tenantId);
}
