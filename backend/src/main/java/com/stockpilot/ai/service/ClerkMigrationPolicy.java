package com.stockpilot.ai.service;

import com.stockpilot.ai.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Legacy migration is an explicit operational opt-in; existing identity links remain usable. */
@Component
public class ClerkMigrationPolicy {
    private final boolean enabled;
    public ClerkMigrationPolicy(@Value("${app.clerk.legacy-migration-enabled:${APP_CLERK_LEGACY_MIGRATION_ENABLED:false}}") boolean enabled) {
        this.enabled = enabled;
    }
    public boolean enabled() { return enabled; }
    public void requireEnabled() {
        if (!enabled) throw new ApiException(HttpStatus.FORBIDDEN, "CLERK_MIGRATION_DISABLED",
            "Existing-account migration is not available in this rollout. Contact support for help with an existing account.");
    }
}
