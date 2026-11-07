import { api } from "@/lib/api";

export const platformRoles = ["PLATFORM_SUPER_ADMIN", "PLATFORM_SUPPORT", "PLATFORM_BILLING_ADMIN"] as const;
export type PlatformRole = typeof platformRoles[number];
export type PlatformSession = { userId: string; email: string; fullName: string; tenantId: string | null; role: string | null; platformRole: string | null; permissions: string[]; workspaces: { tenantId: string; name: string; role: string }[] };
export type PlatformPage<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number };
export const businessStatuses = ["ACTIVE", "SUSPENDED", "DELETED"] as const;
export const plans = ["FREE", "STARTER", "PRO", "ENTERPRISE"] as const;
export const subscriptionStatuses = ["TRIAL", "ACTIVE", "PAST_DUE", "CANCELLED", "EXPIRED"] as const;
export type BusinessSummary = { id: string; name: string; businessMode: string; status: string; currency: string; gstEnabled: boolean; createdAt: string; plan: string; subscriptionStatus: string; subscriptionEndsAt: string | null; memberCount: number; productCount: number; importCount: number; lastImportAt: string | null };
export type BusinessUsage = { memberCount: number; productCount: number; customerCount: number; supplierCount: number; warehouseCount: number; salesInvoiceCount: number; purchaseInvoiceCount: number; stockMovementCount: number; importCount: number; importFileCount: number; lastImportAt: string | null };
export type BusinessDetail = { business: BusinessSummary; usage: BusinessUsage; internalNotes: string | null };
export type BusinessMember = { userId: string; email: string; fullName: string; role: string; active: boolean; joinedAt: string };
export type PlatformImport = { id: string; tenantId: string; businessName: string; kind: "LEGACY" | "SMART"; source: string; status: string; name: string; createdAt: string; committedAt: string | null; rowCount: number; errorCount: number; fileCount: number; fileNames: string[] };
export type PlatformDashboard = { generatedAt: string; totalBusinesses: number; activeBusinesses: number; suspendedBusinesses: number; deletedBusinesses: number; totalUsers: number; totalImports: number; failedImports: number; committingImports: number; businessesByPlan: Record<string, number>; businessesBySubscriptionStatus: Record<string, number> };
export type PlatformAudit = { id: string; tenantId: string | null; businessName: string | null; actorUserId: string | null; actorName: string | null; action: string; reason: string; beforeValues: Record<string, unknown>; afterValues: Record<string, unknown>; createdAt: string };
export type PlatformSystem = { generatedAt: string; database: { status: string; responseTimeMs: number; checkedAt: string }; uptimeSeconds: number; totalImports: number; failedImports: number; committingImports: number; oldestCommittingAt: string | null; failuresLast24Hours: number; lastFailureAt: string | null; importsByStatus: Record<string, number> };

export function isPlatformSession(session?: PlatformSession): session is PlatformSession {
  return !!session && session.tenantId === null && platformRoles.includes(session.role as PlatformRole) && platformRoles.includes(session.platformRole as PlatformRole);
}

export function platformPath(path: string, filters: Record<string, string | number | boolean | undefined> = {}) {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => { if (value !== undefined && value !== "") params.set(key, String(value)); });
  return `/api/platform${path}${params.size ? `?${params}` : ""}`;
}

export const platformApi = {
  dashboard: () => api<PlatformDashboard>(platformPath("/dashboard")),
  system: () => api<PlatformSystem>(platformPath("/system")),
  businesses: (filters: Record<string, string | number | undefined>) => api<PlatformPage<BusinessSummary>>(platformPath("/businesses", { size: 20, ...filters })),
  business: (id: string) => api<BusinessDetail>(platformPath(`/businesses/${encodeURIComponent(id)}`)),
  members: (id: string, page: number) => api<PlatformPage<BusinessMember>>(platformPath(`/businesses/${encodeURIComponent(id)}/members`, { page, size: 10 })),
  businessImports: (id: string, page: number) => api<PlatformPage<PlatformImport>>(platformPath(`/businesses/${encodeURIComponent(id)}/imports`, { page, size: 10 })),
  imports: (filters: Record<string, string | number | boolean | undefined>) => api<PlatformPage<PlatformImport>>(platformPath("/imports", { size: 20, ...filters })),
  audit: (filters: Record<string, string | number | undefined>) => api<PlatformPage<PlatformAudit>>(platformPath("/audit", { size: 20, ...filters })),
  setStatus: (id: string, body: { status: "ACTIVE" | "SUSPENDED"; reason: string }) => api<BusinessDetail>(platformPath(`/businesses/${encodeURIComponent(id)}/status`), { method: "PATCH", body: JSON.stringify(body) }),
  setSubscription: (id: string, body: { plan: string; subscriptionStatus: string; subscriptionEndsAt: string | null; reason: string }) => api<BusinessDetail>(platformPath(`/businesses/${encodeURIComponent(id)}/subscription`), { method: "PATCH", body: JSON.stringify(body) }),
  setNotes: (id: string, body: { notes: string; reason: string }) => api<BusinessDetail>(platformPath(`/businesses/${encodeURIComponent(id)}/notes`), { method: "PATCH", body: JSON.stringify(body) })
};
