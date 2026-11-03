package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "smart_staged_voucher_items")
public class SmartStagedVoucherItem extends TenantOwnedEntity {
    public UUID importSessionId;
    public UUID stagedVoucherId;
    public UUID sessionFileId;
    public int sourceRowNumber;
    public String productName;
    public String normalizedProductName;
    public String unitCode;
    public BigDecimal quantity;
    public BigDecimal rawQuantity;
    public BigDecimal billedQuantity;
    public BigDecimal actualQuantity;
    public BigDecimal rate;
    public BigDecimal amount;
    public String warehouseName;
    public UUID matchedProductId;
    public UUID matchedWarehouseId;
    public String rateSource;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> rawMetadataJson = new LinkedHashMap<>();
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartMatchStatus matchStatus = DomainEnums.SmartMatchStatus.UNKNOWN;
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartReviewStatus reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
    @Enumerated(EnumType.STRING)
    public DomainEnums.ReturnDirection returnDirection = DomainEnums.ReturnDirection.NONE;
    @Enumerated(EnumType.STRING)
    public DomainEnums.ReturnDisposition returnDisposition = DomainEnums.ReturnDisposition.REVIEW_REQUIRED;
    @Enumerated(EnumType.STRING)
    public DomainEnums.ReturnResolutionStatus returnResolutionStatus;
    public BigDecimal returnConfidence = BigDecimal.ZERO;
    public String returnDetectionReason;
    public UUID returnResolvedBy;
    public Instant returnResolvedAt;
}
