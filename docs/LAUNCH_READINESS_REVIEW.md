# StockPilot AI: Client Launch Readiness Review

Review date: 2026-09-21

## Fix Follow Up on 2026-10-05

The original evidence below is retained as the review baseline, not a description of every current code path. See [Audit fix evidence and remaining work](AUDIT_FIXES_2026-10-05.md) for changed files, compatibility notes, regression results, and the local database test-isolation incident.

| Finding | Current status |
| --- | --- |
| F1 | Open: invoice-only history is still missing from key demand analytics |
| F2 | Fixed in tested customer, supplier, portal, dashboard and assistant balance paths |
| F3 | Partially fixed: new purchase/sale costing, stock valuation reads and ledger conversion; historical values and all import costing paths still need review |
| F4 | Open: server-side sensitive-field permissions |
| F5 | Fixed: password reset revokes earlier access/refresh tokens through account auth version |
| F6 | Fixed by disabling fabricated OCR; real extraction is unavailable |
| F7 | Open: invoice identity across suppliers and fiscal years |
| F8 | Open: interrupted commit recovery requires a controlled crash/retry test and implementation |
| F9 | Partial pre-existing progress: unexpected errors are now logged; request IDs, redaction and error tracking remain open |
| F10 | Partially fixed: session expiry, cache-clear notification and safe return navigation; bounded request timeouts remain open |
| F11 | Fixed for latest successful import status/counts across legacy and Smart Import; error/unmapped counters remain legacy-scoped |
| F12 | Formula-neutralization implemented and regression-tested; manual Excel/LibreOffice verification remains open |

This is not launch approval. F1, F4, F7, F8 and the unverified operational gates still prevent unrestricted customer access. No live customer import or existing financial-data repair was performed for this fix batch.

## Decision

Do not open unrestricted client access yet. The project builds and has useful import safeguards, but the current implementation can produce contradictory business numbers and expose cost/profit data to restricted staff.

The next phase should be **Launch Stabilization**, not additional AI models, connectors, or ERP features. Target a supervised, single-business pilot after the gates below pass. A calendar deadline is not a substitute for passing those gates.

## Scope and Evidence

Reviewed backend services/controllers/security, Smart Import processing, reporting and analytics, web API/session handling, deployment configuration, migrations, and automated tests. Built the backend and web; tested the backend/web; checked lint and mobile types.

Seven findings below were reproduced with HTTP-level MockMvc requests and isolated H2 test data. No client records, production databases, or real user passwords were changed. Invoice-only history in the analytics probe was seeded directly to reproduce the persisted shape of the snapshot import workflow; this was not another full 337 MB export upload.

This is a targeted repository review, not a guarantee that every bug is found, a penetration test, a dependency vulnerability audit, or verification of the live Vercel/Render/Neon settings.

Priority: P1 = fix or disable the affected workflow before a client uses it. P2 = reliability or usability defect to fix before that workflow is included in the pilot.

## Verified Launch Blockers

### F1 [P1] Imported sales are invisible to key inventory intelligence

**Reproduced:** A product with five units invoiced yesterday, positive snapshot stock, and no SALE stock movement received a zero next-30-day forecast and appeared in dead stock.

**Cause:** Forecasting, dead-stock detection, and reorder demand read SALE stock movements. Hybrid reconciliation deliberately stores historical invoices without generating those movements, because doing so would double-count closing-stock inventory.

**Impact:** A successful import can still produce misleading forecasts, dead-stock classifications, and purchase recommendations. Missing history is being interpreted as zero sales.

**Fix:** Read dated, product-level sales history from invoice items for invoice-backed sales; explicitly reconcile/deduplicate movement-only legacy history. Normalize quantities to base units. Distinguish missing history from genuinely zero demand. Do not create extra stock movements merely to make analytics work.

