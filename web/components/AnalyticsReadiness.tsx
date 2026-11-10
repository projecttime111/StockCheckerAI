"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import Link from "next/link";
import { analyticsApi } from "@/lib/analytics";
import { DEMO_MODE } from "@/lib/api";
import { useAccess } from "@/lib/business";
import { Field, PrimaryButton, TextInput } from "./FormControls";
import { Panel } from "./Panel";
import { MutationFeedback, QueryFeedback } from "./QueryFeedback";

export function AnalyticsReadiness({ allowCoverage = false }: { allowCoverage?: boolean }) {
  const { can } = useAccess();
  const client = useQueryClient();
  const query = useQuery({ queryKey: ["analytics-readiness"], queryFn: analyticsApi.readiness, enabled: !DEMO_MODE });
  const [from, setFrom] = useState("");
  const [through, setThrough] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const save = useMutation({ mutationFn: () => analyticsApi.coverage({ from, through, complete: true }), onSuccess: async () => {
    setConfirmed(false); await Promise.all([client.invalidateQueries({ queryKey: ["analytics-readiness"] }), client.invalidateQueries({ queryKey: ["forecasts"] }), client.invalidateQueries({ queryKey: ["dashboard"] })]);
  } });
  const data = query.data;
  if (DEMO_MODE) return null;
  return <Panel title="Data readiness and coverage"><QueryFeedback queries={[query]} />{data && !query.error ? <div className="space-y-4"><div className="flex flex-wrap items-center justify-between gap-3"><p className="text-sm font-semibold text-moss">{data.status === "READY" ? "Ready for analysis" : data.status === "NO_DATA" ? "Import data to start" : "Partial data — review the limitations"}</p><span className="text-xs text-ink/50">Checked {new Date(data.asOf).toLocaleString()}</span></div><dl className="grid gap-4 text-sm sm:grid-cols-2 lg:grid-cols-4"><div><dt className="text-xs text-ink/55">{data.coverageConfirmed ? "Confirmed history dates" : "Observed sales date range"}</dt><dd className="mt-1">{data.salesFrom && data.salesThrough ? `${data.salesFrom} to ${data.salesThrough}` : "No sales history"}</dd></div><div><dt className="text-xs text-ink/55">History coverage</dt><dd className="mt-1">{data.coverageConfirmed ? `${data.coveredDays} confirmed days` : "Not confirmed"}<span className="block text-xs text-ink/50">{data.observedDays} calendar days between first and last sale</span></dd></div><div><dt className="text-xs text-ink/55">Cost quality</dt><dd className="mt-1">{data.uncostedSalesLines == null || data.estimatedCostLines == null ? "Restricted for your role" : `${data.uncostedSalesLines} uncosted · ${data.estimatedCostLines} estimated lines`}</dd></div><div><dt className="text-xs text-ink/55">Forecast freshness</dt><dd className="mt-1">{data.latestForecastAt ? new Date(data.latestForecastAt).toLocaleString() : "Not run yet"}{data.forecastStale ? <strong className="block text-xs text-amber-900">Refresh forecast after data changes</strong> : null}</dd></div></dl>{data.warnings.length ? <ul className="list-disc space-y-1 rounded-lg border border-amber/25 bg-amber/10 py-3 pl-8 pr-4 text-xs leading-6 text-amber-950">{data.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul> : null}<p className="text-xs leading-6 text-ink/55">The first and last sale dates do not prove that every day was imported. Blank days can mean missing records rather than zero demand. {data.latestImportAt ? `Latest import: ${new Date(data.latestImportAt).toLocaleString()}.` : ""}</p>{!allowCoverage ? <Link className="text-sm font-semibold text-moss underline" href="/imports/smart">Review imports and source totals</Link> : null}</div> : null}
    {allowCoverage && can("imports.validate") ? <form className="mt-5 space-y-3 border-t border-ink/10 pt-5" onSubmit={event => { event.preventDefault(); if (confirmed && from && through && from <= through) save.mutate(); }}><h3 className="text-sm font-semibold">Confirm a complete sales-history period</h3><p className="text-xs leading-6 text-ink/60">Only confirm after comparing your source files and import reconciliation. This confirms coverage for the whole business, including days with no sales.</p><fieldset disabled={save.isPending} className="space-y-3"><div className="grid gap-3 sm:grid-cols-2"><Field label="Complete history from"><TextInput type="date" required value={from} onChange={event => { setFrom(event.target.value); setConfirmed(false); save.reset(); }} /></Field><Field label="Complete history through"><TextInput type="date" required min={from || undefined} value={through} onChange={event => { setThrough(event.target.value); setConfirmed(false); save.reset(); }} /></Field></div><label className="flex items-start gap-2 text-xs leading-6"><input className="mt-1" type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />I checked the source records and confirm every sales day in this period is included, including any days with zero sales.</label><MutationFeedback mutation={save} success="History coverage saved. Run a fresh forecast to use the confirmed period." /><PrimaryButton type="submit" disabled={!confirmed || !from || !through || from > through || save.isPending}>{save.isPending ? "Saving coverage…" : "Confirm history coverage"}</PrimaryButton></fieldset></form> : null}
  </Panel>;
}
