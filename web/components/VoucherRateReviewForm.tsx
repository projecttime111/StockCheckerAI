"use client";

import { useMutation } from "@tanstack/react-query";
import { Calculator, CheckCircle2, CircleDollarSign, Files, PauseCircle, ShieldX } from "lucide-react";
import { useMemo, useState } from "react";
import { Field, PrimaryButton, SecondaryButton, SelectInput, TextInput } from "@/components/FormControls";
import { api } from "@/lib/api";

export type VoucherRateAction =
  | "CONFIRM_ZERO_VALUE"
  | "DERIVE_RATE"
  | "TREAT_AS_FINANCIAL_ADJUSTMENT"
  | "ENTER_VERIFIED_RATE"
  | "EXCLUDE_VOUCHER"
  | "KEEP_UNRESOLVED";

type RateContext = {
  eligibleActions?: VoucherRateAction[];
  voucherType?: string;
  voucherNumber?: string;
  voucherDate?: string;
  partyName?: string;
  productName?: string;
  quantity?: number | string;
  amount?: number | string;
  rate?: number | string;
  rateSource?: string;
  derivedRate?: number | string;
};

type RateReviewItem = {
  id: string;
  code: string;
  context?: RateContext;
};

type BulkPreview = {
  action: VoucherRateAction;
  matchingRows: number;
  matchingVouchers: number;
  confirmation: string;
  rowsTruncated: boolean;
  rows: Array<{
    issueId: string;
    row: number;
    voucherType?: string;
    voucherNumber?: string;
    productName?: string;
    quantity?: number | string;
    amount?: number | string;
    derivedRate?: number | string;
  }>;
};

const ACTIONS: Array<{ value: VoucherRateAction; label: string }> = [
  { value: "CONFIRM_ZERO_VALUE", label: "Confirm zero-valued / free item" },
  { value: "DERIVE_RATE", label: "Derive rate from amount and quantity" },
  { value: "TREAT_AS_FINANCIAL_ADJUSTMENT", label: "Treat as financial adjustment" },
  { value: "ENTER_VERIFIED_RATE", label: "Enter verified source rate" },
  { value: "EXCLUDE_VOUCHER", label: "Exclude entire voucher from this commit" },
  { value: "KEEP_UNRESOLVED", label: "Keep unresolved" }
];

const CONFIRMATIONS: Record<Exclude<VoucherRateAction, "KEEP_UNRESOLVED">, string> = {
  CONFIRM_ZERO_VALUE: "CONFIRM ZERO VALUE",
  DERIVE_RATE: "DERIVE RATE",
  TREAT_AS_FINANCIAL_ADJUSTMENT: "TREAT AS FINANCIAL ADJUSTMENT",
  ENTER_VERIFIED_RATE: "APPLY VERIFIED RATE",
  EXCLUDE_VOUCHER: "EXCLUDE VOUCHER"
};

