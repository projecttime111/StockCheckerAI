-- Keep source document identity scoped to the Indian financial year (April-March).
alter table purchase_invoices drop constraint purchase_invoices_tenant_id_invoice_number_key;
alter table sales_invoices drop constraint sales_invoices_tenant_id_invoice_number_key;
create unique index uq_purchase_invoice_supplier_fiscal_number on purchase_invoices (
  tenant_id, coalesce(supplier_id, '00000000-0000-0000-0000-000000000000'::uuid),
  (extract(year from invoice_date)::integer - case when extract(month from invoice_date) < 4 then 1 else 0 end),
  lower(btrim(invoice_number))
);
create unique index uq_sales_invoice_fiscal_number on sales_invoices (
  tenant_id,
  (extract(year from invoice_date)::integer - case when extract(month from invoice_date) < 4 then 1 else 0 end),
  lower(btrim(invoice_number))
);
-- Unit costs are not currency totals; retain conversion precision without rewriting historical values.
alter table products alter column default_purchase_price type numeric(24,12);
alter table sales_invoice_items alter column cost_rate type numeric(24,12);
alter table stock_movements alter column rate type numeric(24,12);
alter table stock_movements alter column total_value type numeric(26,12);

create table mutation_receipts (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  user_id uuid not null references app_users(id),
  operation varchar(48) not null,
  request_key varchar(128) not null,
  request_hash varchar(64) not null,
  result_id uuid not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  unique (tenant_id, user_id, operation, request_key)
);
