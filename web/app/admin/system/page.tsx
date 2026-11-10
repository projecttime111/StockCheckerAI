"use client";

import { useQuery } from "@tanstack/react-query";
import { Activity, Clock3, Database, TriangleAlert } from "lucide-react";
import { ServiceStatusPanel } from "@/components/ServiceStatusPanel";
import { SecondaryButton } from "@/components/FormControls";
import { Panel } from "@/components/Panel";
import { QueryFeedback } from "@/components/QueryFeedback";
import { StatCard } from "@/components/StatCard";
import { DetailItem, PlatformHeading, PlatformLink, StatusBadge, formatCount, formatTimestamp } from "@/components/platform/PlatformUI";
import { platformApi } from "@/lib/platform";

export default function PlatformSystemPage() {
  const health = useQuery({ queryKey: ["platform", "system"], queryFn: platformApi.system });
  const data = health.data;
  return <><PlatformHeading eyebrow="Service operations" title="System status" description="Current API database connectivity and recorded import health. Refresh to retrieve a new observation." action={<SecondaryButton disabled={health.isFetching} onClick={() => void health.refetch()}>{health.isFetching ? "Refreshing…" : "Refresh status"}</SecondaryButton>} /><div className="mb-6"><ServiceStatusPanel /></div><QueryFeedback queries={[health]} />
    {data && !health.error ? <div className="space-y-6"><div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4"><StatCard label="Database" value={<StatusBadge value={data.database.status} />} icon={<Database size={20} />} /><StatCard label="API uptime" value={uptime(data.uptimeSeconds)} icon={<Clock3 size={20} />} /><StatCard label="Committing imports" value={formatCount(data.committingImports)} icon={<Activity size={20} />} /><StatCard label="Import failures · last 24h" value={formatCount(data.failuresLast24Hours)} icon={<TriangleAlert size={20} />} tone={data.failuresLast24Hours ? "coral" : "moss"} /></div>
      <div className="grid gap-6 lg:grid-cols-2"><Panel title="Database observation"><dl className="grid gap-6 sm:grid-cols-2"><DetailItem label="Response time">{data.database.responseTimeMs < 0 ? "Unavailable" : `${formatCount(data.database.responseTimeMs)} ms`}</DetailItem><DetailItem label="Checked at">{formatTimestamp(data.database.checkedAt)}</DetailItem></dl><p className="mt-6 text-xs leading-5 text-ink/50">This check covers database connectivity from the API. Email, storage, authentication providers and other services are not included in this observation.</p></Panel><Panel title="Import health" action={<PlatformLink href="/admin/imports">Review imports</PlatformLink>}><dl className="grid gap-6 sm:grid-cols-2"><DetailItem label="Total imports">{formatCount(data.totalImports)}</DetailItem><DetailItem label="Failed imports">{formatCount(data.failedImports)}</DetailItem><DetailItem label="Oldest committing import">{formatTimestamp(data.oldestCommittingAt)}</DetailItem><DetailItem label="Latest import failure">{formatTimestamp(data.lastFailureAt)}</DetailItem></dl></Panel></div>
      <Panel title="Recorded import states"><div className="grid gap-3 sm:grid-cols-3 xl:grid-cols-5">{Object.entries(data.importsByStatus).map(([status, count]) => <div key={status} className="rounded-lg border border-ink/10 p-4"><StatusBadge value={status} /><p className="mt-3 text-2xl font-semibold">{formatCount(count)}</p></div>)}{!Object.keys(data.importsByStatus).length ? <p className="text-sm text-ink/50">No imports recorded.</p> : null}</div></Panel><p className="text-xs text-ink/45">Snapshot generated {formatTimestamp(data.generatedAt)}</p></div> : null}
  </>;
}

function uptime(seconds: number) {
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours}h ${minutes % 60}m` : `${Math.floor(hours / 24)}d ${hours % 24}h`;
}
