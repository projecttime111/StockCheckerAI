"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { BarChart3 } from "lucide-react";
import Link from "next/link";
import { FormEvent, useEffect, useState } from "react";
import { api, ApiClientError, clearSession, DEMO_MODE, loginDestination, setSession, setSessionPermissions } from "@/lib/api";
import { Field, PrimaryButton, TextInput } from "@/components/FormControls";
import { GoogleSignInButton } from "@/components/GoogleSignInButton";

type AuthResponse = { accessToken: string; tenantId: string | null; role: string | null };
type PermissionResponse = { permissions: string[] };

const demoAccounts = [
  ["Owner", "owner@demo.com"],
  ["Admin", "admin@demo.com"],
  ["Manager", "manager@demo.com"],
  ["Warehouse", "warehouse@demo.com"],
  ["Sales", "sales@demo.com"],
  ["Purchase", "purchase@demo.com"],
  ["Accountant", "accountant@demo.com"],
  ["Viewer", "viewer@demo.com"],
  ["Auditor", "auditor@demo.com"],
  ["Dealer", "ravi@demo.com"]
];

export default function LoginPage() {
  const queryClient = useQueryClient();
  const [email, setEmail] = useState(DEMO_MODE ? "owner@demo.com" : "");
  const [password, setPassword] = useState(DEMO_MODE ? "password123" : "");
  const [resendMessage, setResendMessage] = useState("");
  const [registrationMessage, setRegistrationMessage] = useState("");

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get("changed") === "1") setRegistrationMessage("Your account changed in another tab. Sign in to continue in the correct workspace.");
    if (params.get("expired") === "1") {
      setRegistrationMessage("Your session expired. Please sign in again.");
    }
    if (params.get("registered") !== "true") return;
    const registeredEmail = params.get("email")?.trim() ?? "";
    if (registeredEmail) {
      setEmail(registeredEmail);
    }
    setRegistrationMessage("Account created successfully. Sign in with your new credentials.");
  }, []);

  const login = useMutation({
    mutationFn: () => api<AuthResponse>("/api/auth/login", { method: "POST", body: JSON.stringify({ email, password }) }),
    onSuccess: async (data) => {
      clearSession();
      queryClient.clear();
      setSession(data.accessToken, data.role ?? undefined);
      if (!data.tenantId || !data.role) {
        window.location.assign("/onboarding");
        return;
      }
      try {
        const access = await api<PermissionResponse>("/api/auth/me/permissions");
        setSessionPermissions(access.permissions ?? []);
      } catch {
        setSessionPermissions([]);
      }
      window.location.assign(loginDestination(data.role));
    }
  });
  const resendVerification = useMutation({
    mutationFn: () => api<{ message: string }>("/api/auth/resend-verification", { method: "POST", body: JSON.stringify({ email }) }),
    onSuccess: (data) => setResendMessage(data.message)
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setResendMessage("");
    login.mutate();
  }

  const loginError = login.error instanceof ApiClientError ? login.error : null;

  return (
    <main className="grid min-h-screen bg-[#eef7f0] lg:grid-cols-[1fr_480px]">
      <section className="relative hidden overflow-hidden lg:block">
        <div className="absolute inset-0 bg-[url('https://images.unsplash.com/photo-1553413077-190dd305871c?auto=format&fit=crop&w=1600&q=80')] bg-cover bg-center" />
        <div className="absolute inset-0 bg-ink/45" />
        <div className="relative flex h-full flex-col justify-end p-12 text-white">
          <h1 className="max-w-2xl text-5xl font-semibold tracking-normal">StockPilot AI</h1>
          <p className="mt-4 max-w-xl text-lg text-white/85">Connect Tally or Excel. Predict stockouts. Reduce dead stock. Improve profit.</p>
          <p className="mt-3 max-w-xl text-sm text-white/75">Tally remains your accounting and compliance system. StockPilot AI is the inventory intelligence layer on top.</p>
        </div>
      </section>
      <section className="flex items-center justify-center px-5 py-10">
        <form onSubmit={submit} className="w-full max-w-sm rounded border border-ink/10 bg-white p-6 shadow-soft">
          <div className="mb-6 flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded bg-moss text-white">
              <BarChart3 />
            </div>
            <div>
              <h2 className="text-xl font-semibold">Welcome back</h2>
              <p className="text-sm text-ink/55">{DEMO_MODE ? "Use a demo account or your own account." : "Sign in with your registered account."}</p>
            </div>
          </div>
          <div className="space-y-4">
            <GoogleSignInButton />
            <div className="flex items-center gap-3 text-xs uppercase tracking-normal text-ink/40">
              <span className="h-px flex-1 bg-ink/10" />
              <span>Email login</span>
              <span className="h-px flex-1 bg-ink/10" />
            </div>
            <Field label="Email">
              <TextInput value={email} onChange={(event) => setEmail(event.target.value)} type="email" required />
            </Field>
            <Field label="Password">
              <TextInput value={password} onChange={(event) => setPassword(event.target.value)} type="password" required />
            </Field>
            {registrationMessage ? <p className="rounded border border-moss/25 bg-mint px-3 py-2 text-sm text-moss">{registrationMessage}</p> : null}
            {login.error ? <p className="text-sm text-coral">{login.error.message}</p> : null}
            {loginError?.code === "EMAIL_NOT_VERIFIED" ? (
              <div className="rounded border border-coral/30 bg-coral/5 p-3 text-sm">
                <button
                  type="button"
                  className="font-semibold text-moss"
                  disabled={resendVerification.isPending}
                  onClick={() => resendVerification.mutate()}
                >
                  {resendVerification.isPending ? "Sending..." : "Resend verification email"}
                </button>
                {resendVerification.error ? <p role="alert" className="mt-2 text-coral">{resendVerification.error.message}</p> : null}
                {resendMessage ? <p className="mt-2 text-ink/60">{resendMessage}</p> : null}
              </div>
            ) : null}
            <PrimaryButton disabled={login.isPending} className="w-full">
              {login.isPending ? "Signing in..." : "Sign in"}
            </PrimaryButton>
          </div>
          <p className="mt-3 text-sm">
            <Link className="font-medium text-moss" href="/forgot-password">Forgot password?</Link>
          </p>
          {DEMO_MODE ? (
            <div className="mt-5 border-t border-ink/10 pt-4">
              <p className="mb-2 text-xs font-semibold uppercase tracking-normal text-ink/45">Demo role accounts</p>
              <div className="grid grid-cols-2 gap-2">
                {demoAccounts.map(([label, accountEmail]) => (
                  <button
                    key={accountEmail}
                    type="button"
                    className="rounded border border-ink/10 px-2 py-2 text-left text-xs font-medium text-ink/70 hover:border-moss/40 hover:bg-mint"
                    onClick={() => {
                      setEmail(accountEmail);
                      setPassword("password123");
                    }}
                  >
                    <span className="block text-ink">{label}</span>
                    <span className="block truncate text-ink/45">{accountEmail}</span>
                  </button>
                ))}
              </div>
            </div>
          ) : null}
          <p className="mt-5 text-sm text-ink/60">
            New business? <Link className="font-semibold text-moss" href="/register">Create account</Link>
          </p>
        </form>
      </section>
    </main>
  );
}
