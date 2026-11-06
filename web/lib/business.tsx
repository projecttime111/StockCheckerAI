"use client";

import { useQuery, QueryClient } from "@tanstack/react-query";
import { ReactNode } from "react";
import { api, getToken, money } from "./api";

export function useAccess() {
  const access = useQuery({ queryKey: ["permissions"], queryFn: () => api<{ role: string; permissions: string[] }>("/api/auth/me/permissions"), enabled: typeof window !== "undefined" && !!getToken() });
  return { ...access, can: (permission: string) => access.data?.permissions.includes(permission) ?? false };
}

export function Permission({ permission, children }: { permission: string; children: ReactNode }) {
  const { can } = useAccess();
  return can(permission) ? <>{children}</> : null;
}

export function useWorkspaceCurrency() {
  const tenant = useQuery({ queryKey: ["tenant"], queryFn: () => api<{ currency: string }>("/api/tenants/current"), enabled: typeof window !== "undefined" && !!getToken() });
  return tenant.data?.currency ?? "INR";
}

export function useMoney() {
  const currency = useWorkspaceCurrency();
  return (value: number | string | null | undefined) => money(value, currency);
}

// All business reads can change after a ledger write, payment, import, or cleanup.
// Keep active screens and inactive cached routes consistent with the same commit.
export function refreshBusiness(client: QueryClient) {
  return client.invalidateQueries({ predicate: query => !["permissions", "tenant", "me"].includes(String(query.queryKey[0])) });
}