export function VoucherRateReviewForm({ sessionId, item, onResolved, onRebuild, rebuilding = false }: {
  sessionId: string;
  item: RateReviewItem;
  onResolved: (workspace: unknown) => Promise<unknown> | unknown;
  onRebuild: () => Promise<unknown>;
  rebuilding?: boolean;
}) {
  const eligible = ACTIONS.filter((candidate) => item.context?.eligibleActions?.includes(candidate.value));
  const [selectedAction, setAction] = useState<VoucherRateAction | "">("");
  const action = eligible.some((candidate) => candidate.value === selectedAction)
    ? selectedAction : eligible[0]?.value ?? "";
  const [verifiedRate, setVerifiedRate] = useState("");
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [bulkConfirmed, setBulkConfirmed] = useState(false);
  const [preview, setPreview] = useState<BulkPreview | null>(null);
  const rebuild = useMutation({ mutationFn: onRebuild });

  const resolve = useMutation({
    mutationFn: (bulk: boolean) => {
      if (!action) throw new Error("Choose a resolution");
      if (bulk) {
        if (!preview) throw new Error("Preview matching rows first");
        return api(`/api/import-sessions/${sessionId}/rate-review/bulk/resolve`, {
          method: "POST",
          body: JSON.stringify({ sourceIssueId: item.id, action, verifiedRate: numberOrUndefined(verifiedRate),
            reason: reason.trim(), expectedCount: preview.matchingRows, confirmation: preview.confirmation })
        });
      }
      return api(`/api/import-sessions/${sessionId}/rate-review/${item.id}/resolve`, {
        method: "POST",
        body: JSON.stringify({ action, verifiedRate: numberOrUndefined(verifiedRate), reason: reason.trim(),
          confirmation: action === "KEEP_UNRESOLVED" ? undefined : CONFIRMATIONS[action] })
      });
    },
    onSuccess: async (workspace) => { setPreview(null); await onResolved(workspace); }
  });

  const previewBulk = useMutation({
    mutationFn: () => {
      if (!action) throw new Error("Choose a resolution");
      return api<BulkPreview>(`/api/import-sessions/${sessionId}/rate-review/bulk/preview`, {
        method: "POST",
        body: JSON.stringify({ sourceIssueId: item.id, action, verifiedRate: numberOrUndefined(verifiedRate) })
      });
    },
    onSuccess: (result) => { setPreview(result); setBulkConfirmed(false); }
  });

  const explanation = useMemo(() => actionExplanation(action, item.context), [action, item.context]);
  const manualReady = action !== "ENTER_VERIFIED_RATE"
    || (Number(verifiedRate) > 0 && reason.trim().length >= 5);
  const singleReady = Boolean(action) && (action === "KEEP_UNRESOLVED" || confirmed) && manualReady;
  const pending = resolve.isPending || previewBulk.isPending;

  if (eligible.length === 0) {
    return (
      <div className="mt-3 space-y-3 border-l-2 border-moss pl-3">
        <p className="text-sm text-ink/65">Resolution options are missing from this saved review. Rebuild the workspace to refresh them.</p>
        <SecondaryButton type="button" disabled={rebuilding || rebuild.isPending}
          onClick={() => rebuild.mutate()}>
          {rebuilding || rebuild.isPending ? "Rebuilding workspace..." : "Rebuild to enable actions"}
        </SecondaryButton>
        {rebuild.error ? <p role="alert" className="text-sm text-coral">{rebuild.error.message}</p> : null}
      </div>
    );
  }

  return (
    <div className="mt-3 space-y-3 border-l-2 border-moss pl-3">
      <div className="grid gap-3 lg:grid-cols-[minmax(0,340px)_minmax(0,1fr)]">
        <Field label="Resolution">
          <SelectInput value={action} disabled={pending} onChange={(event) => {
            setAction(event.target.value as VoucherRateAction);
            setConfirmed(false);
            setBulkConfirmed(false);
            setPreview(null);
          }}>
            <option value="">Choose resolution</option>
            {eligible.map((candidate) => (
              <option key={candidate.value} value={candidate.value}>{candidate.label}</option>
            ))}
          </SelectInput>
        </Field>
        <div className="text-sm leading-6 text-ink/65">
          {explanation}
          {item.context?.derivedRate ? <div className="font-semibold text-moss">Derived rate: {formatNumber(item.context.derivedRate)}</div> : null}
        </div>
      </div>

      {action === "ENTER_VERIFIED_RATE" ? (
        <div className="grid gap-3 sm:grid-cols-[180px_minmax(0,1fr)]">
          <Field label="Verified source rate">
            <TextInput type="number" min="0.000001" step="any" value={verifiedRate}
              onChange={(event) => setVerifiedRate(event.target.value)} disabled={pending} />
          </Field>
          <Field label="Source evidence / reason">
            <TextInput maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)}
              placeholder="For example: checked voucher in Tally" disabled={pending} />
          </Field>
        </div>
      ) : action === "TREAT_AS_FINANCIAL_ADJUSTMENT" || action === "EXCLUDE_VOUCHER" ? (
        <Field label="Review note (optional)">
          <TextInput maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} disabled={pending} />
        </Field>
      ) : null}

      {action && action !== "KEEP_UNRESOLVED" ? (
        <label className="flex items-start gap-2 text-sm">
          <input type="checkbox" className="mt-1" checked={confirmed}
            onChange={(event) => setConfirmed(event.target.checked)} disabled={pending} />
          <span>{confirmationLabel(action)}</span>
        </label>
      ) : null}

      <div className="flex flex-wrap gap-2">
        <PrimaryButton type="button" className="h-auto min-h-10 gap-2 py-2 text-left"
          disabled={!singleReady || pending} onClick={() => resolve.mutate(false)}>
          {actionIcon(action)}{resolve.isPending ? "Saving resolution..." : actionButtonLabel(action)}
        </PrimaryButton>
        {action && action !== "KEEP_UNRESOLVED" ? (
          <SecondaryButton type="button" className="h-auto min-h-10 gap-2 py-2 text-left"
            disabled={!manualReady || pending} onClick={() => previewBulk.mutate()}>
            <Files size={16} />{previewBulk.isPending ? "Checking matches..." : "Preview matching rows"}
          </SecondaryButton>
        ) : null}
      </div>

      {preview ? (
        <div className="space-y-3 border-y border-ink/10 bg-mint/30 px-3 py-3">
          <div className="text-sm font-semibold">{preview.matchingRows} matching rows across {preview.matchingVouchers} vouchers</div>
          <div className="max-h-48 overflow-auto text-xs">
            {preview.rows.map((row) => (
              <div key={row.issueId} className="grid gap-1 border-t border-ink/10 py-2 sm:grid-cols-[70px_130px_minmax(0,1fr)_100px]">
                <span>Row {row.row}</span><span>{row.voucherNumber || row.voucherType || "Voucher"}</span>
                <span>{row.productName || "Unresolved item"}</span>
                <span>{row.derivedRate ? `Rate ${formatNumber(row.derivedRate)}` : row.amount ? `Amount ${formatNumber(row.amount)}` : "No value"}</span>
              </div>
            ))}
          </div>
          {preview.rowsTruncated ? <p className="text-xs text-ink/55">Showing the first 100 matching rows.</p> : null}
          <label className="flex items-start gap-2 text-sm">
            <input type="checkbox" className="mt-1" checked={bulkConfirmed}
              onChange={(event) => setBulkConfirmed(event.target.checked)} disabled={pending} />
            <span>I reviewed this preview and want to apply the same resolution to all {preview.matchingRows} exact matches.</span>
          </label>
          <PrimaryButton type="button" className="h-auto min-h-10 gap-2 py-2 text-left"
            disabled={!bulkConfirmed || pending} onClick={() => resolve.mutate(true)}>
            <Files size={16} />Apply to {preview.matchingRows} matching rows
          </PrimaryButton>
        </div>
      ) : null}

      {resolve.error || previewBulk.error ? (
        <p role="alert" className="text-sm text-coral">{(resolve.error || previewBulk.error)?.message}</p>
      ) : null}
    </div>
  );
}

