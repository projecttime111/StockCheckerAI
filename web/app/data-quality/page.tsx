"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Merge, Sparkles, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { AppShell } from "@/components/AppShell";
import { EmptyState } from "@/components/EmptyState";
import { Permission } from "@/lib/business";
import { MutationFeedback, QueryFeedback } from "@/components/QueryFeedback";
import { Pagination } from "@/components/Pagination";
import { Panel } from "@/components/Panel";
import { StatCard } from "@/components/StatCard";
import { api } from "@/lib/api";

type Suggestion = { productId: string; productName: string; normalizedName: string; brand: string; category: string; unitSize: string; issues: string[] };
type DuplicateGroup = { matchKey: string; similarityScore: number; products: Suggestion[]; suggestedCanonical: Suggestion };
type MissingFields = { productId: string; productName: string; missingFields: string[]; suggestion: Suggestion };
type Summary = { totalProducts: number; duplicateGroups: number; productsWithMissingFields: number; productsWithoutRecentSales: number; productsWithoutPurchaseCost: number; qualityScore: number };

export default function DataQualityPage() {
  const [missingPage, setMissingPage] = useState(0);
  const queryClient = useQueryClient();
  const [mergeConfirmations, setMergeConfirmations] = useState<Record<string, string>>({});
  const summary = useQuery({ queryKey: ["data-quality", "summary"], queryFn: () => api<Summary>("/api/data-quality/summary") });
  const duplicates = useQuery({ queryKey: ["data-quality", "duplicates"], queryFn: () => api<DuplicateGroup[]>("/api/data-quality/products/duplicates") });
  const missing = useQuery({ queryKey: ["data-quality", "missing"], queryFn: () => api<MissingFields[]>("/api/data-quality/products/missing-fields") });
  const apply = useMutation({
    mutationFn: (row: Suggestion) => api(`/api/data-quality/products/${row.productId}/apply-suggestion`, { method: "POST", body: JSON.stringify({ normalizedName: row.normalizedName, brand: row.brand, category: row.category }) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["data-quality"] })
  });
  const merge = useMutation({
    mutationFn: (group: DuplicateGroup) => api("/api/data-quality/products/merge", { method: "POST", body: JSON.stringify({ targetProductId: group.suggestedCanonical.productId, sourceProductIds: group.products.filter((p) => p.productId !== group.suggestedCanonical.productId).map((p) => p.productId), confirmation: mergeConfirmations[group.matchKey] ?? "" }) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["data-quality"] })
  });
  const summaryData = summary.data;
  const duplicateRows = duplicates.data ?? [];
  const missingRows = missing.data ?? [];
  const hasProducts = Number(summaryData?.totalProducts ?? 0) > 0;

  if (summary.isLoading || summary.error || duplicates.isLoading || duplicates.error || missing.isLoading || missing.error) return <AppShell title="Data Quality"><QueryFeedback queries={[summary, duplicates, missing]} /></AppShell>;

  return (
    <AppShell title="Data Quality">
      <QueryFeedback queries={[summary, duplicates, missing]} />
      <MutationFeedback mutation={apply} success="Suggestion applied." />
      <MutationFeedback mutation={merge} success="Products merged." />
      <div className="space-y-6">
        <Panel title="Product Cleanup Engine">
          <p className="text-sm text-ink/65">Clean messy Tally or Excel item masters before forecasting. StockPilot AI detects duplicates, missing GST/HSN/unit/category, missing purchase cost, and suggests normalized names without changing your accounting records.</p>
          <p className="mt-3 rounded bg-mint/70 p-3 text-xs text-moss">Exact import matches are reused automatically. Possible duplicates require review before merging.</p>
        </Panel>
        {!hasProducts ? (
          <EmptyState title="No data-quality issues yet. Import or add products to begin cleanup.">
            Duplicate detection, normalized product names, missing unit/HSN/GST/category checks, and purchase-cost checks run only against products in this backend tenant.
          </EmptyState>
        ) : null}
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
          <StatCard label="Quality score" value={hasProducts ? `${summaryData?.qualityScore ?? 0}%` : "No data"} icon={<CheckCircle2 size={18} />} />
          <StatCard label="Products" value={summaryData?.totalProducts ?? 0} icon={<Sparkles size={18} />} />
          <StatCard label="Duplicate groups" value={summaryData?.duplicateGroups ?? 0} icon={<Merge size={18} />} tone="amber" />
          <StatCard label="Missing fields" value={summaryData?.productsWithMissingFields ?? 0} icon={<TriangleAlert size={18} />} tone="coral" />
          <StatCard label="No purchase cost" value={summaryData?.productsWithoutPurchaseCost ?? 0} icon={<TriangleAlert size={18} />} tone="coral" />
        </div>

        <div className="grid gap-4 xl:grid-cols-2">
          <Panel title="Duplicate Products">
            <div className="space-y-3">
              {duplicateRows.map((group) => (
                <div key={group.matchKey} className="rounded border border-ink/10 p-3">
                  <div className="flex items-center justify-between gap-2">
                    <div className="font-semibold">Similarity {group.similarityScore}%</div>
                    <Permission permission="data_quality.merge"><button className="rounded bg-moss px-3 py-2 text-sm font-semibold text-white disabled:opacity-50" disabled={merge.isPending || (mergeConfirmations[group.matchKey] ?? "") !== "MERGE PRODUCTS"} onClick={() => merge.mutate(group)}>Merge into canonical</button></Permission>
                  </div>
                  <div className="mt-2 text-sm text-ink/55">Suggested: {group.suggestedCanonical.normalizedName}</div>
                  <input
                    className="mt-3 w-full rounded border border-ink/15 px-3 py-2 text-sm"
                    placeholder="Type MERGE PRODUCTS to confirm"
                    value={mergeConfirmations[group.matchKey] ?? ""}
                    onChange={(event) => setMergeConfirmations((current) => ({ ...current, [group.matchKey]: event.target.value }))}
                  />
                  <div className="mt-3 space-y-1">
                    {group.products.map((product) => <div key={product.productId} className="text-sm">{product.productName}</div>)}
                  </div>
                </div>
              ))}
              {!duplicateRows.length ? <EmptyState title="No duplicate groups detected.">Similar-product detection will run after products exist in this tenant.</EmptyState> : null}
            </div>
          </Panel>

          <Panel title="Missing Fields and Suggestions">
            <div className="space-y-3">
              {missingRows.slice(missingPage * 20, (missingPage + 1) * 20).map((row) => (
                <div key={row.productId} className="rounded border border-ink/10 p-3">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <div className="font-semibold">{row.productName}</div>
                      <div className="text-sm text-ink/55">Missing: {row.missingFields.join(", ")}</div>
                      <div className="text-sm text-moss">Suggestion: {row.suggestion.normalizedName} / {row.suggestion.category}</div>
                    </div>
                    <Permission permission="data_quality.apply_suggestion"><button disabled={apply.isPending} className="rounded border border-ink/15 px-3 py-2 text-sm font-semibold" onClick={() => apply.mutate(row.suggestion)}>Apply</button></Permission>
                  </div>
                </div>
              ))}
              <Pagination data={{ content: missingRows, totalElements: missingRows.length, totalPages: Math.ceil(missingRows.length / 20), number: missingPage }} page={missingPage} onChange={setMissingPage} />
              {!missingRows.length ? <EmptyState title="No missing-field suggestions.">Products with missing unit, HSN, GST, category, or purchase cost will appear here.</EmptyState> : null}
            </div>
          </Panel>
        </div>
      </div>
    </AppShell>
  );
}
