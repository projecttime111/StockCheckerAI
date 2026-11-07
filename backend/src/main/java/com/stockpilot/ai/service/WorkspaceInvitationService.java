package com.stockpilot.ai.service;

import com.stockpilot.ai.config.JwtService;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.repo.WorkspaceInvitationRepository;
import com.stockpilot.ai.web.dto.ApiDtos;
import com.stockpilot.ai.web.dto.WorkspaceDtos;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class WorkspaceInvitationService {
    public static final Set<DomainEnums.Role> ASSIGNABLE = Collections.unmodifiableSet(EnumSet.of(
        DomainEnums.Role.ADMIN, DomainEnums.Role.MANAGER, DomainEnums.Role.STAFF,
        DomainEnums.Role.WAREHOUSE_STAFF, DomainEnums.Role.SALES_STAFF, DomainEnums.Role.PURCHASE_MANAGER,
        DomainEnums.Role.ACCOUNTANT, DomainEnums.Role.VIEWER, DomainEnums.Role.AUDITOR));
    private final WorkspaceInvitationRepository invitations;
    private final Repositories.TenantRepository tenants;
    private final Repositories.UserRepository users;
    private final Repositories.MembershipRepository memberships;
    private final TenantWriteLockService locks;
    private final AuthTokenService tokens;
    private final EmailService emails;
    private final ClerkBackendClient clerk;
    private final AuditService audit;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final EntityManager em;
    private final String publicUrl;

    public WorkspaceInvitationService(WorkspaceInvitationRepository invitations, Repositories.TenantRepository tenants,
        Repositories.UserRepository users, Repositories.MembershipRepository memberships, TenantWriteLockService locks,
        AuthTokenService tokens, EmailService emails, ClerkBackendClient clerk, AuditService audit,
        PasswordEncoder passwords, JwtService jwt, EntityManager em, @Value("${app.public-url:http://localhost:3000}") String publicUrl) {
        this.invitations = invitations; this.tenants = tenants; this.users = users; this.memberships = memberships;
        this.locks = locks; this.tokens = tokens; this.emails = emails; this.clerk = clerk; this.audit = audit;
        this.passwords = passwords; this.jwt = jwt; this.em = em; this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public Page<WorkspaceDtos.InvitationResponse> list(int page, int size) {
        return invitations.findByTenantIdOrderByCreatedAtDesc(TenantContext.tenantId(), PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size))))
            .map(this::response);
    }

    @Transactional
    public WorkspaceDtos.InvitationResponse invite(WorkspaceDtos.InvitationRequest request) {
        requireAssignable(request.role());
        var tenantId = TenantContext.tenantId(); locks.lock(tenantId);
        var tenant = activeTenant(tenantId);
        var email = request.email().trim().toLowerCase(Locale.ROOT);
        users.findByEmailIgnoreCase(email).ifPresent(user -> {
            if (memberships.findByTenantIdAndUserId(tenantId, user.id).isPresent()) throw ApiErrors.conflict("This account already belongs to the workspace");
            if (!user.active) throw ApiErrors.conflict("This account is unavailable");
        });
        var invitation = invitations.findByTenantIdAndEmailIgnoreCase(tenantId, email).orElseGet(WorkspaceInvitation::new);
        var raw = tokens.generateRawToken();
        invitation.tenantId = tenantId; invitation.email = email; invitation.role = request.role();
        invitation.tokenHash = tokens.hash(raw); invitation.status = "PENDING"; invitation.invitedBy = TenantContext.userId();
        invitation.expiresAt = Instant.now().plus(7, ChronoUnit.DAYS); invitation.acceptedBy = null; invitation.acceptedAt = null;
        invitations.saveAndFlush(invitation);
        var deliveryStatus = "SENT";
        var message = "Invitation sent. The teammate can join using the email link.";
        if (tenant.clerkOrganizationId != null) {
            var inviter = users.findById(TenantContext.userId()).orElseThrow();
            if (inviter.externalClerkId == null) throw ApiErrors.badRequest("Sign in with the linked Clerk account to invite organization members");
            var delivery = clerk.inviteOrganization(tenant.clerkOrganizationId, email, inviter.externalClerkId, publicUrl + "/auth/complete");
            deliveryStatus = delivery.status();
            if (delivery.expiresAt() != null && delivery.expiresAt().isBefore(invitation.expiresAt)) invitation.expiresAt = delivery.expiresAt();
            if ("EXISTING_MEMBER".equals(deliveryStatus)) {
                message = "Access invitation recorded. This person already belongs to the Clerk organization; ask them to sign in and select this workspace. No email was sent.";
            } else if ("EXISTING_INVITATION".equals(deliveryStatus)) {
                message = "Invitation recorded. Use the existing Clerk invitation email before the expiry shown; no new email was sent.";
            }
        } else {
            emails.sendWorkspaceInvitation(email, tenant.name, publicUrl + "/team/accept?token=" + raw);
        }
        audit.logCurrent("SENT".equals(deliveryStatus) ? "WORKSPACE_INVITATION_SENT" : "WORKSPACE_INVITATION_RECORDED", "WorkspaceInvitation", invitation.id,
            Map.of("email", email, "role", request.role().name(), "deliveryStatus", deliveryStatus));
        return new WorkspaceDtos.InvitationResponse(invitation.id, invitation.email, invitation.role, invitation.status,
            invitation.expiresAt, invitation.createdAt, deliveryStatus, message);
    }

    @Transactional
    public void revoke(UUID id) {
        var tenantId = TenantContext.tenantId(); locks.lock(tenantId);
        var invitation = invitations.findByTenantIdAndId(tenantId, id).orElseThrow(() -> ApiErrors.notFound("Invitation not found"));
        if (!"PENDING".equals(invitation.status)) throw ApiErrors.conflict("Only pending invitations can be revoked");
        invitation.status = "REVOKED"; invitations.save(invitation);
        audit.logCurrent("WORKSPACE_INVITATION_REVOKED", "WorkspaceInvitation", id, Map.of());
    }

    @Transactional(readOnly = true)
    public WorkspaceDtos.InvitationPreview preview(String raw) {
        var invitation = valid(raw);
        var tenant = activeTenant(invitation.tenantId);
        return new WorkspaceDtos.InvitationPreview(tenant.name, invitation.email, invitation.role,
            users.existsByEmailIgnoreCase(invitation.email), tenant.clerkOrganizationId != null);
    }

    @Transactional
    public ApiDtos.AuthResponse register(WorkspaceDtos.InvitationRegistration request) {
        var invitation = lockedValid(request.token());
        if (activeTenant(invitation.tenantId).clerkOrganizationId != null) throw ApiErrors.badRequest("Accept this invitation through Clerk");
        if (users.existsByEmailIgnoreCase(invitation.email)) throw ApiErrors.conflict("An account already exists. Sign in to accept this invitation");
        var account = new UserAccount(); account.email = invitation.email; account.fullName = request.fullName().trim();
        account.passwordHash = passwords.encode(request.password()); account.emailVerified = true; account.emailVerifiedAt = Instant.now();
        users.save(account);
        var member = attach(invitation, account);
        return localSession(account, member);
    }

    @Transactional
    public ApiDtos.AuthResponse accept(String raw) {
        if ("clerk".equals(TenantContext.authProvider())) throw ApiErrors.badRequest("Use local sign-in for a local workspace invitation; accept Clerk-managed invitations through Clerk");
        var invitation = lockedValid(raw);
        var account = users.findById(TenantContext.userId()).filter(user -> user.active && user.emailVerified)
            .orElseThrow(() -> ApiErrors.forbidden("Sign in with the invited account"));
        if (!account.email.equalsIgnoreCase(invitation.email)) throw ApiErrors.forbidden("This invitation belongs to a different email address");
        if (activeTenant(invitation.tenantId).clerkOrganizationId != null) throw ApiErrors.badRequest("Accept this invitation through Clerk");
        return localSession(account, attach(invitation, account));
    }

    /** Called only after the authentication adapter verifies the signed active Clerk organization. */
    @Transactional
    public Optional<UserTenantMembership> resolveClerkMembership(Tenant tenant, UserAccount account) {
        if (tenant.clerkOrganizationId == null || account.externalClerkId == null || !account.active || !account.emailVerified) return Optional.empty();
        locks.lock(tenant.id);
        var existing = memberships.findByTenantIdAndUserId(tenant.id, account.id);
        if (existing.isPresent()) return existing;
        var invitation = invitations.findByTenantIdAndEmailIgnoreCase(tenant.id, account.email).orElse(null);
        if (invitation == null || !"PENDING".equals(invitation.status) || !invitation.expiresAt.isAfter(Instant.now())) return Optional.empty();
        var currentProfile = clerk.verifiedProfile(account.externalClerkId);
        if (!currentProfile.email().equalsIgnoreCase(invitation.email)) return Optional.empty();
        return Optional.of(attach(invitation, account));
    }

    private UserTenantMembership attach(WorkspaceInvitation invitation, UserAccount account) {
        requireAssignable(invitation.role);
        var member = memberships.findByTenantIdAndUserId(invitation.tenantId, account.id).orElseGet(() -> {
            var created = new UserTenantMembership(); created.tenantId = invitation.tenantId; created.userId = account.id; created.role = invitation.role;
            return memberships.save(created);
        });
        invitation.status = "ACCEPTED"; invitation.acceptedBy = account.id; invitation.acceptedAt = Instant.now(); invitations.save(invitation);
        audit.log(invitation.tenantId, account.id, "WORKSPACE_INVITATION_ACCEPTED", "WorkspaceInvitation", invitation.id, Map.of("role", member.role.name()));
        return member;
    }
    private ApiDtos.AuthResponse localSession(UserAccount account, UserTenantMembership member) {
        return new ApiDtos.AuthResponse(jwt.issue(account.id, member.tenantId, account.email, member.role, account.authVersion), member.tenantId, account.id, member.role, account.email, account.fullName);
    }
    private WorkspaceInvitation lockedValid(String raw) {
        var invitation = valid(raw); locks.lock(invitation.tenantId); em.refresh(invitation); assertPending(invitation); return invitation;
    }
    private WorkspaceInvitation valid(String raw) {
        if (raw == null || raw.length() > 128 || raw.isBlank()) throw ApiErrors.badRequest("Invalid invitation");
        var invitation = invitations.findByTokenHash(tokens.hash(raw)).orElseThrow(() -> ApiErrors.notFound("Invitation not found"));
        assertPending(invitation); return invitation;
    }
    private void assertPending(WorkspaceInvitation invitation) {
        if (!"PENDING".equals(invitation.status) || invitation.expiresAt == null || !invitation.expiresAt.isAfter(Instant.now()))
            throw ApiErrors.conflict("This invitation has expired or is no longer available");
    }
    private Tenant activeTenant(UUID id) {
        return tenants.findByIdAndStatus(id, DomainEnums.TenantStatus.ACTIVE).orElseThrow(() -> ApiErrors.notFound("Workspace is not active"));
    }
    public static void requireAssignable(DomainEnums.Role role) {
        if (role == null || !ASSIGNABLE.contains(role)) throw ApiErrors.badRequest("Choose a business team role; owner and platform roles cannot be assigned here");
    }
    private WorkspaceDtos.InvitationResponse response(WorkspaceInvitation invitation) {
        var status = "PENDING".equals(invitation.status) && !invitation.expiresAt.isAfter(Instant.now()) ? "EXPIRED" : invitation.status;
        return new WorkspaceDtos.InvitationResponse(invitation.id, invitation.email, invitation.role, status, invitation.expiresAt, invitation.createdAt);
    }
}
