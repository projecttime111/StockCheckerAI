create table platform_client_profiles (
    id uuid primary key,
    tenant_id uuid not null references tenants(id),
    plan varchar(32) not null default 'FREE',
    subscription_status varchar(32) not null default 'TRIAL',
    subscription_ends_at timestamptz,
    internal_notes varchar(10000) not null default '',
    created_at timestamptz not null, updated_at timestamptz not null,
    created_by uuid, updated_by uuid,
    constraint uk_platform_client_profile_tenant unique (tenant_id),
    constraint ck_platform_plan check (plan in ('FREE', 'STARTER', 'PRO', 'ENTERPRISE')),
    constraint ck_platform_subscription_status check (subscription_status in ('TRIAL', 'ACTIVE', 'PAST_DUE', 'CANCELLED', 'EXPIRED'))
);
create index idx_platform_client_profile_subscription on platform_client_profiles(subscription_status, plan);
create index idx_platform_tenant_created on tenants(created_at desc, id);
create index idx_platform_tenant_name on tenants(lower(name));

create table platform_admin_audit (
    id uuid primary key,
    target_tenant_id uuid not null references tenants(id),
    actor_user_id uuid not null references app_users(id),
    action varchar(64) not null,
    reason varchar(1000) not null,
    before_values jsonb not null default '{}'::jsonb,
    after_values jsonb not null default '{}'::jsonb,
    created_at timestamptz not null, updated_at timestamptz not null,
    created_by uuid, updated_by uuid
);
create index idx_platform_audit_created on platform_admin_audit(created_at desc, id);
create index idx_platform_audit_tenant on platform_admin_audit(target_tenant_id, created_at desc);
create index idx_platform_audit_action on platform_admin_audit(action, created_at desc);
create index idx_platform_import_batches_status_created on import_batches(status, created_at desc);
create index idx_platform_import_sessions_status_created on import_sessions(status, created_at desc);
