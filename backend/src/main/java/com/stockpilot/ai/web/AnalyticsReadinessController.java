package com.stockpilot.ai.web;
import com.stockpilot.ai.service.AnalyticsReadinessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;
@RestController @RequestMapping("/api/analytics")
public class AnalyticsReadinessController {
    private final AnalyticsReadinessService readiness;
    public AnalyticsReadinessController(AnalyticsReadinessService readiness){this.readiness=readiness;}
    @GetMapping("/readiness") @PreAuthorize("@permissionService.has('dashboard.view') or @permissionService.has('imports.view') or @permissionService.has('ai.assistant.use')")
    public Map<String,Object> overview(){return readiness.overview();}
    public record CoverageRequest(@NotNull LocalDate from,@NotNull LocalDate through,@NotNull Boolean complete){}
    @PutMapping("/coverage") @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String,Object> confirm(@Valid @RequestBody CoverageRequest request){return readiness.confirm(request.from(),request.through(),request.complete());}
}
