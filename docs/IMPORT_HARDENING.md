# Import Hardening Notes

StockPilot AI treats Tally, Excel, CSV, JSON, and XML uploads as external source data. Uploaded rows are never inserted directly into final business tables. Every file is stored, parsed into staging tables, validated, previewed, and only then committed transactionally.

## Supported Sources

- CSV: header-based imports with trimmed headers, case-insensitive matching, blank-row skipping, duplicate-header suffixing, and common aliases.
- Excel/XLSX/XLS: first-sheet imports with trimmed headers, duplicate-header suffixing, blank-row skipping, and evaluated formula values where Apache POI can evaluate them.
- XML/JSON: generic adapters for simple exported row structures.
- Tally XML: common `STOCKITEM`, `LEDGER`, report-style `DSPACCNAME` / `DSPSTKINFO` stock rows, and `VOUCHER` exports with inventory entries.
- Tally Excel-style exports: routed through the Excel adapter and common alias mapping.

## Smart Import Workspace Phase 2

`/imports/smart` is a multi-file staging workspace layered beside the existing single-file import flow. A tenant can create a session and upload several XML, CSV, Excel, or JSON files in any order. Phase 1 behavior remains: raw file storage, SHA-256 duplicate detection, content-based classification, best-effort company/date extraction, and a dependency-aware draft plan.

The classifier recognizes:

- Tally `STOCKITEM` inventory masters and `LEDGER` accounting masters;
- sales, purchase, credit-note, debit-note, and stock-journal vouchers;
- Tally `DSPACCNAME` with `DSPSTKINFO` / `DSPCLQTY` as stock snapshots;
- stock-ageing, debtor/creditor, and cashbook signatures where tags or tabular headers provide sufficient evidence;
- CSV/Excel signatures based on normalized headers.

Duplicate-file behavior is non-destructive. Files are compared by SHA-256 inside the same tenant and session. A duplicate remains recorded for traceability, receives `DUPLICATE_FILE`, and reuses the first file's classification rather than being parsed twice. Unknown or low-confidence content receives `REVIEW_REQUIRED`; it is never silently assigned a business purpose.

Draft strategy rules:

- stock snapshot only: `SNAPSHOT_FIRST`;
- sales/purchase transactions without a snapshot: `TRANSACTION_HISTORY`;
- snapshot plus transaction files: `HYBRID_RECONCILIATION`;
- insufficient classified evidence: `UNKNOWN`.

Phase 2 adds tenant/session-scoped canonical staging for products, parties, warehouses, units, snapshots, vouchers and items, cashbook rows, and stock-ageing rows. `POST /api/import-sessions/{sessionId}/stage` parses and matches every classified non-duplicate file. `POST /api/import-sessions/{sessionId}/rebuild` clears only that session's canonical staging, restages the selected files, reruns matching, regenerates review items, and rebuilds the plan.

Exact product matching uses external ID, SKU, barcode, or normalized name plus unit within the current tenant. Exact party matching uses GSTIN, normalized name, phone, or email. Warehouses match by normalized godown name. Possible product duplicates and ambiguous identities are never auto-merged. Matching statuses are `CREATE_NEW`, `MATCH_EXISTING`, `POSSIBLE_DUPLICATE_REVIEW`, `AMBIGUOUS_REVIEW`, `SKIP_DUPLICATE`, and `UNKNOWN`.

The review queue covers unknown file types, parsing failures, ambiguous/possible product matches, unknown parties and warehouses, negative snapshot stock, missing voucher identity, duplicate voucher conflicts, invalid or zero rates, unmatched cashbook entries, and unmatched stock-ageing rows. Simple decisions persist as session resolutions and survive rebuilds. A file-type override restages the file and rebuilds the workspace; it does not bypass safe XML parsing.

Snapshot preview calculates `deltaPreview = importedStock - currentStock` and an action without creating a stock movement. Voucher preview recommends invoice-only behavior when the session contains a snapshot and recommends transaction stock impact otherwise. There is intentionally no Smart Import commit endpoint in Phase 2, no final business-table write, and no ledger change. The existing `/api/imports` staging/validation/commit flow remains unchanged and is still required for real commits.

Smart Import APIs require `imports.view`, `imports.upload`, or `imports.validate` as appropriate. Every session, file, plan, and issue query includes `tenantId`; customer portal users have no matching permissions.

## Smart Import Workspace Phase 3

