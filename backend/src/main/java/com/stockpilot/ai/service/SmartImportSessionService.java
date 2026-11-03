package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.ImportPlan;
import com.stockpilot.ai.domain.ImportPlanIssue;
import com.stockpilot.ai.domain.ImportSession;
import com.stockpilot.ai.domain.ImportSessionFile;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SmartImportSessionService {
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("xml", "csv", "xlsx", "xls", "json");

    private final Repositories.ImportSessionRepository sessions;
    private final Repositories.ImportSessionFileRepository files;
    private final Repositories.ImportPlanRepository plans;
    private final Repositories.ImportPlanIssueRepository issues;
    private final Repositories.ImportDryRunRepository dryRuns;
    private final Repositories.ImportDryRunItemRepository dryRunItems;
    private final ObjectStorageService storage;
    private final ImportFileClassifierService classifier;
    private final ImportPlanBuilderService planBuilder;
    private final AuditService auditService;
    private final long maxUploadBytes;

    public SmartImportSessionService(
        Repositories.ImportSessionRepository sessions,
        Repositories.ImportSessionFileRepository files,
        Repositories.ImportPlanRepository plans,
        Repositories.ImportPlanIssueRepository issues,
        Repositories.ImportDryRunRepository dryRuns,
        Repositories.ImportDryRunItemRepository dryRunItems,
        ObjectStorageService storage,
        ImportFileClassifierService classifier,
        ImportPlanBuilderService planBuilder,
        AuditService auditService,
        @Value("${app.import.max-upload-size:500MB}") DataSize maxUploadSize
    ) {
        this.sessions = sessions;
        this.files = files;
        this.plans = plans;
        this.issues = issues;
        this.dryRuns = dryRuns;
        this.dryRunItems = dryRunItems;
        this.storage = storage;
        this.classifier = classifier;
        this.planBuilder = planBuilder;
        this.auditService = auditService;
        this.maxUploadBytes = maxUploadSize.toBytes();
    }

    @Transactional
    public ImportSession create(String requestedName) {
        var session = new ImportSession();
        session.tenantId = TenantContext.tenantId();
        session.name = requestedName == null || requestedName.isBlank()
            ? "Smart import " + Instant.now()
            : requestedName.trim();
        session.createdBy = TenantContext.userId();
        session.updatedBy = TenantContext.userId();
        sessions.save(session);
        auditService.logCurrent("SMART_IMPORT_SESSION_CREATED", "ImportSession", session.id, Map.of("name", session.name));
        return session;
    }

    public Page<ImportSession> list(Pageable pageable) {
        return sessions.findByTenantIdOrderByCreatedAtDesc(TenantContext.tenantId(), pageable);
    }

    public ImportSession get(UUID sessionId) {
        return sessions.findByTenantIdAndId(TenantContext.tenantId(), sessionId)
            .orElseThrow(() -> ApiErrors.notFound("Import session not found"));
    }

    public List<ImportSessionFile> files(UUID sessionId) {
        var session = get(sessionId);
        return files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id);
    }

    public List<ImportPlanIssue> issues(UUID sessionId) {
        var session = get(sessionId);
        return issues.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id);
    }

    public ImportPlan plan(UUID sessionId) {
        var session = get(sessionId);
        return plans.findByTenantIdAndImportSessionId(session.tenantId, session.id)
            .orElseThrow(() -> ApiErrors.notFound("Draft import plan has not been built yet"));
    }

    public ImportPlan planOrNull(UUID sessionId) {
        var session = get(sessionId);
        return plans.findByTenantIdAndImportSessionId(session.tenantId, session.id).orElse(null);
    }

    @Transactional
    public List<ImportSessionFile> upload(UUID sessionId, List<MultipartFile> uploads) {
        var session = get(sessionId);
        ensureMutable(session);
        invalidateDryRun(session);
        if (uploads == null || uploads.isEmpty()) {
            throw ApiErrors.badRequest("Select at least one file to upload");
        }
        uploads.forEach(upload -> ImportService.validateUpload(upload, SUPPORTED_EXTENSIONS, maxUploadBytes));

        var stored = new ArrayList<ImportSessionFile>();
        for (var upload : uploads) {
            stored.add(storeFile(session, upload));
        }
        issues.deleteByTenantIdAndImportSessionId(session.tenantId, session.id);
        plans.findByTenantIdAndImportSessionId(session.tenantId, session.id).ifPresent(plans::delete);
        var duplicateIssues = stored.stream()
            .filter(file -> file.status == DomainEnums.ImportSessionFileStatus.DUPLICATE)
            .map(file -> duplicateFileIssue(session, file))
            .toList();
        issues.saveAll(duplicateIssues);
        stored.stream()
            .filter(file -> file.status == DomainEnums.ImportSessionFileStatus.DUPLICATE)
            .forEach(file -> file.warningCount = 1);
        files.saveAll(stored);
        session.status = DomainEnums.ImportSessionStatus.FILES_UPLOADED;
        session.updatedBy = TenantContext.userId();
        sessions.save(session);
        auditService.logCurrent("SMART_IMPORT_FILES_UPLOADED", "ImportSession", session.id, Map.of(
            "fileCount", uploads.size(),
            "duplicateCount", stored.stream().filter(file -> file.status == DomainEnums.ImportSessionFileStatus.DUPLICATE).count()
        ));
        return stored;
    }

    @Transactional
    public List<ImportSessionFile> classify(UUID sessionId) {
        var session = get(sessionId);
        ensureMutable(session);
        invalidateDryRun(session);
        var sessionFiles = files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id);
        if (sessionFiles.isEmpty()) {
            throw ApiErrors.badRequest("Upload at least one file before classification");
        }

        for (var file : sessionFiles) {
            if (file.status == DomainEnums.ImportSessionFileStatus.DUPLICATE) {
                copyDuplicateClassification(sessionFiles, file);
                continue;
            }
            try (var input = storage.read(file.storageKey)) {
                apply(file, classifier.classify(file.originalFileName, input));
                file.status = DomainEnums.ImportSessionFileStatus.CLASSIFIED;
            } catch (Exception ex) {
                file.detectedFileType = DomainEnums.DetectedFileType.UNKNOWN;
                file.confidence = java.math.BigDecimal.ZERO;
                file.detectionReason = "Classification failed: " + safeMessage(ex);
                file.status = DomainEnums.ImportSessionFileStatus.FAILED;
                file.metadataJson.put("classificationError", safeMessage(ex));
            }
            file.updatedBy = TenantContext.userId();
        }
        files.saveAll(sessionFiles);
        planBuilder.rebuildClassificationIssues(session, sessionFiles);
        auditService.logCurrent("SMART_IMPORT_FILES_CLASSIFIED", "ImportSession", session.id, Map.of(
            "fileCount", sessionFiles.size(),
            "unknownCount", sessionFiles.stream().filter(file -> file.detectedFileType == DomainEnums.DetectedFileType.UNKNOWN).count()
        ));
        return sessionFiles;
    }

    @Transactional
    public ImportPlan buildPlan(UUID sessionId) {
        var session = get(sessionId);
        ensureMutable(session);
        var sessionFiles = files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id);
        if (sessionFiles.stream().anyMatch(file -> file.status == DomainEnums.ImportSessionFileStatus.UPLOADED)) {
            throw ApiErrors.badRequest("Classify uploaded files before building the draft plan");
        }
        var plan = planBuilder.build(session);
        auditService.logCurrent("SMART_IMPORT_PLAN_BUILT", "ImportSession", session.id, Map.of(
            "strategy", plan.strategy.name(),
            "status", plan.status.name()
        ));
        return plan;
    }

    @Transactional
    public ImportSession cancel(UUID sessionId) {
        var session = get(sessionId);
        if (session.status == DomainEnums.ImportSessionStatus.COMMITTED) {
            throw ApiErrors.conflict("A committed import session cannot be cancelled");
        }
        session.status = DomainEnums.ImportSessionStatus.CANCELLED;
        session.updatedBy = TenantContext.userId();
        sessions.save(session);
        plans.findByTenantIdAndImportSessionId(session.tenantId, session.id).ifPresent(plan -> {
            plan.status = DomainEnums.ImportPlanStatus.CANCELLED;
            plans.save(plan);
        });
        var sessionFiles = files.findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(session.tenantId, session.id);
        sessionFiles.forEach(file -> file.status = DomainEnums.ImportSessionFileStatus.CANCELLED);
        files.saveAll(sessionFiles);
        auditService.logCurrent("SMART_IMPORT_SESSION_CANCELLED", "ImportSession", session.id, Map.of());
        return session;
    }

    private ImportSessionFile storeFile(ImportSession session, MultipartFile upload) {
        var hash = hash(upload);
        var duplicate = files.findFirstByTenantIdAndImportSessionIdAndFileHashOrderByCreatedAtAsc(session.tenantId, session.id, hash).orElse(null);
        var file = new ImportSessionFile();
        file.tenantId = session.tenantId;
        file.importSessionId = session.id;
        file.originalFileName = upload.getOriginalFilename() == null ? "upload.dat" : upload.getOriginalFilename();
        file.contentType = upload.getContentType();
        file.fileHash = hash;
        file.createdBy = TenantContext.userId();
        file.updatedBy = TenantContext.userId();
        file.status = duplicate == null ? DomainEnums.ImportSessionFileStatus.UPLOADED : DomainEnums.ImportSessionFileStatus.DUPLICATE;
        file.metadataJson.put("sizeBytes", upload.getSize());
        if (duplicate != null) {
            file.metadataJson.put("duplicateOfFileId", duplicate.id.toString());
        }

        var key = session.tenantId + "/smart-imports/" + session.id + "/" + file.id + "-" + sanitize(file.originalFileName);
        try {
            file.storageKey = storage.store(key, upload);
        } catch (IOException ex) {
            throw ApiErrors.badRequest("Upload could not be stored");
        }
        return files.save(file);
    }

    private void copyDuplicateClassification(List<ImportSessionFile> sessionFiles, ImportSessionFile duplicate) {
        var original = sessionFiles.stream()
            .filter(file -> !file.id.equals(duplicate.id))
            .filter(file -> file.fileHash.equals(duplicate.fileHash))
            .filter(file -> file.status == DomainEnums.ImportSessionFileStatus.CLASSIFIED)
            .findFirst()
            .orElse(null);
        if (original == null) {
            return;
        }
        duplicate.detectedFileType = original.detectedFileType;
        duplicate.confidence = original.confidence;
        duplicate.detectionReason = "Duplicate of " + original.originalFileName + "; classification was reused.";
        duplicate.rowCount = original.rowCount;
        duplicate.dateRangeStart = original.dateRangeStart;
        duplicate.dateRangeEnd = original.dateRangeEnd;
        duplicate.companyName = original.companyName;
        duplicate.metadataJson.put("classificationReused", true);
    }

    private void apply(ImportSessionFile file, ImportFileClassifierService.DetectionResult result) {
        file.detectedFileType = result.detectedFileType();
        file.confidence = result.confidence();
        file.detectionReason = result.reason();
        file.rowCount = result.rowCount();
        file.dateRangeStart = result.dateRangeStart();
        file.dateRangeEnd = result.dateRangeEnd();
        file.companyName = result.companyName();
        file.metadataJson.putAll(result.metadata());
    }

    private String hash(MultipartFile upload) {
        try (InputStream input = upload.getInputStream()) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw ApiErrors.badRequest("Upload file could not be hashed safely");
        }
    }

    private void ensureMutable(ImportSession session) {
        if (session.status == DomainEnums.ImportSessionStatus.CANCELLED) {
            throw ApiErrors.conflict("Cancelled import sessions cannot be changed");
        }
        if (session.status == DomainEnums.ImportSessionStatus.COMMITTED || session.status == DomainEnums.ImportSessionStatus.COMMITTING) {
            throw ApiErrors.conflict("This import session can no longer be changed");
        }
    }

    private void invalidateDryRun(ImportSession session) {
        dryRunItems.deleteByTenantIdAndImportSessionId(session.tenantId, session.id);
        dryRuns.deleteByTenantIdAndImportSessionId(session.tenantId, session.id);
    }

    private ImportPlanIssue duplicateFileIssue(ImportSession session, ImportSessionFile file) {
        var issue = new ImportPlanIssue();
        issue.tenantId = session.tenantId;
        issue.importSessionId = session.id;
        issue.affectedFileId = file.id;
        issue.severity = DomainEnums.ImportPlanIssueSeverity.WARNING;
        issue.code = "DUPLICATE_FILE";
        issue.message = "This file appears to have already been uploaded.";
        issue.suggestedAction = "Keep one copy; duplicate files are not classified twice by default.";
        return issue;
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String safeMessage(Exception ex) {
        var message = ex.getMessage();
        return message == null || message.isBlank() ? "invalid file content" : message.replaceAll("[\\r\\n]+", " ");
    }
}
