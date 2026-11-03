# StockPilot AI

StockPilot AI is an AI-powered inventory intelligence layer for retailers, wholesalers, distributors, warehouses, and multi-branch businesses that already use Tally, Excel, or another ERP.

**Promise:** Connect Tally or Excel. Predict stockouts. Reduce dead stock. Improve profit.

StockPilot AI is designed to work with Tally, not replace it. Accounting, GST, ledgers, vouchers, invoicing, compliance, and statutory reporting remain in Tally or your system of record. StockPilot AI uses exported operational data to generate forecasting, reorder, dead-stock, profit, margin, data-quality, mobile warehouse, and dealer-ordering insights.

## Features
- JWT authentication, roles, tenant isolation, and audit logs.
- Hybrid retail/wholesale tenant mode: `RETAIL`, `WHOLESALE`, or `HYBRID`.
- Product catalog with category, brand, unit, barcode, GST/HSN, reorder settings, and unit conversion tables.
- Stock ledger model using `stock_movements`; current stock is derived from movement sums.
- Purchases create positive stock movements; sales create negative stock movements; adjustments and transfers are ledger entries.
- Dashboard: stock value, monthly sales, gross profit, low stock, dead stock, and receivables.
- Customer payment follow-ups with reminder date/time, due and overdue queue, and a "Payment received" action to reduce outstanding balances.
- Reports page with CSV exports for stock, low stock, dead stock, reorder suggestions, master data, invoices, top/slow products, and audit logs.
- Import hub for CSV, Excel, JSON, XML, Tally XML, and Tally Excel-style files with staging, mapping, validation, errors, preview, and commit.
- Forecast job with moving average, weighted demand, stockout estimate, reorder point, and reorder quantity.
- Local rule-based AI assistant for profit drop, dead stock, reorder, categorization, stockout, and outstanding questions.
- Tally Integration Hub with import status, data-quality score, unmapped fields, duplicate products, and AI insight counts.
- Product cleanup engine for duplicate detection, normalized names, brand/category/unit-size suggestions, and missing GST/HSN/unit/category warnings.
- Smart reorder engine that considers demand, lead time, safety stock, pending orders, MOQ, and warehouse-level stock.
- Dead-stock action center with blocked capital, last-sold date, warehouse, and recommended clearance actions.
- Dealer/customer ordering portal for assigned pricing, available products, sales orders, outstanding, and invoices.
- Invoice upload endpoint with local/mock extraction service.
- Tenant-scoped audit log viewer for imports, stock changes, payment follow-ups, payments, and cleanup actions.
- Next.js admin dashboard and Expo mobile app consuming the same Spring Boot APIs.

## How StockPilot AI Is Different From Tally
Tally is the system of record for:
- Accounting.
- GST and compliance.
- Ledgers and vouchers.
- Invoicing.
- Statutory reports.
- Historical reporting.

StockPilot AI is the decision engine for:
- AI demand forecasting.
- Reorder recommendations.
- Stockout prediction.
- Dead-stock action center.
- AI profit analysis and margin leakage detection.
- Mobile warehouse workflow.
- Dealer ordering portal.
- Product cleanup and duplicate detection.
- Multi-source import from Tally, Excel, CSV, XML, and JSON.

StockPilot AI is designed to work with Tally, not replace it.

## Architecture
```text
Next.js Web Dashboard
Expo Mobile App
External/ERP Uploads
        |
        v
Spring Boot API -> PostgreSQL
        |       -> Redis cache
        |       -> RabbitMQ-ready queue abstraction
        |       -> Local object storage / MinIO-compatible path
        v
Local AI provider / optional LLM provider interface
```

## Tech Stack
- Backend: Java 21, Spring Boot 3, Maven, Spring Web, Security, JWT, JPA, Flyway, Actuator, OpenAPI.
- Data/infra: PostgreSQL, Redis, RabbitMQ, MinIO-compatible storage, Adminer.
- Frontend: Next.js, React, TypeScript, Tailwind CSS, TanStack Query, Recharts.
- Mobile: React Native, Expo, TypeScript.

## Local Setup
Copy environment values if you run services manually:

```bash
cp .env.example .env
```

Start the local stack:

```bash
docker compose up --build
```

To run the backend directly with the ignored root `.env` file:

```bash
./backend/scripts/run-with-root-env.sh
```

Backend API: `http://localhost:8080`

API docs: `http://localhost:8080/swagger-ui.html`

