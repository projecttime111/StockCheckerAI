"use client";

import { Monitor, RefreshCw, Server } from "lucide-react";
import { useServiceHealth } from "@/lib/serviceHealth";
import { ServiceStatusBadge } from "./ServiceStatusBadge";

type ProbeState = ReturnType<typeof useServiceHealth>["frontend"];

export function ServiceStatusPanel() {
  const health = useServiceHealth();
  return <section aria-labelledby="service-status-title" className="rounded-2xl border border-ink/10 bg-white p-5 shadow-sm sm:p-6">
    <div className="flex flex-wrap items-start justify-between gap-4">
      <div><p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-moss">Public service checks</p><h2 id="service-status-title" className="mt-2 text-xl font-semibold tracking-tight text-ink">Frontend and backend status</h2><p className="mt-2 max-w-2xl text-sm leading-6 text-ink/60">Check whether the website and backend process respond, even before signing in.</p></div>
      <button type="button" disabled={health.checking} onClick={() => void health.check()} className="focus-ring inline-flex min-h-10 items-center gap-2 rounded-lg border border-moss/20 bg-mint/30 px-4 py-2 text-sm font-semibold text-moss hover:bg-mint/60 disabled:cursor-wait disabled:opacity-60"><RefreshCw size={16} aria-hidden="true" className={health.checking ? "motion-safe:animate-spin" : ""} />{health.checking ? "Checking services…" : "Check again"}</button>
    </div>
    <div className="mt-6 grid gap-4 md:grid-cols-2">
      <ServiceCard name="Frontend" description="The StockPilot website server" probe={health.frontend} icon={<Monitor size={21} aria-hidden="true" />} />
      <ServiceCard name="Backend" description="The StockPilot API process" probe={health.backend} icon={<Server size={21} aria-hidden="true" />} />
    </div>
    {health.backend.status === "waiting" ? <p role="status" className="mt-4 rounded-lg border border-amber/30 bg-amber/10 px-4 py-3 text-sm leading-6 text-amber-950">The backend is taking longer to respond. It may be starting or temporarily slow. This check can wait for up to 90 seconds.</p> : null}
    <p className="mt-5 text-xs leading-6 text-ink/55">These are observations from this browser, not continuous monitoring. An online result does not verify sign-in, database access, or every workspace action. Use “Check again” for a fresh observation.</p>
  </section>;
}

function ServiceCard({ name, description, probe, icon }: { name: string; description: string; probe: ProbeState; icon: React.ReactNode }) {
  return <article aria-label={`${name} health`} className="rounded-xl border border-ink/10 bg-cream/30 p-4 sm:p-5">
    <div className="flex flex-wrap items-start justify-between gap-3"><div className="flex items-center gap-3"><span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-mint/60 text-moss">{icon}</span><div><h3 className="font-semibold text-ink">{name}</h3><p className="mt-1 text-xs text-ink/50">{description}</p></div></div><span role="status" className="rounded-full border border-ink/10 bg-white px-3 py-1.5 text-xs font-medium text-ink/75"><ServiceStatusBadge probe={probe} /></span></div>
    <p className="mt-4 min-h-12 text-sm leading-6 text-ink/65">{probe.message || "No check has completed yet."}</p>
    <dl className="mt-4 grid gap-3 border-t border-ink/10 pt-4 text-xs sm:grid-cols-2"><div><dt className="text-ink/50">Last checked</dt><dd className="mt-1 font-medium text-ink/75">{probe.checkedAt != null ? <time dateTime={new Date(probe.checkedAt).toISOString()}>{new Date(probe.checkedAt).toLocaleString()}</time> : "Not checked yet"}</dd></div><div><dt className="text-ink/50">Check duration</dt><dd className="mt-1 font-medium text-ink/75">{probe.responseTimeMs == null ? "Not available yet" : `${new Intl.NumberFormat().format(probe.responseTimeMs)} ms`}</dd></div></dl>
  </article>;
}
