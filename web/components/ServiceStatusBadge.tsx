"use client";

import type { useServiceHealth } from "@/lib/serviceHealth";

type ProbeState = ReturnType<typeof useServiceHealth>["frontend"];
const styles: Record<ProbeState["status"], string> = {
  unknown: "bg-slate-400", checking: "bg-amber-500 motion-safe:animate-pulse",
  waiting: "bg-amber-500 motion-safe:animate-pulse", online: "bg-emerald-600",
  unavailable: "bg-rose-600", offline: "bg-slate-500", demo: "bg-sky-600"
};
const labels: Record<ProbeState["status"], string> = {
  unknown: "Not checked", checking: "Checking", waiting: "Waiting for response",
  online: "Online", unavailable: "Unavailable", offline: "Device offline", demo: "Demo mode"
};

export function ServiceStatusBadge({ probe, compact = false }: { probe: ProbeState; compact?: boolean }) {
  const label = probe.status === "unknown" && probe.checkedAt != null ? "Check needed" : probe.status === "waiting" && compact ? "Waiting" : labels[probe.status];
  return <span className="inline-flex items-center gap-2 whitespace-nowrap"><span aria-hidden="true" className={`h-2 w-2 shrink-0 rounded-full ${styles[probe.status]}`} /><span>{label}</span></span>;
}
