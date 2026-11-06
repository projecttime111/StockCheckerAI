package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "sales_invoice_items")
public class SalesInvoiceItem extends TenantOwnedEntity {
    public UUID salesInvoiceId;
    public UUID productId;
    public BigDecimal quantity = BigDecimal.ZERO;
    public UUID unitId;
    public BigDecimal rate = BigDecimal.ZERO;
    @jakarta.persistence.Column(precision = 24, scale = 12)
    public BigDecimal costRate = BigDecimal.ZERO;
    public BigDecimal taxPercentage = BigDecimal.ZERO;
    public BigDecimal taxAmount = BigDecimal.ZERO;
    public BigDecimal discountAmount = BigDecimal.ZERO;
    public BigDecimal lineTotal = BigDecimal.ZERO;
}
