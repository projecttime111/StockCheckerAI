"use client";

import { useAuth, useClerk } from "@clerk/nextjs";
import { usePathname } from "next/navigation";
import { ReactNode, useEffect, useRef, useState } from "react";
import { CLERK_ENABLED, clerkSessionReady, configureClerkSession } from "@/lib/api";
import { LoadingState } from "@/components/LoadingState";

export function IdentityProvider({ children }: { children: ReactNode }) {
  return CLERK_ENABLED ? <ClerkSessionBridge>{children}</ClerkSessionBridge> : <>{children}</>;
}
function ClerkSessionBridge({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { isLoaded, userId, orgId, getToken } = useAuth();
  const { signOut, setActive } = useClerk();
  const methods = useRef({ getToken, signOut, setActive });
  methods.current = { getToken, signOut, setActive };
  const identity = userId ? `clerk:${userId}:${orgId ?? "personal"}` : null;
  const [readyIdentity, setReadyIdentity] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    if (!isLoaded) return;
    configureClerkSession({ identity, getToken: () => methods.current.getToken(), signOut: () => methods.current.signOut({ redirectUrl: "/" }), clearOrganization: async () => { await methods.current.setActive({ organization: null }); } });
    setReadyIdentity(identity);
    return () => configureClerkSession(null);
  }, [identity, isLoaded]);
  // The public homepage must work even if Clerk is still loading or unreachable.
  // Keep the bridge mounted in the background so navigating to sign-in is ready.
  if (pathname === "/" || pathname === "/status") return <>{children}</>;
  if (!isLoaded || readyIdentity !== identity || !clerkSessionReady(identity)) return <SessionLoading key={identity} />;
  return <>{children}</>;
}

function SessionLoading() {
  const [delayed, setDelayed] = useState(false);
  useEffect(() => {
    const timer = window.setTimeout(() => setDelayed(true), 15_000);
    return () => window.clearTimeout(timer);
  }, []);
  return <main className="flex min-h-screen items-center justify-center bg-[#eef7f0] p-5">
    <div className="w-full max-w-md space-y-4 rounded-2xl bg-white p-8 shadow-sm">
      <h1 className="text-xl font-semibold">Connecting to StockPilot</h1>
      <LoadingState compact title={delayed ? "Sign-in is taking longer than expected" : "Preparing your secure session…"} description={delayed ? "Check your connection and retry." : "Securely verifying your sign-in."} />
      {delayed && <button type="button" className="rounded bg-moss px-4 py-2 text-white" onClick={() => window.location.reload()}>Retry sign-in</button>}
    </div>
  </main>;
}
