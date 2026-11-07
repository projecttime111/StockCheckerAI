"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, useState } from "react";
import { Field, PrimaryButton, SecondaryButton, SelectInput, TextInput } from "@/components/FormControls";
import { Panel } from "@/components/Panel";
import { MutationFeedback, QueryFeedback } from "@/components/QueryFeedback";
import { BusinessDetail, BusinessSummary, BusinessUsage, plans, platformApi, subscriptionStatuses } from "@/lib/platform";
import { ImportTable } from "./ImportTable";
import { usePlatformAccess } from "./PlatformShell";
import { DetailItem, PlatformEmpty, PlatformHeading, PlatformLink, PlatformPager, StatusBadge, formatCount, formatTimestamp, tableClass, textAreaClass } from "./PlatformUI";

export function BusinessDetailView({ id }: { id: string }) {
  const { can } = usePlatformAccess();
  const detail = useQuery({ queryKey: ["platform", "business", id], queryFn: () => platformApi.business(id) });
  const data = detail.data;
  return <><div className="mb-5"><PlatformLink href="/admin/businesses" back>All clients</PlatformLink></div><QueryFeedback queries={[detail]} />
    {data && !detail.error ? <>
      <PlatformHeading eyebrow="Client workspace" title={data.business.name} description="Review this business account, its subscription record, and recent operations." action={<StatusBadge value={data.business.status} />} />
      <div className="space-y-6">
        <div className="grid items-start gap-6 xl:grid-cols-[1.3fr_1fr]"><Panel title="Business profile"><dl className="grid gap-6 sm:grid-cols-2"><DetailItem label="Business ID"><span className="font-mono text-xs">{data.business.id}</span></DetailItem><DetailItem label="Created">{formatTimestamp(data.business.createdAt)}</DetailItem><DetailItem label="Business mode">{data.business.businessMode}</DetailItem><DetailItem label="Currency">{data.business.currency}</DetailItem><DetailItem label="GST enabled">{data.business.gstEnabled ? "Yes" : "No"}</DetailItem><DetailItem label="Latest import">{formatTimestamp(data.business.lastImportAt)}</DetailItem></dl></Panel><Panel title="Subscription record"><dl className="grid gap-6 sm:grid-cols-2"><DetailItem label="Plan">{data.business.plan}</DetailItem><DetailItem label="Status"><StatusBadge value={data.business.subscriptionStatus} /></DetailItem><DetailItem label="Subscription end">{formatTimestamp(data.business.subscriptionEndsAt)}</DetailItem></dl><p className="mt-5 rounded border border-ink/10 bg-slate-50 p-3 text-xs leading-5 text-ink/60">This is a manually maintained account record. Updating it does not collect a payment, issue an invoice, or change payment-provider data.</p></Panel></div>
        <UsagePanel usage={data.usage} />
        {can("platform.subscriptions.manage") || can("platform.tenants.manage") ? <div className="grid items-start gap-6 xl:grid-cols-2">
          {can("platform.subscriptions.manage") && data.business.status !== "DELETED" ? <SubscriptionForm key={`subscription-${id}`} business={data.business} /> : null}
          {can("platform.tenants.manage") ? <StatusControl key={`status-${id}`} business={data.business} /> : null}
        </div> : null}
        {data.internalNotes !== null ? <NotesPanel key={`notes-${id}`} data={data} editable={can("platform.tenants.manage") && data.business.status !== "DELETED"} /> : null}
        {can("platform.audit.view") ? <><MembersPanel key={`members-${id}`} id={id} /><BusinessImportsPanel key={`imports-${id}`} id={id} /></> : null}
      </div>
    </> : null}
  </>;
}

const usageFields: { key: keyof BusinessUsage; label: string }[] = [
  { key: "memberCount", label: "Members" }, { key: "productCount", label: "Products" }, { key: "customerCount", label: "Customers" }, { key: "supplierCount", label: "Suppliers" }, { key: "warehouseCount", label: "Warehouses" }, { key: "salesInvoiceCount", label: "Sales invoices" }, { key: "purchaseInvoiceCount", label: "Purchase invoices" }, { key: "stockMovementCount", label: "Stock movements" }, { key: "importCount", label: "Imports" }, { key: "importFileCount", label: "Import files" }
];
function UsagePanel({ usage }: { usage: BusinessUsage }) {
  return <Panel title="Workspace usage"><dl className="grid grid-cols-2 gap-5 sm:grid-cols-3 xl:grid-cols-5">{usageFields.map(item => <div key={item.key} className="border-l-2 border-moss/20 pl-3"><dt className="text-xs text-ink/50">{item.label}</dt><dd className="mt-2 text-xl font-semibold">{formatCount(usage[item.key] as number)}</dd></div>)}</dl></Panel>;
}

