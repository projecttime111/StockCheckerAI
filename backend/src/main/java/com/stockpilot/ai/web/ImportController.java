package com.stockpilot.ai.web;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.ImportBatch;
import com.stockpilot.ai.domain.ImportError;
import com.stockpilot.ai.domain.ImportMappingTemplate;
import com.stockpilot.ai.service.ImportService;
import com.stockpilot.ai.service.InvoiceExtractionService;
import com.stockpilot.ai.service.ObjectStorageService;
import com.stockpilot.ai.web.dto.ApiDtos;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/imports")
public class ImportController {
    private final ImportService imports;
    private final InvoiceExtractionService extraction;
    private final ObjectStorageService storage;

    public ImportController(ImportService imports, InvoiceExtractionService extraction, ObjectStorageService storage) {
        this.imports = imports;
        this.extraction = extraction;
        this.storage = storage;
    }

    @PostMapping("/upload")
    @PreAuthorize("@permissionService.has('imports.upload')")
    public ApiDtos.ImportUploadResponse upload(@RequestParam(defaultValue = "CSV") DomainEnums.SourceType sourceType, @RequestPart("file") MultipartFile file) {
        return imports.upload(sourceType, file);
    }

    @GetMapping
    @PreAuthorize("@permissionService.has('imports.view')")
    public Page<Map<String, Object>> list(Pageable pageable) {
        return imports.list(pageable).map(this::batch);
    }

    @GetMapping("/{batchId}")
    @PreAuthorize("@permissionService.has('imports.view')")
    public Map<String, Object> get(@PathVariable UUID batchId) {
        return batch(imports.get(batchId));
    }

    @GetMapping("/{batchId}/preview")
    @PreAuthorize("@permissionService.has('imports.view')")
    public List<Map<String, Object>> preview(@PathVariable UUID batchId) {
        return imports.preview(batchId);
    }

    @PostMapping("/{batchId}/mapping")
    @PreAuthorize("@permissionService.has('imports.map')")
    public Map<String, Object> mapping(@PathVariable UUID batchId, @Valid @RequestBody ApiDtos.ImportMappingRequest request) {
        return batch(imports.applyMapping(batchId, request));
    }

    @PostMapping("/{batchId}/validate")
    @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String, Object> validate(@PathVariable UUID batchId, @RequestBody(required = false) ApiDtos.ImportResolutionRequest request) {
        return batch(imports.validate(
            batchId,
            request == null ? null : request.negativeStockPolicy(),
            request == null ? null : request.voucherStockImpactMode(),
            request != null && Boolean.TRUE.equals(request.confirmStockMovementsAfterSnapshot())
        ));
    }

    @PostMapping("/{batchId}/commit")
    @PreAuthorize("@permissionService.has('imports.commit')")
    public Map<String, Object> commit(@PathVariable UUID batchId, @RequestBody(required = false) ApiDtos.ImportResolutionRequest request) {
        return imports.commit(
            batchId,
            request == null ? null : request.negativeStockPolicy(),
            request == null ? null : request.voucherStockImpactMode(),
            request != null && Boolean.TRUE.equals(request.confirmStockMovementsAfterSnapshot())
        );
    }

    @GetMapping("/{batchId}/errors")
    @PreAuthorize("@permissionService.has('imports.view')")
    public List<Map<String, Object>> errors(@PathVariable UUID batchId) {
        return imports.errors(batchId).stream().map(this::error).toList();
    }

    @GetMapping("/{batchId}/reconciliation")
    @PreAuthorize("@permissionService.has('imports.view')")
    public Map<String, Object> reconciliation(@PathVariable UUID batchId) {
        return imports.reconciliation(batchId);
    }

    @GetMapping("/{batchId}/undo-preview")
    @PreAuthorize("@permissionService.has('imports.rollback')")
    public Map<String, Object> undoPreview(@PathVariable UUID batchId) {
        return imports.undoPreview(batchId);
    }

    @PostMapping("/{batchId}/undo")
    @PreAuthorize("@permissionService.has('imports.rollback')")
    public Map<String, Object> undo(@PathVariable UUID batchId, @RequestBody(required = false) ApiDtos.ImportUndoRequest request) {
        return imports.undo(batchId, request == null ? null : request.strategy());
    }

    @PostMapping("/{batchId}/replace")
    @PreAuthorize("@permissionService.has('imports.rollback') and @permissionService.has('imports.upload')")
    public Map<String, Object> replace(@PathVariable UUID batchId) {
        return imports.replace(batchId);
    }

    @GetMapping("/templates")
    @PreAuthorize("@permissionService.has('imports.view')")
    public List<Map<String, Object>> templates() {
        return imports.templates().stream().map(this::template).toList();
    }

