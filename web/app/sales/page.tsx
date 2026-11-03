"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, useState } from "react";
import Link from "next/link";
import { AppShell } from "@/components/AppShell";
import { Field, PrimaryButton, SelectInput, TextInput } from "@/components/FormControls";
import { Panel } from "@/components/Panel";
import { api, money, Page, Product, Warehouse } from "@/lib/api";

type Invoice = { id: string; invoiceNumber: string; invoiceDate: string; totalAmount: number };
type Customer = { id: string; name: string };

export default function SalesPage() {
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ customerId: "", warehouseId: "", invoiceNumber: `S-${Date.now()}`, invoiceDate: new Date().toISOString().slice(0, 10), productId: "", quantity: "1", rate: "" });
  const invoices = useQuery({ queryKey: ["sales"], queryFn: () => api<Page<Invoice>>("/api/sales?size=50") });
  const products = useQuery({ queryKey: ["products", "sales"], queryFn: () => api<Page<Product>>("/api/products?size=200") });
  const warehouses = useQuery({ queryKey: ["warehouses"], queryFn: () => api<Warehouse[]>("/api/warehouses") });
  const customers = useQuery({ queryKey: ["customers"], queryFn: () => api<Page<Customer>>("/api/customers?size=100") });
  const create = useMutation({
    mutationFn: () => api("/api/sales", {
      method: "POST",
      body: JSON.stringify({
        customerId: form.customerId || null,
        warehouseId: form.warehouseId,
        invoiceNumber: form.invoiceNumber,
        invoiceDate: form.invoiceDate,
        items: [{ productId: form.productId, quantity: Number(form.quantity), rate: Number(form.rate || 0) }]
      })
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["sales"] });
      queryClient.invalidateQueries({ queryKey: ["stock"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard"] });
    }
  });

  function productChanged(id: string) {
    const product = products.data?.content.find((item) => item.id === id);
    setForm({ ...form, productId: id, rate: product ? String(product.defaultSalesPrice) : form.rate });
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate();
  }

  return (
    <AppShell title="Sales">
      <nav aria-label="Sales views" className="mb-5 flex flex-wrap gap-6 border-b border-ink/15 text-sm">
        <Link className="border-b-2 border-moss py-3 font-semibold text-moss" aria-current="page" href="/sales">Sales invoices</Link>
        <Link className="py-3 text-ink/65" href="/sales/retail-counter">Retail counter sales</Link>
      </nav>
      <div className="grid gap-4 xl:grid-cols-[1fr_400px]">
        <Panel title="Sales Invoices">
          <table className="w-full text-left text-sm">
            <thead className="text-ink/50"><tr><th className="py-2">Invoice</th><th>Date</th><th>Total</th></tr></thead>
            <tbody>{(invoices.data?.content ?? []).map((invoice) => <tr key={invoice.id} className="border-t border-ink/10"><td className="py-3 font-medium">{invoice.invoiceNumber}</td><td>{invoice.invoiceDate}</td><td>{money(invoice.totalAmount)}</td></tr>)}</tbody>
          </table>
        </Panel>
        <Panel title="Create Sale">
          <form onSubmit={submit} className="space-y-3">
            <Field label="Customer"><SelectInput value={form.customerId} onChange={(e) => setForm({ ...form, customerId: e.target.value })}><option value="">Walk-in</option>{(customers.data?.content ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</SelectInput></Field>
            <Field label="Warehouse"><SelectInput value={form.warehouseId} onChange={(e) => setForm({ ...form, warehouseId: e.target.value })} required><option value="">Select</option>{(warehouses.data ?? []).map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}</SelectInput></Field>
            <div className="grid grid-cols-2 gap-3"><Field label="Invoice no"><TextInput value={form.invoiceNumber} onChange={(e) => setForm({ ...form, invoiceNumber: e.target.value })} required /></Field><Field label="Date"><TextInput type="date" value={form.invoiceDate} onChange={(e) => setForm({ ...form, invoiceDate: e.target.value })} required /></Field></div>
            <Field label="Product"><SelectInput value={form.productId} onChange={(e) => productChanged(e.target.value)} required><option value="">Select</option>{(products.data?.content ?? []).map((p) => <option key={p.id} value={p.id}>{p.name} ({p.currentStock})</option>)}</SelectInput></Field>
            <div className="grid grid-cols-2 gap-3"><Field label="Quantity"><TextInput type="number" value={form.quantity} onChange={(e) => setForm({ ...form, quantity: e.target.value })} required /></Field><Field label="Rate"><TextInput type="number" step="0.01" value={form.rate} onChange={(e) => setForm({ ...form, rate: e.target.value })} required /></Field></div>
            {create.error ? <p className="text-sm text-coral">{create.error.message}</p> : null}
            <PrimaryButton className="w-full" disabled={create.isPending}>Create sale</PrimaryButton>
          </form>
        </Panel>
      </div>
    </AppShell>
  );
}
