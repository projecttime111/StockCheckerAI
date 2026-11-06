"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Layers3, Upload } from "lucide-react";
import Link from "next/link";
import { FormEvent, useEffect, useMemo, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { EmptyState } from "@/components/EmptyState";
import { Field, PrimaryButton, SecondaryButton, SelectInput } from "@/components/FormControls";
import { useAccess } from "@/lib/business";
import { Pagination } from "@/components/Pagination";
import { QueryFeedback, MutationFeedback } from "@/components/QueryFeedback";
import { Panel } from "@/components/Panel";
import { api, Page } from "@/lib/api";

type NegativeStockImportPolicy = "BLOCK" | "IMPORT_AS_IS" | "SKIP_STOCK_MOVEMENT";
type VoucherStockImpactMode = "CREATE_INVOICES_AND_STOCK_MOVEMENTS" | "CREATE_INVOICES_ONLY" | "APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE" | "BLOCK_IF_SNAPSHOT_EXISTS";
type ImportBatch = {
  id: string;
  sourceType: string;
  status: string;
  originalFileName: string;
  rowCount: number;
  validCount: number;
  errorCount: number;
  committedAt?: string;
  rolledBackAt?: string;
  rollbackStatus?: string;
  importPurpose?: string;
  negativeStockPolicy?: NegativeStockImportPolicy | string;
  voucherStockImpactMode?: VoucherStockImpactMode | string;
  hasStockSnapshot?: boolean;
  latestSnapshotDate?: string;
  recommendedVoucherStockImpactMode?: VoucherStockImpactMode | string;
  voucherDateFrom?: string;
  voucherDateTo?: string;
  warningAboutDoubleCounting?: string;
};
type PreviewRow = Record<string, unknown>;
type ImportError = {
  rowNumber: number;
  fieldName: string;
  errorCode: string;
  message: string;
  severity?: string;
  rawValue?: string;
  suggestedFix?: string;
};
type Tenant = { id: string; name: string; allowNegativeStock: boolean };
type Reconciliation = Record<string, unknown>;
type UndoPreview = {
  canUndo: boolean;
  reasonIfCannotUndo: string;
  productsToDelete: Record<string, unknown>[];
  customersToDelete: Record<string, unknown>[];
  suppliersToDelete: Record<string, unknown>[];
  stockMovementsToReverse: Record<string, unknown>[];
  salesInvoicesToVoidOrDelete: Record<string, unknown>[];
  purchaseInvoicesToVoidOrDelete: Record<string, unknown>[];
  warnings: string[];
  irreversibleItems: Record<string, unknown>[];
};

const negativeStockCodes = new Set(["NEGATIVE_QUANTITY", "NEGATIVE_STOCK_IMPORTED", "NEGATIVE_STOCK_NOT_ALLOWED", "NEGATIVE_STOCK_SKIPPED"]);
const policyLabels: Record<NegativeStockImportPolicy, string> = {
  BLOCK: "Block import and fix Tally data",
  IMPORT_AS_IS: "Import negative stock as-is",
  SKIP_STOCK_MOVEMENT: "Import products but skip negative stock snapshot"
};
const voucherModeLabels: Record<Exclude<VoucherStockImpactMode, "BLOCK_IF_SNAPSHOT_EXISTS">, string> = {
  CREATE_INVOICES_ONLY: "Create invoices only, do not affect stock",
  CREATE_INVOICES_AND_STOCK_MOVEMENTS: "Create invoices and stock movements",
  APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE: "Apply stock movements only after latest snapshot date"
};

export default function ImportsPage() {
  const { can } = useAccess();
  const [page, setPage] = useState(0);
  const queryClient = useQueryClient();
  const [sourceType, setSourceType] = useState("CSV");
  const [file, setFile] = useState<File | null>(null);
  const [batchId, setBatchId] = useState<string>("");
  const [undoConfirmation, setUndoConfirmation] = useState("");
  const [negativeStockPolicy, setNegativeStockPolicy] = useState<NegativeStockImportPolicy>("BLOCK");
  const [voucherStockImpactMode, setVoucherStockImpactMode] = useState<VoucherStockImpactMode>("CREATE_INVOICES_AND_STOCK_MOVEMENTS");
  const [confirmStockMovementsAfterSnapshot, setConfirmStockMovementsAfterSnapshot] = useState(false);
  const [mapping, setMapping] = useState<Record<string, string>>({
    productName: "Item Name",
    sku: "SKU",
    unitCode: "Unit",
    openingStock: "Opening Stock",
    purchasePrice: "Purchase Price",
    salesPrice: "Sales Price",
    warehouseName: "Warehouse",
    movementType: "Movement Type",
    quantity: "Qty",
    rate: "Rate",
    movementDate: "Voucher Date"
  });

  const tenant = useQuery({ queryKey: ["tenant"], queryFn: () => api<Tenant>("/api/tenants/current") });
  const batches = useQuery({ queryKey: ["imports", page], queryFn: () => api<Page<ImportBatch>>(`/api/imports?size=20&page=${page}`) });
  const preview = useQuery({ queryKey: ["imports", batchId, "preview"], enabled: !!batchId, queryFn: () => api<PreviewRow[]>(`/api/imports/${batchId}/preview`) });
  const errors = useQuery({ queryKey: ["imports", batchId, "errors"], enabled: !!batchId, queryFn: () => api<ImportError[]>(`/api/imports/${batchId}/errors`) });
  const reconciliation = useQuery({ queryKey: ["imports", batchId, "reconciliation"], enabled: !!batchId, queryFn: () => api<Reconciliation>(`/api/imports/${batchId}/reconciliation`) });
  const undoPreview = useQuery({ queryKey: ["imports", batchId, "undo-preview"], enabled: !!batchId && can("imports.rollback"), queryFn: () => api<UndoPreview>(`/api/imports/${batchId}/undo-preview`) });

  const sourceColumns = useMemo(() => {
    const columns = new Set<string>();
    for (const row of preview.data ?? []) {
      const raw = row.rawMetadata && typeof row.rawMetadata === "object" ? (row.rawMetadata as Record<string, unknown>) : {};
      Object.keys(raw).forEach((key) => columns.add(key));
    }
    return Array.from(columns).sort((a, b) => a.localeCompare(b));
  }, [preview.data]);

  const previewRowsByNumber = useMemo(() => {
    const rows = new Map<number, PreviewRow>();
    for (const row of preview.data ?? []) {
      const rowNumber = Number(row.rowNumber);
      if (Number.isFinite(rowNumber)) rows.set(rowNumber, row);
    }
    return rows;
  }, [preview.data]);

  const upload = useMutation({
    mutationFn: async () => {
      if (!file) throw new Error("Select a file");
      const form = new FormData();
      form.append("file", file);
      return api<{ batchId: string }>(`/api/imports/upload?sourceType=${sourceType}`, { method: "POST", body: form });
    },
    onSuccess: (data) => {
      setBatchId(data.batchId);
      setNegativeStockPolicy("BLOCK");
      setConfirmStockMovementsAfterSnapshot(false);
      batches.refetch();
      errors.refetch();
      preview.refetch();
    }
  });
  const applyMapping = useMutation({
    mutationFn: () => api(`/api/imports/${batchId}/mapping`, { method: "POST", body: JSON.stringify({ mapping }) }),
    onSuccess: () => {
      preview.refetch();
      errors.refetch();
    }
  });
  const validate = useMutation({
    mutationFn: () => api(`/api/imports/${batchId}/validate`, { method: "POST", body: JSON.stringify({ negativeStockPolicy, voucherStockImpactMode, confirmStockMovementsAfterSnapshot }) }),
    onSuccess: () => {
      batches.refetch();
      errors.refetch();
      reconciliation.refetch();
      undoPreview.refetch();
    }
  });
  const undoImport = useMutation({
    mutationFn: () => {
      if (undoConfirmation !== "UNDO IMPORT") throw new Error("Type UNDO IMPORT to confirm");
      return api(`/api/imports/${batchId}/undo`, { method: "POST", body: JSON.stringify({ strategy: "SAFE_REVERSAL" }) });
    },
    onSuccess: () => {
      setUndoConfirmation("");
      queryClient.invalidateQueries();
    }
  });
  const replaceImport = useMutation({
    mutationFn: () => {
      if (undoConfirmation !== "UNDO IMPORT") throw new Error("Type UNDO IMPORT to confirm");
      return api(`/api/imports/${batchId}/replace`, { method: "POST" });
    },
    onSuccess: () => {
      setUndoConfirmation("");
      setBatchId("");
      setFile(null);
      queryClient.invalidateQueries();
    }
  });
  const commit = useMutation({
    mutationFn: () => api(`/api/imports/${batchId}/commit`, { method: "POST", body: JSON.stringify({ negativeStockPolicy, voucherStockImpactMode, confirmStockMovementsAfterSnapshot }) }),
    onSuccess: () => {
      batches.refetch();
      errors.refetch();
    },
    onError: async (error) => {
      if (error instanceof Error && error.message.includes("Fix validation errors")) {
        await api(`/api/imports/${batchId}/validate`, { method: "POST", body: JSON.stringify({ negativeStockPolicy, voucherStockImpactMode, confirmStockMovementsAfterSnapshot }) }).catch(() => undefined);
        batches.refetch();
        errors.refetch();
      }
    }
  });

  const recentBatches = batches.data?.content ?? [];
  const selectedBatch = recentBatches.find((batch) => batch.id === batchId);
  useEffect(() => {
    if (!selectedBatch) return;
    setVoucherStockImpactMode(normalizeVoucherMode(selectedBatch.voucherStockImpactMode));
    setConfirmStockMovementsAfterSnapshot(false);
  }, [selectedBatch?.id, selectedBatch?.voucherStockImpactMode]);
  const isSnapshotBatch = selectedBatch?.importPurpose === "STOCK_SNAPSHOT" || preview.data?.some((row) => row.importPurpose === "STOCK_SNAPSHOT");
  const isTallyPurchaseBatch = selectedBatch?.sourceType === "TALLY_XML" && preview.data?.some((row) => row.movementType === "PURCHASE" || row.movementType === "RETURN_OUT");
  const isVoucherBatch = selectedBatch?.importPurpose === "TRANSACTION_IMPORT" || preview.data?.some((row) => row.type === "STOCK_MOVEMENT");
  const hasStockSnapshot = Boolean(selectedBatch?.hasStockSnapshot ?? reconciliation.data?.hasStockSnapshot);
  const latestSnapshotDate = String(selectedBatch?.latestSnapshotDate || reconciliation.data?.latestSnapshotDate || "");
  const validationIssues = errors.data ?? [];
  const issueGroups = useMemo(() => {
    const groups = new Map<string, { code: string; severity: string; rows: number[] }>();
    for (const issue of validationIssues) {
      const level = severity(issue);
      const key = `${level}:${issue.errorCode}`;
      const group = groups.get(key) ?? { code: issue.errorCode, severity: level, rows: [] };
      if (!group.rows.includes(issue.rowNumber)) group.rows.push(issue.rowNumber);
      groups.set(key, group);
    }
    return Array.from(groups.values());
  }, [validationIssues]);
  const blockingErrors = validationIssues.filter((issue) => severity(issue) !== "WARNING");
  const warnings = validationIssues.filter((issue) => severity(issue) === "WARNING");
  const negativeStockIssues = validationIssues.filter(isNegativeStockIssue);
  const tenantAllowsNegativeStock = Boolean(tenant.data?.allowNegativeStock);
  const selectedPolicyCanResolveNegativeStock = negativeStockPolicy === "SKIP_STOCK_MOVEMENT" || (negativeStockPolicy === "IMPORT_AS_IS" && tenantAllowsNegativeStock);
  const effectiveBlockingErrors = blockingErrors.filter((issue) => !(isNegativeStockIssue(issue) && selectedPolicyCanResolveNegativeStock));
  const hasUnresolvedBlockingErrors = effectiveBlockingErrors.length > 0;
  const requiresStockImpactConfirmation = hasStockSnapshot && voucherStockImpactMode === "CREATE_INVOICES_AND_STOCK_MOVEMENTS";

  function submit(event: FormEvent) {
    event.preventDefault();
    upload.mutate();
  }

  function updateMapping(target: string, source: string) {
    setMapping((current) => ({ ...current, [target]: source }));
  }

  function selectBatch(batch: ImportBatch) {
    setBatchId(batch.id);
    setUndoConfirmation("");
    setNegativeStockPolicy(normalizePolicy(batch.negativeStockPolicy));
    setVoucherStockImpactMode(normalizeVoucherMode(batch.voucherStockImpactMode));
    setConfirmStockMovementsAfterSnapshot(false);
  }

  const mappingFields = [
    ["productName", "Product name"],
    ["sku", "SKU"],
    ["unitCode", "Unit"],
    ["openingStock", "Opening stock"],
    ["purchasePrice", "Purchase price"],
    ["salesPrice", "Sales price"],
    ["warehouseName", "Warehouse"],
    ["movementType", "Movement type"],
    ["quantity", "Movement quantity"],
    ["rate", "Movement rate"],
    ["movementDate", "Movement date"]
  ];

  return (
    <AppShell title="Imports" actions={<Link className="focus-ring inline-flex h-10 items-center gap-2 rounded bg-moss px-4 text-sm font-semibold text-white" href="/imports/smart"><Layers3 size={16} />Smart Import</Link>}>
      <QueryFeedback queries={[batches, preview, errors, reconciliation]} />
      <MutationFeedback mutation={commit} success="Import committed. Business records refreshed." />
      <div className="grid gap-4 xl:grid-cols-[360px_1fr]">
        <div className="space-y-4">
          <Panel title="Import Companion Data">
            <p className="text-sm text-ink/65">
              Upload Tally, Excel, CSV, XML, or JSON exports. Your accounting remains in Tally; StockPilot AI stages exported rows, validates mappings, and commits only inventory intelligence data.
            </p>
            <p className="mt-3 text-sm text-ink/60">
              Use <span className="font-medium text-ink">Tally XML</span> for Tally stock reports with DSPACCNAME/DSPSTKINFO rows. Generic XML is for simple non-Tally row exports.
            </p>
            <p className="mt-3 rounded bg-mint/70 p-3 text-xs text-moss">
              Closing Stock / Stock Report files set current stock. Re-importing the same file will not double stock.
            </p>
          </Panel>
          <Panel title="Upload File" action={<Upload size={18} />}>
            <form onSubmit={submit} className="space-y-3">
              <Field label="Source">
                <SelectInput value={sourceType} onChange={(event) => setSourceType(event.target.value)}>
                  <option value="CSV">CSV</option>
                  <option value="EXCEL">Excel</option>
                  <option value="JSON">JSON</option>
                  <option value="XML">XML</option>
                  <option value="TALLY">Tally</option>
                  <option value="TALLY_XML">Tally XML</option>
                  <option value="TALLY_EXCEL">Tally Excel</option>
                </SelectInput>
              </Field>
              <input className="block w-full text-sm" type="file" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
              {upload.error ? <p className="text-sm text-coral">{upload.error.message}</p> : null}
              <PrimaryButton className="w-full" disabled={!can("imports.upload") || upload.isPending}>{upload.isPending ? "Uploading..." : "Upload and preview"}</PrimaryButton>
            </form>
          </Panel>
          <Panel title="Recent Batches">
            <div className="space-y-2">
              {recentBatches.map((batch) => (
                <div key={batch.id} className="rounded border border-ink/10 p-3 text-sm">
                  <button className="focus-ring w-full text-left hover:text-moss" onClick={() => selectBatch(batch)}>
                    <div className="font-medium">{batch.originalFileName || batch.id}</div>
                    <div className="text-xs text-ink/50">{batch.sourceType} - {batch.status} - {batch.rowCount} rows</div>
                    <div className="mt-1 text-xs text-ink/45">Purpose: {batch.importPurpose === "STOCK_SNAPSHOT" ? "Stock Snapshot Import" : batch.importPurpose ?? "MASTER_IMPORT"} - Rollback: {batch.rollbackStatus ?? "NOT_ROLLED_BACK"}</div>
                    <div className="mt-1 text-xs text-ink/45">Negative stock policy: {normalizePolicy(batch.negativeStockPolicy).replaceAll("_", " ")}</div>
                    {batch.importPurpose === "TRANSACTION_IMPORT" ? <div className="mt-1 text-xs text-ink/45">Stock impact: {normalizeVoucherMode(batch.voucherStockImpactMode).replaceAll("_", " ")}</div> : null}
                  </button>
                  <div className="mt-3 flex flex-wrap gap-2">
                    <SecondaryButton onClick={() => selectBatch(batch)}>View reconciliation</SecondaryButton>
                    <SecondaryButton onClick={() => selectBatch(batch)} disabled={!can("imports.rollback") || batch.status !== "COMMITTED"}>Undo import</SecondaryButton>
                  </div>
                </div>
              ))}
              {!batches.error && !batches.isLoading && !recentBatches.length ? <EmptyState title="No imports yet.">Upload a Tally, Excel, CSV, XML, or JSON export to create the first staged import batch for this backend tenant.</EmptyState> : null}
            </div>
            <Pagination data={batches.data} page={page} onChange={setPage} />
          </Panel>
        </div>

        <div className="space-y-4">
          {batchId ? (
            <Panel title="Import Reconciliation">
              {reconciliation.isLoading ? <p className="text-sm text-ink/55">Loading reconciliation...</p> : (
                <div className="grid gap-3 md:grid-cols-4">
                  {[
                    ["Rows staged", reconciliation.data?.rowsStaged],
                    ["Products", reconciliation.data?.productsCreated],
                    ["Stock movements", reconciliation.data?.stockMovementsCreated],
                    ["Purchase items", reconciliation.data?.purchaseInvoiceItemsCreated],
                    ["Sales items", reconciliation.data?.salesInvoiceItemsCreated],
                    ["Rates derived", reconciliation.data?.purchaseRatesDerivedFromAmount],
                    ["Free items", reconciliation.data?.zeroCostPurchaseItemsImported],
                    ["Stock movements skipped", Number(reconciliation.data?.stockMovementsSkippedDueToInvoiceOnly ?? 0) + Number(reconciliation.data?.stockMovementsSkippedBeforeSnapshotDate ?? 0)],
                    ["Ledger lines skipped", reconciliation.data?.ledgerLinesSkipped],
                    ["Rollback", reconciliation.data?.rollbackStatus]
                  ].map(([label, value]) => (
                    <div key={String(label)} className="rounded border border-ink/10 p-3">
                      <div className="text-xs text-ink/50">{String(label)}</div>
                      <div className="mt-1 text-lg font-semibold">{String(value ?? "-")}</div>
                    </div>
                  ))}
                </div>
              )}
              <p className="mt-3 rounded bg-amber-50 p-3 text-xs text-amber-800">
                Closing stock and Tally stock reports are snapshots. StockPilot adjusts stock to match the imported closing quantity instead of adding it again. Re-importing the same closing stock file will not double stock.
              </p>
            </Panel>
          ) : null}

          {batchId ? (
            <Panel title="Undo this import?">
              {undoPreview.isLoading ? <p className="text-sm text-ink/55">Checking rollback impact...</p> : (
                <div className="space-y-3 text-sm">
                  <div className="grid gap-3 md:grid-cols-4">
                    <Impact label="Products removable" value={undoPreview.data?.productsToDelete?.length ?? 0} />
                    <Impact label="Movements reversed" value={undoPreview.data?.stockMovementsToReverse?.length ?? 0} />
                    <Impact label="Customers removable" value={undoPreview.data?.customersToDelete?.length ?? 0} />
                    <Impact label="Suppliers removable" value={undoPreview.data?.suppliersToDelete?.length ?? 0} />
                  </div>
                  {undoPreview.data?.reasonIfCannotUndo ? <p className="rounded border border-coral/25 bg-coral/10 p-3 text-coral">{undoPreview.data.reasonIfCannotUndo}</p> : null}
                  {undoPreview.data?.warnings?.length ? (
                    <div className="rounded border border-amber-300 bg-amber-50 p-3 text-amber-800">
                      <div className="font-medium">Warnings</div>
                      <ul className="mt-2 list-disc pl-5">
                        {undoPreview.data.warnings.slice(0, 5).map((warning, index) => <li key={index}>{warning}</li>)}
                      </ul>
                    </div>
                  ) : null}
                  {undoPreview.data?.irreversibleItems?.length ? <p className="rounded border border-coral/25 bg-coral/10 p-3 text-coral">This import has {undoPreview.data.irreversibleItems.length} item(s) that cannot be safely reversed.</p> : null}
                  <Field label="Type UNDO IMPORT to confirm">
                    <input className="w-full rounded border border-ink/15 px-3 py-2" value={undoConfirmation} onChange={(event) => setUndoConfirmation(event.target.value)} />
                  </Field>
                  <div className="flex flex-wrap gap-2">
                    <SecondaryButton onClick={() => undoImport.mutate()} disabled={!can("imports.rollback") || undoConfirmation !== "UNDO IMPORT" || undoImport.isPending || !undoPreview.data?.canUndo}>
                      {undoImport.isPending ? "Rolling back..." : "Undo Import"}
                    </SecondaryButton>
                    <PrimaryButton onClick={() => replaceImport.mutate()} disabled={!can("imports.rollback") || undoConfirmation !== "UNDO IMPORT" || replaceImport.isPending || !undoPreview.data?.canUndo}>
                      {replaceImport.isPending ? "Rolling back..." : "Undo and upload replacement"}
                    </PrimaryButton>
                  </div>
                  {undoImport.error ? <p className="text-sm text-coral">{undoImport.error.message}</p> : null}
                  {replaceImport.error ? <p className="text-sm text-coral">{replaceImport.error.message}</p> : null}
                  {undoImport.data ? <p className="text-sm text-moss">Import rolled back successfully.</p> : null}
                </div>
              )}
            </Panel>
          ) : null}

          {batchId ? (
            <Panel title="Column Mapping" action={<SecondaryButton onClick={() => applyMapping.mutate()} disabled={!can("imports.map") || applyMapping.isPending || !sourceColumns.length}>{applyMapping.isPending ? "Saving..." : "Apply mapping"}</SecondaryButton>}>
              {!sourceColumns.length ? <p className="text-sm text-ink/55">No source columns were detected for this batch.</p> : (
                <div className="grid gap-3 md:grid-cols-2">
                  {mappingFields.map(([target, label]) => (
                    <Field key={target} label={label}>
                      <SelectInput value={mapping[target] ?? ""} onChange={(event) => updateMapping(target, event.target.value)}>
                        <option value="">Keep detected value</option>
                        {sourceColumns.map((column) => <option key={column} value={column}>{column}</option>)}
                      </SelectInput>
                    </Field>
                  ))}
                </div>
              )}
              {applyMapping.error ? <p className="mt-3 text-sm text-coral">{applyMapping.error.message}</p> : null}
            </Panel>
          ) : null}

          {batchId && negativeStockIssues.length ? (
            <Panel title={`Negative Stock Found (${negativeStockIssues.length} rows)`}>
              <div className="grid gap-4 lg:grid-cols-[1fr_300px]">
                <div>
                  <p className="text-sm text-ink/65">
                    Tally stock reports can contain negative closing stock. Keep the original XML untouched and choose how StockPilot AI should handle only these negative stock snapshot rows.
                  </p>
                  <div className="mt-3 max-h-64 overflow-auto rounded border border-ink/10">
                    <table className="w-full text-left text-xs">
                      <thead className="bg-ink/[0.03] text-ink/50">
                        <tr>
                          <th className="px-3 py-2">Row</th>
                          <th className="px-3 py-2">Product</th>
                          <th className="px-3 py-2">Quantity</th>
                        </tr>
                      </thead>
                      <tbody>
                        {negativeStockIssues.map((issue) => {
                          const row = previewRowsByNumber.get(issue.rowNumber);
                          return (
                            <tr key={`${issue.rowNumber}-${issue.errorCode}`} className="border-t border-ink/10">
                              <td className="px-3 py-2">{issue.rowNumber}</td>
                              <td className="max-w-[320px] truncate px-3 py-2">{String(row?.productName ?? "Unknown product")}</td>
                              <td className="px-3 py-2">{String(issue.rawValue || row?.openingStock || "")}</td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                </div>
                <div className="space-y-2">
                  {(["BLOCK", "IMPORT_AS_IS", "SKIP_STOCK_MOVEMENT"] as NegativeStockImportPolicy[]).map((policy) => (
                    <label key={policy} className="flex gap-3 rounded border border-ink/10 p-3 text-sm">
                      <input
                        type="radio"
                        name="negativeStockPolicy"
                        value={policy}
                        checked={negativeStockPolicy === policy}
                        disabled={policy === "IMPORT_AS_IS" && !tenantAllowsNegativeStock}
                        onChange={() => setNegativeStockPolicy(policy)}
                      />
                      <span>
                        <span className="block font-medium text-ink">{policyLabels[policy]}</span>
                        <span className="mt-1 block text-xs text-ink/55">{policyHelp(policy, tenantAllowsNegativeStock)}</span>
                      </span>
                    </label>
                  ))}
                  {!tenantAllowsNegativeStock ? (
                    <p className="text-xs text-ink/55">Import as-is is disabled because this tenant does not allow negative stock. You can enable it in Settings, or use the recommended skip option.</p>
                  ) : null}
                  <p className="rounded bg-mint/70 p-3 text-xs text-moss">
                    Recommended for first import: import products and skip negative stock snapshots, then reconcile those products physically after the import.
                  </p>
                </div>
              </div>
            </Panel>
          ) : null}

          {batchId && isVoucherBatch ? (
            <Panel title="Stock Impact Mode">
              {hasStockSnapshot ? (
                <p className="mb-3 rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800">
                  Closing Stock has already been imported{latestSnapshotDate ? ` (${latestSnapshotDate})` : ""}. Creating stock movements from historical vouchers can double-count inventory.
                </p>
              ) : (
                <p className="mb-3 text-sm text-ink/60">No committed closing-stock snapshot was found. Transaction-history mode can build stock from purchase and sales movements.</p>
              )}
              <div className="space-y-2">
                {(Object.keys(voucherModeLabels) as Array<Exclude<VoucherStockImpactMode, "BLOCK_IF_SNAPSHOT_EXISTS">>).map((mode) => (
                  <label key={mode} className="flex gap-3 rounded border border-ink/10 p-3 text-sm">
                    <input
                      type="radio"
                      name="voucherStockImpactMode"
                      value={mode}
                      checked={voucherStockImpactMode === mode}
                      onChange={() => {
                        setVoucherStockImpactMode(mode);
                        setConfirmStockMovementsAfterSnapshot(false);
                      }}
                    />
                    <span>
                      <span className="block font-medium text-ink">{voucherModeLabels[mode]}</span>
                      <span className="mt-1 block text-xs text-ink/55">{voucherModeHelp(mode, latestSnapshotDate)}</span>
                      {hasStockSnapshot && mode === "CREATE_INVOICES_ONLY" ? <span className="mt-1 block text-xs font-medium text-moss">Recommended for this batch</span> : null}
                    </span>
                  </label>
                ))}
              </div>
              {requiresStockImpactConfirmation ? (
                <label className="mt-3 flex gap-3 rounded border border-coral/25 bg-coral/10 p-3 text-sm text-coral">
                  <input type="checkbox" checked={confirmStockMovementsAfterSnapshot} onChange={(event) => setConfirmStockMovementsAfterSnapshot(event.target.checked)} />
                  <span>I understand historical voucher movements may double-count stock, and I want to use transaction-history mode.</span>
                </label>
              ) : null}
              <p className="mt-3 text-xs text-ink/50">Voucher range: {selectedBatch?.voucherDateFrom || "unknown"} to {selectedBatch?.voucherDateTo || "unknown"}. The selected mode is stored with this import and shown in reconciliation.</p>
            </Panel>
          ) : null}

          <Panel title="Preview" action={batchId ? <div className="flex gap-2"><SecondaryButton onClick={() => validate.mutate()} disabled={!can("imports.validate") || validate.isPending}>Validate</SecondaryButton><PrimaryButton onClick={() => commit.mutate()} disabled={!can("imports.commit") || commit.isPending || hasUnresolvedBlockingErrors || (requiresStockImpactConfirmation && !confirmStockMovementsAfterSnapshot)}>Commit</PrimaryButton></div> : null}>
            {!batchId ? <p className="text-sm text-ink/55">Upload or select a batch to inspect staged rows.</p> : (
              <div>
                {isSnapshotBatch ? (
                  <p className="mb-3 rounded bg-mint/70 p-3 text-xs text-moss">
                    Stock Snapshot Import: currentStock, importedStock, and delta show the adjustment that will be created. NO_CHANGE rows create no stock movement.
                  </p>
                ) : null}
                {isTallyPurchaseBatch ? (
                  <p className="mb-3 rounded border border-amber-300 bg-amber-50 p-3 text-xs text-amber-800">
                    Tally Purchase Import: rates may come directly from RATE, be derived from amount / quantity, or be zero for genuine free and scheme items. Review rawRate, parsedRate, rateSource, and action before commit. Tax, discount, freight, and round-off ledger lines do not create stock movements.
                  </p>
                ) : null}
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-ink/50"><tr>{Object.keys(preview.data?.[0] ?? { rowNumber: "" }).map((key) => <th key={key} className="whitespace-nowrap px-2 py-2">{key}</th>)}</tr></thead>
                    <tbody>{(preview.data ?? []).slice(0, 50).map((row, index) => <tr key={index} className="border-t border-ink/10">{Object.keys(preview.data?.[0] ?? row).map((key) => <td key={key} className="max-w-[220px] truncate px-2 py-3">{typeof row[key] === "object" ? JSON.stringify(row[key]) : String(row[key] ?? "")}</td>)}</tr>)}</tbody>
                  </table>
                </div>
              </div>
            )}
            {validate.error ? <p className="mt-3 text-sm text-coral">{validate.error.message}</p> : null}
            {hasUnresolvedBlockingErrors ? <p className="mt-3 text-sm text-coral">{effectiveBlockingErrors.length} blocking error{effectiveBlockingErrors.length === 1 ? "" : "s"} must be fixed before commit. Warnings do not block commit.</p> : null}
            {blockingErrors.length > effectiveBlockingErrors.length ? <p className="mt-3 text-sm text-moss">Negative-stock rows will be resolved with: {negativeStockPolicy.replaceAll("_", " ")}. Click Validate to refresh warnings, or Commit to revalidate and commit.</p> : null}
            {commit.error ? <p className="mt-3 text-sm text-coral">{commit.error.message}</p> : null}
          </Panel>

          {validationIssues.length ? (
            <Panel title={`Validation Issues (${effectiveBlockingErrors.length} blocking, ${warnings.length} warnings)`}>
              <div className="mb-4 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
                {issueGroups.map((group) => (
                  <div key={`${group.severity}-${group.code}`} className={`rounded border p-3 text-sm ${group.severity === "WARNING" ? "border-amber-300 bg-amber-50 text-amber-800" : "border-coral/25 bg-coral/10 text-coral"}`}>
                    <div className="font-medium">{group.code.replaceAll("_", " ")}</div>
                    <div className="mt-1 text-xs">{group.rows.length} affected row{group.rows.length === 1 ? "" : "s"}: {group.rows.slice(0, 12).join(", ")}{group.rows.length > 12 ? "..." : ""}</div>
                  </div>
                ))}
              </div>
              <div className="mb-2 text-xs font-medium uppercase text-ink/45">Row details</div>
              <div className="space-y-2">
                {effectiveBlockingErrors.map((error, index) => (
                  <div key={`error-${index}`} className="rounded border border-coral/25 bg-coral/10 p-3 text-sm text-coral">
                    <div className="font-medium">Error - Row {error.rowNumber}: {error.message}</div>
                    {error.suggestedFix ? <div className="mt-1 text-xs text-coral/80">{error.suggestedFix}</div> : null}
                  </div>
                ))}
                {blockingErrors.filter((issue) => isNegativeStockIssue(issue) && selectedPolicyCanResolveNegativeStock).map((issue, index) => (
                  <div key={`resolved-negative-${index}`} className="rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800">
                    <div className="font-medium">Pending revalidation - Row {issue.rowNumber}: {issue.message}</div>
                    <div className="mt-1 text-xs text-amber-700">Selected policy: {policyLabels[negativeStockPolicy]}. Commit will revalidate before writing data.</div>
                  </div>
                ))}
                {warnings.map((warning, index) => (
                  <div key={`warning-${index}`} className="rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800">
                    <div className="font-medium">Warning - Row {warning.rowNumber}: {warning.message}</div>
                    {warning.suggestedFix ? <div className="mt-1 text-xs text-amber-700">{warning.suggestedFix}</div> : null}
                  </div>
                ))}
              </div>
            </Panel>
          ) : null}
          {commit.data ? <Panel title="Commit Result"><pre className="overflow-x-auto text-sm">{JSON.stringify(commit.data, null, 2)}</pre></Panel> : null}
        </div>
      </div>
    </AppShell>
  );
}

function severity(issue: ImportError) {
  return (issue.severity ?? "ERROR").toUpperCase();
}

function isNegativeStockIssue(issue: ImportError) {
  return negativeStockCodes.has(issue.errorCode);
}

function normalizePolicy(value: unknown): NegativeStockImportPolicy {
  return value === "IMPORT_AS_IS" || value === "SKIP_STOCK_MOVEMENT" || value === "BLOCK" ? value : "BLOCK";
}

function normalizeVoucherMode(value: unknown): VoucherStockImpactMode {
  if (value === "CREATE_INVOICES_ONLY" || value === "APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE" || value === "BLOCK_IF_SNAPSHOT_EXISTS") return value;
  return "CREATE_INVOICES_AND_STOCK_MOVEMENTS";
}

function Impact({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded border border-ink/10 p-3">
      <div className="text-xs text-ink/50">{label}</div>
      <div className="mt-1 text-lg font-semibold">{value}</div>
    </div>
  );
}

function policyHelp(policy: NegativeStockImportPolicy, tenantAllowsNegativeStock: boolean) {
  if (policy === "BLOCK") return "Negative stock remains a blocking error. No products are committed until the source data is fixed.";
  if (policy === "IMPORT_AS_IS") return tenantAllowsNegativeStock ? "Creates products and a stock snapshot adjustment with audit logging." : "Requires Settings > Allow negative stock movements.";
  return "Creates products but skips only the negative stock snapshot movement. Reconciliation will count skipped rows.";
}

function voucherModeHelp(mode: Exclude<VoucherStockImpactMode, "BLOCK_IF_SNAPSHOT_EXISTS">, latestSnapshotDate: string) {
  if (mode === "CREATE_INVOICES_ONLY") return "Creates invoice headers and line items for analytics, without changing the stock ledger.";
  if (mode === "APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE") return `Creates movements only for vouchers after ${latestSnapshotDate || "the latest snapshot date"}.`;
  return "Use only when building stock entirely from opening balances and transaction history.";
}
