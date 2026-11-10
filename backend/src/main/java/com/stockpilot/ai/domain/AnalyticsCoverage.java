package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/** Explicitly confirmed contiguous sales coverage; absent dates must never imply zero demand. */
@Entity @Table(name = "analytics_coverage")
public class AnalyticsCoverage extends TenantOwnedEntity {
    public LocalDate fromDate;
    public LocalDate throughDate;
    public boolean complete;
    public UUID confirmedBy;
}
