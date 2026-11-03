package com.stockpilot.ai.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "retail_counter_sales", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "source_fingerprint"}))
public class RetailCounterSale extends TenantOwnedEntity {
    @Column(nullable = false)
    public LocalDate saleDate;
    @Column(nullable = false, precision = 18, scale = 2)
    public BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public DomainEnums.RetailCollectionMethod collectionMethod;
    public UUID sourceImportSessionId;
    public UUID sourceFileId;
    public int sourceRowNumber;
    @Column(nullable = false, length = 64)
    public String sourceFingerprint;
    @Column(length = 500)
    public String sourcePartyName;
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartCashbookDirection sourceDirection;
    public String referenceNumber;
    @Column(length = 500)
    public String note;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> rawMetadataJson = new LinkedHashMap<>();
}
