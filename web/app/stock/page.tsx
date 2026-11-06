"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormEvent, useMemo, useRef, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { ErrorState } from "@/components/ErrorState";
import { Field, PrimaryButton, SelectInput, TextInput } from "@/components/FormControls";
import { LoadingState } from "@/components/LoadingState";
import { RecordPicker } from "@/components/RecordPicker";
import { MutationFeedback, QueryFeedback } from "@/components/QueryFeedback";
import { Panel } from "@/components/Panel";
import { api, newOperationId, Product, Warehouse } from "@/lib/api";
import { Permission, useAccess, useMoney } from "@/lib/business";

type StockRow = { productId: string; productName: string; warehouseId: string; warehouseName: string; currentStock: number; stockValue: number | null };

export default function StockPage() {
  const money = useMoney();
  const { can } = useAccess();
  const attempt = useRef({ body: "", key: "" });
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ productId: "", warehouseId: "", quantityDelta: "", rate: "", notes: "Manual stock count adjustment" });
  const stock = useQuery({ queryKey: ["stock"], queryFn: () => api<StockRow[]>("/api/stock/current") });
  const warehouses = useQuery({ queryKey: ["warehouses"], enabled: can("stock.adjust"), queryFn: () => api<Warehouse[]>("/api/warehouses") });
  const adjustment = useMutation({
    mutationFn: () => {
      const body = JSON.stringify({ ...form, quantityDelta: Number(form.quantityDelta), rate: Number(form.rate || 0) });
      if (attempt.current.body !== body) attempt.current = { body, key: newOperationId() };
      return api("/api/stock/adjustment", { method: "POST", body, headers: { "Idempotency-Key": attempt.current.key } });
    },
    onSuccess: () => {
      attempt.current = { body: "", key: "" };
      setForm({ productId: "", warehouseId: "", quantityDelta: "", rate: "", notes: "Manual stock count adjustment" });
      queryClient.invalidateQueries({ queryKey: ["stock"] });
      queryClient.invalidateQueries({ queryKey: ["products"] });
    }
  });
  const totalValue = useMemo(() => !stock.data || stock.data.some(row => row.stockValue == null) ? null : stock.data.reduce((sum, row) => sum + Number(row.stockValue), 0), [stock.data]);

  function submit(event: FormEvent) {
    event.preventDefault();
    adjustment.mutate();
  }

  return (
    <AppShell title="Stock">
      <div className="grid gap-4 xl:grid-cols-[1fr_380px]">
        <Panel title={`Current Stock (${money(totalValue)})`}>
          {stock.isLoading ? <LoadingState /> : stock.error ? <ErrorState error={stock.error} /> : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-ink/50"><tr><th className="py-2">Product</th><th>Warehouse</th><th>Stock</th><th>Value</th></tr></thead>
                <tbody>
                  {(stock.data ?? []).map((row) => (
                    <tr key={`${row.productId}-${row.warehouseId}`} className="border-t border-ink/10">
                      <td className="py-3 font-medium">{row.productName}</td>
                      <td>{row.warehouseName}</td>
                      <td>{row.currentStock}</td>
                      <td>{money(row.stockValue)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
        <Permission permission="stock.adjust"><Panel title="Stock Adjustment">
          <form onSubmit={submit} className="space-y-3">
            <RecordPicker<Product> path="/api/products" label="Product" value={form.productId} onChange={product => setForm({ ...form, productId: product?.id ?? "" })} />
            <QueryFeedback queries={[warehouses]} />
            <Field label="Warehouse">
              <SelectInput value={form.warehouseId} onChange={(e) => setForm({ ...form, warehouseId: e.target.value })} required>
                <option value="">Select warehouse</option>
                {(warehouses.data ?? []).map((warehouse) => <option key={warehouse.id} value={warehouse.id}>{warehouse.name}</option>)}
              </SelectInput>
            </Field>
            <Field label="Quantity delta">
              <TextInput value={form.quantityDelta} onChange={(e) => setForm({ ...form, quantityDelta: e.target.value })} type="number" step="0.001" required />
            </Field>
            <Field label="Rate">
              <TextInput value={form.rate} onChange={(e) => setForm({ ...form, rate: e.target.value })} type="number" step="0.01" />
            </Field>
            <Field label="Notes">
              <TextInput value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} required />
            </Field>
            <MutationFeedback mutation={adjustment} success="Stock adjustment saved." />

            <PrimaryButton className="w-full" disabled={adjustment.isPending}>Save adjustment</PrimaryButton>
          </form>
        </Panel></Permission>
      </div>
    </AppShell>
  );
}
