package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.ImportPlan;
import com.stockpilot.ai.domain.ImportPlanIssue;
import com.stockpilot.ai.domain.ImportSession;
import com.stockpilot.ai.domain.ImportSessionFile;
import com.stockpilot.ai.repo.Repositories;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ImportPlanBuilderService {
    private static final Set<DomainEnums.DetectedFileType> TRANSACTION_TYPES = Set.of(
        DomainEnums.DetectedFileType.SALES_VOUCHERS,
        DomainEnums.DetectedFileType.PURCHASE_VOUCHERS,
        DomainEnums.DetectedFileType.CREDIT_NOTES,
        DomainEnums.DetectedFileType.DEBIT_NOTES,
        DomainEnums.DetectedFileType.STOCK_JOURNAL,
        DomainEnums.DetectedFileType.MIXED_TALLY_VOUCHERS
    );

    private final Repositories.ImportSessionRepository sessions;
    private final Repositories.ImportSessionFileRepository files;
    private final Repositories.ImportPlanRepository plans;
    private final Repositories.ImportPlanIssueRepository issues;

    public ImportPlanBuilderService(
        Repositories.ImportSessionRepository sessions,
        Repositories.ImportSessionFileRepository files,
        Repositories.ImportPlanRepository plans,
        Repositories.ImportPlanIssueRepository issues
    ) {
        this.sessions = sessions;
        this.files = files;
        this.plans = plans;
        this.issues = issues;
    }

    @Transactional
    public ImportPlan build(ImportSession session) {
        var sessionFiles = files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id);
        if (sessionFiles.isEmpty()) {
            throw com.stockpilot.ai.exception.ApiErrors.badRequest("Upload at least one file before building an import plan");
        }

        issues.deleteByTenantIdAndImportSessionId(session.tenantId, session.id);
        var generatedIssues = baseIssues(session, sessionFiles);

        var types = sessionFiles.stream()
            .filter(file -> file.status != DomainEnums.ImportSessionFileStatus.DUPLICATE)
            .map(ImportPlanBuilderService::effectiveType)
            .toList();
        var hasSnapshot = types.contains(DomainEnums.DetectedFileType.STOCK_SNAPSHOT);
        var transactionFileCount = types.stream().filter(TRANSACTION_TYPES::contains).count();
        var hasTransactions = transactionFileCount > 0;
        var hasAccountingMaster = types.contains(DomainEnums.DetectedFileType.ACCOUNTING_MASTER)
            || types.contains(DomainEnums.DetectedFileType.MIXED_TALLY_MASTERS);
        var hasSales = types.contains(DomainEnums.DetectedFileType.SALES_VOUCHERS)
            || contentCount(sessionFiles, "voucherSales") > 0;
        var hasPurchases = types.contains(DomainEnums.DetectedFileType.PURCHASE_VOUCHERS)
            || contentCount(sessionFiles, "voucherPurchase") > 0;
        var hasCashbook = types.contains(DomainEnums.DetectedFileType.CASH_BOOK)
            || contentCount(sessionFiles, "voucherReceipt") > 0
            || contentCount(sessionFiles, "voucherPayment") > 0;

        var strategy = strategy(hasSnapshot, transactionFileCount);
        var explanation = explanation(strategy);

        if (hasSnapshot) {
            generatedIssues.add(issue(session, null, DomainEnums.ImportPlanIssueSeverity.INFO,
                "STOCK_SNAPSHOT_DETECTED",
                "A closing-stock file was detected and will be treated as snapshot data in a future commit phase.",
                "Review the detected snapshot date before the commit phase is enabled."));
        }
        if (hasSnapshot && hasTransactions) {
            generatedIssues.add(issue(session, null, DomainEnums.ImportPlanIssueSeverity.WARNING,
                "TRANSACTION_FILES_WITH_SNAPSHOT",
                "Transaction files and a stock snapshot were uploaded together.",
                "Use hybrid reconciliation so historical vouchers do not double-count snapshot stock."));
        }
        if (hasSales && !hasAccountingMaster) {
            generatedIssues.add(issue(session, null, DomainEnums.ImportPlanIssueSeverity.WARNING,
                "SALES_WITHOUT_PARTY_MASTER",
                "Sales files were detected without a separate customer/ledger master file.",
                "Customers will need to be matched or created from voucher parties in a later phase."));
        }
        if (hasPurchases && !hasAccountingMaster) {
            generatedIssues.add(issue(session, null, DomainEnums.ImportPlanIssueSeverity.WARNING,
                "PURCHASE_WITHOUT_SUPPLIER_MASTER",
                "Purchase files were detected without a separate supplier/ledger master file.",
                "Suppliers will need to be matched or created from voucher parties in a later phase."));
        }
        if (hasCashbook) {
            generatedIssues.add(issue(session, null, DomainEnums.ImportPlanIssueSeverity.WARNING,
                "CASHBOOK_MATCHING_LIMITED",
                "Cashbook matching is limited until invoice and party references are reconciled.",
                "Review unmatched receipts and payments before a future commit."));
        }
        var deferredAccountingVouchers = contentCount(sessionFiles, "voucherContra")
            + contentCount(sessionFiles, "voucherJournal");
        if (deferredAccountingVouchers > 0) {
            generatedIssues.add(issue(session, null, DomainEnums.ImportPlanIssueSeverity.INFO,
                "NON_INVENTORY_ACCOUNTING_VOUCHERS_DEFERRED",
                deferredAccountingVouchers + " Contra/Journal vouchers were identified and kept outside sales and purchase staging.",
                "Accounting remains in Tally; these voucher types are not posted as inventory invoices."));
        }
        if (types.contains(DomainEnums.DetectedFileType.STOCK_AGEING)) {
            generatedIssues.add(issue(session, null, DomainEnums.ImportPlanIssueSeverity.INFO,
                "STOCK_AGEING_IS_COMPARISON_DATA",
                "Stock ageing is comparison data and should not create stock movements.",
                "Use it to verify ageing and valuation after core inventory data is imported."));
        }

        issues.saveAll(generatedIssues);
        updateFileIssueCounts(sessionFiles, generatedIssues);

        var plan = plans.findByTenantIdAndImportSessionId(session.tenantId, session.id).orElseGet(ImportPlan::new);
        plan.tenantId = session.tenantId;
        plan.importSessionId = session.id;
        plan.strategy = strategy;
        plan.planJson = planJson(strategy, explanation, sessionFiles, hasSnapshot, hasTransactions);
        var needsReview = generatedIssues.stream().anyMatch(row -> row.severity == DomainEnums.ImportPlanIssueSeverity.ERROR
            || row.severity == DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED);
        plan.status = needsReview ? DomainEnums.ImportPlanStatus.NEEDS_REVIEW : DomainEnums.ImportPlanStatus.READY;
        plans.save(plan);

        session.recommendedStrategy = strategy;
        session.status = needsReview ? DomainEnums.ImportSessionStatus.NEEDS_REVIEW : DomainEnums.ImportSessionStatus.PLAN_READY;
        sessions.save(session);
        return plan;
    }

    private int contentCount(List<ImportSessionFile> sessionFiles, String key) {
        return sessionFiles.stream()
            .filter(file -> file.status != DomainEnums.ImportSessionFileStatus.DUPLICATE)
            .map(file -> file.metadataJson == null ? null : file.metadataJson.get("contentCounts"))
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(map -> map.get(key))
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .mapToInt(Number::intValue)
            .sum();
    }

    @Transactional
    public List<ImportPlanIssue> rebuildClassificationIssues(ImportSession session, List<ImportSessionFile> sessionFiles) {
        issues.deleteByTenantIdAndImportSessionId(session.tenantId, session.id);
        var generated = baseIssues(session, sessionFiles);
        issues.saveAll(generated);
        updateFileIssueCounts(sessionFiles, generated);
        var needsReview = generated.stream().anyMatch(row -> row.severity == DomainEnums.ImportPlanIssueSeverity.ERROR
            || row.severity == DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED);
        session.status = needsReview ? DomainEnums.ImportSessionStatus.NEEDS_REVIEW : DomainEnums.ImportSessionStatus.CLASSIFIED;
        sessions.save(session);
        return generated;
    }

    private List<ImportPlanIssue> baseIssues(ImportSession session, List<ImportSessionFile> sessionFiles) {
        var generated = new ArrayList<ImportPlanIssue>();
        var uncertain = false;
        for (var file : sessionFiles) {
            if (file.status == DomainEnums.ImportSessionFileStatus.DUPLICATE) {
                generated.add(issue(session, file.id, DomainEnums.ImportPlanIssueSeverity.WARNING,
                    "DUPLICATE_FILE",
                    "This file appears to have already been uploaded.",
                    "Keep one copy; duplicate files are not classified twice by default."));
                continue;
            }
            if (file.status == DomainEnums.ImportSessionFileStatus.FAILED) {
                generated.add(issue(session, file.id, DomainEnums.ImportPlanIssueSeverity.ERROR,
                    "CLASSIFICATION_FAILED",
                    "The file could not be inspected safely.",
                    "Check the file format and encoding, then upload a corrected export."));
                uncertain = true;
                continue;
            }
            if (effectiveType(file) == DomainEnums.DetectedFileType.UNKNOWN) {
                generated.add(issue(session, file.id, DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED,
                    "UNKNOWN_FILE_TYPE",
                    "StockPilot could not confidently determine this file's business purpose.",
                    "Review the file and select its type in a future mapping phase."));
                uncertain = true;
            } else if (file.confidence == null || file.confidence.compareTo(new java.math.BigDecimal("0.70")) < 0) {
                generated.add(issue(session, file.id, DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED,
                    "UNCERTAIN_FILE_CLASSIFICATION",
                    "The detected file type has low confidence.",
                    "Review the detected type before this session can be committed."));
                uncertain = true;
            }
        }
        if (uncertain) {
            generated.add(issue(session, null, DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED,
                "REVIEW_REQUIRED_FOR_UNCERTAIN_FILES",
                "One or more files require classification review.",
                "Resolve uncertain file types before a future commit."));
        }
        return generated;
    }

    private void updateFileIssueCounts(List<ImportSessionFile> sessionFiles, List<ImportPlanIssue> generatedIssues) {
        for (var file : sessionFiles) {
            file.errorCount = (int) generatedIssues.stream()
                .filter(issue -> file.id.equals(issue.affectedFileId))
                .filter(issue -> issue.severity == DomainEnums.ImportPlanIssueSeverity.ERROR)
                .count();
            file.warningCount = (int) generatedIssues.stream()
                .filter(issue -> file.id.equals(issue.affectedFileId))
                .filter(issue -> issue.severity == DomainEnums.ImportPlanIssueSeverity.WARNING
                    || issue.severity == DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED)
                .count();
        }
        files.saveAll(sessionFiles);
    }

    private DomainEnums.ImportStrategy strategy(boolean hasSnapshot, long transactionFileCount) {
        if (hasSnapshot && transactionFileCount > 0) {
            return DomainEnums.ImportStrategy.HYBRID_RECONCILIATION;
        }
        if (hasSnapshot) {
            return DomainEnums.ImportStrategy.SNAPSHOT_FIRST;
        }
        if (transactionFileCount > 0) {
            return DomainEnums.ImportStrategy.TRANSACTION_HISTORY;
        }
        return DomainEnums.ImportStrategy.UNKNOWN;
    }

    private String explanation(DomainEnums.ImportStrategy strategy) {
        return switch (strategy) {
            case SNAPSHOT_FIRST -> "Closing stock/stock snapshot file detected. StockPilot recommends snapshot-first mode to avoid stock double-counting. Sales and purchase files should default to invoice-only unless they are after the snapshot date.";
            case TRANSACTION_HISTORY -> "No stock snapshot was detected. StockPilot can build stock from transaction history, but opening stock may be required.";
            case HYBRID_RECONCILIATION -> "Both stock snapshot and transaction files were detected. StockPilot will use snapshot reconciliation to prevent double-counting.";
            case UNKNOWN -> "There is not enough classified inventory or transaction data to recommend a safe import strategy yet.";
        };
    }

    private Map<String, Object> planJson(
        DomainEnums.ImportStrategy strategy,
        String explanation,
        List<ImportSessionFile> sessionFiles,
        boolean hasSnapshot,
        boolean hasTransactions
    ) {
        var plan = new LinkedHashMap<String, Object>();
        plan.put("strategy", strategy.name());
        plan.put("explanation", explanation);
        plan.put("phase", "FOUNDATION_ONLY");
        plan.put("commitEnabled", false);
        plan.put("filesConsidered", sessionFiles.stream().filter(file -> file.status != DomainEnums.ImportSessionFileStatus.DUPLICATE).count());
        plan.put("duplicateFilesSkipped", sessionFiles.stream().filter(file -> file.status == DomainEnums.ImportSessionFileStatus.DUPLICATE).count());
        plan.put("hasStockSnapshot", hasSnapshot);
        plan.put("hasTransactions", hasTransactions);
        plan.put("steps", List.of(
            step(1, "Units", "Detect and normalize units"),
            step(2, "Warehouses / godowns", "Match or prepare warehouses"),
            step(3, "Products", "Match product masters before voucher lines"),
            step(4, "Customers / suppliers / parties", "Match accounting parties"),
            step(5, "Stock snapshot", "Prepare dated snapshot reconciliation"),
            step(6, "Purchases", "Prepare purchase invoices and dependencies"),
            step(7, "Sales", "Prepare sales invoices and dependencies"),
            step(8, "Credit / debit notes", "Prepare returns and adjustments"),
            step(9, "Cashbook", "Prepare payment matching"),
            step(10, "Stock ageing", "Use as non-posting comparison data"),
            step(11, "Reconciliation", "Verify counts, values, and stock impact")
        ));
        plan.put("dependencyNote", "Files may be uploaded in any order. The future commit phase will resolve products, parties, warehouses, and units before dependent vouchers.");
        return plan;
    }

    private Map<String, Object> step(int order, String name, String purpose) {
        return Map.of("order", order, "name", name, "purpose", purpose, "status", "PLANNED");
    }

    private ImportPlanIssue issue(
        ImportSession session,
        UUID fileId,
        DomainEnums.ImportPlanIssueSeverity severity,
        String code,
        String message,
        String suggestedAction
    ) {
        var issue = new ImportPlanIssue();
        issue.tenantId = session.tenantId;
        issue.importSessionId = session.id;
        issue.affectedFileId = fileId;
        issue.severity = severity;
        issue.code = code;
        issue.message = message;
        issue.suggestedAction = suggestedAction;
        return issue;
    }

    private static DomainEnums.DetectedFileType effectiveType(ImportSessionFile file) {
        return file.selectedFileType == null ? file.detectedFileType : file.selectedFileType;
    }
}
