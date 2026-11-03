# Project Structure

Generated for external code review.

## 1. Project Folder Tree

```text
stockpilot-ai/
  backend/
    Dockerfile
    pom.xml
    src/main/java/com/stockpilot/ai/
      config/
      domain/
      exception/
      repo/
      service/
      web/
      StockPilotApplication.java
    src/main/resources/
      application.yml
      db/migration/
      sample-imports/
    src/test/java/com/stockpilot/ai/
      service/
      web/
    src/test/resources/
  web/
    app/
      dashboard/
      products/
      stock/
      purchases/
      sales/
      customers/
      suppliers/
      imports/
      integrations/tally/
      forecasts/
      forecast/
      dead-stock/
      data-quality/
      assistant/
      ai-assistant/
      reports/
      audit-logs/
      portal/
      settings/
      login/
      register/
      onboarding/
    components/
    lib/
    __tests__/
    package.json
    package-lock.json
    next.config.mjs
    tailwind.config.ts
    tsconfig.json
  mobile/
    app/
      dashboard.tsx
      products.tsx
      stock-in.tsx
      stock-out.tsx
      transfer.tsx
      low-stock.tsx
      reorder.tsx
      invoice-upload.tsx
      settings.tsx
    src/
      components/
      lib/
    app.json
    eas.json
    package.json
    package-lock.json
    tsconfig.json
  .github/workflows/ci.yml
  docs/
  docker-compose.yml
  .env.example
  README.md
  AGENTS.md
```

Excluded from the review zip: `node_modules`, `target`, `build`, `dist`, `.next`, `.expo`, `.git`, `coverage`, log files, `.env` files except `.env.example`, private keys, secrets, and generated binaries.

## 2. Backend Modules

- `config`: Spring Security, JWT, CORS, tenant context.
- `domain`: JPA entities for tenancy, catalog, stock ledger, purchase/sales documents, import staging, forecasting, AI, payment reminders, and audit logs.
- `repo`: Spring Data JPA repositories, including tenant-scoped query methods.
- `service`: business logic for auth, catalog, stock ledger, purchases, sales, dashboard metrics, imports, forecasting, reorder, dead stock, data quality, AI assistant, portal, audit, object storage, OCR mock, and demo seed data.
- `web`: REST controllers and DTO records.
- `exception`: API exception model and global error response handler.
- `resources/db/migration`: Flyway schema migrations.
- `resources/sample-imports`: sample CSV/XML import files for review and local testing.

## 3. Frontend Routes

- `/`: redirects/entry route.
- `/login`: login with demo role account picker.
- `/register`: workspace registration.
- `/onboarding`: currently reuses settings flow.
- `/dashboard`: owner/action dashboard and KPI cards.
- `/products`: product list/create/search.
- `/stock`: current stock, adjustments, transfers.
- `/purchases`: purchase invoice entry/list.
- `/sales`: sales invoice entry/list.
- `/customers`: customers, outstanding balances, payment reminders, payment received.
- `/suppliers`: suppliers.
- `/imports`: import upload, mapping, validation, preview, commit.
- `/integrations/tally`: Tally companion import dashboard.
- `/forecasts` and `/forecast`: forecasting and smart reorder suggestions.
- `/dead-stock`: dead-stock action center.
- `/data-quality`: duplicate/missing-field cleanup.
- `/assistant` and `/ai-assistant`: AI assistant.
- `/reports`: CSV export hub.
- `/audit-logs`: tenant audit log viewer.
- `/portal`: dealer/customer portal.
- `/settings`: business mode, stock policy, warehouses.

## 4. Mobile Screens

- `index.tsx`: login.
- `dashboard.tsx`: mobile summary/action hub.
- `products.tsx`: product lookup and manual barcode input.
- `stock-in.tsx`: quick stock-in adjustment.
- `stock-out.tsx`: quick stock-out adjustment.
- `transfer.tsx`: warehouse transfer.
- `low-stock.tsx`: low-stock alert list.
- `reorder.tsx`: reorder suggestions.
- `invoice-upload.tsx`: invoice file upload/mock extraction.
- `settings.tsx`: API/demo mode/settings/sign-out.

## 5. API Endpoints

Auth and account:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `GET /api/auth/me`
- `GET /api/auth/me/permissions`
- `GET /api/auth/me/tenants`
- `DELETE /api/account`

Tenant/settings:

- `GET /api/tenants/current`
- `PUT /api/tenants/current`
- `POST /api/tenants/current/business-mode`

Catalog and parties:

- `GET /api/products`
- `POST /api/products`
- `GET /api/products/{id}`
- `PUT /api/products/{id}`
- `DELETE /api/products/{id}`
- `GET /api/products/search`
- `GET /api/products/barcode/{barcode}`
- `GET /api/warehouses`
- `POST /api/warehouses`
- `PUT /api/warehouses/{id}`
- `GET /api/customers`
- `POST /api/customers`
- `GET /api/customers/{id}`
- `PUT /api/customers/{id}`
- `GET /api/customers/{id}/outstanding`
- `GET /api/customers/payment-reminders`
- `POST /api/customers/{id}/payment-reminders`
- `POST /api/customers/{id}/payments`
- `GET /api/suppliers`
- `POST /api/suppliers`
- `GET /api/suppliers/{id}`
- `PUT /api/suppliers/{id}`

Purchases, sales, stock:

