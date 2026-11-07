-- External identity identifiers map onto existing local IDs; business foreign keys remain unchanged.
alter table app_users add column external_clerk_id varchar(128);
alter table app_users add column platform_role varchar(32);
alter table app_users add constraint uq_app_users_clerk_identity unique (external_clerk_id);
alter table app_users add constraint ck_app_users_platform_role check (
    platform_role is null or platform_role in ('PLATFORM_SUPER_ADMIN', 'PLATFORM_SUPPORT', 'PLATFORM_BILLING_ADMIN')
);
alter table tenants add column clerk_organization_id varchar(128);
alter table tenants add constraint uq_tenants_clerk_organization unique (clerk_organization_id);
