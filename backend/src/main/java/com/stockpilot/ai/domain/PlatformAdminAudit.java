package com.stockpilot.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Global administrative history; targetTenantId is explicit and never inferred from a workspace. */
@Entity
@Table(name = "platform_admin_audit")
public class PlatformAdminAudit extends BaseAudit {
    @Column(nullable = false)
    public UUID targetTenantId;
    @Column(nullable = false)
    public UUID actorUserId;
    @Column(nullable = false, length = 64)
    public String action;
    @Column(nullable = false, length = 1000)
    public String reason;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> beforeValues = new LinkedHashMap<>();
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> afterValues = new LinkedHashMap<>();
}
