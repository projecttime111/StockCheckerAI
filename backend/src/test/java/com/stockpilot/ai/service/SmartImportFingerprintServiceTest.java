package com.stockpilot.ai.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class SmartImportFingerprintServiceTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    @Test
    void dryRunFingerprintFitsDatabaseAndIsStableForEveryOption() {
        var service = mock(SmartImportFingerprintService.class, CALLS_REAL_METHODS);
        doReturn("a".repeat(64)).when(service).capture(TENANT, SESSION);
        var options = new String[]{"APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE",
            "APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE", "APPLY_WAREHOUSE_TRANSFERS_AFTER_SNAPSHOT_DATE",
            "IMPORT_AS_IS", "2026-08-31"};

        var original = capture(service, options);

        assertThat(original).matches("[0-9a-f]{64}");
        assertThat(capture(service, options)).isEqualTo(original);
        for (int index = 0; index < options.length; index++) {
            var changed = options.clone();
            changed[index] = "CHANGED";
            assertThat(capture(service, changed)).isNotEqualTo(original);
        }
        doReturn("b".repeat(64)).when(service).capture(TENANT, SESSION);
        assertThat(capture(service, options)).isNotEqualTo(original);
    }

    @Test
    void absentSnapshotDateHasTheSameFingerprintAsItsPersistedEmptyValue() {
        var service = mock(SmartImportFingerprintService.class, CALLS_REAL_METHODS);
        doReturn("a".repeat(64)).when(service).capture(TENANT, SESSION);

        assertThat(service.captureDryRun(TENANT, SESSION, "CREATE_INVOICES_ONLY", "FINANCIAL_ONLY",
            "DEFER_STOCK_JOURNALS", "BLOCK", null))
            .isEqualTo(service.captureDryRun(TENANT, SESSION, "CREATE_INVOICES_ONLY", "FINANCIAL_ONLY",
                "DEFER_STOCK_JOURNALS", "BLOCK", ""));
    }

    private String capture(SmartImportFingerprintService service, String[] options) {
        return service.captureDryRun(TENANT, SESSION, options[0], options[1], options[2], options[3], options[4]);
    }
}