Adminer: `http://localhost:8081`

RabbitMQ console: `http://localhost:15672`

MinIO console: `http://localhost:9001`

For a click-by-click demo path across web and mobile, see `LOCAL_TESTING_GUIDE.md`.

## Run Backend
Use Java 21. Prefer the Maven wrapper so the project does not depend on a global Maven install:

If you run PostgreSQL directly on your machine (rather than with `docker compose`), initialize the local development role and database once before starting the backend:

```bash
./scripts/bootstrap-local-postgres.sh
```

When already inside `backend/`, use `./scripts/bootstrap-local-postgres.sh` instead.

The command connects as your current PostgreSQL administrator. If your server uses the standard `postgres` administrator role instead, run `PG_BOOTSTRAP_USER=postgres ./scripts/bootstrap-local-postgres.sh`. It is safe to run again and creates/updates the local-only `stockpilot` login expected by the default backend configuration.

```powershell
cd backend
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
.\mvnw.cmd package
```

On macOS/Linux, use `./mvnw` instead of `.\mvnw.cmd`.

If macOS resolves an older Java first, point the current terminal at a JDK 21 or
newer installation before starting the backend. This machine's Homebrew JDK can
be selected with:

```bash
export JAVA_HOME="$(brew --prefix openjdk)/libexec/openjdk.jdk/Contents/Home"
export PATH="$(brew --prefix openjdk)/bin:$PATH"
java -version
```

If Windows resolves Java 8 first, set Java 21 for the current terminal before running backend commands:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

For real backend testing and real Tally imports, keep demo seed disabled:

```powershell
$env:APP_DEMO_SEED_ENABLED="false"
```

Email verification is disabled by default for local development, so a new account can return to the login page and sign in immediately. The `prod` profile enables verification by default. To pause it temporarily in production, explicitly set `APP_AUTH_REQUIRE_EMAIL_VERIFICATION=false`; remove that override or set it to `true` when email delivery is ready. To test verification locally, set it to `true` and keep `EMAIL_PROVIDER=local`; the backend logs verification and password-reset links. In production, configure an email provider such as Resend:

```powershell
$env:APP_AUTH_REQUIRE_EMAIL_VERIFICATION="true"
$env:APP_PUBLIC_URL="https://your-vercel-app.vercel.app"
$env:EMAIL_PROVIDER="resend"
$env:EMAIL_FROM="StockPilot AI <noreply@yourdomain.com>"
$env:RESEND_API_KEY="your-resend-api-key"
```

Google sign-in is optional. To enable the "Continue with Google" button, create a Google OAuth **Web application** client and configure the same client ID in backend and web environments:

```powershell
$env:GOOGLE_CLIENT_ID="your-google-oauth-web-client-id"
```

For Vercel, also set:

```text
NEXT_PUBLIC_GOOGLE_CLIENT_ID=your-google-oauth-web-client-id
```

Add your final Vercel domain, for example `https://your-project.vercel.app`, as an authorized JavaScript origin in Google Cloud Console. Without these variables, email/password still works, but Google sign-in is shown as not configured.

For test-only local shortcuts, `APP_AUTH_REQUIRE_EMAIL_VERIFICATION=false` restores immediate login after registration. Do not disable verification in production.

The backend only seeds local demo data when `APP_DEMO_SEED_ENABLED=true`. Use that only when you intentionally want the built-in demo tenant:

All demo users use password: `password123`

| Role | Email |
| --- | --- |
| Owner | `owner@demo.com` |
| Admin | `admin@demo.com` |
| Manager | `manager@demo.com` |
| Warehouse staff | `warehouse@demo.com` |
| Sales staff | `sales@demo.com` |
| Purchase manager | `purchase@demo.com` |
| Accountant | `accountant@demo.com` |
| Viewer | `viewer@demo.com` |
| Auditor | `auditor@demo.com` |
| Dealer/customer portal | `ravi@demo.com` |

This password is for local demo data only.

## Run Web
```bash
cd web
npm install
npm run dev
npm run build
npm test
```

Set `NEXT_PUBLIC_API_URL=http://localhost:8080` when needed.

For real backend mode, use:

```powershell
cd web
$env:NEXT_PUBLIC_DEMO_MODE="false"
$env:NEXT_PUBLIC_API_URL="http://localhost:8080"
npm.cmd run dev -- --hostname 127.0.0.1
```

