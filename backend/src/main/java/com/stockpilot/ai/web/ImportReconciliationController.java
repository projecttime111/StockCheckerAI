package com.stockpilot.ai.web;
import com.stockpilot.ai.service.ImportReconciliationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/import-sessions")
public class ImportReconciliationController {
    private final ImportReconciliationService reconciliation;
    public ImportReconciliationController(ImportReconciliationService reconciliation){this.reconciliation=reconciliation;}
    @GetMapping("/{sessionId}/reconciliation") @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String,Object> report(@PathVariable UUID sessionId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size){return reconciliation.report(sessionId,page,size);}
}
