CREATE TABLE source_document_revisions (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
 import_session_id UUID NOT NULL, source_file_id UUID, invoice_id UUID NOT NULL,
 document_type VARCHAR(40) NOT NULL, source_system VARCHAR(80), source_company VARCHAR(500), source_record_id VARCHAR(500),
 source_revision VARCHAR(200), source_hash VARCHAR(64) NOT NULL, source_identity_hash VARCHAR(64), operation VARCHAR(40) NOT NULL,
 reason VARCHAR(500), before_json JSONB NOT NULL DEFAULT '{}', after_json JSONB NOT NULL DEFAULT '{}',
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created_by UUID, updated_by UUID
);
CREATE INDEX idx_source_revision_tenant_invoice ON source_document_revisions(tenant_id, invoice_id, created_at);
CREATE INDEX idx_source_revision_tenant_session ON source_document_revisions(tenant_id, import_session_id);

ALTER TABLE smart_staged_products ADD COLUMN sales_price NUMERIC(14,2);
CREATE INDEX idx_source_revision_identity ON source_document_revisions(tenant_id, source_identity_hash, created_at DESC);
