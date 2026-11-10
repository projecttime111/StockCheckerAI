package com.stockpilot.ai.web;

import com.stockpilot.ai.service.SourceDocumentRevisionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/import-sessions/{sessionId}/document-revisions")
public class SourceDocumentRevisionController {
    private final SourceDocumentRevisionService service;
    public SourceDocumentRevisionController(SourceDocumentRevisionService service) { this.service=service; }
    @GetMapping @PreAuthorize("@permissionService.has('imports.validate')")
    public Map<String,Object> list(@PathVariable UUID sessionId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="true") boolean changedOnly) {return service.list(sessionId,page,size,changedOnly);}
    public record Review(@NotBlank String action,@NotBlank @Size(max=500) String reason,
        @NotBlank @Size(max=64) String expectedSourceHash,@NotBlank @Size(max=64) String expectedDestinationHash,@NotBlank String confirmation) { }
    @PostMapping("/{voucherId}/review") @PreAuthorize("@permissionService.has('imports.commit')")
    public Map<String,Object> review(@PathVariable UUID sessionId,@PathVariable UUID voucherId,@Valid @RequestBody Review request) {
        return service.review(sessionId,voucherId,request.action(),request.reason(),request.expectedSourceHash(),request.expectedDestinationHash(),request.confirmation());
    }
}
