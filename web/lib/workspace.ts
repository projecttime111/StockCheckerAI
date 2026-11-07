import { api, Page } from "@/lib/api";

export type TeamMember = { userId: string; fullName: string; email: string; role: string; active: boolean; joinedAt: string };
export type TeamInvitation = { id: string; email: string; role: string; status: string; expiresAt: string; createdAt: string; deliveryStatus?: string; message?: string };
export type InvitationPreview = { workspaceName: string; email: string; role: string; accountExists: boolean; clerkManaged: boolean };
export type InvitationSession = { accessToken?: string; tenantId?: string; role?: string; email?: string; fullName?: string };
export type WorkspaceAccount = { tenantId: string; name: string; businessMode: string; currency: string; plan: string; subscriptionStatus: string; subscriptionEndsAt: string | null; clerkManaged: boolean; usage: { members: number; products: number; warehouses: number; imports: number } };
export const workspaceApi = {
  members: (page: number, query: string) => api<Page<TeamMember>>(`/api/workspace/team/members?${new URLSearchParams({ page: String(page), size: "20", query })}`),
  roles: () => api<string[]>("/api/workspace/team/roles"),
  invitations: (page: number) => api<Page<TeamInvitation>>(`/api/workspace/team/invitations?page=${page}&size=10`),
  invite: (email: string, role: string) => api<TeamInvitation>("/api/workspace/team/invitations", { method: "POST", body: JSON.stringify({ email: email.trim(), role }) }),
  changeRole: (id: string, role: string) => api<TeamMember>(`/api/workspace/team/members/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify({ role }) }),
  removeMember: (id: string) => api<{ status: string }>(`/api/workspace/team/members/${encodeURIComponent(id)}`, { method: "DELETE" }),
  revokeInvitation: (id: string) => api<{ status: string }>(`/api/workspace/team/invitations/${encodeURIComponent(id)}`, { method: "DELETE" }),
  invitationPreview: (token: string) => api<InvitationPreview>("/api/auth/invitations/preview", { method: "POST", body: JSON.stringify({ token }) }),
  acceptInvitation: (token: string) => api<InvitationSession>("/api/workspace/invitations/accept", { method: "POST", body: JSON.stringify({ token }), headers: { "X-StockPilot-Mode": "auto" } }),
  registerInvitation: (token: string, fullName: string, password: string) => api<InvitationSession>("/api/auth/invitations/register", { method: "POST", body: JSON.stringify({ token, fullName: fullName.trim(), password }) }),
  account: () => api<WorkspaceAccount>("/api/workspace/account")
};

export function roleLabel(role: string) {
  return role.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, letter => letter.toUpperCase());
}