**Acceptance:** The same historical sales produce equivalent demand inputs in invoice-only and stock-posting modes; no double counting. Recent invoice sales exclude the product from a no-sales dead-stock classification.

Evidence: [ForecastService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/ForecastService.java:47>), [DashboardService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/DashboardService.java:97>), [ReorderEngineService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/ReorderEngineService.java:145>).

### F2 [P1] Customer screen, CSV export, and customer portal disagree

**Reproduced:** With an outstanding snapshot of 500 and an older invoice of 100, the customer API returned 500, while both the CSV and customer portal returned 100.

**Cause:** The customer controller uses OutstandingService, which applies snapshot dates and financial adjustments. ReportsController and PortalService recalculate using opening balance plus all invoices minus payments.

**Impact:** A business owner and their customer can see different amounts owed. Collections and exported reports become unreliable.

**Fix:** Use one shared outstanding calculation everywhere. Preserve snapshot cutoffs and credit/debit adjustment rules. Review supplier exports separately: their current export emits opening balance, not current payable.

**Acceptance:** Screen, dashboard, export, and portal agree for opening balances, dated snapshots, invoices, payments, and adjustments both before and after the snapshot.

Evidence: [OutstandingService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/OutstandingService.java:44>), [ReportsController](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/web/ReportsController.java:167>), [PortalService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/PortalService.java:117>).

### F3 [P1] Pack-to-piece conversion inflates stock valuation

**Reproduced:** Purchasing two boxes of 12 pieces at 120 per box created a purchase total of 240 and 24 pieces of stock, but displayed stock value was 2,880.

**Cause:** StockLedgerService converts quantity to base units while retaining the original per-box rate. PurchaseService also stores the source rate as the product default purchase price.

**Impact:** Stock value and subsequent cost-backed profit calculations are wrong whenever purchase and base units differ.

**Fix:** Make source-unit rate and base-unit cost explicit. Preserve purchase total while converting quantity and cost consistently. Check imported stock, purchases, returns, and sales cost calculations against the same invariant.

**Acceptance:** Two boxes cost 240, base stock is 24, base-unit cost is 10, and valuation remains 240. Add fractional conversion, return, and single-unit regression cases.

Evidence: [StockLedgerService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/StockLedgerService.java:122>), [PurchaseService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/PurchaseService.java:102>).

### F4 [P1] Restricted staff can read purchase costs and profits

**Reproduced:** WAREHOUSE_STAFF lacks stock.view_cost and insights.profit.view, but received defaultPurchasePrice from the product API and grossProfit from dashboard summary.

**Cause:** Product DTOs include costs unconditionally; dashboard profit routes require only dashboard.view. Endpoint access is not sufficient protection for sensitive fields within those responses.

**Impact:** Staff can retrieve business-sensitive purchase prices and margins through allowed APIs, even if UI controls hide them. This finding is within one tenant; it is not a demonstrated cross-tenant leak.

**Fix:** Enforce sensitive-field permissions server-side, or separate sensitive endpoints. Review stock, sales, reports, and assistant evidence for the same exposure.

**Acceptance:** Owner/accountant see their permitted data; warehouse/sales/viewer roles cannot retrieve forbidden fields using direct requests. Continue cross-tenant denial tests.

Evidence: [PermissionService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/PermissionService.java:185>), [CatalogService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/CatalogService.java:176>), [DashboardController](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/web/DashboardController.java:24>).

### F5 [P1] Password reset does not invalidate existing access tokens

**Reproduced:** An access token issued before password reset still returned HTTP 200 from /api/auth/me after the reset.

**Cause:** Reset changes the password and records passwordResetUsedAt, but JWT authentication never checks a revocation timestamp or authentication version.

**Impact:** Resetting a compromised account's password does not terminate its existing authenticated sessions.

**Fix:** Add an authentication version or dedicated revoke-before timestamp checked on every access/refresh operation. Avoid depending on a reset-token field that is cleared when another reset is requested.

