"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, CheckCircle2, IndianRupee } from "lucide-react";
import { FormEvent, useRef, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { Field, PrimaryButton, SecondaryButton, TextInput } from "@/components/FormControls";
import { Pagination } from "@/components/Pagination";
import { MutationFeedback, QueryFeedback } from "@/components/QueryFeedback";
import { Panel } from "@/components/Panel";
import { StatCard } from "@/components/StatCard";
import { api, localDate, newOperationId, Page } from "@/lib/api";
import { Permission, useAccess, useMoney } from "@/lib/business";

type Customer = { id: string; name: string; phone?: string; email?: string; gstin?: string; creditLimit: number; outstanding: number | null };
type Reminder = {
  id: string;
  customerId: string;
  customerName: string;
  outstanding: number;
  amountDue: number;
  reminderDate: string;
  reminderTime?: string;
  status: "OPEN" | "PAID" | "CANCELLED";
  dueStatus: "OVERDUE" | "DUE_TODAY" | "UPCOMING" | "PAID" | "CANCELLED";
  notes?: string;
};

const dateInput = localDate;

function dueClass(status: Reminder["dueStatus"]) {
  if (status === "OVERDUE") return "bg-coral/10 text-coral";
  if (status === "DUE_TODAY") return "bg-amber/20 text-ink";
  if (status === "PAID") return "bg-mint text-moss";
  return "bg-ink/[0.06] text-ink/60";
}

export default function CustomersPage() {
  const money = useMoney();
  const { can } = useAccess();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const paymentAttempt = useRef({ body: "", key: "" });
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ name: "", phone: "", email: "", gstin: "", creditLimit: "" });
  const [selectedCustomer, setSelectedCustomer] = useState<Customer | null>(null);
  const [actionMode, setActionMode] = useState<"reminder" | "payment">("reminder");
  const [reminderForm, setReminderForm] = useState({ reminderDate: dateInput(1), reminderTime: "10:00", amountDue: "", notes: "" });
  const [paymentForm, setPaymentForm] = useState({ amount: "", paymentDate: dateInput(), mode: "UPI", referenceNumber: "", notes: "", reminderId: "" });

  const customers = useQuery({ queryKey: ["customers", page, search], queryFn: () => api<Page<Customer>>(`/api/customers?size=25&page=${page}&query=${encodeURIComponent(search)}`) });
  const reminders = useQuery({ queryKey: ["payment-reminders"], enabled: can("customers.view_outstanding"), queryFn: () => api<Reminder[]>("/api/customers/payment-reminders") });

  const totals = useQuery({ queryKey: ["customers-summary"], enabled: can("customers.view_outstanding"), queryFn: () => api<{ outstandingReceivables: number }>("/api/customers/summary") });

  const create = useMutation({
    mutationFn: () => api("/api/customers", { method: "POST", body: JSON.stringify({ ...form, creditLimit: Number(form.creditLimit || 0) }) }),
    onSuccess: () => {
      setForm({ name: "", phone: "", email: "", gstin: "", creditLimit: "" });
      queryClient.invalidateQueries({ queryKey: ["customers"] });
    }
  });

  const createReminder = useMutation({
    mutationFn: () => {
      if (!selectedCustomer) throw new Error("Select a customer first");
      return api(`/api/customers/${selectedCustomer.id}/payment-reminders`, {
        method: "POST",
        body: JSON.stringify({ ...reminderForm, amountDue: Number(reminderForm.amountDue || selectedCustomer.outstanding || 0) })
      });
    },
    onSuccess: () => {
      setReminderForm({ reminderDate: dateInput(1), reminderTime: "10:00", amountDue: "", notes: "" });
      queryClient.invalidateQueries({ queryKey: ["payment-reminders"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard", "actions"] });
    }
  });

  const recordPayment = useMutation({
    mutationFn: () => {
      if (!selectedCustomer) throw new Error("Select a customer first");
      const body = JSON.stringify({
        amount: Number(paymentForm.amount || 0), paymentDate: paymentForm.paymentDate,
        mode: paymentForm.mode, referenceNumber: paymentForm.referenceNumber,
        notes: paymentForm.notes, reminderId: paymentForm.reminderId || undefined
      });
      const identity = selectedCustomer.id + body;
      if (paymentAttempt.current.body !== identity) paymentAttempt.current = { body: identity, key: newOperationId() };
      return api(`/api/customers/${selectedCustomer.id}/payments`, { method: "POST", body, headers: { "Idempotency-Key": paymentAttempt.current.key } });
    },
    onSuccess: () => {
      setSelectedCustomer(null);
      paymentAttempt.current = { body: "", key: "" };
      setPaymentForm({ amount: "", paymentDate: dateInput(), mode: "UPI", referenceNumber: "", notes: "", reminderId: "" });
      queryClient.invalidateQueries({ queryKey: ["customers"] });
      queryClient.invalidateQueries({ queryKey: ["payment-reminders"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard", "summary"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard", "actions"] });
    }
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate();
  }

  function selectForReminder(customer: Customer) {
    setSelectedCustomer(customer);
    setActionMode("reminder");
    setReminderForm({ reminderDate: dateInput(1), reminderTime: "10:00", amountDue: String(customer.outstanding || ""), notes: "Payment follow-up" });
  }

  function selectForPayment(customer: Customer, reminder?: Reminder) {
    setSelectedCustomer(customer);
    setActionMode("payment");
    setPaymentForm({
      amount: String(reminder?.amountDue || customer.outstanding || ""),
      paymentDate: dateInput(),
      mode: "UPI",
      referenceNumber: "",
      notes: reminder ? `Received against reminder for ${reminder.reminderDate}` : "Payment received from party",
      reminderId: reminder?.id ?? ""
    });
  }

  function nextReminder(customerId: string) {
    return (reminders.data ?? [])
      .filter((reminder) => reminder.customerId === customerId && reminder.status === "OPEN")
      .sort((a, b) => `${a.reminderDate} ${a.reminderTime ?? ""}`.localeCompare(`${b.reminderDate} ${b.reminderTime ?? ""}`))[0];
  }

  const customerRows = customers.data?.content ?? [];
  const openReminders = (reminders.data ?? []).filter((reminder) => reminder.status === "OPEN");
  const overdueReminders = openReminders.filter((reminder) => reminder.dueStatus === "OVERDUE" || reminder.dueStatus === "DUE_TODAY");
  const totalOutstanding = totals.data?.outstandingReceivables;
  const dueAmount = overdueReminders.reduce((sum, reminder) => sum + Number(reminder.amountDue || 0), 0);

  return (
    <AppShell title="Customers">
      <div className="space-y-4">
        <QueryFeedback queries={[customers, reminders, totals]} />
        <MutationFeedback mutation={recordPayment} success="Payment recorded. Outstanding balances refreshed." />
        <Permission permission="customers.view_outstanding"><div className="grid gap-4 md:grid-cols-3">
          <StatCard label="Total receivables" value={money(totalOutstanding)} icon={<IndianRupee size={18} />} />
          <StatCard label="Due follow-ups" value={overdueReminders.length} icon={<Bell size={18} />} tone="amber" />
          <StatCard label="Due reminder amount" value={money(dueAmount)} icon={<CheckCircle2 size={18} />} tone="moss" />
        </div></Permission>

        <div className="grid gap-4 xl:grid-cols-[1fr_400px]">
          <Panel title="Customer Ledger" action={<input aria-label="Search customers" className="rounded border p-2" placeholder="Search customers" value={search} onChange={event => { setSearch(event.target.value); setPage(0); }} />}>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-ink/50">
                  <tr>
                    <th className="py-2">Name</th>
                    <th>GSTIN</th>
                    <th>Credit limit</th>
                    <th>Outstanding</th>
                    <th>Next reminder</th>
                    <th className="text-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {customerRows.map((customer) => {
                    const reminder = nextReminder(customer.id);
                    return (
                      <tr key={customer.id} className="border-t border-ink/10 align-top">
                        <td className="py-3 font-medium">{customer.name}</td>
                        <td>{customer.gstin ?? "-"}</td>
                        <td>{money(customer.creditLimit)}</td>
                        <td className="font-semibold text-moss">{money(customer.outstanding)}</td>
                        <td>
                          {reminder ? (
                            <div>
                              <span className={`rounded px-2 py-1 text-xs font-semibold ${dueClass(reminder.dueStatus)}`}>{reminder.dueStatus.replace("_", " ")}</span>
                              <div className="mt-1 text-xs text-ink/55">{reminder.reminderDate} {reminder.reminderTime ?? ""}</div>
                            </div>
                          ) : <span className="text-ink/40">Not set</span>}
                        </td>
                        <td>
                          <Permission permission="customers.update"><div className="flex justify-end gap-2">
                            <SecondaryButton type="button" onClick={() => selectForReminder(customer)}>Set reminder</SecondaryButton>
                            <PrimaryButton type="button" onClick={() => selectForPayment(customer)}>Payment received</PrimaryButton>
                          </div></Permission>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <Pagination data={customers.data} page={page} onChange={setPage} />
          </Panel>

          <div className="space-y-4">
            <Permission permission="customers.update"><Panel title={actionMode === "reminder" ? "Set Payment Reminder" : "Record Payment Received"}>
              {selectedCustomer ? (
                actionMode === "reminder" ? (
                  <form onSubmit={(event) => { event.preventDefault(); createReminder.mutate(); }} className="space-y-3">
                    <div className="rounded bg-ink/[0.03] p-3 text-sm">
                      <div className="font-semibold">{selectedCustomer.name}</div>
                      <div className="text-ink/55">Outstanding: {money(selectedCustomer.outstanding)}</div>
                    </div>
                    <Field label="Reminder date"><TextInput type="date" value={reminderForm.reminderDate} onChange={(e) => setReminderForm({ ...reminderForm, reminderDate: e.target.value })} required /></Field>
                    <Field label="Reminder time"><TextInput type="time" value={reminderForm.reminderTime} onChange={(e) => setReminderForm({ ...reminderForm, reminderTime: e.target.value })} /></Field>
                    <Field label="Amount to collect"><TextInput type="number" min="0.01" step="0.01" value={reminderForm.amountDue} onChange={(e) => setReminderForm({ ...reminderForm, amountDue: e.target.value })} required /></Field>
                    <label className="block">
                      <span className="mb-1 block text-sm font-medium text-ink/70">Notes</span>
                      <textarea className="focus-ring min-h-20 w-full rounded border border-ink/15 bg-white px-3 py-2 text-sm text-ink" value={reminderForm.notes} onChange={(e) => setReminderForm({ ...reminderForm, notes: e.target.value })} />
                    </label>
                    <MutationFeedback mutation={createReminder} success="Reminder saved." />

                    <PrimaryButton className="w-full" disabled={createReminder.isPending}>Save reminder</PrimaryButton>
                  </form>
                ) : (
                  <form onSubmit={(event) => { event.preventDefault(); recordPayment.mutate(); }} className="space-y-3">
                    <div className="rounded bg-ink/[0.03] p-3 text-sm">
                      <div className="font-semibold">{selectedCustomer.name}</div>
                      <div className="text-ink/55">Outstanding: {money(selectedCustomer.outstanding)}</div>
                    </div>
                    <Field label="Amount received"><TextInput type="number" min="0.01" step="0.01" value={paymentForm.amount} onChange={(e) => setPaymentForm({ ...paymentForm, amount: e.target.value })} required /></Field>
                    <Field label="Payment date"><TextInput type="date" value={paymentForm.paymentDate} onChange={(e) => setPaymentForm({ ...paymentForm, paymentDate: e.target.value })} required /></Field>
                    <Field label="Payment mode"><TextInput value={paymentForm.mode} onChange={(e) => setPaymentForm({ ...paymentForm, mode: e.target.value })} placeholder="Cash, UPI, Bank transfer, cheque" /></Field>
                    <Field label="Reference number"><TextInput value={paymentForm.referenceNumber} onChange={(e) => setPaymentForm({ ...paymentForm, referenceNumber: e.target.value })} /></Field>
                    <label className="block">
                      <span className="mb-1 block text-sm font-medium text-ink/70">Notes</span>
                      <textarea className="focus-ring min-h-20 w-full rounded border border-ink/15 bg-white px-3 py-2 text-sm text-ink" value={paymentForm.notes} onChange={(e) => setPaymentForm({ ...paymentForm, notes: e.target.value })} />
                    </label>

                    <PrimaryButton className="w-full" disabled={recordPayment.isPending}>Mark payment received</PrimaryButton>
                  </form>
                )
              ) : (
                <p className="text-sm text-ink/55">Select a customer from the ledger to set a reminder or record a received payment.</p>
              )}
            </Panel></Permission>

            <Permission permission="customers.view_outstanding"><Panel title="Payment Follow-ups">
              <div className="space-y-3">
                {openReminders.length === 0 ? <p className="text-sm text-ink/55">No open payment reminders.</p> : openReminders.map((reminder) => {
                  const customer = customerRows.find((item) => item.id === reminder.customerId) ?? { id: reminder.customerId, name: reminder.customerName, outstanding: reminder.outstanding, creditLimit: 0 };
                  return (
                    <div key={reminder.id} className="rounded border border-ink/10 p-3">
                      <div className="flex items-start justify-between gap-3">
                        <div>
                          <div className="font-medium">{reminder.customerName}</div>
                          <div className="text-sm text-ink/55">{reminder.reminderDate} {reminder.reminderTime ?? ""}</div>
                        </div>
                        <span className={`rounded px-2 py-1 text-xs font-semibold ${dueClass(reminder.dueStatus)}`}>{reminder.dueStatus.replace("_", " ")}</span>
                      </div>
                      <div className="mt-2 text-sm font-semibold text-moss">{money(reminder.amountDue)}</div>
                      {reminder.notes ? <p className="mt-1 text-sm text-ink/55">{reminder.notes}</p> : null}
                      {can("customers.update") ? <SecondaryButton type="button" className="mt-3 w-full" onClick={() => selectForPayment(customer, reminder)}>Mark received</SecondaryButton> : null}
                    </div>
                  );
                })}
              </div>
            </Panel></Permission>

            <Permission permission="customers.create"><Panel title="Add Customer">
              <form onSubmit={submit} className="space-y-3">
                <Field label="Name"><TextInput value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></Field>
                <Field label="Phone"><TextInput value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></Field>
                <Field label="Email"><TextInput value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} type="email" /></Field>
                <Field label="GSTIN"><TextInput value={form.gstin} onChange={(e) => setForm({ ...form, gstin: e.target.value })} /></Field>
                <Field label="Credit limit"><TextInput value={form.creditLimit} onChange={(e) => setForm({ ...form, creditLimit: e.target.value })} type="number" /></Field>
                <MutationFeedback mutation={create} success="Customer saved." />

                <PrimaryButton className="w-full" disabled={create.isPending}>Save customer</PrimaryButton>
              </form>
            </Panel></Permission>
          </div>
        </div>
      </div>
    </AppShell>
  );
}
