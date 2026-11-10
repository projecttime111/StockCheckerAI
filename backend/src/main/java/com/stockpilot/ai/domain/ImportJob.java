package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "import_jobs")
public class ImportJob extends TenantOwnedEntity {
    public UUID importSessionId;
    public UUID requestedBy;
    public UUID requestId;
    public String requestHash;
    public String operation;
    public String status = "QUEUED";
    public int attempts;
    public int progress;
    public String message;
    public String error;
    public String workerId;
    public Instant leaseUntil;
    @JdbcTypeCode(SqlTypes.JSON) public Map<String, Object> payload = new LinkedHashMap<>();
    @JdbcTypeCode(SqlTypes.JSON) public Map<String, Object> result = new LinkedHashMap<>();
}
