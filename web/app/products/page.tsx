"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { FormEvent, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { Field, PrimaryButton, TextInput } from "@/components/FormControls";
import { LoadingState } from "@/components/LoadingState";
import { Pagination } from "@/components/Pagination";
import { MutationFeedback } from "@/components/QueryFeedback";
import { Panel } from "@/components/Panel";
import { api, Page, Product } from "@/lib/api";
import { Permission, useMoney } from "@/lib/business";

const blank = {
  sku: "",
  name: "",
  categoryName: "",
  brandName: "",
  unitCode: "PCS",
  barcode: "",
  hsnCode: "",
  gstPercentage: "5",
  defaultPurchasePrice: "",
  defaultSalesPrice: "",
  reorderPoint: "10",
  safetyStock: "5",
  leadTimeDays: "7",
  minimumOrderQuantity: "1"
};

export default function ProductsPage() {
  const money = useMoney();
  const queryClient = useQueryClient();
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [form, setForm] = useState(blank);
  const products = useQuery({ queryKey: ["products", query, page], queryFn: () => api<Page<Product>>(`/api/products?size=25&page=${page}${query ? `&query=${encodeURIComponent(query)}` : ""}`) });
  const create = useMutation({
    mutationFn: () => api<Product>("/api/products", {
      method: "POST",
      body: JSON.stringify({
        ...form,
        gstPercentage: Number(form.gstPercentage || 0),
        defaultPurchasePrice: Number(form.defaultPurchasePrice || 0),
        defaultSalesPrice: Number(form.defaultSalesPrice || 0),
        reorderPoint: Number(form.reorderPoint || 0),
        safetyStock: Number(form.safetyStock || 0),
        leadTimeDays: Number(form.leadTimeDays || 0),
        minimumOrderQuantity: Number(form.minimumOrderQuantity || 0)
      })
    }),
    onSuccess: () => {
      setForm(blank);
      queryClient.invalidateQueries({ queryKey: ["products"] });
    }
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate();
  }

  return (
    <AppShell title="Products">
      <div className="grid gap-4 xl:grid-cols-[1fr_380px]">
        <Panel title="Product Catalog" action={<input className="focus-ring h-10 rounded border border-ink/15 px-3 text-sm" placeholder="Search products" value={query} onChange={(e) => { setQuery(e.target.value); setPage(0); }} />}>
          {products.isLoading ? <LoadingState /> : products.error ? <ErrorState error={products.error} /> : products.data?.content.length ? (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-ink/50"><tr><th className="py-2">Product</th><th>Category</th><th>Stock</th><th>Cost</th><th>Price</th><th>Barcode</th></tr></thead>
                <tbody>
                  {products.data.content.map((product) => (
                    <tr key={product.id} className="border-t border-ink/10">
                      <td className="py-3"><div className="font-medium">{product.name}</div><div className="text-xs text-ink/45">{product.sku}</div></td>
                      <td>{product.categoryName ?? "-"}</td>
                      <td>{product.currentStock}</td>
                      <td>{money(product.defaultPurchasePrice)}</td>
                      <td>{money(product.defaultSalesPrice)}</td>
                      <td>{product.barcode ?? "-"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : <EmptyState label="No products yet" />}
        <Pagination data={products.data} page={page} onChange={setPage} />
        </Panel>

        <Permission permission="products.create"><Panel title="Add Product" action={<Plus size={18} />}>
          <form onSubmit={submit} className="space-y-3">
            <Field label="Name"><TextInput value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label="SKU"><TextInput value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value })} /></Field>
              <Field label="Unit"><TextInput value={form.unitCode} onChange={(e) => setForm({ ...form, unitCode: e.target.value })} required /></Field>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <Field label="Category"><TextInput value={form.categoryName} onChange={(e) => setForm({ ...form, categoryName: e.target.value })} /></Field>
              <Field label="Brand"><TextInput value={form.brandName} onChange={(e) => setForm({ ...form, brandName: e.target.value })} /></Field>
            </div>
            <Field label="Barcode"><TextInput value={form.barcode} onChange={(e) => setForm({ ...form, barcode: e.target.value })} /></Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label="Cost"><TextInput value={form.defaultPurchasePrice} onChange={(e) => setForm({ ...form, defaultPurchasePrice: e.target.value })} type="number" step="0.01" /></Field>
              <Field label="Price"><TextInput value={form.defaultSalesPrice} onChange={(e) => setForm({ ...form, defaultSalesPrice: e.target.value })} type="number" step="0.01" /></Field>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <Field label="GST %"><TextInput value={form.gstPercentage} onChange={(e) => setForm({ ...form, gstPercentage: e.target.value })} type="number" /></Field>
              <Field label="Reorder point"><TextInput value={form.reorderPoint} onChange={(e) => setForm({ ...form, reorderPoint: e.target.value })} type="number" /></Field>
            </div>
            <MutationFeedback mutation={create} success="Product saved." />

            <PrimaryButton className="w-full" disabled={create.isPending}>{create.isPending ? "Saving..." : "Save product"}</PrimaryButton>
          </form>
        </Panel></Permission>
      </div>
    </AppShell>
  );
}
