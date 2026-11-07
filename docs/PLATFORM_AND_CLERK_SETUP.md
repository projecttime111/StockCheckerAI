# Platform administration and optional Clerk setup

The `/admin` panel manages client workspaces. The business panel manages the currently selected workspace's inventory and operations. The current web rollout uses Clerk for new customers; set both Clerk flags after configuring the same real Clerk instance. Example files keep Clerk **disabled by default** so an unconfigured deployment still starts. Legacy local authentication remains available for compatibility, but account/workspace migration endpoints are disabled by default and migration controls are not part of the current customer interface. No existing users, links, tenant IDs or business data are deleted by this rollout.

## Access and administration

| Role | Allowed platform operations |
| --- | --- |
| `PLATFORM_SUPER_ADMIN` | Business metadata, usage, members, imports, audit and system metrics; suspend/reactivate; edit manual subscriptions and internal notes |
| `PLATFORM_SUPPORT` | Read business metadata, internal notes, members, import metadata, audit and system metrics |
| `PLATFORM_BILLING_ADMIN` | Read business metadata and usage counts; edit manual plan/subscription records. Internal notes, members, import monitor, audit and system pages are unavailable |
| Business `OWNER` / `ADMIN` | Their workspace permissions only; no platform access |

Platform access comes from `app_users.platform_role`, separate from workspace membership roles. A platform session has no active tenant. Switching to a business requires an ordinary membership in that business; a platform grant does not grant access to its inventory. A Clerk organization role is not a StockPilot platform grant.

To bootstrap a super administrator for the Clerk rollout, add only the intended administrator's exact email to the backend's comma-separated `APP_PLATFORM_ADMIN_EMAILS`, then sign in through Clerk with that verified primary email. A new Clerk account can receive this explicitly configured grant without a local StockPilot password or a business workspace. The backend verifies the current email and subject through Clerk's Backend API; Clerk organization roles and client-supplied emails never authorize platform access. An already-linked Clerk identity can also receive this grant after the same current-profile check. An unlinked legacy account with the same email is still rejected rather than silently merged; contact the operator for a controlled migration if needed.

Existing verified local/Google accounts retain their bootstrap-on-sign-in behavior; public local registration does not grant platform roles. Remove `APP_PLATFORM_ADMIN_EMAILS` after confirming `/admin` access. Removing it does **not** revoke a persisted grant; leaving it configured can restore a subsequently removed grant when the verified account authenticates again. Support/billing grants and revocations require trusted server/database administration; there is no public role-elevation endpoint.

Suspension and reactivation require a reason and are recorded atomically with the tenant status change. Suspended workspaces reject business access. Deleted workspaces cannot be restored through these APIs. The platform panel has no destructive workspace deletion action. Subscription metadata and platform audit history survive a business-data reset.

Plans are `FREE`, `STARTER`, `PRO`, or `ENTERPRISE`. Manual subscription statuses are `TRIAL`, `ACTIVE`, `PAST_DUE`, `CANCELLED`, or `EXPIRED`, with an optional end date. These records do not charge a card, create a payment-provider subscription, enforce quotas, or automatically suspend a business. Existing businesses without a profile display `FREE` / `TRIAL` until an administrator records different values.

## Environment configuration

Keep private values in the deployment provider's secret settings or ignored local environment files. The backend root examples are `.env.example` and `.env.neon.example`; web values belong in `web/.env.local` or the web deployment environment.

| Backend variable | Meaning |
| --- | --- |
| `APP_CLERK_ENABLED` | `false` by default; set `true` only after supplying the required instance settings |
| `APP_CLERK_LEGACY_MIGRATION_ENABLED` | `false` by default; keep false for the new-customer rollout. An explicit operator opt-in enables the legacy dual-proof account and organization linking APIs |
| `CLERK_ISSUER` | Trusted HTTPS Clerk instance origin; must match the session JWT issuer |
| `CLERK_AUTHORIZED_PARTIES` | Comma-separated exact permitted web origins, including scheme and any port; no paths, wildcards, or trailing slash. HTTP is accepted only for localhost development |
| `CLERK_SECRET_KEY` | Private backend API key for that same instance; required when enabled to verify account details and send organization invitations |
| `CLERK_JWT_PUBLIC_KEY` | Optional RSA public key in PEM form. Leave blank to fetch signing keys from the configured issuer's JWKS endpoint; a pinned key requires manual rotation |
| `APP_PLATFORM_ADMIN_EMAILS` | Optional exact-email bootstrap allowlist for current verified Clerk identities, or existing verified local/Google accounts; remove after bootstrap |
| `CORS_ALLOWED_ORIGINS` | Exact allowed web origins; include the frontend origin used in `CLERK_AUTHORIZED_PARTIES` |
| `APP_PUBLIC_URL` | Web origin used in account and invitation links |