function actionExplanation(action: VoucherRateAction | "", context?: RateContext) {
  switch (action) {
    case "CONFIRM_ZERO_VALUE": return "Records this as a genuine free, scheme, or sample line. No price is invented.";
    case "DERIVE_RATE": return "Calculates the historical rate from this row's own absolute amount divided by its absolute quantity.";
    case "TREAT_AS_FINANCIAL_ADJUSTMENT": return "Posts the amount-only debit/credit note as a financial adjustment. It creates no product or stock movement.";
    case "ENTER_VERIFIED_RATE": return "Uses the rate you verified from Tally or another source record. A reason is mandatory.";
    case "EXCLUDE_VOUCHER": return `Skips the entire voucher ${context?.voucherNumber || ""} from dry run and final commit.`;
    case "KEEP_UNRESOLVED": return "Leaves this row blocked in the Review Queue without changing staged data.";
    default: return "Choose how this missing or zero rate should be handled.";
  }
}

function confirmationLabel(action: Exclude<VoucherRateAction, "KEEP_UNRESOLVED">) {
  switch (action) {
    case "CONFIRM_ZERO_VALUE": return "I confirm this item was genuinely supplied free or at zero value.";
    case "DERIVE_RATE": return "I confirm amount and quantity belong to this same voucher line.";
    case "TREAT_AS_FINANCIAL_ADJUSTMENT": return "I confirm this is an amount-only accounting adjustment and must not affect stock.";
    case "ENTER_VERIFIED_RATE": return "I verified this historical rate against source evidence.";
    case "EXCLUDE_VOUCHER": return "I understand the entire voucher will be omitted from this import commit.";
  }
}

function actionButtonLabel(action: VoucherRateAction | "") {
  switch (action) {
    case "KEEP_UNRESOLVED": return "Keep unresolved";
    case "EXCLUDE_VOUCHER": return "Confirm voucher exclusion";
    case "TREAT_AS_FINANCIAL_ADJUSTMENT": return "Confirm financial adjustment";
    case "ENTER_VERIFIED_RATE": return "Apply verified rate";
    case "DERIVE_RATE": return "Confirm derived rate";
    case "CONFIRM_ZERO_VALUE": return "Confirm zero-valued item";
    default: return "Choose a resolution";
  }
}

function actionIcon(action: VoucherRateAction | "") {
  if (action === "DERIVE_RATE") return <Calculator size={16} />;
  if (action === "TREAT_AS_FINANCIAL_ADJUSTMENT" || action === "ENTER_VERIFIED_RATE") return <CircleDollarSign size={16} />;
  if (action === "EXCLUDE_VOUCHER") return <ShieldX size={16} />;
  if (action === "KEEP_UNRESOLVED") return <PauseCircle size={16} />;
  return <CheckCircle2 size={16} />;
}

function numberOrUndefined(value: string) {
  const parsed = Number(value);
  return value.trim() && Number.isFinite(parsed) ? parsed : undefined;
}

function formatNumber(value: number | string) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? new Intl.NumberFormat("en-IN", { maximumFractionDigits: 6 }).format(parsed) : String(value);
}
