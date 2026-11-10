package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "ai_messages")
public class AiMessage extends TenantOwnedEntity {
    public UUID conversationId;
    @Enumerated(EnumType.STRING)
    public DomainEnums.AiRole role;
    public String content;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> evidenceJson = new HashMap<>();
    public UUID requestId;
    public String status;
    @JdbcTypeCode(SqlTypes.JSON)
    public java.util.Set<String> requiredPermissions = new java.util.HashSet<>();
}