| Web variable | Meaning |
| --- | --- |
| `NEXT_PUBLIC_CLERK_ENABLED` | `false` by default; set `true` together with the backend flag |
| `NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY` | Public key for the same Clerk instance |
| `NEXT_PUBLIC_CLERK_SIGN_IN_URL` | Set `/sign-in` so middleware redirects use this app's sign-in page |
| `NEXT_PUBLIC_CLERK_SIGN_UP_URL` | Set `/sign-up` so middleware redirects use this app's sign-up page |
| `CLERK_SECRET_KEY` | Server-only key used by Clerk middleware. Never use a `NEXT_PUBLIC_` prefix for this secret |
| `NEXT_PUBLIC_API_URL` | Backend origin, for example `http://localhost:8080`; do not append `/api` |
| `NEXT_PUBLIC_DEMO_MODE` | Keep `false` when checking real backend/Clerk behavior |

Public Next.js variables are embedded at build time: rebuild and redeploy after changing them. Render declares `APP_CLERK_ENABLED` with `sync: false` so Blueprint updates preserve the value already configured in the dashboard. For a new service, choose `true` only after supplying the private Clerk settings, otherwise choose `false`. Docker Compose passes the optional backend variables through from the root environment, with the flag defaulting to false.

## Enable the new-customer Clerk rollout

1. Deploy the backend migrations in order: V26 adds identity links and global platform roles, V27 adds platform profiles/audit, and V28 adds workspace invitations. Existing tenant IDs, users and business records remain in place. Back up production before deployment; do not edit applied Flyway migrations.
2. Configure one Clerk instance with verified email sign-in and Organizations. **Allow personal accounts in the Clerk Dashboard**: the backend rejects sessions with `sts: pending`, so organization membership must not be required before the user can finish sign-in. After sign-in, the organization selector handles creating/selecting an organization before business onboarding. Supply matching backend/web keys and exact frontend origins. The backend must reach the configured issuer's JWKS endpoint and `api.clerk.com` unless a signing key is pinned; the backend API is still needed for verified profile lookup and invitations.
3. Enable backend `APP_CLERK_ENABLED=true` first, then enable `NEXT_PUBLIC_CLERK_ENABLED=true` and rebuild/redeploy the web app. Keep `APP_CLERK_LEGACY_MIGRATION_ENABLED=false`. The web session uses Clerk's current token; the backend verifies signature, issuer, expiry and allowed origin and resolves the signed active organization to an existing tenant mapping. Configure the administrator email as described above; a local account is not a prerequisite.
4. For a **new business**, sign in, create/select its Clerk organization, and complete workspace onboarding as the organization administrator. One Clerk organization maps to one StockPilot tenant. Additional organization members need a StockPilot membership or pending invitation; organization membership alone does not provide inventory access.

## Legacy migration is paused

With the default migration flag off, `POST /api/auth/clerk/link` and `POST /api/auth/clerk/organization/link` return `403 CLERK_MIGRATION_DISABLED`. A verified Clerk identity whose email belongs to an unlinked legacy account receives `409 CLERK_ACCOUNT_EXISTS` with support guidance. It does not overwrite or adopt that account. Existing identity links and organization mappings continue working; this flag does not revoke access or alter existing records.

If an operator later explicitly enables `APP_CLERK_LEGACY_MIGRATION_ENABLED=true`, the retained account-link API still requires both an authenticated local account and a Clerk session proof with the same current verified email. The organization-link API additionally requires the local business `OWNER` and Clerk organization-administrator proof. One organization cannot map to two tenants. These are controlled migration APIs, not visible customer onboarding options in the current rollout; reintroducing the migration interface is separate work. Turn the flag off again after the controlled operation.

During a future controlled migration, existing StockPilot teammates must also join the linked organization through the Clerk Dashboard. Linking one owner's account does not enroll the rest of the team, and the StockPilot Team invite form rejects accounts that already belong to that workspace. Their existing StockPilot roles remain authoritative. The same consideration applies to existing dealer-portal members.

Platform and business modes remain separate. The backend returns the current mode, role, access state and available memberships from `/api/auth/session`. Local sessions switch through `/api/auth/platform` or `/api/auth/workspaces/{id}/switch`. Clerk requests use `X-StockPilot-Mode` and the signed active organization; the client never grants itself a tenant or role by sending an ID.

## Invitations and email delivery

The business team page can invite supported business roles, update roles and revoke pending invitations. Invitations cannot assign `OWNER` or any platform role. Local invitations expire after seven days, are tied to the invited email, and are consumed on acceptance. Existing users sign in as that email; new users register through the invitation link.

For local workspaces, `EMAIL_PROVIDER=local` logs the link for development and **does not deliver an email**. Production delivery requires the existing Resend configuration (`EMAIL_PROVIDER=resend`, a real `RESEND_API_KEY`, and a verified `EMAIL_FROM` domain). Set `APP_PUBLIC_URL` to the deployed web origin so links open the correct app. Clerk-linked workspaces use Clerk organization invitations; acceptance still requires the matching pending StockPilot invitation before business access is granted. Existing organization members can receive a StockPilot access invitation without another organization membership. The Team page reports whether an email was sent, an existing invitation was reused, or the teammate should simply sign in and select the organization. Old local invitation links are not converted automatically; while migration is paused, direct those users to the operator for help.

