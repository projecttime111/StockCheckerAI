# Import reliability, reconciliation and assistant

## Import workflow

Smart Imports now queues classification, staging, preview and commit in `import_jobs`. The browser submits a stable request ID and polls a saved job; reloading or losing the response does not submit another commit. The worker recovers expired claims after a backend restart, backs off transient failures and stops after three attempts. Business commits retain their atomic, idempotent transaction. A completed commit refreshes forecasts; a refresh failure is reported separately without undoing the import.

The worker runs inside the API process and needs the same persistent upload storage as the API. Jobs remain queued while the backend is asleep or stopped and resume when it runs again. This does not add a scheduled Tally connector: a future connector should upload through the same tenant-authenticated staging workflow. No Redis or RabbitMQ is required for these jobs. Keep `APP_IMPORT_JOBS_ENABLED=true` (default). Read-only viewers cannot submit jobs; commit still requires `imports.commit` and an approved dry run.

Canonical source metadata retains company identity, source record identity, revision, cancellation state, raw fields and complete line hashes. An unchanged replay remains idempotent. A changed sale or purchase requires an explicit source revision review, a reason and confirmation of the reviewed hash. The review displays old/new lines, totals and stock effects.

Supported corrections retain the original document identity, archive its old contents, reverse prior stock effects and apply the reviewed replacement once. Cancellation retains the document and audit history with a zero financial balance. Corrections involving payments, dependent adjustments, covering balance snapshots, later stock baselines, changed party/date/document number/warehouse or unresolved product/unit/warehouse mappings are blocked for review. Revision sessions are deliberately limited to one document. Classic imports continue to reject conflicting revisions; use Smart Imports for reviewed corrections.

## Reconciling an Excel file

1. Upload the file to Smart Imports. Check its classification and all validation/review issues; preserve unknown columns in source metadata and map ambiguous fields.
2. Review the import plan and stock mode. Closing-stock snapshots and transaction history must use the correct cutoff date so historical vouchers do not double-count stock.
3. Run the preview, resolve every blocking issue, then confirm commit. The job panel displays pending, running, retry and completed states.
4. Open **Source reconciliation**. Invoice count, gross invoice totals, tax, complete document content and dated stock snapshots are compared with their matching committed records. Repeated identical exports count once. Stock quantities are converted to each product's base unit and checked at the snapshot date.
5. Compare the original workbook independently with the parsed scope. Reconciliation cannot certify rows the parser did not recognize; ignored rows, unsupported voucher types, failures and missing baselines remain visible as partial coverage. Payments, returns and journals have their own review/effect reports and are not silently included in gross invoice totals.
6. Match dashboard dates and business scope to the file. The dashboard may also contain earlier imports. Opening/closing stock is not sales revenue, and a master file alone cannot establish profit or demand.

The reconciliation endpoint is `GET /api/import-sessions/{id}/reconciliation?page=0&size=100`; its overall status covers the entire report, not only the displayed page. It requires `imports.validate`.

## Analytics quality

The readiness panel reports confirmed history dates, last committed import, forecast freshness, missing costs and estimated costs. Cost quality details follow financial permissions. Confirm complete history only when the source covers **all sales** in that date range; gaps without this confirmation do not prove zero sales. `PUT /api/analytics/coverage` records the confirming user and audit event.

Forecasts use the actual available or confirmed observation window (up to 90 days), including historical periods, rather than dividing a short upload by 90 days. Invoice-only history contributes to demand and is not counted again through its matching stock movement. Unconfirmed estimates remain provisional. Calendar month profit comparisons use matching elapsed dates. Imported sales costs are labelled estimated/current-cost or legacy/unverified; gross profit remains an estimate until source cost provenance supports an accounting reconciliation.

## Assistant configuration

The assistant has private saved conversations, follow-up context, permission-checked read-only analytics tools, dated evidence and retry-safe requests. It does not write stock, invoices, orders or payments. Conversations are scoped to both user and tenant, and results are redacted if financial access is later removed. Limits bound request frequency, tool calls, context and provider response time.

Hosted AI is **off by default**. Without a provider, the UI identifies the local analytics fallback. The current hosted adapter implements Gemini native function calling behind the `AiProvider` interface; adding another provider needs an adapter and its protocol tests, not changes to financial calculations.

Backend-only configuration (Render/local backend; never `NEXT_PUBLIC_*` or Vercel frontend variables):

```dotenv
APP_AI_ENABLED=false
APP_AI_PROVIDER=gemini
APP_AI_MODEL=
GEMINI_API_KEY=
APP_AI_TIMEOUT_SECONDS=20
APP_AI_USER_REQUESTS_PER_MINUTE=10
APP_AI_TENANT_REQUESTS_PER_MINUTE=60
```

After selecting a model/provider and provisioning its key, set the model ID and private key, then enable hosted AI. Enabling it sends permitted question context and tool results to that provider. An invalid enabled configuration fails startup explicitly. Timeouts/provider failures return a labelled local fallback. `LLM_ENDPOINT` and `LLM_API_KEY` are obsolete; arbitrary provider endpoints are not accepted. No key is required for the local fallback.

## Deployment and validation

Deploy backend and web together. Flyway V29–V31 add source revisions, durable jobs, coverage/cost provenance and private chat request tracking. They preserve existing tenant data. Existing Vercel API/auth variables remain unchanged. Render's persistent `/var/data/uploads` storage remains required. No production data reset is needed.

Regression coverage includes actual generated XLSX upload through staging/preview/background commit, source-to-invoice totals, demand denominators, duplicate/revision/cancellation behavior, transaction rollback, job recovery, tenant and role isolation, private chat history and provider protocol mocks. PostgreSQL tests run only against the guarded disposable local database. A successful test does not validate a customer's unprovided Excel file or a live model account: those are the next acceptance checks.

Validation on 2026-11-10: 462 backend tests passed, including 59 PostgreSQL checks; the optional large commit-replay benchmark was skipped. All 166 web tests, TypeScript/lint checks and the web production build passed. The generated XLSX scenario reconciled two invoices totalling INR 75 and five sold units; its two-day observed demand was 2.5 units/day. Resetting that test workspace also removed its jobs, coverage, source revisions and saved chat records without foreign-key failures. No production database or live AI account was used.
