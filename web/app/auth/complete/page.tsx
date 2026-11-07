"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { AccountFrame } from "@/components/AccountFrame";
import { getToken, loginDestination, signOutSession } from "@/lib/api";
import { useSession } from "@/lib/session";

export default function AuthCompletePage() {
  const session = useSession("auto");
  const router = useRouter();
  useEffect(() => {
    if (!getToken()) { router.replace("/login"); return; }
    const data = session.data;
    if (!data) return;
    if (data.accessState === "PLATFORM_READY" || data.accessState === "WORKSPACE_READY") router.replace(loginDestination(data.role ?? ""));
    else if (data.accessState === "ORGANIZATION_UNLINKED") router.replace("/onboarding");
    else router.replace("/workspace/select");
  }, [session.data, router]);
  return <AccountFrame title="Connecting your account">{session.error ? <div role="alert" className="space-y-4"><p>{session.error.message}</p>{/CLERK_ACCOUNT_LINK_REQUIRED|link.*existing|existing.*account/i.test(session.error.message) ? <><p>Confirm your existing StockPilot sign-in once to keep your workspaces and history.</p><Link className="inline-block rounded bg-moss px-4 py-2 text-white" href="/account/link">Link existing StockPilot account</Link></> : <button className="underline" onClick={() => session.refetch()}>Retry</button>}<Link className="ml-4 underline" href="/workspace/select">Switch workspace</Link><button className="ml-4 underline" onClick={() => void signOutSession()}>Sign out</button></div> : <p role="status">Checking your account and workspace access…</p>}</AccountFrame>;
}