## Session checks after inactivity

The backend returns JSON authentication/provider errors; it does not redirect browsers back to sign-in. An established linked account normally resolves from the database without a Clerk profile request. A new signup, a configured administrator awaiting bootstrap, or a pending team-invitation claim needs a fresh verified profile. Signing keys are cached for five minutes, so the first request after a longer idle period may also refresh the JWKS cache.

Each Clerk HTTP call has a five-second request timeout and a six-second completion bound. A database pool acquisition can take up to the configured `DATABASE_CONNECTION_TIMEOUT_MS` (30 seconds by default). A hosting service that sleeps may add startup delay before the application receives the request. These delays should produce a visible retry/error state in the web app, not an unlimited loading screen. If the problem persists, inspect the `/api/auth/session` status: `401` indicates an invalid/expired session or mismatched issuer/origin, `409 CLERK_ACCOUNT_EXISTS` indicates a legacy email conflict, and `503 IDENTITY_PROVIDER_UNAVAILABLE` indicates a Clerk network/API failure. Verify `APP_CLERK_ENABLED`, exact issuer, authorized web origin, same-instance keys and backend availability; do not disable authentication or tenant checks as a workaround.

## Smoke checks

- With both Clerk flags false, verify local login, workspace selection, a read from inventory and a permitted write. An ordinary owner/admin must receive 403 for `/api/platform/businesses`.
- With real Clerk configuration enabled, verify sign-in, sign-out, refresh/expired-token recovery and new-business organization selection. Try two separate organizations and confirm neither can read the other's business data. Test a member without an invitation: business access must remain unavailable. Confirm both migration endpoints reject requests with the migration flag off and an existing unlinked email is not silently adopted.
- Verify an exact allowlisted, verified Clerk administrator can access `/admin` without creating a business or using a local password. Remove the bootstrap allowlist afterward and confirm ordinary users have no global platform access.
- In `/admin`, search/page through businesses; open member, usage and import metadata; suspend one test workspace, confirm its business access stops, reactivate it and inspect both audit entries. Confirm support cannot edit and billing can edit only subscriptions.
- Invite a real test email and verify delivery and acceptance. Confirm the assigned role, revoke a pending invitation, and check that its link cannot grant access.
- Inspect `/api/platform/system`: database connectivity and import aggregates come from actual queries. Failure recency uses the last update time of currently failed imports; these are not historical incident counts. No Redis, queue, billing-provider or Clerk availability is invented.

Automated platform API tests cover these role boundaries, validation, filters, privacy, status transitions and concurrent suspension. `PlatformAdminPostgresIntegrationTest` repeats them against the guarded disposable audit database when explicitly enabled; it is not a live Clerk acceptance test.

## Disable Clerk / rollback authentication

Set backend `APP_CLERK_ENABLED=false` and web `NEXT_PUBLIC_CLERK_ENABLED=false`, restart the backend, and rebuild/redeploy the web app. Use the existing local login flow. Leave V26–V28 and their data in place; disabling authentication integration does not require dropping tables or unlinking identities.

Linked existing accounts retain their local credentials. A Clerk-created account starts with an unknown random local password, so it needs a valid local password-reset email before local login is possible. Verify that recovery path and production email delivery before relying on it. Disabling Clerk does not cancel existing Clerk organization invitations or remove persisted platform grants.

## Dependency security status — 7 November 2026

The web app pins Next.js and `eslint-config-next` to **16.4.5** and `@sentry/nextjs` to **10.78.0**, with compatible transitive security updates in the lockfile. Clerk remains **7.9.25**. These updates include the [Next.js security release](https://github.com/vercel/next.js/releases/tag/v16.4.5) and the fix for Sentry's [tunnel-route middleware bypass](https://github.com/getsentry/sentry-javascript/security/advisories/GHSA-mchm-gjx6-7h55).

`npm audit --omit=dev` reports **0 critical, 0 high and 5 moderate package findings**. The five findings propagate two advisories through Sentry's build-tool dependencies: [glob cancellation can continue filesystem traversal](https://github.com/isaacs/node-glob/security/advisories/GHSA-3g3m-2fjr-6pw4), and [minimatch can amplify attacker-controlled patterns](https://github.com/isaacs/minimatch/security/advisories/GHSA-g3hv-75wg-m249). In this repository they are used by Sentry's build plugin; application routes do not accept glob patterns. Their published fixes require newer dependency majors, and npm recommends Sentry 11; no forced major upgrade or override was applied. Treat build inputs and source-map patterns as trusted configuration and reassess when a compatible upstream fix is available.

The full developer dependency audit is **not clean**: it separately reports 23 package findings, including 2 critical findings in the Vitest/tinypool tooling chain. The current `npm test` command uses Node's built-in test runner, not Vitest or a Vitest UI server. The unused Vitest configuration remains outside this feature's changes; developer-tool vulnerabilities need a separate compatible upgrade or removal review. Production-only audit counts must not be presented as the result of the full dependency audit.
