# Production Logging and Error Monitoring

Phase 1 adds request correlation, safe backend exception logging, production JSON
logs, and optional Sentry error reporting. It does not change API response bodies,
authentication, database behavior, or normal frontend routes.

## What is enabled by default

- The backend writes logs to standard output only. Render collects that output
  automatically.
- Every backend response includes `X-Request-Id`. The same ID is present in all
  application logs produced while handling that request.
- On Render, the backend adopts Render's `Rndr-Id` as its request ID when it is
  available.
- Unexpected backend exceptions log their full server-side stack trace. The client
  still receives the existing safe `Something went wrong` response.
- The `prod` Spring profile emits JSON console logs. Local profiles keep a readable
  text format.
- Health checks and CORS preflight requests are excluded from application request
  logs to avoid unnecessary volume.
- The new request and exception logs do not record request bodies, authorization
  headers, passwords, tokens, database URLs, or email contents.
- Sentry is disabled until its DSN and enable flag are explicitly configured.
  Performance tracing defaults to `0`, and default PII collection is disabled.

`EMAIL_PROVIDER=local` is an existing development-only exception: it intentionally
prints verification and password-reset links to the local backend terminal. Keep
the production provider set to `resend`.

## Where to see logs

| Environment | Location | What appears there |
| --- | --- | --- |
| Local backend | The terminal running `./backend/scripts/run-with-root-env.sh` or Maven | Request summaries, startup messages, and full backend stack traces |
| Local frontend | Browser DevTools Console and the terminal running `npm run dev` | Browser errors and Next.js development/build messages |
| Render backend | Render Dashboard → backend service → **Logs** | JSON application logs, request IDs, backend errors, and stack traces |
| Render deploy | Render Dashboard → backend service → **Deploys** → select a deploy | Docker build and startup logs for that deploy |
| Vercel frontend | Vercel Dashboard → project → **Logs** | Next.js server/function and middleware runtime logs |
| Vercel build | Vercel Dashboard → project → **Deployments** → select deployment → build logs | Install, TypeScript, and Next.js build failures |
| Sentry | Sentry → the selected project → **Issues** | Grouped frontend or backend exceptions after Sentry is enabled |

The Vercel site is mostly statically rendered, so Vercel runtime logs do not contain
every browser-side exception. Browser DevTools is useful while reproducing locally;
Sentry is the production location for browser errors.

Render keeps application logs on all plans. Its separate platform-generated HTTP
request logs require a Pro workspace, but the application request summaries added
in Phase 1 are still visible on Hobby.

## Diagnose `Something went wrong`

1. Open Browser DevTools → **Network** before reproducing the problem.
2. Select the failed API request and copy the `X-Request-Id` response header.
3. Open Render Dashboard → backend service → **Logs**.
4. Search for that request ID. If no ID was copied, search for
   `Unhandled exception`, the API path such as `/api/auth/register`, and the
   approximate time.
5. Read the first `Caused by` section in the stack trace. It normally contains the
   actual database, email provider, validation, or configuration failure.

The request ID is safe to share in a bug report. Do not share authorization tokens,
cookies, passwords, database credentials, or complete environment-variable
screenshots.

## Optional: enable Sentry

Create two Sentry projects so frontend and backend issues stay easy to filter:

- a Java / Spring Boot project for Render;
- a Next.js project for Vercel.

### Render variables

Set these in Render → backend service → **Environment**:

```text
SENTRY_ENABLED=true
SENTRY_DSN=<Spring Boot project DSN>
SENTRY_ENVIRONMENT=production
SENTRY_TRACES_SAMPLE_RATE=0.0
LOG_LEVEL_ROOT=INFO
LOG_LEVEL_APP=INFO
```

`SENTRY_RELEASE` is optional. It can be the deployed Git commit SHA or a release
label such as `stockchecker-backend@2026.07.25`.

### Vercel variables

Set these for the Vercel **Production** environment:

```text
NEXT_PUBLIC_SENTRY_DSN=<Next.js project DSN>
NEXT_PUBLIC_SENTRY_ENVIRONMENT=production
NEXT_PUBLIC_SENTRY_TRACES_SAMPLE_RATE=0

SENTRY_DSN=<same Next.js project DSN>
SENTRY_ENVIRONMENT=production
SENTRY_TRACES_SAMPLE_RATE=0
```

The DSN identifies where events are sent and is expected to be present in browser
code. Never prefix the Sentry auth token with `NEXT_PUBLIC_`.

Readable source-mapped browser stack traces additionally require:

```text
SENTRY_AUTH_TOKEN=<private Sentry token>
SENTRY_ORG=<organization slug>
SENTRY_PROJECT=<Next.js project slug>
```

The build skips source-map upload when any of those three values is absent. Error
capture still works, but minified production stack traces are less useful.

After changing environment variables, redeploy the Render service and Vercel
production deployment. Existing deployments do not receive newly added variables.

## Recommended production workflow

1. Deploy this phase with Sentry disabled.
2. Confirm Render startup and application logs are healthy.
3. Exercise registration, Google sign-in, email verification, login, and one normal
   business workflow.
4. Enable the backend Sentry project and redeploy Render.
5. Enable the frontend Sentry project and redeploy Vercel.
6. Confirm normal workflows again and check that no new issue spike appears.
7. Keep log levels at `INFO`. Temporarily use `LOG_LEVEL_APP=DEBUG` only during a
   controlled investigation, then restore it to `INFO`.

Official references:

- [Render log explorer](https://render.com/docs/logging)
- [Vercel runtime logs](https://vercel.com/docs/logs/runtime)
- [Sentry Spring Boot](https://docs.sentry.io/platforms/java/guides/spring-boot/)
- [Sentry Next.js](https://docs.sentry.io/platforms/javascript/guides/nextjs/)