On Windows, you can also double-click `start-web-backend.bat` from the project root. The top banner should say `Backend mode: web calls API at http://localhost:8080.` If the banner says demo mode, you are not testing PostgreSQL/backend data.

If the browser says `127.0.0.1 refused to connect`, the web server is not running; start it with `start-web-backend.bat`, `start-web-demo.bat`, or `npm run dev` and keep that terminal open.

In backend mode, a brand-new workspace must show zero dashboard values and empty Forecasts, Dead Stock, Data Quality, and Imports pages. Demo products such as Parle-G, Surf Excel, MAGGI, or `demo-tally-vouchers.xml` should appear only when `NEXT_PUBLIC_DEMO_MODE=true` or when that exact tenant has imported/seeded those records. Sign out/sign in after switching accounts so tenant-sensitive query cache is cleared.

For temporary frontend-only testing without Docker/backend/PostgreSQL, double-click `start-web-demo.bat` or run:

```powershell
cd web
$env:NEXT_PUBLIC_DEMO_MODE="true"
npm run dev -- --hostname 127.0.0.1
```

Open `http://127.0.0.1:3000` and log in with the demo credentials. Demo mode uses in-browser dummy API data and does not persist to PostgreSQL.

For clean real Tally/Excel import testing, follow `docs/REAL_TALLY_IMPORT_TESTING.md`.

## Run Mobile
```bash
cd mobile
npm install
npm start
npm run typecheck
```

Set `EXPO_PUBLIC_API_URL=http://localhost:8080`. Android emulators may need `http://10.0.2.2:8080`.

## Import Workflow

### Smart Import Workspace (Phase 4B-2D)

Open `/imports/smart` to create a Smart Import Session and upload multiple XML, CSV, XLSX/XLS, or JSON exports in any order. StockPilot stores every file under the current tenant, computes a SHA-256 hash, detects likely file purpose from content, extracts company/date information where available, warns about duplicate files, and builds a draft dependency-aware import plan.

Detected purposes include inventory/accounting masters, stock snapshots, stock ageing, sales/purchase vouchers, credit/debit notes, cashbook, debtor/creditor analysis, and stock journals. Exact duplicate files remain visible but are not classified twice by default. Unknown or low-confidence files are marked for review instead of being guessed silently.

Phase 2 parses classified files into tenant-scoped canonical staging records for products, parties, warehouses, units, stock snapshots, vouchers/items, cashbook entries, and stock-ageing rows. Exact product, party, and warehouse matches are reused automatically. Possible duplicates, ambiguous identities, unknown warehouses, duplicate vouchers, invalid rates, and comparison rows are placed in a review queue. Users can override a file type, map simple review items, remove files, and rebuild the workspace without writing final business data.

Phase 3 adds a persistent, transaction-safe dry run. After staging, open the **Dry Run** tab, confirm the recommended strategy, voucher stock-impact mode, and negative-stock policy, then click **Run Dry Run**. The simulation reports records to create/match/review/skip, invoice and stock-movement impact, duplicate vouchers/files, rate provenance, unmatched cashbook/ageing rows, and final warning/blocking counts.

Stock snapshots are date-aware during dry run: `deltaAtSnapshotDate = importedSnapshotQuantity - stockAtSnapshotDate`. The preview also reports current stock and projected current stock after the dated adjustment. Under snapshot-first or hybrid mode, vouchers on or before the snapshot date can remain invoice-only, preventing historical transactions from double-counting closing stock. Later vouchers can preview stock movements when `APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE` is selected.

Phase 4A enables a deliberately limited **Safe Foundation Commit** after a clean dry run. It transactionally creates or reuses units, warehouses/godowns, products, customers/suppliers, external mappings, and stock-snapshot delta adjustments. The request requires the exact confirmation `COMMIT IMPORT PLAN`, a fresh completed dry run, zero blocking errors, and zero unresolved review-required items. File, staging, stock-at-date, or review changes invalidate the dry run and require another simulation.

Phase 4B-1 extends the same transaction with invoice-only sales and purchase posting. Reviewed vouchers create tenant-scoped invoice headers and items but create no `SALE` or `PURCHASE` stock movements.

Phase 4B-2A adds financial-only credit and debit note posting. Notes are stored as tenant-scoped financial adjustments with their product references and GST/tax, discount, freight, round-off, and other-charge components. They do not create return stock movements, so the closing-stock snapshot remains the inventory source of truth.

