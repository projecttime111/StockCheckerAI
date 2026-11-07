"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { Search } from "lucide-react";
import { Field, PrimaryButton, SecondaryButton, SelectInput, TextInput } from "@/components/FormControls";
import { Panel } from "@/components/Panel";
import { QueryFeedback } from "@/components/QueryFeedback";
import { PlatformEmpty, PlatformHeading, PlatformPager, StatusBadge, formatCount, formatTimestamp, tableClass } from "@/components/platform/PlatformUI";
import { businessStatuses, plans, platformApi, subscriptionStatuses } from "@/lib/platform";

const initialFilters = { search: "", status: "", plan: "", subscriptionStatus: "" };

export default function PlatformBusinessesPage() {
  const [draft, setDraft] = useState(initialFilters);
  const [filters, setFilters] = useState(initialFilters);
  const [page, setPage] = useState(0);
  const clients = useQuery({ queryKey: ["platform", "businesses", filters, page], queryFn: () => platformApi.businesses({ ...filters, page }) });
  function apply(event: FormEvent) { event.preventDefault(); setPage(0); setFilters({ ...draft, search: draft.search.trim() }); }
  return <>
    <PlatformHeading eyebrow="Client management" title="Businesses on StockPilot" description="Find a client workspace, review its activity, and manage its platform account. These are businesses using StockPilot." />
    <Panel title="Client directory" action={<span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-ink/60">{clients.data ? `${formatCount(clients.data.totalElements)} matching clients` : "Live directory"}</span>}>
      <form onSubmit={apply} className="mb-6 grid items-end gap-3 sm:grid-cols-2 xl:grid-cols-[2fr_1fr_1fr_1fr_auto]">
        <Field label="Search businesses"><TextInput maxLength={200} value={draft.search} onChange={event => setDraft({ ...draft, search: event.target.value })} placeholder="Business name" /></Field>
        <Field label="Business status"><SelectInput value={draft.status} onChange={event => setDraft({ ...draft, status: event.target.value })}><option value="">All statuses</option>{businessStatuses.map(value => <option key={value}>{value}</option>)}</SelectInput></Field>
        <Field label="Plan"><SelectInput value={draft.plan} onChange={event => setDraft({ ...draft, plan: event.target.value })}><option value="">All plans</option>{plans.map(value => <option key={value}>{value}</option>)}</SelectInput></Field>
        <Field label="Subscription status"><SelectInput value={draft.subscriptionStatus} onChange={event => setDraft({ ...draft, subscriptionStatus: event.target.value })}><option value="">All subscriptions</option>{subscriptionStatuses.map(value => <option key={value}>{value}</option>)}</SelectInput></Field>
        <div className="flex gap-2"><PrimaryButton className="gap-2" type="submit"><Search size={16} />Search</PrimaryButton><SecondaryButton type="button" onClick={() => { setDraft(initialFilters); setFilters(initialFilters); setPage(0); }}>Reset</SecondaryButton></div>
      </form>
      <QueryFeedback queries={[clients]} />
      {clients.data && !clients.error ? <>
        {clients.data.content.length ? <div className="overflow-x-auto"><table className={tableClass}><thead><tr><th>Business</th><th>Status</th><th>Plan / subscription</th><th>Members</th><th>Last import</th><th><span className="sr-only">Actions</span></th></tr></thead><tbody>{clients.data.content.map(client => <tr key={client.id} className="hover:bg-slate-50"><td><Link className="focus-ring rounded font-semibold text-moss hover:underline" href={`/admin/businesses/${client.id}`}>{client.name}</Link><p className="mt-1 text-xs text-ink/45">{client.businessMode} · {client.currency}</p></td><td><StatusBadge value={client.status} /></td><td><div className="mb-1 text-xs font-semibold text-ink/70">{client.plan}</div><StatusBadge value={client.subscriptionStatus} /></td><td>{formatCount(client.memberCount)}</td><td className="whitespace-nowrap text-xs text-ink/60">{formatTimestamp(client.lastImportAt)}</td><td><Link aria-label={`View ${client.name}`} className="focus-ring rounded text-sm font-semibold text-moss hover:underline" href={`/admin/businesses/${client.id}`}>View client →</Link></td></tr>)}</tbody></table></div> : <PlatformEmpty title="No businesses found">Try another name or clear the filters. Newly created client workspaces will appear here.</PlatformEmpty>}
        <PlatformPager data={clients.data} page={page} onChange={setPage} busy={clients.isFetching} />
      </> : null}
    </Panel>
  </>;
}
