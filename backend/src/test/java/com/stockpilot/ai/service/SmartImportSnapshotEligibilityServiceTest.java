package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.SmartStagedStockSnapshot;
import com.stockpilot.ai.domain.StockSnapshotBaseline;
import com.stockpilot.ai.repo.Repositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartImportSnapshotEligibilityServiceTest {
    @Mock
    private Repositories.SmartStagedStockSnapshotRepository stagedSnapshots;
    @Mock
    private Repositories.StockSnapshotBaselineRepository baselines;

    private SmartImportSnapshotEligibilityService service;
    private UUID tenantId;
    private UUID sessionId;
    private UUID productId;
    private UUID warehouseId;

    @BeforeEach
    void setUp() {
        service = new SmartImportSnapshotEligibilityService(stagedSnapshots, baselines);
        tenantId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        productId = UUID.randomUUID();
        warehouseId = UUID.randomUUID();
        when(stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId))
            .thenReturn(List.of());
    }

    @Test
    void selectsLatestWarehouseSnapshotAndAllowsStrictlyLaterVoucher() {
        var older = baseline(LocalDate.of(2026, 3, 31), warehouseId, "100");
        var latest = baseline(LocalDate.of(2026, 6, 30), warehouseId, "120");
        when(baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId))
            .thenReturn(List.of(latest, older));

        var decision = decide(LocalDate.of(2026, 7, 1), warehouseId);

        assertThat(decision.eligible()).isTrue();
        assertThat(decision.snapshotId()).isEqualTo(latest.id);
        assertThat(decision.snapshotDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(decision.reasonCode())
            .isEqualTo(SmartImportSnapshotEligibilityService.ReasonCode.AFTER_SNAPSHOT);
    }

    @Test
    void warehouseSnapshotIsPreferredOverProductLevelSnapshot() {
        var warehouseSnapshot = baseline(LocalDate.of(2026, 3, 31), warehouseId, "100");
        var productSnapshot = baseline(LocalDate.of(2026, 6, 30), null, "120");
        when(baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId))
            .thenReturn(List.of(productSnapshot, warehouseSnapshot));

        var decision = decide(LocalDate.of(2026, 7, 1), warehouseId);

        assertThat(decision.snapshotId()).isEqualTo(warehouseSnapshot.id);
        assertThat(decision.productLevelFallback()).isFalse();
    }

    @Test
    void productLevelFallbackIsExplicitlyWarned() {
        var productSnapshot = baseline(LocalDate.of(2026, 6, 30), null, "120");
        when(baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId))
            .thenReturn(List.of(productSnapshot));

        var decision = decide(LocalDate.of(2026, 7, 1), warehouseId);

        assertThat(decision.eligible()).isTrue();
        assertThat(decision.productLevelFallback()).isTrue();
        assertThat(decision.warningCode()).isEqualTo("PRODUCT_LEVEL_SNAPSHOT_FALLBACK");
    }

    @Test
    void transferEligibilityRejectsProductLevelOnlySnapshot() {
        var productSnapshot = baseline(LocalDate.of(2026, 6, 30), null, "120");
        when(baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId))
            .thenReturn(List.of(productSnapshot));

        var decision = decideWarehouseSpecific(LocalDate.of(2026, 7, 1), warehouseId);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.blocking()).isTrue();
        assertThat(decision.reasonCode())
            .isEqualTo(SmartImportSnapshotEligibilityService.ReasonCode.WAREHOUSE_BASELINE_REQUIRED);
        assertThat(decision.warningCode()).isEqualTo("WAREHOUSE_BASELINE_REQUIRED_FOR_TRANSFER");
    }

    @Test
    void transferEligibilityAllowsOnlyStrictlyLaterWarehouseMovement() {
        var snapshot = baseline(LocalDate.of(2026, 6, 30), warehouseId, "100");
        when(baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId))
            .thenReturn(List.of(snapshot));

        assertThat(decideWarehouseSpecific(LocalDate.of(2026, 6, 29), warehouseId).eligible()).isFalse();
        assertThat(decideWarehouseSpecific(LocalDate.of(2026, 6, 30), warehouseId).eligible()).isFalse();
        assertThat(decideWarehouseSpecific(LocalDate.of(2026, 7, 1), warehouseId).eligible()).isTrue();
    }

    @Test
    void sameDateVoucherIsConservativelyCovered() {
        var snapshot = baseline(LocalDate.of(2026, 6, 30), warehouseId, "100");
        when(baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId))
            .thenReturn(List.of(snapshot));

        var decision = decide(LocalDate.of(2026, 6, 30), warehouseId);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.blocking()).isFalse();
        assertThat(decision.reasonCode())
            .isEqualTo(SmartImportSnapshotEligibilityService.ReasonCode.ON_SNAPSHOT_DATE);
        assertThat(decision.warningCode()).isEqualTo("VOUCHER_ON_SNAPSHOT_DATE_DEFERRED");
    }

    @Test
    void missingSnapshotBlocksPostSnapshotMovement() {
        when(baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId))
            .thenReturn(List.of());

        var decision = decide(LocalDate.of(2026, 7, 1), warehouseId);

        assertThat(decision.eligible()).isFalse();
        assertThat(decision.blocking()).isTrue();
        assertThat(decision.warningCode()).isEqualTo("NO_APPLICABLE_STOCK_SNAPSHOT");
    }

    @Test
    void repositoryLookupAlwaysUsesCurrentTenant() {
        when(baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId))
            .thenReturn(List.of());

        decide(LocalDate.of(2026, 7, 1), warehouseId);

        verify(stagedSnapshots)
            .findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId);
        verify(baselines)
            .findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId);
    }

    @Test
    void correctedSnapshotInCurrentDryRunSupersedesOlderPersistedValueOnSameDate() {
        var staged = stagedSnapshot(LocalDate.of(2026, 6, 30), "120");
        var olderPersisted = baseline(LocalDate.of(2026, 6, 30), warehouseId, "100");
        olderPersisted.sourceImportSessionId = UUID.randomUUID();
        when(stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId))
            .thenReturn(List.of(staged));
        when(baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId))
            .thenReturn(List.of(olderPersisted));

        var decision = decide(LocalDate.of(2026, 7, 1), warehouseId);

        assertThat(decision.blocking()).isFalse();
        assertThat(decision.snapshotId()).isEqualTo(staged.id);
        assertThat(decision.stagedSnapshot()).isTrue();
    }

    @Test
    void currentSessionPersistedBaselineWinsDuringCommitRecheck() {
        var staged = stagedSnapshot(LocalDate.of(2026, 6, 30), "120");
        var currentPersisted = baseline(LocalDate.of(2026, 6, 30), warehouseId, "120");
        var olderPersisted = baseline(LocalDate.of(2026, 6, 30), warehouseId, "100");
        olderPersisted.sourceImportSessionId = UUID.randomUUID();
        when(stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId))
            .thenReturn(List.of(staged));
        when(baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId))
            .thenReturn(List.of(currentPersisted, olderPersisted));

        var decision = decide(LocalDate.of(2026, 7, 1), warehouseId);

        assertThat(decision.blocking()).isFalse();
        assertThat(decision.snapshotId()).isEqualTo(currentPersisted.id);
        assertThat(decision.stagedSnapshot()).isFalse();
    }

    @Test
    void conflictingSnapshotsInsideCurrentStageRequireReview() {
        var first = stagedSnapshot(LocalDate.of(2026, 6, 30), "100");
        var second = stagedSnapshot(LocalDate.of(2026, 6, 30), "120");
        when(stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId))
            .thenReturn(List.of(first, second));
        when(baselines.findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(tenantId, productId))
            .thenReturn(List.of());

        var decision = decide(LocalDate.of(2026, 7, 1), warehouseId);

        assertThat(decision.blocking()).isTrue();
        assertThat(decision.reasonCode())
            .isEqualTo(SmartImportSnapshotEligibilityService.ReasonCode.AMBIGUOUS_SNAPSHOT);
    }

    private SmartImportSnapshotEligibilityService.SnapshotEligibilityDecision decide(
        LocalDate voucherDate,
        UUID voucherWarehouseId
    ) {
        return service.decide(
            tenantId,
            sessionId,
            voucherDate,
            productId,
            "Sample Item A",
            "PCS",
            voucherWarehouseId,
            "Main Location",
            null);
    }

    private SmartImportSnapshotEligibilityService.SnapshotEligibilityDecision decideWarehouseSpecific(
        LocalDate voucherDate,
        UUID voucherWarehouseId
    ) {
        return service.decideWarehouseSpecific(
            tenantId,
            sessionId,
            voucherDate,
            productId,
            "Sample Item A",
            "PCS",
            voucherWarehouseId,
            "Main Location",
            null);
    }

    private StockSnapshotBaseline baseline(LocalDate date, UUID snapshotWarehouseId, String quantity) {
        var baseline = new StockSnapshotBaseline();
        baseline.id = UUID.randomUUID();
        baseline.tenantId = tenantId;
        baseline.productId = productId;
        baseline.warehouseId = snapshotWarehouseId;
        baseline.adjustmentWarehouseId = warehouseId;
        baseline.snapshotDate = date;
        baseline.importedQuantity = new BigDecimal(quantity);
        baseline.sourceImportSessionId = sessionId;
        return baseline;
    }

    private SmartStagedStockSnapshot stagedSnapshot(LocalDate date, String quantity) {
        var snapshot = new SmartStagedStockSnapshot();
        snapshot.id = UUID.randomUUID();
        snapshot.tenantId = tenantId;
        snapshot.importSessionId = sessionId;
        snapshot.matchedProductId = productId;
        snapshot.matchedWarehouseId = warehouseId;
        snapshot.productName = "Sample Item A";
        snapshot.normalizedProductName = "SAMPLE ITEM A";
        snapshot.unitCode = "PCS";
        snapshot.snapshotDate = date;
        snapshot.importedStock = new BigDecimal(quantity);
        return snapshot;
    }
}