Phase 4B-2B adds debtor/creditor outstanding snapshots and confidence-based cashbook payment matching. Debtor rows create customer receivable snapshots and creditor rows create supplier payable snapshots; neither creates a fake invoice. Exact invoice-reference matches and reliable tenant-party matches create customer or supplier payments, optionally linked to the matched invoice. Ambiguous and unmatched rows remain staged for review and are reported in reconciliation instead of being guessed or silently dropped. A tenant-scoped source fingerprint prevents the same cashbook payment from being posted again across retries or later sessions.

Unmatched and low-confidence cashbook rows can be resolved from the Smart Import **Review Queue** after staging or after the safe commit. An authorized import committer can select a ranked tenant-local customer, supplier, sales invoice, or purchase invoice candidate, inspect the confidence reason, and explicitly confirm `POST PAYMENT`. The selected payment is created immediately and linked to the invoice when applicable. Entries may instead be marked non-business/ignored or left unresolved. Manual resolutions update the existing commit reconciliation and emit dedicated audit events; `CUSTOMER_USER` cannot view or resolve this queue.

Phase 4B-2C adds an explicit inventory-effect choice. `CREATE_INVOICES_ONLY` preserves the Phase 4B-1 behavior. `APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE` treats the latest applicable tenant/product/warehouse closing-stock snapshot as the inventory baseline: sales and purchases strictly after its date create `SALE` and `PURCHASE` movements; same-date and earlier vouchers remain invoice-only. If only a product-level snapshot exists, the dry run warns that warehouse precision is unavailable. A missing snapshot or unresolved stock-item warehouse blocks the post-snapshot mode instead of guessing.

Phase 4B-2D adds a separate return inventory-effect choice. `FINANCIAL_ONLY` preserves stock-neutral credit/debit note posting. `APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE` creates `RETURN_IN` for confirmed customer returns and `RETURN_OUT` for confirmed supplier returns only when the note is strictly after the applicable closing-stock snapshot. Same-date and earlier notes remain financial-only. GST, discount, freight, round-off, ledger-only, service, and zero-quantity lines never create stock movements.

Credit/debit note wording alone is not physical-return proof. StockPilot checks product, non-zero quantity, inventory allocation, and godown evidence. Conflicting billed/actual quantities, missing product/godown, or damaged, expired, rejected, scrap, and non-saleable evidence enter **Physical Return Review**. Authorized users may select a tenant product and warehouse, confirm the normalized quantity and `RESTOCK_TO_AVAILABLE`, and type the exact phrase `POST RETURN STOCK`; they may instead confirm financial-only or leave the line unresolved. `CUSTOMER_USER` cannot access this workflow.

Return stock effects reuse snapshot eligibility, stock-key locking, negative-stock policy, transactional commit, and tenant-scoped stock-effect fingerprints. Re-importing the same note does not create a second return movement. A purchase return that would make stock negative is blocked, skipped, or imported as-is according to the selected policy; import-as-is still requires `tenant.allowNegativeStock=true`. Damaged/non-saleable goods are not automatically added to available stock.

The post-snapshot dry run and commit share one eligibility service. They report the snapshot used, voucher date, current and projected stock, movement quantity/type, decision reason, warehouse, duplicate result, and negative-stock outcome. `BLOCK` prevents a projected negative sale, `SKIP_STOCK_MOVEMENT` may commit the invoice while skipping the movement, and `IMPORT_AS_IS` is available only when the tenant allows negative stock. Stock keys are locked and current stock is rechecked against the dry run before each write.

Outstanding is an operational MVP projection, not a statutory accounting ledger. The latest imported snapshot is used as the baseline, then later invoices, financial adjustments, and payments are applied. When no snapshot exists, StockPilot falls back to opening balance plus known documents and payments. Tally remains the accounting system of record.

The Phase 4B-2D commit is tenant-scoped, transactional, audited, and idempotent. A failed business write rolls back all foundation, invoice, stock-movement, financial-adjustment, outstanding-snapshot, and payment changes and records a safe FAILED result. Voucher identity uses a Tally external ID when available plus a tenant-scoped fingerprint of type, number, date, party, and amount. Eligible stock effects also have a tenant-scoped fingerprint and retain their invoice/note item, source file/row/session, selected snapshot baseline, and decision reason. Exact repeats are skipped; conflicting reuse of a number or external identity is blocked for review. Retrying an already committed session returns its existing result without duplicating invoices, financial notes, payments, snapshots, items, products, parties, mappings, or movements.

