"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, localDate, newOperationId, Page, Product, Warehouse } from "@/lib/api";
import { Permission, refreshBusiness, useAccess, useMoney } from "@/lib/business";
import { Panel } from "./Panel";
import { Field, PrimaryButton, SelectInput, TextInput } from "./FormControls";
import { Pagination } from "./Pagination";
import { RecordPicker } from "./RecordPicker";
import { MutationFeedback, QueryFeedback } from "./QueryFeedback";

type Invoice = { id: string; invoiceNumber: string; invoiceDate: string; totalAmount: number | null };
type Stock = { productId: string; warehouseId: string; currentStock: number };

export function InvoiceWorkspace({ kind }: { kind: "sales" | "purchases" }) {
  const sale = kind === "sales";
  const noun = sale ? "sale" : "purchase";
  const money = useMoney();
  const { can } = useAccess();
  const client = useQueryClient();
  const blank = () => ({ partyId: "", warehouseId: "", invoiceNumber: `${sale ? "S" : "P"}-${newOperationId().slice(0, 12)}`, invoiceDate: localDate(), productId: "", quantity: "1", rate: "" });
  const [form, setForm] = useState(blank);
  const [page, setPage] = useState(0);
  const [savedNumber, setSavedNumber] = useState("");
  const invoices = useQuery({ queryKey: [kind, page], queryFn: () => api<Page<Invoice>>(`/api/${kind}?size=25&page=${page}&sort=invoiceDate,desc`) });
  const warehouses = useQuery({ queryKey: ["warehouses"], queryFn: () => api<Warehouse[]>("/api/warehouses"), enabled: can(`${kind}.create`) });
  const stock = useQuery({ queryKey: ["stock", form.productId, form.warehouseId], enabled: sale && !!form.productId && !!form.warehouseId, queryFn: () => api<Stock[]>(`/api/stock/current?productId=${form.productId}&warehouseId=${form.warehouseId}`) });
  const available = stock.data?.reduce((sum, row) => sum + Number(row.currentStock), 0);
  const create = useMutation({
    mutationFn: () => api<Invoice>(`/api/${kind}`, { method: "POST", body: JSON.stringify({ [sale ? "customerId" : "supplierId"]: form.partyId || null, warehouseId: form.warehouseId, invoiceNumber: form.invoiceNumber, invoiceDate: form.invoiceDate, items: [{ productId: form.productId, quantity: Number(form.quantity), rate: Number(form.rate) }] }) }),
    onSuccess: async (invoice) => { setSavedNumber(invoice.invoiceNumber); setForm(blank()); setPage(0); await refreshBusiness(client); }
  });
  return <div className="grid gap-4 xl:grid-cols-[1fr_400px]">
    <Panel title={sale ? "Sales Invoices" : "Purchase Invoices"}>
      <QueryFeedback queries={[invoices]} />
      {invoices.data ? <><div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead><tr><th className="py-2">Invoice</th><th>Date</th><th>Total</th></tr></thead><tbody>{invoices.data.content.map(invoice => <tr key={invoice.id} className="border-t border-ink/10"><td className="py-3">{invoice.invoiceNumber}</td><td>{invoice.invoiceDate}</td><td>{money(invoice.totalAmount)}</td></tr>)}</tbody></table></div>{!invoices.data.content.length ? <p className="py-4 text-sm text-ink/60">No invoices yet.</p> : null}<Pagination data={invoices.data} page={page} onChange={setPage} /></> : null}
    </Panel>
    <Permission permission={`${kind}.create`}><Panel title={`Create ${sale ? "Sale" : "Purchase"}`}>
      <form className="space-y-3" onSubmit={event => { event.preventDefault(); if (!create.isPending) create.mutate(); }}>
        <RecordPicker<{id: string; name: string}> path={sale ? "/api/customers" : "/api/suppliers"} label={sale ? "Customer" : "Supplier"} value={form.partyId} optionalLabel={sale ? "Walk-in" : "No supplier"} onChange={row => setForm({ ...form, partyId: row?.id ?? "" })} />
        <QueryFeedback queries={[warehouses]} />
        <Field label="Warehouse"><SelectInput required value={form.warehouseId} onChange={event => setForm({ ...form, warehouseId: event.target.value })}><option value="">Select warehouse</option>{warehouses.data?.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}</SelectInput></Field>
        <div className="grid grid-cols-2 gap-3"><Field label="Invoice no"><TextInput required value={form.invoiceNumber} onChange={event => setForm({ ...form, invoiceNumber: event.target.value })} /></Field><Field label="Date"><TextInput required type="date" value={form.invoiceDate} onChange={event => setForm({ ...form, invoiceDate: event.target.value })} /></Field></div>
        <RecordPicker<Product> path="/api/products" label="Product" value={form.productId} onChange={product => setForm({ ...form, productId: product?.id ?? "", rate: String((sale ? product?.defaultSalesPrice : product?.defaultPurchasePrice) ?? "") })} />
        {sale && form.productId && form.warehouseId ? <><QueryFeedback queries={[stock]} />{stock.data ? <p className="text-sm">Available in selected warehouse: <strong>{available}</strong></p> : null}</> : null}
        <div className="grid grid-cols-2 gap-3"><Field label="Quantity"><TextInput required type="number" min="0.001" step="0.001" value={form.quantity} onChange={event => setForm({ ...form, quantity: event.target.value })} /></Field><Field label="Rate"><TextInput required type="number" min="0" step="0.01" value={form.rate} onChange={event => setForm({ ...form, rate: event.target.value })} /></Field></div>
        {sale && available !== undefined && Number(form.quantity) > available ? <p className="text-sm text-amber-800">Quantity exceeds this warehouse&apos;s stock. Posting requires the workspace negative-stock policy to allow it.</p> : null}
        <MutationFeedback mutation={create} success={`Saved invoice ${savedNumber}. Ready for another ${noun}.`} />
        <PrimaryButton className="w-full" disabled={create.isPending || warehouses.isLoading || !!warehouses.error || (sale && (!!stock.error || stock.isFetching))}>{create.isPending ? "Saving…" : `Create ${noun}`}</PrimaryButton>
      </form>
    </Panel></Permission>
  </div>;
}