Phase 3 adds a transaction-safe dry run after canonical staging. `POST /api/import-sessions/{sessionId}/dry-run` replaces the previous dry-run rows for that tenant/session and persists only simulation metadata in `import_dry_runs` and `import_dry_run_items`. It never writes products, customers, suppliers, invoices, invoice items, warehouses, units, or stock movements. `GET /api/import-sessions/{sessionId}/dry-run` returns the latest summary; `GET /api/import-sessions/{sessionId}/dry-run/items` supports `itemType`, `action`, `issue`, and `sourceFileId` filters.

The request may explicitly choose `SNAPSHOT_FIRST`, `TRANSACTION_HISTORY`, or `HYBRID_RECONCILIATION`. `UNKNOWN` is rejected because StockPilot must not silently choose stock behavior. Snapshot-first and transaction-history default to invoice-only voucher impact; hybrid defaults to applying eligible movements only after the applicable snapshot. An unrestricted historical movement mode is not accepted in Phase 4B-2C.

Snapshot reconciliation is date-aware:

```text
stockAtSnapshotDate = sum(stock_movements.base_quantity up to the end of snapshotDate)
deltaAtSnapshotDate = importedSnapshotQuantity - stockAtSnapshotDate
projectedCurrentStock = currentStock + deltaAtSnapshotDate
```

Dry run creates no adjustment. An identical already-reflected snapshot is `NO_CHANGE`; a changed quantity previews only the required delta. Existing later ledger movements remain visible in projected current stock. If the source has no report date and no user date is supplied, the session date is used with `SNAPSHOT_DATE_FALLBACK`.

Voucher dry-run items separately report the invoice action and each proposed/skipped stock movement. Under snapshot-first/hybrid rules, historical vouchers on or before the snapshot date are skipped for stock with `STOCK_MOVEMENT_SKIPPED_BEFORE_SNAPSHOT` while their invoices remain previewed. Duplicate file hashes and existing/duplicate voucher identities are skipped. Derived/sign-normalized/zero rates, skipped Tally ledger lines, negative-stock policy outcomes, cashbook matching, and stock-ageing matching are included in reconciliation counts.

Dry-run endpoints require `imports.validate`, remain tenant-scoped, and are unavailable to `CUSTOMER_USER`.

## Smart Import Workspace Phase 4A

Phase 4A adds `POST /api/import-sessions/{sessionId}/commit` and `GET /api/import-sessions/{sessionId}/commit-result`, both protected by `imports.commit`. A commit requires a fresh completed dry run, the exact confirmation `COMMIT IMPORT PLAN`, no blocking dry-run errors, and no unresolved ERROR or REVIEW_REQUIRED plan items. The dry-run fingerprint covers session files, review decisions, and canonical staged rows. Existing matched snapshot stock is also rechecked at commit time.

The transactional write scope is intentionally limited to units, warehouses/godowns, products, customers/suppliers, external record mappings, and stock-snapshot adjustments. `SmartImportCommit` stores durable COMMITTING/COMMITTED/FAILED state and reconciliation. `SmartImportEffect` records every created, updated, matched, skipped, or reversible entity effect. `ExternalRecordMapping` links normalized/external source identities to tenant-local records for repeat-safe matching.

Sales and purchase vouchers, credit/debit notes, cashbook entries, and stock-ageing rows are counted as deferred and are not posted in Phase 4A. Reconciliation says that voucher and cashbook commit is reserved for Phase 4B. The legacy `/imports` flow remains available when those records must be committed now.

Commit execution is one database transaction. A write failure rolls back products, parties, catalog records, stock movements, mappings, effects, and in-transaction audit rows; the coordinator then records a FAILED result and `SMART_IMPORT_FAILED`. A completed session is idempotent: a retry returns the existing reconciliation. Snapshot movement timestamps avoid day-boundary precision rounding, so a repeated identical closing-stock snapshot is seen at its snapshot date and produces `NO_CHANGE` rather than another adjustment.

Negative snapshot rows follow the dry-run policy. `BLOCK` prevents commit; `SKIP_STOCK_MOVEMENT` commits safe master data and records a skipped effect without changing stock; `IMPORT_AS_IS` is accepted only when `tenant.allowNegativeStock=true` and uses the normal stock-ledger audit path.

## Smart Import Workspace Phase 4B-1

Phase 4B-1 adds transactional invoice-only posting for staged Sales and Purchase vouchers. A clean dry run must use `CREATE_INVOICES_ONLY`; unsupported stock-affecting modes are rejected during commit preflight. The Phase 4A foundation, snapshot adjustments, invoice headers, invoice items, mappings, effects, and audit records are written in one transaction.

