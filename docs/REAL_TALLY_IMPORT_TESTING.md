# Real Tally / Excel Import Testing

Use this guide when you want to test real exported Tally, Excel, CSV, XML, or JSON files against the Spring Boot backend and PostgreSQL. Do not use frontend demo mode for this flow.

## Confirm The Mode

The web app uses these exact variables:

```env
NEXT_PUBLIC_DEMO_MODE=false
NEXT_PUBLIC_API_URL=http://localhost:8080
```

If `NEXT_PUBLIC_DEMO_MODE=true`, the web app calls `web/lib/demoApi.ts` and stores data in browser `localStorage`. It will not use PostgreSQL or backend import APIs.

The backend demo seed flag is:

```env
APP_DEMO_SEED_ENABLED=false
```

For real Tally testing, keep demo seed disabled.

## Private Export Safety

Never upload private Tally/customer files to GitHub, public chat, or review bundles.

Keep real exports under:

```text
private-tally-exports/
```

The repository ignores:

```text
private-tally-exports/
*.private.xml
*.private.xlsx
*.private.csv
*.private.json
```

## Start Database And Services

With Docker installed:

```powershell
cd C:\Users\Dhruv Chandak\Desktop\smartledger\ai_predication_\stockpilot-ai
docker compose up -d postgres redis rabbitmq minio adminer
```

Without Docker, start PostgreSQL locally and create:

```text
database: stockpilot
user: stockpilot
password: stockpilot
```

On macOS/Linux, the repository can create these local development credentials for you (the connection user must have permission to create roles and databases):

```bash
./scripts/bootstrap-local-postgres.sh
```

If the PostgreSQL administrator is named `postgres`, use `PG_BOOTSTRAP_USER=postgres ./scripts/bootstrap-local-postgres.sh`. This resolves `FATAL: role "stockpilot" does not exist` without changing the application's database settings.

Redis, RabbitMQ, and MinIO are optional for this local import test because the backend defaults to simple cache and local file storage when those modes are configured.

## Start Backend Clean

Use Java 21.

```powershell
cd C:\Users\Dhruv Chandak\Desktop\smartledger\ai_predication_\stockpilot-ai\backend
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:DATABASE_URL="jdbc:postgresql://localhost:5432/stockpilot"
$env:DATABASE_USERNAME="stockpilot"
$env:DATABASE_PASSWORD="stockpilot"
$env:APP_DEMO_SEED_ENABLED="false"
$env:APP_DEV_TOOLS_ENABLED="false"
$env:CACHE_TYPE="simple"
$env:LOCAL_STORAGE_ROOT="uploads"
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

Backend URL:

```text
http://localhost:8080
```

Swagger:

```text
http://localhost:8080/swagger-ui.html
```

## Start Web In Real Backend Mode

Option 1, Windows helper:

```powershell
cd C:\Users\Dhruv Chandak\Desktop\smartledger\ai_predication_\stockpilot-ai
.\start-web-backend.bat
```

Option 2, manual:

```powershell
cd C:\Users\Dhruv Chandak\Desktop\smartledger\ai_predication_\stockpilot-ai\web
$env:NEXT_PUBLIC_DEMO_MODE="false"
$env:NEXT_PUBLIC_API_URL="http://localhost:8080"
npm.cmd run dev -- --hostname 127.0.0.1
```

Open:

```text
http://127.0.0.1:3000
```

You should see:

```text
Backend mode: web calls API at http://localhost:8080.
```

If you see:

```text
Demo mode is enabled. Data is stored in your browser...
```

stop and restart web with `NEXT_PUBLIC_DEMO_MODE=false`.

## Create A Clean Workspace

1. Open `http://127.0.0.1:3000/register`.
2. Enter a unique business name and email.
3. Select `RETAIL`, `WHOLESALE`, or `HYBRID`.
4. Submit registration.
5. Confirm the dashboard/sidebar shows the new business name.
6. Open Products and confirm it is empty.
7. Open Customers and confirm it is empty.
8. Open Suppliers and confirm it is empty.
9. Open Imports and confirm no old import batches are shown.
10. Open Dashboard and confirm all business metrics are zero and the page says:

```text
No business data yet. Import Tally/Excel data or add your first product.
```

11. Open Forecasts, Dead Stock, and Data Quality. They should show empty-state messages, not demo products such as Parle-G, Surf Excel, MAGGI, or `demo-tally-vouchers.xml`.

Left sidebar menu items are navigation only. They do not mean the workspace has data. In backend mode, every dashboard and intelligence value must come from PostgreSQL for the current tenant.

If old data appears, it is usually one of these:

- frontend is still in demo mode;
- browser has an old token/cache;
- backend was started with `APP_DEMO_SEED_ENABLED=true`;
- you logged into an old account instead of registering a new workspace;
- PostgreSQL volume still contains old test data.

