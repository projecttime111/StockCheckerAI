package com.stockpilot.ai.web;

import com.stockpilot.ai.service.WorkspaceInvitationService;
import com.stockpilot.ai.service.WorkspaceTeamService;
import com.stockpilot.ai.web.dto.ApiDtos;
import com.stockpilot.ai.web.dto.WorkspaceDtos;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceTeamController {
    private final WorkspaceTeamService team;
    private final WorkspaceInvitationService invitations;
    public WorkspaceTeamController(WorkspaceTeamService team, WorkspaceInvitationService invitations) { this.team = team; this.invitations = invitations; }
    @GetMapping("/team/members")
    @PreAuthorize("@permissionService.has('users.view')")
    public Page<WorkspaceDtos.MemberResponse> members(@RequestParam(defaultValue = "") String query,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) { return team.members(query, page, size); }
    @GetMapping("/team/roles")
    @PreAuthorize("@permissionService.has('users.view')")
    public Object roles() { return WorkspaceInvitationService.ASSIGNABLE; }
    @PatchMapping("/team/members/{userId}")
    @PreAuthorize("@permissionService.has('users.manage')")
    public WorkspaceDtos.MemberResponse role(@PathVariable UUID userId, @Valid @RequestBody WorkspaceDtos.RoleRequest request) { return team.changeRole(userId, request.role()); }
    @DeleteMapping("/team/members/{userId}")
    @PreAuthorize("@permissionService.has('users.manage')")
    public Map<String,String> remove(@PathVariable UUID userId) { team.remove(userId); return Map.of("status", "REMOVED"); }
    @GetMapping("/team/invitations")
    @PreAuthorize("@permissionService.has('users.view')")
    public Page<WorkspaceDtos.InvitationResponse> invitations(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) { return invitations.list(page, size); }
    @PostMapping("/team/invitations")
    @PreAuthorize("@permissionService.has('users.manage')")
    public WorkspaceDtos.InvitationResponse invite(@Valid @RequestBody WorkspaceDtos.InvitationRequest request) { return invitations.invite(request); }
    @DeleteMapping("/team/invitations/{id}")
    @PreAuthorize("@permissionService.has('users.manage')")
    public Map<String,String> revoke(@PathVariable UUID id) { invitations.revoke(id); return Map.of("status", "REVOKED"); }
    @PostMapping("/invitations/accept")
    @PreAuthorize("isAuthenticated()")
    public ApiDtos.AuthResponse accept(@Valid @RequestBody WorkspaceDtos.TokenRequest request) { return invitations.accept(request.token()); }
    @GetMapping("/account")
    @PreAuthorize("@permissionService.has('billing.view')")
    public Map<String,Object> account() { return team.account(); }
}
