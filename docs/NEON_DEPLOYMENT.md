# Production Deployment: GitHub → Render → Vercel → Neon

This is the clean deployment path for the current repository:

```text
Browser on Vercel → HTTPS Spring Boot API on Render → existing Neon PostgreSQL
```

The browser must never receive Neon credentials. Keep all database, JWT, and email
secrets in Render only.

## 1. Keep the existing Neon database

You do not need to create or reset the database. Reuse the same Neon project,
branch, database, role, password, and URLs.

For the backend runtime, prefer Neon's pooled hostname containing `-pooler`. For
Flyway migrations, prefer the direct hostname without `-pooler`.

Spring expects JDBC URLs. If the value already starts with `jdbc:postgresql://`,
keep it unchanged. If Neon gives you this:

```text
postgresql://role:password@ep-example-pooler.region.aws.neon.tech/neondb?sslmode=require
```

configure it in Render as:

```text
DATABASE_URL=jdbc:postgresql://ep-example-pooler.region.aws.neon.tech/neondb?sslmode=require
DATABASE_USERNAME=role
DATABASE_PASSWORD=<the existing password>
```

Keep the username and password separate from the JDBC URL. Do not commit real
values. Neon's connection modal and pooling guidance are at
<https://neon.com/docs/connect/connection-pooling>.

Flyway is append-only and runs automatically when the backend starts. Keeping the
same Neon database preserves the current schema and data; it does not create a new
database.

## 1A. Create a new Neon database instead

Choose **PostgreSQL 16** when creating a new Neon project. It is the safest match
for the backend's current Spring Boot 3.3.5, Flyway 10.10, and PostgreSQL JDBC
42.7.4 dependency stack. The existing migrations use standard PostgreSQL features
and need no code changes for PostgreSQL 16.

| Neon PostgreSQL version | Project status |
| --- | --- |
| 14 or 15 | Compatible, but unnecessarily old for a new project |
| **16** | **Recommended; no dependency or migration changes required** |
| 17 | Schema-compatible, but upgrade Flyway and the JDBC driver and test first |
| 18 | Do not use with the current dependency stack; upgrade and stage-test first |

Neon manages the minor release. Select major version 16 in the Neon project
creation screen; do not attempt to pin a minor version in application config.

Create the project in a region close to the Render service, then copy both
connection strings from Neon:

- **Pooled connection**: hostname contains `-pooler`; use it for `DATABASE_URL`.
- **Direct connection**: hostname does not contain `-pooler`; use it for
  `FLYWAY_URL`, `pg_dump`, and `pg_restore`.

Convert Neon's `postgresql://` strings to `jdbc:postgresql://`, remove credentials
from the URL, and set the role and password separately:

```text
DATABASE_URL=jdbc:postgresql://<new-pooled-host>/<database>?sslmode=require&channelBinding=require
DATABASE_USERNAME=<new-role>
DATABASE_PASSWORD=<new-password>
FLYWAY_URL=jdbc:postgresql://<new-direct-host>/<database>?sslmode=require&channelBinding=require
FLYWAY_USERNAME=<new-role>
FLYWAY_PASSWORD=<new-password>
```

These are the only Render variables that must change when switching Neon
projects. `FLYWAY_USERNAME` and `FLYWAY_PASSWORD` can be omitted when they match
`DATABASE_USERNAME` and `DATABASE_PASSWORD`. Do not change the Vercel variables,
Render URL, CORS values, JWT secret, or email settings just because the database
changed.

### Start with an empty database

Leave the new database empty, update the six Render values above, and redeploy the
backend. Flyway will create the schema by applying migrations V1 through V17.
Keep `APP_DEMO_SEED_ENABLED=false` in production.

### Copy the existing Neon data

Do not point Render to the destination until the copy finishes. Use Neon's Import
Data Assistant for a small database, or use direct (not pooled) URLs:

```bash
pg_dump -Fc -v -d "$SOURCE_NEON_DIRECT_URL" -f stockpilot.dump
pg_restore -v --no-owner --no-privileges -d "$DESTINATION_NEON_DIRECT_URL" stockpilot.dump
```

After the restore, change the six Render values and redeploy. Flyway will validate
the restored `flyway_schema_history` table and apply only any later migrations.
For a large or near-zero-downtime move, follow Neon's logical replication
procedure instead of this short maintenance-window flow:
<https://neon.com/docs/import/migrate-from-neon>.

### Verify and roll back

After deployment, check `/actuator/health`, log in, and verify that the latest
migration is successful:

