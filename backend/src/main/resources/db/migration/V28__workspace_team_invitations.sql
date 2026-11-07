create table workspace_invitations (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  email varchar(190) not null,
  role varchar(32) not null,
  token_hash varchar(64) not null unique,
  status varchar(16) not null default 'PENDING',
  expires_at timestamptz not null,
  invited_by uuid not null references app_users(id),
  accepted_by uuid references app_users(id),
  accepted_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  created_by uuid,
  updated_by uuid,
  unique (tenant_id, email),
  constraint workspace_invitation_status check (status in ('PENDING','ACCEPTED','REVOKED')),
  constraint workspace_invitation_role check (role in ('ADMIN','MANAGER','STAFF','WAREHOUSE_STAFF','SALES_STAFF','PURCHASE_MANAGER','ACCOUNTANT','VIEWER','AUDITOR'))
);
create index idx_workspace_invitations_tenant_created on workspace_invitations (tenant_id, created_at desc);
