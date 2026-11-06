"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, CheckCircle2, Clock3, Store, ChevronLeft, ChevronRight, Search, ListChecks, X, RefreshCw } from "lucide-react";
import Link from "next/link";
import { useMemo, useState } from "react";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { PrimaryButton, SecondaryButton } from "@/components/FormControls";
import { api } from "@/lib/api";
import { RetailSaleReviewForm, RetailSaleDecision } from "@/components/RetailSaleReviewForm";
import { BulkRetailSaleForm } from "@/components/BulkRetailSaleForm";

type CashbookCandidate = {
  type: "CUSTOMER" | "SUPPLIER" | "SALES_INVOICE" | "PURCHASE_INVOICE";
  action: "MAP_CUSTOMER" | "MAP_SUPPLIER" | "MAP_SALES_INVOICE" | "MAP_PURCHASE_INVOICE";
  targetId: string;
  label: string;
  detail: string;
  confidence: number;
  reason: string;
};

export type CashbookReviewEntry = {
  id: string;
  sourceRowNumber: number;
  entryDate?: string;
  partyName: string;
  amount: number;
  direction: string;
  referenceNumber?: string;
  cashbookMatchStatus: string;
  matchReason?: string;
  sourceLedgerDetails?: string;
  candidates: CashbookCandidate[];
};

type BulkResult = {
  requestedCount: number;
  recordedCount: number;
  duplicateCount: number;
  failedCount: number;
  recordedAmount: number;
  results: { entryId: string; status: string; message: string; sourceRowNumber?: number }[];
};

