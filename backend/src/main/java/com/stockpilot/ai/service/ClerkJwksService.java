package com.stockpilot.ai.service;

import org.springframework.stereotype.Service;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpRequest;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
public class ClerkJwksService {
    private final ClerkConfiguration config;
    private final ClerkHttpClient http;
    private Map<String, PublicKey> keys = Map.of();
    private Instant fetchedAt = Instant.EPOCH;
    private PublicKey pinnedKey;
    public ClerkJwksService(ClerkConfiguration config, ClerkHttpClient http) { this.config = config; this.http = http; }

    public synchronized PublicKey key(String keyId) {
        if (!config.enabled) throw new IllegalStateException("Clerk authentication is disabled");
        try {
            if (!config.publicKey.isBlank()) {
                if (pinnedKey == null) {
                    var pem = config.publicKey.replace("\\n", "\n").replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
                    pinnedKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
                }
                return pinnedKey;
            }
            var now = Instant.now();
            if (now.isAfter(fetchedAt.plusSeconds(300)) || (!keys.containsKey(keyId) && now.isAfter(fetchedAt.plusSeconds(30)))) {
                // Always use configured issuer, never JWT jku/x5u/iss to select a URL.
                var payload = http.send(HttpRequest.newBuilder(URI.create(config.issuer + "/.well-known/jwks.json"))
                    .timeout(Duration.ofSeconds(5)).GET().build());
                var fresh = new HashMap<String, PublicKey>();
                for (var key : payload.path("keys")) {
                    if (!"RSA".equals(key.path("kty").asText()) || (!key.path("use").asText("sig").equals("sig"))
                        || !key.path("alg").asText("RS256").equals("RS256")) continue;
                    var modulus = new BigInteger(1, Base64.getUrlDecoder().decode(key.path("n").asText()));
                    if (modulus.bitLength() < 2048) continue;
                    var exponent = new BigInteger(1, Base64.getUrlDecoder().decode(key.path("e").asText()));
                    fresh.put(key.path("kid").asText(), KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)));
                }
                keys = Map.copyOf(fresh); fetchedAt = now;
            }
            var key = keys.get(keyId);
            if (key == null) throw new IllegalArgumentException("Unknown Clerk signing key");
            return key;
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("Invalid Clerk signing key", ex); }
    }
}