## Confirm The Current Tenant Is Empty

Use these checks before importing real Tally data. Replace `<tenant_id>` with the ID returned by `GET /api/tenants/current`.

```sql
select count(*) from products where tenant_id = '<tenant_id>' and active = true;
select count(*) from customers where tenant_id = '<tenant_id>';
select count(*) from suppliers where tenant_id = '<tenant_id>';
select count(*) from stock_movements where tenant_id = '<tenant_id>';
select count(*) from sales_invoices where tenant_id = '<tenant_id>';
select count(*) from purchase_invoices where tenant_id = '<tenant_id>';
select count(*) from import_batches where tenant_id = '<tenant_id>';
select count(*) from forecast_results where tenant_id = '<tenant_id>';
select count(*) from reorder_suggestions where tenant_id = '<tenant_id>';
select count(*) from dead_stock_insights where tenant_id = '<tenant_id>';
```

For a clean workspace before import, all counts should be `0`.

Do not import real Tally files until dashboard, forecasts, dead-stock, data-quality, and imports pages are empty for the new backend tenant.

## Real Tally Import Flow

### Plan Multiple Files In Any Order

Open `http://127.0.0.1:3000/imports/smart` to create a Smart Import Session. You may upload stock summary, inventory master, ledger, sales, purchase, credit/debit note, cashbook, and ageing exports together in any order.

Click **Classify, stage, and match**. Review the workspace tabs:

- **Files:** detected/selected purpose, confidence, company/date range, duplicate SHA-256 warnings, and manual type override.
- **Staging Summary:** canonical counts, stock snapshot current/imported/delta preview, and voucher stock-impact suggestions.
- **Matching:** products, parties, and godowns that match existing tenant records, will be created later, or require review.
- **Review Queue:** possible duplicates, ambiguous identities, unknown warehouses, duplicate vouchers, and source-data warnings. Simple choices can be resolved and persisted across rebuilds.
- **Plan:** recommended snapshot-first, transaction-history, or hybrid strategy and the proposed internal processing order.
- **Dry Run:** choose the strategy, voucher stock-impact mode, and negative-stock policy; run a non-posting final-impact simulation and inspect filtered reconciliation items.
- **Final Commit:** after a clean invoice-only dry run, type `COMMIT IMPORT PLAN` and use **Commit Reviewed Import**.

Smart Import Phase 4B-2B commits the Phase 4A foundation, reviewed Sales and Purchase invoice headers/items, credit/debit notes as financial adjustments, debtor/creditor outstanding snapshots, and high-confidence cashbook payments. Select `CREATE_INVOICES_ONLY`: no voucher, cashbook, outstanding, or return stock movement is written, so a previously imported closing-stock snapshot remains the inventory source of truth. Stock-ageing actions, unmatched cashbook entries, and stock-affecting Smart Import voucher posting remain deferred.

In the staging preview, verify that debtor rows are customers, creditor rows are suppliers, snapshot dates and balances match the report, and cashbook entries show their match status and evidence. `MATCHED_SALES_INVOICE` and `MATCHED_PURCHASE_INVOICE` create linked payments. Exact party-only matches create unlinked customer or supplier payments. `LOW_CONFIDENCE_REVIEW` and `UNMATCHED_REVIEW` stay staged and are counted after commit; do not force a match merely to clear the review list.

To resolve a deferred cashbook row, open **Review Queue**, then use **Unmatched Cashbook Resolution**. Compare the candidate type, party/invoice label, confidence, and reason with the original Tally entry. Choose the correct customer, supplier, sales invoice, or purchase invoice and confirm **Post payment**. Use **Ignore** only for verified non-business transfers; **Keep unresolved** makes no financial write. Reopening the session shows fewer unresolved rows and refreshed commit reconciliation. A repeated source fingerprint must report a duplicate and must not create another payment.

The safe commit is transactional and repeat-safe. If a write fails, no partial business records remain. Reusing the same committed session returns the existing result. Re-importing an identical closing-stock snapshot in a new session calculates a zero delta and creates no movement. If files, review decisions, staged rows, or stock-at-snapshot-date changed after dry run, run the dry run again before committing.

For closing-stock exports, verify these dry-run fields: snapshot date, stock at snapshot date, imported quantity, delta at snapshot date, and projected current stock. Re-running the same already-reflected snapshot should show `NO_CHANGE`. Every Sales/Purchase voucher shows invoice creation with skipped stock impact, every credit/debit note shows financial-adjustment creation with deferred return stock impact, and every cashbook row shows matched, unmatched, review-required, or duplicate status.