Customers, suppliers, products, units, and warehouses are resolved inside the current tenant. Newly reviewed master rows are created first and then reused by their vouchers. Missing or ambiguous party/product identities block posting. When a source omits the voucher warehouse, the single tenant warehouse is used; otherwise an explicit `Imported - Unspecified` warehouse preserves the required invoice header relationship without creating a stock movement.

Voucher identity prefers Tally GUID/external ID and persists a fingerprint of tenant, voucher type, voucher number, date, normalized party, and total amount. Exact duplicates are skipped and audited. Reuse of an invoice number or external identity with different date, party, or amount is an ambiguous conflict and blocks commit. A committed-session retry returns the existing result and cannot add invoice items twice.

Every committed sales/purchase item increments `stockMovementsSkippedDueToInvoiceOnly`; `voucherStockMovementsCreated` remains zero. Reconciliation reports invoice/item counts, duplicate counts, rate provenance, zero-rate/zero-cost rows, skipped ledger lines, and records deferred for future phases. Credit/debit notes, cashbook matching, stock ageing, and `CREATE_INVOICES_AND_STOCK_MOVEMENTS` remain Phase 4B-2 work.

## Smart Import Workspace Phase 4B-2A

Phase 4B-2A commits reviewed credit and debit notes as dedicated tenant-scoped financial adjustments. Each note preserves its party, voucher identity, date, item references, subtotal, GST/tax, discount, freight, round-off, other charges, and final amount. Header-only notes are supported when the Tally export contains only ledger adjustments.

Financial notes are deliberately stock-neutral in this phase. No return-in or return-out movement is created from a credit/debit note, and reconciliation must report `stockMovementsCreatedFromReturns = 0`. `returnStockMovementsDeferred` records note item rows whose physical inventory effect is left for a later reviewed posting phase. Closing-stock snapshots continue to control imported inventory.

Financial adjustment identity prefers Tally GUID/external ID, then a tenant-scoped fingerprint and voucher-number check. An exact repeat is skipped without adding another adjustment or item. A conflicting number or external identity remains review-blocking. Sales and purchase invoices also retain the captured tax, discount, freight, round-off, and other-charge totals rather than treating those ledger rows as products.

Reconciliation reports created and duplicate credit/debit notes, note items, each captured ledger-component line type, and deferred return-stock rows. Commit emits dedicated audit events for committed notes, captured ledger adjustments, and duplicate notes. Cashbook matching, stock ageing, and all stock-affecting voucher posting remain deferred.

## Smart Import Workspace Phase 4B-2B

Phase 4B-2B commits reviewed debtor/creditor balances as dated `OutstandingSnapshot` records and high-confidence cashbook rows as tenant-scoped customer or supplier payments. Debtor rows never create sales invoices, creditor rows never create purchase invoices, and none of these financial records create stock movements.

Cashbook matching is deliberately conservative. StockPilot first tries an exact invoice reference, then an exact tenant party by GSTIN or normalized name, and finally a unique party, amount, and seven-day invoice-date match. A unique invoice match links the payment to that invoice. Multiple candidates become `LOW_CONFIDENCE_REVIEW`; no candidate becomes `UNMATCHED_REVIEW`. Both remain staged and visible after the safe portion of the session commits. They are not silently dropped or automatically posted.

Payment identity uses a tenant-scoped SHA-256 fingerprint of date, normalized party name, absolute amount, direction, and reference. The database also enforces uniqueness for imported customer and supplier payment fingerprints, so a retry or a later session containing the same row increments `duplicatePaymentsSkipped` without creating another payment.

The latest outstanding snapshot is treated as an operational baseline. Invoices, credit/debit financial adjustments, and payments after that snapshot date are applied dynamically. Without a snapshot, the calculation falls back to opening balance plus all known documents and payments. This projection supports collection and payable workflows but does not replace Tally's statutory ledger.

Reconciliation includes debtor and creditor rows processed, snapshots created, customer and supplier payments created, invoice links, matched/unmatched/review-required cashbook rows, and duplicate payments skipped. Dedicated audit events record outstanding commits, matched payments, deferred review rows, and duplicates. Stock-ageing actions, unresolved cashbook posting, return stock, and other stock-affecting voucher movements remain deferred.

## Smart Import Workspace Phase 4B-2C

