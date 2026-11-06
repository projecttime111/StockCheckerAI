create table legacy_import_raw_rows (
    id uuid primary key,
    tenant_id uuid not null references tenants(id),
    import_batch_id uuid not null references import_batches(id),
    row_number integer not null,
    raw_metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null, updated_at timestamptz not null,
    created_by uuid, updated_by uuid,
    unique (tenant_id, import_batch_id, row_number)
);
