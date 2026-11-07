package com.stockpilot.ai.web;

import com.stockpilot.ai.service.PlatformAdminService;
import com.stockpilot.ai.web.dto.PlatformDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/platform")
public class PlatformController {
    private final PlatformAdminService platform;
    public PlatformController(PlatformAdminService platform) { this.platform=platform; }

    @GetMapping("/dashboard")
    @PreAuthorize("@permissionService.has('platform.system_health.view')")
    public PlatformDtos.Dashboard dashboard() { return platform.dashboard(); }

    @GetMapping("/businesses")
    @PreAuthorize("@permissionService.has('platform.tenants.view')")
    public PlatformDtos.Page<PlatformDtos.BusinessSummary> businesses(@RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size,@RequestParam(required=false) String search,
        @RequestParam(required=false) String status,@RequestParam(required=false) String plan,
        @RequestParam(required=false) String subscriptionStatus) {
        return platform.businesses(page,size,search,status,plan,subscriptionStatus);
    }

    @GetMapping("/businesses/{id}")
    @PreAuthorize("@permissionService.has('platform.tenants.view')")
    public PlatformDtos.BusinessDetail business(@PathVariable UUID id) { return platform.business(id); }

    @GetMapping("/businesses/{id}/members")
    @PreAuthorize("@permissionService.has('platform.audit.view')")
    public PlatformDtos.Page<PlatformDtos.Member> members(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return platform.members(id,page,size);
    }

    @GetMapping("/businesses/{id}/imports")
    @PreAuthorize("@permissionService.has('platform.audit.view')")
    public PlatformDtos.Page<PlatformDtos.ImportSummary> businessImports(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size,@RequestParam(required=false) String status,@RequestParam(required=false) String source,
        @RequestParam(defaultValue="false") boolean failedOnly) {
        return platform.imports(page,size,null,status,source,failedOnly,id);
    }

    @PatchMapping("/businesses/{id}/status")
    @PreAuthorize("@permissionService.has('platform.tenants.manage')")
    public PlatformDtos.BusinessDetail status(@PathVariable UUID id,@Valid @RequestBody PlatformDtos.StatusUpdate request) { return platform.updateStatus(id,request); }

    @PatchMapping("/businesses/{id}/subscription")
    @PreAuthorize("@permissionService.has('platform.subscriptions.manage')")
    public PlatformDtos.BusinessDetail subscription(@PathVariable UUID id,@Valid @RequestBody PlatformDtos.SubscriptionUpdate request) { return platform.updateSubscription(id,request); }

    @PatchMapping("/businesses/{id}/notes")
    @PreAuthorize("@permissionService.has('platform.tenants.manage')")
    public PlatformDtos.BusinessDetail notes(@PathVariable UUID id,@Valid @RequestBody PlatformDtos.NotesUpdate request) { return platform.updateNotes(id,request); }

    @GetMapping("/imports")
    @PreAuthorize("@permissionService.has('platform.audit.view')")
    public PlatformDtos.Page<PlatformDtos.ImportSummary> imports(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(required=false) String search,@RequestParam(required=false) String status,@RequestParam(required=false) String source,
        @RequestParam(defaultValue="false") boolean failedOnly,@RequestParam(required=false) UUID tenantId) {
        return platform.imports(page,size,search,status,source,failedOnly,tenantId);
    }

    @GetMapping("/audit")
    @PreAuthorize("@permissionService.has('platform.audit.view')")
    public PlatformDtos.Page<PlatformDtos.AuditEntry> audit(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
        @RequestParam(required=false) UUID tenantId,@RequestParam(required=false) String action) { return platform.audit(page,size,tenantId,action); }

    @GetMapping("/system")
    @PreAuthorize("@permissionService.has('platform.system_health.view')")
    public PlatformDtos.SystemHealth system() { return platform.system(); }
}
