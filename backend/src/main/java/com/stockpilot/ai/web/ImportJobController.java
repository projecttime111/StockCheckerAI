package com.stockpilot.ai.web;
import com.stockpilot.ai.service.ImportJobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/import-sessions/{sessionId}/jobs")
public class ImportJobController {
    private final ImportJobService jobs;
    public ImportJobController(ImportJobService jobs){this.jobs=jobs;}
    public record Request(@NotBlank @Size(max=24) String operation,@NotNull UUID requestId,Map<String,Object> payload){}
    @PostMapping @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String,Object> submit(@PathVariable UUID sessionId,@Valid @RequestBody Request request){return jobs.submit(sessionId,request.operation(),request.requestId(),request.payload());}
    @GetMapping @PreAuthorize("@permissionService.has('imports.validate')")
    public List<Map<String,Object>> list(@PathVariable UUID sessionId){return jobs.list(sessionId);}
    @GetMapping("/{id}") @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String,Object> get(@PathVariable UUID sessionId,@PathVariable UUID id){return jobs.get(sessionId,id);}
}
