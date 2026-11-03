alter table smart_staged_vouchers add column return_classification varchar(40) not null default 'NOT_A_RETURN';
alter table smart_staged_vouchers add column return_resolution_status varchar(40);
alter table smart_staged_vouchers add column return_confidence numeric(5,4) not null default 0;
alter table smart_staged_vouchers add column return_detection_reason varchar(1000);
alter table smart_staged_vouchers add column return_resolved_by uuid;
alter table smart_staged_vouchers add column return_resolved_at timestamptz;

alter table smart_staged_voucher_items add column raw_quantity numeric(18,3);
alter table smart_staged_voucher_items add column billed_quantity numeric(18,3);
alter table smart_staged_voucher_items add column actual_quantity numeric(18,3);
alter table smart_staged_voucher_items add column return_direction varchar(32) not null default 'NONE';
alter table smart_staged_voucher_items add column return_disposition varchar(32) not null default 'REVIEW_REQUIRED';
alter table smart_staged_voucher_items add column return_resolution_status varchar(40);
alter table smart_staged_voucher_items add column return_confidence numeric(5,4) not null default 0;
alter table smart_staged_voucher_items add column return_detection_reason varchar(1000);
alter table smart_staged_voucher_items add column return_resolved_by uuid;
alter table smart_staged_voucher_items add column return_resolved_at timestamptz;

alter table financial_adjustments add column return_classification varchar(40) not null default 'FINANCIAL_ONLY';
alter table financial_adjustments add column return_resolution_status varchar(40);

alter table financial_adjustment_items add column return_direction varchar(32) not null default 'NONE';
alter table financial_adjustment_items add column return_disposition varchar(32) not null default 'FINANCIAL_ONLY';
alter table financial_adjustment_items add column stock_movement_id uuid references stock_movements(id);
alter table financial_adjustment_items add column stock_effect_fingerprint varchar(64);

create index idx_staged_voucher_return_review
  on smart_staged_vouchers(tenant_id, import_session_id, return_resolution_status, return_classification);
create index idx_staged_voucher_item_return_review
  on smart_staged_voucher_items(tenant_id, import_session_id, return_resolution_status, return_direction);
create index idx_financial_adjustment_return
  on financial_adjustments(tenant_id, return_classification, voucher_date);
create unique index ux_financial_adjustment_item_stock_effect
  on financial_adjustment_items(tenant_id, stock_effect_fingerprint)
  where stock_effect_fingerprint is not null;
