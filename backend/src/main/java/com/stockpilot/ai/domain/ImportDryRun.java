package com.stockpilot.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "import_dry_runs")
public class ImportDryRun extends TenantOwnedEntity {
    public UUID importSessionId;

    @Enumerated(EnumType.STRING)
    public DomainEnums.ImportDryRunStatus status = DomainEnums.ImportDryRunStatus.RUNNING;

    @Enumerated(EnumType.STRING)
    public DomainEnums.ImportStrategy strategy = DomainEnums.ImportStrategy.UNKNOWN;

    public Instant startedAt;
    public Instant finishedAt;
    @Column(length = 64)
    public String inputFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> summaryJson = new LinkedHashMap<>();
}
