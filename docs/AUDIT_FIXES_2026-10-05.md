# StockPilot Audit Fix Evidence

Date: 2026-10-05. This follows the [launch readiness audit](LAUNCH_READINESS_REVIEW.md) and records the implemented first batch, test evidence, compatibility changes, and remaining limits. It is not a claim that every module is bug-free or ready for unrestricted client access.

Existing in-progress Smart Import changes were preserved. No Git commit, push, deployment, production configuration change, secret change, customer import, or financial-data repair was performed. A local schema change occurred because of a test-isolation mistake, documented below; it must not be hidden behind a claim that all local data was untouched.

## Finding Results

### F2 Consistent Outstanding Balances

Customer CSV, customer portal, and assistant answers now use the existing tenant-scoped `OutstandingService`, as the customer screen and dashboard do. Supplier CSV retains `opening_balance` and appends `current_payable` from the same service. Snapshot cutoffs and credit/debit adjustments are not replaced by a second formula.

Regression: snapshot 500, subsequent invoice 100, payment 20 and credit 10 produce customer outstanding 570 in the screen, CSV, portal and dashboard. Before/on-snapshot entries do not get counted again. Supplier payable is 470 while its separate opening balance remains 50. Assistant evidence uses the snapshot balance. Tests also reject another tenant's customer and verify that its identifier is absent from the export.

### F3 Unit Cost Conversion

`StockMovement.rate` remains a source-unit rate. New movement value is absolute source quantity multiplied by that rate. Base quantity is stored separately. New purchases save product default cost per base unit; new sales translate that base cost back to their source unit when creating the movement. Stock and dashboard valuation derive base cost using the purchase movement's stored quantity ratio, not today's conversion setting.

Regression: two boxes of 12 pieces at 120 per box give invoice total 240, base stock 24, base-unit cost 10 and stock value 240. Selling one box leaves stock value 120 with a sale cost movement of 120. Fractional conversion 0.5 and multiplier 1 are covered. Ledger-level return-in, return-out and imported opening movements also preserve source value. Invalid conversion multipliers are rejected, not silently replaced.

**Still open:** this is not a repair of old `totalValue`, `costRate`, or product fallback costs. Smart Import uses additional source-rate/cost paths; its complete historical costing and financial-return reconciliation have not been certified by these tests. Do not backfill or reprice existing invoices automatically. Review that data separately with source evidence and an approved repair plan.

### F5 Password Reset Revocation

New migration V23 adds `app_users.auth_version` with default zero. Password reset increments it in the reset transaction. JWTs include the current version, and authentication compares it with the current account before accepting the token. All application token-issuance paths pass the version, including Google sign-in and workspace lifecycle operations.

Regression: old `/api/auth/me` and refresh requests return 401 after reset, new login succeeds, an unrelated account stays authenticated, and requesting another reset does not reactivate old tokens. Missing-version legacy tokens are treated as version zero, so the schema upgrade does not sign everyone out; resetting an account invalidates those tokens too.

Migration checks cover V22 to V23 with an existing account, unchanged password hash, default auth version zero, repeat migration with no additional work, and PostgreSQL schema validation. No applied migration was edited.

### F6 Honest Invoice Extraction Availability

The unconditional fixed-data extractor was removed. Its replacement returns HTTP 503 with `INVOICE_EXTRACTION_UNAVAILABLE`. The controller attempts extraction before storage, so this unavailable path does not save an upload. Mobile no longer offers the nonfunctional upload action; it displays the unavailable state.

Regression: arbitrary image bytes cannot return invented supplier, customer, confidence or storage-key data. This does not implement OCR. Real image/PDF extraction remains a future, separately tested integration.

### F10 Session Expiry

Authenticated 401 responses clear credentials, notify the query provider to clear caches, and navigate to login with an expiry message and intended destination. Failed public login requests do not trigger an expiry redirect. A delayed 401 for an older token cannot clear a newer login. Replacing a session also removes stale roles and permissions. AppShell no longer defaults to owner access while identity loads and redirects a missing-token session to login.

