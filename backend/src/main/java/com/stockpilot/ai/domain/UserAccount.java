package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "app_users")
public class UserAccount extends BaseAudit {
    public String email;
    public String passwordHash;
    public String fullName;
    public String googleSubject;
    @jakarta.persistence.Column(unique = true, length = 128)
    public String externalClerkId;
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @jakarta.persistence.Column(length = 32)
    public DomainEnums.Role platformRole;
    public boolean active = true;
    public boolean emailVerified = true;
    public Instant emailVerifiedAt;
    public String verificationTokenHash;
    public Instant verificationTokenExpiresAt;
    public Instant verificationSentAt;
    public int verificationResendCount = 0;
    public String passwordResetTokenHash;
    public Instant passwordResetTokenExpiresAt;
    public Instant passwordResetRequestedAt;
    public Instant passwordResetUsedAt;
    public long authVersion = 0;
}
