package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product extends TenantOwnedEntity {
    public String sku;
    public String name;
    public String normalizedName;
    public UUID categoryId;
    public UUID brandId;
    public UUID baseUnitId;
    public String hsnCode;
    public BigDecimal gstPercentage = BigDecimal.ZERO;
    @jakarta.persistence.Column(precision = 24, scale = 12)
    public BigDecimal defaultPurchasePrice = BigDecimal.ZERO;
    public BigDecimal defaultSalesPrice = BigDecimal.ZERO;
    public BigDecimal reorderPoint = BigDecimal.ZERO;
    public BigDecimal safetyStock = BigDecimal.ZERO;
    public int leadTimeDays = 7;
    public BigDecimal minimumOrderQuantity = BigDecimal.ZERO;
    public boolean active = true;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> rawMetadata = new HashMap<>();
}
