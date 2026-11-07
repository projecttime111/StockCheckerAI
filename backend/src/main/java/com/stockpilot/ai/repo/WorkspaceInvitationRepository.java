package com.stockpilot.ai.repo;

import com.stockpilot.ai.domain.WorkspaceInvitation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, UUID> {
    Page<WorkspaceInvitation> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
    Optional<WorkspaceInvitation> findByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);
    Optional<WorkspaceInvitation> findByTenantIdAndId(UUID tenantId, UUID id);
    // The opaque, one-use token is the invitation capability before membership exists.
    Optional<WorkspaceInvitation> findByTokenHash(String tokenHash);
}