export function CashbookReviewPanel({ sessionId, currency = "INR" }: { sessionId: string; currency?: string }) {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<Record<string, string>>({});
  const [page, setPage] = useState(0);
  const [notice, setNotice] = useState("");
  const [keyword, setKeyword] = useState("");
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [bulkResult, setBulkResult] = useState<BulkResult | null>(null);
  const queryKey = ["smart-import-cashbook-review", sessionId];
  const rows = useQuery({
    queryKey,
    queryFn: () => api<CashbookReviewEntry[]>(`/api/import-sessions/${sessionId}/cashbook-review`),
    enabled: Boolean(sessionId)
  });
  const resolve = useMutation({
    mutationFn: ({ entryId, ...decision }: { entryId: string; action: string; targetId?: string; confirmation?: string } & Partial<Omit<RetailSaleDecision, "confirmation">>) =>
      api(`/api/import-sessions/${sessionId}/cashbook-review/${entryId}/resolve`, {
        method: "POST",
        body: JSON.stringify(decision)
      }),
    onSuccess: async (_result, decision) => {
      setSelectedIds((current) => current.filter((id) => id !== decision.entryId));
      setNotice(decision.action === "RECORD_RETAIL_SALE" ? "Retail counter sale recorded. Stock unchanged." : "Cashbook entry updated.");
      await Promise.all([
        queryClient.invalidateQueries({ queryKey }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-staging", sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-commit", sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-dry-run", sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["retail-counter-sales"] })
      ]);
    }
  });
  const bulk = useMutation({
    mutationFn: (decision: RetailSaleDecision) => api<BulkResult>(`/api/import-sessions/${sessionId}/cashbook-review/retail-sales/bulk`, {
      method: "POST",
      body: JSON.stringify({
        entryIds: selectedIds,
        collectionMethod: decision.collectionMethod,
        confirmation: `RECORD ${selectedIds.length} RETAIL SALES`,
        note: decision.note,
        receivedFromRetailSales: decision.receivedFromRetailSales,
        notAlreadyRecorded: decision.notAlreadyRecorded
      })
    }),
    onSuccess: async (result) => {
      setBulkResult(result);
      setNotice(`${result.recordedCount} retail sales recorded, ${result.duplicateCount} duplicates skipped, ${result.failedCount} failed. Stock unchanged.`);
      setSelectedIds(result.results.filter((item) => item.status === "FAILED").map((item) => item.entryId));
      await Promise.all([
        queryClient.invalidateQueries({ queryKey }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-staging", sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-commit", sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-dry-run", sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["retail-counter-sales"] })
      ]);
    }
  });

  const recheck = useMutation({
    mutationFn: () => api<{ unpostedReviewCount: number }>(`/api/import-sessions/${sessionId}/cashbook-review/recheck`, { method: "POST" }),
    onSuccess: async (result) => {
      setNotice(`${result.unpostedReviewCount} unposted entries need review. Posted payments were not changed.`);
      setSelectedIds([]);
      setPage(0);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-staging", sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-session", sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-commit", sessionId] })
      ]);
    }
  });
  const recheckControl = <div className="py-3">
    <SecondaryButton className="gap-2" disabled={recheck.isPending || resolve.isPending || bulk.isPending} onClick={() => recheck.mutate()}>
      <RefreshCw size={16} /> {recheck.isPending ? "Rechecking..." : "Recheck unposted entries"}
    </SecondaryButton>
    {recheck.error ? <ErrorState error={recheck.error} /> : null}
  </div>;
  const allRows = rows.data ?? [];
  const normalizedKeyword = keyword.trim().toLowerCase();
  const filteredRows = useMemo(() => allRows.filter((row) => !normalizedKeyword || [
    row.partyName, row.referenceNumber, row.direction, row.entryDate, String(row.sourceRowNumber)
  ].some((value) => String(value ?? "").toLowerCase().includes(normalizedKeyword))), [allRows, normalizedKeyword]);
  const eligibleFilteredRows = filteredRows.filter((row) => Boolean(row.entryDate) && Number(row.amount) > 0);
  const selectedRows = allRows.filter((row) => selectedIds.includes(row.id));

  if (rows.isLoading) return <LoadingState label="Loading cashbook review entries..." />;
  if (rows.error) return <ErrorState error={rows.error} />;
  if (!rows.data?.length) {
    return <div className="space-y-3 py-4 text-sm"><p role="status">{notice || "No unresolved cashbook entries."}</p>{recheckControl}{bulkResult ? <BulkResultSummary result={bulkResult} currency={currency} /> : null}<Link className="text-moss underline" href="/sales/retail-counter">View retail counter sales</Link></div>;
  }
  const lastPage = Math.max(0, Math.ceil(filteredRows.length / 10) - 1);
  const activePage = Math.min(page, lastPage);

  return (
    <div className="divide-y divide-ink/10 border-y border-ink/10">
      {recheckControl}
      {notice ? <div role="status" className="py-3 text-sm text-moss">{notice} <Link className="underline" href="/sales/retail-counter">View retail counter sales</Link></div> : null}
      {bulkResult ? <BulkResultSummary result={bulkResult} currency={currency} /> : null}
      <div className="space-y-3 py-4">
        <label className="relative block">
          <span className="sr-only">Filter cashbook entries</span>
          <Search className="pointer-events-none absolute left-3 top-3 text-ink/45" size={16} />
          <input aria-label="Filter cashbook entries" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage(0); }}
            placeholder="Filter by keyword, for example Cash or GPay" className="focus-ring h-10 w-full rounded border border-ink/20 bg-white pl-9 pr-3 text-sm" />
        </label>
        <div className="flex flex-wrap items-center gap-2">
          <SecondaryButton className="h-auto min-h-10 gap-2 py-2" disabled={!eligibleFilteredRows.length || bulk.isPending}
            onClick={() => setSelectedIds(eligibleFilteredRows.slice(0, 200).map((row) => row.id))}>
            <ListChecks size={16} /> Select all {Math.min(eligibleFilteredRows.length, 200)} filtered
          </SecondaryButton>
          <SecondaryButton className="gap-2" disabled={!selectedIds.length || bulk.isPending} onClick={() => setSelectedIds([])}><X size={16} /> Clear selection</SecondaryButton>
          <span className="text-xs text-ink/55">{filteredRows.length} of {allRows.length} shown | {selectedRows.length} selected | Maximum 200 per batch</span>
        </div>
      </div>
      {selectedRows.length ? <BulkRetailSaleForm currency={currency} rows={selectedRows} pending={bulk.isPending}
        onCancel={() => setSelectedIds([])} onRecord={(decision) => {
          const total = selectedRows.reduce((sum, row) => sum + Number(row.amount || 0), 0);
          if (!window.confirm(`Record ${selectedRows.length} selected entries totaling ${formatMoney(total, currency)} as retail counter sales?`)) return;
          setBulkResult(null);
          bulk.mutate(decision);
        }} /> : null}
      {bulk.error ? <div className="py-3 text-sm text-coral">{bulk.error.message}</div> : null}
      {!filteredRows.length ? <div className="py-8 text-center text-sm text-ink/55">No unresolved entries match this keyword.</div> : null}
      {filteredRows.slice(activePage * 10, activePage * 10 + 10).map((row) => {
        const selectedValue = selected[row.id] ?? "";
        return (
          <CashbookReviewRow
            currency={currency}
            key={row.id}
            row={row}
            selectedValue={selectedValue}
            onSelect={(value) => setSelected((current) => ({ ...current, [row.id]: value }))}
            pending={resolve.isPending || bulk.isPending || recheck.isPending}
            checked={selectedIds.includes(row.id)}
            selectionDisabled={selectedIds.length >= 200 && !selectedIds.includes(row.id)}
            onChecked={(checked) => setSelectedIds((current) => checked
              ? Array.from(new Set([...current, row.id])).slice(0, 200)
              : current.filter((id) => id !== row.id))}
            error={resolve.variables?.entryId === row.id ? resolve.error : null}
            onRetail={(decision) => resolve.mutate({ entryId: row.id, action: "RECORD_RETAIL_SALE", ...decision })}
            onPost={() => {
              const [action, targetId] = selectedValue.split("|");
              if (!action || !targetId) return;
              if (!window.confirm("Post this cashbook entry as a payment to the selected record?")) return;
              resolve.mutate({ entryId: row.id, action, targetId, confirmation: "POST PAYMENT" });
            }}
            onIgnore={() => {
              if (!window.confirm("Exclude this entry from customer/supplier payment posting? No payment will be created.")) return;
              resolve.mutate({ entryId: row.id, action: "IGNORE" });
            }}
            onKeep={() => resolve.mutate({ entryId: row.id, action: "KEEP_UNRESOLVED" })}
          />
        );
      })}
      <div className="flex items-center justify-between gap-3 py-3 text-sm">
        <span>{filteredRows.length} filtered unresolved entries | Page {activePage + 1} of {lastPage + 1}</span>
        <div className="flex gap-2">
          <SecondaryButton title="Previous page" aria-label="Previous cashbook page" disabled={!activePage || resolve.isPending || bulk.isPending} onClick={() => setPage(activePage - 1)}><ChevronLeft size={16} /></SecondaryButton>
          <SecondaryButton title="Next page" aria-label="Next cashbook page" disabled={activePage === lastPage || resolve.isPending || bulk.isPending} onClick={() => setPage(activePage + 1)}><ChevronRight size={16} /></SecondaryButton>
        </div>
      </div>
    </div>
  );
}

