"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Users } from "lucide-react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { FormEvent, ReactNode, Suspense, useState } from "react";
import { Field, PrimaryButton, TextInput } from "@/components/FormControls";
import { MutationFeedback, QueryFeedback } from "@/components/QueryFeedback";
import { clearSession, CLERK_ENABLED, DEMO_MODE, getToken, setSession } from "@/lib/api";
import { useSession } from "@/lib/session";
import { InvitationSession, roleLabel, workspaceApi } from "@/lib/workspace";

export default function AcceptInvitationPage() {
  return <Suspense fallback={<InvitationFrame><p role="status">Opening invitation…</p></InvitationFrame>}><InvitationFromLink /></Suspense>;
}

function InvitationFromLink() {
  const search = useSearchParams();
  return DEMO_MODE ? <InvitationFrame><h1 className="text-2xl font-semibold">Workspace invitations</h1><p className="mt-3 text-sm text-ink/60">Invitations require a connected backend. They are not available in the local demo.</p><Link href="/login" className="mt-5 inline-block text-moss underline">Return to sign in</Link></InvitationFrame> : <InvitationAcceptance token={search.get("token") ?? ""} />;
}

export function InvitationAcceptance({ token }: { token: string }) {
  const client = useQueryClient();
  const validToken = !!token && token.length <= 128;
  const signedIn = !!getToken();
  const session = useSession("auto");
  const preview = useQuery({ queryKey: ["invitation-preview", token], enabled: validToken, queryFn: () => workspaceApi.invitationPreview(token), retry: false });
  const [fullName, setFullName] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const destination = `/team/accept?token=${encodeURIComponent(token)}`;
  const loginHref = `/login?next=${encodeURIComponent(destination)}`;
  function complete(data: InvitationSession) {
    if (data.accessToken) {
      clearSession();
      client.clear();
      setSession(data.accessToken, data.role, []);
    } else client.clear();
    window.location.assign("/dashboard");
  }
  const accept = useMutation({ mutationFn: () => workspaceApi.acceptInvitation(token), onSuccess: complete });
  const register = useMutation({ mutationFn: () => workspaceApi.registerInvitation(token, fullName, password), onSuccess: complete });
  function submit(event: FormEvent) {
    event.preventDefault(); setFormError(null);
    if (!fullName.trim()) { setFormError("Enter your full name."); return; }
    if (password !== confirmPassword) { setFormError("The passwords do not match."); return; }
    if (password.length < 8 || password.length > 72) { setFormError("Choose a password between 8 and 72 characters."); return; }
    if (!register.isPending) register.mutate();
  }
  const invitation = preview.data;
  const matchingAccount = signedIn && !session.error && session.data?.email.toLowerCase() === invitation?.email.toLowerCase();
  return <InvitationFrame>
    <div className="mb-5 inline-flex rounded-xl bg-mint p-3 text-moss"><Users size={24} /></div><h1 className="text-2xl font-semibold tracking-tight">Join your team</h1><p className="mt-2 text-sm text-ink/55">Accept an invitation to a StockPilot business workspace.</p>
    {!validToken ? <div className="mt-6"><p role="alert" className="text-sm text-[#ad4534]">This invitation link is incomplete. Open the full link from your invitation email.</p><Link href="/login" className="mt-4 inline-block text-sm font-semibold text-moss underline">Return to sign in</Link></div> : <div className="mt-6"><QueryFeedback queries={[preview]} />{invitation && !preview.error ? <>
      <dl className="mb-6 space-y-3 rounded-lg border border-ink/10 bg-slate-50 p-4 text-sm"><div><dt className="text-xs text-ink/45">Workspace</dt><dd className="mt-1 font-semibold">{invitation.workspaceName}</dd></div><div><dt className="text-xs text-ink/45">Invited email</dt><dd className="mt-1 break-words">{invitation.email}</dd></div><div><dt className="text-xs text-ink/45">Workspace role</dt><dd className="mt-1">{roleLabel(invitation.role)}</dd></div></dl>
      {CLERK_ENABLED && !invitation.clerkManaged ? <div className="space-y-4"><p role="alert" className="text-sm leading-6 text-ink/65">This invitation is no longer supported. Ask the business owner to send you a new invitation from the Team page, then sign in with the invited email.</p><Link href={signedIn ? "/workspace/select" : "/sign-in?next=%2Fauth%2Fcomplete"} className="text-sm font-semibold text-moss underline">{signedIn ? "Choose a workspace" : "Return to sign in"}</Link></div> : invitation.clerkManaged ? <div className="space-y-4"><p className="text-sm leading-6 text-ink/65">This workspace uses organization sign-in. Open the organization invitation in your email to join, then continue to StockPilot with {invitation.email}.</p><Link href={signedIn ? "/auth/complete" : "/sign-in?next=%2Fauth%2Fcomplete"} className="focus-ring inline-flex rounded bg-moss px-4 py-2.5 text-sm font-semibold text-white">{signedIn ? "Continue to workspace selection" : "Continue to sign in"}</Link></div> : signedIn ? <div className="space-y-4"><QueryFeedback queries={[session]} />{session.data && !session.error ? matchingAccount ? <><p className="text-sm text-ink/60">Signed in as {session.data.email}. Accept to join this workspace.</p>{!session.data.emailVerified ? <p role="alert" className="text-sm text-[#ad4534]">Verify your account email before accepting this invitation.</p> : null}<MutationFeedback mutation={accept} /><PrimaryButton loading={accept.isPending} disabled={accept.isPending || !session.data.emailVerified} onClick={() => accept.mutate()}>{accept.isPending ? "Joining…" : "Accept invitation"}</PrimaryButton></> : <><p role="alert" className="text-sm leading-6 text-[#ad4534]">You are signed in as {session.data.email}. This invitation belongs to {invitation.email}.</p><Link href={loginHref} className="text-sm font-semibold text-moss underline">Sign in with the invited email</Link></> : session.error ? <Link href={loginHref} className="text-sm font-semibold text-moss underline">Sign in to accept</Link> : null}</div> : invitation.accountExists ? <div className="space-y-4"><p className="text-sm leading-6 text-ink/60">An account already exists for this email. Sign in with that account to accept your invitation.</p><Link href={loginHref} className="focus-ring inline-flex rounded bg-moss px-4 py-2.5 text-sm font-semibold text-white">Sign in to accept</Link></div> : <form onSubmit={submit} className="space-y-4"><p className="text-sm leading-6 text-ink/60">Create your login to join {invitation.workspaceName}. Your invited email and role are fixed by the workspace administrator.</p><fieldset disabled={register.isPending} className="space-y-4"><Field label="Full name"><TextInput required maxLength={160} autoComplete="name" value={fullName} onChange={event => setFullName(event.target.value)} /></Field><Field label="Password"><TextInput type="password" required minLength={8} maxLength={72} autoComplete="new-password" value={password} onChange={event => setPassword(event.target.value)} /></Field><Field label="Confirm password"><TextInput type="password" required minLength={8} maxLength={72} autoComplete="new-password" value={confirmPassword} onChange={event => setConfirmPassword(event.target.value)} /></Field>{formError ? <p role="alert" className="text-sm text-[#ad4534]">{formError}</p> : null}<MutationFeedback mutation={register} /><PrimaryButton type="submit" loading={register.isPending} disabled={register.isPending}>{register.isPending ? "Joining…" : "Create account and join"}</PrimaryButton></fieldset></form>}
    </> : preview.error ? <p className="mt-4 text-sm leading-6 text-ink/55">If this invitation has expired or been revoked, ask your workspace administrator for a new invitation.</p> : null}</div>}
  </InvitationFrame>;
}

function InvitationFrame({ children }: { children: ReactNode }) {
  return <main className="flex min-h-screen items-center justify-center bg-[#eef7f0] px-5 py-12"><div className="w-full max-w-lg rounded-xl border border-ink/10 bg-white p-6 shadow-soft sm:p-8">{children}</div></main>;
}
