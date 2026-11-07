package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.UserAccount;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.exception.ApiException;
import com.stockpilot.ai.repo.AuthIdentityRepository;
import com.stockpilot.ai.repo.Repositories;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ClerkAccountService {
    private final AuthIdentityRepository identities;
    private final Repositories.UserRepository users;
    private final ClerkBackendClient clerk;
    private final ClerkJwtVerifier verifier;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    public ClerkAccountService(AuthIdentityRepository identities, Repositories.UserRepository users,
        ClerkBackendClient clerk, ClerkJwtVerifier verifier, PasswordEncoder passwords, AuditService audit) {
        this.identities = identities; this.users = users; this.clerk = clerk; this.verifier = verifier;
        this.passwords = passwords; this.audit = audit;
    }
    @Transactional
    public UserAccount resolve(ClerkJwtVerifier.VerifiedClerkIdentity identity) {
        var linked = identities.findByExternalClerkId(identity.subject());
        if (linked.isPresent()) return requireActive(linked.get());
        var profile = clerk.verifiedProfile(identity.subject());
        if (users.existsByEmailIgnoreCase(profile.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "CLERK_ACCOUNT_LINK_REQUIRED", "Sign in with your existing StockPilot account and explicitly link Clerk before continuing.");
        }
        var account = new UserAccount(); account.externalClerkId = identity.subject();
        account.email = profile.email(); account.fullName = profile.fullName();
        account.passwordHash = passwords.encode(UUID.randomUUID() + ":" + UUID.randomUUID());
        account.emailVerified = true; account.emailVerifiedAt = Instant.now();
        users.saveAndFlush(account);
        audit.log(null, account.id, "CLERK_ACCOUNT_CREATED", "UserAccount", account.id, Map.of());
        return account;
    }
    @Transactional
    public UserAccount link(String clerkToken) {
        if (!"local".equals(TenantContext.authProvider())) throw ApiErrors.forbidden("Sign in with your existing StockPilot credentials before linking Clerk");
        var identity = verifyLinkProof(clerkToken);
        var profile = clerk.verifiedProfile(identity.subject());
        var account = identities.lockUser(TenantContext.userId()).map(this::requireActive).orElseThrow(() -> ApiErrors.notFound("Account not found"));
        if (!account.emailVerified || !account.email.equalsIgnoreCase(profile.email())) throw ApiErrors.forbidden("Both accounts must have the same verified email address");
        if (account.externalClerkId != null && !account.externalClerkId.equals(identity.subject())) throw ApiErrors.conflict("This account is already linked to another Clerk identity");
        identities.findByExternalClerkId(identity.subject()).filter(existing -> !existing.id.equals(account.id))
            .ifPresent(existing -> { throw ApiErrors.conflict("This Clerk identity is already linked to another account"); });
        account.externalClerkId = identity.subject(); users.saveAndFlush(account);
        audit.log(null, account.id, "CLERK_ACCOUNT_LINKED", "UserAccount", account.id, Map.of());
        return account;
    }
    public ClerkJwtVerifier.VerifiedClerkIdentity verifyLinkProof(String token) {
        try { return verifier.verify(token); }
        catch (ApiException ex) { throw ex; }
        catch (RuntimeException ex) { throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CLERK_TOKEN", "The Clerk session proof is invalid or expired"); }
    }
    private UserAccount requireActive(UserAccount account) {
        if (!account.active || !account.emailVerified) throw ApiErrors.forbidden("Account is unavailable");
        return account;
    }
}
