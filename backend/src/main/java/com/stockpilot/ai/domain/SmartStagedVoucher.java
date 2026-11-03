package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "smart_staged_vouchers")
public class SmartStagedVoucher extends TenantOwnedEntity {
    public UUID importSessionId;
    public UUID sessionFileId;
    public int sourceRowNumber;
    public String voucherType;
    public String voucherNumber;
    public LocalDate voucherDate;
    public String partyName;
    public String normalizedPartyName;
    public BigDecimal totalAmount;
    public BigDecimal taxAmount = BigDecimal.ZERO;
    public BigDecimal discountAmount = BigDecimal.ZERO;
    public BigDecimal freightAmount = BigDecimal.ZERO;
    public BigDecimal roundOffAmount = BigDecimal.ZERO;
    public BigDecimal otherChargesAmount = BigDecimal.ZERO;
    public int taxLineCount;
    public int discountLineCount;
    public int freightLineCount;
    public int roundOffLineCount;
    public int otherChargeLineCount;
    public String externalId;
    public String fingerprint;
    public UUID matchedPartyId;
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartVoucherStockImpactSuggestion stockImpactModeSuggestion;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> rawMetadataJson = new LinkedHashMap<>();
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartMatchStatus matchStatus = DomainEnums.SmartMatchStatus.UNKNOWN;
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartReviewStatus reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
    @Enumerated(EnumType.STRING)
    public DomainEnums.ReturnClassification returnClassification = DomainEnums.ReturnClassification.NOT_A_RETURN;
    @Enumerated(EnumType.STRING)
    public DomainEnums.ReturnResolutionStatus returnResolutionStatus;
    public BigDecimal returnConfidence = BigDecimal.ZERO;
    public String returnDetectionReason;
    public UUID returnResolvedBy;
    public Instant returnResolvedAt;
}
