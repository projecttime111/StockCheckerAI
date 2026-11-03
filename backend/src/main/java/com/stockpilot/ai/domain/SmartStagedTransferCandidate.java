package com.stockpilot.ai.domain;

import jakarta.persistence.Column;
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
@Table(name = "smart_staged_transfer_candidates")
public class SmartStagedTransferCandidate extends TenantOwnedEntity {
    public UUID importSessionId;
    public UUID stagedVoucherId;
    public UUID sessionFileId;
    public int sourceRowNumber;
    public UUID sourceItemId;
    public UUID destinationItemId;
    public String voucherExternalId;
    public String voucherNumber;
    public LocalDate voucherDate;
    public String productName;
    public String normalizedProductName;
    public UUID matchedProductId;
    public String unitCode;
    public String sourceWarehouseName;
    public UUID sourceWarehouseId;
    public String destinationWarehouseName;
    public UUID destinationWarehouseId;
    public BigDecimal rawOutwardQuantity;
    public BigDecimal rawInwardQuantity;
    public BigDecimal normalizedQuantity;
    public String batchReference;
    @Enumerated(EnumType.STRING)
    public DomainEnums.StockJournalClassification classification;
    public BigDecimal classificationConfidence = BigDecimal.ZERO;
    @Column(length = 2000)
    public String decisionReasons;
    @Enumerated(EnumType.STRING)
    public DomainEnums.TransferResolutionStatus resolutionStatus = DomainEnums.TransferResolutionStatus.REVIEW_REQUIRED;
    public String transferFingerprint;
    public String transferLineFingerprint;
    public UUID sourceSnapshotId;
    public UUID destinationSnapshotId;
    public LocalDate sourceSnapshotDate;
    public LocalDate destinationSnapshotDate;
    public UUID resolvedBy;
    public Instant resolvedAt;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> evidenceJson = new LinkedHashMap<>();
}
