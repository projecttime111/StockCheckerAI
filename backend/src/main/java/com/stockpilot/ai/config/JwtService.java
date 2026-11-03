package com.stockpilot.ai.config;

import com.stockpilot.ai.domain.DomainEnums;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private static final String LOCAL_DEFAULT_SECRET = "local-dev-only-change-this-secret-local-dev-only-change-this-secret";
    private final SecretKey key;
    private final long accessTokenMinutes;

    public JwtService(
        @Value("${app.jwt.secret}") String secret,
        @Value("${app.jwt.access-token-minutes}") long accessTokenMinutes,
        @Value("${spring.profiles.active:}") String activeProfiles
    ) {
        var profileList = Arrays.stream(activeProfiles.split(",")).map(String::trim).toList();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
        }
        if (profileList.contains("prod") && LOCAL_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException("Production profile requires a non-default JWT_SECRET");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public String issue(UUID userId, UUID tenantId, String email, DomainEnums.Role role) {
        return issue(userId, tenantId, email, role, 0);
    }

    public String issue(UUID userId, UUID tenantId, String email, DomainEnums.Role role, long authVersion) {
        var now = Instant.now();
        return Jwts.builder()
            .subject(email)
            .claim("uid", userId.toString())
            .claim("tid", tenantId.toString())
            .claim("role", role.name())
            .claim("av", authVersion)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(accessTokenMinutes * 60)))
            .signWith(key)
            .compact();
    }

    public String issueAccountOnly(UUID userId, String email) {
        return issueAccountOnly(userId, email, 0);
    }

    public String issueAccountOnly(UUID userId, String email, long authVersion) {
        var now = Instant.now();
        return Jwts.builder()
            .subject(email)
            .claim("uid", userId.toString())
            .claim("av", authVersion)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(accessTokenMinutes * 60)))
            .signWith(key)
            .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public UUID userId(Claims claims) {
        return UUID.fromString((String) claims.get("uid"));
    }

    public UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tid"));
    }

    public DomainEnums.Role role(Claims claims) {
        return DomainEnums.Role.valueOf((String) claims.get("role"));
    }
}