```sql
select version();
select installed_rank, version, description, success
from flyway_schema_history
order by installed_rank;
```

The current repository should show successful migrations through V17. If the
switch fails, restore the previous Neon connection variables in Render and
redeploy. Do not edit or renumber an existing Flyway migration.

## 2. Point the local clone at the new GitHub repository

The current clone may still reference the old remote. Check it:

```bash
git remote -v
```

After replacing the placeholders with the new repository URL:

```bash
git remote set-url origin https://github.com/<owner>/<new-repository>.git
git push -u origin main
```

Do not run `git init` again and do not commit `.env` or `web/.env.local`.

## 3. Configure the Render backend

For an existing Render web service, connect the new GitHub repository and use:

| Render setting | Value |
| --- | --- |
| Branch | `main` |
| Language/runtime | `Docker` |
| Root Directory | `backend` |
| Dockerfile Path | `./Dockerfile` |
| Docker Build Context | `.` |
| Health Check Path | `/actuator/health` |
| Auto-Deploy | Enabled |

Do not add a custom build or start command. The Dockerfile builds with Java 21 and
starts the executable Spring Boot JAR. Render supplies `PORT`; the backend already
uses it.

The root [`render.yaml`](../render.yaml) is an optional Blueprint for a new service.
It declares Render's paid `starter` plan and a 1 GB persistent disk mounted at
`/var/data`. Uploads use `/var/data/uploads`. Applying this Blueprint creates paid
resources; editing the file alone does not deploy or change an existing service.

### Required Render variables

Set these in **Render → Service → Environment**:

| Variable | What to enter |
| --- | --- |
| `DATABASE_URL` | Existing Neon pooled JDBC URL |
| `DATABASE_USERNAME` | Existing Neon role |
| `DATABASE_PASSWORD` | Existing Neon password |
| `FLYWAY_URL` | Existing Neon direct JDBC URL |
| `JWT_SECRET` | A new private random value, at least 64 characters |
| `CORS_ALLOWED_ORIGINS` | Exact Vercel origin, such as `https://your-project.vercel.app` |
| `APP_PUBLIC_URL` | The same exact Vercel origin |
| `EMAIL_FROM` | Verified sender, such as `StockPilot AI <noreply@yourdomain.com>` |
| `RESEND_API_KEY` | Private Resend key |

No value above belongs in Vercel.

Generate a JWT secret on macOS/Linux:

```bash
openssl rand -base64 64
```

Generate one in Windows PowerShell:

```powershell
$bytes = New-Object byte[] 64
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
[Convert]::ToBase64String($bytes)
$rng.Dispose()
```

Changing `JWT_SECRET` signs out existing browser sessions but does not change Neon
data. Use a new value for the new deployment.

### Fixed Render variables

Add these exactly:

```text
SPRING_PROFILES_ACTIVE=prod
EMAIL_PROVIDER=resend
CACHE_TYPE=simple
MANAGEMENT_HEALTH_REDIS_ENABLED=false
MANAGEMENT_HEALTH_RABBIT_ENABLED=false
APP_AUTH_REQUIRE_EMAIL_VERIFICATION=true
APP_DEMO_SEED_ENABLED=false
APP_DEV_TOOLS_ENABLED=false
SWAGGER_PUBLIC_ENABLED=false
LOCAL_STORAGE_ROOT=/var/data/uploads
```

Do not add `PORT`; Render creates it. Do not add Redis, RabbitMQ, or MinIO variables
for this deployment. The current production path uses the in-process cache and
local upload implementation.

### Optional Render variables

Add only when needed:

