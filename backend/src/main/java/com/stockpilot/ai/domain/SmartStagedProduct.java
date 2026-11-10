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
@Table(name = "smart_staged_products")
public class SmartStagedProduct extends TenantOwnedEntity {
    public UUID importSessionId;
    public UUID sessionFileId;
    public int sourceRowNumber;
    @Enumerated(EnumType.STRING)
    public DomainEnums.DetectedFileType sourceType;
    public String rawName;
    public String normalizedName;
    public String unitCode;
    public String sku;
    public String barcode;
    public String categoryName;
    public String brandName;
    public String hsn;
    public BigDecimal gstPercent;
    public BigDecimal purchasePrice;
    public BigDecimal salesPrice;
    public String externalId;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> rawMetadataJson = new LinkedHashMap<>();
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartMatchStatus matchStatus = DomainEnums.SmartMatchStatus.UNKNOWN;
    public UUID matchedProductId;
    @Enumerated(EnumType.STRING)
    public DomainEnums.SmartReviewStatus reviewStatus = DomainEnums.SmartReviewStatus.PENDING;
}
