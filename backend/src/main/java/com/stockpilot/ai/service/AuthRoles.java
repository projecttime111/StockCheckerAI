package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;

public final class AuthRoles {
    private AuthRoles() { }
    public static boolean isPlatform(DomainEnums.Role role) {
        return role == DomainEnums.Role.PLATFORM_SUPER_ADMIN || role == DomainEnums.Role.PLATFORM_SUPPORT
            || role == DomainEnums.Role.PLATFORM_BILLING_ADMIN;
    }
}