After commit, verify invoice and note counts plus `outstandingSnapshotsCreated`, `customerPaymentsCreated`, `supplierPaymentsCreated`, `cashbookRowsMatched`, `cashbookRowsUnmatched`, `cashbookRowsReviewRequired`, and `duplicatePaymentsSkipped`. `voucherStockMovementsCreated` and `stockMovementsCreatedFromReturns` must both be `0`; `returnStockMovementsDeferred` reports note item rows not posted to inventory. Re-importing the same cashbook in a new session should increment `duplicatePaymentsSkipped` and create no additional payments.

Files may be uploaded in any order. Smart Import classifies and stages them first, then applies its internal dependency-aware plan. This removes manual upload sequencing without weakening tenant, duplicate, or review controls.

Dry-run API equivalents:

```http
POST /api/import-sessions/{sessionId}/dry-run
GET /api/import-sessions/{sessionId}/dry-run
GET /api/import-sessions/{sessionId}/dry-run/items?itemType=STOCK_SNAPSHOT
```

File type override changes how one session file is parsed, clears its old canonical staging through a session rebuild, and reruns matching/review. Use override only when the detected purpose is wrong. Rebuild is safe to repeat because session staging is replaced rather than appended.

Real Tally exports can contain customer, supplier, price, GST, stock, and financial information. Keep them in `private-tally-exports/`; never add them to source control, fixtures, screenshots, review archives, or prompts.

Current single-file commit order:

1. Upload Inventory Masters XML or Excel export.
2. Validate.
3. Review preview/errors.
4. If negative stock rows appear, choose an import resolution policy before commit.
5. Commit.
6. Open reconciliation.
7. Upload Accounting Masters/Ledgers XML.
8. Validate, preview, commit.
9. Upload Inventory Vouchers XML.
10. Validate, preview, commit.
11. Open reconciliation for every batch.
12. Compare product counts and stock quantities with Tally Stock Summary Excel.
13. Review Import Errors for warnings like missing GST, HSN, unit, category, unknown godown, duplicate SKU/GSTIN, duplicate invoice, negative stock skipped/imported, or cancelled voucher.

Use the `Tally XML` source for Tally report XML exports that contain `DSPACCNAME`, `DSPSTKINFO`, or `DSPCLQTY`. Generic `XML` is only for simple non-Tally row-shaped XML.

Negative stock policy:

- `BLOCK` is the default and keeps negative quantities as blocking validation errors.
- `SKIP_STOCK_MOVEMENT` imports the product and skips only the negative stock snapshot adjustment. This is the safest first-import option when Tally has negative report rows.
- `IMPORT_AS_IS` imports a negative stock snapshot adjustment only if the tenant setting `allowNegativeStock=true`.

Do not edit or delete rows from the original Tally XML just to pass validation. Keep the source export intact and use reconciliation to inspect negative rows that were skipped or imported.

Purchase voucher rate behavior:

- Use `Tally XML`, not generic `XML`, for Tally purchase vouchers.
- A rate like `30.00/Nos` is parsed as `30.00`.
- When `RATE` is absent but non-zero `AMOUNT` and quantity exist, StockPilot derives `abs(amount) / abs(quantity)` and shows a warning.
- Negative Tally purchase amounts are normal debit/credit sign conventions and do not make the stock movement negative.
- Positive quantity with no rate and no amount is treated as a free/scheme/sample item. It adds stock with zero line value and produces a warning instead of a blocking error.
- Do not add a fake price or remove free-item rows from the XML. Confirm the warning against the original Tally voucher before commit.
- GST, discount, freight, loading, and round-off ledger allocations do not create stock movements.
- Preview exposes `rawRate`, `parsedRate`, `amount`, `rateSource`, and the planned action. Reconciliation counts derived rates, normalized signs, zero-cost items, invalid blocked rows, purchase invoice items, and skipped ledger lines.

Closing stock / stock summary report behavior:

- Tally XML rows from `DSPACCNAME`, `DSPSTKINFO`, `DSPCLQTY`, `DSPCLRATE`, or `DSPCLAMTA` are treated as `STOCK_SNAPSHOT`.
- Snapshot imports SET stock, they do not ADD stock again.
- Commit calculates `delta = imported closing stock - current stock` and writes an audited `ADJUSTMENT` only when delta is non-zero.
- Example: if current stock is 100 and the imported closing stock is 100, no movement is created and final stock remains 100.
- Example: if current stock is 200 because an older import doubled stock, re-importing a latest closing stock snapshot of 100 creates a -100 adjustment and final stock becomes 100.
- Inventory Master imports and Voucher imports are different. Master opening balances may create opening-balance movements; voucher imports create purchase/sale movements.

Voucher stock-impact workflow after closing stock:

