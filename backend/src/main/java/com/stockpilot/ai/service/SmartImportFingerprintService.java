package com.stockpilot.ai.service;

import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.domain.BaseAudit;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class SmartImportFingerprintService {
    private final Repositories.ImportSessionFileRepository files;
    private final Repositories.ImportPlanIssueRepository issues;
    private final Repositories.SmartStagedProductRepository stagedProducts;
    private final Repositories.SmartStagedPartyRepository stagedParties;
    private final Repositories.SmartStagedWarehouseRepository stagedWarehouses;
    private final Repositories.SmartStagedUnitRepository stagedUnits;
    private final Repositories.SmartStagedStockSnapshotRepository stagedSnapshots;
    private final Repositories.SmartStagedVoucherRepository stagedVouchers;
    private final Repositories.SmartStagedVoucherItemRepository stagedVoucherItems;
    private final Repositories.SmartStagedTransferCandidateRepository stagedTransfers;
    private final Repositories.SmartStagedCashbookEntryRepository stagedCashbook;
    private final Repositories.SmartStagedStockAgeingRepository stagedAgeing;

    public SmartImportFingerprintService(
        Repositories.ImportSessionFileRepository files,
        Repositories.ImportPlanIssueRepository issues,
        Repositories.SmartStagedProductRepository stagedProducts,
        Repositories.SmartStagedPartyRepository stagedParties,
        Repositories.SmartStagedWarehouseRepository stagedWarehouses,
        Repositories.SmartStagedUnitRepository stagedUnits,
        Repositories.SmartStagedStockSnapshotRepository stagedSnapshots,
        Repositories.SmartStagedVoucherRepository stagedVouchers,
        Repositories.SmartStagedVoucherItemRepository stagedVoucherItems,
        Repositories.SmartStagedTransferCandidateRepository stagedTransfers,
        Repositories.SmartStagedCashbookEntryRepository stagedCashbook,
        Repositories.SmartStagedStockAgeingRepository stagedAgeing
    ) {
        this.files = files;
        this.issues = issues;
        this.stagedProducts = stagedProducts;
        this.stagedParties = stagedParties;
        this.stagedWarehouses = stagedWarehouses;
        this.stagedUnits = stagedUnits;
        this.stagedSnapshots = stagedSnapshots;
        this.stagedVouchers = stagedVouchers;
        this.stagedVoucherItems = stagedVoucherItems;
        this.stagedTransfers = stagedTransfers;
        this.stagedCashbook = stagedCashbook;
        this.stagedAgeing = stagedAgeing;
    }

    public String capture(UUID tenantId, UUID sessionId) {
        var canonical = new StringBuilder();
        files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(tenantId, sessionId).forEach(file -> canonical
            .append("FILE|").append(file.id).append('|').append(safe(file.fileHash)).append('|')
            .append(file.detectedFileType).append('|').append(file.selectedFileType).append('|')
            .append(file.status).append('|').append(file.rowCount).append('|')
            .append(file.errorCount).append('|').append(file.warningCount).append('|')
            .append(file.updatedAt).append('\n'));
        issues.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(tenantId, sessionId).forEach(issue -> canonical
            .append("ISSUE|").append(issue.id).append('|').append(issue.severity).append('|')
            .append(safe(issue.code)).append('|').append(issue.resolved).append('|')
            .append(issue.resolutionJson).append('|').append(issue.updatedAt).append('\n'));
        append(canonical, "PRODUCT", stagedProducts.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId));
        append(canonical, "PARTY", stagedParties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId));
        append(canonical, "WAREHOUSE", stagedWarehouses.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId));
        append(canonical, "UNIT", stagedUnits.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId));
        append(canonical, "SNAPSHOT", stagedSnapshots.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId));
        append(canonical, "VOUCHER", stagedVouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(tenantId, sessionId));
        append(canonical, "VOUCHER_ITEM", stagedVoucherItems.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId));
        appendTransfers(canonical, tenantId, sessionId);
        append(canonical, "CASHBOOK", stagedCashbook.findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(tenantId, sessionId));
        append(canonical, "AGEING", stagedAgeing.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId));
        return sha256(canonical.toString());
    }

    public String captureDryRun(
        UUID tenantId,
        UUID sessionId,
        String stockImpactMode,
        String returnStockImpactMode,
        String stockJournalImpactMode,
        String negativeStockPolicy,
        String snapshotDateSelection
    ) {
        // Hash the options with the staging fingerprint; V12 stores a 64-character SHA-256 value.
        return sha256(String.join(":", capture(tenantId, sessionId), safe(stockImpactMode),
            safe(returnStockImpactMode), safe(stockJournalImpactMode), safe(negativeStockPolicy), safe(snapshotDateSelection)));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void append(StringBuilder canonical, String type, Iterable<? extends BaseAudit> rows) {
        for (var row : rows) {
            canonical.append("STAGED|").append(type).append('|').append(row.id).append('|')
                .append(row.updatedAt).append('\n');
        }
    }

    private void appendTransfers(StringBuilder canonical, UUID tenantId, UUID sessionId) {
        for (var row : stagedTransfers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(
            tenantId, sessionId)) {
            // Snapshot IDs/dates are dry-run evidence, not user-controlled staging input. Keeping
            // them out prevents a dry run from invalidating itself while resolution changes below
            // still make the plan stale.
            canonical.append("STAGED|TRANSFER|").append(row.id).append('|')
                .append(row.classification).append('|').append(row.resolutionStatus).append('|')
                .append(row.matchedProductId).append('|').append(row.sourceWarehouseId).append('|')
                .append(row.destinationWarehouseId).append('|').append(row.normalizedQuantity).append('|')
                .append(safe(row.transferFingerprint)).append('|').append(safe(row.transferLineFingerprint)).append('|')
                .append(row.resolvedBy).append('|').append(row.resolvedAt).append('|')
                .append(safe(row.decisionReasons)).append('\n');
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
