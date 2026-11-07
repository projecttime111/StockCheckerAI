package com.stockpilot.ai.web;

import com.stockpilot.ai.service.WorkspaceInvitationService;
import com.stockpilot.ai.web.dto.ApiDtos;
import com.stockpilot.ai.web.dto.WorkspaceDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/invitations")
public class WorkspaceInvitationController {
    private final WorkspaceInvitationService invitations;
    public WorkspaceInvitationController(WorkspaceInvitationService invitations) { this.invitations = invitations; }
    @PostMapping("/preview")
    public WorkspaceDtos.InvitationPreview preview(@Valid @RequestBody WorkspaceDtos.TokenRequest request) { return invitations.preview(request.token()); }
    @PostMapping("/register")
    public ApiDtos.AuthResponse register(@Valid @RequestBody WorkspaceDtos.InvitationRegistration request) { return invitations.register(request); }
}
