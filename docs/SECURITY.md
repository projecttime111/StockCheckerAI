# Security Notes

## Permission-Protected APIs

StockPilot AI uses role-based authentication plus permission checks through `PermissionService`. Controllers should use explicit `@PreAuthorize` checks for business data endpoints instead of relying only on URL-level authentication.

Important permission groups:

- Dashboard and alerts: `dashboard.view` or `stock.view` for low-stock alerts.
- Profit insights: `insights.profit.view`.
- Dead stock: `dead_stock.view`.
- Reorder suggestions: `reorder.view`.
- Customer receivables: `customers.view_outstanding`.
- Purchase cost and supplier cost: `purchases.view_cost`.
- Data cleanup: `data_quality.view`.
- Reports: `reports.view` and `reports.export`.
- Tally Integration Hub: `integrations.tally.view`; management actions must use `integrations.tally.manage`.

## Tenant Isolation Rule

Every business-owned query must include the current tenant ID from `TenantContext.tenantId()`. Do not use generic repository lookups such as `findById(...)`, `findAll(...)`, `existsById(...)`, `getReferenceById(...)`, or `deleteById(...)` for tenant-owned records.

Required pattern:

```java
products.findByTenantIdAndId(TenantContext.tenantId(), productId)
```

Every client-supplied ID must be checked against the current tenant before it is used to create invoices, stock movements, imports, reports, forecasts, or portal orders. This includes:

- `productId`
- `warehouseId`
- `customerId`
- `supplierId`
- `invoiceId`
- `orderId`
- `importBatchId`
- `stockMovementId`

Raw SQL queries must include `tenant_id = :tenantId` when reading or mutating business data.

## Email Verification And Password Reset

Real user registrations require email verification by default:

```env
APP_AUTH_REQUIRE_EMAIL_VERIFICATION=true
APP_PUBLIC_URL=https://your-vercel-app.vercel.app
EMAIL_PROVIDER=local
EMAIL_FROM=StockPilot AI <noreply@example.com>
```

Registration creates the workspace and owner account but does not issue a full access token while `emailVerified=false`. Login with a valid password is blocked with `EMAIL_NOT_VERIFIED` until `/api/auth/verify-email` succeeds. `JwtAuthenticationFilter` also rejects stale tokens for unverified accounts, so tenant APIs cannot be reached before verification.

Verification and password-reset tokens are generated with a cryptographically secure random source. Only SHA-256 token hashes are stored in `app_users`; raw tokens are sent by email and never stored. Tokens expire and are single-use. Creating a new verification or reset token replaces the older token of the same type.

Public auth endpoints use safe generic responses where email existence must not be disclosed:

- `POST /api/auth/resend-verification`
- `POST /api/auth/forgot-password`

For local development, `EMAIL_PROVIDER=local` logs links through `LocalLoggingEmailService`. This is intentionally local/dev behavior only. For production email delivery, configure Resend:

```env
EMAIL_PROVIDER=resend
RESEND_API_KEY=your-secret-key
EMAIL_FROM=StockPilot AI <noreply@yourdomain.com>
```

If `EMAIL_PROVIDER=resend` is selected without the required secrets, startup/request handling fails clearly instead of silently pretending to send email. Do not disable `APP_AUTH_REQUIRE_EMAIL_VERIFICATION` in production.

## Google Sign-In

Google sign-in uses Google Identity Services on the web and the backend verifies the returned ID token before issuing a StockPilot JWT. It is disabled until both environments are configured:

```env
GOOGLE_CLIENT_ID=your-google-oauth-web-client-id
NEXT_PUBLIC_GOOGLE_CLIENT_ID=your-google-oauth-web-client-id
```

`GOOGLE_CLIENT_ID` belongs to the Spring Boot backend deployment. `NEXT_PUBLIC_GOOGLE_CLIENT_ID` belongs to the Next.js deployment. The backend rejects missing, invalid, unverified, or wrong-audience Google tokens. If a Google email already exists as a password account, the verified Google subject is linked to that same account instead of creating a duplicate user.

In Google Cloud Console, add only trusted frontend origins such as `https://your-project.vercel.app` and local development origins such as `http://localhost:3000` when needed. Do not put Google client secrets in this app; the current flow uses public web client IDs plus backend ID-token verification.

Automated guardrails live in `TenantIsolationGuardrailTest`. The test scans backend source for:

- unsafe base repository access such as `products.findById(...)` on business data;
- tenant-owned repository derived methods that omit `ByTenantId`;
- native SQL touching tenant-owned tables without a `tenant_id` filter.

The allowlist is intentionally small and documented in the test. Current allowed unscoped lookups are limited to authentication/user lookup by ID and tenant-setting lookup by tenant ID because those records are not tenant-owned business data.

## Customer Portal Isolation

`CUSTOMER_USER` accounts are restricted to `/api/portal/**` and `/api/account/**`. Portal services must resolve the linked customer through the current user's `UserTenantMembership` and must filter all orders, invoices, outstanding balances, and price lists by that linked `customerId`.

Portal catalog visibility follows this MVP policy:

- If customer-specific price-list rows exist, the customer sees only those active products.
- If no price-list rows exist, the customer sees all active tenant products only when `portalShowAllActiveProducts=true`.
- `portalShowAllActiveProducts` defaults to `false`.
- When the default is false and no catalog is assigned, the portal returns an empty catalog and the web UI shows: `No products have been assigned to your catalog yet.`

Portal order creation uses the same visible catalog as product browsing. A customer cannot order inactive products, unassigned products, products from another tenant, or products hidden by the tenant catalog policy.

Customer portal users must not access:

