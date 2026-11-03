package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.SmartStagedStockSnapshot;
import com.stockpilot.ai.domain.StockSnapshotBaseline;
import com.stockpilot.ai.repo.Repositories;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class SmartImportSnapshotEligibilityService {
    private final Repositories.SmartStagedStockSnapshotRepository stagedSnapshots;
    private final Repositories.StockSnapshotBaselineRepository baselines;

    public SmartImportSnapshotEligibilityService(
        Repositories.SmartStagedStockSnapshotRepository stagedSnapshots,
        Repositories.StockSnapshotBaselineRepository baselines
    ) {
        this.stagedSnapshots = stagedSnapshots;
        this.baselines = baselines;
    }

    public SnapshotEligibilityDecision decide(
        UUID tenantId,
        UUID importSessionId,
        LocalDate voucherDate,
        UUID productId,
        String productName,
        String unitCode,
        UUID warehouseId,
        String warehouseName,
        LocalDate requestedSnapshotDate
    ) {
        return decideInternal(tenantId, importSessionId, voucherDate, productId, productName, unitCode,
            warehouseId, warehouseName, requestedSnapshotDate, true);
    }

    public SnapshotEligibilityDecision decideWarehouseSpecific(
        UUID tenantId,
        UUID importSessionId,
        LocalDate voucherDate,
        UUID productId,
        String productName,
        String unitCode,
        UUID warehouseId,
        String warehouseName,
        LocalDate requestedSnapshotDate
    ) {
        return decideInternal(tenantId, importSessionId, voucherDate, productId, productName, unitCode,
            warehouseId, warehouseName, requestedSnapshotDate, false);
    }

    private SnapshotEligibilityDecision decideInternal(
        UUID tenantId,
        UUID importSessionId,
        LocalDate voucherDate,
        UUID productId,
        String productName,
        String unitCode,
        UUID warehouseId,
        String warehouseName,
        LocalDate requestedSnapshotDate,
        boolean allowProductLevelFallback
    ) {
        if (voucherDate == null) {
            return blocked(ReasonCode.NO_VOUCHER_DATE, "Voucher date is required before stock eligibility can be determined.");
        }

        var candidates = new ArrayList<Candidate>();
        for (var row : stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, importSessionId)) {
            if (!sameProduct(row, productId, productName, unitCode)) continue;
            var snapshotDate = row.snapshotDate == null ? requestedSnapshotDate : row.snapshotDate;
            if (snapshotDate == null) continue;
            var scope = warehouseScope(row, warehouseId, warehouseName);
            if (scope != Scope.UNRELATED) {
                candidates.add(new Candidate(row.id, snapshotDate, scope, row.importedStock, true, true));
            }
        }
        if (productId != null) {
            for (var row : baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId)) {
                var scope = row.warehouseId == null ? Scope.PRODUCT_LEVEL
                    : Objects.equals(row.warehouseId, warehouseId) ? Scope.WAREHOUSE : Scope.UNRELATED;
                if (scope != Scope.UNRELATED) {
                    candidates.add(new Candidate(row.id, row.snapshotDate, scope, row.importedQuantity, false,
                        Objects.equals(row.sourceImportSessionId, importSessionId)));
                }
            }
        }

        var warehouseCandidates = candidates.stream().filter(candidate -> candidate.scope == Scope.WAREHOUSE).toList();
        var selectedPool = warehouseCandidates.isEmpty() && allowProductLevelFallback
            ? candidates.stream().filter(candidate -> candidate.scope == Scope.PRODUCT_LEVEL).toList()
            : warehouseCandidates;
        if (selectedPool.isEmpty()) {
            if (!allowProductLevelFallback && candidates.stream().anyMatch(candidate -> candidate.scope == Scope.PRODUCT_LEVEL)) {
                return blocked(ReasonCode.WAREHOUSE_BASELINE_REQUIRED,
                    "A warehouse-specific closing-stock baseline is required for automatic warehouse transfer posting.");
            }
            return blocked(ReasonCode.NO_SNAPSHOT,
                "No applicable closing-stock snapshot exists for this product and warehouse.");
        }

        var latestDate = selectedPool.stream().map(candidate -> candidate.snapshotDate)
            .max(LocalDate::compareTo).orElseThrow();
        var latest = selectedPool.stream().filter(candidate -> candidate.snapshotDate.equals(latestDate)).toList();
        var stagedLatest = latest.stream().filter(candidate -> candidate.staged).toList();
        var distinctQuantities = stagedLatest.stream()
            .map(candidate -> nvl(candidate.importedQuantity).stripTrailingZeros())
            .distinct().count();
        if (distinctQuantities > 1) {
            return blocked(ReasonCode.AMBIGUOUS_SNAPSHOT,
                "Multiple snapshots on the same date contain different quantities and require review.");
        }
        var selected = latest.stream()
            .filter(candidate -> !candidate.staged && candidate.currentSession)
            .findFirst()
            .orElseGet(() -> stagedLatest.stream()
                .max(Comparator.comparing(candidate -> candidate.id))
                .orElseGet(() -> latest.stream().filter(candidate -> !candidate.staged)
                    .findFirst().orElseThrow()));
        var productFallback = selected.scope == Scope.PRODUCT_LEVEL;
        var warning = productFallback ? "PRODUCT_LEVEL_SNAPSHOT_FALLBACK" : null;

        if (voucherDate.isAfter(selected.snapshotDate)) {
            return new SnapshotEligibilityDecision(true, false, selected.id, selected.snapshotDate, voucherDate,
                ReasonCode.AFTER_SNAPSHOT, "Voucher is strictly after the latest applicable closing-stock snapshot.",
                warning, productFallback, selected.staged);
        }
        if (voucherDate.isEqual(selected.snapshotDate)) {
            return new SnapshotEligibilityDecision(false, false, selected.id, selected.snapshotDate, voucherDate,
                ReasonCode.ON_SNAPSHOT_DATE,
                "Voucher is on the snapshot date and is conservatively treated as already covered by closing stock.",
                "VOUCHER_ON_SNAPSHOT_DATE_DEFERRED", productFallback, selected.staged);
        }
        return new SnapshotEligibilityDecision(false, false, selected.id, selected.snapshotDate, voucherDate,
            ReasonCode.BEFORE_SNAPSHOT,
            "Voucher predates the latest closing-stock snapshot and is already covered by that snapshot.",
            "VOUCHER_COVERED_BY_SNAPSHOT", productFallback, selected.staged);
    }

    private boolean sameProduct(
        SmartStagedStockSnapshot row, UUID productId, String productName, String unitCode
    ) {
        if (productId != null && productId.equals(row.matchedProductId)) return true;
        return normalize(row.normalizedProductName == null ? row.productName : row.normalizedProductName)
            .equals(normalize(productName))
            && normalizeUnit(row.unitCode).equals(normalizeUnit(unitCode));
    }

    private Scope warehouseScope(SmartStagedStockSnapshot row, UUID warehouseId, String warehouseName) {
        if (row.matchedWarehouseId != null) {
            return Objects.equals(row.matchedWarehouseId, warehouseId) ? Scope.WAREHOUSE : Scope.UNRELATED;
        }
        if (row.warehouseName != null && !row.warehouseName.isBlank()) {
            return normalize(row.warehouseName).equals(normalize(warehouseName)) ? Scope.WAREHOUSE : Scope.UNRELATED;
        }
        return Scope.PRODUCT_LEVEL;
    }

    private SnapshotEligibilityDecision blocked(ReasonCode code, String explanation) {
        return new SnapshotEligibilityDecision(false, true, null, null, null, code, explanation,
            code == ReasonCode.NO_SNAPSHOT ? "NO_APPLICABLE_STOCK_SNAPSHOT"
                : code == ReasonCode.WAREHOUSE_BASELINE_REQUIRED ? "WAREHOUSE_BASELINE_REQUIRED_FOR_TRANSFER"
                : code.name(), false, false);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private String normalizeUnit(String value) {
        return normalize(value);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public enum ReasonCode {
        AFTER_SNAPSHOT,
        ON_SNAPSHOT_DATE,
        BEFORE_SNAPSHOT,
        NO_SNAPSHOT,
        WAREHOUSE_BASELINE_REQUIRED,
        NO_VOUCHER_DATE,
        AMBIGUOUS_SNAPSHOT
    }

    public record SnapshotEligibilityDecision(
        boolean eligible,
        boolean blocking,
        UUID snapshotId,
        LocalDate snapshotDate,
        LocalDate voucherDate,
        ReasonCode reasonCode,
        String explanation,
        String warningCode,
        boolean productLevelFallback,
        boolean stagedSnapshot
    ) {
    }

    private enum Scope {
        WAREHOUSE, PRODUCT_LEVEL, UNRELATED
    }

    private record Candidate(
        UUID id,
        LocalDate snapshotDate,
        Scope scope,
        BigDecimal importedQuantity,
        boolean staged,
        boolean currentSession
    ) {
    }
}
