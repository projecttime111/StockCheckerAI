"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Boxes, IndianRupee, Tag } from "lucide-react";
import { AppShell } from "@/components/AppShell";
import { EmptyState } from "@/components/EmptyState";
import { MutationFeedback, QueryFeedback } from "@/components/QueryFeedback";
import { Panel } from "@/components/Panel";
import { StatCard } from "@/components/StatCard";
import { api } from "@/lib/api";
import { Permission, useMoney } from "@/lib/business";

type DeadStockRow = { productId: string; productName: string; warehouseName: string; quantity: number; stockValue: number; lastSoldDate?: string; daysSinceLastSale: number; averageMonthlySale: number; blockedCapital: number | null; recommendedAction: string };

const actions = ["apply discount", "bundle with fast-moving item", "transfer to another warehouse", "stop reordering", "return to supplier", "mark as clearance item"];

export default function DeadStockPage() {
  const money = useMoney();
  const queryClient = useQueryClient();
  const rows = useQuery({ queryKey: ["dead-stock"], queryFn: () => api<DeadStockRow[]>("/api/dead-stock") });
  const record = useMutation({
    mutationFn: ({ productId, action }: { productId: string; action: string }) => api(`/api/dead-stock/${productId}/action`, { method: "POST", body: JSON.stringify({ action }) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["dead-stock"] })
  });
  const deadStockRows = rows.data ?? [];
  const total = deadStockRows.some(row => row.blockedCapital == null) ? null : deadStockRows.reduce((sum, row) => sum + Number(row.blockedCapital), 0);

  if (rows.isLoading || rows.error) return <AppShell title="Dead Stock"><QueryFeedback queries={[rows]} /></AppShell>;

  return (
    <AppShell title="Dead-Stock Action Center">
      <QueryFeedback queries={[rows]} />
      <MutationFeedback mutation={record} success="Action recorded." />
      <div className="space-y-6">
        <div className="grid gap-4 md:grid-cols-3">
          <StatCard label="Money blocked in dead stock" value={money(total)} icon={<IndianRupee size={18} />} tone="coral" />
          <StatCard label="Products to act on" value={deadStockRows.length} icon={<Boxes size={18} />} tone="amber" />
          <StatCard label="Recommended actions" value={actions.length} icon={<Tag size={18} />} />
        </div>

        <Panel title="Action Queue">
          {deadStockRows.length ? <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-ink/50">
                <tr><th className="py-2">Product</th><th>Warehouse</th><th>Qty</th><th>Blocked capital</th><th>Last sold</th><th>Avg monthly sale</th><th>Action</th></tr>
              </thead>
              <tbody>
                {deadStockRows.map((row) => (
                  <tr key={`${row.productId}-${row.warehouseName}`} className="border-t border-ink/10">
                    <td className="py-3 font-medium">{row.productName}</td>
                    <td>{row.warehouseName}</td>
                    <td>{row.quantity}</td>
                    <td className="text-coral">{money(row.blockedCapital)}</td>
                    <td>{row.lastSoldDate ?? "Never"} ({row.daysSinceLastSale} days)</td>
                    <td>{row.averageMonthlySale}</td>
                    <td>
                      <Permission permission="dead_stock.action"><select disabled={record.isPending} aria-label={`Action for ${row.productName}`} className="rounded border border-ink/15 px-2 py-1" defaultValue={row.recommendedAction} onChange={(event) => record.mutate({ productId: row.productId, action: event.target.value })}>
                        {actions.map((action) => <option key={action} value={action}>{action}</option>)}
                      </select></Permission>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div> : (
            <EmptyState title="No dead-stock items found. Dead-stock detection will appear after inventory history exists." />
          )}
        </Panel>
      </div>
    </AppShell>
  );
}
