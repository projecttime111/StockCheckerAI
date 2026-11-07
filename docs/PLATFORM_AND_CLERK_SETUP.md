# Platform administration and optional Clerk setup

The `/admin` panel manages client workspaces. The business panel manages the currently selected workspace's inventory and operations. Clerk is optional and **disabled by default**. Local email/password authentication remains supported. The examples contain no live Clerk configuration; enabling it requires a real Clerk instance and the smoke checks below.

## Access and administration

| Role | Allowed platform operations |
| --- | --- |
| `PLATFORM_SUPER_ADMIN` | Business metadata, usage, members, imports, audit and system metrics; suspend/reactivate; edit manual subscriptions and internal notes |
| `PLATFORM_SUPPORT` | Read business metadata, internal notes, members, import metadata, audit and system metrics |
| `PLATFORM_BILLING_ADMIN` | Read business metadata and usage counts; edit manual plan/subscription records. Internal notes, members, import monitor, audit and system pages are unavailable |
| Business `OWNER` / `ADMIN` | Their workspace permissions only; no platform access |

Platform access comes from `app_users.platform_role`, separate from workspace membership roles. A platform session has no active tenant. Switching to a business requires an ordinary membership in that business; a platform grant does not grant access to its inventory. A Clerk organization role is not a StockPilot platform grant.

To bootstrap a super administrator, first create and verify the intended local account. Add its exact email to the backend's comma-separated `APP_PLATFORM_ADMIN_EMAILS`, then sign in with its existing StockPilot credentials. Existing verified Google accounts also support this bootstrap on sign-in. Registration and first-time Clerk account creation do not grant platform roles. Remove the bootstrap allowlist when no longer needed; this does **not** revoke a previously persisted grant. Support/billing grants and revocations require trusted server/database administration; there is no public role-elevation endpoint.

Suspension and reactivation require a reason and are recorded atomically with the tenant status change. Suspended workspaces reject business access. Deleted workspaces cannot be restored through these APIs. The platform panel has no destructive workspace deletion action. Subscription metadata and platform audit history survive a business-data reset.

Plans are `FREE`, `STARTER`, `PRO`, or `ENTERPRISE`. Manual subscription statuses are `TRIAL`, `ACTIVE`, `PAST_DUE`, `CANCELLED`, or `EXPIRED`, with an optional end date. These records do not charge a card, create a payment-provider subscription, enforce quotas, or automatically suspend a business. Existing businesses without a profile display `FREE` / `TRIAL` until an administrator records different values.

## Environment configuration

Keep private values in the deployment provider's secret settings or ignored local environment files. The backend root examples are `.env.example` and `.env.neon.example`; web values belong in `web/.env.local` or the web deployment environment.

| Backend variable | Meaning |
| --- | --- |
| `APP_CLERK_ENABLED` | `false` by default; set `true` only after supplying the required instance settings |
| `CLERK_ISSUER` | Trusted HTTPS Clerk instance origin; must match the session JWT issuer |
| `CLERK_AUTHORIZED_PARTIES` | Comma-separated exact permitted web origins, including scheme and any port; no paths, wildcards, or trailing slash. HTTP is accepted only for localhost development |
| `CLERK_SECRET_KEY` | Private backend API key for that same instance; required when enabled to verify account details and send organization invitations |
| `CLERK_JWT_PUBLIC_KEY` | Optional RSA public key in PEM form. Leave blank to fetch signing keys from the configured issuer's JWKS endpoint; a pinned key requires manual rotation |
| `APP_PLATFORM_ADMIN_EMAILS` | Optional bootstrap allowlist for existing verified administrator accounts |
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

Public Next.js variables are embedded at build time: rebuild and redeploy after changing them. Render keeps `APP_CLERK_ENABLED=false` in `render.yaml`; add the private Clerk settings through Render before opting in. Docker Compose passes the optional backend variables through from the root environment, with the flag defaulting to false.

## Enable and migrate

