package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "smart_staged_cashbook_entries")
public class SmartStagedCashbookEntry extends TenantOwnedEntity {
    public UUID importSessionId;
    public UUID sessionFileId;
    public int sourceRowNumber;
    public LocalDate entryDate;
    public String partyName;
    public String gstin;
    public BigDecimal amount;
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartCashbookDirection direction = DomainEnums.SmartCashbookDirection.UNKNOWN;
    public UUID matchedPartyId;
    public UUID matchedInvoiceId;
    public UUID matchedStagedVoucherId;
    public String matchedPartyName;
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartPartyType matchedPartyType = DomainEnums.SmartPartyType.UNKNOWN;
    @Enumerated(EnumType.STRING)
    public DomainEnums.CashbookMatchStatus cashbookMatchStatus = DomainEnums.CashbookMatchStatus.UNMATCHED_REVIEW;
    @Enumerated(EnumType.STRING)
    public DomainEnums.PaymentMode paymentMode = DomainEnums.PaymentMode.OTHER;
    public String referenceNumber;
    public String fingerprint;
    public BigDecimal matchConfidence = BigDecimal.ZERO;
    public String matchReason;
    @Enumerated(EnumType.STRING)
    public DomainEnums.CashbookResolutionStatus resolutionStatus = DomainEnums.CashbookResolutionStatus.UNRESOLVED;
    @Enumerated(EnumType.STRING)
    public DomainEnums.CashbookResolutionAction manualResolutionAction;
    public UUID customerPaymentId;
    public UUID retailCounterSaleId;
    public UUID supplierPaymentId;
    public UUID resolvedBy;
    public Instant resolvedAt;
    public String resolutionNote;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> rawMetadataJson = new LinkedHashMap<>();
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartMatchStatus matchStatus = DomainEnums.SmartMatchStatus.UNKNOWN;
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartReviewStatus reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
}