| Variable | Purpose/default |
| --- | --- |
| `FLYWAY_USERNAME` | Only if the migration role differs; otherwise `DATABASE_USERNAME` |
| `FLYWAY_PASSWORD` | Only if the migration password differs; otherwise `DATABASE_PASSWORD` |
| `DATABASE_POOL_MAX_SIZE` | Default `5` |
| `DATABASE_POOL_MIN_IDLE` | Default `0` |
| `DATABASE_CONNECTION_TIMEOUT_MS` | Default `30000` |
| `DATABASE_VALIDATION_TIMEOUT_MS` | Default `5000` |
| `JWT_ACCESS_TOKEN_MINUTES` | Default `120` |
| `APP_AUTH_VERIFICATION_TOKEN_HOURS` | Default `24` |
| `APP_AUTH_PASSWORD_RESET_TOKEN_MINUTES` | Default `30` |
| `APP_AUTH_EMAIL_COOLDOWN_SECONDS` | Default `60` |
| `GOOGLE_CLIENT_ID` | Optional Google OAuth Web client ID |
| `UPLOAD_MAX_FILE_SIZE` | Default `150MB` |
| `UPLOAD_MAX_REQUEST_SIZE` | Default `160MB` |
| `IMPORT_MAX_UPLOAD_SIZE` | Defaults to upload max size |
| `APP_AI_ENABLED` | Defaults to `false`; use the local analytics assistant until a hosted provider is configured |
| `APP_AI_PROVIDER` | Hosted adapter currently supports `gemini` |
| `APP_AI_MODEL` | Provider model ID; required only when hosted AI is enabled |
| `GEMINI_API_KEY` | Private backend key; required only when hosted AI is enabled |
| `APP_IMPORT_JOBS_ENABLED` | Defaults to `true`; enables the database-backed import worker |
| `LOG_LEVEL_ROOT` | Default `INFO`; keep this level in production |
| `LOG_LEVEL_APP` | Default `INFO`; temporarily use `DEBUG` only while investigating |
| `SENTRY_ENABLED` | Default `false`; set `true` only after adding a backend Sentry DSN |
| `SENTRY_DSN` | Optional backend Sentry project DSN |
| `SENTRY_ENVIRONMENT` | Default `local`; use `production` on Render |
| `SENTRY_RELEASE` | Optional deployed commit SHA or release label |
| `SENTRY_TRACES_SAMPLE_RATE` | Default `0.0`; error reporting works without tracing |

The copyable backend checklist is [`.env.neon.example`](../.env.neon.example).
For log locations, request-ID debugging, and optional Sentry setup, see
[Production Logging and Error Monitoring](PRODUCTION_LOGGING.md).

### Email not ready yet

The secure production setting is email verification plus Resend. If the sender
domain is not ready, the backend can be brought up temporarily with:

```text
APP_AUTH_REQUIRE_EMAIL_VERIFICATION=false
EMAIL_PROVIDER=local
EMAIL_FROM=StockPilot AI <noreply@example.com>
```

With `EMAIL_PROVIDER=local`, `RESEND_API_KEY` is not required, but verification and
password-reset emails are not delivered. Restore Resend before accepting real
users.

## 4. Configure the Vercel frontend

Import the same new GitHub repository into Vercel and use:

| Vercel setting | Value |
| --- | --- |
| Framework Preset | Next.js |
| Root Directory | `web` |
| Install Command | `npm ci` or default |
| Build Command | `npm run build` |
| Node.js | `22.x` (also pinned in `web/package.json`) |

Add only these browser-visible variables:

```text
NEXT_PUBLIC_API_URL=https://your-render-service.onrender.com
NEXT_PUBLIC_DEMO_MODE=false
NEXT_PUBLIC_GOOGLE_CLIENT_ID=
```

`NEXT_PUBLIC_GOOGLE_CLIENT_ID` is optional. If Google sign-in is enabled, use the
same public Web client ID in Render's `GOOGLE_CLIENT_ID`.

Do not add a trailing slash to `NEXT_PUBLIC_API_URL`. Never add `DATABASE_URL`,
database credentials, `JWT_SECRET`, or `RESEND_API_KEY` to Vercel.

Sentry is optional and disabled when its DSNs are blank. If it is enabled, use the
frontend variables and private source-map upload values documented in
[Production Logging and Error Monitoring](PRODUCTION_LOGGING.md). Never expose
`SENTRY_AUTH_TOKEN` as a `NEXT_PUBLIC_` variable.

Add the variables to **Production** (and Preview only if preview deployments must
call the production API), then redeploy. Vercel applies changed environment
variables only to new deployments. The production build now fails clearly if
`NEXT_PUBLIC_API_URL` is absent, and a Vercel build fails if it is not HTTPS.

After Vercel assigns the final production domain, return to Render and make both
of these match that exact origin:

```text
CORS_ALLOWED_ORIGINS=https://your-project.vercel.app
APP_PUBLIC_URL=https://your-project.vercel.app
```

Then redeploy Render. CORS contains the browser's Vercel origin, not the Render URL
and not the Neon URL.

## 5. Optional Google sign-in

Use the same Google OAuth Web client ID in both deployments:

```text
# Render
GOOGLE_CLIENT_ID=<public OAuth Web client ID>

# Vercel
NEXT_PUBLIC_GOOGLE_CLIENT_ID=<same public OAuth Web client ID>
```

