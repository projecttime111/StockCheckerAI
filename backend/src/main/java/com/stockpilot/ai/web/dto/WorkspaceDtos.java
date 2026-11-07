package com.stockpilot.ai.web.dto;

import com.stockpilot.ai.domain.DomainEnums;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class WorkspaceDtos {
    private WorkspaceDtos() { }
    public record InvitationRequest(@Email @NotBlank @Size(max = 190) String email, @NotNull DomainEnums.Role role) { }
    public record InvitationResponse(UUID id, String email, DomainEnums.Role role, String status, Instant expiresAt, Instant createdAt,
        String deliveryStatus, String message) {
        public InvitationResponse(UUID id, String email, DomainEnums.Role role, String status, Instant expiresAt, Instant createdAt) {
            this(id, email, role, status, expiresAt, createdAt, null, null);
        }
    }
    public record InvitationPreview(String workspaceName, String email, DomainEnums.Role role, boolean accountExists, boolean clerkManaged) { }
    public record TokenRequest(@NotBlank @Size(max = 128) String token) { }
    public record InvitationRegistration(@NotBlank @Size(max = 128) String token,
        @NotBlank @Size(max = 160) String fullName, @NotBlank @Size(min = 8, max = 72) String password) { }
    public record MemberResponse(UUID userId, String fullName, String email, DomainEnums.Role role, boolean active, Instant joinedAt) { }
    public record RoleRequest(@NotNull DomainEnums.Role role) { }
}
