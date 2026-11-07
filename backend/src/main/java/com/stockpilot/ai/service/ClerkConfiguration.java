package com.stockpilot.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ClerkConfiguration {
    public final boolean enabled;
    public final String issuer;
    public final Set<String> authorizedParties;
    final String secretKey;
    final String publicKey;

    public ClerkConfiguration(@Value("${app.clerk.enabled:${APP_CLERK_ENABLED:false}}") boolean enabled,
        @Value("${app.clerk.issuer:${CLERK_ISSUER:}}") String issuer,
        @Value("${app.clerk.authorized-parties:${CLERK_AUTHORIZED_PARTIES:}}") String parties,
        @Value("${app.clerk.secret-key:${CLERK_SECRET_KEY:}}") String secretKey,
        @Value("${app.clerk.jwt-public-key:${CLERK_JWT_PUBLIC_KEY:}}") String publicKey) {
        this.enabled = enabled;
        this.issuer = issuer.trim().replaceAll("/+$", "");
        this.authorizedParties = Arrays.stream(parties.split(",")).map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
        this.secretKey = secretKey.trim(); this.publicKey = publicKey.trim();
        if (enabled) {
            var uri = URI.create(this.issuer);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty()) {
                throw new IllegalStateException("CLERK_ISSUER must be the trusted HTTPS Clerk instance origin");
            }
            if (authorizedParties.isEmpty() || authorizedParties.stream().anyMatch(p -> !isOrigin(p))) {
                throw new IllegalStateException("CLERK_AUTHORIZED_PARTIES must contain exact permitted application origins");
            }
            if (this.secretKey.isBlank()) throw new IllegalStateException("CLERK_SECRET_KEY is required for verified account provisioning");
        }
    }

    private static boolean isOrigin(String value) {
        try {
            var uri = URI.create(value);
            return uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                && uri.getPath().isEmpty() && ("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())));
        } catch (IllegalArgumentException ex) { return false; }
    }
}