In Google Cloud Console, add the exact Vercel production origin to Authorized
JavaScript origins. This flow does not use a Google client secret.

## 6. Local development after cleanup

Use Java 21 for the backend and Node 22 for the web app.

Check the active runtimes first:

```bash
java -version
node --version
```

On this macOS workstation, the default `java` is older than the project target,
but a suitable Homebrew JDK is already installed. Select it for the terminal:

```bash
export JAVA_HOME="$(brew --prefix openjdk)/libexec/openjdk.jdk/Contents/Home"
export PATH="$(brew --prefix openjdk)/bin:$PATH"
```

Backend:

```bash
cp .env.example .env
./backend/scripts/run-with-root-env.sh
```

Web, in another terminal:

```bash
cp web/.env.example web/.env.local
cd web
npm ci
npm run dev
```

The local URLs are:

```text
Web:     http://localhost:3000
Backend: http://localhost:8080
Health:  http://localhost:8080/actuator/health
```

The real `.env` and `web/.env.local` remain ignored. The checked-in example files
contain placeholders only.

## 7. Upload persistence

Neon persists database records. The supplied Blueprint stores original import
files under `/var/data/uploads` on its persistent disk. Existing services must
also have the disk mounted at `/var/data` and `LOCAL_STORAGE_ROOT` set to that
uploads directory; changing an environment variable alone does not create a disk.

Before switching an existing service, copy any files still on its old filesystem
to the durable upload directory. Do not discard them: staged imports retain their
storage keys and need the original files for remapping and review.

After deployment, upload a small test import, restart the service, and verify the
same staged file can still be read/remapped. Repeat after a redeploy. This checks
retention on the actual host; local tests cannot establish hosted durability.
Keep backups of both database records and uploaded files. A future S3-compatible
storage adapter can replace the disk when multiple application instances are needed.

## 8. Deployment order and smoke test

### Invoice identity migration preflight

V24 scopes purchase invoice numbers by supplier and April-March financial year,
and sales numbers by financial year. Both indexes ignore case and surrounding
spaces. Before upgrading a populated database, run these read-only checks:

```sql
select tenant_id, supplier_id,
       extract(year from invoice_date)::int -
         case when extract(month from invoice_date) < 4 then 1 else 0 end as fiscal_year,
       lower(btrim(invoice_number)) as normalized_number, count(*)
from purchase_invoices
group by 1, 2, 3, 4
having count(*) > 1;

select tenant_id,
       extract(year from invoice_date)::int -
         case when extract(month from invoice_date) < 4 then 1 else 0 end as fiscal_year,
       lower(btrim(invoice_number)) as normalized_number, count(*)
from sales_invoices
group by 1, 2, 3
having count(*) > 1;
```

If either query returns rows, review the original documents and resolve their
identities with the data owner before migration. Do not delete or renumber
financial records automatically. Back up the database first. V24 increases unit
cost precision; it cannot recover precision already lost in historical values.
V25 stores original classic-import rows for future remapping; previously discarded
rows require re-upload from the original file.

### Deploy and verify

1. Push the cleaned repository to the new GitHub remote.
2. Configure Render with the backend variables and deploy.
3. Confirm `https://<render-domain>/actuator/health` returns `"status":"UP"`.
4. Configure Vercel with the Render URL and deploy from the `web` root.
5. Put the final Vercel origin into Render's `CORS_ALLOWED_ORIGINS` and
   `APP_PUBLIC_URL`, then redeploy Render.
6. Open the Vercel site in a private browser window.
7. Confirm browser network requests go to Render, never `localhost` or Neon.
8. Register, verify the email, log in, create a product, and refresh.
9. Confirm the product remains after refresh and a Render restart.
10. Check Render logs for Flyway completion and Neon connection errors. For an API
    failure, copy `X-Request-Id` from the browser response and search that value in
    the Render log explorer.

If the Render health check fails, inspect the first startup exception. The most
common causes are a non-JDBC database URL, wrong Neon role/password, missing
`JWT_SECRET`, missing email settings, or a Flyway migration error. Do not delete or
reset the Neon database to fix a configuration error.

Official platform references:

- Render Docker services: <https://render.com/docs/docker>
- Render monorepo root directories: <https://render.com/docs/monorepo-support>
- Render health checks: <https://render.com/docs/health-checks>
- Render free-instance limits: <https://render.com/docs/free>
- Vercel monorepos: <https://vercel.com/docs/monorepos>
- Vercel environment variables: <https://vercel.com/docs/environment-variables>
