package com.stockpilot.ai.service;

import com.stockpilot.ai.config.JwtService;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.Tenant;
import com.stockpilot.ai.domain.UserAccount;
import com.stockpilot.ai.domain.UserTenantMembership;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.exception.ApiException;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
public class AuthService {
    private final Repositories.UserRepository users;
    private final Repositories.TenantRepository tenants;
    private final Repositories.MembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final AuthTokenService tokenService;
    private final EmailService emailService;
    private final GoogleIdentityService googleIdentityService;
    private final boolean requireEmailVerification;
    private final Duration verificationTokenTtl;
    private final Duration passwordResetTokenTtl;
    private final Duration emailCooldown;
    private final String appPublicUrl;
    private final AuthSessionService sessions;
    private final PlatformBootstrapService platformBootstrap;

    public AuthService(
        Repositories.UserRepository users,
        Repositories.TenantRepository tenants,
        Repositories.MembershipRepository memberships,
        PasswordEncoder passwordEncoder,
        AuthenticationManager authenticationManager,
        JwtService jwtService,
        AuditService auditService,
        AuthTokenService tokenService,
        EmailService emailService,
        GoogleIdentityService googleIdentityService,
        @Value("${app.auth.require-email-verification:false}") boolean requireEmailVerification,
        @Value("${app.auth.verification-token-hours:24}") long verificationTokenHours,
        @Value("${app.auth.password-reset-token-minutes:30}") long passwordResetTokenMinutes,
        @Value("${app.auth.email-cooldown-seconds:60}") long emailCooldownSeconds,
        @Value("${app.public-url:http://localhost:3000}") String appPublicUrl,
        AuthSessionService sessions, PlatformBootstrapService platformBootstrap
    ) {
        this.users = users;
        this.tenants = tenants;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.tokenService = tokenService;
        this.emailService = emailService;
        this.googleIdentityService = googleIdentityService;
        this.requireEmailVerification = requireEmailVerification;
        this.verificationTokenTtl = Duration.ofHours(verificationTokenHours);
        this.passwordResetTokenTtl = Duration.ofMinutes(passwordResetTokenMinutes);
        this.emailCooldown = Duration.ofSeconds(emailCooldownSeconds);
        this.appPublicUrl = trimTrailingSlash(appPublicUrl);
        this.sessions = sessions; this.platformBootstrap = platformBootstrap;
    }

    @Transactional
    public Object register(ApiDtos.RegisterRequest request) {
        var normalizedEmail = normalizeEmail(request.email());
        if (users.existsByEmailIgnoreCase(normalizedEmail)) {
            throw ApiErrors.conflict("An account with this email already exists");
        }
        var tenant = new Tenant();
        tenant.name = request.businessName();
        tenant.businessMode = request.businessMode();
        tenants.save(tenant);

        var user = new UserAccount();
        user.email = normalizedEmail;
        user.fullName = request.fullName();
        user.passwordHash = passwordEncoder.encode(request.password());
        user.emailVerified = !requireEmailVerification;
        user.emailVerifiedAt = user.emailVerified ? Instant.now() : null;
        users.save(user);

        var membership = new UserTenantMembership();
        membership.tenantId = tenant.id;
        membership.userId = user.id;
        membership.role = DomainEnums.Role.OWNER;
        memberships.save(membership);

        auditService.log(tenant.id, user.id, "USER_REGISTERED", "Tenant", tenant.id, Map.of("businessName", tenant.name));
        if (requireEmailVerification) {
            issueVerificationEmail(user, Instant.now());
            return new ApiDtos.AuthMessageResponse("Registration successful. Please check your email to verify your account.", true);
        }
        var token = jwtService.issue(user.id, tenant.id, user.email, membership.role, user.authVersion);
        return new ApiDtos.AuthResponse(token, tenant.id, user.id, membership.role, user.email, user.fullName);
    }

