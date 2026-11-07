package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "tenants")
public class Tenant extends BaseAudit {
    public String name;
    @jakarta.persistence.Column(unique = true, length = 128)
    public String clerkOrganizationId;
    @Enumerated(EnumType.STRING)
    public DomainEnums.BusinessMode businessMode = DomainEnums.BusinessMode.RETAIL;
    public String currency = "INR";
    public boolean gstEnabled = true;
    public boolean allowNegativeStock = false;
    public boolean portalShowAllActiveProducts = false;
    @Enumerated(EnumType.STRING)
    public DomainEnums.TenantStatus status = DomainEnums.TenantStatus.ACTIVE;
    public Instant deletedAt;
}
