package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.ImportDryRun;
import com.stockpilot.ai.domain.ImportSession;
import com.stockpilot.ai.domain.SmartImportCommit;
import com.stockpilot.ai.domain.SmartStagedVoucher;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.exception.ApiException;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SmartImportCommitService {
    private static final Logger log = LoggerFactory.getLogger(SmartImportCommitService.class);
    public static final String CONFIRMATION = "COMMIT IMPORT PLAN";

    private final Repositories.ImportSessionRepository sessions;
    private final Repositories.ImportDryRunRepository dryRuns;
    private final Repositories.ImportDryRunItemRepository dryRunItems;
    private final Repositories.ImportPlanIssueRepository planIssues;
    private final Repositories.SmartStagedVoucherRepository stagedVouchers;
    private final Repositories.SmartStagedVoucherItemRepository stagedVoucherItems;
    private final Repositories.SmartStagedTransferCandidateRepository stagedTransfers;
    private final Repositories.SmartImportCommitRepository commits;
    private final SmartImportFingerprintService fingerprints;
    private final SmartImportSnapshotDateService snapshotDates;
    private final SmartImportCommitExecutor executor;
    private final StockLedgerService stockLedger;
    private final AuditService audit;
    private final SmartImportDestinationValidator destinationValidator;
    private final TenantWriteLockService executionLock;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public SmartImportCommitService(
        Repositories.ImportSessionRepository sessions,
        Repositories.ImportDryRunRepository dryRuns,
        Repositories.ImportDryRunItemRepository dryRunItems,
        Repositories.ImportPlanIssueRepository planIssues,
        Repositories.SmartStagedVoucherRepository stagedVouchers,
        Repositories.SmartStagedVoucherItemRepository stagedVoucherItems,
        Repositories.SmartStagedTransferCandidateRepository stagedTransfers,
        Repositories.SmartImportCommitRepository commits,
        SmartImportFingerprintService fingerprints,
        SmartImportSnapshotDateService snapshotDates,
        SmartImportCommitExecutor executor,
        StockLedgerService stockLedger,
        AuditService audit,
        SmartImportDestinationValidator destinationValidator,
        TenantWriteLockService executionLock,
        org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        this.sessions = sessions;
        this.dryRuns = dryRuns;
        this.dryRunItems = dryRunItems;
        this.planIssues = planIssues;
        this.stagedVouchers = stagedVouchers;
        this.stagedVoucherItems = stagedVoucherItems;
        this.stagedTransfers = stagedTransfers;
        this.commits = commits;
        this.fingerprints = fingerprints;
        this.snapshotDates = snapshotDates;
        this.executor = executor;
        this.stockLedger = stockLedger;
        this.audit = audit;
        this.destinationValidator = destinationValidator;
        this.executionLock = executionLock;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    public Map<String, Object> commit(UUID sessionId, SmartImportDtos.CommitRequest request) {
        if (request == null || !CONFIRMATION.equals(request.confirmation())) {
            throw ApiErrors.badRequest("Enter COMMIT IMPORT PLAN to confirm the safe foundation commit");
        }
        var tenantId = TenantContext.tenantId();
        try {
            return transactions.execute(status -> commitLocked(tenantId, sessionId, request));
        } catch (ExecutionFailed failure) {
            // The business transaction has fully rolled back before a separate failure record is written.
            return transactions.execute(status -> recordFailure(tenantId, sessionId, failure));
        }
    }

    private Map<String, Object> commitLocked(UUID tenantId, UUID sessionId, SmartImportDtos.CommitRequest request) {
        executionLock.lock(tenantId);
        var session = session(tenantId, sessionId);
        var existing = commits.findByTenantIdAndImportSessionId(tenantId, sessionId).orElse(null);
        if (existing != null && existing.status == DomainEnums.SmartImportCommitStatus.COMMITTED) {
            return response(existing, true);
        }
        // Ownership is the database transaction lock, never the age of a status marker.
        // Acquiring this lock proves a previous transaction is no longer executing. New executions
        // persist status and business effects atomically; legacy stranded markers can be retried.
        if (existing != null && existing.status == DomainEnums.SmartImportCommitStatus.COMMITTING) {
            audit.logCurrent("SMART_IMPORT_INTERRUPTED_COMMIT_RECOVERED", "SmartImportCommit", existing.id,
                Map.of("sessionId", sessionId));
        }

        var dryRun = requireFreshDryRun(tenantId, session, request.dryRunId());
        var commit = existing == null ? new SmartImportCommit() : existing;
        commit.tenantId = tenantId;
        commit.importSessionId = session.id;
        commit.dryRunId = dryRun.id;
        commit.strategy = dryRun.strategy;
        commit.status = DomainEnums.SmartImportCommitStatus.COMMITTING;
        commit.startedAt = Instant.now();
        commit.finishedAt = null;
        commit.committedBy = TenantContext.userId();
        commit.summaryJson = new LinkedHashMap<>(Map.of(
            "phase", "4B-2E",
            "message", "Safe foundation, financial records, and reviewed post-snapshot inventory commit is running"
        ));
        commit.createdBy = commit.createdBy == null ? TenantContext.userId() : commit.createdBy;
        commit.updatedBy = TenantContext.userId();
        commits.save(commit);
        audit.logCurrent("SMART_IMPORT_COMMIT_STARTED", "SmartImportCommit", commit.id, Map.of(
            "sessionId", session.id, "dryRunId", dryRun.id, "strategy", dryRun.strategy.name()));

        try {
            return executor.execute(tenantId, session.id, commit.id);
        } catch (RuntimeException ex) {
            throw new ExecutionFailed(commit.id, dryRun.id, dryRun.strategy, ex);
        }
    }

    private Map<String, Object> recordFailure(UUID tenantId, UUID sessionId, ExecutionFailed failure) {
        executionLock.lock(tenantId);
        var session = session(tenantId, sessionId);
        var failed = commits.findByTenantIdAndImportSessionId(tenantId, sessionId).orElse(null);
        // Another successful retry may have acquired ownership after our rollback.
        if (failed != null && failed.status == DomainEnums.SmartImportCommitStatus.COMMITTED) return response(failed, true);
        if (failed == null) {
            failed = new SmartImportCommit();
            failed.tenantId = tenantId;
            failed.importSessionId = sessionId;
            failed.createdBy = TenantContext.userId();
        }
        failed.dryRunId = failure.dryRunId;
        failed.strategy = failure.strategy;
        failed.status = DomainEnums.SmartImportCommitStatus.FAILED;
        failed.startedAt = Instant.now();
        failed.finishedAt = Instant.now();
        failed.committedBy = TenantContext.userId();
        failed.updatedBy = TenantContext.userId();
        failed.summaryJson = new LinkedHashMap<>(Map.of("phase", "4B-2E",
            "failureReason", safeFailure(failure.original), "transactionRolledBack", true, "blockingErrors", 1));
        commits.save(failed);
        session.status = DomainEnums.ImportSessionStatus.FAILED;
        session.updatedBy = TenantContext.userId();
        sessions.save(session);
        audit.logCurrent("SMART_IMPORT_FAILED", "SmartImportCommit", failed.id,
            Map.of("sessionId", sessionId, "reason", safeFailure(failure.original), "transactionRolledBack", true));
        log.error("Smart import transaction rolled back for tenant {} session {}", tenantId, sessionId, failure.original);
        return response(failed, false);
    }

    private static final class ExecutionFailed extends RuntimeException {
        final UUID dryRunId;
        final DomainEnums.ImportStrategy strategy;
        final RuntimeException original;
        ExecutionFailed(UUID commitId, UUID dryRunId, DomainEnums.ImportStrategy strategy, RuntimeException original) {
            super("Import execution " + commitId + " rolled back", original);
            this.dryRunId = dryRunId; this.strategy = strategy; this.original = original;
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> result(UUID sessionId) {
        var tenantId = TenantContext.tenantId();
        session(tenantId, sessionId);
        return commits.findByTenantIdAndImportSessionId(tenantId, sessionId)
            .map(commit -> response(commit, false))
            .orElseGet(() -> new LinkedHashMap<>(Map.of("available", false, "status", "NOT_COMMITTED")));
    }

    private ImportDryRun requireFreshDryRun(UUID tenantId, ImportSession session, UUID requestedDryRunId) {
        if (requestedDryRunId == null) {
            throw ApiErrors.badRequest("Run and select a completed dry run before committing");
        }
        var dryRun = dryRuns.findByTenantIdAndImportSessionIdAndId(tenantId, session.id, requestedDryRunId)
            .orElseThrow(() -> ApiErrors.badRequest("The selected dry run is not available for this import session"));
        if (dryRun.status != DomainEnums.ImportDryRunStatus.COMPLETED) {
            throw ApiErrors.badRequest("The selected dry run did not complete successfully");
        }
        if (number(dryRun.summaryJson.get("cashbookMatchPolicyVersion")) != SmartImportCashbookMatchingService.POLICY_VERSION) {
            throw ApiErrors.conflict("Cashbook matching rules changed. Run a new dry run before committing; no rebuild or reupload is needed.");
        }
        var dates = snapshotDates.fromSummary(dryRun.summaryJson);
        snapshotDates.validateSession(tenantId, session.id, dates);
        if (dryRun.inputFingerprint == null
            || !dryRun.inputFingerprint.equals(dryRunFingerprint(tenantId, session.id, dryRun))) {
            throw ApiErrors.conflict("The dry run is stale because files or review decisions changed. Run the dry run again.");
        }
        if (number(dryRun.summaryJson.get("blockingErrors")) > 0) {
            throw ApiErrors.badRequest("Resolve all blocking dry-run errors before committing");
        }
        if (number(dryRun.summaryJson.get("reviewRequiredCount")) > 0) {
            throw ApiErrors.badRequest("Resolve or explicitly ignore every review-required item before committing");
        }
        var invoiceVouchers = stagedVouchers
            .findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(tenantId, session.id).stream()
            .filter(voucher -> !excludedVoucher(voucher))
            .filter(voucher -> {
                var type = Objects.toString(voucher.voucherType, "").toUpperCase();
                return !type.contains("RETURN")
                    && (type.contains("SALE") || type.contains("PURCHASE")
                        || type.contains("CREDIT") || type.contains("DEBIT"));
            }).toList();
        var hasInvoiceVouchers = !invoiceVouchers.isEmpty();
        var stockImpactMode = Objects.toString(dryRun.summaryJson.get("stockImpactMode"), "");
        var supportedMode = DomainEnums.VoucherStockImpactMode.CREATE_INVOICES_ONLY.name().equals(stockImpactMode)
            || DomainEnums.VoucherStockImpactMode.APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE.name().equals(stockImpactMode);
        if (hasInvoiceVouchers && !supportedMode) {
            throw ApiErrors.badRequest("Smart Import supports CREATE_INVOICES_ONLY or APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE.");
        }
        var invoiceVoucherIds = invoiceVouchers.stream().map(voucher -> voucher.id).collect(Collectors.toSet());
        var unresolvedVoucher = invoiceVouchers.stream()
            .filter(voucher -> voucher.matchStatus != DomainEnums.SmartMatchStatus.SKIP_DUPLICATE)
            .filter(voucher -> unresolved(voucher.matchStatus, voucher.reviewStatus)
                || voucher.voucherNumber == null || voucher.voucherDate == null
                || voucher.partyName == null || voucher.partyName.isBlank())
            .findFirst();
        if (unresolvedVoucher.isPresent()) {
            throw ApiErrors.badRequest("Resolve customer or supplier identity for voucher row "
                + unresolvedVoucher.get().sourceRowNumber + " before committing");
        }
        var unresolvedItem = stagedVoucherItems
            .findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, session.id).stream()
            .filter(item -> invoiceVoucherIds.contains(item.stagedVoucherId))
            .filter(item -> unresolved(item.matchStatus, item.reviewStatus)
                || item.productName == null || item.productName.isBlank())
            .findFirst();
        if (unresolvedItem.isPresent()) {
            throw ApiErrors.badRequest("Resolve product identity for voucher item row "
                + unresolvedItem.get().sourceRowNumber + " before committing");
        }
        var transferMode = Objects.toString(dryRun.summaryJson.get("stockJournalImpactMode"),
            DomainEnums.StockJournalImpactMode.DEFER_STOCK_JOURNALS.name());
        if (DomainEnums.StockJournalImpactMode.APPLY_CONFIRMED_WAREHOUSE_TRANSFERS_AFTER_SNAPSHOT_DATE.name()
            .equals(transferMode)) {
            var unresolvedTransfer = stagedTransfers
                .findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(tenantId, session.id).stream()
                .filter(candidate -> candidate.classification == DomainEnums.StockJournalClassification.WAREHOUSE_TRANSFER)
                .filter(candidate -> candidate.resolutionStatus == DomainEnums.TransferResolutionStatus.REVIEW_REQUIRED)
                .findFirst();
            if (unresolvedTransfer.isPresent()) {
                throw ApiErrors.badRequest("Resolve warehouse transfer review row "
                    + unresolvedTransfer.get().sourceRowNumber + " before committing");
            }
        }
        var unresolved = planIssues.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(tenantId, session.id).stream()
            .filter(issue -> !issue.resolved)
            .filter(issue -> !resolvedByNegativeStockPolicy(issue.code, dryRun, tenantId))
            .filter(issue -> issue.severity == DomainEnums.ImportPlanIssueSeverity.ERROR
                || issue.severity == DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED
                || issue.code != null && issue.code.contains("DUPLICATE_VOUCHER"))
            .findFirst();
        if (unresolved.isPresent()) {
            throw ApiErrors.badRequest("Resolve import review item " + unresolved.get().code + " before committing");
        }
        var destinationErrors = destinationValidator.validate(tenantId, session.id);
        if (!destinationErrors.isEmpty()) {
            var violation = destinationErrors.getFirst();
            throw ApiErrors.badRequest("Import row " + violation.rowNumber() + ": " + violation.message()
                + ". Correct the source and run a new dry run before committing.");
        }
        verifySnapshotInputsStillMatch(tenantId, dryRun);
        return dryRun;
    }

    private boolean resolvedByNegativeStockPolicy(String issueCode, ImportDryRun dryRun, UUID tenantId) {
        if (!"NEGATIVE_STOCK".equals(issueCode)) return false;
        var configured = String.valueOf(dryRun.summaryJson.getOrDefault(
            "negativeStockPolicy", DomainEnums.NegativeStockImportPolicy.BLOCK.name()));
        if (DomainEnums.NegativeStockImportPolicy.SKIP_STOCK_MOVEMENT.name().equals(configured)) return true;
        return DomainEnums.NegativeStockImportPolicy.IMPORT_AS_IS.name().equals(configured)
            && stockLedger.negativeStockAllowed(tenantId);
    }

    private String dryRunFingerprint(UUID tenantId, UUID sessionId, ImportDryRun dryRun) {
        return fingerprints.captureDryRun(tenantId, sessionId,
            Objects.toString(dryRun.summaryJson.get("stockImpactMode"), ""),
            Objects.toString(dryRun.summaryJson.get("returnStockImpactMode"), ""),
            Objects.toString(dryRun.summaryJson.get("stockJournalImpactMode"),
                DomainEnums.StockJournalImpactMode.DEFER_STOCK_JOURNALS.name()),
            Objects.toString(dryRun.summaryJson.get("negativeStockPolicy"), ""),
            snapshotDates.fromSummary(dryRun.summaryJson).fingerprint());
    }

    private boolean unresolved(DomainEnums.SmartMatchStatus matchStatus, DomainEnums.SmartReviewStatus reviewStatus) {
        return matchStatus == null || matchStatus == DomainEnums.SmartMatchStatus.UNKNOWN
            || matchStatus == DomainEnums.SmartMatchStatus.POSSIBLE_DUPLICATE_REVIEW
            || matchStatus == DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW
            || reviewStatus == DomainEnums.SmartReviewStatus.PENDING;
    }

    private boolean excludedVoucher(SmartStagedVoucher voucher) {
        return voucher.rawMetadataJson != null
            && Boolean.parseBoolean(Objects.toString(voucher.rawMetadataJson.get("excludedFromCommit"), "false"));
    }

    private void verifySnapshotInputsStillMatch(UUID tenantId, ImportDryRun dryRun) {
        for (var item : dryRunItems.findByTenantIdAndDryRunIdAndItemTypeOrderByCreatedAtAsc(
            tenantId, dryRun.id, DomainEnums.ImportDryRunItemType.STOCK_SNAPSHOT)) {
            if (item.targetEntityId == null) continue;
            var warehouseId = uuid(item.previewJson.get("warehouseId"));
            var snapshotDate = date(item.previewJson.get("snapshotDate"));
            if (warehouseId == null || snapshotDate == null) continue;
            var expected = decimal(item.previewJson.get("stockAtSnapshotDate"));
            var actual = stockLedger.stockAt(tenantId, item.targetEntityId, warehouseId, snapshotDate);
            if (expected.compareTo(actual) != 0) {
                throw ApiErrors.conflict("Stock changed after the dry run. Run the dry run again before committing.");
            }
        }
    }

    private Map<String, Object> response(SmartImportCommit commit, boolean idempotentRetry) {
        var result = new LinkedHashMap<String, Object>();
        result.put("available", true);
        result.put("id", commit.id);
        result.put("importSessionId", commit.importSessionId);
        result.put("dryRunId", commit.dryRunId);
        result.put("status", commit.status);
        result.put("strategy", commit.strategy);
        result.put("startedAt", commit.startedAt);
        result.put("finishedAt", commit.finishedAt == null ? "" : commit.finishedAt);
        result.put("committedAt", commit.status == DomainEnums.SmartImportCommitStatus.COMMITTED ? commit.finishedAt : "");
        result.put("committedBy", commit.committedBy == null ? "" : commit.committedBy);
        result.put("idempotentRetry", idempotentRetry);
        result.put("summary", commit.summaryJson);
        return result;
    }

    private ImportSession session(UUID tenantId, UUID sessionId) {
        return sessions.findByTenantIdAndId(tenantId, sessionId)
            .orElseThrow(() -> ApiErrors.notFound("Import session not found"));
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private UUID uuid(Object value) {
        try {
            var text = Objects.toString(value, "");
            return text.isBlank() ? null : UUID.fromString(text);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private LocalDate date(Object value) {
        try {
            var text = Objects.toString(value, "");
            return text.isBlank() ? null : LocalDate.parse(text);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private String safeFailure(RuntimeException ex) {
        if (ex instanceof ApiException && ex.getMessage() != null && !ex.getMessage().isBlank()) {
            return ex.getMessage();
        }
        return "The transactional import write failed. No partial business data was retained.";
    }
}
