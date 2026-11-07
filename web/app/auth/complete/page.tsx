"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { AccountFrame } from "@/components/AccountFrame";
import { LoadingState } from "@/components/LoadingState";
import { PrimaryButton } from "@/components/FormControls";
import { CLERK_ENABLED, getToken, invitationDestination, loginDestination, signOutSession } from "@/lib/api";
import { useSession, type Session } from "@/lib/session";

function sessionDestination(data: Session, search: string) {
  const invitation = invitationDestination(search);
  if (invitation) return invitation;
  switch (data.accessState) {
    case "PLATFORM_READY":
    case "WORKSPACE_READY": return loginDestination(data.role ?? "", search);
    case "ORGANIZATION_UNLINKED": return "/onboarding";
    case "ORGANIZATION_REQUIRED":
    case "WORKSPACE_REQUIRED": return data.workspaces.length ? "/workspace/select" : "/onboarding";
    case "MEMBERSHIP_REQUIRED": return "/workspace/select";
    default: return null;
  }
}

export default function AuthCompletePage() {
  const session = useSession("auto");
  const router = useRouter();
  const client = useQueryClient();
  const signedIn = !!getToken();
  const [attempt, setAttempt] = useState(0);
  const [slow, setSlow] = useState(false);
  const [expired, setExpired] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const redirected = useRef<string | null>(null);
  const destination = session.data && typeof window !== "undefined" ? sessionDestination(session.data, window.location.search) : null;

  useEffect(() => {
    const explain = window.setTimeout(() => setSlow(true), 10_000);
    // Covers a paused/orphaned query or navigation failure as well as a slow API.
    const stop = window.setTimeout(() => setExpired(true), 90_000);
    return () => { window.clearTimeout(explain); window.clearTimeout(stop); };
  }, [attempt]);

  useEffect(() => {
    const target = !signedIn ? (CLERK_ENABLED ? "/sign-in" : "/login") : !session.error && !session.isFetching ? destination : null;
    if (target && redirected.current !== target) {
      redirected.current = target;
      try { router.replace(target); }
      catch { setActionError("Navigation could not finish. Use the continue link below or reload this page."); setExpired(true); }
    }
  }, [destination, router, signedIn, session.error, session.isFetching, attempt]);

  async function retry() {
    await client.cancelQueries({ queryKey: ["session"] });
    redirected.current = null;
    setSlow(false); setExpired(false); setActionError(null); setAttempt(value => value + 1);
    await session.refetch();
  }
  async function signOut() {
    setSigningOut(true); setActionError(null);
    try { await signOutSession(); }
    catch { setSigningOut(false); setActionError("Could not sign out. Check your connection and try again."); }
  }

  const failed = !!session.error || expired || (!!session.data && !destination);
  const errorMessage = session.error?.message || (expired ? "We couldn’t finish opening your workspace. The service may still be starting, or your connection may have changed." : "We couldn’t determine your workspace access. Please retry or sign in again.");
  return <AccountFrame title="Getting your workspace ready" description="Your secure sign-in is complete. We’re checking which workspace to open.">
    {failed ? <div className="space-y-4">
      <p role="alert" className="rounded-xl border border-amber/30 bg-amber/10 p-4 text-sm leading-6">{errorMessage}</p>
      <div className="flex flex-wrap items-center gap-4"><PrimaryButton onClick={() => void retry()}>Try again</PrimaryButton>{destination && <a className="font-medium text-moss underline" href={destination}>Continue to workspace</a>}</div>
    </div> : <>
      <LoadingState compact title={destination && !session.isFetching ? "Opening your workspace…" : slow ? "Still checking your workspace…" : "Checking your account and workspace access…"} description={slow ? "The service may need a moment to wake up after inactivity. This check will stop and offer a retry if it takes too long." : "This usually takes a few seconds."} />
      {slow && <div className="flex flex-wrap gap-4 text-sm"><button className="font-medium text-moss underline" onClick={() => void retry()}>Retry connection</button>{destination && <a className="font-medium text-moss underline" href={destination}>Continue to workspace</a>}</div>}
    </>}
    {actionError && <p role="alert" className="text-sm text-coral">{actionError}</p>}
    <div className="flex flex-wrap items-center gap-5 border-t border-ink/10 pt-5 text-sm"><Link className="text-ink/65 hover:text-moss" href="/">Back to home</Link><button disabled={signingOut} className="text-moss underline disabled:opacity-50" onClick={() => void signOut()}>{signingOut ? "Signing out…" : "Sign out and try again"}</button></div>
  </AccountFrame>;
}
