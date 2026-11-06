"use client";

import { Download, FileText } from "lucide-react";
import { useState } from "react";
import { AppShell } from "@/components/AppShell";
import { PrimaryButton } from "@/components/FormControls";
import { Panel } from "@/components/Panel";
import { apiText } from "@/lib/api";
import { Permission, useAccess } from "@/lib/business";

const reports = [
  { id: "current-stock", name: "Current Stock", description: "Warehouse-wise quantity and stock value from the ledger." },
  { id: "low-stock", name: "Low-Stock Alerts", description: "Products at or below reorder point." },
  { id: "dead-stock", name: "Dead Stock", description: "Blocked capital and recommended action by warehouse." },
  { id: "reorder-suggestions", name: "Reorder Suggestions", description: "Demand inputs, stockout dates, and recommended order quantity." },
  { id: "products", name: "Product Master", description: "SKU, name, HSN, GST, pricing, and reorder data." },
  { id: "customers", name: "Customer Master", description: "Customer contact, GSTIN, credit limit, and outstanding." },
  { id: "suppliers", name: "Supplier Master", description: "Supplier contact, GSTIN, credit days, and opening balance." },
  { id: "sales", name: "Sales Invoices", description: "Sales invoice headers for operational analysis." },
  { id: "purchases", name: "Purchase Invoices", description: "Purchase invoice headers for stock and cost checks." },
  { id: "top-products", name: "Top Products", description: "Best-selling products by revenue." },
  { id: "slow-moving-products", name: "Slow-Moving Products", description: "Products with stock on hand and weak recent movement." },
  { id: "audit-logs", name: "Audit Logs", description: "Important system actions and stock changes." }
];

export default function ReportsPage() {
  const { can } = useAccess();
  const [downloading, setDownloading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function download(reportId: string) {
    setDownloading(reportId);
    setError(null);
    try {
      const csv = await apiText(`/api/reports/export/${reportId}?format=csv`);
      const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `${reportId}.csv`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Report download failed");
    } finally {
      setDownloading(null);
    }
  }

  return (
    <AppShell title="Reports">
      <div className="space-y-4">
        <Panel title="Export Reports">
          {!can("reports.export") ? <p className="mb-4 text-sm text-ink/60">Your role can view report descriptions. Ask an authorized exporter for CSV files.</p> : null}
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {reports.map((report) => (
              <div key={report.id} className="rounded border border-ink/10 p-4">
                <div className="flex items-start gap-3">
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded bg-mint text-moss">
                    <FileText size={18} />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="font-semibold">{report.name}</div>
                    <p className="mt-1 min-h-10 text-sm text-ink/55">{report.description}</p>
                  </div>
                </div>
                <Permission permission="reports.export"><PrimaryButton className="mt-4 w-full gap-2" onClick={() => download(report.id)} disabled={downloading === report.id}>
                  <Download size={16} />
                  {downloading === report.id ? "Preparing..." : "Download CSV"}
                </PrimaryButton></Permission>
              </div>
            ))}
          </div>
          {error ? <p className="mt-4 rounded border border-coral/20 bg-coral/10 p-3 text-sm text-coral">{error}</p> : null}
        </Panel>
      </div>
    </AppShell>
  );
}
