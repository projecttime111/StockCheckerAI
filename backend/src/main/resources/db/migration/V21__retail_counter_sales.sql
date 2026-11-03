create table retail_counter_sales (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  sale_date date not null,
  amount numeric(18,2) not null check (amount > 0),
  collection_method varchar(32) not null check (collection_method in ('CASH', 'UPI', 'CASH_AND_UPI')),
  source_import_session_id uuid,
  source_file_id uuid,
  source_row_number integer not null,
  source_fingerprint varchar(64) not null,
  source_party_name varchar(500),
  source_direction varchar(32),
  reference_number varchar(255),
  note varchar(500),
  raw_metadata_json jsonb not null default '{}'::jsonb,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  unique (tenant_id, source_fingerprint)
);

create index idx_retail_counter_sales_date on retail_counter_sales(tenant_id, sale_date desc, id);
alter table smart_staged_cashbook_entries add column retail_counter_sale_id uuid;
