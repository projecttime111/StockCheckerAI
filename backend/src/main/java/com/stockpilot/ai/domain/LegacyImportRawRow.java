package com.stockpilot.ai.domain;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
@Entity
@Table(name = "legacy_import_raw_rows")
public class LegacyImportRawRow extends TenantOwnedEntity {
    public UUID importBatchId;
    public int rowNumber;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, String> rawMetadata = new LinkedHashMap<>();
}