Phase 4B-2C retains `CREATE_INVOICES_ONLY` and adds `APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE`. Closing stock remains the inventory baseline. For each genuine sales/purchase item, a shared eligibility service finds a tenant-scoped warehouse snapshot first and otherwise the latest product-level snapshot. Sales and purchases strictly after that snapshot create `SALE` and `PURCHASE` movements. Same-date and earlier vouchers create invoices only. Missing/ambiguous snapshots and unresolved item godowns block post-snapshot commit; product-level fallback is warning-marked.

The dry run shows voucher date, applicable snapshot, current stock, signed quantity, projected stock, movement type/action, eligibility reason, warehouse resolution, and negative-stock result. Its summary separates eligible sales/purchase rows, rows covered by the snapshot, same-date deferrals, missing snapshots, warehouse blocks, policy skips, and projected changes. Final reconciliation separates created sales/purchase movements, deferred rows, duplicate effects, negative-stock decisions, and product-level fallbacks.

Movement commit uses the existing stock ledger and stock-key lock. It re-evaluates snapshot eligibility, verifies the dry-run item and expected current stock, and writes the invoice/item/movement/mapping/effect/audit records in one transaction. A tenant-scoped SHA-256 stock-effect fingerprint and PostgreSQL unique partial index prevent duplicate stock effects. Each movement retains source session/file/row, invoice-item reference, snapshot baseline, fingerprint, and decision reason.

Negative sales follow the selected policy: `BLOCK` prevents commit, `SKIP_STOCK_MOVEMENT` permits the reviewed invoice but records no movement, and `IMPORT_AS_IS` requires `tenant.allowNegativeStock=true`. Physical credit/debit note stock effects are handled separately by Phase 4B-2D. Stock journals/transfers, manufacturing movements, and stock-ageing reconciliation remain deferred.

Worked example:

```text
2026-03-31 closing stock: 100
2026-03-30 sale 10: invoice only
2026-03-31 purchase 5: invoice only
2026-04-01 purchase 20: PURCHASE +20
2026-04-02 sale 15: SALE -15
Expected stock: 105
```

## Smart Import Workspace Phase 4B-2D

Phase 4B-2D preserves financial adjustment posting and adds `FINANCIAL_ONLY` and `APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE` return modes. A credit/debit note is not assumed to move goods. Inventory evidence must include a resolved stock item, positive normalized physical quantity, and resolved tenant godown. Ledger-only tax, discount, freight, round-off, service, and zero-quantity lines stay financial-only.

Confirmed customer returns create `RETURN_IN`; confirmed supplier returns create `RETURN_OUT`. Both reuse `SmartImportSnapshotEligibilityService`: only notes strictly after the latest applicable tenant/product/warehouse snapshot are eligible. Same-date and earlier notes are covered by the snapshot. Product-level fallback is warning-marked; missing/ambiguous snapshots and missing/ambiguous warehouses block stock effect.

Conflicting billed/actual quantities and damaged, expired, rejected, scrap, or non-saleable evidence create review items. `GET /api/import-sessions/{sessionId}/return-review` lists unresolved lines. `POST /api/import-sessions/{sessionId}/return-review/{itemId}/resolve` accepts physical sales return, physical purchase return, financial-only, or keep-unresolved decisions. Physical decisions require a tenant-scoped product and warehouse, a positive normalized quantity, `RESTOCK_TO_AVAILABLE`, and the exact phrase `POST RETURN STOCK`. Both endpoints require `imports.validate`; final posting still requires `imports.commit`. `CUSTOMER_USER` has neither permission.

Return commit uses the existing stock-key lock and negative-stock policy. `RETURN_OUT` is blocked, skipped, or allowed according to `BLOCK`, `SKIP_STOCK_MOVEMENT`, or `IMPORT_AS_IS`; import-as-is also requires the tenant negative-stock setting. A tenant-scoped stock-effect fingerprint prevents duplicate movements across retries and repeated sessions. A financial note already committed in financial-only mode may receive its eligible stock effect once in a later reviewed session.

Reconciliation includes classifications, physical return detection, covered/same-date/missing-snapshot/warehouse decisions, `returnInMovementsCreated`, `returnOutMovementsCreated`, negative-stock results, duplicate return effects, manual resolutions, and deferred return rows. Audit events record mode selection, committed return directions, financial-only confirmation, snapshot coverage, duplicates, and allowed/blocked negative stock without logging raw financial payloads.

