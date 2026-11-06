package com.stockpilot.ai.web;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.AuditLog;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.service.SensitiveDataService;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {
    private final Repositories.AuditLogRepository auditLogs;
    private final SensitiveDataService sensitive;

    public AuditLogController(Repositories.AuditLogRepository auditLogs, SensitiveDataService sensitive) {
        this.auditLogs = auditLogs;
        this.sensitive = sensitive;
    }

    @GetMapping
    @PreAuthorize("@permissionService.has('audit.view')")
    public Page<ApiDtos.AuditLogResponse> list(Pageable pageable) {
        return auditLogs.findByTenantId(TenantContext.tenantId(), pageable).map(this::response);
    }

    private ApiDtos.AuditLogResponse response(AuditLog audit) {
        return new ApiDtos.AuditLogResponse(
            audit.id,
            audit.actorUserId,
            audit.action,
            audit.entityType,
            audit.entityId,
            sensitive.auditDetails(audit.detailsJson),
            audit.createdAt
        );
    }
}
