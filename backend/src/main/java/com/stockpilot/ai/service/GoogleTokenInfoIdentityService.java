package com.stockpilot.ai.service;

import com.stockpilot.ai.exception.ApiErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class GoogleTokenInfoIdentityService implements GoogleIdentityService {
    private final String clientId;
    private volatile RestClient client;

    public GoogleTokenInfoIdentityService(@Value("${app.auth.google-client-id:}") String clientId) {
        this.clientId = clientId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public GoogleUserInfo verify(String idToken) {
        if (clientId == null || clientId.isBlank()) {
            throw ApiErrors.badRequest("Google sign-in is not configured.");
        }
        Map<String, Object> tokenInfo;
        try {
            tokenInfo = client().get()
                .uri(uriBuilder -> uriBuilder.path("/tokeninfo").queryParam("id_token", idToken).build())
                .retrieve()
                .body(Map.class);
        } catch (Exception ex) {
            throw ApiErrors.badRequest("Google sign-in token is invalid.");
        }
        if (tokenInfo == null) {
            throw ApiErrors.badRequest("Google sign-in token is invalid.");
        }
        var audience = String.valueOf(tokenInfo.getOrDefault("aud", ""));
        var email = String.valueOf(tokenInfo.getOrDefault("email", ""));
        var subject = String.valueOf(tokenInfo.getOrDefault("sub", ""));
        var name = String.valueOf(tokenInfo.getOrDefault("name", email));
        var emailVerified = Boolean.parseBoolean(String.valueOf(tokenInfo.getOrDefault("email_verified", "false")));
        if (!clientId.equals(audience) || subject.isBlank() || email.isBlank() || !emailVerified) {
            throw ApiErrors.badRequest("Google sign-in token is invalid.");
        }
        return new GoogleUserInfo(subject, email.trim().toLowerCase(), name);
    }

    private RestClient client() {
        var current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = RestClient.builder().baseUrl("https://oauth2.googleapis.com").build();
                    client = current;
                }
            }
        }
        return current;
    }
}