function CashbookReviewRow({
  currency, row, selectedValue, onSelect, pending, error, onPost, onIgnore, onKeep, onRetail, checked, selectionDisabled, onChecked
}: {
  currency: string;
  row: CashbookReviewEntry;
  selectedValue: string;
  onSelect: (value: string) => void;
  pending: boolean;
  error: Error | null;
  onPost: () => void;
  onIgnore: () => void;
  onKeep: () => void;
  onRetail: (decision: RetailSaleDecision) => void;
  checked: boolean;
  selectionDisabled: boolean;
  onChecked: (checked: boolean) => void;
}) {
  const [retailOpen, setRetailOpen] = useState(false);
  const candidate = useMemo(
    () => row.candidates.find((item) => `${item.action}|${item.targetId}` === selectedValue),
    [row.candidates, selectedValue]
  );
  return (
    <section className="grid min-w-0 gap-4 py-5 lg:grid-cols-[minmax(0,0.8fr)_minmax(0,1.4fr)]">
      <div className="min-w-0 break-words">
        <div className="flex items-start gap-3">
          <input type="checkbox" className="mt-1" aria-label={`Select row ${row.sourceRowNumber} for bulk retail recording`}
            checked={checked} onChange={(event) => onChecked(event.target.checked)}
            disabled={pending || selectionDisabled || !row.entryDate || row.amount <= 0} />
          <div><div className="flex flex-wrap items-center gap-2 text-sm"><strong>{row.partyName || "Unidentified party"}</strong><span className="text-ink/45">Row {row.sourceRowNumber}</span></div>
        <div className="mt-2 text-sm text-ink/65">{row.entryDate || "No date"} | {row.direction} | {formatMoney(row.amount, currency)}</div>
        <div className="mt-1 text-xs text-ink/50">{row.referenceNumber || "No reference"}</div>
        <div className="mt-3 text-xs leading-5 text-amber-800">{row.matchReason || row.cashbookMatchStatus}</div>
        {row.sourceLedgerDetails ? <div className="mt-2 break-words text-xs text-ink/70">Source ledgers: {row.sourceLedgerDetails}</div> : null}
          </div>
        </div>
      </div>
      <div className="space-y-3">
        {retailOpen ? <RetailSaleReviewForm currency={currency} amount={row.amount} direction={row.direction} pending={pending} onRecord={onRetail} onCancel={() => setRetailOpen(false)} /> : <>
        <select
          aria-label={`Resolution target for row ${row.sourceRowNumber}`}
          className="focus-ring min-h-11 w-full border border-ink/20 bg-white px-3 text-sm"
          value={selectedValue}
          onChange={(event) => onSelect(event.target.value)}
        >
          <option value="">Select customer, supplier, or invoice</option>
          {row.candidates.map((item) => (
            <option key={`${item.action}:${item.targetId}`} value={`${item.action}|${item.targetId}`}>
              {readable(item.type)} | {item.label} | {Math.round(Number(item.confidence) * 100)}%
            </option>
          ))}
        </select>
        {candidate ? (
          <div className="border-l-2 border-moss/50 pl-3 text-xs leading-5 text-ink/65"><div className="font-semibold text-ink">{candidate.reason}</div><div>{candidate.detail}</div></div>
        ) : null}
        <div className="flex flex-wrap gap-2">
          <PrimaryButton className="gap-2" onClick={onPost} disabled={!candidate || pending}><CheckCircle2 size={16} /> Post payment</PrimaryButton>
          <SecondaryButton className="gap-2" onClick={() => setRetailOpen(true)} disabled={pending || !row.entryDate || row.amount <= 0}><Store size={16} /> Retail counter sale</SecondaryButton>
          <SecondaryButton className="gap-2" onClick={onIgnore} disabled={pending}><Ban size={16} /> Ignore</SecondaryButton>
          <SecondaryButton className="gap-2" onClick={onKeep} disabled={pending}><Clock3 size={16} /> Keep unresolved</SecondaryButton>
        </div>
        </>}
        {error ? <div className="text-sm text-coral">{error.message}</div> : null}
      </div>
    </section>
  );
}

function readable(value: string) {
  return value.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function formatMoney(value: number, currency: string) {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(value || 0));
}

function BulkResultSummary({ result, currency }: { result: BulkResult; currency: string }) {
  const failures = result.results.filter((item) => item.status === "FAILED");
  return <div className="space-y-2 border-l-2 border-moss px-3 py-3 text-sm">
    <p><strong>Bulk result:</strong> {result.recordedCount} recorded, {result.duplicateCount} duplicates skipped, {result.failedCount} failed. Newly recorded amount: {formatMoney(result.recordedAmount, currency)}.</p>
    {failures.length ? <ul className="space-y-1 text-coral">{failures.map((failure) => <li key={failure.entryId}>Row {failure.sourceRowNumber ?? failure.entryId}: {failure.message}</li>)}</ul> : null}
  </div>;
}
