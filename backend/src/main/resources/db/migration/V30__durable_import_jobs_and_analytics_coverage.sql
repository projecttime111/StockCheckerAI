create table import_jobs (
    id uuid primary key, tenant_id uuid not null references tenants(id),
    import_session_id uuid not null references import_sessions(id), requested_by uuid not null references app_users(id),
    request_id uuid not null, request_hash varchar(64) not null, operation varchar(24) not null,
    status varchar(24) not null, attempts integer not null default 0, progress integer not null default 0,
    message varchar(255), error varchar(255), worker_id varchar(80), lease_until timestamptz,
    payload jsonb not null default '{}', result jsonb not null default '{}',
    created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid,
    unique(tenant_id,request_id),
    constraint import_job_status check(status in ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    constraint import_job_operation check(operation in ('CLASSIFY','STAGE','DRY_RUN','COMMIT'))
);
create index idx_import_jobs_claim on import_jobs(status, lease_until, created_at);
create index idx_import_jobs_session on import_jobs(tenant_id,import_session_id,created_at desc);
create unique index idx_import_jobs_one_active_tenant on import_jobs(tenant_id) where status in ('QUEUED','RUNNING');
create table analytics_coverage (
    id uuid primary key, tenant_id uuid not null unique references tenants(id),
    from_date date not null, through_date date not null, complete boolean not null default false,
    confirmed_by uuid not null references app_users(id),
    created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid,
    check(through_date >= from_date)
);
create index idx_sales_invoice_analytics_date on sales_invoices(tenant_id,invoice_date);
create index idx_purchase_invoice_analytics_date on purchase_invoices(tenant_id,invoice_date);
create index idx_sales_item_analytics_document on sales_invoice_items(tenant_id,sales_invoice_id,product_id);
alter table sales_invoice_items add column cost_provenance varchar(40) not null default 'LEGACY_UNVERIFIED';
alter table sales_invoice_items add column warehouse_id uuid references warehouses(id);
-- Recover unambiguous line-level warehouse evidence for prior stock-affecting imports.
update sales_invoice_items i set warehouse_id = m.warehouse_id
from (
    select tenant_id, reference_id, (array_agg(warehouse_id))[1] as warehouse_id
    from stock_movements
    where reference_type = 'SALES_INVOICE_ITEM' and movement_type = 'SALE' and warehouse_id is not null
    group by tenant_id, reference_id having count(distinct warehouse_id) = 1
) m where i.tenant_id = m.tenant_id and i.id = m.reference_id;
alter table forecast_results add column coverage_days integer not null default 0;
alter table forecast_results add column coverage_confirmed boolean not null default false;
