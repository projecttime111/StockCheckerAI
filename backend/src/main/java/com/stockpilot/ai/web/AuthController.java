package com.stockpilot.ai.web;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.service.AuthService;
import com.stockpilot.ai.service.PermissionService;
import com.stockpilot.ai.service.AuthSessionService;
import com.stockpilot.ai.service.ClerkAccountService;
import com.stockpilot.ai.service.ClerkOrganizationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.stockpilot.ai.web.dto.ApiDtos;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final Repositories.UserRepository users;
    private final PermissionService permissionService;
    private final AuthSessionService sessions;
    private final ClerkAccountService clerkAccounts;
    private final ClerkOrganizationService clerkOrganizations;

    public AuthController(AuthService authService, Repositories.UserRepository users, PermissionService permissionService,
        AuthSessionService sessions, ClerkAccountService clerkAccounts, ClerkOrganizationService clerkOrganizations) {
        this.authService = authService;
        this.users = users;
        this.permissionService = permissionService;
        this.sessions = sessions; this.clerkAccounts = clerkAccounts; this.clerkOrganizations = clerkOrganizations;
    }

    @PostMapping("/register")
    public Object register(@Valid @RequestBody ApiDtos.RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public ApiDtos.AuthResponse login(@Valid @RequestBody ApiDtos.LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/google")
    public ApiDtos.AuthResponse google(@Valid @RequestBody ApiDtos.GoogleLoginRequest request) {
        return authService.loginWithGoogle(request);
    }

    @PostMapping("/verify-email")
    public ApiDtos.AuthMessageResponse verifyEmail(@Valid @RequestBody ApiDtos.VerifyEmailRequest request) {
        return authService.verifyEmail(request);
    }

    @PostMapping("/resend-verification")
    public ApiDtos.AuthMessageResponse resendVerification(@Valid @RequestBody ApiDtos.ResendVerificationRequest request) {
        return authService.resendVerification(request);
    }

    @PostMapping("/forgot-password")
    public ApiDtos.AuthMessageResponse forgotPassword(@Valid @RequestBody ApiDtos.ForgotPasswordRequest request) {
        return authService.forgotPassword(request);
    }

    @PostMapping("/reset-password")
    public ApiDtos.AuthMessageResponse resetPassword(@Valid @RequestBody ApiDtos.ResetPasswordRequest request) {
        return authService.resetPassword(request);
    }

    @PostMapping("/refresh")
    public ApiDtos.AuthResponse refresh(Authentication authentication) {
        return authService.refresh(authentication.getName());
    }

    @GetMapping("/me")
    public ApiDtos.MeResponse me(Authentication authentication) {
        var user = users.findByEmailIgnoreCase(authentication.getName()).orElseThrow();
        return new ApiDtos.MeResponse(user.id, user.email, user.fullName, TenantContext.tenantIdOrNull(), TenantContext.role());
    }

    @GetMapping("/me/permissions")
    public Map<String, Object> permissions() {
        if (TenantContext.role() == null) {
            return Map.of("role", "WORKSPACELESS", "permissions", List.of());
        }
        return permissionService.currentPermissionResponse();
    }

    @GetMapping("/me/tenants")
    public List<Map<String, Object>> tenants(Authentication authentication) {
        var user = users.findByEmailIgnoreCase(authentication.getName()).orElseThrow();
        return sessions.workspaces(user.id);
    }

    @GetMapping("/session")
    public Map<String, Object> session() { return sessions.currentSession(); }

    @PostMapping("/platform")
    public ApiDtos.AuthResponse platform() { return sessions.switchPlatform(); }

    @PostMapping("/workspaces/{id}/switch")
    public ApiDtos.AuthResponse switchWorkspace(@PathVariable UUID id) { return sessions.switchWorkspace(id); }

    @PostMapping("/clerk/link")
    public Map<String, Object> linkClerk(@Valid @RequestBody ClerkLinkRequest request) {
        var user = clerkAccounts.link(request.clerkToken());
        return Map.of("userId", user.id, "clerkLinked", true);
    }

    @PostMapping("/clerk/workspace")
    public ApiDtos.AuthResponse createClerkWorkspace(@Valid @RequestBody ApiDtos.WorkspaceCreateRequest request) {
        return clerkOrganizations.createWorkspace(request);
    }

    @PostMapping("/clerk/organization/link")
    public Map<String, Object> linkOrganization(@Valid @RequestBody ClerkLinkRequest request) {
        return clerkOrganizations.linkOrganization(request.clerkToken());
    }

    public record ClerkLinkRequest(@NotBlank @Size(max = 16384) String clerkToken) { }
}
