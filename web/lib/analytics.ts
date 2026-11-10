import { api } from "./api";

export type AnalyticsReadiness = { status: "READY" | "PARTIAL" | "NO_DATA"; asOf: string; salesFrom?: string; salesThrough?: string; observedDays: number; coveredDays: number; coverageConfirmed: boolean; latestImportAt?: string; latestForecastAt?: string; forecastStale: boolean; uncostedSalesLines: number | null; estimatedCostLines: number | null; warnings: string[] };
export const analyticsApi = {
  readiness: () => api<AnalyticsReadiness>("/api/analytics/readiness"),
  coverage: (body: { from: string; through: string; complete: boolean }) => api<AnalyticsReadiness>("/api/analytics/coverage", { method: "PUT", body: JSON.stringify(body) })
};
