"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis, CartesianGrid } from "recharts";
import { AppShell } from "@/components/AppShell";
import { EmptyState } from "@/components/EmptyState";
import { PrimaryButton } from "@/components/FormControls";
import { Permission, useMoney } from "@/lib/business";
import { MutationFeedback, QueryFeedback } from "@/components/QueryFeedback";
import { Panel } from "@/components/Panel";
import { api } from "@/lib/api";

type Forecast = { productId: string; productName: string; currentStock: number; averageDailyDemand: number; next7DaysDemand: number; next30DaysDemand: number; stockoutDate?: string; salesHistoryAvailable?: boolean };
type Suggestion = { productId: string; productName: string; warehouseName: string; currentStock: number; averageDailyDemand: number; last7DaysDemand: number; last30DaysDemand: number; expectedStockoutDate?: string; salesHistoryAvailable?: boolean; supplierLeadTimeDays: number; recommendedQuantity: number; reason: string };

export default function ForecastsPage() {
  const money = useMoney();
  const queryClient = useQueryClient();
  const forecasts = useQuery({ queryKey: ["forecasts"], queryFn: () => api<Forecast[]>("/api/forecast/results") });
  const suggestions = useQuery({ queryKey: ["reorder"], queryFn: () => api<Suggestion[]>("/api/reorder/suggestions") });
  const run = useMutation({
    mutationFn: () => api<Forecast[]>("/api/forecast/run", { method: "POST" }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["forecasts"] });
      queryClient.invalidateQueries({ queryKey: ["reorder"] });
    }
  });
  const draftPo = useMutation({
    mutationFn: () => api<{ orderNumber: string; totalAmount: number; itemCount: number }>("/api/reorder/generate-purchase-order", { method: "POST", body: JSON.stringify({ productIds: Array.from(new Set((suggestions.data ?? []).map((row) => row.productId))) }) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["reorder"] })
  });
  const forecastRows = forecasts.data ?? [];
  const suggestionRows = suggestions.data ?? [];

  if (forecasts.isLoading || forecasts.error || suggestions.isLoading || suggestions.error) return <AppShell title="Forecasts"><QueryFeedback queries={[forecasts, suggestions]} /></AppShell>;

  return (
    <AppShell title="Forecasts" actions={<PrimaryButton onClick={() => run.mutate()} disabled={run.isPending}>{run.isPending ? "Running..." : "Run forecast"}</PrimaryButton>}>
      <QueryFeedback queries={[forecasts, suggestions]} />
      <MutationFeedback mutation={run} success="Forecast refreshed." />
      <MutationFeedback mutation={draftPo} success="Draft purchase order created." />
      <div className="grid gap-4 xl:grid-cols-[1fr_440px]">
        <Panel title="Demand Forecast">
          {forecastRows.length ? (
            <div className="mb-4 h-72">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={forecastRows}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#d7e4dc" />
                  <XAxis dataKey="productName" tick={{ fontSize: 11 }} interval={0} angle={-20} height={80} />
                  <YAxis tick={{ fontSize: 12 }} />
                  <Tooltip />
                  <Line dataKey="next7DaysDemand" stroke="#326657" strokeWidth={2} />
                  <Line dataKey="next30DaysDemand" stroke="#df6b57" strokeWidth={2} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          ) : (
            <EmptyState title="No forecasts yet. Import sales history or run forecasting after adding stock movements." />
          )}
          <table className="w-full text-left text-sm">
            <thead className="text-ink/50"><tr><th className="py-2">Product</th><th>Stock</th><th>Daily demand</th><th>7 days</th><th>30 days</th><th>Stockout</th></tr></thead>
            <tbody>{forecastRows.map((row) => <tr key={row.productId} className="border-t border-ink/10"><td className="py-3 font-medium">{row.productName}</td><td>{row.currentStock}</td><td>{row.salesHistoryAvailable === false ? "No sales history" : row.averageDailyDemand}</td><td>{row.salesHistoryAvailable === false ? "—" : row.next7DaysDemand}</td><td>{row.salesHistoryAvailable === false ? "—" : row.next30DaysDemand}</td><td>{row.currentStock <= 0 ? "Out of stock" : row.salesHistoryAvailable === false ? "No sales history" : row.stockoutDate ?? "Not projected"}</td></tr>)}</tbody>
          </table>
        </Panel>
        <Panel title="Reorder Suggestions" action={<Permission permission="reorder.create_purchase_order"><PrimaryButton onClick={() => draftPo.mutate()} disabled={draftPo.isPending || !(suggestions.data ?? []).length}>{draftPo.isPending ? "Creating..." : "Create Draft Purchase Order"}</PrimaryButton></Permission>}>
          <div className="space-y-3">
            {suggestionRows.map((suggestion) => (
              <div key={`${suggestion.productId}-${suggestion.warehouseName}`} className="rounded border border-ink/10 p-3">
                <div className="flex justify-between gap-3">
                  <span className="font-medium">{suggestion.productName}</span>
                  <span className="text-sm text-moss">{suggestion.recommendedQuantity}</span>
                </div>
                <div className="mt-1 text-xs text-ink/50">{suggestion.warehouseName} - stock {suggestion.currentStock} - avg/day {suggestion.averageDailyDemand} - stockout {suggestion.currentStock <= 0 ? "Out of stock" : suggestion.salesHistoryAvailable === false ? "No sales history" : suggestion.expectedStockoutDate ?? "not projected"}</div>
                <p className="mt-1 text-sm text-ink/55">{suggestion.reason}</p>
              </div>
            ))}
            {!suggestionRows.length ? <EmptyState title="No reorder suggestions yet.">Suggestions appear after products, warehouse stock, and sales demand are available.</EmptyState> : null}
            {draftPo.data ? <p className="rounded bg-mint p-3 text-sm">Draft {draftPo.data.orderNumber}: {draftPo.data.itemCount} items, {money(draftPo.data.totalAmount)}.</p> : null}
          </div>
        </Panel>
      </div>
    </AppShell>
  );
}
