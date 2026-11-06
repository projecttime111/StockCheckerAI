"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "@/lib/api";
import { EmptyState } from "@/components/EmptyState";
import { Field, PrimaryButton, SecondaryButton, TextInput } from "@/components/FormControls";
import { RecordPicker } from "@/components/RecordPicker";
import { QueryFeedback } from "@/components/QueryFeedback";
import { LoadingState } from "@/components/LoadingState";

type ReturnReview = {
  id: string;
  voucherType: string;
  voucherNumber: string;
  voucherDate: string;
  classification: string;
  classificationConfidence: number;
  detectionReason: string;
  productId?: string;
  productName: string;
  warehouseId?: string;
  warehouseName: string;
  normalizedQuantity: number;
  returnDirection: string;
  disposition: string;
  resolutionStatus: string;
};

type Product = { id: string; name: string };
type Warehouse = { id: string; name: string };

export function ReturnReviewPanel({ sessionId }: { sessionId: string }) {
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState("");
  const [productId, setProductId] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [quantity, setQuantity] = useState("");
  const [confirmation, setConfirmation] = useState("");

  const reviews = useQuery({
    queryKey: ["smart-import-return-review", sessionId],
    enabled: !!sessionId,
    queryFn: () => api<ReturnReview[]>(`/api/import-sessions/${sessionId}/return-review`)
  });
  const warehouses = useQuery({
    queryKey: ["warehouses", "return-review"],
    queryFn: () => api<Warehouse[]>("/api/warehouses")
  });
  const selected = reviews.data?.find((row) => row.id === selectedId);

  const resolve = useMutation({
    mutationFn: (action: string) => api(`/api/import-sessions/${sessionId}/return-review/${selectedId}/resolve`, {
      method: "POST",
      body: JSON.stringify({
        action,
        productId: productId || selected?.productId,
        warehouseId: warehouseId || selected?.warehouseId,
        quantity: Number(quantity || selected?.normalizedQuantity || 0),
        disposition: action.startsWith("CONFIRM_PHYSICAL") ? "RESTOCK_TO_AVAILABLE" : "FINANCIAL_ONLY",
        confirmation
      })
    }),
    onSuccess: async () => {
      setSelectedId("");
      setConfirmation("");
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["smart-import-return-review", sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-staging", sessionId] }),
        queryClient.invalidateQueries({ queryKey: ["smart-import-dry-run", sessionId] })
      ]);
    }
  });

  if (reviews.isLoading) return <LoadingState label="Loading return review items..." />;
  if (!reviews.data?.length) {
    return <EmptyState label="No ambiguous physical returns. Reliable inventory returns were classified automatically." />;
  }

  return (
    <div className="space-y-4">
      <QueryFeedback queries={[warehouses]} />
      <p className="text-sm leading-6 text-ink/65">
        Credit and debit notes do not always move goods. Confirm only lines where inventory physically returned.
        Damaged or non-saleable items remain deferred from available stock.
      </p>
      <div className="divide-y divide-ink/10 border-y border-ink/10">
        {reviews.data.map((row) => (
          <button
            key={row.id}
            type="button"
            onClick={() => {
              setSelectedId(row.id);
              setProductId(row.productId || "");
              setWarehouseId(row.warehouseId || "");
              setQuantity(String(row.normalizedQuantity || ""));
              setConfirmation("");
            }}
            className="focus-ring grid w-full gap-2 px-2 py-4 text-left sm:grid-cols-[1fr_1fr_auto]"
          >
            <div>
              <div className="font-semibold">{row.voucherNumber || "Unnumbered note"}</div>
              <div className="text-xs text-ink/55">{row.voucherDate} | {row.voucherType}</div>
            </div>
            <div>
              <div className="text-sm">{row.productName || "Product unresolved"}</div>
              <div className="text-xs text-ink/55">{row.detectionReason}</div>
            </div>
            <div className="text-xs font-semibold text-amber-700">{row.resolutionStatus}</div>
          </button>
        ))}
      </div>

      {selected ? (
        <div className="grid gap-3 border-l-2 border-moss/40 pl-4 sm:grid-cols-2">
          <RecordPicker<Product> path="/api/products" label="Product" value={productId} onChange={product => setProductId(product?.id ?? "")} />
          <Field label="Warehouse receiving/leaving stock">
            <select className="focus-ring h-10 w-full rounded border border-ink/15 bg-white px-3 text-sm" value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)}>
              <option value="">Choose warehouse</option>
              {warehouses.data?.map((warehouse) => <option key={warehouse.id} value={warehouse.id}>{warehouse.name}</option>)}
            </select>
          </Field>
          <Field label="Normalized physical quantity">
            <TextInput type="number" min="0.001" step="0.001" value={quantity} onChange={(event) => setQuantity(event.target.value)} />
          </Field>
          <Field label="Type POST RETURN STOCK">
            <TextInput value={confirmation} onChange={(event) => setConfirmation(event.target.value)} autoComplete="off" />
          </Field>
          <div className="flex flex-wrap gap-2 sm:col-span-2">
            <PrimaryButton
              onClick={() => resolve.mutate(selected.returnDirection === "PURCHASE_RETURN_OUT"
                ? "CONFIRM_PHYSICAL_PURCHASE_RETURN" : "CONFIRM_PHYSICAL_SALES_RETURN")}
              disabled={resolve.isPending || !productId || !warehouseId || Number(quantity) <= 0 || confirmation !== "POST RETURN STOCK"}
            >
              Post confirmed physical return
            </PrimaryButton>
            <SecondaryButton onClick={() => resolve.mutate("CONFIRM_FINANCIAL_ONLY")} disabled={resolve.isPending}>
              Financial-only
            </SecondaryButton>
            <SecondaryButton onClick={() => resolve.mutate("KEEP_UNRESOLVED")} disabled={resolve.isPending}>
              Keep unresolved
            </SecondaryButton>
          </div>
        </div>
      ) : null}
    </div>
  );
}
