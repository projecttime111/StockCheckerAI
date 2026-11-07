"use client";

import Link from "next/link";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { FormEvent, useEffect, useState } from "react";
import { AccountFrame } from "@/components/AccountFrame";
import { BusinessMode, BusinessModeSelector } from "@/components/BusinessModeSelector";
import { Field, PrimaryButton, TextInput } from "@/components/FormControls";
import { LoadingState } from "@/components/LoadingState";
import { BusinessOrganizationPicker } from "@/components/workspace/BusinessOrganizationPicker";
import { api, clearSession, setSession, CLERK_ENABLED, getToken, signOutSession } from "@/lib/api";
import { useSession } from "@/lib/session";

type AuthResponse = { accessToken: string | null; role: string };

export default function OnboardingPage() {
  const queryClient = useQueryClient();
  const session = useSession("workspace");
  const signedIn = !!getToken();
  useEffect(() => {
    if (!signedIn) window.location.replace(`${CLERK_ENABLED ? "/sign-in" : "/login"}?next=%2Fonboarding`);
  }, [signedIn]);
  const [form, setForm] = useState({ businessName: "", businessMode: "RETAIL", currency: "INR", gstEnabled: true });
  const createWorkspace = useMutation({
    mutationFn: () => api<AuthResponse>(CLERK_ENABLED ? "/api/auth/clerk/workspace" : "/api/tenants", { method: "POST", body: JSON.stringify({ ...form, businessName: form.businessName.trim() }) }),
    onSuccess: data => {
      queryClient.clear();
      if (!CLERK_ENABLED && data.accessToken) { clearSession(); setSession(data.accessToken, data.role, []); }
      window.location.assign("/dashboard");
    }
  });

  if (!signedIn || session.isLoading) return <AccountFrame title="Getting your business ready"><LoadingState title={signedIn ? "Checking your account…" : "Opening sign-in…"} description="We’re preparing your workspace setup." /></AccountFrame>;
  if (session.error || !session.data) return <AccountFrame title="Let’s reconnect your account">
    <div role="alert" className="rounded-xl border border-coral/20 bg-coral/5 p-4 text-sm">{session.error?.message ?? "We couldn’t load your account. Please try again."}</div>
    <PrimaryButton loading={session.isFetching} disabled={session.isFetching} onClick={() => void session.refetch()}>Try again</PrimaryButton>
    <button type="button" className="ml-4 text-sm font-medium text-moss underline" onClick={() => void signOutSession()}>Sign out</button>
  </AccountFrame>;

  if (CLERK_ENABLED && (!session.data.clerkOrganizationId || session.data.tenantId || session.data.accessState !== "ORGANIZATION_UNLINKED")) return <AccountFrame title="Choose your business" description="Create a business organization or select one you already belong to. Your inventory and team stay together in its workspace.">
    {session.data.accessState === "MEMBERSHIP_REQUIRED" ? <p role="alert" className="rounded-xl border border-amber/40 bg-amber/10 p-4 text-sm">Your organization already has a workspace. Ask its owner to invite your email from the Team page, or select a different business below.</p> : null}
    {session.data.tenantId ? <div className="rounded-xl border border-moss/20 bg-mint p-4"><p className="text-sm">Your selected business is ready to use.</p><Link className="mt-3 inline-flex rounded-lg bg-moss px-4 py-2 text-sm font-semibold text-white" href="/dashboard">Open workspace</Link></div> : null}
    <BusinessOrganizationPicker />
    <Link className="inline-block text-sm font-medium text-moss underline" href="/workspace/select">Back to workspaces</Link>
  </AccountFrame>;

  return <AccountFrame title="Set up your business" description="A few details, and your workspace is ready for products, stock and your first import.">
    <ol aria-label="Account setup progress" className="mb-6 grid grid-cols-3 gap-2 text-xs font-medium">
      <li className="rounded-lg bg-mint px-3 py-2 text-moss">1. Account ready</li>
      <li className="rounded-lg bg-mint px-3 py-2 text-moss">2. Business selected</li>
      <li aria-current="step" className="rounded-lg bg-moss px-3 py-2 text-white">3. Workspace details</li>
    </ol>
    <form className="space-y-5" onSubmit={(event: FormEvent) => { event.preventDefault(); if (!createWorkspace.isPending) createWorkspace.mutate(); }}>
      <fieldset className="space-y-5 disabled:opacity-60" disabled={createWorkspace.isPending}>
        <Field label="Business name"><TextInput value={form.businessName} onChange={event => setForm({ ...form, businessName: event.target.value })} maxLength={160} autoComplete="organization" required /></Field>
        <div><div className="mb-2 text-sm font-medium text-ink/70">Business mode</div><BusinessModeSelector value={form.businessMode} onChange={(value: BusinessMode) => setForm({ ...form, businessMode: value })} /></div>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Currency"><TextInput pattern="[A-Z]{3}" maxLength={3} title="Three-letter currency code" value={form.currency} onChange={event => setForm({ ...form, currency: event.target.value.toUpperCase() })} required /></Field>
          <label className="flex items-center gap-2 pt-7 text-sm"><input type="checkbox" checked={form.gstEnabled} onChange={event => setForm({ ...form, gstEnabled: event.target.checked })} /> GST enabled</label>
        </div>
      </fieldset>
      {createWorkspace.error ? <div role="alert" className="space-y-2 rounded-xl border border-coral/20 bg-coral/5 p-4 text-sm"><p>{createWorkspace.error.message}</p><button type="button" className="font-medium text-moss underline" onClick={() => void session.refetch()}>Check workspace status</button></div> : null}
      <PrimaryButton type="submit" loading={createWorkspace.isPending} disabled={createWorkspace.isPending || !form.businessName.trim()}>{createWorkspace.isPending ? "Creating your workspace…" : "Create workspace"}</PrimaryButton>
      <Link className="ml-4 inline-block text-sm font-medium text-moss underline" href="/workspace/select">Back to workspaces</Link>
    </form>
  </AccountFrame>;
}