**Acceptance:** Old access tokens and refresh attempts fail after reset; new login succeeds; resetting one account does not revoke other users.

Evidence: [AuthService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/AuthService.java:239>), [JwtAuthenticationFilter](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/config/JwtAuthenticationFilter.java:55>).

### F6 [P1] Invoice extraction returns fabricated sample data

**Reproduced:** Uploading three arbitrary bytes as receipt.png returned a sales invoice for Ravi Traders with confidence 0.68.

**Cause:** LocalMockInvoiceExtractionService uses only the filename and returns fixed supplier/customer/product values. It is an unconditional Spring service, not restricted to test/dev.

**Impact:** A customer can mistake demonstration output for actual OCR extraction.

**Smallest launch fix:** Disable the endpoint and associated upload UI outside an explicitly labelled demo environment, returning an honest unavailable response. Real OCR is not required for the first pilot.

**Acceptance:** Production cannot return sample invoice data as extracted customer data.

Evidence: [LocalMockInvoiceExtractionService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/LocalMockInvoiceExtractionService.java:11>).

### F7 [P1] Invoice-number uniqueness rejects legitimate supplier invoices

**Reproduced:** Supplier A invoice PACK-1 succeeded; supplier B invoice PACK-1 returned HTTP 409.

**Cause:** Purchase invoice identity is tenant plus invoice number, without supplier scope. Import invoice lookups use similarly broad keys. Number reuse across fiscal years also needs an explicit policy.

**Impact:** Normal supplier documents can block import or invoice entry despite not being duplicates.

**Fix:** Define invoice identity using supplier/customer document rules, fiscal year where appropriate, and stable source voucher identity. Update database constraints, lookup logic, and dry-run duplicate detection together using a forward migration. Do not simply remove duplicate prevention.

**Acceptance:** Different suppliers may legitimately share a number; exact repeated imports remain idempotent; changed source records are surfaced for review; fiscal-year numbering behaves as documented.

Evidence: [PurchaseService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/PurchaseService.java:59>), [V1 invoice schema](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/resources/db/migration/V1__init_schema.sql:283>).

## Additional Code Findings

### F8 [P1] Interrupted final commits can remain permanently in progress

**Code-inspected; process-crash test not run.** SmartImportCommitService saves COMMITTING before calling the transactional executor. Normal exceptions mark failure, but process termination cannot execute the catch block. A later retry rejects a persisted COMMITTING record without a stale-job recovery path.

Implement controlled recovery using execution ownership/lease or an explicit operator procedure that first proves no execution/transaction is still active. Never reset statuses blindly or repost payments automatically.

Acceptance: terminate a commit worker in staging, restart, recover safely, and prove no duplicate invoices, payments, or stock movements.

Evidence: [SmartImportCommitService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/SmartImportCommitService.java:80>).

### F9 [P2] Generic failures lose useful diagnostics

**Code-inspected.** GlobalExceptionHandler catches unexpected exceptions and returns "Something went wrong" without logging that exception. Some lower layers may log independently, but this catch path does not guarantee diagnostic evidence.

Keep sensitive stack traces out of browser responses. Log exceptions server-side with a request ID and safe tenant/session context; return that request ID to the user. Wire an error tracker and redact tokens, passwords, and source documents.

Evidence: [GlobalExceptionHandler](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/exception/GlobalExceptionHandler.java:64>).

### F10 [P2] Session expiry can leave an unusable dashboard

**Code-inspected; browser expiry scenario not exercised.** The shared API helper clears stored credentials on 401 but does not trigger navigation. AppShell suppresses permission/bootstrap failures and initially defaults to OWNER navigation.

Centralize expired-session handling: clear query caches and credentials, redirect to login, preserve the intended destination, and show a clear expiry message. This is a UX issue, not evidence that the backend accepts unauthenticated calls.

Also define bounded request timeouts. For long-running imports, a timeout means "outcome unknown": retrieve operation status before retrying a write.

