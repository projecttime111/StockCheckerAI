"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { Field, PrimaryButton, SecondaryButton, SelectInput, TextInput } from "@/components/FormControls";
import { Panel } from "@/components/Panel";
import { MutationFeedback, QueryFeedback } from "@/components/QueryFeedback";
import { PlatformEmpty, PlatformPager, StatusBadge, formatTimestamp, tableClass } from "@/components/platform/PlatformUI";
import { DEMO_MODE } from "@/lib/api";
import { Session, useSession } from "@/lib/session";
import { TeamInvitation, TeamMember, roleLabel, workspaceApi } from "@/lib/workspace";

export default function TeamPage() {
  return DEMO_MODE ? <AppShell title="Team"><Panel title="Team management"><p className="text-sm leading-6 text-ink/60">Team management and invitations require a connected backend. They are not available in the local demo.</p></Panel></AppShell> : <LiveTeamPage />;
}

function LiveTeamPage() {
  const session = useSession("workspace");
  return <AppShell title="Team"><QueryFeedback queries={[session]} />{!session.error && session.data?.permissions.includes("users.view") && session.data.tenantId ? <TeamWorkspace key={session.data.tenantId} session={session.data} /> : session.data ? <p role="alert" className="rounded border bg-white p-5 text-sm">Your role does not have access to team management.</p> : null}</AppShell>;
}

