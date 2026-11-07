package com.stockpilot.ai.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspace_invitations", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "email"}))
public class WorkspaceInvitation extends TenantOwnedEntity {
    @Column(nullable = false, length = 190) public String email;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) public DomainEnums.Role role;
    @Column(nullable = false, length = 64, unique = true) public String tokenHash;
    @Column(nullable = false, length = 16) public String status = "PENDING";
    public Instant expiresAt;
    public UUID invitedBy;
    public UUID acceptedBy;
    public Instant acceptedAt;
}
