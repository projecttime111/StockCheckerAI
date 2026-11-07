package com.stockpilot.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.exception.ApiException;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.service.*;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final Repositories.UserRepository users;
    private final Repositories.MembershipRepository memberships;
    private final Repositories.TenantRepository tenants;
    private final ClerkJwtVerifier clerkVerifier;
    private final ClerkAccountService clerkAccounts;
    private final AuthSessionService sessions;
    private final ObjectMapper json;
    public JwtAuthenticationFilter(JwtService jwtService, Repositories.UserRepository users,
        Repositories.MembershipRepository memberships, Repositories.TenantRepository tenants,
        ClerkJwtVerifier clerkVerifier, ClerkAccountService clerkAccounts, AuthSessionService sessions, ObjectMapper json) {
        this.jwtService = jwtService; this.users = users; this.memberships = memberships; this.tenants = tenants;
        this.clerkVerifier = clerkVerifier; this.clerkAccounts = clerkAccounts; this.sessions = sessions; this.json = json;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try {
            var header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                try { authenticate(header.substring(7), request.getHeader("X-StockPilot-Mode")); }
                catch (Exception ex) {
                    int status = ex instanceof ApiException api ? api.status.value() : 401;
                    var error = ex instanceof ApiException api ? api.errorCode : "INVALID_TOKEN";
                    var message = ex instanceof ApiException ? ex.getMessage() : "Authentication token is invalid or expired";
                    response.setStatus(status); response.setContentType("application/json");
                    json.writeValue(response.getWriter(), Map.of("timestamp", Instant.now().toString(), "status", status,
                        "error", error, "message", message, "fieldErrors", Map.of(), "path", request.getRequestURI()));
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally { TenantContext.clear(); SecurityContextHolder.clearContext(); }
    }
    private void authenticate(String token, String mode) {
        Claims claims;
        try { claims = jwtService.parse(token); }
        catch (JwtException | IllegalArgumentException ex) {
            var identity = clerkVerifier.verify(token);
            var account = clerkAccounts.resolve(identity);
            sessions.establishClerk(account, identity, mode);
            authenticatePrincipal(account.email, token); return;
        }
        var userId = jwtService.userId(claims);
        var account = users.findById(userId).filter(user -> user.active && user.emailVerified).orElseThrow();
        var tokenVersion = claims.get("av", Number.class);
        if ((tokenVersion == null ? 0 : tokenVersion.longValue()) != account.authVersion
            || !account.email.equalsIgnoreCase(claims.getSubject())) throw new IllegalStateException("Token has been revoked");
        TenantContext.setIdentity(AuthRoles.isPlatform(account.platformRole) ? account.platformRole : null, "local", null);
        if ("platform".equals(claims.get("mode", String.class))) {
            if (claims.get("tid") != null || !AuthRoles.isPlatform(account.platformRole)) throw new IllegalStateException("Global platform grant is required");
            TenantContext.setPlatform(userId, account.platformRole);
        } else if (claims.get("tid") == null) {
            TenantContext.setAccountOnly(userId);
        } else {
            var tenantId = jwtService.tenantId(claims);
            var membership = memberships.findByTenantIdAndUserId(tenantId, userId).orElseThrow();
            tenants.findByIdAndStatus(tenantId, DomainEnums.TenantStatus.ACTIVE).orElseThrow();
            if (AuthRoles.isPlatform(membership.role)) throw new IllegalStateException("A business membership cannot grant platform access");
            TenantContext.set(tenantId, userId, membership.role);
        }
        authenticatePrincipal(account.email, token);
    }
    private void authenticatePrincipal(String email, String token) {
        var role = TenantContext.role();
        var principal = User.withUsername(email).password("")
            .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + (role == null ? "WORKSPACELESS" : role.name())))).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities()));
    }
}
