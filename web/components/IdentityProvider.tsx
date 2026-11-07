"use client";

import { useAuth, useClerk } from "@clerk/nextjs";
import { ReactNode, useEffect, useState } from "react";
import { CLERK_ENABLED, clerkSessionReady, configureClerkSession } from "@/lib/api";

export function IdentityProvider({ children }: { children: ReactNode }) {
  return CLERK_ENABLED ? <ClerkSessionBridge>{children}</ClerkSessionBridge> : <>{children}</>;
}
function ClerkSessionBridge({ children }: { children: ReactNode }) {
  const { isLoaded, userId, orgId, getToken } = useAuth();
  const { signOut, setActive } = useClerk();
  const identity = userId ? `clerk:${userId}:${orgId ?? "personal"}` : null;
  const [readyIdentity, setReadyIdentity] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    if (!isLoaded) return;
    configureClerkSession({ identity, getToken: () => getToken(), signOut: () => signOut({ redirectUrl: "/login" }), clearOrganization: async () => { await setActive({ organization: null }); } });
    setReadyIdentity(identity);
    return () => configureClerkSession(null);
  }, [identity, isLoaded, getToken, signOut, setActive]);
  if (!isLoaded || readyIdentity !== identity || !clerkSessionReady(identity)) return <div className="p-8 text-sm" role="status">Preparing your secure session…</div>;
  return <>{children}</>;
}
