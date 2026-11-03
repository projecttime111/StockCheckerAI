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
@Table(name = "financial_adjustments")
public class FinancialAdjustment extends TenantOwnedEntity {
    @Enumerated(EnumType.STRING)
    public DomainEnums.FinancialAdjustmentType adjustmentType;
    public UUID customerId;
    public UUID supplierId;
    public String voucherNumber;
    public LocalDate voucherDate;
    public BigDecimal subtotal = BigDecimal.ZERO;
    public BigDecimal taxAmount = BigDecimal.ZERO;
    public BigDecimal discountAmount = BigDecimal.ZERO;
    public BigDecimal freightAmount = BigDecimal.ZERO;
    public BigDecimal roundOffAmount = BigDecimal.ZERO;
    public BigDecimal otherChargesAmount = BigDecimal.ZERO;
    public BigDecimal totalAmount = BigDecimal.ZERO;
    public String sourceExternalId;
    public String sourceFingerprint;
    @Enumerated(EnumType.STRING)
    public DomainEnums.ReturnClassification returnClassification = DomainEnums.ReturnClassification.FINANCIAL_ONLY;
    @Enumerated(EnumType.STRING)
    public DomainEnums.ReturnResolutionStatus returnResolutionStatus;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> rawMetadata = new LinkedHashMap<>();
}