Smart Import Session APIs are tenant-scoped and permission protected:

- `imports.view`: list/read sessions, files, plans, staging previews, matching results, and review items.
- `imports.upload`: create/cancel sessions and upload files.
- `imports.validate`: classify files, stage/rebuild canonical records, override file types, and resolve supported review items, including ambiguous returns.
- `imports.validate`: also run/read the latest dry run and filter its paginated items.
- `imports.commit`: execute/read the Phase 4B-2D foundation, invoice, eligible post-snapshot sale/purchase/return movement, financial-adjustment, outstanding-snapshot, and matched-payment commit and reconciliation.
- `CUSTOMER_USER` has no Smart Import access.

Dry-run APIs:

- `POST /api/import-sessions/{sessionId}/dry-run`
- `GET /api/import-sessions/{sessionId}/dry-run`
- `GET /api/import-sessions/{sessionId}/dry-run/items?itemType=&action=&issue=&sourceFileId=`

Phase 4B-2D commit and return-review APIs:

- `POST /api/import-sessions/{sessionId}/commit`
- `GET /api/import-sessions/{sessionId}/commit-result`
- `GET /api/import-sessions/{sessionId}/return-review`
- `POST /api/import-sessions/{sessionId}/return-review/{itemId}/resolve`

The existing `/imports` route remains the operational single-file workflow:

1. Select source: Tally, Excel, CSV, JSON, XML, or other ERP.
2. Upload file.
3. Backend creates an `ImportBatch` and stores the raw file.
4. Adapter parses rows into staging tables.
5. App auto-detects common fields.
6. User can save/apply mapping templates.
7. Validate rows and inspect `import_errors`.
8. Preview staged rows.
9. Commit writes final products, parties, invoices, invoice items, and stock movements transactionally.
10. Review `GET /api/imports/{batchId}/reconciliation` to compare rows read, staged, skipped, errored, warned, and committed.
11. If a committed test import is wrong, use `GET /api/imports/{batchId}/undo-preview` and `POST /api/imports/{batchId}/undo` instead of manually deleting rows.

Import commit is idempotent at the batch level: a `COMMITTED` batch cannot be committed again, validation is rerun before commit, duplicate tenant invoice numbers are blocked, and cancelled/deleted Tally vouchers are warning-marked and skipped. Fatal validation errors block commit; warnings remain visible but do not block.

Tally stock report XML can contain negative stock rows in `DSPACCNAME` / `DSPSTKINFO` / `DSPCLQTY` report exports. StockPilot AI does not require editing or deleting rows from the original XML. Negative opening stock is blocked by default, then the import screen lets the user choose an import-level policy:
- `BLOCK`: keep negative stock as a blocking validation error.
- `IMPORT_AS_IS`: import the product and create a negative stock snapshot adjustment only when the tenant has enabled negative stock.
- `SKIP_STOCK_MOVEMENT`: import the product but skip the negative stock snapshot adjustment; reconciliation reports the skipped rows.

Closing stock / stock report XML is a snapshot. StockPilot AI treats `DSPACCNAME` / `DSPSTKINFO` / `DSPCLQTY` exports as `STOCK_SNAPSHOT` imports and calculates `delta = imported closing stock - current stock`. Re-importing the same closing stock file creates no stock movement and does not double stock. If an older test import overcounted stock, re-importing the latest closing stock snapshot corrects current stock by delta.

Voucher, return, and stock-journal inventory effects are selected independently per Smart Import dry run. `CREATE_INVOICES_ONLY` creates invoice headers/items without changing stock. `APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE` creates movements only for valid sales/purchase items strictly after their applicable snapshot. `APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE` does the same for confirmed physical returns. `DEFER_STOCK_JOURNALS` is the default for stock journals. `APPLY_CONFIRMED_WAREHOUSE_TRANSFERS_AFTER_SNAPSHOT_DATE` posts only balanced, confirmed non-manufacturing transfers strictly after both warehouse-specific baselines. Same-date transactions are conservatively covered by the snapshot. Unrestricted historical posting is not exposed. Manufacturing, unilateral stock adjustments, damaged/quarantine stock, and stock-ageing reconciliation remain deferred.

Worked example: closing stock on `2026-03-31` is Product A `100`. A `2026-03-30` sale of `10` and a `2026-03-31` purchase of `5` are invoice-only. A `2026-04-01` purchase of `20` creates `PURCHASE +20`; a `2026-04-02` sale of `15` creates `SALE -15`. Final supported stock is `105`.

