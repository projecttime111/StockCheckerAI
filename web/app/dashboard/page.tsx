"use client";

import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, Bot, Boxes, ClipboardCheck, IndianRupee, Package, Settings, Store, TrendingUp, Truck, Wallet } from "lucide-react";
import Link from "next/link";
import { Area, AreaChart, Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { AnalyticsReadiness } from "@/components/AnalyticsReadiness";
import { AppShell } from "@/components/AppShell";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { QueryFeedback } from "@/components/QueryFeedback";
import { Panel } from "@/components/Panel";
import { StatCard } from "@/components/StatCard";
import { api } from "@/lib/api";
import { Permission, useAccess, useMoney } from "@/lib/business";

type Summary = {
  totalStockValue: number;
  monthlySales: number;
  grossProfit: number;
  lowStockCount: number;
  deadStockValue: number;
  outstandingReceivables: number;
  grossMarginPercent: number;
  totalProducts: number;
  totalCustomers: number;
  totalSuppliers: number;
  importDataQualityScore: number;
};

type TrendPoint = { date: string; value: number };
type DeadStock = { productName: string; stockQuantity: number; stockValue: number; suggestedAction: string };
type LowStock = { productName: string; currentStock: number; reorderPoint: number };
type ActionCard = { title: string; reason: string; estimatedImpact: number; href: string; cta: string };
type ProfitReason = { reason: string; impactAmount: number; evidence: string };
type ProfitDrop = { summary: string; profitChange: number; marginChangePercent: number; topReasons: ProfitReason[]; recommendedActions: string[] };
type Tenant = { id: string; name: string; businessMode: "RETAIL" | "WHOLESALE" | "HYBRID"; currency: string; gstEnabled: boolean };
type ProductMetric = { productId: string; productName: string; quantity: number; revenue: number; stockQuantity: number; stockValue: number; lastSoldDate?: string };

const modeDescriptions: Record<Tenant["businessMode"], string> = {
  RETAIL: "Retail mode is tuned for counter sales, simple product catalogs, and quick stock operations.",
  WHOLESALE: "Wholesale mode is tuned for godowns, bulk units, customer pricing, credit, and distributor workflows.",
  HYBRID: "Hybrid mode enables both retail counter operations and wholesale distributor workflows in one tenant."
};

export default function DashboardPage() {
  const money = useMoney();
  const { can } = useAccess();
  const summary = useQuery({ queryKey: ["dashboard", "summary"], queryFn: () => api<Summary>("/api/dashboard/summary") });
  const salesTrend = useQuery({ queryKey: ["dashboard", "sales"], queryFn: () => api<TrendPoint[]>("/api/dashboard/sales-trend") });
  const profitTrend = useQuery({ queryKey: ["dashboard", "profit"], queryFn: () => api<TrendPoint[]>("/api/dashboard/profit-loss") });
  const lowStock = useQuery({ queryKey: ["dashboard", "low"], queryFn: () => api<LowStock[]>("/api/dashboard/low-stock") });
  const deadStock = useQuery({ queryKey: ["dashboard", "dead"], queryFn: () => api<DeadStock[]>("/api/dashboard/dead-stock") });
  const actions = useQuery({ queryKey: ["dashboard", "actions"], queryFn: () => api<ActionCard[]>("/api/dashboard/actions") });
  const topProducts = useQuery({ queryKey: ["dashboard", "top-products"], queryFn: () => api<ProductMetric[]>("/api/dashboard/top-products") });
  const slowMoving = useQuery({ queryKey: ["dashboard", "slow-moving"], queryFn: () => api<ProductMetric[]>("/api/dashboard/slow-moving-products") });
  const profitDrop = useQuery({ queryKey: ["profit-drop"], enabled: can("insights.profit.view"), queryFn: () => api<ProfitDrop>("/api/insights/profit-drop?period=current-month") });
  const tenant = useQuery({ queryKey: ["tenant"], queryFn: () => api<Tenant>("/api/tenants/current") });
  const summaryData = summary.data;
  const hasBusinessData = hasDashboardBusinessData(summaryData);
  const actionRows = actions.data ?? [];
  const topProductRows = topProducts.data ?? [];
  const slowMovingRows = slowMoving.data ?? [];
  const lowStockRows = lowStock.data ?? [];
  const deadStockRows = deadStock.data ?? [];

  return (
    <AppShell title="Dashboard">
      <div className="mb-5"><AnalyticsReadiness /></div>
      {summary.isLoading ? <LoadingState /> : summary.error ? <ErrorState error={summary.error} /> : (
        <div className="space-y-6">
          <QueryFeedback queries={[salesTrend, profitTrend, lowStock, deadStock, actions, topProducts, slowMoving, profitDrop, tenant]} />
          <Panel title="StockPilot AI is your intelligence layer">
            <div className="grid gap-4 lg:grid-cols-[1fr_340px]">
              <div className="grid gap-3 text-sm text-ink/70">
                <p>Your accounting, GST, ledgers, vouchers, invoicing, compliance, and historical statutory reports remain in Tally or your ERP.</p>
                <p>StockPilot AI reads exported operational data to forecast stockouts, find dead stock, explain profit changes, clean messy product names, and guide mobile warehouse actions.</p>
              </div>
              <div className="rounded border border-moss/20 bg-mint/60 p-4">
                <div className="text-xs font-semibold uppercase tracking-normal text-ink/50">Current tenant mode</div>
                <div className="mt-1 text-xl font-semibold text-moss">{tenant.data?.businessMode ?? "HYBRID"}</div>
                <p className="mt-2 text-sm text-ink/60">{modeDescriptions[tenant.data?.businessMode ?? "HYBRID"]}</p>
                <Permission permission="settings.view"><Link href="/settings" className="mt-3 inline-flex items-center gap-2 rounded border border-moss/25 bg-white px-3 py-2 text-sm font-semibold text-moss">
                  <Settings size={16} />
                  Change mode
                </Link></Permission>
              </div>
            </div>
          </Panel>

          {!hasBusinessData ? (
            <EmptyState
              title="No business data yet. Import Tally/Excel data or add your first product."
              action={<Link className="inline-flex rounded bg-moss px-3 py-2 text-sm font-semibold text-white" href="/imports">Open imports</Link>}
            >
              Dashboard, forecasts, dead-stock, data quality, and AI insights will stay empty until this backend tenant has committed products, purchases, sales, stock movements, or parties.
            </EmptyState>
          ) : null}

          <div className="grid gap-4 md:grid-cols-2">
            <Permission permission="settings.view"><Link href="/settings" className="rounded border border-ink/10 bg-white p-4 shadow-sm transition hover:border-moss/40">
              <div className="flex items-center gap-2 text-sm font-semibold text-moss"><Settings size={17} /> Retail / Wholesale / Hybrid Mode</div>
              <p className="mt-2 text-sm text-ink/60">Choose RETAIL, WHOLESALE, or HYBRID tenant mode from Settings. The current tenant is {tenant.data?.businessMode ?? "HYBRID"}.</p>
            </Link></Permission>
            <Permission permission="ai.assistant.use"><Link href="/assistant" className="rounded border border-ink/10 bg-white p-4 shadow-sm transition hover:border-moss/40">
              <div className="flex items-center gap-2 text-sm font-semibold text-moss"><Bot size={17} /> AI Assistant</div>
              <p className="mt-2 text-sm text-ink/60">Ask data-backed questions like why profit dropped, what to reorder, and which imported products need cleanup.</p>
            </Link></Permission>
          </div>

          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-6">
            <StatCard label="Stock value" value={money(summaryData?.totalStockValue)} icon={<Boxes size={18} />} />
            <StatCard label="Monthly sales" value={money(summaryData?.monthlySales)} icon={<IndianRupee size={18} />} />
            <StatCard label="Gross profit" value={money(summaryData?.grossProfit)} icon={<TrendingUp size={18} />} />
            <StatCard label="Low stock" value={summaryData?.lowStockCount ?? 0} icon={<AlertTriangle size={18} />} tone="amber" />
            <StatCard label="Money blocked in dead stock" value={money(summaryData?.deadStockValue)} icon={<Boxes size={18} />} tone="coral" />
            <StatCard label="Receivables" value={money(summaryData?.outstandingReceivables)} icon={<Wallet size={18} />} />
          </div>

          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
            <StatCard label="Gross margin" value={summaryData?.grossMarginPercent == null ? "—" : `${summaryData.grossMarginPercent}%`} icon={<TrendingUp size={18} />} />
            <StatCard label="Products" value={summaryData?.totalProducts ?? 0} icon={<Package size={18} />} />
            <StatCard label="Customers" value={summaryData?.totalCustomers ?? 0} icon={<Store size={18} />} />
            <StatCard label="Suppliers" value={summaryData?.totalSuppliers ?? 0} icon={<Truck size={18} />} />
            <StatCard label="Import data quality" value={hasBusinessData ? `${summaryData?.importDataQualityScore ?? 0}%` : "No data"} icon={<ClipboardCheck size={18} />} tone={(summaryData?.importDataQualityScore ?? 100) < 75 ? "amber" : "moss"} />
          </div>

          <Panel title="Today's Actions">
            {actionRows.length ? (
              <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                {actionRows.map((action) => (
                <div key={action.title} className="rounded border border-ink/10 bg-white p-4">
                  <div className="font-semibold">{action.title}</div>
                  <p className="mt-1 min-h-10 text-sm text-ink/55">{action.reason}</p>
                  <div className="mt-3 text-sm font-semibold text-moss">{action.estimatedImpact ? money(action.estimatedImpact) : "Operational priority"}</div>
                  <Link className="mt-3 inline-flex rounded bg-moss px-3 py-2 text-sm font-semibold text-white" href={action.href}>{action.cta}</Link>
                </div>
                ))}
              </div>
            ) : (
              <EmptyState title="No recommended actions yet.">
                Import Tally/Excel data or create products, purchases, and sales to generate reorder, receivable, dead-stock, and margin actions.
              </EmptyState>
            )}
          </Panel>

          <div className="grid gap-4 xl:grid-cols-2">
            <Panel title="Top Products">
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-ink/50"><tr><th className="py-2">Product</th><th>Sold</th><th>Revenue</th><th>Stock</th></tr></thead>
                  <tbody>
                    {topProductRows.slice(0, 8).map((row) => (
                      <tr key={row.productId} className="border-t border-ink/10">
                        <td className="py-3 font-medium">{row.productName}</td>
                        <td>{row.quantity}</td>
                        <td>{money(row.revenue)}</td>
                        <td>{row.stockQuantity}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!topProductRows.length ? <EmptyState title="No top products yet.">Sales invoice data will populate this table.</EmptyState> : null}
            </Panel>
            <Panel title="Slow-Moving Products">
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-ink/50"><tr><th className="py-2">Product</th><th>Stock value</th><th>Recent sales</th><th>Last sold</th></tr></thead>
                  <tbody>
                    {slowMovingRows.slice(0, 8).map((row) => (
                      <tr key={row.productId} className="border-t border-ink/10">
                        <td className="py-3 font-medium">{row.productName}</td>
                        <td>{money(row.stockValue)}</td>
                        <td>{row.quantity}</td>
                        <td>{row.lastSoldDate ?? "-"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!slowMovingRows.length ? <EmptyState title="No slow-moving products yet.">Slow-moving analysis appears after stock and sales history exists.</EmptyState> : null}
            </Panel>
          </div>

          <div className="grid gap-4 xl:grid-cols-2">
            <Panel title="Sales Trend">
              <div className="h-72">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={salesTrend.data ?? []}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#d7e4dc" />
                    <XAxis dataKey="date" tick={{ fontSize: 12 }} />
                    <YAxis tick={{ fontSize: 12 }} />
                    <Tooltip formatter={(value) => money(Number(value))} />
                    <Area type="monotone" dataKey="value" stroke="#326657" fill="#dff5eb" />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </Panel>
            <Permission permission="insights.profit.view"><Panel title="Profit Trend">
              <div className="h-72">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={profitTrend.data ?? []}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#d7e4dc" />
                    <XAxis dataKey="date" tick={{ fontSize: 12 }} />
                    <YAxis tick={{ fontSize: 12 }} />
                    <Tooltip formatter={(value) => money(Number(value))} />
                    <Bar dataKey="value" fill="#f6b84b" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </Panel></Permission>
          </div>

          <Permission permission="insights.profit.view"><Panel title="Why Profit Changed">
            <div className="grid gap-4 xl:grid-cols-[1fr_320px]">
              <div>
                <p className="text-sm text-ink/70">{profitDrop.data?.summary ?? "Profit explanation will appear once sales and purchase data are available."}</p>
                <div className="mt-4 grid gap-3 sm:grid-cols-2">
                  <StatCard label="Profit change" value={money(profitDrop.data?.profitChange)} icon={<TrendingUp size={18} />} tone={(profitDrop.data?.profitChange ?? 0) < 0 ? "coral" : "moss"} />
                  <StatCard label="Margin change" value={`${profitDrop.data?.marginChangePercent ?? 0}%`} icon={<IndianRupee size={18} />} />
                </div>
              </div>
              <div className="space-y-2">
                {(profitDrop.data?.topReasons ?? []).slice(0, 3).map((reason) => (
                  <div key={reason.reason} className="rounded border border-ink/10 p-3 text-sm">
                    <div className="font-medium">{reason.reason}</div>
                    <div className="text-coral">{money(reason.impactAmount)}</div>
                    <p className="text-ink/55">{reason.evidence}</p>
                  </div>
                ))}
              </div>
            </div>
          </Panel></Permission>

          <div className="grid gap-4 xl:grid-cols-2">
            <Panel title="Low-Stock Alerts">
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-ink/50"><tr><th className="py-2">Product</th><th>Stock</th><th>Reorder point</th></tr></thead>
                  <tbody>
                    {lowStockRows.slice(0, 8).map((row) => (
                      <tr key={row.productName} className="border-t border-ink/10"><td className="py-3 font-medium">{row.productName}</td><td>{row.currentStock}</td><td>{row.reorderPoint}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!lowStockRows.length ? <EmptyState title="No low-stock alerts.">Alerts appear when current stock falls to or below reorder points.</EmptyState> : null}
            </Panel>
            <Panel title="Dead-Stock Insights">
              <div className="space-y-3">
                {deadStockRows.slice(0, 5).map((row) => (
                  <div key={row.productName} className="rounded border border-ink/10 p-3">
                    <div className="flex justify-between gap-3">
                      <span className="font-medium">{row.productName}</span>
                      <span className="text-sm text-coral">{money(row.stockValue)}</span>
                    </div>
                    <p className="mt-1 text-sm text-ink/55">{row.suggestedAction}</p>
                  </div>
                ))}
                {!deadStockRows.length ? <EmptyState title="No dead-stock items found.">Dead-stock detection will appear after inventory history exists.</EmptyState> : null}
              </div>
            </Panel>
          </div>
        </div>
      )}
    </AppShell>
  );
}

function hasDashboardBusinessData(summary: Summary | undefined) {
  if (!summary) return false;
  return [
    summary.totalProducts,
    summary.totalCustomers,
    summary.totalSuppliers,
    summary.totalStockValue,
    summary.monthlySales,
    summary.grossProfit,
    summary.lowStockCount,
    summary.deadStockValue,
    summary.outstandingReceivables
  ].some((value) => Number(value ?? 0) !== 0);
}
