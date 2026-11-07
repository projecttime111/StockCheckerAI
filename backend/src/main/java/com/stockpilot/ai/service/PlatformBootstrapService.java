package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.UserAccount;
import com.stockpilot.ai.repo.Repositories;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PlatformBootstrapService {
    private final Set<String> emails;
    private final Repositories.UserRepository users;
    private final AuditService audit;
    public PlatformBootstrapService(@Value("${app.platform-admin-emails:${APP_PLATFORM_ADMIN_EMAILS:}}") String emails,
        Repositories.UserRepository users, AuditService audit) {
        this.emails = Arrays.stream(emails.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT))
            .filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
        this.users = users; this.audit = audit;
    }
    /** Called only after authentication of an existing account, never during public registration/JIT creation. */
    @Transactional
    public UserAccount grantConfigured(UserAccount account) {
        if (account.active && account.emailVerified && account.platformRole == null
            && emails.contains(account.email.toLowerCase(Locale.ROOT))) {
            account.platformRole = DomainEnums.Role.PLATFORM_SUPER_ADMIN;
            users.save(account);
            audit.log(null, account.id, "PLATFORM_ADMIN_BOOTSTRAPPED", "UserAccount", account.id, Map.of("source", "explicit_server_configuration"));
        }
        return account;
    }
}
