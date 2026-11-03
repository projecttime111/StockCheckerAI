package com.stockpilot.ai.web;

import com.stockpilot.ai.domain.ImportPlan;
import com.stockpilot.ai.domain.ImportPlanIssue;
import com.stockpilot.ai.domain.ImportSession;
import com.stockpilot.ai.domain.ImportSessionFile;
import com.stockpilot.ai.service.SmartImportSessionService;
import com.stockpilot.ai.service.SmartImportStagingService;
import com.stockpilot.ai.service.SmartImportDryRunService;
import com.stockpilot.ai.service.SmartImportCommitService;
import com.stockpilot.ai.service.CashbookReviewService;
import com.stockpilot.ai.service.CashbookBulkRetailService;
import com.stockpilot.ai.service.SmartImportReturnService;
import com.stockpilot.ai.service.SmartImportTransferService;
import com.stockpilot.ai.service.VoucherRateReviewService;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/import-sessions")
public class SmartImportSessionController {
    private final SmartImportSessionService smartImports;
    private final SmartImportStagingService staging;
    private final SmartImportDryRunService dryRuns;
    private final SmartImportCommitService commits;
    private final CashbookReviewService cashbookReviews;
    private final CashbookBulkRetailService bulkRetailSales;
    private final SmartImportReturnService returnReviews;
    private final SmartImportTransferService transferReviews;
    private final VoucherRateReviewService rateReviews;

    public SmartImportSessionController(
        SmartImportSessionService smartImports,
        SmartImportStagingService staging,
        SmartImportDryRunService dryRuns,
        SmartImportCommitService commits,
        CashbookReviewService cashbookReviews,
        CashbookBulkRetailService bulkRetailSales,
        SmartImportReturnService returnReviews,
        SmartImportTransferService transferReviews,
        VoucherRateReviewService rateReviews
    ) {
        this.smartImports = smartImports;
        this.staging = staging;
        this.dryRuns = dryRuns;
        this.commits = commits;
        this.cashbookReviews = cashbookReviews;
        this.bulkRetailSales = bulkRetailSales;
        this.returnReviews = returnReviews;
        this.transferReviews = transferReviews;
        this.rateReviews = rateReviews;
    }

    @PostMapping
    @PreAuthorize("@permissionService.has('imports.upload')")
    public Map<String, Object> create(@Valid @RequestBody(required = false) SmartImportDtos.CreateSessionRequest request) {
        return session(smartImports.create(request == null ? null : request.name()));
    }

    @GetMapping
    @PreAuthorize("@permissionService.has('imports.view')")
    public Page<Map<String, Object>> list(Pageable pageable) {
        return smartImports.list(pageable).map(this::session);
    }

    @GetMapping("/{sessionId}")
    @PreAuthorize("@permissionService.has('imports.view')")
    public Map<String, Object> get(@PathVariable UUID sessionId) {
        return details(sessionId);
    }

    @PostMapping("/{sessionId}/files")
    @PreAuthorize("@permissionService.has('imports.upload')")
    public Map<String, Object> upload(@PathVariable UUID sessionId, @RequestPart("files") List<MultipartFile> files) {
        smartImports.upload(sessionId, files);
        return details(sessionId);
    }

    @PostMapping("/{sessionId}/classify")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> classify(@PathVariable UUID sessionId) {
        smartImports.classify(sessionId);
        return details(sessionId);
    }

    @PostMapping("/{sessionId}/stage")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> stage(@PathVariable UUID sessionId) {
        return staging.stage(sessionId);
    }

    @PostMapping("/{sessionId}/rebuild")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> rebuild(@PathVariable UUID sessionId) {
        return staging.rebuild(sessionId);
    }

    @GetMapping("/{sessionId}/staging")
    @PreAuthorize("@permissionService.has('imports.view')")
    public Map<String, Object> staging(@PathVariable UUID sessionId) {
        return staging.workspace(sessionId);
    }

    @GetMapping("/{sessionId}/review-items")
    @PreAuthorize("@permissionService.has('imports.view')")
    public List<Map<String, Object>> reviewItems(@PathVariable UUID sessionId) {
        return staging.reviewItems(sessionId);
    }

    @PostMapping("/{sessionId}/review-items/{itemId}/resolve")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> resolve(
        @PathVariable UUID sessionId,
        @PathVariable UUID itemId,
        @Valid @RequestBody SmartImportDtos.ReviewResolutionRequest request
    ) {
        return staging.resolve(sessionId, itemId, request.action(), request.targetId(), request.selectedFileType());
    }

    @PostMapping("/{sessionId}/rate-review/{itemId}/resolve")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> resolveRate(
        @PathVariable UUID sessionId,
        @PathVariable UUID itemId,
        @Valid @RequestBody SmartImportDtos.VoucherRateResolutionRequest request
    ) {
        return rateReviews.resolve(sessionId, itemId, request);
    }

