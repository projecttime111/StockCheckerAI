# Cashbook Matching and Recovery

## Cause

Older matching treated a payment/receipt voucher's own number as an invoice
reference. Staging could therefore call an unrelated invoice a match while
discarding the identity of a matched staged invoice. Preview trusted that label;
commit used the source ledger name and reference instead, skipping payments or
leaving them unallocated.

## Corrected Behavior

- Payment references remain unchanged for audit and duplicate detection. A
  separate source invoice reference is used for matching. Legacy own-voucher
  fallback references are not treated as invoice evidence.
- A verified tenant customer/supplier is required before invoice matching.
  Cash and bank ledger names alone are not customer/supplier identities.
- Reference, party, date and amount must agree; ambiguous matches require review.
  A unique exact-party/exact-amount match within seven days can retain a staged
  invoice identity when there is no explicit invoice reference.
- Excluded or unresolved staged vouchers are not automatic invoice targets.
- Matched party name and staged voucher UUID survive until final commit. Commit
  resolves that UUID through its saved invoice mapping, not a number-only lookup.
- A new dry run rechecks unresolved legacy matches without rebuilding files or
  changing saved retail, ignored or posted-payment decisions. Old previews must
  be regenerated before commit.
- Final payment and invoice-link counts must equal the preview. A mismatch rolls
  back the transaction with an actionable error instead of silently dropping rows.
- Unmatched cashbook rows remain explicitly deferred warnings. Other ready import
  records may still commit; zero blocking errors does not mean every payment posts.

## An Already-Committed Import

Open Review Queue, then Unmatched Cashbook Resolution, and select **Recheck
unposted entries**. This does not replay the import or change posted payments,
retail totals, invoices, or stock. It refreshes staging review reasons and the
commit's deferred-entry counters. Repeating the action is safe.

Confirm each actual customer/supplier collection or payment against source
evidence. Record retail collections only when genuinely retail money received
and not already counted. Expense and transfer entries should be excluded from
customer/supplier payment posting, with a note; they are not automatically sales.
Unknown entries may remain unresolved. Existing invoice allocations are never
silently rewritten by recheck.

V22 adds nullable match identity columns. Back up before deployment. The isolated
replay test checks both preview/commit count equality and committed-session
recheck, including hashes proving posted financial records were unchanged.