Regression tests execute the real shared API helper for expiry, late-response races, public-login failure, stale session metadata and safe return URLs. Customer users cannot use the return parameter to bypass the portal landing rule. Query retry logic does not retry authentication failures.

**Still open:** browser-level cache-clear/redirect verification and network-interruption UX; bounded request timeouts and checking operation outcome before retrying import writes. No arbitrary timeout/retry was added to final commit.

### F11 Tally Hub Import Status

The status query now considers successful legacy batches and successful Smart Import commits. It chooses the most recent successful path and counts distinct affected entities from non-reversed Smart Import effects. Multiple effects for one entity are not counted as multiple products or invoices. Legacy success lookup is no longer limited to the last 25 batches.

Regression: a Smart-Import-only workspace reports `CONNECTED_BY_EXPORT` and the successful commit date, distinct product/party/invoice counts remain one when effects repeat, and another tenant still has no successful import.

**Limit:** these are counts for the latest successful import, not lifetime business totals or proof of live synchronization. Existing error and unmapped counters still describe legacy staging. They were not broadened silently to mix unlike metrics.

### F12 Spreadsheet Safe CSV Text

Untrusted text cells with formula prefixes `=`, `+`, `-`, `@` after leading whitespace, or containing tab/newline controls, receive an apostrophe prefix before CSV syntax escaping. Numeric values remain numeric, including genuine negative amounts.

Regression covers formula strings, leading spaces, tabs, quoted/comma-containing names and a negative numeric balance. Manual opening in the customer's Excel/LibreOffice version is still required; automated string assertions are not a claim of universal spreadsheet compatibility.

## Compatibility Notes

- Supplier CSV adds a final `current_payable` column. Header-based readers continue to work; strict fixed-column consumers must accept the appended field.
- Invoice extraction now returns explicit 503 rather than fake success. No ERP import endpoint was removed.
- JWT claim `av` and V23 are required for the new backend. Apply the reviewed migration with the release; old version-zero sessions remain valid until reset or expiry.
- Stock movement values and new default purchase costs have corrected unit semantics. Sales invoice selling prices are unchanged. Historical stock costs are not rewritten.
- CSV text may include a protective leading apostrophe. Do not remove it before opening the file in spreadsheet software.
- Expired web sessions navigate to login. Public authentication error responses keep their normal behavior.
- Tally Hub reports the latest successful import across both pipelines, not an always-live connection.

## Reproduction and Verification

New regression sources:

- `backend/src/test/java/com/stockpilot/ai/web/LaunchAuditRegressionTest.java`
- `backend/src/test/java/com/stockpilot/ai/web/LaunchAuditPostgresIntegrationTest.java`
- `backend/src/test/java/com/stockpilot/ai/web/LaunchAuditMigrationTest.java`
- `backend/src/test/resources/application-audit-postgres.yml`
- `web/__tests__/session-expiry.test.mjs`

Failing baseline evidence was captured before the corresponding application fixes:

| Evidence | Reproduction |
| --- | --- |
| `.codex-logs/audit-baseline-tests.log` | Five failures: customer/supplier CSV, reset token validity, fabricated extraction, unsafe CSV text |
| `.codex-logs/audit-cost-baseline.log` | Three failures: assistant snapshot balance, pack valuation, fractional conversion |
| `.codex-logs/audit-tally-baseline.log` | Smart-Import-only workspace incorrectly reported no successful import |
| Initial `node --test` run | Four session behavior failures and one passing URL-safety baseline |

These ignored logs are local evidence, not files to publish with source data. Password-reset tests log synthetic local-only reset links; do not publish those raw logs.

The first resumed full suite ran 274 test executions with one failure and one intentional skip. The failure was the tenant-isolation guardrail rejecting the new repository method name `countEntities`. The query already included both tenant and commit predicates. Renaming it to `countEntitiesByTenantIdAndCommitId` fixed the convention without modifying or weakening the guardrail assertions. The complete rerun then passed.

