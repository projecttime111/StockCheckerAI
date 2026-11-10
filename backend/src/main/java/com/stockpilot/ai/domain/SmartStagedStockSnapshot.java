package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "smart_staged_stock_snapshots")
public class SmartStagedStockSnapshot extends TenantOwnedEntity {
    public UUID importSessionId;
    public UUID sessionFileId;
    public int sourceRowNumber;
    public String productName;
    public String normalizedProductName;
    public String unitCode;
    public String warehouseName;
    public LocalDate snapshotDate;
    @jakarta.persistence.Column(precision=14, scale=3)
    public BigDecimal importedStock = BigDecimal.ZERO;
    public BigDecimal rate;
    public BigDecimal stockValue;
    public UUID matchedProductId;
    public UUID matchedWarehouseId;
    @jakarta.persistence.Column(precision=14, scale=3)
    public BigDecimal currentStock = BigDecimal.ZERO;
    @jakarta.persistence.Column(precision=14, scale=3)
    public BigDecimal deltaPreview = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartSnapshotAction action = DomainEnums.SmartSnapshotAction.REVIEW_REQUIRED;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> rawMetadataJson = new LinkedHashMap<>();
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartMatchStatus matchStatus = DomainEnums.SmartMatchStatus.UNKNOWN;
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartReviewStatus reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
}
