package com.stockpilot.ai.web;

import com.stockpilot.ai.service.AccountLifecycleService;
import com.stockpilot.ai.service.TenantService;
import com.stockpilot.ai.web.dto.ApiDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tenants/current")
public class TenantController {
    private final TenantService tenantService;
    private final AccountLifecycleService lifecycleService;

    public TenantController(TenantService tenantService, AccountLifecycleService lifecycleService) {
        this.tenantService = tenantService;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    @PreAuthorize("@permissionService.has('dashboard.view') or @permissionService.has('settings.view') or @permissionService.has('portal.customer.view')")
    public ApiDtos.TenantResponse current() {
        return tenantService.response(tenantService.current());
    }

    @PutMapping
    @PreAuthorize("@permissionService.has('settings.manage')")
    public ApiDtos.TenantResponse update(@Valid @RequestBody ApiDtos.TenantUpdateRequest request) {
        return tenantService.update(request);
    }

    @PostMapping("/business-mode")
    @PreAuthorize("@permissionService.has('settings.manage')")
    public ApiDtos.TenantResponse updateMode(@Valid @RequestBody ApiDtos.BusinessModeRequest request) {
        return tenantService.updateMode(request);
    }

    @GetMapping("/delete-impact")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ApiDtos.DeleteImpactResponse deleteImpact() {
        return lifecycleService.deleteImpact();
    }

    @PostMapping("/reset-business-data")
    @PreAuthorize("hasRole('OWNER')")
    public ApiDtos.WorkspaceResetResponse resetBusinessData(@Valid @RequestBody ApiDtos.DestructiveActionRequest request) {
        return lifecycleService.resetCurrentWorkspace(request.confirmation());
    }

    @DeleteMapping
    @PreAuthorize("hasRole('OWNER')")
    public ApiDtos.WorkspaceDeletionResponse deleteWorkspace(@Valid @RequestBody ApiDtos.DestructiveActionRequest request) {
        return lifecycleService.deleteCurrentWorkspace(request.confirmation());
    }
}