| Final check | Result |
| --- | --- |
| Backend full suite with isolated PostgreSQL enabled | 274 reported: 273 passed, zero failures/errors, one intentionally skipped real-data replay; `.codex-logs/audit-regression-final.log` |
| PostgreSQL targeted audit and migration checks before resume | 21 executions, zero failures/errors/skips, including H2 equivalents |
| Frontend `npm test` | 44 passed |
| Frontend `npm run lint` | Passed |
| Frontend `tsc --noEmit --incremental false` | Passed |
| Frontend `npm run build` | Passed, 31 routes; `.codex-logs/audit-web-build-final.log` |
| Mobile `npm run typecheck` | Passed |
| `git diff --check` | Passed; existing LF/CRLF warnings |

PostgreSQL tests run against the separate local cluster on `127.0.0.1:55434`, database `stockpilot_audit`, not the business database on port 5432. The integration class pins BOTH Spring datasource and Flyway connections at highest test property precedence. A migration guard checks the actual JDBC URL and database user before migration. The migration upgrade test creates a uniquely named schema only inside the same disposable audit database. Tests remain opt-in outside this environment.

Re-run only after preparing that disposable cluster, from `backend`:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=Z:\stockpilot-no-socket-dir'
$env:STOCKPILOT_AUDIT_PG_URL='jdbc:postgresql://127.0.0.1:55434/stockpilot_audit'
.\mvnw.cmd test
```

The PostgreSQL version was 18.4. Flyway emitted its older tested-version warning; migration, validation and database regression outcomes are reported separately above. This local opt-in suite does not establish a PostgreSQL CI job or certify the deployed database version.

## Test Isolation Incident

During the first PostgreSQL attempt, the new test profile overrode the application datasource but not Flyway's independently configured URL. Flyway therefore applied V23 to the **local app database** at `localhost:5432/stockpilot` before application startup failed because the separate test database still lacked tables. This added `auth_version` with default zero and recorded V23 in local Flyway history. No integration test business-record writes ran against that local database, and no hosted production database was contacted. This was an unintended local schema change, not an approved data repair.

Evidence: `.codex-logs/audit-postgres-tests.log`, around the 2026-10-05 11:56 UTC Flyway startup. No rollback or manual history edit was attempted. The local schema is left for explicit review. Subsequent tests pin and verify both URLs as described above.

The first correctly isolated PostgreSQL run then exposed incomplete test fixtures: outstanding snapshots require source session/file references in the real schema. The fixtures now create those required records. Production constraints were not relaxed and assertions were not removed. The later targeted rerun passed.

On resuming, a sandboxed status check failed to see the existing audit server. A start attempt failed to open the occupied log; a direct approved connection check confirmed the original isolated server was still healthy. No second cluster or app-server restart was needed.

## Files Changed for This Batch

Paths below are relative to the repository root. Other dirty/untracked files in the worktree predate this audit batch and were not reverted.

| Area | Files |
| --- | --- |
| Balances and CSV | `backend/src/main/java/com/stockpilot/ai/web/ReportsController.java`, `service/PortalService.java`, `service/AiAssistantService.java` under the same Java package root |
| Cost conversion | `backend/src/main/java/com/stockpilot/ai/service/StockLedgerService.java`, `PurchaseService.java`, `SalesService.java`, `DashboardService.java` |
| Authentication | `backend/src/main/java/com/stockpilot/ai/config/JwtService.java`, `JwtAuthenticationFilter.java`; `domain/UserAccount.java`; `service/AuthService.java`, `AccountLifecycleService.java` under the package root |
| Migration | `backend/src/main/resources/db/migration/V23__account_auth_version.sql` (new) |
| Extraction | `backend/src/main/java/com/stockpilot/ai/service/UnavailableInvoiceExtractionService.java` (new); `LocalMockInvoiceExtractionService.java` (removed); `web/ImportController.java` under the package root; `mobile/app/invoice-upload.tsx` |
| Integration status | `backend/src/main/java/com/stockpilot/ai/service/TallyIntegrationService.java`, `backend/src/main/java/com/stockpilot/ai/repo/Repositories.java` |
| Web session handling | `web/lib/api.ts`, `web/lib/query.tsx`, `web/components/AppShell.tsx`, `web/components/GoogleSignInButton.tsx`, `web/app/login/page.tsx` |
| Tests | The five new regression/profile files listed in Reproduction and Verification |
| Audit evidence | `docs/LAUNCH_READINESS_REVIEW.md`, `docs/AUDIT_FIXES_2026-10-05.md` |

## Remaining Launch Work

1. **F4: sensitive-field permissions.** Restrict purchase costs and profit across products, stock, dashboards, reports and assistant responses on the server. UI hiding is insufficient. Include direct API role-matrix tests and cross-tenant tests.
2. **F1: imported sales demand.** Build a common base-unit demand input from invoice items and explicitly deduplicate legacy movement-only history. Do not post extra stock movements to make forecasts work. Distinguish unavailable history from zero sales.
3. **F7: document identity.** Agree supplier/customer/fiscal-year/source identity rules, then update constraints, duplicate checks, imports and idempotency together with a forward migration and PostgreSQL tests.
4. **F8: interrupted commits.** Add execution ownership and controlled recovery, then terminate a worker during a staging commit and verify safe recovery without duplicate effects. Never blindly reset a live `COMMITTING` record.
5. **F3 historical and imported costing.** Verify source/base units across import and financial adjustment paths; propose any existing-data repair separately with an impact preview and backup.
6. **F9/F10 and operations.** Add safe request IDs/error tracking, rate limits, bounded reads and write-outcome handling. Verify durable uploaded files, backup restoration, readiness and a non-destructive deployment smoke test.
7. **Pilot rehearsal.** Use an isolated copy of the large export; reconcile dated stock, invoices, payments, outstanding and exclusions with the owner. Test repeated imports and record resource usage before offering access.

The full 337 MB customer export, real-data final commit/recovery, hosted deployment, backup restore, physical mobile device, browser expiry flow and spreadsheet application behavior were not reverified in this batch. The opt-in real-dataset replay test was not enabled. Do not describe passing unit/integration tests as client launch approval.

## Services Worth Adding Later

These are proposed product directions, not implemented features and not substitutes for the launch fixes.

- **Import reconciliation service:** an owner-readable comparison of source period, snapshot dates, quantities, invoice totals, payments and exclusions, linked to their source rows. Extend the existing reconciliation workflow rather than creating a second source of truth.
- **Read-only Tally collection agent:** retrieve approved company exports automatically from the customer's machine, with last-success time, checkpoints and retry visibility. Tally supports XML over HTTP; a local, export-only agent is the proposed StockPilot architecture, not a claim of an existing connector. Keep Tally's local endpoint off the public internet. See [Tally XML integration](https://help.tallysolutions.com/xml-integration/) and [integration prerequisites](https://help.tallysolutions.com/pre-requisites-for-integrations/).
- **Physical stock reconciliation:** counted versus recorded quantities, reviewed discrepancy reasons and authorized adjustments. This is particularly relevant where counter sales are recorded as totals without item details; never invent which products were sold.
- **Receivables follow-up:** dated, reconciled balances and an approved reminder workflow. Extend existing reminder functionality after F2 and permission acceptance checks, not before.
- **Reliable import operations:** visible job ownership, progress, failure reasons and controlled recovery. This is an extension of the F8 launch requirement, not automatic reposting.

Prioritize accurate records, permission boundaries and reliable recovery before additional AI models, ERP write-back or manufacturing automation. Backend source changes have been tested but were not packaged into a replacement running server or deployed. After verification, the disposable audit PostgreSQL cluster on port 55434 was stopped successfully. No app server or app database was stopped or restarted during this resumed verification.
