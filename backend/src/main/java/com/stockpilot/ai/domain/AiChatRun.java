package com.stockpilot.ai.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.*;

@Entity
@Table(name="ai_chat_runs", uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","user_id","request_id"}))
public class AiChatRun extends TenantOwnedEntity {
    public UUID userId;
    public UUID conversationId;
    public UUID requestId;
    public String fingerprint;
    public String status;
    public int attemptCount = 1;
    public java.time.Instant lastAttemptAt = java.time.Instant.now();
    public String provider;
    public String model;
    public int inputTokens;
    public int outputTokens;
    public long durationMs;
    @JdbcTypeCode(SqlTypes.JSON) public Map<String,Object> responseJson = new LinkedHashMap<>();
    @JdbcTypeCode(SqlTypes.JSON) public Set<String> requiredPermissions = new HashSet<>();
}
