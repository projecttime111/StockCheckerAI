package com.stockpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Service
public class ClerkJwtVerifier {
    private final ClerkConfiguration config;
    private final ClerkJwksService jwks;
    private final ObjectMapper json;
    public ClerkJwtVerifier(ClerkConfiguration config, ClerkJwksService jwks, ObjectMapper json) {
        this.config = config; this.jwks = jwks; this.json = json;
    }
    public VerifiedClerkIdentity verify(String token) {
        if (!config.enabled) throw new IllegalArgumentException("Clerk authentication is disabled");
        try {
            if (token == null || token.length() > 16384) throw new IllegalArgumentException("Invalid session token");
            var parts = token.split("\\.");
            if (parts.length != 3) throw new IllegalArgumentException("Invalid session token");
            var header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"RS256".equals(header.path("alg").asText())) throw new IllegalArgumentException("Unexpected signing algorithm");
            var keyId = header.path("kid").asText();
            if (keyId.isBlank() || keyId.length() > 256) throw new IllegalArgumentException("Missing signing key");
            var claims = Jwts.parser().verifyWith(jwks.key(keyId)).requireIssuer(config.issuer)
                .clockSkewSeconds(5).build().parseSignedClaims(token).getPayload();
            var subject = claims.getSubject();
            var session = claims.get("sid", String.class);
            if (subject == null || !subject.matches("user_[A-Za-z0-9]{1,120}") || session == null || !session.startsWith("sess_")) {
                throw new IllegalArgumentException("A Clerk user session is required");
            }
            if (claims.getExpiration() == null || claims.getNotBefore() == null || claims.getIssuedAt() == null
                || claims.getIssuedAt().toInstant().isAfter(Instant.now().plusSeconds(5))) throw new IllegalArgumentException("Invalid session times");
            if (!config.authorizedParties.contains(claims.get("azp", String.class))) throw new IllegalArgumentException("Unauthorized session origin");
            if (claims.containsKey("act")) throw new IllegalArgumentException("Impersonated sessions are not permitted");
            if (claims.containsKey("sts") && !"active".equals(claims.get("sts", String.class))) throw new IllegalArgumentException("Session is not active");
            String org = claims.get("org_id", String.class), orgRole = claims.get("org_role", String.class);
            if (claims.get("o") instanceof Map<?, ?> organization) {
                org = organization.get("id") instanceof String value ? value : null;
                orgRole = organization.get("rol") instanceof String value ? "org:" + value : null;
            }
            if (org != null && !org.matches("org_[A-Za-z0-9]{1,120}")) throw new IllegalArgumentException("Invalid organization");
            return new VerifiedClerkIdentity(subject, session, org, orgRole);
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid session token", ex); }
    }
    public record VerifiedClerkIdentity(String subject, String sessionId, String organizationId, String organizationRole) {
        public boolean organizationAdmin() { return organizationId != null && "org:admin".equals(organizationRole); }
    }
}
