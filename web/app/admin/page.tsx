"use client";

import { useQuery } from "@tanstack/react-query";
import { Building2, CircleAlert, Upload, Users } from "lucide-react";
import { Panel } from "@/components/Panel";
import { QueryFeedback } from "@/components/QueryFeedback";
import { StatCard } from "@/components/StatCard";
import { PlatformHeading, PlatformLink, formatCount, formatTimestamp } from "@/components/platform/PlatformUI";
import { platformApi } from "@/lib/platform";

export default function PlatformOverviewPage() {
  const overview = useQuery({ queryKey: ["platform", "dashboard"], queryFn: platformApi.dashboard });
  const data = overview.data;
  return <>
    <PlatformHeading eyebrow="Platform overview" title="Your clients, at a glance" description="A live view of subscribed businesses and import operations across StockPilot." action={<PlatformLink href="/admin/businesses">View clients</PlatformLink>} />
    <QueryFeedback queries={[overview]} />
    {data && !overview.error ? <div className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4"><StatCard label="Total businesses" value={formatCount(data.totalBusinesses)} icon={<Building2 size={20} />} /><StatCard label="Registered users" value={formatCount(data.totalUsers)} icon={<Users size={20} />} /><StatCard label="Imports recorded" value={formatCount(data.totalImports)} icon={<Upload size={20} />} /><StatCard label="Failed imports" value={formatCount(data.failedImports)} icon={<CircleAlert size={20} />} tone={data.failedImports ? "coral" : "moss"} /></div>
      <div className="grid gap-6 xl:grid-cols-[1.2fr_1fr]">
        <Panel title="Business activity"><div className="grid gap-3 sm:grid-cols-3">{[{ label: "Active", value: data.activeBusinesses, style: "bg-mint/60 text-moss" }, { label: "Suspended", value: data.suspendedBusinesses, style: "bg-amber/10 text-ink" }, { label: "Deleted", value: data.deletedBusinesses, style: "bg-slate-50 text-ink/60" }].map(item => <div key={item.label} className={`rounded-lg p-5 ${item.style}`}><p className="text-sm">{item.label}</p><p className="mt-3 text-3xl font-semibold">{formatCount(item.value)}</p></div>)}</div><p className="mt-5 text-xs leading-5 text-ink/50">Workspace lifecycle status is separate from the manual subscription record. Open a client to review both.</p></Panel>
        <Panel title="Import operations" action={<PlatformLink href="/admin/imports">Monitor imports</PlatformLink>}><dl className="divide-y divide-ink/10"><div className="flex justify-between py-4"><dt className="text-sm text-ink/60">Currently committing</dt><dd className="font-semibold">{formatCount(data.committingImports)}</dd></div><div className="flex justify-between py-4"><dt className="text-sm text-ink/60">Failed / validation failed</dt><dd className="font-semibold">{formatCount(data.failedImports)}</dd></div></dl><p className="mt-3 text-xs leading-5 text-ink/50">Counts reflect recorded import states. Review the affected client before taking action.</p></Panel>
      </div>
      <div className="grid gap-6 md:grid-cols-2"><Panel title="Businesses by plan"><Distribution values={data.businessesByPlan} /></Panel><Panel title="Subscription records"><Distribution values={data.businessesBySubscriptionStatus} /><p className="mt-5 text-xs leading-5 text-ink/50">Manually maintained statuses. These figures do not represent collected payments or revenue.</p></Panel></div>
      <p className="text-xs text-ink/45">Snapshot generated {formatTimestamp(data.generatedAt)}</p>
    </div> : null}
  </>;
}

function Distribution({ values }: { values: Record<string, number> }) {
  const rows = Object.entries(values);
  const max = Math.max(1, ...Object.values(values));
  return rows.length ? <dl className="space-y-5">{rows.map(([label, value]) => <div key={label}><div className="mb-2 flex justify-between gap-4 text-sm"><dt className="capitalize text-ink/60">{label.replaceAll("_", " ").toLowerCase()}</dt><dd className="font-semibold">{formatCount(value)}</dd></div><div className="h-2 overflow-hidden rounded-full bg-slate-100" aria-hidden="true"><div className="h-full rounded-full bg-moss/75" style={{ width: `${value / max * 100}%` }} /></div></div>)}</dl> : <p className="py-5 text-sm text-ink/50">No businesses recorded yet.</p>;
}
