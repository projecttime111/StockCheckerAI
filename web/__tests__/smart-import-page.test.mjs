import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const page = readFileSync(new URL("../app/imports/smart/page.tsx", import.meta.url), "utf8");
const legacy = readFileSync(new URL("../app/imports/page.tsx", import.meta.url), "utf8");
const cashbookReview = readFileSync(new URL("../components/CashbookReviewPanel.tsx", import.meta.url), "utf8");
const returnReview = readFileSync(new URL("../components/ReturnReviewPanel.tsx", import.meta.url), "utf8");
const transferReview = readFileSync(new URL("../components/TransferReviewPanel.tsx", import.meta.url), "utf8");
const rateReview = readFileSync(new URL("../components/VoucherRateReviewForm.tsx", import.meta.url), "utf8");

test("smart import page exposes the canonical staging workspace", () => {
  assert.match(page, /New Smart Import Session/);
  assert.match(page, /type="file" multiple/);
  assert.match(page, /Detected Files/);
  assert.match(page, /Staging Summary/);
  assert.match(page, /Matching/);
  assert.match(page, /Review Queue/);
  assert.match(page, /Canonical Staging Summary/);
  assert.match(page, /Stock Snapshot Preview/);
  assert.match(page, /Voucher Stock Impact Preview/);
  assert.match(page, /Transaction-safe Dry Run/);
  assert.match(page, /Run Dry Run/);
  assert.match(page, /Reviewed Smart Import Commit/);
  assert.match(page, /Commit Reviewed Import/);
});

test("smart import page renders dry-run and safe commit reconciliation", () => {
  assert.match(page, /\/dry-run/);
  assert.match(page, /Final Impact Preview/);
  assert.match(page, /Dry-run Items/);
  assert.match(page, /snapshotAdjustmentsPreviewed/);
  assert.match(page, /stockMovementsSkippedBeforeSnapshotDate/);
  assert.match(page, /COMMIT IMPORT PLAN/);
  assert.match(page, /commit-result/);
  assert.match(page, /Commit Reconciliation/);
  assert.match(page, /salesInvoicesCreated/);
  assert.match(page, /purchaseInvoicesCreated/);
  assert.match(page, /Still deferred/);
});

test("smart import safe commit requires exact confirmation and a clean dry run", () => {
  assert.match(page, /commitConfirmation !== "COMMIT IMPORT PLAN"/);
  assert.match(page, /blockingErrors/);
  assert.match(page, /reviewRequiredCount/);
  assert.match(page, /Foundation data, snapshot adjustments, sales and purchase invoices, eligible post-snapshot stock movements, financial notes, confirmed physical returns/);
  assert.match(page, /Ambiguous or damaged returns, manufacturing journals, unilateral stock adjustments, and stock-ageing reconciliation remain deferred/);
});