    @Transactional
    public ApiDtos.AuthResponse login(ApiDtos.LoginRequest request) {
        var normalizedEmail = normalizeEmail(request.email());
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(normalizedEmail, request.password()));
        var user = users.findByEmailIgnoreCase(normalizedEmail).orElseThrow();
        if (!user.emailVerified) {
            if (requireEmailVerification) {
                auditService.log(null, user.id, "LOGIN_BLOCKED_EMAIL_NOT_VERIFIED", "UserAccount", user.id, Map.of());
                throw new ApiException(HttpStatus.UNAUTHORIZED, "EMAIL_NOT_VERIFIED", "Please verify your email before logging in.");
            }
            user.emailVerified = true;
            user.emailVerifiedAt = Instant.now();
            user.verificationTokenHash = null;
            user.verificationTokenExpiresAt = null;
            users.save(user);
            auditService.log(null, user.id, "EMAIL_VERIFICATION_BYPASSED", "UserAccount", user.id, Map.of("reason", "verification_disabled"));
        }
        platformBootstrap.grantConfigured(user);
        var response = sessions.defaultSession(user);
        auditService.log(response.tenantId(), user.id, "USER_LOGIN", "UserAccount", user.id, Map.of());
        return response;
    }

    @Transactional
    public ApiDtos.AuthResponse loginWithGoogle(ApiDtos.GoogleLoginRequest request) {
        var google = googleIdentityService.verify(request.idToken());
        var existing = users.findByGoogleSubject(google.subject()).or(() -> users.findByEmailIgnoreCase(google.email()));
        var user = existing.orElseGet(() -> {
                var account = new UserAccount();
                account.email = google.email();
                account.fullName = google.name();
                account.googleSubject = google.subject();
                account.passwordHash = passwordEncoder.encode(tokenService.generateRawToken());
                account.emailVerified = true;
                account.emailVerifiedAt = Instant.now();
                return users.save(account);
            });
        if (!user.active) throw ApiErrors.forbidden("This account is inactive");
        if (user.googleSubject == null || user.googleSubject.isBlank()) {
            user.googleSubject = google.subject();
        }
        if (!user.emailVerified) {
            user.emailVerified = true;
            user.emailVerifiedAt = Instant.now();
            user.verificationTokenHash = null;
            user.verificationTokenExpiresAt = null;
        }
        user.fullName = (user.fullName == null || user.fullName.isBlank()) ? google.name() : user.fullName;
        users.save(user);

        if (existing.isPresent()) platformBootstrap.grantConfigured(user);
        var response = sessions.defaultSession(user);
        auditService.log(response.tenantId(), user.id, "GOOGLE_LOGIN", "UserAccount", user.id, Map.of());
        return response;
    }

    public ApiDtos.AuthResponse refresh(String email) {
        return sessions.currentAuthResponse();
    }

    @Transactional
    public ApiDtos.AuthMessageResponse verifyEmail(ApiDtos.VerifyEmailRequest request) {
        var tokenHash = tokenService.hash(request.token());
        var user = users.findByVerificationTokenHash(tokenHash)
            .orElseThrow(() -> ApiErrors.badRequest("Verification link is invalid or expired."));
        var now = Instant.now();
        if (user.verificationTokenExpiresAt == null || user.verificationTokenExpiresAt.isBefore(now)) {
            throw ApiErrors.badRequest("Verification link is invalid or expired.");
        }
        user.emailVerified = true;
        user.emailVerifiedAt = now;
        user.verificationTokenHash = null;
        user.verificationTokenExpiresAt = null;
        users.save(user);
        auditService.log(null, user.id, "EMAIL_VERIFIED", "UserAccount", user.id, Map.of());
        return new ApiDtos.AuthMessageResponse("Email verified successfully. You can now log in.", false);
    }

    @Transactional
    public ApiDtos.AuthMessageResponse resendVerification(ApiDtos.ResendVerificationRequest request) {
        var now = Instant.now();
        users.findByEmailIgnoreCase(normalizeEmail(request.email()))
            .filter(user -> !user.emailVerified)
            .ifPresent(user -> {
                if (user.verificationSentAt == null || user.verificationSentAt.plus(emailCooldown).isBefore(now)) {
                    issueVerificationEmail(user, now);
                }
            });
        return new ApiDtos.AuthMessageResponse("If an account exists and requires verification, we sent a verification email.", true);
    }

    @Transactional
    public ApiDtos.AuthMessageResponse forgotPassword(ApiDtos.ForgotPasswordRequest request) {
        var now = Instant.now();
        users.findByEmailIgnoreCase(normalizeEmail(request.email())).ifPresent(user -> {
            if (user.passwordResetRequestedAt == null || user.passwordResetRequestedAt.plus(emailCooldown).isBefore(now)) {
                var rawToken = tokenService.generateRawToken();
                user.passwordResetTokenHash = tokenService.hash(rawToken);
                user.passwordResetTokenExpiresAt = now.plus(passwordResetTokenTtl);
                user.passwordResetRequestedAt = now;
                user.passwordResetUsedAt = null;
                users.save(user);
                emailService.sendPasswordResetEmail(user.email, appPublicUrl + "/reset-password?token=" + rawToken);
            }
        });
        return new ApiDtos.AuthMessageResponse("If an account exists, we sent password reset instructions.", false);
    }

    @Transactional
    public ApiDtos.AuthMessageResponse resetPassword(ApiDtos.ResetPasswordRequest request) {
        var tokenHash = tokenService.hash(request.token());
        var user = users.findByPasswordResetTokenHash(tokenHash)
            .orElseThrow(() -> ApiErrors.badRequest("Password reset link is invalid or expired."));
        var now = Instant.now();
        if (user.passwordResetTokenExpiresAt == null || user.passwordResetTokenExpiresAt.isBefore(now)) {
            throw ApiErrors.badRequest("Password reset link is invalid or expired.");
        }
        user.passwordHash = passwordEncoder.encode(request.newPassword());
        user.authVersion = Math.addExact(user.authVersion, 1);
        user.passwordResetTokenHash = null;
        user.passwordResetTokenExpiresAt = null;
        user.passwordResetUsedAt = now;
        users.save(user);
        auditService.log(null, user.id, "PASSWORD_RESET_COMPLETED", "UserAccount", user.id, Map.of());
        return new ApiDtos.AuthMessageResponse("Password reset successful. You can now log in.", false);
    }

    private void issueVerificationEmail(UserAccount user, Instant now) {
        var rawToken = tokenService.generateRawToken();
        user.verificationTokenHash = tokenService.hash(rawToken);
        user.verificationTokenExpiresAt = now.plus(verificationTokenTtl);
        user.verificationSentAt = now;
        user.verificationResendCount++;
        users.save(user);
        emailService.sendVerificationEmail(user.email, appPublicUrl + "/verify-email?token=" + rawToken);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:3000";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
