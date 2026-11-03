package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "financial_adjustment_items")
public class FinancialAdjustmentItem extends TenantOwnedEntity {
    public UUID financialAdjustmentId;
    public UUID productId;
    public UUID unitId;
    public UUID warehouseId;
    public String warehouseName;
    public BigDecimal quantity = BigDecimal.ZERO;
    public BigDecimal rate = BigDecimal.ZERO;
    public BigDecimal lineTotal = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    public DomainEnums.ReturnDirection returnDirection = DomainEnums.ReturnDirection.NONE;
    @Enumerated(EnumType.STRING)
    public DomainEnums.ReturnDisposition returnDisposition = DomainEnums.ReturnDisposition.FINANCIAL_ONLY;
    public UUID stockMovementId;
    public String stockEffectFingerprint;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> rawMetadata = new LinkedHashMap<>();
}