Return example: closing stock on `2026-03-31` is Product A `100`. A `2026-03-30` customer return of `5` and a `2026-03-31` supplier return of `4` are financial-only because the snapshot covers them. A confirmed `2026-04-01` customer return creates `RETURN_IN +10`; a confirmed `2026-04-02` supplier return creates `RETURN_OUT -6`. Final supported stock is `104`.

Warehouse transfer example: closing-stock baselines on `2026-03-31` show Product A `100` in Warehouse East and `40` in Warehouse West. A confirmed `2026-04-02` transfer of `20` creates `TRANSFER_OUT -20` in East and `TRANSFER_IN +20` in West under one transfer group. Final stock is East `80`, West `60`, and company total remains `140`. A transfer on `2026-03-31` is treated as covered by the snapshots. A product-level snapshot without warehouse allocation is not sufficient.

Transfer review is tenant-scoped and requires `imports.validate`; final posting requires `imports.commit`. Exact manual confirmation is `POST STOCK TRANSFER`. Duplicate fingerprints protect same-session retries and repeated uploads. The pair is locked and committed atomically, and negative-stock policy is applied to both sides together.

Undo/replace support:
- `SAFE_REVERSAL` is the default undo strategy. It creates reversing stock adjustments for import-created stock movements and deletes only import-created products/parties/catalog rows that are unused.
- `DEV_HARD_DELETE` is available only when `APP_DEV_TOOLS_ENABLED=true` in local/dev and is not for production data.
- `POST /api/imports/{batchId}/replace` rolls back the old batch and sends the user back to upload the replacement file as a new batch.
- Undo/replace is useful for removing a bad batch, but it is not required just to prevent double-counting repeated closing-stock snapshots.

Detailed import hardening notes, validation rules, Tally limitations, reconciliation behavior, undo/replacement, and rollback policy are documented in `docs/IMPORT_HARDENING.md`.

Sample files live in `backend/src/main/resources/sample-imports/`.

Included samples:
- `sample-products.csv`
- `sample-customers.csv`
- `sample-suppliers.csv`
- `sample-sales.csv`
- `sample-purchases.csv`
- `sample-tally-stock-items.xml`
- `sample-tally-vouchers.xml`

## Tally Notes
The Tally XML adapter tolerates missing optional fields and extracts common `STOCKITEM`, `LEDGER`, report-style `DSPACCNAME` / `DSPSTKINFO` stock rows, and inventory voucher entry nodes, including multiple inventory entries per sales/purchase voucher. Unknown fields are preserved in `raw_metadata` for debugging. Inventory master opening stock imports create `OPENING_BALANCE` movements. Closing-stock report imports create `ADJUSTMENT` movements only for the delta needed to match imported stock. Cancelled/deleted vouchers are warning-marked and skipped during commit. Use the `Tally XML` source for Tally report exports; generic `XML` is intended for simpler non-Tally row exports.

For Tally purchase vouchers, StockPilot reads unit-formatted rates such as `30.00/Nos`, derives a missing rate from `abs(amount) / abs(quantity)` when both values are usable, and normalizes a negative rate sign with a warning. A purchase inventory line with positive quantity and no rate or amount is treated as a zero-cost free/scheme item: it creates a purchase invoice item and stock movement at zero line value without replacing an existing positive product cost. Tally tax, discount, freight, and round-off ledger allocations are not staged as products or stock movements. Raw rate, amount, and quantity remain in import metadata for review.

Large Tally sales/purchase XML exports can be much bigger than master/stock reports. The local backend default accepts import files up to `500MB` (`IMPORT_MAX_UPLOAD_SIZE` / `UPLOAD_MAX_FILE_SIZE`) and requests up to `525MB` (`UPLOAD_MAX_REQUEST_SIZE`). Split exports larger than this by financial year or quarter instead of removing the upload cap.

Open `/integrations/tally` after login to review the companion import dashboard: last successful import, products/customers/suppliers imported, sales and purchase voucher counts, errors, unmapped fields, duplicate items, missing GST/HSN/unit/category warnings, and generated insights.

## Payment Follow-ups
Open `/customers` to track receivables by party. Owners and managers can set payment reminders with date, time, amount, and notes, then mark a party payment as received when money is collected. In demo mode this updates the customer outstanding balance immediately; in backend mode it writes a `customer_payments` record and completes the related payment reminder.

