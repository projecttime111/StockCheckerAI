package com.stockpilot.ai.service;

import com.stockpilot.ai.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmartImportSnapshotDateServiceTest {
    private final LocalDate date = LocalDate.of(2026, 8, 31);

    @Test
    void sourceDatesArePreservedForBothKindsOfSnapshot() {
        var selection = new SmartImportSnapshotDateService.Selection(date, true, date, true);
        var source = date.minusMonths(1);
        assertThat(selection.stockDate(source)).isEqualTo(source);
        assertThat(selection.outstandingDate(source)).isEqualTo(source);
    }

    @Test
    void noImplicitTodayFallbackExists() {
        var selection = new SmartImportSnapshotDateService.Selection(null, false, null, false);
        assertThatThrownBy(() -> selection.stockDate(null)).isInstanceOf(ApiException.class).hasMessageContaining("closing-stock");
        assertThatThrownBy(() -> selection.outstandingDate(null)).isInstanceOf(ApiException.class).hasMessageContaining("outstanding");
        assertThat(selection.stockDate(date)).isEqualTo(date);
        assertThat(selection.outstandingDate(date)).isEqualTo(date);
    }

    @Test
    void confirmationCannotBeSuppliedWithoutADateOrDateWithoutConfirmation() {
        assertThatThrownBy(() -> new SmartImportSnapshotDateService.Selection(null, true, null, false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new SmartImportSnapshotDateService.Selection(date, false, null, false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new SmartImportSnapshotDateService.Selection(null, false, null, true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new SmartImportSnapshotDateService.Selection(null, false, date, false)).isInstanceOf(ApiException.class);
    }

    @Test
    void savedPolicyRoundTripsAndLegacyOrMalformedDatesAreRejected() {
        var service = new SmartImportSnapshotDateService(null, null);
        var selection = new SmartImportSnapshotDateService.Selection(date, true, date.minusDays(1), true);
        assertThat(service.fromSummary(selection.summary())).isEqualTo(selection);
        assertThat(service.fromSummary(selection.summary()).fingerprint()).isEqualTo(selection.fingerprint());
        assertThatThrownBy(() -> service.fromSummary(Map.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.fromSummary(Map.of("snapshotDatePolicyVersion", 1,
            "requestedSnapshotDate", "2026-02-30", "snapshotDateConfirmed", true))).isInstanceOf(ApiException.class);
    }
}