    @PostMapping("/templates")
    @PreAuthorize("@permissionService.has('imports.map')")
    public Map<String, Object> saveTemplate(@Valid @RequestBody ApiDtos.ImportTemplateRequest request) {
        return template(imports.saveTemplate(request));
    }

    @PostMapping("/invoice-upload")
    @PreAuthorize("@permissionService.has('stock.adjust') or @permissionService.has('purchases.create')")
    public Map<String, Object> invoiceUpload(@RequestPart("file") MultipartFile file) throws Exception {
        ImportService.validateUpload(file, Set.of("pdf", "png", "jpg", "jpeg", "webp", "csv", "xlsx", "xls", "xml"), 25L * 1024L * 1024L);
        var extracted = extraction.extract(file);
        var key = TenantContext.tenantId() + "/invoice-uploads/" + System.currentTimeMillis() + "-" + (file.getOriginalFilename() == null ? "invoice" : file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_"));
        storage.store(key, file);
        return Map.of("storageKey", key, "extraction", extracted);
    }

    private Map<String, Object> batch(ImportBatch batch) {
        batch = imports.withVoucherStockImpactContext(batch);
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("id", batch.id);
        response.put("sourceType", batch.sourceType);
        response.put("status", batch.status);
        response.put("originalFileName", batch.originalFileName == null ? "" : batch.originalFileName);
        response.put("rowCount", batch.rowCount);
        response.put("validCount", batch.validCount);
        response.put("errorCount", batch.errorCount);
        response.put("importPurpose", batch.importPurpose == null ? "MASTER_IMPORT" : batch.importPurpose.name());
        response.put("committedAt", batch.committedAt == null ? "" : batch.committedAt.toString());
        response.put("rolledBackAt", batch.rolledBackAt == null ? "" : batch.rolledBackAt.toString());
        response.put("rollbackStatus", batch.status == DomainEnums.ImportStatus.ROLLED_BACK ? "ROLLED_BACK" : "NOT_ROLLED_BACK");
        response.put("negativeStockPolicy", batch.mappingJson == null ? "BLOCK" : String.valueOf(batch.mappingJson.getOrDefault("negativeStockPolicy", "BLOCK")));
        response.put("voucherStockImpactMode", batch.mappingJson == null ? "CREATE_INVOICES_AND_STOCK_MOVEMENTS" : String.valueOf(batch.mappingJson.getOrDefault("voucherStockImpactMode", "CREATE_INVOICES_AND_STOCK_MOVEMENTS")));
        response.put("hasStockSnapshot", batch.mappingJson != null && Boolean.parseBoolean(String.valueOf(batch.mappingJson.getOrDefault("hasStockSnapshot", false))));
        response.put("latestSnapshotDate", batch.mappingJson == null ? "" : String.valueOf(batch.mappingJson.getOrDefault("latestSnapshotDate", "")));
        response.put("recommendedVoucherStockImpactMode", batch.mappingJson == null ? "CREATE_INVOICES_AND_STOCK_MOVEMENTS" : String.valueOf(batch.mappingJson.getOrDefault("recommendedVoucherStockImpactMode", "CREATE_INVOICES_AND_STOCK_MOVEMENTS")));
        response.put("voucherDateFrom", batch.mappingJson == null ? "" : String.valueOf(batch.mappingJson.getOrDefault("voucherDateFrom", "")));
        response.put("voucherDateTo", batch.mappingJson == null ? "" : String.valueOf(batch.mappingJson.getOrDefault("voucherDateTo", "")));
        response.put("warningAboutDoubleCounting", batch.mappingJson == null ? "" : String.valueOf(batch.mappingJson.getOrDefault("warningAboutDoubleCounting", "")));
        response.put("mapping", batch.mappingJson == null ? Map.of() : batch.mappingJson);
        return response;
    }

    private Map<String, Object> error(ImportError error) {
        return Map.of(
            "batchId", error.importBatchId,
            "rowNumber", error.rowNumber,
            "entityType", error.entityType == null ? "" : error.entityType,
            "fieldName", error.fieldName == null ? "" : error.fieldName,
            "errorCode", error.errorCode == null ? "IMPORT_ERROR" : error.errorCode,
            "message", error.message == null ? "Import validation failed" : error.message,
            "severity", error.severity == null ? "ERROR" : error.severity,
            "rawValue", error.rawValue == null ? "" : error.rawValue,
            "suggestedFix", error.suggestedFix == null ? "" : error.suggestedFix
        );
    }

    private Map<String, Object> template(ImportMappingTemplate template) {
        return Map.of("id", template.id, "name", template.name, "sourceType", template.sourceType, "mapping", template.mappingJson);
    }
}
