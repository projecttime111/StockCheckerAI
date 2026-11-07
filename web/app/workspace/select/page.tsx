"use client";

import { OrganizationList, useClerk } from "@clerk/nextjs";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useEffect } from "react";
import { AccountFrame } from "@/components/AccountFrame";
import { api, CLERK_ENABLED, getToken, loginDestination, setSession, signOutSession } from "@/lib/api";
import { useSession, type WorkspaceMembership } from "@/lib/session";

type AuthResponse = { accessToken: string | null; role: string | null };
export default function WorkspaceSelectPage() {
  const session = useSession("auto");
  const client = useQueryClient();
  useEffect(() => { if (!getToken()) window.location.replace("/login?next=%2Fworkspace%2Fselect"); }, []);
  const platform = useMutation({ mutationFn: () => api<AuthResponse>("/api/auth/platform", { method: "POST" }), onSuccess: data => {
    if (data.accessToken) setSession(data.accessToken, data.role ?? undefined, []);
    client.clear(); window.location.assign("/admin");
  }});
  return <AccountFrame title="Choose your workspace" description="Each business has its own inventory, imports, team, and reports.">
    {session.isLoading ? <p role="status">Loading your workspaces…</p> : null}
    {session.error ? <div role="alert"><p>{session.error.message}</p><button className="mt-3 underline" onClick={() => session.refetch()}>Retry</button>{CLERK_ENABLED ? <Link className="ml-4 underline" href="/account/link">Link existing account</Link> : null}</div> : null}
    {CLERK_ENABLED && session.error ? <ClerkWorkspaces workspaces={[]} /> : null}
    {session.data ? <>
      {session.data.platformRole ? <div className="rounded border border-moss/25 bg-mint p-4"><h2 className="font-semibold">StockPilot platform administration</h2><p className="mt-1 text-sm">Manage client accounts and monitor the service.</p><button className="mt-3 rounded bg-moss px-4 py-2 text-white disabled:opacity-50" disabled={platform.isPending} onClick={() => platform.mutate()}>{platform.isPending ? "Opening…" : "Open platform admin"}</button>{platform.error ? <p role="alert" className="mt-2 text-coral">{platform.error.message}</p> : null}</div> : null}
      {session.data.accessState === "MEMBERSHIP_REQUIRED" ? <p role="alert" className="rounded border border-amber/40 bg-amber/10 p-4">You belong to this organization, but have not been given a StockPilot workspace role. Ask its owner to invite this email from the Team page.</p> : null}
      {CLERK_ENABLED ? <ClerkWorkspaces workspaces={session.data.workspaces} /> : <LocalWorkspaces workspaces={session.data.workspaces} />}
      <div className="flex flex-wrap gap-4 border-t pt-4"><Link className="underline" href="/account/profile">My account</Link><button className="underline" onClick={() => void signOutSession()}>Sign out</button></div>
    </> : null}
  </AccountFrame>;
}
function LocalWorkspaces({ workspaces }: { workspaces: WorkspaceMembership[] }) {
  const client = useQueryClient();
  const select = useMutation({ mutationFn: (id: string) => api<AuthResponse>(`/api/auth/workspaces/${id}/switch`, { method: "POST" }), onSuccess: data => {
    if (!data.accessToken) throw new Error("Workspace session is missing. Sign in again.");
    setSession(data.accessToken, data.role ?? undefined, []); client.clear(); window.location.assign(loginDestination(data.role ?? "", ""));
  }});
  return <div className="space-y-3">{workspaces.length ? workspaces.map(workspace => <button key={workspace.tenantId} disabled={select.isPending} className="block w-full rounded border p-4 text-left hover:border-moss disabled:opacity-50" onClick={() => select.mutate(workspace.tenantId)}><span className="font-semibold">{workspace.name}</span><span className="mt-1 block text-xs text-ink/60">{workspace.role.replaceAll("_", " ")} · {workspace.businessMode}</span></button>) : <p>No active business workspaces are linked to this account.</p>}{select.error ? <p role="alert" className="text-coral">{select.error.message}</p> : null}<Link className="inline-block underline" href="/onboarding">Create a business workspace</Link></div>;
}
function ClerkWorkspaces({ workspaces }: { workspaces: WorkspaceMembership[] }) {
  const { setActive } = useClerk();
  const select = useMutation({ mutationFn: async (workspace: WorkspaceMembership) => { if (!workspace.clerkOrganizationId) throw new Error("Link this business to a Clerk organization first."); await setActive({ organization: workspace.clerkOrganizationId }); window.location.assign("/auth/complete"); } });
  return <div className="space-y-4"><div className="space-y-2">{workspaces.map(workspace => workspace.clerkOrganizationId ? <button key={workspace.tenantId} className="block w-full rounded border p-3 text-left disabled:opacity-50" disabled={select.isPending} onClick={() => select.mutate(workspace)}>{workspace.name}<span className="ml-3 text-xs text-ink/60">{workspace.role.replaceAll("_", " ")}</span></button> : <div className="rounded border p-3" key={workspace.tenantId}><span>{workspace.name}</span>{workspace.role === "OWNER" ? <Link className="ml-4 underline" href="/account/link?organization=1">Connect existing business</Link> : <p className="mt-1 text-sm text-ink/60">Ask the owner to connect this workspace to an organization.</p>}</div>)}</div>{select.error ? <p role="alert" className="text-coral">{select.error.message}</p> : null}<h2 className="font-semibold">Organizations</h2><OrganizationList hidePersonal afterSelectOrganizationUrl="/auth/complete" afterCreateOrganizationUrl="/onboarding" /><Link className="inline-block underline" href="/account/link?organization=1">Connect an existing StockPilot business</Link></div>;
}
