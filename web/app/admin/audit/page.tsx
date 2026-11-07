"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { Field, PrimaryButton, SecondaryButton, SelectInput, TextInput } from "@/components/FormControls";
import { Panel } from "@/components/Panel";
import { QueryFeedback } from "@/components/QueryFeedback";
import { PlatformEmpty, PlatformHeading, PlatformPager, formatTimestamp, tableClass } from "@/components/platform/PlatformUI";
import { platformApi } from "@/lib/platform";

const initial = { tenantId: "", action: "" };

export default function PlatformAuditPage() {
  const [draft, setDraft] = useState(initial);
  const [filters, setFilters] = useState(initial);
  const [page, setPage] = useState(0);
  const audit = useQuery({ queryKey: ["platform", "audit", filters, page], queryFn: () => platformApi.audit({ ...filters, page }) });
  function apply(event: FormEvent) { event.preventDefault(); setPage(0); setFilters({ tenantId: draft.tenantId.trim(), action: draft.action.trim() }); }
  return <><PlatformHeading eyebrow="Accountability" title="Platform audit trail" description="Review platform changes, the staff member responsible, and the recorded reason. Client inventory activity remains in its workspace audit log." />
    <Panel title="Recorded actions"><form onSubmit={apply} className="mb-6 flex flex-wrap items-end gap-3"><div className="min-w-64 flex-1"><Field label="Business ID"><TextInput value={draft.tenantId} onChange={event => setDraft({ ...draft, tenantId: event.target.value })} placeholder="Optional workspace UUID" pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" /></Field></div><div className="min-w-52 flex-1"><Field label="Action"><SelectInput value={draft.action} onChange={event => setDraft({ ...draft, action: event.target.value })}><option value="">All actions</option><option value="TENANT_SUSPENDED">Business suspended</option><option value="TENANT_REACTIVATED">Business reactivated</option><option value="SUBSCRIPTION_UPDATED">Subscription updated</option><option value="NOTES_UPDATED">Notes updated</option></SelectInput></Field></div><PrimaryButton type="submit">Apply filters</PrimaryButton><SecondaryButton type="button" onClick={() => { setDraft(initial); setFilters(initial); setPage(0); }}>Reset</SecondaryButton></form><QueryFeedback queries={[audit]} />
      {audit.data && !audit.error ? <>{audit.data.content.length ? <div className="overflow-x-auto"><table className={tableClass}><thead><tr><th>Action / reason</th><th>Business</th><th>Performed by</th><th>Recorded</th></tr></thead><tbody>{audit.data.content.map(entry => <tr key={entry.id}><td><p className="font-semibold">{entry.action.replaceAll("_", " ")}</p><p className="mt-2 max-w-lg whitespace-pre-wrap break-words text-xs leading-5 text-ink/60">{entry.reason || "No reason recorded"}</p><details className="mt-3 text-xs"><summary className="focus-ring cursor-pointer rounded text-moss">View changes</summary><div className="mt-3 grid gap-3 md:grid-cols-2"><div><p className="mb-1 text-ink/45">Before</p><pre className="max-w-sm whitespace-pre-wrap break-words rounded bg-slate-50 p-3 text-xs">{JSON.stringify(entry.beforeValues, null, 2) ?? "—"}</pre></div><div><p className="mb-1 text-ink/45">After</p><pre className="max-w-sm whitespace-pre-wrap break-words rounded bg-slate-50 p-3 text-xs">{JSON.stringify(entry.afterValues, null, 2) ?? "—"}</pre></div></div></details></td><td>{entry.tenantId ? <Link className="focus-ring rounded font-medium text-moss hover:underline" href={`/admin/businesses/${entry.tenantId}`}>{entry.businessName || entry.tenantId}</Link> : <span className="text-ink/45">Platform</span>}</td><td><p>{entry.actorName || "Unknown actor"}</p>{entry.actorUserId ? <p className="mt-1 max-w-36 break-all text-xs text-ink/40">{entry.actorUserId}</p> : null}</td><td className="whitespace-nowrap text-xs text-ink/55">{formatTimestamp(entry.createdAt)}</td></tr>)}</tbody></table></div> : <PlatformEmpty title="No platform actions found">Platform changes will appear here with their recorded reason. Clear filters to see other actions.</PlatformEmpty>}<PlatformPager data={audit.data} page={page} onChange={setPage} busy={audit.isFetching} /></> : null}
    </Panel>
  </>;
}
