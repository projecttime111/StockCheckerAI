"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { EmptyState } from "@/components/EmptyState";
import { Field, PrimaryButton, SecondaryButton, TextInput } from "@/components/FormControls";
import { RecordPicker } from "@/components/RecordPicker";
import { QueryFeedback } from "@/components/QueryFeedback";
import { LoadingState } from "@/components/LoadingState";
import { api } from "@/lib/api";

type TransferReview = {
  id: string;
  voucherNumber: string;
  voucherDate: string;
  productId?: string;
  productName: string;
  unitCode: string;
  sourceWarehouseId?: string;
  sourceWarehouseName: string;
  destinationWarehouseId?: string;
  destinationWarehouseName: string;
  rawOutwardQuantity: number;
  rawInwardQuantity: number;
  normalizedQuantity: number;
  classification: string;
  classificationConfidence: number;
  decisionReasons: string;
  resolutionStatus: string;
};

type Product = { id: string; name: string };
type Warehouse = { id: string; name: string };

export function TransferReviewPanel({ sessionId }: { sessionId: string }) {
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState("");
  const [productId, setProductId] = useState("");
  const [sourceWarehouseId, setSourceWarehouseId] = useState("");
  const [destinationWarehouseId, setDestinationWarehouseId] = useState("");
  const [quantity, setQuantity] = useState("");
  const [confirmation, setConfirmation] = useState("");

  const reviews = useQuery({
    queryKey: ["smart-import-transfer-review", sessionId],
    enabled: !!sessionId,
    queryFn: () => api<TransferReview[]>(`/api/import-sessions/${sessionId}/transfer-review`)
  });
  const warehouses = useQuery({
    queryKey: ["warehouses", "transfer-review"],
    queryFn: () => api<Warehouse[]>("/api/warehouses")
  });
  const selected = reviews.data?.find((row) => row.id === selectedId);

  const resolve = useMutation({
    mutationFn: (action: string) => api(`/api/import-sessions/${sessionId}/transfer-review/${selectedId}/resolve`, {
      method: "POST",
      body: JSON.stringify({
        action,
        productId: productId || selected?.productId,
        sourceWarehouseId: sourceWarehouseId || selected?.sourceWarehouseId,
        destinationWarehouseId: destinationWarehouseId || selected?.destinationWarehouseId,
        quantity: Number(quantity || selected?.normalizedQuantity || 0),
        confirmation
      })
    }),
    onSuccess: async () => {
      setSelectedId("");
      setConfirmation("");
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["smart-import-transfer-review", sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-staging", sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-dry-run", sessionId] })
      ]);
    }
  });

  if (reviews.isLoading) return <LoadingState label="Loading stock-journal review items..." />;
  if (!reviews.data?.length) {
    return <EmptyState label="No stock journals were staged for transfer review." />;
  }

  return (
    <div className="space-y-4">
      <QueryFeedback queries={[warehouses]} />
      <p className="text-sm leading-6 text-ink/65">
        Confirm only balanced movement of the same product between two different warehouses.
        Manufacturing and unbalanced journals remain deferred. Posting always creates one linked
        TRANSFER_OUT and TRANSFER_IN, so company-wide quantity stays unchanged.
      </p>
      <div className="divide-y divide-ink/10 border-y border-ink/10">
        {reviews.data.map((row) => (
          <button
            key={row.id}
            type="button"
            onClick={() => {
              setSelectedId(row.id);
              setProductId(row.productId || "");
              setSourceWarehouseId(row.sourceWarehouseId || "");
              setDestinationWarehouseId(row.destinationWarehouseId || "");
              setQuantity(String(row.normalizedQuantity || ""));
              setConfirmation("");
            }}
            className="focus-ring grid w-full gap-2 px-2 py-4 text-left lg:grid-cols-[1fr_1fr_1fr_auto]"
          >
            <div>
              <div className="font-semibold">{row.voucherNumber || "Unnumbered stock journal"}</div>
              <div className="text-xs text-ink/55">{row.voucherDate || "Date missing"} | {row.classification}</div>
            </div>
            <div>
              <div className="text-sm">{row.productName || "Product unresolved"} {row.normalizedQuantity || 0} {row.unitCode}</div>
              <div className="text-xs text-ink/55">{Math.round(Number(row.classificationConfidence || 0) * 100)}% confidence</div>
            </div>
            <div>
              <div className="text-sm">{row.sourceWarehouseName || "Source unresolved"} to {row.destinationWarehouseName || "Destination unresolved"}</div>
              <div className="text-xs text-ink/55">{row.decisionReasons}</div>
            </div>
            <div className="text-xs font-semibold text-amber-700">{row.resolutionStatus}</div>
          </button>
        ))}
      </div>

      {selected ? (
        <div className="grid gap-3 border-l-2 border-moss/40 pl-4 sm:grid-cols-2">
          <RecordPicker<Product> path="/api/products" label="Product" value={productId} onChange={product => setProductId(product?.id ?? "")} />
          <Field label="Normalized transfer quantity">
            <TextInput type="number" min="0.001" step="0.001" value={quantity} onChange={(event) => setQuantity(event.target.value)} />
          </Field>
          <Field label="Source warehouse">
            <select className="focus-ring h-10 w-full rounded border border-ink/15 bg-white px-3 text-sm" value={sourceWarehouseId} onChange={(event) => setSourceWarehouseId(event.target.value)}>
              <option value="">Choose source warehouse</option>
              {warehouses.data?.map((warehouse) => <option key={warehouse.id} value={warehouse.id}>{warehouse.name}</option>)}
            </select>
          </Field>
          <Field label="Destination warehouse">
            <select className="focus-ring h-10 w-full rounded border border-ink/15 bg-white px-3 text-sm" value={destinationWarehouseId} onChange={(event) => setDestinationWarehouseId(event.target.value)}>
              <option value="">Choose destination warehouse</option>
              {warehouses.data?.map((warehouse) => <option key={warehouse.id} value={warehouse.id}>{warehouse.name}</option>)}
            </select>
          </Field>
          <Field label="Type POST STOCK TRANSFER">
            <TextInput value={confirmation} onChange={(event) => setConfirmation(event.target.value)} autoComplete="off" />
          </Field>
          <div className="flex flex-wrap items-end gap-2">
            <PrimaryButton
              onClick={() => resolve.mutate("CONFIRM_WAREHOUSE_TRANSFER")}
              disabled={resolve.isPending || !productId || !sourceWarehouseId || !destinationWarehouseId
                || sourceWarehouseId === destinationWarehouseId || Number(quantity) <= 0
                || confirmation !== "POST STOCK TRANSFER"}
            >
              Confirm warehouse transfer
            </PrimaryButton>
          </div>
          <div className="flex flex-wrap gap-2 sm:col-span-2">
            <SecondaryButton onClick={() => resolve.mutate("MARK_MANUFACTURING_DEFERRED")} disabled={resolve.isPending}>
              Manufacturing, defer
            </SecondaryButton>
            <SecondaryButton onClick={() => resolve.mutate("MARK_UNSUPPORTED_ADJUSTMENT")} disabled={resolve.isPending}>
              Unsupported adjustment
            </SecondaryButton>
            <SecondaryButton onClick={() => resolve.mutate("IGNORE")} disabled={resolve.isPending}>
              Ignore no-op
            </SecondaryButton>
            <SecondaryButton onClick={() => resolve.mutate("KEEP_UNRESOLVED")} disabled={resolve.isPending}>
              Keep unresolved
            </SecondaryButton>
          </div>
          {resolve.error ? <p className="text-sm text-coral sm:col-span-2">{resolve.error.message}</p> : null}
        </div>
      ) : null}
    </div>
  );
}
