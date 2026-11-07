"use client";

import { useClerk } from "@clerk/nextjs";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useEffect } from "react";
import { AccountFrame } from "@/components/AccountFrame";
import { PrimaryButton } from "@/components/FormControls";
import { LoadingState } from "@/components/LoadingState";
import { BusinessOrganizationPicker } from "@/components/workspace/BusinessOrganizationPicker";
import { api, CLERK_ENABLED, getToken, loginDestination, setSession, signOutSession } from "@/lib/api";
import { useSession, type WorkspaceMembership } from "@/lib/session";

type AuthResponse = { accessToken: string | null; role: string | null };
export default function WorkspaceSelectPage() {
  const session = useSession("auto");
  const client = useQueryClient();
  const signedIn = !!getToken();
  useEffect(() => {
    if (!signedIn) window.location.replace(`${CLERK_ENABLED ? "/sign-in" : "/login"}?next=%2Fworkspace%2Fselect`);
  }, [signedIn]);
  const platform = useMutation({ mutationFn: () => api<AuthResponse>("/api/auth/platform", { method: "POST" }), onSuccess: data => {
    if (data.accessToken) setSession(data.accessToken, data.role ?? undefined, []);
    client.clear(); window.location.assign("/admin");
  }});
  return <AccountFrame title="Choose your workspace" description="Each business has its own inventory, imports, team, and reports.">
    {!signedIn || session.isLoading ? <LoadingState title={signedIn ? "Loading your workspaces…" : "Opening sign-in…"} description="We’re checking the businesses available to your account." /> : session.error || !session.data ? <div className="space-y-4">
      <p role="alert" className="rounded-xl border border-coral/20 bg-coral/5 p-4 text-sm">{session.error?.message ?? "We couldn’t load your workspaces. Please try again."}</p>
      <PrimaryButton loading={session.isFetching} disabled={session.isFetching} onClick={() => void session.refetch()}>Try again</PrimaryButton>
      <button type="button" className="ml-4 text-sm font-medium text-moss underline" onClick={() => void signOutSession()}>Sign out</button>
    </div> : <>
      {session.data.platformRole ? <div className="rounded-xl border border-moss/25 bg-mint p-4"><h2 className="font-semibold">StockPilot platform administration</h2><p className="mb-3 mt-1 text-sm">Manage client accounts and monitor the service.</p><PrimaryButton loading={platform.isPending} disabled={platform.isPending} onClick={() => platform.mutate()}>{platform.isPending ? "Opening administration…" : "Open platform admin"}</PrimaryButton>{platform.error ? <p role="alert" className="mt-2 text-sm text-coral">{platform.error.message}</p> : null}</div> : null}
      {session.data.accessState === "MEMBERSHIP_REQUIRED" ? <p role="alert" className="rounded-xl border border-amber/40 bg-amber/10 p-4 text-sm">You belong to this organization, but haven’t received a workspace role yet. Ask its owner to invite your email from the Team page.</p> : null}
      {CLERK_ENABLED ? <ClerkWorkspaces workspaces={session.data.workspaces} /> : <LocalWorkspaces workspaces={session.data.workspaces} />}
      <div className="flex flex-wrap gap-4 border-t border-ink/10 pt-4 text-sm font-medium text-moss"><Link className="underline" href="/account/profile">My account</Link><button type="button" className="underline" onClick={() => void signOutSession()}>Sign out</button></div>
    </>}
  </AccountFrame>;
}
function LocalWorkspaces({ workspaces }: { workspaces: WorkspaceMembership[] }) {
  const client = useQueryClient();
  const select = useMutation({ mutationFn: (id: string) => api<AuthResponse>(`/api/auth/workspaces/${id}/switch`, { method: "POST" }), onSuccess: data => {
    if (!data.accessToken) throw new Error("Workspace session is missing. Sign in again.");
    setSession(data.accessToken, data.role ?? undefined, []); client.clear(); window.location.assign(loginDestination(data.role ?? "", ""));
  }});
  return <div className="space-y-3">{workspaces.length ? workspaces.map(workspace => <button key={workspace.tenantId} type="button" disabled={select.isPending} className="focus-ring block w-full rounded-xl border border-ink/15 p-4 text-left transition-colors hover:border-moss hover:bg-mint/50 disabled:opacity-50" onClick={() => select.mutate(workspace.tenantId)}><span className="font-semibold">{workspace.name}</span><span className="mt-1 block text-xs text-ink/60">{workspace.role.replaceAll("_", " ")} · {workspace.businessMode}</span></button>) : <p className="text-sm text-ink/65">Create your first business workspace to get started.</p>}{select.isPending ? <LoadingState compact title="Opening your workspace…" /> : null}{select.error ? <p role="alert" className="text-sm text-coral">{select.error.message}</p> : null}<Link className="inline-block text-sm font-semibold text-moss underline" href="/onboarding">Create a business workspace</Link></div>;
}
function ClerkWorkspaces({ workspaces }: { workspaces: WorkspaceMembership[] }) {
  const { setActive } = useClerk();
  const available = workspaces.filter(workspace => workspace.clerkOrganizationId);
  const select = useMutation({ mutationFn: async (workspace: WorkspaceMembership) => {
    if (!workspace.clerkOrganizationId) throw new Error("This workspace is unavailable. Select another business.");
    let timer: number | undefined;
    try {
      await Promise.race([setActive({ organization: workspace.clerkOrganizationId }), new Promise<never>((_, reject) => {
        timer = window.setTimeout(() => reject(new Error("Workspace selection is taking longer than expected. Reload this page to check your current workspace.")), 30_000);
      })]);
      window.location.assign("/auth/complete");
    } finally { window.clearTimeout(timer); }
  }});
  return <div className="space-y-5">
    {available.length ? <div className="space-y-2"><h2 className="mb-3 font-semibold">Your business workspaces</h2>{available.map(workspace => <button key={workspace.tenantId} type="button" className="focus-ring block w-full rounded-xl border border-ink/15 p-4 text-left transition-colors hover:border-moss hover:bg-mint/50 disabled:opacity-50" disabled={select.isPending} onClick={() => select.mutate(workspace)}><span className="font-semibold">{workspace.name}</span><span className="mt-1 block text-xs text-ink/60">{workspace.role.replaceAll("_", " ")} · {workspace.businessMode}</span></button>)}</div> : <div className="rounded-xl border border-moss/20 bg-mint/50 p-4"><h2 className="font-semibold">Welcome to StockPilot</h2><p className="mt-1 text-sm text-ink/65">Create your business organization below to set up your first workspace. If you’re joining a team, use its invitation or select its organization.</p></div>}
    {select.isPending ? <LoadingState compact title="Opening your workspace…" description="Switching to your selected business." /> : null}
    {select.error ? <div role="alert" className="space-y-2 text-sm"><p className="text-coral">{select.error.message}</p><button type="button" className="font-medium text-moss underline" onClick={() => window.location.reload()}>Reload workspaces</button></div> : null}
    <div className="space-y-3"><h2 className="font-semibold">{available.length ? "Select or create a business organization" : "Your business starts here"}</h2><BusinessOrganizationPicker /></div>
  </div>;
}