function useRefreshPlatform() {
  const client = useQueryClient();
  return () => client.invalidateQueries({ queryKey: ["platform"] });
}

function StatusControl({ business }: { business: BusinessSummary }) {
  const refresh = useRefreshPlatform();
  const [review, setReview] = useState(false);
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const target = business.status === "SUSPENDED" ? "ACTIVE" : "SUSPENDED";
  const label = target === "SUSPENDED" ? "Suspend business" : "Reactivate business";
  const mutation = useMutation({ mutationFn: () => platformApi.setStatus(business.id, { status: target, reason: reason.trim() }), onSuccess: async () => { setReview(false); setReason(""); setConfirmation(""); await refresh(); } });
  function submit(event: FormEvent) { event.preventDefault(); if (confirmation === business.name && reason.trim() && !mutation.isPending) mutation.mutate(); }
  return <Panel title="Workspace access"><p className="text-sm leading-6 text-ink/60">{business.status === "DELETED" ? "This workspace has been deleted. Access cannot be changed here." : target === "SUSPENDED" ? "Suspending this business blocks workspace access for its members. Existing business data is preserved." : "Reactivating restores workspace access for existing members. Review the subscription record separately."}</p><div className="mt-4"><MutationFeedback mutation={mutation} success="Business access updated." /></div>
    {business.status !== "DELETED" ? review ? <form onSubmit={submit} className="mt-4 space-y-4 rounded-lg border border-coral/25 bg-coral/[0.03] p-4"><h3 className="font-semibold">Confirm: {label.toLowerCase()}</h3><fieldset disabled={mutation.isPending} className="space-y-4"><Field label="Reason for access change"><textarea className={textAreaClass} required maxLength={1000} value={reason} onChange={event => setReason(event.target.value)} placeholder="Explain why this access change is needed" /></Field><Field label={`Type ${business.name} to confirm`}><TextInput required autoComplete="off" value={confirmation} onChange={event => setConfirmation(event.target.value)} /></Field><div className="flex flex-wrap gap-2"><PrimaryButton type="submit" className={target === "SUSPENDED" ? "bg-[#ad4534]" : ""} disabled={mutation.isPending || !reason.trim() || confirmation !== business.name}>{mutation.isPending ? "Updating…" : label}</PrimaryButton><SecondaryButton type="button" disabled={mutation.isPending} onClick={() => { setReview(false); setReason(""); setConfirmation(""); mutation.reset(); }}>Cancel</SecondaryButton></div></fieldset></form> : <SecondaryButton className="mt-4" type="button" onClick={() => { mutation.reset(); setReview(true); }}>Review {target === "SUSPENDED" ? "suspension" : "reactivation"}</SecondaryButton> : null}
  </Panel>;
}

function SubscriptionForm({ business }: { business: BusinessSummary }) {
  const refresh = useRefreshPlatform();
  const [plan, setPlan] = useState(business.plan);
  const [status, setStatus] = useState(business.subscriptionStatus);
  const [end, setEnd] = useState(toLocalDateTime(business.subscriptionEndsAt));
  const [reason, setReason] = useState("");
  const [saved, setSaved] = useState(false);
  const mutation = useMutation({ mutationFn: () => platformApi.setSubscription(business.id, { plan, subscriptionStatus: status, subscriptionEndsAt: end ? new Date(end).toISOString() : null, reason: reason.trim() }), onSuccess: async () => { setReason(""); setSaved(true); await refresh(); } });
  function submit(event: FormEvent) { event.preventDefault(); if (reason.trim() && !mutation.isPending) { setSaved(false); mutation.mutate(); } }
  return <Panel title="Update subscription record"><form onSubmit={submit} className="space-y-4"><fieldset disabled={mutation.isPending} className="space-y-4"><div className="grid gap-3 sm:grid-cols-2"><Field label="Recorded plan"><SelectInput value={plan} onChange={event => { setPlan(event.target.value); setSaved(false); }}>{plans.map(value => <option key={value}>{value}</option>)}</SelectInput></Field><Field label="Recorded subscription status"><SelectInput value={status} onChange={event => { setStatus(event.target.value); setSaved(false); }}>{subscriptionStatuses.map(value => <option key={value}>{value}</option>)}</SelectInput></Field></div><Field label="Subscription end (your local time)"><TextInput type="datetime-local" value={end} onChange={event => { setEnd(event.target.value); setSaved(false); }} /></Field><p className="text-xs text-ink/45">Leave the end date empty if no expiry has been agreed.</p><Field label="Reason for subscription update"><textarea className={textAreaClass} required maxLength={1000} value={reason} onChange={event => { setReason(event.target.value); setSaved(false); }} placeholder="Record the agreement or correction" /></Field><MutationFeedback mutation={{ ...mutation, isSuccess: saved }} success="Subscription record updated." /><PrimaryButton type="submit" disabled={mutation.isPending || !reason.trim()}>{mutation.isPending ? "Saving…" : "Save subscription record"}</PrimaryButton></fieldset></form></Panel>;
}