1. If Closing Stock was imported first, upload historical Purchase/Sales XML using `CREATE_INVOICES_ONLY`. StockPilot creates invoices and line items for revenue, cost, supplier, and customer analysis while current stock remains equal to the snapshot.
2. Use `APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE` when the file contains transactions around the cutoff. Rows on or before the latest snapshot date become invoice-only; later rows create movements.
3. Use `CREATE_INVOICES_AND_STOCK_MOVEMENTS` only when intentionally rebuilding stock from transaction history. Historical overlap with a snapshot requires an explicit checkbox confirmation and is audit logged.
4. To build a pure movement ledger, start with Opening Stock, then import purchases, sales, and returns in date order. Do not import final Closing Stock first in that workflow.

Expected reconciliation for invoice-only historical purchases:

- `purchaseInvoicesCreated > 0`
- `purchaseInvoiceItemsCreated > 0`
- `stockMovementsCreated = 0`
- `stockMovementsSkippedDueToInvoiceOnly > 0`
- current stock remains unchanged from the closing-stock snapshot

Reconciliation endpoint:

```http
GET /api/imports/{batchId}/reconciliation
```

## Undo Or Replace A Previous Import

During real Tally testing, do not create a new workspace just because one XML file needs to be tested again.

Use:

```http
GET /api/imports/{batchId}/undo-preview
POST /api/imports/{batchId}/undo
POST /api/imports/{batchId}/replace
```

Default undo strategy is `SAFE_REVERSAL`:

```json
{
  "strategy": "SAFE_REVERSAL"
}
```

Safe reversal behavior:

- stock movements are reversed with audited `ADJUSTMENT` movements;
- product-only imports can delete products created by the batch;
- products with stock ledger history, sales, purchases, orders, or manual adjustments are kept;
- customers/suppliers are deleted only if unused;
- imported invoices are not silently deleted in production safe mode;
- the batch status becomes `ROLLED_BACK`;
- calling undo twice does not create duplicate reversal movements.

For updated Tally closing stock or stock summary reports:

1. Upload the latest corrected/new Tally XML as a new batch.
2. Confirm the preview shows `Stock Snapshot Import`.
3. Review `currentStock`, `importedStock`, `delta`, `matchStatus`, and `action`.
4. Validate and commit.
5. Final stock should match the imported closing quantity.

Use undo/replace only when you need to remove a bad import's created products/parties or reverse unrelated imported movements. Re-importing the same stock snapshot should not double stock.

`DEV_HARD_DELETE` exists only for local development when `APP_DEV_TOOLS_ENABLED=true`. Do not use it for real business data.

## Reset A Test Workspace

Use Settings > Danger Zone > **Reset workspace data** when many real-file test imports have accumulated and you need to repeat the complete workflow from zero. Review the deletion counts, back up anything needed, and type:

```text
RESET WORKSPACE DATA
```

Reset removes products, stock, imports, sales, purchases, customers, suppliers, forecasts, insights, and uploaded import metadata for the current tenant. It keeps the current account, workspace, and owner membership. The dashboard and import history should be empty afterward.

Use Import Undo for one bad batch. Use workspace reset for a full tenant cleanup. Delete workspace only when the workspace itself is no longer needed. Real Tally XML files may contain private business data and must not be committed to the repository.

## Troubleshooting

If old browser data appears:

1. Open DevTools.
2. Go to Application.
3. Clear `localStorage` and `sessionStorage` for `127.0.0.1:3000`.
4. Refresh and log in again.

If API calls fail:

- confirm backend is running at `http://localhost:8080/actuator/health`;
- confirm `NEXT_PUBLIC_API_URL=http://localhost:8080`;
- confirm CORS allows `http://127.0.0.1:3000`;
- check backend logs for validation or database errors.

If demo products appear:

- check the top banner;
- check `NEXT_PUBLIC_DEMO_MODE=false`;
- check backend was started with `APP_DEMO_SEED_ENABLED=false`;
- sign out and sign back in so tenant-sensitive query cache is cleared;
- register a new clean workspace with a new email.

If PostgreSQL contains old test data and you want a completely clean database:

- with Docker: remove the `postgres-data` volume only after backing up anything important;
- without Docker: create a new database name and update `DATABASE_URL`;
- easiest safe path: use Import Undo for one bad batch, or use Settings > Danger Zone > Reset workspace data for a full current-tenant cleanup.
- local/dev only: set `APP_DEV_TOOLS_ENABLED=true`, restart backend in `dev` profile, open Settings, type `RESET WORKSPACE`, and reset current tenant business data.

## What Not To Do

- Do not commit real Tally exports.
- Do not paste private customer data into prompts.
- Do not use frontend demo mode for real import validation.
- Do not enable demo seed when testing a clean tenant.
- Do not manually delete XML rows or database rows to make an import pass. Use validation, undo, reconciliation, and replacement.