## Reports and Audit Logs
Open `/reports` to download tenant-scoped CSV reports for current stock, low-stock alerts, dead stock, reorder suggestions, product/customer/supplier masters, sales, purchases, top products, slow-moving products, and audit logs. The backend endpoints are under `/api/reports/export/{report}?format=csv`.

Open `/audit-logs` to review important activity, including stock movements, stock transfers, import commits, payment reminders, payments received, product cleanup, and dead-stock actions. The API is `GET /api/audit-logs`.

## Forecasting
Forecasts use the last 90 days of sales movements:
- average daily demand
- weighted recent demand
- next 7 and 30 day demand
- stockout date
- reorder point = demand x lead time + safety stock
- suggested quantity = max(reorder point + next 30 day demand - current stock, minimum order quantity)

## Stock Ledger and Stock Safety
`StockMovement` is the inventory source of truth. Current stock is derived from movement sums rather than a mutable product quantity.

Movement rules:
- `PURCHASE`, `RETURN_IN`, `TRANSFER_IN`, and `OPENING_BALANCE` increase stock.
- `SALE`, `RETURN_OUT`, and `TRANSFER_OUT` decrease stock.
- `ADJUSTMENT` uses a signed quantity and requires a reason.

Negative stock is blocked by default through `allowNegativeStock=false`. If a tenant enables negative stock, sales, stock-out adjustments, transfers, and import-as-is snapshot adjustments may create negative stock and the backend records a `NEGATIVE_STOCK_ALLOWED` audit event. Tally report imports can also choose `SKIP_STOCK_MOVEMENT` to create products while leaving negative snapshot stock out of the ledger for later reconciliation.

Stock-changing operations are transaction-safe. `StockLockService` locks tenant/product/warehouse keys before stock checks and movement writes. PostgreSQL uses transaction-scoped advisory locks; H2/tests use a local per-key transaction lock.

Transfers are atomic and create paired `TRANSFER_OUT` and `TRANSFER_IN` movements with the same transfer reference ID.

## AI Assistant
`POST /api/ai/assistant/chat` answers with tenant data only. If data is missing, it says so. Local AI is rule-based and works without API keys. Optional LLM support is isolated behind `AiProvider`; configure through environment variables only.

## Security Notes
- BCrypt password hashing.
- JWT authentication.
- Permission-based method checks for sensitive APIs.
- AI assistant answers are guarded by intent-specific permissions; denied sensitive AI requests return a safe access-denied response and write an audit log.
- Expanded role model: `OWNER`, `ADMIN`, `MANAGER`, `WAREHOUSE_STAFF`, `SALES_STAFF`, `PURCHASE_MANAGER`, `ACCOUNTANT`, `VIEWER`, `AUDITOR`, `CUSTOMER_USER`, `SUPPLIER_USER`, and platform roles.
- Tenant-scoped repository queries.
- No hardcoded production secrets.
- Production startup rejects the local default JWT secret when the `prod` profile is active.
- Demo seed data is disabled by default with `APP_DEMO_SEED_ENABLED=false`; enable it only for local/dev demos.
- Production-safe workspace reset and deletion controls are available to workspace owners under Settings > Danger Zone. Every action requires an exact typed confirmation.
- Swagger is protected by default. The `dev` profile may expose it for local development; the `prod` profile always requires `OWNER` or `ADMIN` authentication.
- Global validation/error response.
- Audit logs for login, settings, stock, invoice, import commit, import rollback, and dev workspace reset actions.
- Negative stock is blocked by default and can only be enabled through tenant stock policy settings.
- `DELETE /api/account` deactivates and anonymizes the current user after confirming that the user owns no active workspace.
- Automated tenant-isolation guardrail tests scan for unsafe base repository access, unscoped tenant-owned repository methods, and native SQL without tenant filters.

See `docs/SECURITY.md` for the permission-protected API map and deployment notes.

## Workspace Reset And Deletion

Settings > Danger Zone provides three separate operations:

- **Reset workspace data** keeps the account, tenant, and membership, but removes tenant business data including products, stock, invoices, parties, imports, forecasts, AI conversations, and previous audit logs. A new `WORKSPACE_DATA_RESET` audit event remains.
- **Delete workspace** removes business data and memberships, then soft-deletes the tenant with `status=DELETED`. The old tenant token stops working immediately. The user is switched to another active workspace, or receives a workspace-less onboarding session when none remains.
- **Delete my account** deactivates and anonymizes the user and removes memberships. It is blocked while the user owns any active workspace.