1. Deploy the backend migrations in order: V26 adds identity links and global platform roles, V27 adds platform profiles/audit, and V28 adds workspace invitations. Existing tenant IDs, users and business records remain in place. Back up production before deployment; do not edit applied Flyway migrations.
2. Configure one Clerk instance with verified email sign-in and Organizations. **Allow personal accounts in the Clerk Dashboard**: the backend rejects sessions with `sts: pending`, so organization membership must not be required before the user can finish sign-in. After sign-in, the organization selector handles creating/selecting an organization before business onboarding. Supply matching backend/web keys and exact frontend origins. The backend must reach the configured issuer's JWKS endpoint and `api.clerk.com` unless a signing key is pinned; the backend API is still needed for verified profile lookup and invitations.
3. Enable both Clerk flags and deploy/rebuild the web app. The web session uses Clerk's current token; the backend verifies signature, issuer, expiry and allowed origin and resolves the signed active organization to an existing tenant mapping.
4. For a **new business**, sign in, create/select its Clerk organization, and complete workspace onboarding as the organization administrator. One Clerk organization maps to one StockPilot tenant. Additional organization members need a StockPilot membership or pending invitation; organization membership alone does not provide inventory access.
5. For an **existing StockPilot account**, open `/account/link` after Clerk sign-in and prove control of the existing StockPilot login as well. Both identities must have the same verified email. The API `POST /api/auth/clerk/link` receives the Clerk proof while authenticated with the existing local session. Matching email alone never silently merges accounts; `CLERK_ACCOUNT_LINK_REQUIRED` means this explicit link is needed.
6. For an **existing business**, its current StockPilot `OWNER` links the selected Clerk organization at `/account/link?organization=1`. The API is `POST /api/auth/clerk/organization/link`; it requires both the local owner session and a Clerk organization-administrator proof. An organization already mapped to another tenant is rejected. Choose this linking flow rather than creating a duplicate workspace. Link the organization before sending Clerk-managed team invitations.

For teammates who already have a StockPilot membership, invite their existing verified email into the linked organization through the Clerk Dashboard during migration. Account/workspace linking does not automatically enroll the rest of the team in Clerk, and the StockPilot Team invite form rejects accounts that already belong to that workspace. Each existing teammate links their own account, accepts the organization invitation, and keeps their existing StockPilot role. The same migration applies to existing dealer-portal members if Clerk is enabled for the web app.

Platform and business modes remain separate. The backend returns the current mode, role, access state and available memberships from `/api/auth/session`. Local sessions switch through `/api/auth/platform` or `/api/auth/workspaces/{id}/switch`. Clerk requests use `X-StockPilot-Mode` and the signed active organization; the client never grants itself a tenant or role by sending an ID.

## Invitations and email delivery

The business team page can invite supported business roles, update roles and revoke pending invitations. Invitations cannot assign `OWNER` or any platform role. Local invitations expire after seven days, are tied to the invited email, and are consumed on acceptance. Existing users sign in as that email; new users register through the invitation link.

For local workspaces, `EMAIL_PROVIDER=local` logs the link for development and **does not deliver an email**. Production delivery requires the existing Resend configuration (`EMAIL_PROVIDER=resend`, a real `RESEND_API_KEY`, and a verified `EMAIL_FROM` domain). Set `APP_PUBLIC_URL` to the deployed web origin so links open the correct app. Clerk-linked workspaces use Clerk organization invitations; acceptance still requires the matching pending StockPilot invitation before business access is granted. Existing organization members can receive a StockPilot access invitation without another organization membership. The Team page reports whether an email was sent, an existing invitation was reused, or the teammate should simply sign in and select the organization. After enabling Clerk, old local invitation links require the owner to connect the business organization and resend the invitation.

## Smoke checks

- With both Clerk flags false, verify local login, workspace selection, a read from inventory and a permitted write. An ordinary owner/admin must receive 403 for `/api/platform/businesses`.
- With real Clerk configuration enabled, verify sign-in, sign-out, refresh/expired-token recovery, account linking and organization selection. Try two separate organizations and confirm neither can read the other's business data. Test a member without an invitation: business access must remain unavailable.
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
