package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "forecast_results")
public class ForecastResult extends TenantOwnedEntity {
    public UUID productId;
    public UUID warehouseId;
    public BigDecimal averageDailyDemand = BigDecimal.ZERO;
    public BigDecimal weightedDailyDemand = BigDecimal.ZERO;
    @Column(name = "next_7_days_demand")
    public BigDecimal next7DaysDemand = BigDecimal.ZERO;
    @Column(name = "next_30_days_demand")
    public BigDecimal next30DaysDemand = BigDecimal.ZERO;
    public BigDecimal currentStock = BigDecimal.ZERO;
    public LocalDate stockoutDate;
    public Instant generatedAt = Instant.now();
    public int coverageDays;
    public boolean coverageConfirmed;
}