```text
2026-03-31 closing stock: 100
2026-03-30 customer return 5: financial note only
2026-03-31 supplier return 4: financial note only
2026-04-01 confirmed customer return 10: RETURN_IN +10
2026-04-02 confirmed supplier return 6: RETURN_OUT -6
Expected stock: 104
```

Damaged/non-saleable returns, manufacturing vouchers, unilateral stock adjustments, and stock-ageing reconciliation remain deferred.

## Phase 4B-2E Stock Journals And Warehouse Transfers

Smart Import classifies Tally Stock Journal vouchers and preserves their evidence. The default
`DEFER_STOCK_JOURNALS` mode creates no inventory effect. The optional
`APPLY_CONFIRMED_WAREHOUSE_TRANSFERS_AFTER_SNAPSHOT_DATE` mode supports only balanced,
same-product, compatible-unit, non-manufacturing warehouse transfers.

A supported transfer creates exactly two linked movements in one transaction:

- `TRANSFER_OUT` from the source warehouse.
- `TRANSFER_IN` to the destination warehouse.
- Both use the same tenant, product, base quantity, transfer group, voucher identity, and pair fingerprint.
- Company-wide quantity change must be zero.

Both source and destination need warehouse-specific closing-stock baselines. The transfer date
must be strictly after both baselines; the later baseline is the effective boundary. Same-date
and earlier transfers are reported as covered by closing stock. Product-level baseline fallback
is deliberately disabled because it cannot establish warehouse distribution.

The dry run reports source/destination stock, projected stock, both baseline dates, duplicate
state, negative-stock decision, paired movement counts, and net company effect. Commit locks both
warehouse stock keys deterministically and rechecks the same assumptions. `BLOCK` and
`SKIP_STOCK_MOVEMENT` apply to the entire pair. `IMPORT_AS_IS` requires the tenant negative-stock
setting and still creates both sides.

Manual review allows an authorized user to select the tenant product, source warehouse,
destination warehouse, and normalized quantity. Posting confirmation must exactly match
`POST STOCK TRANSFER`. Manufacturing, unbalanced, ambiguous, and ignored journals remain
visible but do not create stock movements. Duplicate pair fingerprints skip exact reimports;
conflicting or one-sided historical effects require review.

Worked example:

```text
2026-03-31 baselines:
  Product A / East = 100
  Product A / West = 40

2026-04-02 transfer:
  East -> West = 20

Movements:
  TRANSFER_OUT East = -20
  TRANSFER_IN West = +20

Result:
  East = 80
  West = 60
  Company total = 140
```

### Manual cashbook review resolution

`GET /api/import-sessions/{sessionId}/cashbook-review` returns only unresolved low-confidence or unmatched staged rows. Each row includes ranked, tenant-local customer, supplier, sales-invoice, and purchase-invoice candidates with confidence and evidence. Reading requires `imports.view`; posting a resolution requires `imports.commit`, so portal `CUSTOMER_USER` accounts cannot access either operation.

`POST /api/import-sessions/{sessionId}/cashbook-review/{entryId}/resolve` accepts `MAP_CUSTOMER`, `MAP_SUPPLIER`, `MAP_SALES_INVOICE`, `MAP_PURCHASE_INVOICE`, `IGNORE`, or `KEEP_UNRESOLVED`. Payment actions require the exact confirmation `POST PAYMENT`. Selected IDs are resolved with tenant-and-ID queries, the staged row is locked, and the existing source fingerprint is checked before any payment is created. An existing same-fingerprint payment is reused as a duplicate; a conflicting customer/supplier mapping is rejected.

Resolved rows retain the created payment ID, actor, timestamp, action, and optional note. Committed-session reconciliation is updated with manual matches, invoice links, ignored rows, and duplicates. Audit events are `CASHBOOK_REVIEW_RESOLVED`, `PAYMENT_MANUALLY_MATCHED`, and `CASHBOOK_ENTRY_IGNORED`. Manual resolution never creates a stock movement or reruns voucher/closing-stock commit logic.

## Tally XML Coverage

The Tally XML adapter safely handles:

- stock item masters, parent stock groups/categories, base units, opening stock, opening rate, HSN/SAC, and GST-like fields when present;
- party/customer/supplier ledgers with GSTIN and parent ledger group;
- report-style stock summaries where item names and quantities are exported as `DSPACCNAME` / `DSPSTKINFO` / `DSPCLQTY`;
- sales and purchase vouchers with multiple inventory entries;
- voucher number, voucher date, party ledger name, stock item name, billed quantity, actual quantity, rate, amount, godown, batch, and cancelled/deleted flags;
- optional or missing fields without parser crashes.
- Tally purchase rates with units/currency formatting, safe amount/quantity derivation, sign normalization, and explicit zero-cost free/scheme item classification;
- voucher ledger allocations such as GST, discount, freight, round-off, and other charges are captured as financial components and excluded from product and stock movement staging.