- `GET /api/purchases`
- `POST /api/purchases`
- `GET /api/purchases/{id}`
- `GET /api/sales`
- `POST /api/sales`
- `GET /api/sales/{id}`
- `GET /api/stock/current`
- `GET /api/stock/product/{productId}`
- `POST /api/stock/adjustment`
- `POST /api/stock/transfer`
- `GET /api/stock/movements`

Dashboard, intelligence, reports:

- `GET /api/dashboard/summary`
- `GET /api/dashboard/sales-trend`
- `GET /api/dashboard/profit-loss`
- `GET /api/dashboard/low-stock`
- `GET /api/dashboard/dead-stock`
- `GET /api/dashboard/outstanding`
- `GET /api/dashboard/actions`
- `GET /api/dashboard/top-products`
- `GET /api/dashboard/slow-moving-products`
- `GET /api/alerts/low-stock`
- `POST /api/forecast/run`
- `GET /api/forecast/results`
- `GET /api/forecast/reorder-suggestions`
- `GET /api/reorder/suggestions`
- `POST /api/reorder/generate-purchase-order`
- `GET /api/dead-stock`
- `POST /api/dead-stock/{productId}/action`
- `GET /api/data-quality/summary`
- `GET /api/data-quality/products/duplicates`
- `GET /api/data-quality/products/missing-fields`
- `POST /api/data-quality/products/{id}/apply-suggestion`
- `POST /api/data-quality/products/merge`
- `GET /api/insights/profit-drop`
- `POST /api/ai/assistant/chat`
- `POST /api/ai/assistant/profit-drop`
- `GET /api/ai/insights`
- `POST /api/ai/insights/generate`
- `GET /api/reports/export/{report}`
- `GET /api/audit-logs`

Imports and integrations:

- `POST /api/imports/upload`
- `GET /api/imports`
- `GET /api/imports/{batchId}`
- `GET /api/imports/{batchId}/preview`
- `POST /api/imports/{batchId}/mapping`
- `POST /api/imports/{batchId}/validate`
- `POST /api/imports/{batchId}/commit`
- `GET /api/imports/{batchId}/errors`
- `GET /api/imports/{batchId}/reconciliation`
- `GET /api/imports/templates`
- `POST /api/imports/templates`
- `POST /api/imports/invoice-upload`
- `GET /api/integrations/tally/status`

Dealer/customer portal:

- `GET /api/portal/products`
- `GET /api/portal/price-list`
- `POST /api/portal/orders`
- `GET /api/portal/orders`
- `GET /api/portal/outstanding`
- `GET /api/portal/invoices`

Health/API docs:

- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /swagger-ui.html`
- `GET /v3/api-docs`

## 6. Database Tables And Migrations

Migrations:

- `V1__init_schema.sql`: base multi-tenant schema.
- `V2__portal_and_order_items.sql`: customer portal linkage and order item tables.
- `V3__payment_reminders.sql`: customer payment reminder workflow.
- `V4__tenant_stock_policy.sql`: tenant stock policy fields.

Main tables:

- Tenancy/auth: `tenants`, `app_users`, `user_tenant_memberships`, `branches`.
- Catalog: `product_categories`, `brands`, `units_of_measure`, `products`, `unit_conversions`, `product_barcodes`, `product_batches`, `product_tax_infos`, `reorder_settings`.
- Warehouse/stock: `warehouses`, `stock_movements`.
- Parties/orders: `suppliers`, `customers`, `customer_groups`, `customer_price_lists`, `purchase_orders`, `purchase_order_items`, `purchase_invoices`, `purchase_invoice_items`, `supplier_payments`, `sales_orders`, `sales_order_items`, `sales_invoices`, `sales_invoice_items`, `delivery_challans`, `customer_payments`, `payment_reminders`.
- Imports: `import_batches`, `import_files`, `import_mapping_templates`, `import_errors`, `staging_products`, `staging_customers`, `staging_suppliers`, `staging_invoices`, `staging_stock_movements`.
- Intelligence: `forecast_results`, `reorder_suggestions`, `dead_stock_insights`, `profit_insights`, `ai_conversations`, `ai_messages`.
- Audit: `audit_logs`.

## 7. Known TODOs / Placeholders

- OCR is intentionally local/mock through `LocalMockInvoiceExtractionService`.
- AI can run locally via rule-based services; optional LLM provider is an interface and env-configured placeholder.
- RBAC is permission-catalog based, not yet DB-backed `roles` / `permissions` / `role_permissions`.
- User invitation, role assignment UI, supplier portal UI, platform admin UI, approval workflows, and support access grants are not complete.
- Onboarding route currently reuses settings page.
- Tally import supports common XML/export shapes and sample files, but should be expanded against real customer exports.
- No production email verification/password reset flow yet.
- Backend tests could not be run on the current laptop without Java 21 and Maven installed.

## 8. Test Commands

```bash
cd backend
mvn test

cd web
npm test
npm run lint

cd mobile
npm run typecheck
```

## 9. Build Commands

```bash
docker compose up --build

cd backend
mvn spring-boot:run

cd web
npm install
npm run build
npm run dev

cd mobile
npm install
npm start
npm run typecheck
```

## 10. Environment Variables Required

The canonical local backend example is [`.env.example`](../.env.example).
The production Render/Neon checklist is [`.env.neon.example`](../.env.neon.example).
Browser-safe variables are in [`web/.env.example`](../web/.env.example), and Expo
variables are in [`mobile/.env.example`](../mobile/.env.example).

Do not add backend database, JWT, or email secrets to Vercel. The exact required,
fixed, and optional production variables are documented in
[`docs/NEON_DEPLOYMENT.md`](NEON_DEPLOYMENT.md).
