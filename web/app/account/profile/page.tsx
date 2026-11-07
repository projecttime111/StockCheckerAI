"use client";

import { UserProfile } from "@clerk/nextjs";
import Link from "next/link";
import { AccountFrame } from "@/components/AccountFrame";
import { CLERK_ENABLED, signOutSession } from "@/lib/api";
import { useSession } from "@/lib/session";

export default function AccountProfilePage() {
  const session = useSession("auto");
  return <AccountFrame title="My account">{session.isLoading ? <p role="status">Loading account…</p> : null}{session.error ? <p role="alert">{session.error.message}</p> : null}{CLERK_ENABLED ? <UserProfile routing="hash" /> : session.data ? <dl className="space-y-3"><div><dt className="text-sm text-ink/60">Name</dt><dd>{session.data.fullName}</dd></div><div><dt className="text-sm text-ink/60">Email</dt><dd>{session.data.email}</dd></div><div><dt className="text-sm text-ink/60">Email verification</dt><dd>{session.data.emailVerified ? "Verified" : "Verification required"}</dd></div><Link className="inline-block underline" href="/forgot-password">Reset password</Link></dl> : <Link href="/login">Sign in</Link>}<div className="flex gap-4"><Link className="underline" href="/workspace/select">Workspaces</Link><button className="underline" onClick={() => void signOutSession()}>Sign out</button></div></AccountFrame>;
}