    @PostMapping("/{sessionId}/rate-review/bulk/preview")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> previewBulkRateResolution(
        @PathVariable UUID sessionId,
        @Valid @RequestBody SmartImportDtos.VoucherRateBulkPreviewRequest request
    ) {
        return rateReviews.previewBulk(sessionId, request);
    }

    @PostMapping("/{sessionId}/rate-review/bulk/resolve")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> resolveBulkRates(
        @PathVariable UUID sessionId,
        @Valid @RequestBody SmartImportDtos.VoucherRateBulkResolutionRequest request
    ) {
        return rateReviews.resolveBulk(sessionId, request);
    }

    @GetMapping("/{sessionId}/cashbook-review")
    @PreAuthorize("@permissionService.has('imports.view')")
    public List<Map<String, Object>> unresolvedCashbook(@PathVariable UUID sessionId) {
        return cashbookReviews.unresolved(sessionId);
    }

    @PostMapping("/{sessionId}/cashbook-review/recheck")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> recheckCashbook(@PathVariable UUID sessionId) {
        return cashbookReviews.recheck(sessionId);
    }

    @PostMapping("/{sessionId}/cashbook-review/{entryId}/resolve")
    @PreAuthorize("@permissionService.has('imports.commit')")
    public Map<String, Object> resolveCashbook(
        @PathVariable UUID sessionId,
        @PathVariable UUID entryId,
        @Valid @RequestBody SmartImportDtos.CashbookReviewResolutionRequest request
    ) {
        return cashbookReviews.resolve(sessionId, entryId, request);
    }

    @PostMapping("/{sessionId}/cashbook-review/retail-sales/bulk")
    @PreAuthorize("@permissionService.has('imports.commit')")
    public Map<String, Object> bulkRetailSales(
        @PathVariable UUID sessionId,
        @Valid @RequestBody SmartImportDtos.BulkRetailSaleResolutionRequest request
    ) {
        return bulkRetailSales.record(sessionId, request);
    }

    @GetMapping("/{sessionId}/return-review")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public List<Map<String, Object>> unresolvedReturns(@PathVariable UUID sessionId) {
        return returnReviews.unresolved(sessionId);
    }

    @PostMapping("/{sessionId}/return-review/{itemId}/resolve")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> resolveReturn(
        @PathVariable UUID sessionId,
        @PathVariable UUID itemId,
        @Valid @RequestBody SmartImportDtos.ReturnReviewResolutionRequest request
    ) {
        return returnReviews.resolve(sessionId, itemId, request);
    }

    @GetMapping("/{sessionId}/transfer-review")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public List<Map<String, Object>> transferReview(@PathVariable UUID sessionId) {
        return transferReviews.review(sessionId);
    }

    @PostMapping("/{sessionId}/transfer-review/{candidateId}/resolve")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> resolveTransfer(
        @PathVariable UUID sessionId,
        @PathVariable UUID candidateId,
        @Valid @RequestBody SmartImportDtos.TransferReviewResolutionRequest request
    ) {
        return transferReviews.resolve(sessionId, candidateId, request);
    }

    @PatchMapping("/{sessionId}/files/{fileId}/type")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> overrideFileType(
        @PathVariable UUID sessionId,
        @PathVariable UUID fileId,
        @Valid @RequestBody SmartImportDtos.FileTypeOverrideRequest request
    ) {
        return staging.overrideFileType(sessionId, fileId, request.selectedFileType());
    }

    @DeleteMapping("/{sessionId}/files/{fileId}")
    @PreAuthorize("@permissionService.has('imports.upload')")
    public Map<String, Object> removeFile(@PathVariable UUID sessionId, @PathVariable UUID fileId) {
        return staging.removeFile(sessionId, fileId);
    }

    @GetMapping("/{sessionId}/files")
    @PreAuthorize("@permissionService.has('imports.view')")
    public List<Map<String, Object>> files(@PathVariable UUID sessionId) {
        return smartImports.files(sessionId).stream().map(this::file).toList();
    }

    @GetMapping("/{sessionId}/plan")
    @PreAuthorize("@permissionService.has('imports.view')")
    public Map<String, Object> plan(@PathVariable UUID sessionId) {
        return planResponse(smartImports.plan(sessionId), smartImports.issues(sessionId));
    }

    @PostMapping("/{sessionId}/plan")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> buildPlan(@PathVariable UUID sessionId) {
        return planResponse(smartImports.buildPlan(sessionId), smartImports.issues(sessionId));
    }

    @PostMapping("/{sessionId}/dry-run")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> dryRun(
        @PathVariable UUID sessionId,
        @RequestBody(required = false) SmartImportDtos.DryRunRequest request
    ) {
        return dryRuns.run(sessionId, request);
    }

    @GetMapping("/{sessionId}/dry-run")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> latestDryRun(@PathVariable UUID sessionId) {
        return dryRuns.latest(sessionId);
    }

