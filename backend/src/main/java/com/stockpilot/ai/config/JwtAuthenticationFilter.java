package com.stockpilot.ai.config;

import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.domain.DomainEnums;
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
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final Repositories.UserRepository users;
    private final Repositories.MembershipRepository memberships;
    private final Repositories.TenantRepository tenants;

    public JwtAuthenticationFilter(JwtService jwtService, Repositories.UserRepository users, Repositories.MembershipRepository memberships, Repositories.TenantRepository tenants) {
        this.jwtService = jwtService;
        this.users = users;
        this.memberships = memberships;
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        try {
            var header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    authenticate(header.substring(7));
                } catch (Exception ex) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"INVALID_TOKEN\",\"message\":\"Authentication token is invalid or expired\"}");
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(String token) {
        var claims = jwtService.parse(token);
        var userId = jwtService.userId(claims);
        var account = users.findById(userId).filter(user -> user.active).orElseThrow();
        var tokenVersion = claims.get("av", Number.class);
        if ((tokenVersion == null ? 0 : tokenVersion.longValue()) != account.authVersion) {
            throw new IllegalStateException("Token has been revoked");
        }
        if (!account.email.equalsIgnoreCase(claims.getSubject())) {
            throw new IllegalStateException("Token subject does not match user");
        }
        if (!account.emailVerified) {
            throw new IllegalStateException("Email is not verified");
        }
        if (claims.get("tid") == null) {
            TenantContext.setAccountOnly(userId);
            var accountOnlyPrincipal = User.withUsername(account.email)
                .password(account.passwordHash)
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_WORKSPACELESS")))
                .build();
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(accountOnlyPrincipal, token, accountOnlyPrincipal.getAuthorities())
            );
            return;
        }
        var tenantId = jwtService.tenantId(claims);
        var membership = memberships.findByTenantIdAndUserId(tenantId, userId).orElseThrow();
        tenants.findByIdAndStatus(tenantId, DomainEnums.TenantStatus.ACTIVE).orElseThrow();
        var role = membership.role;
        TenantContext.set(tenantId, userId, role);
        var principal = User.withUsername(account.email)
            .password(account.passwordHash)
            .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + role.name())))
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities())
        );
    }
}
