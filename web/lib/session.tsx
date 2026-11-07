"use client";

import { useQuery } from "@tanstack/react-query";
import { api, getToken } from "./api";

export type WorkspaceMembership = { tenantId: string; name: string; role: string; businessMode: string; clerkOrganizationId?: string | null };
export type Session = {
  userId: string; email: string; fullName: string; tenantId: string | null; role: string | null;
  platformRole: string | null; permissions: string[]; workspaces: WorkspaceMembership[];
  authProvider: "local" | "clerk"; clerkOrganizationId: string | null;
  accessState: "WORKSPACE_REQUIRED" | "ORGANIZATION_REQUIRED" | "ORGANIZATION_UNLINKED" | "MEMBERSHIP_REQUIRED" | "WORKSPACE_READY" | "PLATFORM_READY";
  emailVerified: boolean; clerkLinked: boolean;
};
export function useSession(mode: "platform" | "workspace" | "auto" = "workspace") {
  return useQuery({ queryKey: ["session", mode], enabled: typeof window !== "undefined" && !!getToken(),
    queryFn: () => api<Session>("/api/auth/session", { headers: { "X-StockPilot-Mode": mode } }), staleTime: 15_000 });
}