function NotesPanel({ data, editable }: { data: BusinessDetail; editable: boolean }) {
  const refresh = useRefreshPlatform();
  const [notes, setNotes] = useState(data.internalNotes ?? "");
  const [reason, setReason] = useState("");
  const mutation = useMutation({ mutationFn: () => platformApi.setNotes(data.business.id, { notes, reason: reason.trim() }), onSuccess: async () => { setReason(""); await refresh(); } });
  function submit(event: FormEvent) { event.preventDefault(); if (reason.trim() && !mutation.isPending) mutation.mutate(); }
  return <Panel title="Internal platform notes">{editable ? <form onSubmit={submit} className="space-y-4"><fieldset disabled={mutation.isPending} className="space-y-4"><Field label="Internal notes"><textarea className={textAreaClass} rows={4} maxLength={10000} value={notes} onChange={event => setNotes(event.target.value)} placeholder="Operational context for authorized platform staff" /></Field><Field label="Reason for notes update"><TextInput required maxLength={1000} value={reason} onChange={event => setReason(event.target.value)} /></Field><p className="text-xs text-ink/45">Notes are visible to authorized platform staff. Changes and their reason are recorded in the platform audit trail.</p><MutationFeedback mutation={mutation} success="Internal notes updated." /><PrimaryButton type="submit" disabled={mutation.isPending || !reason.trim()}>{mutation.isPending ? "Saving…" : "Save internal notes"}</PrimaryButton></fieldset></form> : <p className="whitespace-pre-wrap text-sm leading-6 text-ink/60">{data.internalNotes || "No internal notes recorded."}</p>}</Panel>;
}

function MembersPanel({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const members = useQuery({ queryKey: ["platform", "business", id, "members", page], queryFn: () => platformApi.members(id, page) });
  return <Panel title="Workspace members"><QueryFeedback queries={[members]} />{members.data && !members.error ? <>{members.data.content.length ? <div className="overflow-x-auto"><table className={tableClass}><thead><tr><th>Member</th><th>Workspace role</th><th>Account status</th><th>Joined</th></tr></thead><tbody>{members.data.content.map(member => <tr key={member.userId}><td><p className="font-medium">{member.fullName || "Unnamed member"}</p><p className="mt-1 text-xs text-ink/50">{member.email}</p></td><td className="text-xs">{member.role.replaceAll("_", " ")}</td><td><StatusBadge value={member.active ? "ACTIVE" : "INACTIVE"} /></td><td className="text-xs text-ink/50">{formatTimestamp(member.joinedAt)}</td></tr>)}</tbody></table></div> : <PlatformEmpty title="No members recorded" />}<PlatformPager data={members.data} page={page} onChange={setPage} busy={members.isFetching} /></> : null}</Panel>;
}

function BusinessImportsPanel({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const imports = useQuery({ queryKey: ["platform", "business", id, "imports", page], queryFn: () => platformApi.businessImports(id, page) });
  return <Panel title="Client import activity"><QueryFeedback queries={[imports]} />{imports.data && !imports.error ? <><ImportTable rows={imports.data.content} showBusiness={false} /><PlatformPager data={imports.data} page={page} onChange={setPage} busy={imports.isFetching} /></> : null}</Panel>;
}

export function toLocalDateTime(iso: string | null) {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
