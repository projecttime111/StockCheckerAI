create table stock_snapshot_baselines (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  product_id uuid not null references products(id),
  warehouse_id uuid references warehouses(id),
  adjustment_warehouse_id uuid not null references warehouses(id),
  snapshot_date date not null,
  imported_quantity numeric(14,3) not null,
  adjustment_movement_id uuid,
  source_import_session_id uuid not null references import_sessions(id),
  source_file_id uuid,
  source_row_number integer,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid
);

create index idx_snapshot_baseline_lookup
  on stock_snapshot_baselines(tenant_id, product_id, warehouse_id, snapshot_date desc);
create index idx_snapshot_baseline_session
  on stock_snapshot_baselines(tenant_id, source_import_session_id);

alter table stock_movements add column source_import_session_id uuid references import_sessions(id);
alter table stock_movements add column source_file_id uuid;
alter table stock_movements add column source_row_number integer;
alter table stock_movements add column source_fingerprint varchar(64);
alter table stock_movements add column snapshot_baseline_id uuid references stock_snapshot_baselines(id);
alter table stock_movements add column decision_reason varchar(64);

create unique index ux_stock_movement_source_fingerprint
  on stock_movements(tenant_id, source_fingerprint)
  where source_fingerprint is not null;
create index idx_stock_movement_import_session
  on stock_movements(tenant_id, source_import_session_id, movement_type);
create index idx_stock_movement_snapshot_source
  on stock_movements(tenant_id, snapshot_baseline_id);
