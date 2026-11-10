package com.stockpilot.ai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiConfiguration {
    public final boolean enabled;
    public final String provider;
    public final String model;
    final String apiKey;
    public final int timeoutSeconds;
    public final int userRequestsPerMinute;
    public final int tenantRequestsPerMinute;
    public AiConfiguration(@Value("${app.ai.enabled:${APP_AI_ENABLED:false}}") boolean enabled,
        @Value("${app.ai.provider:${APP_AI_PROVIDER:gemini}}") String provider,
        @Value("${app.ai.model:${APP_AI_MODEL:}}") String model,
        @Value("${app.ai.gemini-api-key:${GEMINI_API_KEY:}}") String apiKey,
        @Value("${app.ai.timeout-seconds:20}") int timeout,
        @Value("${app.ai.user-requests-per-minute:10}") int userRate,
        @Value("${app.ai.tenant-requests-per-minute:60}") int tenantRate) {
        this.enabled=enabled; this.provider=provider; this.model=model.trim(); this.apiKey=apiKey.trim();
        timeoutSeconds=Math.max(2,Math.min(30,timeout));
        userRequestsPerMinute=Math.max(1,Math.min(60,userRate)); tenantRequestsPerMinute=Math.max(1,Math.min(300,tenantRate));
        if(enabled && (!"gemini".equals(provider) || this.apiKey.isBlank() || !this.model.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,100}")))
            throw new IllegalStateException("Hosted AI requires APP_AI_PROVIDER=gemini, GEMINI_API_KEY and a valid APP_AI_MODEL");
    }
}
