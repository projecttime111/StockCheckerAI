create table smart_staged_transfer_candidates (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  import_session_id uuid not null references import_sessions(id),
  staged_voucher_id uuid not null references smart_staged_vouchers(id),
  session_file_id uuid,
  source_row_number integer not null,
  source_item_id uuid references smart_staged_voucher_items(id),
  destination_item_id uuid references smart_staged_voucher_items(id),
  voucher_external_id varchar(255),
  voucher_number varchar(255),
  voucher_date date,
  product_name varchar(300),
  normalized_product_name varchar(300),
  matched_product_id uuid references products(id),
  unit_code varchar(40),
  source_warehouse_name varchar(200),
  source_warehouse_id uuid references warehouses(id),
  destination_warehouse_name varchar(200),
  destination_warehouse_id uuid references warehouses(id),
  raw_outward_quantity numeric(18,3),
  raw_inward_quantity numeric(18,3),
  normalized_quantity numeric(18,3),
  batch_reference varchar(255),
  classification varchar(48) not null,
  classification_confidence numeric(5,4) not null default 0,
  decision_reasons varchar(2000),
  resolution_status varchar(40) not null default 'REVIEW_REQUIRED',
  transfer_fingerprint varchar(64),
  transfer_line_fingerprint varchar(64),
  source_snapshot_id uuid references stock_snapshot_baselines(id),
  destination_snapshot_id uuid references stock_snapshot_baselines(id),
  source_snapshot_date date,
  destination_snapshot_date date,
  resolved_by uuid,
  resolved_at timestamptz,
  evidence_json jsonb not null default '{}'::jsonb,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid
);

create index idx_transfer_candidate_session_classification
  on smart_staged_transfer_candidates(tenant_id, import_session_id, classification);
create index idx_transfer_candidate_review
  on smart_staged_transfer_candidates(tenant_id, import_session_id, resolution_status);
create index idx_transfer_candidate_fingerprint
  on smart_staged_transfer_candidates(tenant_id, transfer_fingerprint);
create index idx_transfer_candidate_route
  on smart_staged_transfer_candidates(
    tenant_id, matched_product_id, source_warehouse_id, destination_warehouse_id, voucher_date
  );

alter table stock_movements add column transfer_group_id uuid;
alter table stock_movements add column linked_movement_id uuid references stock_movements(id);
alter table stock_movements add column transfer_pair_fingerprint varchar(64);
alter table stock_movements add column transfer_line_fingerprint varchar(64);

create index idx_stock_movement_transfer_group
  on stock_movements(tenant_id, transfer_group_id);
create index idx_stock_movement_transfer_line
  on stock_movements(tenant_id, transfer_line_fingerprint);
create unique index ux_stock_movement_transfer_pair_side
  on stock_movements(tenant_id, transfer_pair_fingerprint, movement_type);
