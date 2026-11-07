"use client";

import { useQuery } from "@tanstack/react-query";
import { useSyncExternalStore } from "react";
import { api, getSessionIdentity, SESSION_CHANGED_EVENT, SESSION_CLEARED_EVENT, WORKSPACE_CHANGED_EVENT } from "./api";

function subscribeIdentity(listener: () => void) {
  const events = [SESSION_CHANGED_EVENT, SESSION_CLEARED_EVENT, WORKSPACE_CHANGED_EVENT];
  events.forEach(event => window.addEventListener(event, listener));
  return () => events.forEach(event => window.removeEventListener(event, listener));
}
const serverIdentity = () => null;

export type WorkspaceMembership = { tenantId: string; name: string; role: string; businessMode: string; clerkOrganizationId?: string | null };
export type Session = {
  userId: string; email: string; fullName: string; tenantId: string | null; role: string | null;
  platformRole: string | null; permissions: string[]; workspaces: WorkspaceMembership[];
  authProvider: "local" | "clerk"; clerkOrganizationId: string | null;
  accessState: "WORKSPACE_REQUIRED" | "ORGANIZATION_REQUIRED" | "ORGANIZATION_UNLINKED" | "MEMBERSHIP_REQUIRED" | "WORKSPACE_READY" | "PLATFORM_READY";
  emailVerified: boolean; clerkLinked: boolean;
};
export function useSession(mode: "platform" | "workspace" | "auto" = "workspace") {
  const identity = useSyncExternalStore(subscribeIdentity, getSessionIdentity, serverIdentity);
  return useQuery({ queryKey: ["session", mode, identity], enabled: !!identity,
    // A sleeping backend needs time to start. Never leave this critical check in
    // React Query's paused/offline queue with no request timeout running.
    queryFn: ({ signal }) => api<Session>("/api/auth/session", { headers: { "X-StockPilot-Mode": mode }, timeoutMs: 75_000, signal }),
    networkMode: "always", retry: false, staleTime: mode === "platform" ? 0 : 15_000,
    refetchOnMount: mode === "platform" ? "always" : true });
}
