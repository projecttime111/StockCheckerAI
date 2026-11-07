package com.stockpilot.ai.repo;

import com.stockpilot.ai.domain.PlatformAdminAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.UUID;

public interface PlatformAdminAuditRepository extends JpaRepository<PlatformAdminAudit, UUID>, JpaSpecificationExecutor<PlatformAdminAudit> { }
