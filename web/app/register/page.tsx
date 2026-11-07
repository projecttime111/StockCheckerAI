"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { FormEvent, useEffect, useState } from "react";
import { api, clearSession, CLERK_ENABLED } from "@/lib/api";
import { BusinessMode, BusinessModeSelector } from "@/components/BusinessModeSelector";
import { Field, PrimaryButton, TextInput } from "@/components/FormControls";
import { GoogleSignInButton } from "@/components/GoogleSignInButton";
import { AccountFrame } from "@/components/AccountFrame";
import { LoadingState } from "@/components/LoadingState";

type AuthResponse = { accessToken?: string; role?: string; emailVerificationRequired?: boolean };

export default function RegisterPage() {
  useEffect(() => { if (CLERK_ENABLED) window.location.replace("/sign-up" + window.location.search); }, []);
  const queryClient = useQueryClient();
  const [form, setForm] = useState({
    businessName: "",
    businessMode: "RETAIL",
    email: "",
    fullName: "",
    password: ""
  });
  const register = useMutation({
    mutationFn: () => api<AuthResponse>("/api/auth/register", { method: "POST", body: JSON.stringify(form) }),
    onSuccess: async (data) => {
      clearSession();
      queryClient.clear();
      if (data.emailVerificationRequired || !data.accessToken) {
        window.location.assign(`/check-email?email=${encodeURIComponent(form.email)}`);
        return;
      }
      window.location.assign(`/login?registered=true&email=${encodeURIComponent(form.email.trim())}`);
    }
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    register.mutate();
  }

  if (CLERK_ENABLED) return <AccountFrame title="Create your StockPilot account"><LoadingState title="Opening secure registration…" description="Create your login, then set up your business workspace." /><Link className="text-sm font-medium text-moss underline" href="/sign-up">Continue to registration</Link></AccountFrame>;
  return (
    <main className="flex min-h-screen items-center justify-center bg-[#eef7f0] px-5 py-10">
      <form onSubmit={submit} className="w-full max-w-xl rounded border border-ink/10 bg-white p-6 shadow-soft">
        <h1 className="text-2xl font-semibold tracking-normal">Create StockPilot Workspace</h1>
        <p className="mt-1 text-sm text-ink/55">Set up an AI inventory intelligence layer for Tally, Excel, or ERP exports. Accounting and compliance stay in your system of record.</p>
        <div className="mt-6">
          <GoogleSignInButton label="Sign up with Google" />
          <p className="mt-2 text-xs text-ink/50">Google sign-in creates your account first, then takes you to workspace setup.</p>
        </div>
        <div className="mt-5 flex items-center gap-3 text-xs uppercase tracking-normal text-ink/40">
          <span className="h-px flex-1 bg-ink/10" />
          <span>Email workspace setup</span>
          <span className="h-px flex-1 bg-ink/10" />
        </div>
        <div className="mt-6 grid gap-4 sm:grid-cols-2">
          <Field label="Business name">
            <TextInput value={form.businessName} onChange={(e) => setForm({ ...form, businessName: e.target.value })} required />
          </Field>
          <div className="sm:col-span-2">
            <div className="mb-2 text-sm font-medium text-ink/70">Tenant mode</div>
            <BusinessModeSelector value={form.businessMode} onChange={(value: BusinessMode) => setForm({ ...form, businessMode: value })} />
          </div>
          <Field label="Owner name">
            <TextInput value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} required />
          </Field>
          <Field label="Email">
            <TextInput type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} required />
          </Field>
          <div className="sm:col-span-2">
            <Field label="Password">
              <TextInput type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} required minLength={8} />
            </Field>
          </div>
        </div>
        {register.error ? <p className="mt-4 text-sm text-coral">{register.error.message}</p> : null}
        <div className="mt-6 flex flex-wrap items-center justify-between gap-3">
          <Link className="text-sm font-medium text-moss" href="/login">I already have an account</Link>
          <PrimaryButton disabled={register.isPending}>{register.isPending ? "Creating..." : "Create workspace"}</PrimaryButton>
        </div>
      </form>
    </main>
  );
}
