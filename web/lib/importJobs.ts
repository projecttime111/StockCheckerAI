"use client";

import { useCallback, useEffect, useRef } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, DEMO_MODE, newOperationId } from "./api";

export type ImportOperation = "CLASSIFY" | "STAGE" | "DRY_RUN" | "COMMIT";
export type ImportJob = { id: string; operation: ImportOperation; status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED"; progress: number; message: string; result?: unknown; error?: string; createdAt: string; updatedAt: string };
export function importJobActive(job: ImportJob) { return job.status === "QUEUED" || job.status === "RUNNING"; }
export function useImportJobs(sessionId: string, onComplete: (job: ImportJob) => void | Promise<void>, enabled = true) {
  const client = useQueryClient();
  const attempts = useRef(new Map<string, { fingerprint: string; requestId: string }>());
  const seen = useRef(new Set<string>());
  const callback = useRef(onComplete); callback.current = onComplete;
  const query = useQuery({ queryKey: ["smart-import-jobs", sessionId], enabled: enabled && !!sessionId && !DEMO_MODE,
    queryFn: () => api<ImportJob[]>(`/api/import-sessions/${sessionId}/jobs`),
    refetchInterval: query => query.state.data?.some(importJobActive) ? 1500 : false,
    refetchIntervalInBackground: true, retry: 1 });
  const start = useCallback(async (operation: ImportOperation, payload: Record<string, unknown> = {}) => {
    const fingerprint = JSON.stringify({ operation, payload });
    const previous = attempts.current.get(sessionId);
    const attempt = previous?.fingerprint === fingerprint ? previous : { fingerprint, requestId: newOperationId() };
    attempts.current.set(sessionId, attempt);
    try {
      const job = await api<ImportJob>(`/api/import-sessions/${sessionId}/jobs`, { method: "POST", body: JSON.stringify({ operation, payload, requestId: attempt.requestId }) });
      attempts.current.delete(sessionId);
      client.setQueryData<ImportJob[]>(["smart-import-jobs", sessionId], old => [job, ...(old ?? []).filter(item => item.id !== job.id)].slice(0, 20));
      return job;
    } finally {
      // Even a disconnected POST may have queued the work. Recover its durable status.
      void client.invalidateQueries({ queryKey: ["smart-import-jobs", sessionId] });
    }
  }, [client, sessionId]);
  const latest = query.data?.[0];
  useEffect(() => {
    if (!enabled || !latest || latest.status !== "SUCCEEDED" || seen.current.has(latest.id)) return;
    seen.current.add(latest.id);
    void Promise.resolve(callback.current(latest)).catch(() => { seen.current.delete(latest.id); });
  }, [enabled, latest]);
  return { ...query, start, latest, active: query.data?.find(importJobActive), busy: enabled && !DEMO_MODE && !!sessionId && (query.isLoading || !!query.error || !!query.data?.some(importJobActive)) };
}
