package com.stockpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClerkJwtVerifierTest {
    private static final KeyPair KEY = Jwts.SIG.RS256.keyPair().build();
    private static final String ISSUER = "https://identity.clerk.accounts.dev";
    private static final String ORIGIN = "https://stockpilot.example.com";
    private final ClerkConfiguration configuration = new ClerkConfiguration(true, ISSUER, ORIGIN, "test-backend-key", "");
    private final ClerkJwksService keys = mock(ClerkJwksService.class);
    private final ClerkJwtVerifier verifier = new ClerkJwtVerifier(configuration, keys, new ObjectMapper());

    @Test void verifiesRsaSignatureAndVersionTwoOrganizationWithoutUsingRoleAsPlatformGrant() {
        when(keys.key("test-key")).thenReturn(KEY.getPublic());
        var claims = claims(); claims.put("o", Map.of("id", "org_business", "rol", "admin"));
        var identity = verifier.verify(token(claims, KEY));
        assertThat(identity.subject()).isEqualTo("user_owner");
        assertThat(identity.organizationId()).isEqualTo("org_business");
        assertThat(identity.organizationAdmin()).isTrue();
    }

    @Test void rejectsWrongSignatureIssuerOriginExpiryNotBeforePendingAndImpersonation() {
        when(keys.key("test-key")).thenReturn(KEY.getPublic());
        assertThatThrownBy(() -> verifier.verify(token(claims(), Jwts.SIG.RS256.keyPair().build()))).isInstanceOf(RuntimeException.class);
        for (var change : List.of(Map.of("iss", "https://attacker.example"), Map.of("azp", ORIGIN + ".attacker.example"),
            Map.of("exp", Instant.now().minusSeconds(60).getEpochSecond()), Map.of("nbf", Instant.now().plusSeconds(60).getEpochSecond()),
            Map.of("sts", "pending"), Map.of("act", Map.of("sub", "user_other")), Map.of("sub", "org_attacker"))) {
            var claims = claims(); claims.putAll(change);
            assertThatThrownBy(() -> verifier.verify(token(claims, KEY))).isInstanceOf(RuntimeException.class);
        }
        for (var missing : List.of("azp", "exp", "nbf", "iat", "sid")) {
            var claims = claims(); claims.remove(missing);
            assertThatThrownBy(() -> verifier.verify(token(claims, KEY))).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void rejectsAlgorithmConfusionAndDisabledClerk() {
        var hmac = Jwts.builder().claims(claims()).header().keyId("test-key").and().signWith(Jwts.SIG.HS256.key().build()).compact();
        assertThatThrownBy(() -> verifier.verify(hmac)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(keys);
        var disabled = new ClerkJwtVerifier(new ClerkConfiguration(false, "", "", "", ""), keys, new ObjectMapper());
        assertThatThrownBy(() -> disabled.verify(token(claims(), KEY))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void jwksFetchUsesOnlyConfiguredIssuerAndCachesKnownKeys() throws Exception {
        var http = mock(ClerkHttpClient.class); var mapper = new ObjectMapper();
        var publicKey = (java.security.interfaces.RSAPublicKey) KEY.getPublic();
        var jwk = Map.of("keys", List.of(Map.of("kid", "test-key", "kty", "RSA", "alg", "RS256", "use", "sig",
            "n", Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.getModulus().toByteArray()),
            "e", Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.getPublicExponent().toByteArray()))));
        when(http.send(any())).thenReturn(mapper.valueToTree(jwk));
        var source = new ClerkJwksService(configuration, http);
        var actualVerifier = new ClerkJwtVerifier(configuration, source, mapper);
        var token = Jwts.builder().claims(claims()).header().keyId("test-key").add("jku", "https://attacker.example/jwks").and().signWith(KEY.getPrivate(), Jwts.SIG.RS256).compact();
        actualVerifier.verify(token); actualVerifier.verify(token);
        var request = org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        verify(http, times(1)).send(request.capture());
        assertThat(request.getValue().uri().toString()).isEqualTo(ISSUER + "/.well-known/jwks.json");
        assertThatThrownBy(() -> source.key("unknown")).isInstanceOf(IllegalArgumentException.class);
        verifyNoMoreInteractions(http);
    }

    @Test void enabledConfigurationRequiresTrustedIssuerSecretAndExactOrigins() {
        assertThatThrownBy(() -> new ClerkConfiguration(true, "http://localhost:8080", ORIGIN, "secret", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ClerkConfiguration(true, ISSUER, "*", "secret", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ClerkConfiguration(true, ISSUER, ORIGIN, "", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ClerkConfiguration(true, ISSUER + "/other", ORIGIN, "secret", "")).isInstanceOf(IllegalStateException.class);
    }

    private Map<String, Object> claims() {
        var now = Instant.now().getEpochSecond(); var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", ISSUER); claims.put("azp", ORIGIN); claims.put("sub", "user_owner"); claims.put("sid", "sess_valid");
        claims.put("iat", now); claims.put("nbf", now - 5); claims.put("exp", now + 60); return claims;
    }
    private String token(Map<String, Object> claims, KeyPair key) {
        return Jwts.builder().claims(claims).header().keyId("test-key").and().signWith(key.getPrivate(), Jwts.SIG.RS256).compact();
    }
}
