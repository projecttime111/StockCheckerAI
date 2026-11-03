"use client";

export type SnapshotDates = {
  snapshotDate: string;
  snapshotDateConfirmed: boolean;
  outstandingSnapshotDate: string;
  outstandingSnapshotDateConfirmed: boolean;
};

export function snapshotDatesFromSummary(summary?: Record<string, unknown>): SnapshotDates {
  const current = summary?.snapshotDatePolicyVersion === 1;
  return {
    snapshotDate: current ? String(summary?.requestedSnapshotDate || "") : "",
    snapshotDateConfirmed: current && summary?.snapshotDateConfirmed === true,
    outstandingSnapshotDate: current ? String(summary?.outstandingSnapshotDate || "") : "",
    outstandingSnapshotDateConfirmed: current && summary?.outstandingSnapshotDateConfirmed === true
  };
}

export function snapshotDatesValid(value: SnapshotDates, stockRequired = false, outstandingRequired = false) {
  return (!stockRequired || !!value.snapshotDate)
    && (!outstandingRequired || !!value.outstandingSnapshotDate)
    && (!!value.snapshotDate === value.snapshotDateConfirmed)
    && (!!value.outstandingSnapshotDate === value.outstandingSnapshotDateConfirmed);
}

export function snapshotDatesMatch(value: SnapshotDates, summary?: Record<string, unknown>) {
  if (summary?.snapshotDatePolicyVersion !== 1 || !snapshotDatesValid(value)) return false;
  const saved = snapshotDatesFromSummary(summary);
  return value.snapshotDate === saved.snapshotDate
    && value.snapshotDateConfirmed === saved.snapshotDateConfirmed
    && value.outstandingSnapshotDate === saved.outstandingSnapshotDate
    && value.outstandingSnapshotDateConfirmed === saved.outstandingSnapshotDateConfirmed;
}

export function SmartImportSnapshotDates({ value, onChange, stockMissing, outstandingMissing, disabled }: {
  value: SnapshotDates;
  onChange: (value: SnapshotDates) => void;
  stockMissing: number;
  outstandingMissing: number;
  disabled?: boolean;
}) {
  return (
    <fieldset disabled={disabled} className="mt-5 grid min-w-0 gap-4 border-t border-ink/10 pt-4 sm:grid-cols-2">
      <legend className="px-1 text-sm font-semibold">Snapshot dates</legend>
      <div className="min-w-0 space-y-2">
        <label className="block text-sm" htmlFor="stock-as-of-date">Closing-stock as-of date{stockMissing > 0 ? " (required)" : ""}</label>
        <input id="stock-as-of-date" type="date" className="focus-ring h-10 w-full min-w-0 rounded border border-ink/15 bg-white px-3 text-sm"
          required={stockMissing > 0} value={value.snapshotDate}
          onChange={(event) => onChange({ ...value, snapshotDate: event.target.value, snapshotDateConfirmed: false })} />
        <p className="text-xs text-ink/60">{stockMissing} stock rows without a source date</p>
        <label className="flex items-start gap-2 text-sm">
          <input type="checkbox" className="mt-1" disabled={!value.snapshotDate} checked={value.snapshotDateConfirmed}
            onChange={(event) => onChange({ ...value, snapshotDateConfirmed: event.target.checked })} />
          I confirm this as-of date for undated closing-stock rows.
        </label>
      </div>
      <div className="min-w-0 space-y-2">
        <label className="block text-sm" htmlFor="outstanding-as-of-date">Outstanding as-of date{outstandingMissing > 0 ? " (required)" : ""}</label>
        <input id="outstanding-as-of-date" type="date" className="focus-ring h-10 w-full min-w-0 rounded border border-ink/15 bg-white px-3 text-sm"
          required={outstandingMissing > 0} value={value.outstandingSnapshotDate}
          onChange={(event) => onChange({ ...value, outstandingSnapshotDate: event.target.value, outstandingSnapshotDateConfirmed: false })} />
        <p className="text-xs text-ink/60">{outstandingMissing} outstanding rows without a source date</p>
        <label className="flex items-start gap-2 text-sm">
          <input type="checkbox" className="mt-1" disabled={!value.outstandingSnapshotDate} checked={value.outstandingSnapshotDateConfirmed}
            onChange={(event) => onChange({ ...value, outstandingSnapshotDateConfirmed: event.target.checked })} />
          I confirm this as-of date for undated customer and supplier balances.
        </label>
      </div>
    </fieldset>
  );
}

export function SavedSnapshotDates({ summary, changed }: { summary?: Record<string, unknown>; changed?: boolean }) {
  if (!summary) return null;
  if (summary.snapshotDatePolicyVersion !== 1) return <p role="alert" className="mb-4 text-sm text-coral">This preview predates snapshot-date confirmation. Run a new dry run before committing.</p>;
  return (
    <div className="mb-4 border-b border-ink/10 pb-3 text-sm leading-6">
      <div>Closing-stock date for undated rows: <strong>{String(summary.requestedSnapshotDate || "Not needed; source dates retained")}</strong></div>
      <div>Outstanding date for undated rows: <strong>{String(summary.outstandingSnapshotDate || "Not needed; source dates retained")}</strong></div>
      {changed ? <p role="alert" className="text-coral">Dates changed or unconfirmed. Run a new dry run before committing.</p> : null}
    </div>
  );
}
