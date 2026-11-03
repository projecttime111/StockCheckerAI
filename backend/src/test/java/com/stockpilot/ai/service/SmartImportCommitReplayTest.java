package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in verification against a disposable PostgreSQL copy, never the live application DB. */
@EnabledIfSystemProperty(named = "stockpilot.commit-replay", matches = "true")
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55433/stockpilot_commit_replay",
    "spring.datasource.username=replay",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=false"
})
@ActiveProfiles("test")
class SmartImportCommitReplayTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Repositories.SmartImportCommitRepository commits;
    @Autowired SmartImportCommitService service;
    @Autowired SmartImportDryRunService dryRunService;
    @Autowired Repositories.ImportDryRunRepository dryRuns;
    @Autowired SmartImportSnapshotDateService snapshotDates;
    @Autowired CashbookReviewService cashbookReview;

    @Test
    void reviewedImportCompletesAndRetryDoesNotDuplicateBusinessRecords() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("stockpilot_commit_replay");
        assertThat(jdbc.queryForObject("select inet_server_port()", Integer.class)).isEqualTo(55433);
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("replay");
        var commit = commits.findById(UUID.fromString(System.getProperty("stockpilot.replay.commit-id"))).orElseThrow();
        TenantContext.set(commit.tenantId, commit.committedBy, DomainEnums.Role.OWNER);
        try {
            if (commit.status == DomainEnums.SmartImportCommitStatus.COMMITTED) {
                verifyRecheck(commit.tenantId, commit.importSessionId);
                return;
            }
            assertThat(commit.status).isEqualTo(DomainEnums.SmartImportCommitStatus.FAILED);
            var previous = dryRuns.findByTenantIdAndImportSessionIdAndId(commit.tenantId, commit.importSessionId, commit.dryRunId).orElseThrow();
            var summary = previous.summaryJson;
            var dates = snapshotDates.fromSummary(summary);
            var preview = dryRunService.run(commit.importSessionId, new SmartImportDtos.DryRunRequest(
                previous.strategy,
                DomainEnums.VoucherStockImpactMode.valueOf(summary.get("stockImpactMode").toString()),
                DomainEnums.ReturnStockImpactMode.valueOf(summary.get("returnStockImpactMode").toString()),
                DomainEnums.StockJournalImpactMode.valueOf(summary.get("stockJournalImpactMode").toString()),
                DomainEnums.NegativeStockImportPolicy.valueOf(summary.get("negativeStockPolicy").toString()),
                dates.stockDate(), dates.stockConfirmed(), dates.outstandingDate(), dates.outstandingConfirmed()));
            System.out.println("ISOLATED_PREVIEW_SUMMARY=" + preview.get("summary"));
            var request = new SmartImportDtos.CommitRequest((UUID) preview.get("id"), SmartImportCommitService.CONFIRMATION);
            var started = System.nanoTime();
            var result = service.commit(commit.importSessionId, request);
            System.out.println("ISOLATED_REPLAY_SECONDS=" + (System.nanoTime() - started) / 1_000_000_000.0);
            System.out.println("ISOLATED_REPLAY_RESULT=" + result);
            assertThat(result.get("status").toString()).as(result.toString()).isEqualTo("COMMITTED");
            var before = (java.util.Map<?, ?>) preview.get("summary");
            var after = (java.util.Map<?, ?>) result.get("summary");
            assertThat(after.get("customerPaymentsCreated").toString()).isEqualTo(before.get("customerPaymentsPreviewed").toString());
            assertThat(after.get("supplierPaymentsCreated").toString()).isEqualTo(before.get("supplierPaymentsPreviewed").toString());
            for (var key : java.util.List.of("paymentsLinkedToSalesInvoices", "paymentsLinkedToPurchaseInvoices",
                "cashbookRowsUnmatched", "cashbookRowsReviewRequired")) {
                assertThat(after.get(key).toString()).as(key).isEqualTo(before.get(key).toString());
            }
            var counts = businessCounts(commit.tenantId);
            var repeated = service.commit(commit.importSessionId, request);
            assertThat(repeated.get("idempotentRetry")).isEqualTo(true);
            assertThat(businessCounts(commit.tenantId)).isEqualTo(counts);
            verifyRecheck(commit.tenantId, commit.importSessionId);
        } finally {
            TenantContext.clear();
        }
    }

    private void verifyRecheck(UUID tenantId, UUID sessionId) {
        var before = businessCounts(tenantId);
        var payments = paymentContents(tenantId);
        var rechecked = cashbookReview.recheck(sessionId);
        System.out.println("ISOLATED_CASHBOOK_RECHECK=" + rechecked);
        assertThat(businessCounts(tenantId)).isEqualTo(before);
        assertThat(paymentContents(tenantId)).isEqualTo(payments);
        assertThat(cashbookReview.unresolved(sessionId)).hasSize(((Number) rechecked.get("unpostedReviewCount")).intValue());
        var repeated = cashbookReview.recheck(sessionId);
        assertThat(repeated).isEqualTo(rechecked);
        assertThat(paymentContents(tenantId)).isEqualTo(payments);
    }

    private java.util.List<String> paymentContents(UUID tenantId) {
        return java.util.List.of("customer_payments", "supplier_payments", "retail_counter_sales").stream()
            .map(table -> jdbc.queryForObject("select md5(coalesce(string_agg(row_to_json(p)::text, '|' order by id), '')) from "
                + table + " p where tenant_id = ?", String.class, tenantId)).toList();
    }

    private java.util.List<Long> businessCounts(UUID tenantId) {
        return java.util.List.of("products", "sales_invoices", "purchase_invoices", "financial_adjustments",
                "customer_payments", "supplier_payments", "stock_movements", "outstanding_snapshots").stream()
            .map(table -> jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Long.class, tenantId))
            .toList();
    }
}
