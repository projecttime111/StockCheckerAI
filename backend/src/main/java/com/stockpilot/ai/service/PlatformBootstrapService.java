package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.UserAccount;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.repo.AuthIdentityRepository;
import jakarta.persistence.EntityManager;
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
    private final AuthIdentityRepository identities;
    private final EntityManager em;
    public PlatformBootstrapService(@Value("${app.platform-admin-emails:${APP_PLATFORM_ADMIN_EMAILS:}}") String emails,
        Repositories.UserRepository users, AuditService audit, AuthIdentityRepository identities, EntityManager em) {
        this.emails = Arrays.stream(emails.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT))
            .filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
        this.users = users; this.audit = audit; this.identities = identities; this.em = em;
    }
    /** Local/Google callers invoke this only after authenticating an existing verified account. */
    @Transactional
    public UserAccount grantConfigured(UserAccount account) {
        if (!needsGrant(account)) return account;
        account = lockCurrent(account);
        grant(account, "explicit_server_configuration");
        return account;
    }

    /** Only a trusted, current Clerk BAPI profile can authorize a Clerk bootstrap; JWT email/org roles cannot. */
    @Transactional
    public UserAccount grantVerifiedClerk(UserAccount account, ClerkBackendClient.VerifiedProfile profile) {
        if (!needsGrant(account)) return account;
        account = lockCurrent(account);
        if (profile != null && account.externalClerkId != null && account.externalClerkId.equals(profile.subject())
            && account.email.equalsIgnoreCase(profile.email())) grant(account, "verified_clerk_server_configuration");
        return account;
    }

    private UserAccount lockCurrent(UserAccount account) {
        var current = identities.lockUser(account.id).orElseThrow();
        em.refresh(current);
        return current;
    }

    private void grant(UserAccount account, String source) {
        if (!needsGrant(account)) return;
        account.platformRole = DomainEnums.Role.PLATFORM_SUPER_ADMIN;
        users.save(account);
        audit.log(null, account.id, "PLATFORM_ADMIN_BOOTSTRAPPED", "UserAccount", account.id, Map.of("source", source));
    }

    public boolean needsGrant(UserAccount account) {
        return account.active && account.emailVerified && account.platformRole == null && account.email != null
            && emails.contains(account.email.toLowerCase(Locale.ROOT));
    }
}
