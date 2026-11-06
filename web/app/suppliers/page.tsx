"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { Field, PrimaryButton, TextInput } from "@/components/FormControls";
import { Permission, useMoney } from "@/lib/business";
import { Pagination } from "@/components/Pagination";
import { MutationFeedback, QueryFeedback } from "@/components/QueryFeedback";
import { Panel } from "@/components/Panel";
import { api, Page } from "@/lib/api";

type Supplier = { id: string; name: string; phone?: string; email?: string; gstin?: string; creditDays: number; payable: number };

export default function SuppliersPage() {
  const money = useMoney();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ name: "", phone: "", email: "", gstin: "", creditDays: "0" });
  const suppliers = useQuery({ queryKey: ["suppliers", page, search], queryFn: () => api<Page<Supplier>>(`/api/suppliers?size=25&page=${page}&query=${encodeURIComponent(search)}`) });
  const create = useMutation({
    mutationFn: () => api("/api/suppliers", { method: "POST", body: JSON.stringify({ ...form, creditDays: Number(form.creditDays || 0) }) }),
    onSuccess: () => {
      setForm({ name: "", phone: "", email: "", gstin: "", creditDays: "0" });
      queryClient.invalidateQueries({ queryKey: ["suppliers"] });
    }
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate();
  }

  return (
    <AppShell title="Suppliers">
      <div className="grid gap-4 xl:grid-cols-[1fr_360px]">
        <Panel title="Supplier Directory" action={<input aria-label="Search suppliers" className="rounded border p-2" placeholder="Search suppliers" value={search} onChange={event => { setSearch(event.target.value); setPage(0); }} />}>
          <QueryFeedback queries={[suppliers]} />
          <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-ink/50"><tr><th className="py-2">Name</th><th>GSTIN</th><th>Payable</th><th>Credit days</th><th>Contact</th></tr></thead>
            <tbody>{(suppliers.data?.content ?? []).map((supplier) => <tr key={supplier.id} className="border-t border-ink/10"><td className="py-3 font-medium">{supplier.name}</td><td>{supplier.gstin ?? "-"}</td><td className="font-semibold text-moss">{money(supplier.payable)}</td><td>{supplier.creditDays}</td><td>{supplier.phone ?? supplier.email ?? "-"}</td></tr>)}</tbody>
          </table></div>
          <Pagination data={suppliers.data} page={page} onChange={setPage} />
        </Panel>
        <Permission permission="suppliers.create"><Panel title="Add Supplier">
          <form onSubmit={submit} className="space-y-3">
            <Field label="Name"><TextInput value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></Field>
            <Field label="Phone"><TextInput value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></Field>
            <Field label="Email"><TextInput value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} type="email" /></Field>
            <Field label="GSTIN"><TextInput value={form.gstin} onChange={(e) => setForm({ ...form, gstin: e.target.value })} /></Field>
            <Field label="Credit days"><TextInput value={form.creditDays} onChange={(e) => setForm({ ...form, creditDays: e.target.value })} type="number" /></Field>
            <MutationFeedback mutation={create} success="Supplier saved." />

            <PrimaryButton className="w-full" disabled={create.isPending}>Save supplier</PrimaryButton>
          </form>
        </Panel></Permission>
      </div>
    </AppShell>
  );
}