Deletion impact preview is available at `GET /api/tenants/current/delete-impact`. Reset and workspace deletion are owner-only and transactional. Uploaded import files are removed only after the database transaction commits; cleanup failures are recorded as `UPLOADED_FILE_DELETE_FAILED` without rolling back otherwise consistent database deletion.

Reset workspace data is destructive. Use only after backup or export. Use Import Undo for one incorrect import; use workspace reset when repeated Tally testing needs a completely empty tenant.

Tenant isolation rule: every business-owned lookup must include the current tenant ID. Customer portal users are restricted to their linked customer record, and report/import/audit APIs must never return another tenant's data. Dealer portal catalogs are assigned by customer price list; if no price list exists, all active products are shown only when `portalShowAllActiveProducts=true`, which defaults to `false`.

## Access Control Model
StockPilot AI now uses a permission catalog on top of roles. After login the web app calls:

- `GET /api/auth/me`
- `GET /api/auth/me/permissions`
- `GET /api/auth/me/tenants`

The frontend hides menus using permissions, and the backend enforces permissions on core actions such as stock adjustment, stock transfer, import commit, product merge, report export, reorder purchase-order creation, AI assistant usage, and audit-log viewing.

Default panel behavior:

- Owner/Admin: full business workspace, settings, reports, imports, intelligence, stock, sales, and purchases.
- Manager: business operations, forecasts, imports, dead-stock and data-quality actions, without billing ownership controls.
- Warehouse staff: product lookup, stock view, stock adjustment, and warehouse transfer without profit visibility.
- Sales staff: products, stock availability, sales entry, customer creation, and customer outstanding without purchase-cost visibility.
- Purchase manager: suppliers, purchases, forecast, reorder suggestions, and draft purchase orders.
- Accountant/Auditor: read-heavy finance, report, and audit permissions.
- Customer user: dealer portal only, scoped to the linked customer.
- Platform roles: platform permission vocabulary exists, but platform support access grants and platform admin screens are future work.

See `docs/RBAC_COMPARISON.md` for the comparison between the enterprise RBAC prompt and the current MVP implementation.

## Production Deployment Checklist

For the clean GitHub → Render → Vercel deployment flow, the exact backend/frontend variables, Neon URL handling, and smoke checks, see [Production Deployment](docs/NEON_DEPLOYMENT.md). For request IDs, log locations, safe exception handling, and optional Sentry setup, see [Production Logging and Error Monitoring](docs/PRODUCTION_LOGGING.md).

- Set `SPRING_PROFILES_ACTIVE=prod` on the backend host. Production configuration requires explicit database, JWT, CORS, public URL, and email values and forces demo seeding, dev tools, and public Swagger off.
- Replace all demo secrets and rotate JWT signing key.
- Use managed PostgreSQL with automated backups and PITR.
- Configure Redis persistence/HA if used for production cache.
- Replace local object storage with MinIO/S3 and lifecycle policies.
- Configure alert rules and longer-term log retention; structured production logs and optional Sentry error reporting are available.
- Enforce HTTPS and strict CORS origins.
- Add email verification and password reset.
- Add invitation/user management UI.
- Add retention-policy automation and self-service data export UX beyond CSV reports.
- Add privacy policy and terms pages.
- Add OCR/LLM provider credentials through secret manager.
- Expand import adapters against real customer Tally exports.
- Add backup/restore drills and retention documentation.
- Review upload size limits and malware scanning.

## App Store / Play Store Readiness
- Configure app icons, splash screens, bundle identifiers, and signing.
- Add camera barcode scanning with manual fallback retained.
- Add privacy labels for uploads and inventory data.
- Add account deletion and support links in mobile settings.
- Test offline/poor network behavior for stock entry.
- Validate Android emulator API URL (`10.0.2.2`) and physical-device LAN URLs.

## Acceptance Smoke Path
1. `docker compose up --build`
2. Log in to web with demo credentials.
3. View dashboard calculations.
4. Create a product.
5. Create a purchase and verify stock increases.
6. Create a sale and verify stock decreases.
7. Run forecast and inspect reorder suggestions.
8. Upload `sample-products.csv` or `sample-tally-stock-items.xml`, validate, preview, and commit.
9. Ask the AI assistant: "Why did my profit drop this month?"