type MemberAction = { kind: "role" | "remove"; member: TeamMember };
function TeamWorkspace({ session }: { session: Session }) {
  const client = useQueryClient();
  const canManage = session.permissions.includes("users.manage");
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [memberPage, setMemberPage] = useState(0);
  const [invitationPage, setInvitationPage] = useState(0);
  const [email, setEmail] = useState("");
  const [inviteRole, setInviteRole] = useState("STAFF");
  const [action, setAction] = useState<MemberAction | null>(null);
  const [nextRole, setNextRole] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [revoke, setRevoke] = useState<TeamInvitation | null>(null);
  const members = useQuery({ queryKey: ["team", session.tenantId, "members", search, memberPage], queryFn: () => workspaceApi.members(memberPage, search) });
  const roles = useQuery({ queryKey: ["team", session.tenantId, "roles"], queryFn: workspaceApi.roles });
  const invitations = useQuery({ queryKey: ["team", session.tenantId, "invitations", invitationPage], queryFn: () => workspaceApi.invitations(invitationPage) });
  const refresh = () => client.invalidateQueries({ queryKey: ["team", session.tenantId] });
  const invite = useMutation({ mutationFn: () => workspaceApi.invite(email, inviteRole), onSuccess: async () => { setEmail(""); setInviteRole("STAFF"); setInvitationPage(0); await refresh(); } });
  const update = useMutation({ mutationFn: async ({ kind, member, role }: MemberAction & { role?: string }) => { if (kind === "remove") await workspaceApi.removeMember(member.userId); else await workspaceApi.changeRole(member.userId, role!); }, onSuccess: async () => { setAction(null); setConfirmation(""); setMemberPage(0); await refresh(); } });
  const revokeMutation = useMutation({ mutationFn: (id: string) => workspaceApi.revokeInvitation(id), onSuccess: async () => { setRevoke(null); await refresh(); } });
  const busy = update.isPending || revokeMutation.isPending;
  function submitInvite(event: FormEvent) { event.preventDefault(); if (email.trim() && roles.data?.includes(inviteRole) && !invite.isPending) invite.mutate(); }
  function submitAction(event: FormEvent) {
    event.preventDefault();
    if (!action || busy) return;
    if (action.kind === "remove" && confirmation !== action.member.email) return;
    if (action.kind === "role" && (!roles.data?.includes(nextRole) || nextRole === action.member.role)) return;
    update.mutate({ ...action, role: nextRole });
  }
  function review(kind: "role" | "remove", member: TeamMember) { update.reset(); setAction({ kind, member }); setNextRole(member.role); setConfirmation(""); }
  return <div className="space-y-6">
    <p className="max-w-3xl text-sm leading-6 text-ink/60">Manage access to this business workspace. Roles determine which inventory, sales, purchasing and reporting actions each teammate can use.</p>
    {canManage ? <Panel title="Invite a teammate"><form onSubmit={submitInvite} className="grid items-end gap-3 md:grid-cols-[2fr_1fr_auto]"><Field label="Teammate email"><TextInput type="email" required maxLength={190} autoComplete="email" value={email} onChange={event => { setEmail(event.target.value); invite.reset(); }} disabled={invite.isPending} placeholder="name@company.com" /></Field><Field label="Invitation role"><SelectInput value={inviteRole} onChange={event => { setInviteRole(event.target.value); invite.reset(); }} disabled={invite.isPending || !roles.data?.length}>{(roles.data ?? []).map(role => <option key={role} value={role}>{roleLabel(role)}</option>)}</SelectInput></Field><PrimaryButton disabled={invite.isPending || !roles.data?.includes(inviteRole)}>{invite.isPending ? "Sending…" : "Send invitation"}</PrimaryButton></form><div className="mt-3"><QueryFeedback queries={[roles]} /><MutationFeedback mutation={invite} success={invite.data?.message ?? "Invitation sent. The teammate can join using the email link."} /></div><p className="mt-3 text-xs leading-5 text-ink/50">Invitations are valid for up to seven days; check the expiry below. Owner and platform roles cannot be assigned here.</p></Panel> : null}
    <Panel title="Workspace members"><form onSubmit={event => { event.preventDefault(); setSearch(query.trim()); setMemberPage(0); }} className="mb-5 flex flex-wrap items-end gap-3"><div className="min-w-56 flex-1"><Field label="Search team"><TextInput maxLength={160} value={query} onChange={event => setQuery(event.target.value)} placeholder="Name or email" /></Field></div><PrimaryButton type="submit">Search</PrimaryButton><SecondaryButton type="button" onClick={() => { setQuery(""); setSearch(""); setMemberPage(0); }}>Reset</SecondaryButton></form><QueryFeedback queries={[members]} /><MutationFeedback mutation={update} success="Team access updated." />
      {members.data && !members.error ? <>{members.data.content.length ? <div className="overflow-x-auto"><table className={tableClass}><thead><tr><th>Teammate</th><th>Role</th><th>Status</th><th>Joined</th>{canManage ? <th>Access</th> : null}</tr></thead><tbody>{members.data.content.map(member => { const self = member.userId === session.userId; const protectedRole = member.role === "OWNER" || member.role.startsWith("PLATFORM_"); return <tr key={member.userId}><td><p className="font-medium">{member.fullName || member.email}{self ? <span className="ml-2 text-xs text-moss">You</span> : null}</p><p className="mt-1 text-xs text-ink/50">{member.email}</p></td><td>{roleLabel(member.role)}</td><td><StatusBadge value={member.active ? "ACTIVE" : "INACTIVE"} /></td><td className="text-xs text-ink/50">{formatTimestamp(member.joinedAt)}</td>{canManage ? <td>{self || protectedRole ? <span className="text-xs text-ink/45">{self ? "Your account" : "Protected role"}</span> : <div className="flex flex-wrap gap-3"><button className="focus-ring rounded text-xs font-semibold text-moss underline disabled:opacity-40" disabled={busy || !roles.data?.length} aria-label={`Change role for ${member.email}`} onClick={() => review("role", member)}>Change role</button><button className="focus-ring rounded text-xs font-semibold text-[#ad4534] underline disabled:opacity-40" disabled={busy} aria-label={`Remove ${member.email}`} onClick={() => review("remove", member)}>Remove</button></div>}</td> : null}</tr>; })}</tbody></table></div> : <PlatformEmpty title="No teammates found">Try another name or email.</PlatformEmpty>}<PlatformPager data={members.data} page={memberPage} onChange={setMemberPage} busy={members.isFetching} /></> : null}
      {action ? <form onSubmit={submitAction} aria-label="Confirm team access change" className="mt-6 space-y-4 rounded-lg border border-ink/15 bg-slate-50 p-5"><h3 className="font-semibold">{action.kind === "remove" ? "Remove workspace access" : "Change workspace role"}</h3><p className="text-sm text-ink/65">{action.member.fullName || action.member.email} · {action.member.email}</p><fieldset disabled={update.isPending} className="space-y-4">{action.kind === "role" ? <><p className="text-sm text-ink/60">Current role: {roleLabel(action.member.role)}</p><Field label="New workspace role"><SelectInput value={nextRole} onChange={event => setNextRole(event.target.value)}>{(roles.data ?? []).map(role => <option key={role} value={role}>{roleLabel(role)}</option>)}</SelectInput></Field><p className="text-xs text-ink/50">This changes the teammate’s permissions in this workspace.</p></> : <><p className="text-sm leading-6 text-ink/60">This removes access to this workspace. Their account and memberships in other workspaces remain.</p><Field label={`Type ${action.member.email} to confirm removal`}><TextInput required autoComplete="off" value={confirmation} onChange={event => setConfirmation(event.target.value)} /></Field></>}<div className="flex flex-wrap gap-2"><PrimaryButton type="submit" disabled={update.isPending || (action.kind === "remove" ? confirmation !== action.member.email : nextRole === action.member.role || !roles.data?.includes(nextRole))}>{update.isPending ? "Updating…" : action.kind === "remove" ? "Confirm removal" : "Confirm role change"}</PrimaryButton><SecondaryButton type="button" disabled={update.isPending} onClick={() => { setAction(null); update.reset(); }}>Cancel</SecondaryButton></div></fieldset></form> : null}
    </Panel>
    <Panel title="Workspace invitations"><QueryFeedback queries={[invitations]} /><MutationFeedback mutation={revokeMutation} success="Invitation revoked." />{invitations.data && !invitations.error ? <>{invitations.data.content.length ? <div className="overflow-x-auto"><table className={tableClass}><thead><tr><th>Email</th><th>Role</th><th>Status</th><th>Expires</th>{canManage ? <th>Actions</th> : null}</tr></thead><tbody>{invitations.data.content.map(invitation => <tr key={invitation.id}><td>{invitation.email}</td><td>{roleLabel(invitation.role)}</td><td><StatusBadge value={invitation.status} /></td><td className="text-xs text-ink/50">{formatTimestamp(invitation.expiresAt)}</td>{canManage ? <td>{invitation.status === "PENDING" ? <button className="focus-ring rounded text-xs font-semibold text-[#ad4534] underline disabled:opacity-40" disabled={busy} aria-label={`Revoke invitation for ${invitation.email}`} onClick={() => { setRevoke(invitation); revokeMutation.reset(); }}>Revoke</button> : <span className="text-ink/35">—</span>}</td> : null}</tr>)}</tbody></table></div> : <PlatformEmpty title="No invitations yet">Invited teammates will appear here with their current invitation status.</PlatformEmpty>}<PlatformPager data={invitations.data} page={invitationPage} onChange={setInvitationPage} busy={invitations.isFetching} /></> : null}
      {revoke ? <div className="mt-5 rounded-lg border border-coral/20 bg-coral/[0.03] p-4"><h3 className="font-semibold">Revoke invitation?</h3><p className="mt-2 text-sm text-ink/60">{revoke.email} will no longer be able to join this workspace using this invitation.</p><div className="mt-4 flex gap-2"><PrimaryButton disabled={revokeMutation.isPending} onClick={() => revokeMutation.mutate(revoke.id)}>{revokeMutation.isPending ? "Revoking…" : "Confirm revocation"}</PrimaryButton><SecondaryButton disabled={revokeMutation.isPending} onClick={() => { setRevoke(null); revokeMutation.reset(); }}>Cancel</SecondaryButton></div></div> : null}
    </Panel>
  </div>;
}
