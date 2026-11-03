# Isolated Smart Import Commit Replay

`SmartImportCommitReplayTest` is disabled during normal test runs. It exercises a
failed import's saved staging data, dates, policies, final commit, and idempotent
retry against a disposable PostgreSQL database. It does not re-upload files or
rebuild staging/review decisions.

A copy with an already-committed import instead exercises cashbook recheck,
verifying that posted payments and retail sale contents do not change.

## Safety Requirements

- Take a custom-format `pg_dump` of the local source database. This reads the
  source database; never restore the dump into the source database.
- Use a separate PostgreSQL cluster bound only to `127.0.0.1`, port `55433`.
- Initialize the disposable cluster with database user `replay`; create database
  `stockpilot_commit_replay` and restore using `--no-owner --no-acl`.
- Keep dumps, cluster files, and logs under `.codex-logs/` (git-ignored). They
  contain private business data and must not be uploaded or checked in.
- The test verifies the destination database name, server port, and database
  user before making any business writes. Its Hibernate mode is `validate`,
  not `create-drop`.
- Apply new schema migrations to the disposable copy before starting the test.
  The test disables Flyway and will reject an out-of-date schema.

## Run

From `backend`, with Java 21 configured and the disposable cluster running:

```powershell
mvn -Dtest=SmartImportCommitReplayTest `
  -Dstockpilot.commit-replay=true `
  -Dstockpilot.replay.commit-id=<failed-commit-uuid> test
```

The test regenerates a dry run using the saved confirmed snapshot dates and
policies, then commits only in the isolated copy. It prints
`ISOLATED_PREVIEW_SUMMARY`, `ISOLATED_REPLAY_SECONDS`, and
`ISOLATED_REPLAY_RESULT`. A successful replay must report `COMMITTED`; repeating
the commit must leave product, invoice, payment, outstanding, and stock movement
counts unchanged.

Payment creation and invoice-link counts must equal the dry-run counts. The test
also prints `ISOLATED_CASHBOOK_RECHECK` and verifies that repeating recheck changes
no business record counts or payment/retail contents.

Stop the disposable cluster after verification. Keep the application's normal
database/server running separately. A passing replay is not a live import:
the owner still needs to review a fresh dry run and confirm the real commit.

## Product Identity Regressions

- Different stock-item names sharing a real stock-master ID retain their
  source name/unit lookup keys during final commit.
- A voucher GUID belongs to the voucher, not to every product on its lines.
  Legacy staged voucher products are handled without rebuilding the workspace.
- No fuzzy name merging or automatic unit conversion is introduced.
