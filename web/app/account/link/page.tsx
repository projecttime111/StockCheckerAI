"use client";

import { OrganizationList } from "@clerk/nextjs";
import { useMutation } from "@tanstack/react-query";
import Link from "next/link";
import { useEffect, useState } from "react";
import { AccountFrame } from "@/components/AccountFrame";
import { Field, PrimaryButton, TextInput } from "@/components/FormControls";
import { api, CLERK_ENABLED, getClerkSessionToken } from "@/lib/api";
import type { Session, WorkspaceMembership } from "@/lib/session";

type LocalProof = { accessToken: string; tenantId: string | null; role: string | null };
export default function AccountLinkPage() {
  const [organization, setOrganization] = useState(false);
  const [form, setForm] = useState({ email: "", password: "" });
  const [proof, setProof] = useState<LocalProof | null>(null);
  const [workspaces, setWorkspaces] = useState<WorkspaceMembership[]>([]);
  const [selected, setSelected] = useState("");
  useEffect(() => { setOrganization(new URLSearchParams(window.location.search).get("organization") === "1"); }, []);
  const verify = useMutation({ mutationFn: async () => {
    const result = await api<LocalProof>("/api/auth/login", { method: "POST", body: JSON.stringify(form) });
    const clerkToken = await getClerkSessionToken();
    if (!clerkToken) throw new Error("Sign in with Clerk before linking your existing account.");
    await api("/api/auth/clerk/link", { method: "POST", headers: { Authorization: `Bearer ${result.accessToken}` }, body: JSON.stringify({ clerkToken }) });
    if (!organization) { window.location.assign("/auth/complete"); return; }
    const account = await api<Session>("/api/auth/session", { headers: { Authorization: `Bearer ${result.accessToken}` } });
    const owned = account.workspaces.filter(workspace => workspace.role === "OWNER" && !workspace.clerkOrganizationId);
    if (!owned.length) throw new Error("There are no unlinked, active businesses owned by this account.");
    setProof(result); setForm(previous => ({ ...previous, password: "" })); setWorkspaces(owned); setSelected(owned[0].tenantId);
  }});
  const link = useMutation({ mutationFn: async () => {
    if (!proof || !selected) throw new Error("Verify your existing account and select a business first.");
    const scoped = await api<LocalProof>(`/api/auth/workspaces/${selected}/switch`, { method: "POST", headers: { Authorization: `Bearer ${proof.accessToken}` } });
    const clerkToken = await getClerkSessionToken();
    if (!clerkToken) throw new Error("Your Clerk session expired. Sign in again.");
    await api("/api/auth/clerk/organization/link", { method: "POST", headers: { Authorization: `Bearer ${scoped.accessToken}` }, body: JSON.stringify({ clerkToken }) });
    window.location.assign("/auth/complete");
  }});
  if (!CLERK_ENABLED) return <AccountFrame title="Existing account"><Link className="underline" href="/login">Continue with your existing sign-in</Link></AccountFrame>;
  return <AccountFrame title={organization ? "Connect your existing business" : "Link your existing account"} description={organization ? "Select or create your business organization, then confirm ownership of your StockPilot business. Its inventory and history stay in place." : "Verify your existing StockPilot login to keep access to your current workspaces. Both accounts must use the same verified email."}>
    {organization && !proof ? <div className="space-y-3"><h2 className="font-semibold">1. Select or create your business organization</h2><OrganizationList hidePersonal afterSelectOrganizationUrl="/account/link?organization=1" afterCreateOrganizationUrl="/account/link?organization=1" /></div> : null}
    {!proof ? <form className="space-y-4" onSubmit={event => { event.preventDefault(); verify.mutate(); }}><h2 className="font-semibold">{organization ? "2. Verify the existing business owner" : "Existing StockPilot credentials"}</h2><Field label="Email"><TextInput type="email" required autoComplete="username" value={form.email} onChange={event => setForm({ ...form, email: event.target.value })} /></Field><Field label="StockPilot password"><TextInput type="password" required autoComplete="current-password" value={form.password} onChange={event => setForm({ ...form, password: event.target.value })} /></Field>{verify.error ? <p role="alert" className="text-coral">{verify.error.message}</p> : null}<PrimaryButton disabled={verify.isPending}>{verify.isPending ? "Verifying…" : "Verify and link account"}</PrimaryButton><Link className="ml-4 text-sm underline" href="/forgot-password">Reset existing StockPilot password</Link></form> : <form className="space-y-4" onSubmit={event => { event.preventDefault(); link.mutate(); }}><Field label="Business to connect"><select className="h-11 w-full rounded border bg-white px-3" value={selected} onChange={event => setSelected(event.target.value)}>{workspaces.map(workspace => <option key={workspace.tenantId} value={workspace.tenantId}>{workspace.name}</option>)}</select></Field><p className="text-sm text-ink/65">You must be an administrator of the selected Clerk organization. An organization can be connected to only one StockPilot business.</p>{link.error ? <p role="alert" className="text-coral">{link.error.message}</p> : null}<PrimaryButton disabled={link.isPending}>{link.isPending ? "Connecting…" : "Connect organization to this business"}</PrimaryButton><button type="button" disabled={link.isPending} className="ml-4 text-sm underline" onClick={() => { setProof(null); link.reset(); }}>Choose another organization</button></form>}
    <Link className="inline-block underline" href="/workspace/select">Back to workspaces</Link>
  </AccountFrame>;
}
