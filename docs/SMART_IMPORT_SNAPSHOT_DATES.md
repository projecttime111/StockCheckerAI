# Smart Import Snapshot Dates

Closing stock and outstanding balances are dated checkpoints, not balances on the day an import is processed.

## Operator Workflow

1. Open the existing session's Dry Run tab. Re-uploading or rebuilding is not required.
2. Enter the closing-stock as-of date for rows missing a source date and confirm it.
3. Independently enter and confirm the outstanding as-of date for undated customer/supplier balances.
4. Run a new dry run. Review the saved dates, exclusions, negative balances, and reconciliation totals.
5. Final Commit uses the saved preview's dates, never the machine's current date. Changing a date clears its confirmation and requires another dry run.

For reports confirmed to represent balances at 31 August 2026, use `2026-08-31`. Do not use the upload date. Source-provided row dates are preserved; these fields only fill missing dates. Split reports into separate sessions when undated files represent different as-of dates.

## API Contract

`POST /api/import-sessions/{id}/dry-run` accepts these additional fields:

```json
{
  "snapshotDate": "2026-08-31",
  "snapshotDateConfirmed": true,
  "outstandingSnapshotDate": "2026-08-31",
  "outstandingSnapshotDateConfirmed": true
}
```

Existing strategy and stock-policy fields remain unchanged. A date and its confirmation must be supplied together. Missing source dates cause HTTP 400 before replacing a previous preview. Dates are optional when every relevant source row is dated.

The saved dry-run summary contains both dates, confirmations, and `snapshotDatePolicyVersion: 1`. The dates are included in the 64-character input fingerprint and the dry-run audit entry. Commit accepts only a saved dry-run ID, not new dates. It rejects legacy previews without the policy version, missing confirmations, and stale fingerprints. The transactional executor revalidates the dates before writing business records.

No schema migration is needed. Deploy backend and web together; previews created before this change need a fresh dry run. Source files and review decisions are not rewritten.
