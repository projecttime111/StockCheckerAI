package com.stockpilot.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/** Platform-managed metadata, retained when the client resets its business data. */
@Entity
@Table(name = "platform_client_profiles")
@AttributeOverride(name = "tenantId", column = @Column(name = "tenant_id", nullable = false, unique = true))
public class PlatformClientProfile extends TenantOwnedEntity {
    public enum Plan { FREE, STARTER, PRO, ENTERPRISE }
    public enum SubscriptionStatus { TRIAL, ACTIVE, PAST_DUE, CANCELLED, EXPIRED }
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public Plan plan = Plan.FREE;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public SubscriptionStatus subscriptionStatus = SubscriptionStatus.TRIAL;
    public Instant subscriptionEndsAt;
    @Column(nullable = false, length = 10000)
    public String internalNotes = "";
}
