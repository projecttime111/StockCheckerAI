"use client";

import { OrganizationList } from "@clerk/nextjs";
import Link from "next/link";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, BarChart3 } from "lucide-react";
import { FormEvent, useEffect, useState } from "react";
import { BusinessMode, BusinessModeSelector } from "@/components/BusinessModeSelector";
import { Field, PrimaryButton, TextInput } from "@/components/FormControls";
import { api, clearSession, setSession, CLERK_ENABLED, getToken, signOutSession } from "@/lib/api";
import { useSession } from "@/lib/session";

type AuthResponse = { accessToken: string | null; role: string };

export default function OnboardingPage() {
  const queryClient = useQueryClient();
  const session = useSession("workspace");
  useEffect(() => { if (!getToken()) window.location.replace("/login?next=%2Fonboarding"); }, []);
  const [form, setForm] = useState({ businessName: "", businessMode: "RETAIL", currency: "INR", gstEnabled: true });
  const [deleteConfirmation, setDeleteConfirmation] = useState("");
  const createWorkspace = useMutation({
    mutationFn: () => api<AuthResponse>(CLERK_ENABLED ? "/api/auth/clerk/workspace" : "/api/tenants", { method: "POST", body: JSON.stringify(form) }),
    onSuccess: (data) => {
      queryClient.clear();
      if (!CLERK_ENABLED && data.accessToken) { clearSession(); setSession(data.accessToken, data.role, []); }
      window.location.assign("/dashboard");
    }
  });
  const deleteAccount = useMutation({
    mutationFn: () => api("/api/account", { method: "DELETE", body: JSON.stringify({ confirmation: deleteConfirmation }) }),
    onSuccess: () => {
      queryClient.clear();
      void signOutSession();
    }
  });

  if (CLERK_ENABLED && (session.isLoading || !session.data || !session.data.clerkOrganizationId || session.data.tenantId)) return <main className="min-h-screen bg-[#eef7f0] p-8"><section className="mx-auto max-w-2xl space-y-4 rounded border bg-white p-6"><h1 className="text-2xl font-semibold">Choose a business organization</h1>{session.isLoading ? <p role="status">Checking your account…</p> : session.error ? <div role="alert"><p>{session.error.message}</p><Link className="underline" href="/account/link">Link existing account</Link></div> : <><p>Select or create an organization for the business you want to set up.</p><OrganizationList hidePersonal afterSelectOrganizationUrl="/auth/complete" afterCreateOrganizationUrl="/onboarding" />{session.data?.tenantId ? <Link className="block underline" href="/dashboard">Open current workspace</Link> : null}<Link className="block underline" href="/account/link?organization=1">Connect an existing StockPilot business</Link></>}<Link className="block underline" href="/workspace/select">Back to workspaces</Link></section></main>;

  return (
    <main className="min-h-screen bg-[#eef7f0] px-5 py-10">
      <div className="mx-auto max-w-2xl space-y-5">
        <section className="rounded border border-ink/10 bg-white p-6 shadow-soft">
          <div className="mb-5 flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded bg-moss text-white"><BarChart3 /></div>
            <div><h1 className="text-2xl font-semibold">Create a new workspace</h1><p className="text-sm text-ink/55">Set up your business workspace to continue.</p></div>
          </div>
          {CLERK_ENABLED ? <p className="mb-5 rounded bg-mint p-3 text-sm">Already have a StockPilot business? <Link className="font-semibold underline" href="/account/link?organization=1">Connect it here</Link> to preserve your inventory.</p> : null}
          <form className="space-y-4" onSubmit={(event: FormEvent) => { event.preventDefault(); createWorkspace.mutate(); }}>
            <Field label="Business name"><TextInput value={form.businessName} onChange={(event) => setForm({ ...form, businessName: event.target.value })} required /></Field>
            <div><div className="mb-2 text-sm font-medium text-ink/70">Business mode</div><BusinessModeSelector value={form.businessMode} onChange={(value: BusinessMode) => setForm({ ...form, businessMode: value })} /></div>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Currency"><TextInput pattern="[A-Z]{3}" maxLength={3} title="Three-letter currency code" value={form.currency} onChange={(event) => setForm({ ...form, currency: event.target.value.toUpperCase() })} required /></Field>
              <label className="flex items-center gap-2 pt-7 text-sm"><input type="checkbox" checked={form.gstEnabled} onChange={(event) => setForm({ ...form, gstEnabled: event.target.checked })} /> GST enabled</label>
            </div>
            {createWorkspace.error ? <p className="text-sm text-coral">{createWorkspace.error.message}</p> : null}
            <PrimaryButton disabled={createWorkspace.isPending}>{createWorkspace.isPending ? "Creating..." : "Create workspace"}</PrimaryButton>
          </form>
        </section>

        <section className="border border-coral/35 bg-white p-5 shadow-sm">
          <div className="flex gap-3"><AlertTriangle className="mt-0.5 text-coral" size={20} /><div><h2 className="font-semibold">Delete my account instead</h2><p className="mt-1 text-sm text-ink/55">Deactivate and anonymize this account instead of creating another workspace.</p></div></div>
          <div className="mt-4 space-y-3">
            <Field label="Type DELETE MY ACCOUNT to confirm"><TextInput value={deleteConfirmation} onChange={(event) => setDeleteConfirmation(event.target.value)} /></Field>
            {deleteAccount.error ? <p className="text-sm text-coral">{deleteAccount.error.message}</p> : null}
            <button className="focus-ring h-10 rounded bg-coral px-4 text-sm font-semibold text-white disabled:opacity-50" disabled={deleteConfirmation !== "DELETE MY ACCOUNT" || deleteAccount.isPending} onClick={() => deleteAccount.mutate()}>
              {deleteAccount.isPending ? "Deleting..." : "Delete my account"}
            </button>
          </div>
        </section>
      </div>
    </main>
  );
}
