alter table ai_conversations add column required_permissions jsonb not null default '[]'::jsonb;
alter table ai_messages add column request_id uuid;
alter table ai_messages add column status varchar(30);
alter table ai_messages add column required_permissions jsonb not null default '[]'::jsonb;
create unique index uq_ai_conversation_tenant_id on ai_conversations(tenant_id,id);
alter table ai_messages add constraint fk_ai_message_tenant_conversation foreign key(tenant_id,conversation_id) references ai_conversations(tenant_id,id);
create index idx_ai_conversation_owner_history on ai_conversations(tenant_id,user_id,updated_at desc,id);
create index idx_ai_message_history on ai_messages(tenant_id,conversation_id,created_at,id);
create table ai_chat_runs (
 id uuid primary key,
 tenant_id uuid not null references tenants(id),
 user_id uuid not null references app_users(id),
 conversation_id uuid not null,
 request_id uuid not null,
 fingerprint varchar(64) not null,
 status varchar(30) not null,
 attempt_count integer not null default 1, last_attempt_at timestamptz not null default now(),
 provider varchar(80), model varchar(120),
 input_tokens integer not null default 0, output_tokens integer not null default 0,
 duration_ms bigint not null default 0,
 response_json jsonb not null default '{}'::jsonb,
 required_permissions jsonb not null default '[]'::jsonb,
 created_at timestamptz not null, updated_at timestamptz not null, created_by uuid, updated_by uuid,
 unique(tenant_id,user_id,request_id),
 foreign key(tenant_id,conversation_id) references ai_conversations(tenant_id,id)
);
create index idx_ai_run_rate on ai_chat_runs(tenant_id,last_attempt_at,user_id);
create index idx_ai_run_conversation on ai_chat_runs(tenant_id,conversation_id,status);
