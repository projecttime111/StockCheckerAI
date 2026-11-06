"use client";

import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Database, FileUp, Sparkles } from "lucide-react";
import Link from "next/link";
import { AppShell } from "@/components/AppShell";
import { QueryFeedback } from "@/components/QueryFeedback";
import { Panel } from "@/components/Panel";
import { StatCard } from "@/components/StatCard";
import { api } from "@/lib/api";

type TallyStatus = {
  status: string;
  lastSuccessfulImportDate?: string;
  productsImported: number;
  customersImported: number;
  suppliersImported: number;
  salesVouchersImported: number;
  purchaseVouchersImported: number;
  importErrors: number;
  unmappedFields: string[];
  duplicateProductsDetected: number;
  missingGstHsnUnitCategoryWarnings: number;
  dataQualityScore: number;
  aiInsightsGenerated: number;
};

export default function TallyIntegrationPage() {
  const status = useQuery({ queryKey: ["integrations", "tally"], queryFn: () => api<TallyStatus>("/api/integrations/tally/status") });
  const data = status.data;

  if (status.isLoading || status.error) return <AppShell title="Tally Integration"><QueryFeedback queries={[status]} /></AppShell>;

  return (
    <AppShell title="Tally Integration Hub" actions={<Link className="inline-flex h-10 items-center gap-2 rounded bg-moss px-4 text-sm font-semibold text-white" href="/imports"><FileUp size={16} />Upload Tally Export</Link>}>
      <div className="space-y-6">
        <Panel title="Companion Mode">
          <div className="grid gap-4 text-sm text-ink/70 lg:grid-cols-2">
            <p>Tally remains your system of record for accounting, GST, ledgers, vouchers, invoicing, compliance, and historical reports.</p>
            <p>StockPilot AI uses exported Tally, Excel, CSV, XML, or JSON data to generate inventory, stockout, reorder, profit, and data-quality insights.</p>
          </div>
        </Panel>

        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
          <StatCard label="Data imported" value={(data?.productsImported ?? 0) + (data?.customersImported ?? 0) + (data?.suppliersImported ?? 0)} icon={<Database size={18} />} />
          <StatCard label="Data quality score" value={`${data?.dataQualityScore ?? 0}%`} icon={<CheckCircle2 size={18} />} />
          <StatCard label="Duplicate items found" value={data?.duplicateProductsDetected ?? 0} icon={<AlertTriangle size={18} />} tone="amber" />
          <StatCard label="Missing mappings" value={data?.unmappedFields?.length ?? 0} icon={<FileUp size={18} />} tone="coral" />
          <StatCard label="AI insights generated" value={data?.aiInsightsGenerated ?? 0} icon={<Sparkles size={18} />} />
        </div>

        <div className="grid gap-4 xl:grid-cols-[1fr_360px]">
          <Panel title="Import Status">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <Metric label="Status" value={data?.status ?? "Loading"} />
              <Metric label="Last successful import" value={data?.lastSuccessfulImportDate ?? "-"} />
              <Metric label="Products" value={data?.productsImported ?? 0} />
              <Metric label="Customers" value={data?.customersImported ?? 0} />
              <Metric label="Suppliers" value={data?.suppliersImported ?? 0} />
              <Metric label="Sales vouchers" value={data?.salesVouchersImported ?? 0} />
              <Metric label="Purchase vouchers" value={data?.purchaseVouchersImported ?? 0} />
              <Metric label="Import errors" value={data?.importErrors ?? 0} />
              <Metric label="Missing GST/HSN/unit/category" value={data?.missingGstHsnUnitCategoryWarnings ?? 0} />
            </div>
          </Panel>

          <Panel title="Supported Export Files">
            <div className="space-y-3 text-sm text-ink/70">
              {["Excel", "CSV", "XML", "JSON"].map((type) => <div key={type} className="rounded border border-ink/10 p-3 font-medium">{type}</div>)}
              <p>Your accounting remains in Tally. StockPilot AI uses exported data to generate inventory and profit insights.</p>
            </div>
          </Panel>
        </div>

        <Panel title="Unmapped Fields and Warnings">
          <div className="flex flex-wrap gap-2">
            {(data?.unmappedFields ?? []).length ? data?.unmappedFields.map((field) => <span key={field} className="rounded bg-coral/10 px-3 py-2 text-sm text-coral">{field}</span>) : <span className="text-sm text-ink/55">No unmapped fields found in recent imports.</span>}
          </div>
        </Panel>
      </div>
    </AppShell>
  );
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded border border-ink/10 bg-white p-3">
      <div className="text-xs font-medium uppercase text-ink/45">{label}</div>
      <div className="mt-1 text-lg font-semibold">{value}</div>
    </div>
  );
}
