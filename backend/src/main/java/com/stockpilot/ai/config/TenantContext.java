package com.stockpilot.ai.config;

import com.stockpilot.ai.domain.DomainEnums;

import java.util.UUID;

public final class TenantContext {
    private static final ThreadLocal<UUID> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<DomainEnums.Role> ROLE = new ThreadLocal<>();
    private static final ThreadLocal<DomainEnums.Role> PLATFORM_ROLE = new ThreadLocal<>();
    private static final ThreadLocal<String> AUTH_PROVIDER = new ThreadLocal<>();
    private static final ThreadLocal<String> CLERK_ORGANIZATION = new ThreadLocal<>();
    private static final ThreadLocal<String> CLERK_ORGANIZATION_ROLE = new ThreadLocal<>();
    private static final ThreadLocal<String> ACCESS_STATE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId, UUID userId, DomainEnums.Role role) {
        TENANT_ID.set(tenantId);
        USER_ID.set(userId);
        ROLE.set(role);
    }

    public static void setAccountOnly(UUID userId) {
        TENANT_ID.remove();
        USER_ID.set(userId);
        ROLE.remove();
    }

    public static void setPlatform(UUID userId, DomainEnums.Role platformRole) {
        TENANT_ID.remove(); USER_ID.set(userId); ROLE.set(platformRole);
    }

    public static void setIdentity(DomainEnums.Role platformRole, String authProvider, String clerkOrganizationId) {
        PLATFORM_ROLE.set(platformRole); AUTH_PROVIDER.set(authProvider); CLERK_ORGANIZATION.set(clerkOrganizationId);
    }

    public static DomainEnums.Role platformRole() { return PLATFORM_ROLE.get(); }
    public static String authProvider() { return AUTH_PROVIDER.get() == null ? "local" : AUTH_PROVIDER.get(); }
    public static String clerkOrganizationId() { return CLERK_ORGANIZATION.get(); }
    public static String clerkOrganizationRole() { return CLERK_ORGANIZATION_ROLE.get(); }
    public static void setClerkOrganizationRole(String role) { CLERK_ORGANIZATION_ROLE.set(role); }
    public static String accessState() { return ACCESS_STATE.get(); }
    public static void setAccessState(String state) { ACCESS_STATE.set(state); }

    public static UUID tenantId() {
        var tenantId = TENANT_ID.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context available");
        }
        return tenantId;
    }

    public static UUID userId() {
        return USER_ID.get();
    }

    public static UUID tenantIdOrNull() {
        return TENANT_ID.get();
    }

    public static DomainEnums.Role role() {
        return ROLE.get();
    }

    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
        ROLE.remove();
        PLATFORM_ROLE.remove(); AUTH_PROVIDER.remove(); CLERK_ORGANIZATION.remove();
        CLERK_ORGANIZATION_ROLE.remove(); ACCESS_STATE.remove();
    }
}
