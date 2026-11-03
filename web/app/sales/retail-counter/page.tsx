"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { AppShell } from "@/components/AppShell";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { SecondaryButton } from "@/components/FormControls";
import { api, Page } from "@/lib/api";

type RetailSale = {
  id: string; saleDate: string; amount: number; collectionMethod: string;
  sourcePartyName?: string; sourceDirection?: string; referenceNumber?: string;
  sourceRowNumber: number; note?: string; createdAt: string;
};
const methodNames: Record<string, string> = { CASH: "Cash", UPI: "UPI / GPay", CASH_AND_UPI: "Cash + UPI combined" };

export default function RetailCounterSalesPage() {
  const [page, setPage] = useState(0);
  const sales = useQuery({ queryKey: ["retail-counter-sales", page],
    queryFn: () => api<Page<RetailSale>>(`/api/sales/retail-counter?page=${page}&size=25`) });
  return (
    <AppShell title="Retail Counter Sales">
      <nav aria-label="Sales views" className="mb-5 flex flex-wrap gap-6 border-b border-ink/15 text-sm">
        <Link className="py-3 text-ink/65" href="/sales">Sales invoices</Link>
        <Link className="border-b-2 border-moss py-3 font-semibold text-moss" aria-current="page" href="/sales/retail-counter">Retail counter sales</Link>
      </nav>
      <div className="mb-4 flex flex-wrap justify-between gap-3 text-sm">
        <span className="text-ink/65">Amount-only collections | No item detail | Stock unchanged | Cost and margin unavailable</span>
        <span>{sales.data?.totalElements ?? 0} records</span>
      </div>
      {sales.isLoading ? <LoadingState label="Loading retail counter sales..." /> : sales.error ? <ErrorState error={sales.error} /> : !sales.data?.content.length ?
        <p className="border-y border-ink/15 py-10 text-sm text-ink/60">No retail counter sales recorded.</p> : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[720px] text-left text-sm">
            <thead className="border-b border-ink/20 text-ink/55"><tr><th className="py-3">Date</th><th>Collection</th><th className="pr-5 text-right">Amount received</th><th>Source</th><th>Note</th></tr></thead>
            <tbody>{sales.data.content.map((sale) => <tr className="border-b border-ink/10 align-top" key={sale.id}>
              <td className="py-4 pr-4 whitespace-nowrap">{sale.saleDate}</td>
              <td className="py-4 pr-4">{methodNames[sale.collectionMethod] || sale.collectionMethod}</td>
              <td className="py-4 pr-5 text-right whitespace-nowrap font-semibold">{new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 2 }).format(sale.amount)}</td>
              <td className="max-w-64 break-words py-4 pr-4"><div>{sale.sourcePartyName || "Cashbook"}</div><div className="mt-1 text-xs text-ink/50">{sale.referenceNumber || "No reference"} | Row {sale.sourceRowNumber} | {sale.sourceDirection}</div></td>
              <td className="max-w-80 break-words py-4">{sale.note || "-"}</td>
            </tr>)}</tbody>
          </table>
        </div>
      )}
      <div className="mt-4 flex items-center justify-end gap-3 text-sm">
        <span>Page {page + 1} of {Math.max(1, sales.data?.totalPages ?? 1)}</span>
        <SecondaryButton title="Previous page" aria-label="Previous retail sales page" disabled={page === 0 || sales.isFetching} onClick={() => setPage(page - 1)}><ChevronLeft size={16} /></SecondaryButton>
        <SecondaryButton title="Next page" aria-label="Next retail sales page" disabled={sales.isFetching || !sales.data || page + 1 >= sales.data.totalPages} onClick={() => setPage(page + 1)}><ChevronRight size={16} /></SecondaryButton>
      </div>
    </AppShell>
  );
}