Known limitations:

- This is not a full Tally compatibility layer.
- GST/tax mismatch is not fully reconciled yet.
- Complex batch/expiry valuation and nested ledger allocations need more real customer exports.
- Import undo supports safe reversal for tracked created records. Existing batches committed before `import_effects` tracking may only have stock movements inferable.

## Validation Behavior

Validation writes row-level `ImportError` records with:

- `batchId`
- `rowNumber`
- `entityType`
- `fieldName`
- `errorCode`
- `message`
- `severity`: `ERROR` or `WARNING`
- `rawValue`
- `suggestedFix`

Fatal `ERROR` rows block commit. `WARNING` rows are visible but do not block commit.

Current checks include:

- Product: missing product name, missing unit, duplicate SKU, duplicate barcode, duplicate product name without SKU/barcode, invalid GST percentage, invalid HSN warning, missing category warning, unknown warehouse, negative opening/snapshot stock.
- Customer: missing name, duplicate GSTIN in the same batch or against existing tenant customers, invalid GSTIN, invalid email, duplicate name+phone warning where GSTIN is missing.
- Supplier: missing name, duplicate GSTIN in the same batch or against existing tenant suppliers, invalid GSTIN, invalid email, duplicate name+phone warning where GSTIN is missing.
- Voucher/stock: missing invoice number/date/party, invalid movement type, invalid quantity/rate, duplicate invoice against existing tenant invoices, unknown warehouse, backdated voucher warning, cancelled/deleted Tally voucher warning, tenant negative-stock policy.

### Tally Purchase Rate Policy

This policy applies only to Tally XML purchase inventory entries. Generic CSV/XML imports retain strict positive-rate validation.

- `RATE_FIELD`: a positive `RATE` value is used directly after removing unit/currency formatting.
- `DERIVED_FROM_AMOUNT`: if rate is missing, zero, or unparsable but amount and quantity are non-zero, cost rate is `abs(amount) / abs(quantity)` and warning `PURCHASE_RATE_DERIVED_FROM_AMOUNT` is recorded.
- `SIGN_NORMALIZED`: a negative rate is converted to its absolute cost value and warning `PURCHASE_RATE_SIGN_NORMALIZED` is recorded. Tally's negative purchase `AMOUNT` convention does not reverse the stock movement.
- `ZERO_COST_ITEM`: positive quantity with no financial amount and no usable rate is imported at rate/value zero with warning `ZERO_COST_PURCHASE_ITEM`. Stock increases, while a positive existing product purchase cost is not overwritten.
- `INVALID`: malformed financial text that cannot be derived remains blocking as `INVALID_PURCHASE_RATE`.

Raw rate, amount, and quantity plus the selected rate source are preserved in staging metadata and shown in preview. Debit Note / Purchase Return inventory rows are classified as `RETURN_OUT`; the inventory movement is supported and warning `PURCHASE_RETURN_REQUIRES_REVIEW` reminds users that statutory accounting remains in Tally.

### Negative Tally Stock Rows

Real Tally stock report XML can contain negative closing/opening quantities. The app should not force users to edit or delete rows from the original export.

Negative stock import policy is stored at the import batch level and defaults to `BLOCK`:

- `BLOCK`: negative quantity remains an `ERROR` with code `NEGATIVE_QUANTITY`, and commit is not allowed.
- `IMPORT_AS_IS`: allowed only when `tenant.allowNegativeStock=true`; commit creates the product and a negative stock snapshot `ADJUSTMENT`, and writes a `NEGATIVE_STOCK_ALLOWED` audit log.
- `SKIP_STOCK_MOVEMENT`: commit creates the product but skips the negative stock snapshot movement; validation records warning code `NEGATIVE_STOCK_SKIPPED`, and reconciliation includes `negativeStockRowsSkipped`.

Missing category remains a `WARNING` with code `CATEGORY_MISSING` and does not block commit.

## Commit Idempotency

Commit runs inside a Spring transaction. If commit fails midway, database changes roll back.

Behavior:

