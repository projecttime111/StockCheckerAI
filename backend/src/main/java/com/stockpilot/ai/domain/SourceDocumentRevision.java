package com.stockpilot.ai.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.*;

/** Immutable source and destination evidence for an applied document revision. */
@Entity @Table(name="source_document_revisions")
public class SourceDocumentRevision extends TenantOwnedEntity {
    public UUID importSessionId;
    public UUID sourceFileId;
    public UUID invoiceId;
    public String documentType;
    @jakarta.persistence.Column(length=80) public String sourceSystem;
    @jakarta.persistence.Column(length=500) public String sourceCompany;
    @jakarta.persistence.Column(length=500) public String sourceRecordId;
    @jakarta.persistence.Column(length=200) public String sourceRevision;
    public String sourceHash;
    @jakarta.persistence.Column(length=64) public String sourceIdentityHash;
    public String operation;
    @jakarta.persistence.Column(length=500) public String reason;
    @JdbcTypeCode(SqlTypes.JSON) public Map<String,Object> beforeJson = new LinkedHashMap<>();
    @JdbcTypeCode(SqlTypes.JSON) public Map<String,Object> afterJson = new LinkedHashMap<>();
}