- internal reports
- imports
- audit logs
- supplier data
- purchase cost
- profit or margin data
- internal stock movement details
- business settings

## Report and Export Isolation

Report exports require `reports.export` and must query only the current tenant. CSV exports must not accept arbitrary tenant, customer, invoice, or warehouse IDs without verifying ownership through tenant-scoped repository methods.

Current exports use tenant-scoped repositories and services, including current stock, low stock, dead stock, reorder suggestions, master data, invoices, top/slow products, and audit logs.

## Import Isolation

Import batches, staged rows, import errors, mapping templates, and uploaded raw files are tenant-scoped. Import preview, mapping, validation, commit, and error retrieval must first verify that the `importBatchId` belongs to `TenantContext.tenantId()`.

Raw uploaded import files are stored under a tenant-prefixed object key. Invoice uploads also use a tenant-prefixed storage key.

## Stock Ledger Correctness

`StockMovement` is the source of truth for inventory. Current stock is derived by summing `baseQuantity` for a tenant/product/warehouse.

Movement behavior:

- `PURCHASE`, `RETURN_IN`, `TRANSFER_IN`, and `OPENING_BALANCE` increase stock.
- `SALE`, `RETURN_OUT`, and `TRANSFER_OUT` decrease stock.
- `ADJUSTMENT` stores a signed quantity and must include a reason.

Stock-changing operations must validate tenant ownership of every product, warehouse, customer, and supplier ID before writing invoices or movements.

Negative stock is controlled by `Tenant.allowNegativeStock`:

- When false, sales, stock-out adjustments, transfers, and import commits are blocked if they would make stock negative.
- When true, those operations may create negative stock and the ledger writes a `NEGATIVE_STOCK_ALLOWED` audit event with the projected stock.

Transfers are atomic. A transfer creates exactly one `TRANSFER_OUT` from the source warehouse and one `TRANSFER_IN` to the destination warehouse, both sharing the same `referenceId` with `referenceType=STOCK_TRANSFER`.

Stock-changing paths use `StockLockService` before stock checks and movement creation. In PostgreSQL, this uses transaction-scoped advisory locks keyed by tenant/product/warehouse. In H2 tests and local fallback databases, it uses a per-key JVM lock released when the transaction completes. This prevents two simultaneous sales from overselling the same stock when negative stock is disabled.

Audit logs are written for stock movement creation, purchase invoices, sales invoices, stock adjustments, stock transfers, and tenant-approved negative stock events.

## AI Assistant Permission Enforcement

The AI assistant is not allowed to use all tenant data just because a user can open the assistant. `AiAssistantService` performs intent-based permission checks before querying sensitive services.

If a user asks for information outside their permissions, the assistant returns:

```text
You do not have permission to access this information.
```

Denied sensitive AI access attempts are audit logged with `AI_ACCESS_DENIED` and include only the intent and required permission, not business numbers.

## Demo Data Seeding

Demo data is disabled by default:

```properties
app.demo.seed-enabled=false
```

Set `APP_DEMO_SEED_ENABLED=true` only in local/dev environments when you intentionally want demo accounts and demo inventory. The local `docker-compose.yml` keeps it disabled by default for clean import testing. Production environments must leave it disabled.

## Swagger/OpenAPI Exposure

Swagger is protected by default and is public only when:

```properties
app.swagger.public-enabled=true
```

The tracked `prod` profile forces Swagger protection regardless of this environment variable. Production deployments must set:

```env
SPRING_PROFILES_ACTIVE=prod
SWAGGER_PUBLIC_ENABLED=false
```

When disabled publicly, `/v3/api-docs/**`, `/swagger-ui/**`, and `/swagger-ui.html` require an authenticated `OWNER` or `ADMIN` role.

## Audit Logging

The application records audit logs for important sensitive actions, including:

- Account deactivation: `ACCOUNT_DEACTIVATED`.
- Report export: `REPORT_EXPORTED`.
- Import commit: `IMPORT_COMMITTED`.
- Stock movement and stock adjustment actions.
- Payment reminders and received payments.
- Product cleanup and dead-stock actions.
- Denied sensitive AI access: `AI_ACCESS_DENIED`.

## Account And Workspace Lifecycle

Lifecycle APIs are authenticated and tenant-scoped:

```http
GET    /api/tenants/current/delete-impact
POST   /api/tenants/current/reset-business-data
DELETE /api/tenants/current
DELETE /api/account
```

- Delete impact requires `OWNER` or `ADMIN` and counts only the current tenant.
- Workspace reset and deletion require `OWNER`, exact typed confirmation, and a single database transaction.
- Reset preserves the tenant, user account, and membership. Customer-linked memberships are detached from deleted customer records to preserve referential integrity.
- Workspace deletion removes all memberships and soft-deletes the tenant. `JwtAuthenticationFilter` verifies that every tenant token targets an active tenant, so tokens for deleted workspaces are rejected. If no workspace remains, a restricted account-only token can call only authenticated account/onboarding APIs, not tenant business APIs.
- Account deletion is self-service only. It is blocked while the user owns an active workspace, then deactivates and anonymizes the account rather than physically deleting IDs referenced by history.
- Raw import files are scheduled for object-storage deletion after the database commit. A physical cleanup failure writes `UPLOADED_FILE_DELETE_FAILED` and cannot leave a partially rolled-back database transaction.

Audit events include `WORKSPACE_DATA_RESET`, `WORKSPACE_DELETED`, `WORKSPACE_CREATED`, `ACCOUNT_DEACTIVATED`, and storage cleanup failures. Production retention and legal-hold requirements should be reviewed before changing soft deletion to physical deletion.