- A committed batch cannot be committed again; the API rejects a second commit with conflict.
- Validation is rerun before commit.
- Batches with fatal validation errors cannot commit.
- Committed staging rows are marked so retry-safe behavior is explicit.
- Imported purchase/sales voucher rows create invoice headers/items and matching stock movements in the same transaction.
- Cancelled/deleted Tally vouchers are warning-marked and skipped during commit.
- Stock movements use the stock ledger service and locking path, so normal stock operations obey the tenant negative-stock setting.
- Tally report negative stock uses the batch policy described above. `IMPORT_AS_IS` still requires the tenant negative-stock setting; `SKIP_STOCK_MOVEMENT` does not create a negative ledger movement.

### Voucher Stock Impact After A Snapshot

Closing Stock is a point-in-time stock snapshot; purchase, sales, credit-note, and debit-note rows are transactions. Importing historical transactions as movements after loading final closing stock would count the same inventory history twice.

Smart Import dry runs store one supported `voucherStockImpactMode`:

- `CREATE_INVOICES_ONLY`: creates sales/purchase invoice headers and items, but no voucher stock movements. This is the safe default when the current tenant has a committed stock snapshot.
- `APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE`: creates invoices/items for every valid row, but creates sales/purchase movements only when the voucher is strictly after its applicable snapshot.

Unrestricted historical and return-stock modes are rejected in Phase 4B-2C. Snapshot lookup is tenant-scoped and prefers product+warehouse scope, with an explicit product-level fallback warning. Preview shows the selected mode, applicable snapshot, and per-row movement action. Projected negative-stock validation only evaluates rows that would actually create movements.

## Reconciliation

Use:

```http
GET /api/imports/{batchId}/reconciliation
```

The response includes rows read/staged, error/warning rows, committed product/customer/supplier counts, sales/purchase invoice and item counts, stock movement count, voucher stock-impact mode, movements skipped by invoice-only/date policy, latest snapshot date, double-count warning, derived/sign-normalized/zero-cost purchase rate counts, skipped Tally ledger allocation count, tracked import effects, rollback status, negative stock rows found/imported/skipped, skipped rows, duplicate rows, commit timestamp, and final status.

The endpoint is tenant-scoped through the same import-batch ownership check as preview/errors/commit.

## Security

- Raw object-storage keys are tenant-prefixed.
- Import batch, file, preview, errors, reconciliation, mapping, validation, and commit are tenant-scoped.
- Import endpoints require import permissions: `imports.view`, `imports.upload`, `imports.map`, `imports.validate`, and `imports.commit`.
- Customer portal users cannot access import APIs.

## Rollback / Reversal Policy

Physical deletion of committed stock movements is intentionally not used in normal production undo because it would break auditability.

Implemented safe policy:

- Commit records `ImportEffect` rows for created products, customers, suppliers, sales/purchase invoices, stock movements, warehouses, categories, and units.
- `GET /api/imports/{batchId}/undo-preview` shows products/parties that may be removed, stock movements that will be reversed, catalog rows that can be deleted if unused, warnings, and irreversible items.
- `POST /api/imports/{batchId}/undo` defaults to `SAFE_REVERSAL`.
- Safe reversal creates audited `ADJUSTMENT` movements with `referenceType=IMPORT_ROLLBACK` instead of deleting historical stock movements.
- Products created by an import are physically deleted only when they have no stock movements, invoice items, orders, or other dependencies. Product-only master imports can therefore be removed cleanly.
- Products that have import ledger history are kept so historical movements remain valid; the reversal brings current stock back down.
- Customers and suppliers created by an import are deleted only when no invoices, orders, payments, or price lists reference them.
- Imported invoice headers are left in place in safe mode if no void/status model exists; their stock impact is reversed through the stock ledger.
- Undo is idempotent. A rolled-back batch returns a safe "already rolled back" response instead of creating duplicate reversals.
- Undo is tenant-scoped and requires `imports.rollback`. Customer portal users cannot access it.

Dev-only hard delete:

- `POST /api/imports/{batchId}/undo` with `{"strategy":"DEV_HARD_DELETE"}` is available only when `app.dev-tools.enabled=true`.
- It is for local/dev import testing, physically deletes tracked import-created records in dependency order, and is never enabled by default.

Replace import workflow:

- Use `POST /api/imports/{batchId}/replace` or the UI button "Undo and upload replacement".
- The MVP replace flow safely rolls back the old batch, then returns the user to upload the corrected/new file as a new batch.
- Closing-stock snapshots can be re-imported safely; StockPilot applies only the delta needed to make current stock equal the imported closing stock.