    @GetMapping("/{sessionId}/dry-run/items")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Page<Map<String, Object>> dryRunItems(
        @PathVariable UUID sessionId,
        @RequestParam(required = false) DomainEnums.ImportDryRunItemType itemType,
        @RequestParam(required = false) DomainEnums.ImportDryRunAction action,
        @RequestParam(required = false) String issue,
        @RequestParam(required = false) UUID sourceFileId,
        Pageable pageable
    ) {
        return dryRuns.items(sessionId, itemType, action, issue, sourceFileId, pageable);
    }

    @PostMapping("/{sessionId}/commit")
    @PreAuthorize("@permissionService.has('imports.commit')")
    public Map<String, Object> commit(
        @PathVariable UUID sessionId,
        @Valid @RequestBody SmartImportDtos.CommitRequest request
    ) {
        return commits.commit(sessionId, request);
    }

    @GetMapping("/{sessionId}/commit-result")
    @PreAuthorize("@permissionService.has('imports.commit')")
    public Map<String, Object> commitResult(@PathVariable UUID sessionId) {
        return commits.result(sessionId);
    }

    @PostMapping("/{sessionId}/cancel")
    @PreAuthorize("@permissionService.has('imports.upload')")
    public Map<String, Object> cancel(@PathVariable UUID sessionId) {
        smartImports.cancel(sessionId);
        return details(sessionId);
    }

    private Map<String, Object> details(UUID sessionId) {
        var response = new LinkedHashMap<String, Object>();
        response.putAll(session(smartImports.get(sessionId)));
        response.put("files", smartImports.files(sessionId).stream().map(this::file).toList());
        response.put("issues", smartImports.issues(sessionId).stream().map(this::issue).toList());
        var plan = smartImports.planOrNull(sessionId);
        response.put("plan", plan == null ? Map.of() : plan(plan));
        return response;
    }

    private Map<String, Object> session(ImportSession session) {
        var response = new LinkedHashMap<String, Object>();
        response.put("id", session.id);
        response.put("name", session.name);
        response.put("status", session.status);
        response.put("recommendedStrategy", session.recommendedStrategy);
        response.put("createdBy", session.createdBy == null ? "" : session.createdBy);
        response.put("createdAt", session.createdAt == null ? "" : session.createdAt);
        response.put("updatedAt", session.updatedAt == null ? "" : session.updatedAt);
        response.put("committedAt", session.committedAt == null ? "" : session.committedAt);
        return response;
    }

    private Map<String, Object> file(ImportSessionFile file) {
        var response = new LinkedHashMap<String, Object>();
        response.put("id", file.id);
        response.put("originalFileName", file.originalFileName);
        response.put("fileHash", file.fileHash);
        response.put("detectedFileType", file.detectedFileType);
        response.put("selectedFileType", file.selectedFileType == null ? "" : file.selectedFileType);
        response.put("confidence", file.confidence);
        response.put("detectionReason", file.detectionReason == null ? "" : file.detectionReason);
        response.put("status", file.status);
        response.put("rowCount", file.rowCount);
        response.put("errorCount", file.errorCount);
        response.put("warningCount", file.warningCount);
        response.put("dateRangeStart", file.dateRangeStart == null ? "" : file.dateRangeStart);
        response.put("dateRangeEnd", file.dateRangeEnd == null ? "" : file.dateRangeEnd);
        response.put("companyName", file.companyName == null ? "" : file.companyName);
        response.put("metadata", file.metadataJson);
        return response;
    }

    private Map<String, Object> plan(ImportPlan plan) {
        var response = new LinkedHashMap<String, Object>();
        response.put("id", plan.id);
        response.put("strategy", plan.strategy);
        response.put("status", plan.status);
        response.put("details", plan.planJson);
        response.put("createdAt", plan.createdAt == null ? "" : plan.createdAt);
        response.put("updatedAt", plan.updatedAt == null ? "" : plan.updatedAt);
        return response;
    }

    private Map<String, Object> issue(ImportPlanIssue issue) {
        var response = new LinkedHashMap<String, Object>();
        response.put("id", issue.id);
        response.put("severity", issue.severity);
        response.put("code", issue.code);
        response.put("message", issue.message);
        response.put("affectedFileId", issue.affectedFileId == null ? "" : issue.affectedFileId);
        response.put("affectedRows", issue.affectedRows);
        response.put("suggestedAction", issue.suggestedAction == null ? "" : issue.suggestedAction);
        response.put("resolved", issue.resolved);
        response.put("availableChoices", issue.availableChoices);
        response.put("context", issue.contextJson);
        response.put("resolution", issue.resolutionJson);
        return response;
    }

    private Map<String, Object> planResponse(ImportPlan plan, List<ImportPlanIssue> issues) {
        var response = new LinkedHashMap<String, Object>();
        response.putAll(plan(plan));
        response.put("issues", issues.stream().map(this::issue).toList());
        return response;
    }
}
