package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "ai_conversations")
public class AiConversation extends TenantOwnedEntity {
    public UUID userId;
    public String title;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    public java.util.Set<String> requiredPermissions = new java.util.HashSet<>();
}
