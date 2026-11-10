package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "stock_snapshot_baselines")
public class StockSnapshotBaseline extends TenantOwnedEntity {
    public UUID productId;
    public UUID warehouseId;
    public UUID adjustmentWarehouseId;
    public LocalDate snapshotDate;
    @jakarta.persistence.Column(precision=14, scale=3)
    public BigDecimal importedQuantity = BigDecimal.ZERO;
    public UUID adjustmentMovementId;
    public UUID sourceImportSessionId;
    public UUID sourceFileId;
    public Integer sourceRowNumber;
}
