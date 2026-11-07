package com.stockpilot.ai.web.dto;

import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.PlatformClientProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlatformDtos {
    private PlatformDtos() { }
    public record Page<T>(List<T> content, int page, int size, long totalElements, long totalPages) { }
    public record BusinessSummary(UUID id, String name, String businessMode, String status, String currency,
        boolean gstEnabled, Instant createdAt, String plan, String subscriptionStatus, Instant subscriptionEndsAt,
        long memberCount, long productCount, long importCount, Instant lastImportAt) { }
    public record Usage(long memberCount, long productCount, long customerCount, long supplierCount,
        long warehouseCount, long salesInvoiceCount, long purchaseInvoiceCount, long stockMovementCount,
        long importCount, long importFileCount, Instant lastImportAt) { }
    public record BusinessDetail(BusinessSummary business, Usage usage, String internalNotes) { }
    public record Member(UUID userId, String email, String fullName, String role, boolean active, Instant joinedAt) { }
    public record ImportSummary(UUID id, UUID tenantId, String businessName, String kind, String source,
        String status, String name, Instant createdAt, Instant committedAt, long rowCount, long errorCount,
        long fileCount, List<String> fileNames) { }
    public record Dashboard(Instant generatedAt, long totalBusinesses, long activeBusinesses,
        long suspendedBusinesses, long deletedBusinesses, long totalUsers, long totalImports,
        long failedImports, long committingImports, Map<String, Long> businessesByPlan,
        Map<String, Long> businessesBySubscriptionStatus) { }
    public record AuditEntry(UUID id, UUID tenantId, String businessName, UUID actorUserId, String actorName,
        String action, String reason, Map<String, Object> beforeValues, Map<String, Object> afterValues, Instant createdAt) { }
    public record DatabaseHealth(String status, long responseTimeMs, Instant checkedAt) { }
    public record SystemHealth(Instant generatedAt, DatabaseHealth database, long uptimeSeconds,
        long totalImports, long failedImports, long committingImports, Instant oldestCommittingAt,
        long failuresLast24Hours, Instant lastFailureAt, Map<String, Long> importsByStatus) { }
    public record StatusUpdate(@NotNull DomainEnums.TenantStatus status, @NotBlank @Size(max = 1000) String reason) { }
    public record SubscriptionUpdate(@NotNull PlatformClientProfile.Plan plan,
        @NotNull PlatformClientProfile.SubscriptionStatus subscriptionStatus, Instant subscriptionEndsAt,
        @NotBlank @Size(max = 1000) String reason) { }
    public record NotesUpdate(@NotNull @Size(max = 10000) String notes, @NotBlank @Size(max = 1000) String reason) { }
}
