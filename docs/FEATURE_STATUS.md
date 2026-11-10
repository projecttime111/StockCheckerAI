# Historical MVP Feature Audit

This is an early MVP audit, not the current deployment status. Several missing items below have since been implemented. See [import, analytics and assistant implementation notes](IMPORT_ANALYTICS_AI.md) for the 2026-11-10 changes and validation, and [platform and Clerk setup](PLATFORM_AND_CLERK_SETUP.md) for the newer identity and workspace system.

Status key:

- `COMPLETE`: implemented and usable for MVP review.
- `PARTIAL`: meaningful implementation exists, but production scope is incomplete.
- `MISSING`: not implemented.
- `MOCK_ONLY`: only a local/mock implementation exists.
- `BROKEN`: known broken flow.

| # | Feature | Status | Notes / Evidence |
| --- | --- | --- | --- |
| 1 | Login and business setup | PARTIAL | JWT login/register, BCrypt, seeded users, business mode settings, and demo role accounts exist. Missing production email verification, password reset, invite flow, and a dedicated onboarding wizard. |
| 2 | Tally/Excel import | PARTIAL | Adapter pattern exists for CSV, Excel, JSON, XML, Tally XML, and Tally Excel. Sample Tally XML files are included. Needs more real-world Tally export coverage and hardening. |
| 3 | Product/customer/supplier import | PARTIAL | Staging entities, validation, templates, preview, errors, and commit paths exist for product/customer/supplier-style rows. Invoice import depth is still limited. |
| 4 | Stock ledger | COMPLETE | `stock_movements` is source of truth. Purchases/sales/transfers/adjustments create ledger movements. Current stock is derived from sums. |
| 5 | Current stock dashboard | COMPLETE | Dashboard summary and stock report use stock ledger-derived current stock and stock value calculations. |
| 6 | Low-stock alerts | COMPLETE | Dashboard low-stock and `/api/alerts/low-stock` endpoints exist, with web/mobile surfaces. |
| 7 | Dead-stock report | COMPLETE | Dead-stock service, API, dashboard card, action center page, and CSV export exist. |
| 8 | Reorder suggestions | PARTIAL | Forecast/reorder services consider demand, stock, lead time, safety stock, MOQ, and pending orders. Needs deeper supplier ordering workflows and approval rules for production. |
| 9 | Profit-drop analysis | PARTIAL | Profit insight endpoint and AI assistant answer with tenant data/evidence. Still local/rule-based and should be expanded for richer accounting/cost variance scenarios. |
| 10 | Data quality cleanup | PARTIAL | Duplicate detection, missing fields, apply suggestion, and merge flows exist. No approval workflow or advanced fuzzy matching review UI yet. |
| 11 | Mobile stock in/out | PARTIAL | Expo app has login, dashboard, lookup, stock in/out, transfer, low-stock, reorder, upload, and settings screens. Camera barcode scanning is not fully production-ready; manual input fallback exists. |
| 12 | Audit logs | COMPLETE | Audit table, service, API, web viewer, and events for stock movements, imports, payments, reports, cleanup, and related actions exist. |
| 13 | Export reports | COMPLETE | `/api/reports/export/{report}` supports tenant-scoped CSV exports and audit logging. |
| 14 | Role-based access control | PARTIAL | Expanded roles, permission catalog, `/api/auth/me/permissions`, permission-aware web shell, and method permission checks exist. DB-backed custom roles/permissions and user role UI are not complete. |
| 15 | Admin/user/customer panels | PARTIAL | Business role panels and customer/dealer portal exist. User management, supplier portal, platform admin/support panels, and role assignment screens are not complete. |
| 16 | Tenant isolation | PARTIAL | Core repository/service queries are tenant-scoped and JWT tenant context is enforced. Needs broader automated coverage, branch/warehouse user scoping, and support access grant enforcement. |
| 17 | Tests | PARTIAL | Backend unit/integration tests exist for auth, stock ledger math, inventory flows, import adapters, forecast math, and permission mappings. Web has a basic Node test and lint/build. Mobile typecheck exists. Coverage is not yet comprehensive for all permission and import edge cases. |
| 18 | Docker setup | COMPLETE | `docker-compose.yml` includes PostgreSQL, Redis, RabbitMQ, MinIO, Adminer, and backend service. Backend Dockerfile exists. |
| 19 | README setup accuracy | PARTIAL | README documents product, stack, Docker, backend/web/mobile, imports, security, access control, and demo accounts. It should be revalidated on a clean machine with Docker/Java 21 installed before external release. |

## Review Notes

- The current MVP is strongest in inventory ledger, dashboard, import staging, reorder/dead-stock intelligence, web demo mode, and customer portal basics.
- The main production gaps are database-backed RBAC, user/admin management screens, approval workflows, supplier/platform portals, real OCR/LLM provider integrations, and deeper test coverage.
- Local review on this machine confirmed web lint, web tests, web build, and mobile typecheck. Backend Maven tests require Java 21 and Maven, which are not installed on the current laptop.
