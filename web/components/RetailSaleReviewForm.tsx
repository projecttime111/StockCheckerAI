"use client";

import { useState } from "react";
import { CheckCircle2 } from "lucide-react";
import { Field, PrimaryButton, SecondaryButton, SelectInput } from "@/components/FormControls";

export type RetailSaleDecision = {
  collectionMethod: "CASH" | "UPI" | "CASH_AND_UPI";
  receivedFromRetailSales: boolean;
  notAlreadyRecorded: boolean;
  note: string;
  confirmation: "RECORD RETAIL SALES";
};

export function RetailSaleReviewForm({ amount, direction, pending, onRecord, onCancel }: {
  amount: number;
  direction: string;
  pending: boolean;
  onRecord: (decision: RetailSaleDecision) => void;
  onCancel: () => void;
}) {
  const [method, setMethod] = useState<RetailSaleDecision["collectionMethod"] | "">("");
  const [received, setReceived] = useState(false);
  const [notRecorded, setNotRecorded] = useState(false);
  const [note, setNote] = useState("");
  const needsExplanation = direction !== "RECEIPT";
  const ready = method !== "" && received && notRecorded && (!needsExplanation || note.trim().length > 0) && amount > 0;

  return (
    <form className="space-y-3 border-l-2 border-moss pl-3" onSubmit={(event) => {
      event.preventDefault();
      if (!ready || pending || !method) return;
      onRecord({ collectionMethod: method, receivedFromRetailSales: received, notAlreadyRecorded: notRecorded,
        note: note.trim(), confirmation: "RECORD RETAIL SALES" });
    }}>
      <h4 className="text-sm font-semibold">Retail counter sale: {new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 2 }).format(amount)}</h4>
      <div className="text-xs text-ink/60">Amount only | Stock unchanged | Cost and margin unavailable</div>
      <Field label="Collection method">
        <SelectInput value={method} onChange={(event) => setMethod(event.target.value as typeof method)} required disabled={pending}>
          <option value="">Select method</option>
          <option value="CASH">Cash</option>
          <option value="UPI">UPI / GPay</option>
          <option value="CASH_AND_UPI">Cash + UPI combined</option>
        </SelectInput>
      </Field>
      {needsExplanation ? <p role="note" className="text-sm text-amber-800">Source direction: {direction}. Confirmation of money received and an explanation are required.</p> : null}
      <Field label={needsExplanation ? "Reason this is retail money received" : "Note (optional)"}>
        <textarea className="focus-ring min-h-20 w-full rounded border border-ink/20 p-2 text-sm" maxLength={500}
          value={note} onChange={(event) => setNote(event.target.value)} required={needsExplanation} disabled={pending} />
      </Field>
      <label className="flex items-start gap-2 text-sm">
        <input type="checkbox" className="mt-1" checked={received} onChange={(event) => setReceived(event.target.checked)} disabled={pending} />
        <span>I confirm this is money received from retail sales, not an expense, loan, bank deposit, or transfer.</span>
      </label>
      <label className="flex items-start gap-2 text-sm">
        <input type="checkbox" className="mt-1" checked={notRecorded} onChange={(event) => setNotRecorded(event.target.checked)} disabled={pending} />
        <span>This amount is not already included in imported sales invoices, payments, or another retail total.</span>
      </label>
      <div className="flex flex-wrap gap-2">
        <PrimaryButton type="submit" disabled={!ready || pending} className="h-auto min-h-10 gap-2 py-2 text-left"><CheckCircle2 className="shrink-0" size={16} />{pending ? "Recording..." : "Confirm and record retail sale"}</PrimaryButton>
        <SecondaryButton type="button" onClick={onCancel} disabled={pending}>Cancel</SecondaryButton>
      </div>
    </form>
  );
}
