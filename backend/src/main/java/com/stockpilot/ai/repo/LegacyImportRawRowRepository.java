package com.stockpilot.ai.repo;
import com.stockpilot.ai.domain.LegacyImportRawRow;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;
public interface LegacyImportRawRowRepository extends JpaRepository<LegacyImportRawRow, UUID> {
    List<LegacyImportRawRow> findByTenantIdAndImportBatchIdOrderByRowNumberAsc(UUID tenantId, UUID batchId);
}
