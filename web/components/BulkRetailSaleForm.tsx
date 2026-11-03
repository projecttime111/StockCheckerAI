"use client";

import { useMemo, useState } from "react";
import { Store } from "lucide-react";
import { Field, PrimaryButton, SecondaryButton, SelectInput } from "@/components/FormControls";
import type { RetailSaleDecision } from "@/components/RetailSaleReviewForm";

type BulkRow = { entryDate?: string; amount: number; direction: string };

export function BulkRetailSaleForm({ rows, pending, onRecord, onCancel }: {
  rows: BulkRow[];
  pending: boolean;
  onRecord: (decision: RetailSaleDecision) => void;
  onCancel: () => void;
}) {
  const [method, setMethod] = useState<RetailSaleDecision["collectionMethod"] | "">("");
  const [received, setReceived] = useState(false);
  const [notRecorded, setNotRecorded] = useState(false);
  const [note, setNote] = useState("");
  const total = useMemo(() => rows.reduce((sum, row) => sum + Number(row.amount || 0), 0), [rows]);
  const dates = rows.map((row) => row.entryDate).filter((date): date is string => Boolean(date)).sort();
  const needsExplanation = rows.some((row) => row.direction !== "RECEIPT");
  const ready = rows.length > 0 && method !== "" && received && notRecorded
    && (!needsExplanation || note.trim().length > 0);

  return (
    <form className="space-y-4 border-y border-moss/25 bg-mint/35 px-4 py-4" onSubmit={(event) => {
      event.preventDefault();
      if (!ready || pending || !method) return;
      onRecord({ collectionMethod: method, receivedFromRetailSales: received,
        notAlreadyRecorded: notRecorded, note: note.trim(), confirmation: "RECORD RETAIL SALES" });
    }}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h4 className="flex items-center gap-2 text-sm font-semibold"><Store size={16} />Bulk retail counter sales</h4>
          <p className="mt-1 text-xs text-ink/60">{rows.length} selected | {dateRange(dates)} | {formatMoney(total)}</p>
        </div>
        <span className="text-xs text-ink/55">Amount only | Stock unchanged</span>
      </div>
      <Field label="Collection method for every selected entry">
        <SelectInput value={method} onChange={(event) => setMethod(event.target.value as typeof method)} required disabled={pending}>
          <option value="">Select method</option>
          <option value="CASH">Cash</option>
          <option value="UPI">UPI / GPay</option>
          <option value="CASH_AND_UPI">Cash + UPI combined</option>
        </SelectInput>
      </Field>
      {needsExplanation ? <p role="note" className="text-sm text-amber-800">Some selected rows have PAYMENT or UNKNOWN source direction. Explain why all selected rows represent retail money received.</p> : null}
      <Field label={needsExplanation ? "Reason these are retail receipts" : "Shared note (optional)"}>
        <textarea className="focus-ring min-h-20 w-full rounded border border-ink/20 bg-white p-2 text-sm" maxLength={500}
          value={note} onChange={(event) => setNote(event.target.value)} required={needsExplanation} disabled={pending} />
      </Field>
      <label className="flex items-start gap-2 text-sm">
        <input type="checkbox" className="mt-1" checked={received} onChange={(event) => setReceived(event.target.checked)} disabled={pending} />
        <span>I checked these entries and confirm they are money received from retail sales, not expenses, loans, bank deposits, or transfers.</span>
      </label>
      <label className="flex items-start gap-2 text-sm">
        <input type="checkbox" className="mt-1" checked={notRecorded} onChange={(event) => setNotRecorded(event.target.checked)} disabled={pending} />
        <span>These amounts are not already included in imported invoices, payments, another retail total, or each other.</span>
      </label>
      <div className="flex flex-wrap gap-2">
        <PrimaryButton type="submit" disabled={!ready || pending} className="h-auto min-h-10 gap-2 py-2 text-left">
          <Store className="shrink-0" size={16} />{pending ? "Recording selected entries..." : `Confirm and record ${rows.length} retail sales`}
        </PrimaryButton>
        <SecondaryButton type="button" onClick={onCancel} disabled={pending}>Cancel bulk selection</SecondaryButton>
      </div>
    </form>
  );
}

function dateRange(dates: string[]) {
  if (!dates.length) return "No dates";
  return dates[0] === dates[dates.length - 1] ? dates[0] : `${dates[0]} to ${dates[dates.length - 1]}`;
}

function formatMoney(value: number) {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 2 }).format(value);
}
