package com.stockpilot.ai.web.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.stockpilot.ai.domain.DomainEnums;

import java.util.UUID;
import java.time.LocalDate;
import java.util.List;
import java.math.BigDecimal;

public final class SmartImportDtos {
    private SmartImportDtos() {
    }

    public record CreateSessionRequest(
        @Size(max = 160, message = "Session name must be 160 characters or fewer") String name
    ) {
    }

    public record FileTypeOverrideRequest(@NotNull DomainEnums.DetectedFileType selectedFileType) {
    }

    public record ReviewResolutionRequest(
        @NotBlank String action,
        UUID targetId,
        DomainEnums.DetectedFileType selectedFileType
    ) {
    }

    public record VoucherRateResolutionRequest(
        @NotNull DomainEnums.VoucherRateResolutionAction action,
        BigDecimal verifiedRate,
        @Size(max = 500) String reason,
        @Size(max = 100) String confirmation
    ) {
    }

    public record VoucherRateBulkPreviewRequest(
        @NotNull UUID sourceIssueId,
        @NotNull DomainEnums.VoucherRateResolutionAction action,
        BigDecimal verifiedRate
    ) {
    }

    public record VoucherRateBulkResolutionRequest(
        @NotNull UUID sourceIssueId,
        @NotNull DomainEnums.VoucherRateResolutionAction action,
        BigDecimal verifiedRate,
        @Size(max = 500) String reason,
        @NotNull Integer expectedCount,
        @NotBlank @Size(max = 100) String confirmation
    ) {
    }

    public record DryRunRequest(
        DomainEnums.ImportStrategy strategy,
        DomainEnums.VoucherStockImpactMode stockImpactMode,
        DomainEnums.ReturnStockImpactMode returnStockImpactMode,
        DomainEnums.StockJournalImpactMode stockJournalImpactMode,
        DomainEnums.NegativeStockImportPolicy negativeStockPolicy,
        LocalDate snapshotDate,
        Boolean snapshotDateConfirmed,
        LocalDate outstandingSnapshotDate,
        Boolean outstandingSnapshotDateConfirmed
    ) {
    }

    public record CommitRequest(
        @NotNull UUID dryRunId,
        @NotBlank String confirmation
    ) {
    }

    public record CashbookReviewResolutionRequest(
        @NotNull DomainEnums.CashbookResolutionAction action,
        UUID targetId,
        @Size(max = 80) String confirmation,
        @Size(max = 500) String note,
        DomainEnums.RetailCollectionMethod collectionMethod,
        Boolean receivedFromRetailSales,
        Boolean notAlreadyRecorded
    ) {
    }

    public record BulkRetailSaleResolutionRequest(
        @NotNull @Size(min = 1, max = 200) List<@NotNull UUID> entryIds,
        @NotNull DomainEnums.RetailCollectionMethod collectionMethod,
        @NotBlank @Size(max = 80) String confirmation,
        @Size(max = 500) String note,
        @NotNull Boolean receivedFromRetailSales,
        @NotNull Boolean notAlreadyRecorded
    ) {
    }

    public record ReturnReviewResolutionRequest(
        @NotNull DomainEnums.ReturnResolutionAction action,
        UUID productId,
        UUID warehouseId,
        java.math.BigDecimal quantity,
        DomainEnums.ReturnDisposition disposition,
        @Size(max = 80) String confirmation,
        @Size(max = 500) String note
    ) {
    }

    public record TransferReviewResolutionRequest(
        @NotNull DomainEnums.TransferResolutionAction action,
        UUID productId,
        UUID sourceWarehouseId,
        UUID destinationWarehouseId,
        java.math.BigDecimal quantity,
        @Size(max = 80) String confirmation,
        @Size(max = 500) String note
    ) {
    }
}
