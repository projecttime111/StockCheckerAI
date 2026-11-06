package com.stockpilot.ai.repo;

import com.stockpilot.ai.domain.MutationReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface MutationReceiptRepository extends JpaRepository<MutationReceipt, UUID> {
    Optional<MutationReceipt> findByTenantIdAndUserIdAndOperationAndRequestKey(UUID tenantId, UUID userId, String operation, String requestKey);
}
