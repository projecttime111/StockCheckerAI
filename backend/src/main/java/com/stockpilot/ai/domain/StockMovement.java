package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_movements")
public class StockMovement extends TenantOwnedEntity {
    public UUID productId;
    public UUID warehouseId;
    public UUID batchId;
    @Enumerated(EnumType.STRING)
    public DomainEnums.MovementType movementType;
    public BigDecimal quantity = BigDecimal.ZERO;
    public UUID unitId;
    public BigDecimal baseQuantity = BigDecimal.ZERO;
    public BigDecimal rate = BigDecimal.ZERO;
    public BigDecimal totalValue = BigDecimal.ZERO;
    public String referenceType;
    public UUID referenceId;
    public UUID sourceImportSessionId;
    public UUID sourceFileId;
    public Integer sourceRowNumber;
    public String sourceFingerprint;
    public UUID snapshotBaselineId;
    public String decisionReason;
    public UUID transferGroupId;
    public UUID linkedMovementId;
    public String transferPairFingerprint;
    public String transferLineFingerprint;
    public Instant movementDate = Instant.now();
    public String notes;
}
