# Retail Counter Sales: Amount Only

In Smart Import -> Review Queue, follow "Resolve cashbook entries and retail sales".
For an unmatched/low-confidence entry, select "Retail counter sale". Choose Cash,
UPI / GPay, or Cash + UPI combined. Confirm that it is money received from retail
sales and is not included in existing invoices, payments, or another retail total.
For PAYMENT or UNKNOWN source directions, an explanation is also mandatory.
"Confirm and record retail sale" immediately saves a business record, like the
existing manual payment action. It does not wait for Final Commit.

For repeated Cash or GPay rows, enter a keyword in the Review Queue, inspect the
filtered rows, and use "Select all filtered". A bulk batch is limited to 200 rows
and displays the selected count, date range, and total before posting. One
collection method and optional shared note apply to the batch. If any selected
source row says PAYMENT or has an unknown direction, the shared explanation is
required. The final browser confirmation repeats the exact count and amount.
Each row is committed independently so a bad row does not roll back valid rows;
the result lists recorded, duplicate-skipped, and failed rows, with failures left
selected for correction or individual review.

Sales -> Retail counter sales lists these confirmed collections separately from
itemized invoices, preserving the source date, amount, direction, reference and
note. No product, quantity, customer, invoice, tax allocation, COGS, profit, payment,
receivable, or stock movement is fabricated. Closing-stock behavior is unchanged.
Cash + UPI is one combined amount, never a guessed split.

## Safety

- Existing cashbook fingerprints are unique per tenant for retail records.
- Repeated confirmation is idempotent. Rebuild/reimport restores the decision.
- Existing customer/supplier payments block retail recording, and retail records
  are excluded from manual/automatic payment posting and future commit previews.
- Concurrent Smart Import payment writers share a tenant lock.
- The API requires imports.commit to record and sales.view to list. CUSTOMER_USER
  has neither access. Decisions are audited as RETAIL_COUNTER_SALE_RECORDED and
  CASHBOOK_REVIEW_RESOLVED. Bulk attempts also emit CASHBOOK_BULK_RETAIL_RESOLVED.
- Final Commit reconciliation counts retailCounterSaleRowsResolved separately.
- Source-session cancellation/removing an upload does not undo an explicitly
  recorded retail sale. Workspace data reset includes these records.

## Limits

Fingerprint matching catches the same source entry; it cannot prove that a
different daily total, invoice or bank settlement does not overlap. Confirm only
after checking those records. Cash deposits and transfers of previously collected
UPI money must not be recorded again as sales. These are amount-only collection
records, not itemized tax invoices or a replacement for required source records.
Correction/reversal of a mistaken retail classification is not provided in this
increment; do not classify uncertain entries. Keep them unresolved instead.

Deployment adds Flyway V21. No production environment variable changes are needed.