test("commit status polls across reloads and disconnected requests without offering a second commit", () => {
  assert.match(page, /refetchInterval: \(query\) => pendingCommitSessionId === sessionId \|\| query.state.data\?\.status === "COMMITTING" \? 3000 : false/);
  assert.match(page, /refetchIntervalInBackground: true/);
  assert.match(page, /onSettled:[\s\S]*invalidateQueries\(\{ queryKey: \["smart-import-commit", context.sessionId\]/);
  assert.match(page, /disabled=\{commitRunning \|\| commitFoundation.isPending/);
});

test("commit counters are final results, not zero-valued progress indicators", () => {
  const reconciliation = page.slice(page.indexOf('<Panel title="Commit Reconciliation"'));
  assert.match(reconciliation, /status === "COMMITTING"[\s\S]*role="status"/);
  assert.match(reconciliation, /status === "COMMITTED" \? \([\s\S]*<MetricGrid entries=\{commitMetrics/);
  assert.match(reconciliation, /failureReason/);
  assert.match(reconciliation, /Your staged files and review decisions are retained/);
});

test("smart import Phase 4B-1 exposes invoice-only reconciliation", () => {
  assert.match(page, /Inventory effect: Invoice-only, no stock movement/);
  assert.match(page, /voucherItemsReady/);
  assert.match(page, /duplicateVouchersSkipped/);
  assert.match(page, /stockMovementsSkippedDueToInvoiceOnly/);
  assert.match(page, /voucherStockMovementsCreated/);
  assert.match(page, /Sales and purchase invoices were posted without changing stock/);
});

test("smart import Phase 4B-2C offers post-snapshot inventory movements", () => {
  assert.match(page, /Apply movements after closing-stock date/);
  assert.match(page, /APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE/);
  assert.match(page, /Sales movements planned/);
  assert.match(page, /Purchase movements planned/);
  assert.match(page, /Covered by snapshot/);
  assert.match(page, /Same-date items deferred/);
  assert.match(page, /applicableSnapshotDate/);
  assert.match(page, /projectedStock/);
  assert.match(page, /Sales and purchases strictly after that date change stock/);
});

test("smart import Phase 4B-2A exposes financial notes and ledger reconciliation", () => {
  assert.match(page, /Credit notes ready/);
  assert.match(page, /Debit notes ready/);
  assert.match(page, /creditNotesCreated/);
  assert.match(page, /debitNotesCreated/);
  assert.match(page, /taxLinesCaptured/);
  assert.match(page, /discountLinesCaptured/);
  assert.match(page, /freightLinesCaptured/);
  assert.match(page, /roundOffLinesCaptured/);
  assert.match(page, /Return inventory effect/);
  assert.match(page, /Credit and debit notes remain financial-only/);
});

test("smart import Phase 4B-2D separates financial notes from confirmed physical returns", () => {
  assert.match(page, /APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE/);
  assert.match(page, /Apply confirmed physical returns after closing-stock date/);
  assert.match(page, /Credit and debit notes do not always represent physical goods movement/);
  assert.match(page, /Return-in planned/);
  assert.match(page, /Return-out planned/);
  assert.match(page, /Ambiguous returns/);
  assert.match(page, /Damaged returns deferred/);
  assert.match(page, /Return-in movements/);
  assert.match(page, /Return-out movements/);
});

test("physical return review requires product, warehouse, disposition, and exact confirmation", () => {
  assert.match(page, /Physical Return Review/);
  assert.match(returnReview, /return-review/);
  assert.match(returnReview, /Choose product/);
  assert.match(returnReview, /Choose warehouse/);
  assert.match(returnReview, /RESTOCK_TO_AVAILABLE/);
  assert.match(returnReview, /POST RETURN STOCK/);
  assert.match(returnReview, /confirmation !== "POST RETURN STOCK"/);
  assert.match(returnReview, /Financial-only/);
  assert.match(returnReview, /Keep unresolved/);
});

test("smart import Phase 4B-2B exposes outstanding snapshots and cashbook reconciliation", () => {
  assert.match(page, /Debtor and Creditor Outstanding Preview/);
  assert.match(page, /Cashbook Payment Matching Preview/);
  assert.match(page, /Cashbook matching is confidence-based/);
  assert.match(page, /cashbookRowsMatched/);
  assert.match(page, /cashbookRowsUnmatched/);
  assert.match(page, /customerPaymentsCreated/);
  assert.match(page, /supplierPaymentsCreated/);
  assert.match(page, /outstandingSnapshotsCreated/);
  assert.match(page, /duplicatePaymentsSkipped/);
  assert.match(page, /row\.cashbookMatchStatus/);
});

test("smart import review queue supports confirmed manual cashbook resolution", () => {
  assert.match(page, /Unmatched Cashbook Resolution/);
  assert.match(page, /cashbookRowsManuallyResolved/);
  assert.match(page, /cashbookRowsIgnored/);
  assert.match(cashbookReview, /cashbook-review/);
  assert.match(cashbookReview, /MAP_CUSTOMER/);
  assert.match(cashbookReview, /MAP_SUPPLIER/);
  assert.match(cashbookReview, /MAP_SALES_INVOICE/);
  assert.match(cashbookReview, /MAP_PURCHASE_INVOICE/);
  assert.match(cashbookReview, /POST PAYMENT/);
  assert.match(cashbookReview, /Post payment/);
  assert.match(cashbookReview, /Keep unresolved/);
});

test("smart import Phase 4B-2E exposes confirmed balanced warehouse transfers", () => {
  assert.match(page, /Stock journal inventory effect/);
  assert.match(page, /DEFER_STOCK_JOURNALS/);
  assert.match(page, /APPLY_CONFIRMED_WAREHOUSE_TRANSFERS_AFTER_SNAPSHOT_DATE/);
  assert.match(page, /Stock Journal and Warehouse Transfer Preview/);
  assert.match(page, /Stock Journal and Transfer Review/);
  assert.match(page, /Warehouse transfers eligible/);
  assert.match(page, /Transfer-out movements/);
  assert.match(page, /Transfer-in movements/);
  assert.match(page, /Transfer net company change/);
  assert.match(page, /Manufacturing journals deferred/);
});

test("warehouse transfer review requires scoped entities and exact confirmation", () => {
  assert.match(transferReview, /transfer-review/);
  assert.match(transferReview, /Choose product/);
  assert.match(transferReview, /Choose source warehouse/);
  assert.match(transferReview, /Choose destination warehouse/);
  assert.match(transferReview, /POST STOCK TRANSFER/);
  assert.match(transferReview, /confirmation !== "POST STOCK TRANSFER"/);
  assert.match(transferReview, /MARK_MANUFACTURING_DEFERRED/);
  assert.match(transferReview, /MARK_UNSUPPORTED_ADJUSTMENT/);
  assert.match(transferReview, /KEEP_UNRESOLVED/);
});

test("smart import page supports file override and simple review resolution", () => {
  assert.match(page, /selectedFileType/);
  assert.match(page, /method: "PATCH"/);
  assert.match(page, /review-items\/\$\{itemId\}\/resolve/);
  assert.match(page, /Rebuild workspace/);
  assert.match(page, /No products, parties, invoices, or stock movements are created in this phase/);
});

test("voucher rate review supports guarded single and bulk resolutions", () => {
  assert.match(page, /VoucherRateReviewForm/);
  assert.match(page, /INVALID_RATE/);
  assert.match(page, /ZERO_RATE_ITEM/);
  assert.match(rateReview, /CONFIRM_ZERO_VALUE/);
  assert.match(rateReview, /DERIVE_RATE/);
  assert.match(rateReview, /TREAT_AS_FINANCIAL_ADJUSTMENT/);
  assert.match(rateReview, /ENTER_VERIFIED_RATE/);
  assert.match(rateReview, /EXCLUDE_VOUCHER/);
  assert.match(rateReview, /KEEP_UNRESOLVED/);
  assert.match(rateReview, /rate-review\/bulk\/preview/);
  assert.match(rateReview, /rate-review\/bulk\/resolve/);
  assert.match(rateReview, /APPLY VERIFIED RATE/);
  assert.match(rateReview, /Preview matching rows/);
  assert.match(rateReview, /I reviewed this preview/);
});

test("smart import page removes uploaded files and refreshes cached session state", () => {
  assert.match(page, /method: "DELETE"/);
  assert.match(page, /Remove file/);
  assert.match(page, /setQueryData<SessionDetails>/);
  assert.match(page, /files\.filter\(\(file\) => file\.id !== fileId\)/);
});

test("smart import page explains and displays mixed Tally content routing", () => {
  assert.match(page, /MIXED_TALLY_MASTERS/);
  assert.match(page, /MIXED_TALLY_VOUCHERS/);
  assert.match(page, /Combined Tally exports are split by each master entity and each voucher/);
  assert.match(page, /actual VCHTYPE/);
  assert.match(page, /contentCounts/);
  assert.match(page, /Contents:/);
});

test("legacy import flow remains available", () => {
  assert.match(page, /href="\/imports"/);
  assert.match(legacy, /href="\/imports\/smart"/);
  assert.match(legacy, /Upload and preview/);
});
