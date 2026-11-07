"use client";

import { useQuery } from "@tanstack/react-query";
import { AppShell } from "@/components/AppShell";
import { Panel } from "@/components/Panel";
import { QueryFeedback } from "@/components/QueryFeedback";
import { DetailItem, StatusBadge, formatCount, formatTimestamp } from "@/components/platform/PlatformUI";
import { DEMO_MODE } from "@/lib/api";
import { useSession } from "@/lib/session";
import { workspaceApi } from "@/lib/workspace";

export default function BillingPage() {
  return DEMO_MODE ? <AppShell title="Plan and usage"><Panel title="Account details"><p className="text-sm leading-6 text-ink/60">Plan and usage records require a connected backend. They are not available in the local demo.</p></Panel></AppShell> : <LiveBillingPage />;
}

function LiveBillingPage() {
  const session = useSession("workspace");
  const allowed = !session.error && !!session.data?.tenantId && !!session.data.permissions.includes("billing.view");
  const account = useQuery({ queryKey: ["workspace-account", session.data?.tenantId], enabled: allowed, queryFn: workspaceApi.account });
  const data = account.data;
  return <AppShell title="Plan and usage"><QueryFeedback queries={[session, ...(allowed ? [account] : [])]} />{session.data && !allowed ? <p role="alert" className="rounded border bg-white p-5 text-sm">Your role does not have access to this workspace’s plan details.</p> : null}{allowed && data && !account.error ? <div className="space-y-6"><p className="max-w-3xl text-sm leading-6 text-ink/60">Review the recorded StockPilot plan and current usage for {data.name}. Contact your StockPilot administrator to change your agreement or correct these details.</p><div className="grid items-start gap-6 lg:grid-cols-2"><Panel title="Current plan"><dl className="grid gap-6 sm:grid-cols-2"><DetailItem label="Recorded plan"><span className="text-xl font-semibold text-moss">{data.plan}</span></DetailItem><DetailItem label="Subscription status"><StatusBadge value={data.subscriptionStatus} /></DetailItem><DetailItem label="Subscription end">{data.subscriptionEndsAt ? formatTimestamp(data.subscriptionEndsAt) : "No end date recorded"}</DetailItem><DetailItem label="Workspace">{data.name}</DetailItem></dl><p className="mt-6 rounded border border-ink/10 bg-slate-50 p-3 text-xs leading-5 text-ink/60">This is a manual subscription record. It is not a payment receipt or confirmation of a charge. Online payments and payment-provider invoices are not available here.</p></Panel><Panel title="Workspace details"><dl className="grid gap-6 sm:grid-cols-2"><DetailItem label="Business mode">{data.businessMode}</DetailItem><DetailItem label="Currency">{data.currency}</DetailItem><DetailItem label="Account management">{data.clerkManaged ? "Organization account" : "StockPilot account"}</DetailItem><DetailItem label="Workspace ID"><span className="font-mono text-xs">{data.tenantId}</span></DetailItem></dl></Panel></div><Panel title="Current usage"><dl className="grid grid-cols-2 gap-5 md:grid-cols-4">{[{ label: "Members", value: data.usage.members }, { label: "Products", value: data.usage.products }, { label: "Warehouses", value: data.usage.warehouses }, { label: "Imports", value: data.usage.imports }].map(item => <div key={item.label} className="rounded border border-ink/10 p-4"><dt className="text-sm text-ink/50">{item.label}</dt><dd className="mt-2 text-2xl font-semibold">{formatCount(item.value)}</dd></div>)}</dl><p className="mt-4 text-xs text-ink/50">Counts reflect recorded workspace data. No plan limits or overage charges are calculated on this page.</p></Panel></div> : null}</AppShell>;
}