Evidence: [web API helper](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/web/lib/api.ts:104>), [AppShell](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/web/components/AppShell.tsx:125>).

### F11 [P2] Tally Hub status ignores Smart Import sessions

**Code-inspected.** Integration status reads legacy import batches and staging tables. Smart Import uses separate session/commit records. A Smart-Import-only workspace can therefore show NO_SUCCESSFUL_IMPORT and zero legacy counts after a successful Smart Import.

Aggregate both supported import paths, or label the existing view as legacy-only until unified.

Evidence: [TallyIntegrationService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/TallyIntegrationService.java:47>).

### F12 [P2] CSV exports do not neutralize spreadsheet formulas

**Code-inspected.** CSV cell escaping handles commas, quotes, and newlines, but exports untrusted names such as =1+1 as spreadsheet formulas. Quoting alone does not make formulas safe.

Apply a documented spreadsheet-safe policy to untrusted text cells while preserving numeric columns. Test the exported file in the spreadsheet applications clients use.

Evidence: [ReportsController CSV escaping](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/web/ReportsController.java:188>).

## Operational Launch Gates

These are missing verification or repository controls, not claims about settings inside your hosting accounts.

- [ ] **Restore a real backup into a separate database.** Verify schema/migration version, row counts, tenant ownership, and representative stock/invoice/payment/outstanding totals. Record restoration duration and maximum acceptable data loss. Do not test a restore over production.
- [ ] **Preserve uploaded files.** LocalObjectStorageService stores uploads on disk; production defaults to /app/uploads. Verify this is the actual persistent mount, or use durable object storage. Restart/redeploy and confirm a prior upload is still readable. Render documents that an unmounted local filesystem is ephemeral: [Render persistent disks](https://render.com/docs/disks).
- [ ] **Rate-limit exposed operations.** No application-wide API/auth limiter was found; verification-email cooldown is not a login/upload limiter. Bound login/reset/register requests and authenticated uploads/expensive import operations. Return 429 without leaking account existence.
- [ ] **Observe failures.** Add structured logs, request IDs, error tracking, and alerts for repeated 5xx, failed imports, and prolonged operations. Verify with a controlled staging failure.
- [ ] **Test health behavior.** Liveness/readiness endpoints exist. Verify production readiness actually fails when the required database is unavailable, and that the platform probes the intended path.
- [ ] **Test PostgreSQL with Flyway.** Current test profile uses H2 and disables Flyway. Add a PostgreSQL job that applies all migrations, then exercises upload, review, dry run, commit, rollback, and repeat import. Test clean install and upgrade from the deployed schema.
- [ ] **Add a post-deploy smoke script.** Check frontend login, API readiness, authenticated current-user/workspace access, list APIs, and a small isolated import dry run. Never automatically final-commit real client data as a smoke check.
- [ ] **Rehearse the real dataset in staging.** Measure memory, query count, and elapsed time for the large export. Dashboard/stock loops perform repeated per-product/warehouse queries, and several services load broad histories. No real-data load benchmark was completed in this review.
- [ ] **Confirm production configuration.** Check prod profile, exact frontend/API/CORS origins, non-demo settings, email delivery, OAuth configuration, secrets, and upload storage using the actual deployment. Local success is not proof of hosted configuration.
- [ ] **Ship a matched release.** Many changes and V18-V21 migrations are currently uncommitted/untracked. Review and include the complete intended backend/frontend/migration set, leaving private exports, credentials, and runtime files out of Git.

Storage evidence: [LocalObjectStorageService](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/LocalObjectStorageService.java:13>), [production storage setting](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/resources/application-prod.yml:34>).

CI evidence: [test database profile](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/test/resources/application-test.yml:3>), [CI workflow](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/.github/workflows/ci.yml:20>).

## Suggested Stabilization Order

This is a target sequence, not a promise that all fixes fit into one week.

| Phase | Focus | Exit condition |
| --- | --- | --- |
| Day 1 | Disable mock OCR; unify customer/supplier reporting | No fabricated extraction; balances agree |
| Days 1-2 | Imported-history analytics and unit costing | Sales history is counted once; pack costs reconcile |
| Day 3 | Supplier invoice identity and import recovery | Legitimate invoices accepted; repeat/restart tests pass |
| Day 4 | Sensitive-field permissions, token revocation, rate limits | Role and session-abuse tests pass |
| Day 5 | Logs, error tracking, durable storage, backup restore | Diagnose a controlled failure and restore successfully |
| Days 6-7 | PostgreSQL migration tests, actual export rehearsal, deploy smoke | Owner signs off reconciled results and supported workflows |

If any gate fails, narrow the pilot scope or extend stabilization. Do not hide inaccurate numbers behind a disclaimer and call the workflow production-ready.

## First-Client Acceptance Checklist

- [ ] Create an isolated customer workspace with no demo records.
- [ ] Export and preserve the agreed source period and closing/outstanding snapshot date.
- [ ] Upload -> classify -> stage -> review -> dry run -> inspect impact -> explicitly confirm final commit.
- [ ] Match product quantities by warehouse, invoice counts/totals, customer/supplier balances, and stock values against source reports for the same dates.
- [ ] Verify item-based sales and amount-only retail receipts are distinguishable; do not infer product demand or margin from unitemized receipts.
- [ ] Ensure negative stock and excluded/financial-only vouchers remain visible, intentional review decisions.
- [ ] Repeat the same import: no duplicate invoices, payments, receipts, or stock.
- [ ] Restart/redeploy: files, review decisions, and completed results remain available.
- [ ] Confirm two tenants cannot read or modify each other's records, and restricted staff cannot read cost/profit data.
- [ ] Exercise session expiry, password reset, network interruption, invalid file, and backend-unavailable cases.
- [ ] Verify restored data in a separate database and retain a short operator recovery runbook.
- [ ] Have the owner approve the reconciled results before using recommendations for purchasing or collections.

Keep Tally as the business source of record during this pilot. Offer only tested workflows. Do not promise live sync, working OCR, or broad manufacturing coverage as part of this release.

## Commands and Results

| Component | Command | Result |
| --- | --- | --- |
| Backend | .\\mvnw.cmd test package | PASS: 223 tests, 0 failures/errors/skips; JAR packaged |
| Web | npm test | PASS: 32 tests |
| Web | npm run lint | PASS |
| Web | npm run build | PASS: Next.js production build |
| Mobile | npm run typecheck | PASS |
| Repository | git diff --check | PASS; existing LF/CRLF conversion warnings |
| Diagnostic probes | Java source launcher + existing test classpath | PASS: seven defects reproduced in isolated H2 data |
| Running local backend | GET /actuator/health/liveness | HTTP 200 |
| Running local frontend | GET /login | HTTP 200 |

The initial web-test attempt hit sandbox worker-spawn restrictions; its approved rerun passed. The diagnostic harness initially could not open a loopback server, then ran successfully using an in-process mock servlet context without a listening port.

The seven probes deliberately assert current faulty behavior. They are diagnostic evidence, not regression tests asserting the desired fix. The ignored source is [LaunchReviewProbe.java](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/target/launch-review/LaunchReviewProbe.java>); a clean build may remove it.

Existing test success is useful but not a launch certificate. Many web tests inspect source strings rather than exercising the full browser workflow. This review did not complete a fresh live-client final commit, real-data performance benchmark, production backup restoration, or hosted deployment audit.

## Changes Made During the Original Review

Only this review document and the ignored diagnostic harness were created/updated. No application-source fixes, production configuration edits, business-data changes, Git commits, pushes, or deployments were performed for this review. Existing user changes and running development servers were preserved.
