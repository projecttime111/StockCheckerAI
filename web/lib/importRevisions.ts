import { api } from "./api";

export type RevisionLine = { productName: string; unitCode?: string; quantity: number; rate?: number | null; amount?: number | null; sourceLineId?: string; lineHash?: string };
export type RevisionStockChange = { productId: string; productName?: string; warehouseId: string; warehouseName?: string; currentStock: number; change: number; projectedStock: number };
export type DocumentRevision = { voucherId: string; documentType: string; number: string; date: string; partyName: string; sourceCompany: string; sourceRecordId: string; sourceRevision: string; sourceHash: string; destinationHash: string | null; existingLines: RevisionLine[]; proposedLines: RevisionLine[]; stockChanges: RevisionStockChange[]; existingLineCount: number; proposedLineCount: number; linePreviewTruncated: boolean; existingInvoiceId?: string; outcome: "NEW" | "UNCHANGED" | "CHANGED" | "CANCELLED"; eligible: boolean; blockingReason?: string; approved: boolean; stockMovementCount: number; existingTotal: number | null; proposedTotal: number | null };
export type RevisionPage = { items: DocumentRevision[]; page: number; size: number; totalElements: number; totalPages: number; newCount: number; unchangedCount: number; changedOnly: boolean };
export const importRevisionApi = {
  list: (id: string, page: number) => api<RevisionPage>(`/api/import-sessions/${id}/document-revisions?page=${page}&size=20&changedOnly=true`),
  review: (sessionId: string, voucherId: string, body: { action: "APPLY_CORRECTION" | "CANCEL_DOCUMENT"; reason: string; expectedSourceHash: string; expectedDestinationHash: string; confirmation: string }) => api(`/api/import-sessions/${sessionId}/document-revisions/${voucherId}/review`, { method: "POST", body: JSON.stringify(body) })
};
