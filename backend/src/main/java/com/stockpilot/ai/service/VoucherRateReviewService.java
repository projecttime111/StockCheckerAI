package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.ImportPlanIssue;
import com.stockpilot.ai.domain.SmartImportResolution;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class VoucherRateReviewService {
    private static final String CONFIRM_ZERO = "CONFIRM ZERO VALUE";
    private static final String CONFIRM_DERIVE = "DERIVE RATE";
    private static final String CONFIRM_FINANCIAL = "TREAT AS FINANCIAL ADJUSTMENT";
    private static final String CONFIRM_MANUAL = "APPLY VERIFIED RATE";
    private static final String CONFIRM_EXCLUDE = "EXCLUDE VOUCHER";
    private static final int MAX_BULK_ROWS = 500;

    private final Repositories.ImportSessionRepository sessions;
    private final Repositories.ImportPlanIssueRepository issues;
    private final Repositories.SmartImportResolutionRepository resolutions;
    private final SmartImportStagingService staging;
    private final AuditService audit;

    public VoucherRateReviewService(
        Repositories.ImportSessionRepository sessions,
        Repositories.ImportPlanIssueRepository issues,
        Repositories.SmartImportResolutionRepository resolutions,
        SmartImportStagingService staging,
        AuditService audit
    ) {
        this.sessions = sessions;
        this.issues = issues;
        this.resolutions = resolutions;
        this.staging = staging;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> resolve(
        UUID sessionId, UUID issueId, SmartImportDtos.VoucherRateResolutionRequest request
    ) {
        staging.lockRateReview(sessionId);
        var session = session(sessionId);
        var issue = rateIssue(session.tenantId, session.id, issueId);
        validate(issue, request.action(), request.verifiedRate(), request.reason(), request.confirmation());
        if (request.action() == DomainEnums.VoucherRateResolutionAction.KEEP_UNRESOLVED) {
            audit.logCurrent("SMART_IMPORT_RATE_REVIEW_KEPT_UNRESOLVED", "ImportPlanIssue", issue.id,
                Map.of("sessionId", session.id, "sourceRow", sourceRow(issue)));
            return staging.workspace(session.id);
        }
        saveResolution(session.tenantId, session.id, issue, request.action(), request.verifiedRate(), request.reason(), false);
        resolutions.flush();
        auditResolution(session.id, issue, request.action(), false);
        if (request.action() == DomainEnums.VoucherRateResolutionAction.EXCLUDE_VOUCHER) {
            return staging.applyVoucherExclusions(session.id, List.of(issue), request.reason());
        }
        return request.action() == DomainEnums.VoucherRateResolutionAction.TREAT_AS_FINANCIAL_ADJUSTMENT
            ? staging.rebuild(session.id) : staging.applyVoucherRates(session.id, List.of(issue));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> previewBulk(
        UUID sessionId, SmartImportDtos.VoucherRateBulkPreviewRequest request
    ) {
        var session = session(sessionId);
        var source = rateIssue(session.tenantId, session.id, request.sourceIssueId());
        var matches = matchingIssues(session.tenantId, session.id, source, request.action());
        validateBulkSize(matches);
        validate(source, request.action(), request.verifiedRate(), previewReason(request.action()), confirmationFor(request.action()));
        return bulkPreview(source, request.action(), request.verifiedRate(), matches);
    }

    @Transactional
    public Map<String, Object> resolveBulk(
        UUID sessionId, SmartImportDtos.VoucherRateBulkResolutionRequest request
    ) {
        staging.lockRateReview(sessionId);
        var session = session(sessionId);
        var source = rateIssue(session.tenantId, session.id, request.sourceIssueId());
        var matches = matchingIssues(session.tenantId, session.id, source, request.action());
        validateBulkSize(matches);
        if (request.expectedCount() == null || request.expectedCount() != matches.size()) {
            throw ApiErrors.conflict("The matching review rows changed. Preview the bulk action again.");
        }
        var expectedConfirmation = "APPLY TO " + matches.size() + " MATCHING ROWS";
        if (!expectedConfirmation.equals(request.confirmation())) {
            throw ApiErrors.badRequest("Enter " + expectedConfirmation + " to confirm the bulk resolution");
        }
        for (var issue : matches) {
            validate(issue, request.action(), request.verifiedRate(), request.reason(), confirmationFor(request.action()));
            if (request.action() != DomainEnums.VoucherRateResolutionAction.KEEP_UNRESOLVED) {
                saveResolution(session.tenantId, session.id, issue, request.action(), request.verifiedRate(), request.reason(), true);
            }
        }
        if (request.action() == DomainEnums.VoucherRateResolutionAction.KEEP_UNRESOLVED) {
            audit.logCurrent("SMART_IMPORT_RATE_REVIEW_BULK_KEPT_UNRESOLVED", "ImportSession", session.id,
                Map.of("count", matches.size(), "matchKey", rateMatchKey(source)));
            return staging.workspace(session.id);
        }
        resolutions.flush();
        audit.logCurrent("SMART_IMPORT_RATE_REVIEW_BULK_RESOLVED", "ImportSession", session.id, Map.of(
            "action", request.action().name(),
            "count", matches.size(),
            "matchKey", rateMatchKey(source)
        ));
        if (request.action() == DomainEnums.VoucherRateResolutionAction.EXCLUDE_VOUCHER) {
            return staging.applyVoucherExclusions(session.id, matches, request.reason());
        }
        return request.action() == DomainEnums.VoucherRateResolutionAction.TREAT_AS_FINANCIAL_ADJUSTMENT
            ? staging.rebuild(session.id) : staging.applyVoucherRates(session.id, matches);
    }

    private void saveResolution(
        UUID tenantId,
        UUID sessionId,
        ImportPlanIssue issue,
        DomainEnums.VoucherRateResolutionAction action,
        BigDecimal verifiedRate,
        String reason,
        boolean bulk
    ) {
        var key = action == DomainEnums.VoucherRateResolutionAction.EXCLUDE_VOUCHER
            ? context(issue, "voucherResolutionKey") : context(issue, "resolutionKey");
        if (key.isBlank()) throw ApiErrors.badRequest("The rate review row does not have a stable resolution key");
        var resolution = resolutions.findByTenantIdAndImportSessionIdAndResolutionKey(tenantId, sessionId, key)
            .orElseGet(SmartImportResolution::new);
        resolution.tenantId = tenantId;
        resolution.importSessionId = sessionId;
        resolution.resolutionKey = key;
        resolution.action = action.name();
        resolution.targetId = null;
        resolution.detailsJson = new LinkedHashMap<>();
        resolution.detailsJson.put("sourceIssueCode", issue.code);
        resolution.detailsJson.put("sourceFileId", Objects.toString(issue.affectedFileId, ""));
        resolution.detailsJson.put("sourceRow", sourceRow(issue));
        resolution.detailsJson.put("reason", clean(reason));
        resolution.detailsJson.put("bulk", bulk);
        if (verifiedRate != null) resolution.detailsJson.put("verifiedRate", verifiedRate.stripTrailingZeros().toPlainString());
        resolution.updatedBy = TenantContext.userId();
        if (resolution.createdBy == null) resolution.createdBy = TenantContext.userId();
        resolutions.save(resolution);
    }

    private void validate(
        ImportPlanIssue issue,
        DomainEnums.VoucherRateResolutionAction action,
        BigDecimal verifiedRate,
        String reason,
        String confirmation
    ) {
        if (!eligibleActions(issue).contains(action.name())) {
            throw ApiErrors.badRequest("This rate review row does not support " + action.name());
        }
        switch (action) {
            case CONFIRM_ZERO_VALUE -> requireConfirmation(confirmation, CONFIRM_ZERO);
            case DERIVE_RATE -> {
                requireConfirmation(confirmation, CONFIRM_DERIVE);
                if (decimal(issue.contextJson.get("derivedRate")) == null) {
                    throw ApiErrors.badRequest("Amount and non-zero quantity are required to derive a rate");
                }
            }
            case TREAT_AS_FINANCIAL_ADJUSTMENT -> requireConfirmation(confirmation, CONFIRM_FINANCIAL);
            case ENTER_VERIFIED_RATE -> {
                requireConfirmation(confirmation, CONFIRM_MANUAL);
                if (verifiedRate == null || verifiedRate.signum() <= 0) {
                    throw ApiErrors.badRequest("Enter a positive source-supported rate");
                }
                if (clean(reason).length() < 5) {
                    throw ApiErrors.badRequest("Explain the source evidence for the verified rate");
                }
            }
            case EXCLUDE_VOUCHER -> requireConfirmation(confirmation, CONFIRM_EXCLUDE);
            case KEEP_UNRESOLVED -> { }
        }
    }

    private List<ImportPlanIssue> matchingIssues(
        UUID tenantId,
        UUID sessionId,
        ImportPlanIssue source,
        DomainEnums.VoucherRateResolutionAction action
    ) {
        var matchKey = rateMatchKey(source);
        if (matchKey.isBlank()) throw ApiErrors.badRequest("This review row cannot be matched safely in bulk");
        return issues.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(tenantId, sessionId).stream()
            .filter(issue -> !issue.resolved && rateCode(issue.code))
            .filter(issue -> matchKey.equals(rateMatchKey(issue)))
            .filter(issue -> eligibleActions(issue).contains(action.name()))
            .toList();
    }

    private Map<String, Object> bulkPreview(
        ImportPlanIssue source,
        DomainEnums.VoucherRateResolutionAction action,
        BigDecimal verifiedRate,
        List<ImportPlanIssue> matches
    ) {
        var rows = matches.stream().limit(100).map(issue -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("issueId", issue.id);
            row.put("fileId", issue.affectedFileId);
            row.put("row", sourceRow(issue));
            row.put("voucherType", issue.contextJson.get("voucherType"));
            row.put("voucherNumber", issue.contextJson.get("voucherNumber"));
            row.put("productName", issue.contextJson.get("productName"));
            row.put("quantity", issue.contextJson.get("quantity"));
            row.put("amount", issue.contextJson.get("amount"));
            row.put("derivedRate", issue.contextJson.get("derivedRate"));
            return row;
        }).toList();
        var voucherCount = matches.stream().map(issue -> context(issue, "voucherResolutionKey")).distinct().count();
        var response = new LinkedHashMap<String, Object>();
        response.put("action", action);
        response.put("matchKey", rateMatchKey(source));
        response.put("matchingRows", matches.size());
        response.put("matchingVouchers", voucherCount);
        response.put("verifiedRate", verifiedRate);
        response.put("rows", rows);
        response.put("rowsTruncated", matches.size() > rows.size());
        response.put("confirmation", "APPLY TO " + matches.size() + " MATCHING ROWS");
        return response;
    }

    private void auditResolution(
        UUID sessionId,
        ImportPlanIssue issue,
        DomainEnums.VoucherRateResolutionAction action,
        boolean bulk
    ) {
        var auditAction = switch (action) {
            case CONFIRM_ZERO_VALUE -> "SMART_IMPORT_ZERO_VALUE_CONFIRMED";
            case DERIVE_RATE -> "SMART_IMPORT_RATE_DERIVED";
            case TREAT_AS_FINANCIAL_ADJUSTMENT -> "SMART_IMPORT_FINANCIAL_ADJUSTMENT_CLASSIFIED";
            case ENTER_VERIFIED_RATE -> "SMART_IMPORT_VERIFIED_RATE_APPLIED";
            case EXCLUDE_VOUCHER -> "SMART_IMPORT_VOUCHER_EXCLUDED";
            case KEEP_UNRESOLVED -> "SMART_IMPORT_RATE_REVIEW_KEPT_UNRESOLVED";
        };
        audit.logCurrent(auditAction, "ImportPlanIssue", issue.id, Map.of(
            "sessionId", sessionId,
            "sourceRow", sourceRow(issue),
            "bulk", bulk
        ));
    }

    private ImportPlanIssue rateIssue(UUID tenantId, UUID sessionId, UUID issueId) {
        var issue = issues.findByTenantIdAndImportSessionIdAndId(tenantId, sessionId, issueId)
            .orElseThrow(() -> ApiErrors.notFound("Rate review item not found"));
        if (!rateCode(issue.code)) throw ApiErrors.badRequest("The selected review item is not a voucher rate issue");
        return issue;
    }

    private com.stockpilot.ai.domain.ImportSession session(UUID sessionId) {
        return sessions.findByTenantIdAndId(TenantContext.tenantId(), sessionId)
            .orElseThrow(() -> ApiErrors.notFound("Import session not found"));
    }

    private boolean rateCode(String code) {
        return "INVALID_RATE".equals(code) || "ZERO_RATE_ITEM".equals(code);
    }

    private List<String> eligibleActions(ImportPlanIssue issue) {
        var value = issue.contextJson.get("eligibleActions");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private String rateMatchKey(ImportPlanIssue issue) {
        return context(issue, "rateMatchKey");
    }

    private String context(ImportPlanIssue issue, String key) {
        return Objects.toString(issue.contextJson.get(key), "");
    }

    private int sourceRow(ImportPlanIssue issue) {
        return issue.affectedRows == null || issue.affectedRows.isEmpty() ? 0 : issue.affectedRows.getFirst();
    }

    private BigDecimal decimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String previewReason(DomainEnums.VoucherRateResolutionAction action) {
        return action == DomainEnums.VoucherRateResolutionAction.ENTER_VERIFIED_RATE
            ? "Bulk preview source evidence" : "";
    }

    private String confirmationFor(DomainEnums.VoucherRateResolutionAction action) {
        return switch (action) {
            case CONFIRM_ZERO_VALUE -> CONFIRM_ZERO;
            case DERIVE_RATE -> CONFIRM_DERIVE;
            case TREAT_AS_FINANCIAL_ADJUSTMENT -> CONFIRM_FINANCIAL;
            case ENTER_VERIFIED_RATE -> CONFIRM_MANUAL;
            case EXCLUDE_VOUCHER -> CONFIRM_EXCLUDE;
            case KEEP_UNRESOLVED -> "";
        };
    }

    private void requireConfirmation(String actual, String expected) {
        if (!expected.equals(actual)) throw ApiErrors.badRequest("Enter " + expected + " to confirm this resolution");
    }

    private void validateBulkSize(List<ImportPlanIssue> matches) {
        if (matches.isEmpty()) throw ApiErrors.badRequest("No unresolved rows match this rate pattern");
        if (matches.size() > MAX_BULK_ROWS) {
            throw ApiErrors.badRequest("Bulk rate resolution is limited to " + MAX_BULK_ROWS + " rows at a time");
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