Snapshot warning:

- Inventory Master Import creates products and may create opening balances.
- Closing Stock / Stock Report Import represents a stock snapshot, not a purchase/sale transaction stream.
- Snapshot imports SET current stock by delta adjustment: `delta = imported closing stock - current stock`.
- Re-importing the same closing-stock file creates no extra stock movement and does not double stock.
- If earlier testing already doubled stock, re-import the latest Tally closing-stock snapshot. The delta adjustment will correct current stock to the imported closing quantity.
- Use `Tally XML` for `DSPACCNAME` / `DSPSTKINFO` / `DSPCLQTY` files. Generic `XML` is for simple non-Tally XML rows.

Dev workspace reset:

- `POST /api/dev/current-tenant/reset-business-data` is available only in `dev`/`test` profile with `APP_DEV_TOOLS_ENABLED=true`.
- It requires OWNER role and typed confirmation `RESET WORKSPACE`.
- It deletes current-tenant business data but keeps tenant, user, membership, and audit history.

## Test Coverage

Import-focused tests cover:

- CSV duplicate headers and blank rows.
- Excel trimmed headers and formula values.
- Product missing name/unit and duplicate SKU validation.
- Customer/supplier duplicate GSTIN validation.
- Duplicate invoice detection.
- Tally XML multiple-line voucher staging.
- Tally XML purchase voucher commit into purchase invoice/items and stock movements.
- Tally purchase rates read from `RATE`, derived from amount/quantity, sign-normalized, or imported as zero-cost scheme items.
- Invalid purchase rate deduplication and exclusion of non-inventory ledger allocations.
- Cancelled Tally voucher warning and skip behavior.
- Tally report negative stock `BLOCK`, `IMPORT_AS_IS`, and `SKIP_STOCK_MOVEMENT` behavior.
- Tally closing-stock snapshot idempotency and delta adjustments.
- Double commit conflict.
- Undo preview for product-only imports.
- Safe undo deleting product-only imports.
- Safe undo reversing opening-stock import movements.
- Undo idempotency.
- Tenant-scoped import batch/errors/reconciliation.
- Tenant/customer-user rollback denial.
- Dev reset default-off behavior.
- Customer portal import access denial.
- Smart Import session creation and multi-file upload.
- Tally snapshot, sales, purchase, note, debtor/creditor, cashbook, stock-ageing, and unknown-file classification.
- Duplicate-file hash detection and classification reuse.
- Snapshot-first, transaction-history, and hybrid draft planning.
- Smart Import tenant isolation and customer-user denial.
- Smart Import canonical staging for snapshot, sales, purchase, debtor/creditor, cashbook, and stock-ageing files.
- Exact product/GSTIN/warehouse matching, possible-duplicate review, snapshot delta preview, and voucher stock-impact preview.
- Manual file-type override, durable warehouse review resolution, rebuild idempotency, and invalid Tally control-character sanitation.
- Smart Import dry-run immutability, date-aware snapshot delta/no-change, before/after-snapshot voucher impact, duplicate voucher skipping, tenant isolation, portal denial, and dry-run replacement.
- Smart Import Phase 4A preflight freshness, exact confirmation, master-data commit, party/warehouse matching, snapshot delta/no-change, negative-stock policies, deferred voucher counts, idempotent retry, transaction rollback, audit effects, tenant isolation, and customer-user denial.

## Combined Tally Exports

Smart Import recognizes combined exports instead of forcing the entire file into one business type:

- `MIXED_TALLY_MASTERS` is used when the file contains both `STOCKITEM` and `LEDGER` masters.
- `MIXED_TALLY_VOUCHERS` is used when the file contains more than one voucher `VCHTYPE`.
- Stock items are routed to product staging. Only Sundry Debtor/Creditor ledgers are routed to party staging; bank, tax, sales, purchase, and expense ledgers are not converted into parties.
- Sales, Purchase, Credit Note, Debit Note, Stock Journal, Receipt, and Payment rows are routed from each voucher's own `VCHTYPE`.
- Contra and Journal vouchers are identified but deliberately kept outside sales/purchase staging because accounting remains in Tally.
- Tally Sundry Debtor/Creditor reports using sibling `DSPACCNAME` and `DSPACCINFO` nodes are paired into outstanding party rows.
- Classification and Tally parsing stream XML input. Large combined voucher exports are no longer copied into one in-memory XML string or one full-file DOM tree.
