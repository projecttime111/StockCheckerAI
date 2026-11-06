package com.stockpilot.ai.web.dto;

import com.stockpilot.ai.domain.DomainEnums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApiDtos {
    private ApiDtos() {
    }

    public record RegisterRequest(
        @NotBlank String businessName,
        @NotNull DomainEnums.BusinessMode businessMode,
        @Email @NotBlank String email,
        @Size(min = 8, message = "Password must be at least 8 characters") String password,
        @NotBlank String fullName
    ) {
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record GoogleLoginRequest(@NotBlank String idToken) {
    }

    public record VerifyEmailRequest(@NotBlank String token) {
    }

    public record ResendVerificationRequest(@Email @NotBlank String email) {
    }

    public record ForgotPasswordRequest(@Email @NotBlank String email) {
    }

    public record ResetPasswordRequest(
        @NotBlank String token,
        @Size(min = 8, message = "Password must be at least 8 characters") String newPassword
    ) {
    }

    public record AuthMessageResponse(String message, boolean emailVerificationRequired) {
    }

    public record AuthResponse(String accessToken, UUID tenantId, UUID userId, DomainEnums.Role role, String email, String fullName) {
    }

    public record MeResponse(UUID userId, String email, String fullName, UUID tenantId, DomainEnums.Role role) {
    }

    public record TenantResponse(UUID id, String name, DomainEnums.BusinessMode businessMode, String currency, boolean gstEnabled, boolean allowNegativeStock, boolean portalShowAllActiveProducts) {
    }

    public record TenantUpdateRequest(@NotBlank String name, @NotBlank String currency, boolean gstEnabled, boolean allowNegativeStock, boolean portalShowAllActiveProducts) {
    }

    public record BusinessModeRequest(@NotNull DomainEnums.BusinessMode businessMode) {
    }

    public record WorkspaceCreateRequest(
        @NotBlank String businessName,
        @NotNull DomainEnums.BusinessMode businessMode,
        @NotBlank String currency,
        boolean gstEnabled
    ) {
    }

    public record ProductRequest(
        @Size(max = 80) String sku,
        @NotBlank @Size(max = 220) String name,
        @Size(max = 160) String categoryName,
        @Size(max = 160) String brandName,
        @NotBlank @Size(max = 20) String unitCode,
        @Size(max = 120) String barcode,
        @Size(max = 30) String hsnCode,
        @PositiveOrZero @DecimalMax("100.00") BigDecimal gstPercentage,
        @PositiveOrZero @Digits(integer = 12, fraction = 12) BigDecimal defaultPurchasePrice,
        @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal defaultSalesPrice,
        @PositiveOrZero BigDecimal reorderPoint,
        @PositiveOrZero BigDecimal safetyStock,
        @Min(0) Integer leadTimeDays,
        @PositiveOrZero BigDecimal minimumOrderQuantity
    ) {
    }

    public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String normalizedName,
        String categoryName,
        String brandName,
        String unitCode,
        String barcode,
        String hsnCode,
        BigDecimal gstPercentage,
        BigDecimal defaultPurchasePrice,
        BigDecimal defaultSalesPrice,
        BigDecimal reorderPoint,
        BigDecimal currentStock,
        boolean active
    ) {
    }

    public record WarehouseRequest(@NotBlank String name, String code, String address) {
    }

    public record WarehouseResponse(UUID id, String name, String code, String address, boolean active) {
    }

    public record PartyRequest(@NotBlank String name, String phone, @Email String email, String gstin, @PositiveOrZero BigDecimal creditLimit, @Min(0) Integer creditDays) {
    }

    public record CustomerResponse(UUID id, String name, String phone, String email, String gstin, BigDecimal creditLimit, BigDecimal outstanding) {
    }

    public record CustomerPaymentRequest(
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotNull LocalDate paymentDate,
        String mode,
        String referenceNumber,
        String notes,
        UUID reminderId
    ) {
    }

    public record CustomerPaymentResponse(UUID id, UUID customerId, BigDecimal amount, LocalDate paymentDate, String notes) {
    }

    public record PaymentReminderRequest(
        @NotNull LocalDate reminderDate,
        String reminderTime,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amountDue,
        String notes
    ) {
    }

    public record PaymentReminderResponse(UUID id, UUID customerId, String customerName, BigDecimal outstanding, BigDecimal amountDue, LocalDate reminderDate, String reminderTime, String status, String dueStatus, String notes) {
    }

    public record SupplierResponse(UUID id, String name, String phone, String email, String gstin, Integer creditDays, BigDecimal payable) {
    }

    public record InvoiceLineRequest(
        @NotNull UUID productId,
        @NotNull @Positive @Digits(integer = 11, fraction = 3) BigDecimal quantity,
        UUID unitId,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal rate,
        @PositiveOrZero @DecimalMax("100.00") BigDecimal taxPercentage,
        @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal discountAmount
    ) {
    }

    public record PurchaseInvoiceRequest(
        UUID supplierId,
        @NotNull UUID warehouseId,
        @NotBlank @Size(max = 80) String invoiceNumber,
        @NotNull LocalDate invoiceDate,
        @NotEmpty List<@Valid InvoiceLineRequest> items
    ) {
    }

    public record SalesInvoiceRequest(
        UUID customerId,
        @NotNull UUID warehouseId,
        @NotBlank @Size(max = 80) String invoiceNumber,
        @NotNull LocalDate invoiceDate,
        @NotEmpty List<@Valid InvoiceLineRequest> items
    ) {
    }

    public record InvoiceResponse(UUID id, String invoiceNumber, LocalDate invoiceDate, BigDecimal subtotal, BigDecimal taxAmount, BigDecimal totalAmount) {
    }

    public record StockAdjustmentRequest(
        @NotNull UUID productId,
        @NotNull UUID warehouseId,
        @NotNull BigDecimal quantityDelta,
        @PositiveOrZero BigDecimal rate,
        @NotBlank String notes
    ) {
    }

    public record StockTransferRequest(
        @NotNull UUID productId,
        @NotNull UUID sourceWarehouseId,
        @NotNull UUID destinationWarehouseId,
        @NotNull @Positive BigDecimal quantity,
        @NotBlank String notes
    ) {
    }

    public record StockResponse(UUID productId, String productName, UUID warehouseId, String warehouseName, BigDecimal currentStock, BigDecimal stockValue) {
    }

    public record MovementResponse(UUID id, UUID productId, UUID warehouseId, DomainEnums.MovementType movementType, BigDecimal quantity, BigDecimal baseQuantity, BigDecimal rate, Instant movementDate, String referenceType) {
    }

    public record DashboardSummary(BigDecimal totalStockValue, BigDecimal monthlySales, BigDecimal grossProfit, long lowStockCount, BigDecimal deadStockValue, BigDecimal outstandingReceivables, BigDecimal grossMarginPercent, long totalProducts, long totalCustomers, long totalSuppliers, int importDataQualityScore) {
    }

    public record ActionCard(String title, String reason, BigDecimal estimatedImpact, String href, String cta) {
    }

    public record TrendPoint(LocalDate date, BigDecimal value) {
    }

    public record DeadStockResponse(UUID productId, String productName, BigDecimal stockQuantity, BigDecimal stockValue, LocalDate lastSoldDate, String suggestedAction, String explanation) {
    }

    public record DeadStockActionResponse(UUID productId, String productName, String warehouseName, BigDecimal quantity, BigDecimal stockValue, LocalDate lastSoldDate, long daysSinceLastSale, BigDecimal averageMonthlySale, BigDecimal blockedCapital, String recommendedAction) {
    }

    public record DeadStockActionRequest(@NotBlank String action, String notes) {
    }

    public record LowStockResponse(UUID productId, String productName, BigDecimal currentStock, BigDecimal reorderPoint) {
    }

    public record DashboardProductMetric(UUID productId, String productName, BigDecimal quantity, BigDecimal revenue, BigDecimal stockQuantity, BigDecimal stockValue, LocalDate lastSoldDate) {
    }

    public record AuditLogResponse(UUID id, UUID actorUserId, String action, String entityType, UUID entityId, Map<String, Object> details, Instant createdAt) {
    }

    public record ForecastResponse(UUID productId, String productName, BigDecimal currentStock, BigDecimal averageDailyDemand, BigDecimal next7DaysDemand, BigDecimal next30DaysDemand, LocalDate stockoutDate, Boolean salesHistoryAvailable) {
        public ForecastResponse(UUID productId, String productName, BigDecimal currentStock, BigDecimal averageDailyDemand, BigDecimal next7DaysDemand, BigDecimal next30DaysDemand, LocalDate stockoutDate) {
            this(productId, productName, currentStock, averageDailyDemand, next7DaysDemand, next30DaysDemand, stockoutDate, null);
        }
    }

    public record ReorderSuggestionResponse(UUID productId, String productName, BigDecimal reorderPoint, BigDecimal suggestedQuantity, String reason, String status) {
    }

    public record SmartReorderSuggestion(UUID productId, String productName, UUID warehouseId, String warehouseName, BigDecimal currentStock, BigDecimal averageDailyDemand, BigDecimal last7DaysDemand, BigDecimal last30DaysDemand, LocalDate expectedStockoutDate, int supplierLeadTimeDays, BigDecimal safetyStock, BigDecimal minimumOrderQuantity, BigDecimal pendingPurchaseQuantity, BigDecimal pendingSalesQuantity, BigDecimal recommendedQuantity, String reason, Boolean salesHistoryAvailable) {
        public SmartReorderSuggestion(UUID productId, String productName, UUID warehouseId, String warehouseName, BigDecimal currentStock, BigDecimal averageDailyDemand, BigDecimal last7DaysDemand, BigDecimal last30DaysDemand, LocalDate expectedStockoutDate, int supplierLeadTimeDays, BigDecimal safetyStock, BigDecimal minimumOrderQuantity, BigDecimal pendingPurchaseQuantity, BigDecimal pendingSalesQuantity, BigDecimal recommendedQuantity, String reason) {
            this(productId, productName, warehouseId, warehouseName, currentStock, averageDailyDemand, last7DaysDemand, last30DaysDemand, expectedStockoutDate, supplierLeadTimeDays, safetyStock, minimumOrderQuantity, pendingPurchaseQuantity, pendingSalesQuantity, recommendedQuantity, reason, null);
        }
    }

    public record DraftPurchaseOrderRequest(UUID supplierId, List<UUID> productIds) {
    }

    public record DraftPurchaseOrderResponse(UUID purchaseOrderId, String orderNumber, BigDecimal totalAmount, int itemCount) {
    }

    public record ImportUploadResponse(UUID batchId, String status, int rowCount) {
    }

    public record ImportMappingRequest(@NotEmpty Map<String, String> mapping) {
    }

    public record ImportResolutionRequest(
        DomainEnums.NegativeStockImportPolicy negativeStockPolicy,
        DomainEnums.VoucherStockImpactMode voucherStockImpactMode,
        Boolean confirmStockMovementsAfterSnapshot
    ) {
    }

    public record ImportUndoRequest(DomainEnums.ImportUndoStrategy strategy) {
    }

    public record ImportTemplateRequest(@NotBlank String name, @NotNull DomainEnums.SourceType sourceType, @NotEmpty Map<String, String> mapping) {
    }

    public record DevResetRequest(@NotBlank String confirmation) {
    }

    public record DestructiveActionRequest(@NotBlank String confirmation) {
    }

    public record DeleteImpactResponse(
        long products,
        long customers,
        long suppliers,
        long warehouses,
        long stockMovements,
        long salesInvoices,
        long purchaseInvoices,
        long importBatches,
        long importFiles,
        long forecastResults,
        long deadStockInsights,
        long auditLogs,
        long uploadedFiles
    ) {
    }

    public record WorkspaceResetResponse(UUID tenantId, Map<String, Integer> deleted, int uploadedFilesScheduledForDeletion) {
    }

    public record WorkspaceDeletionResponse(
        String status,
        UUID deletedTenantId,
        UUID nextTenantId,
        boolean onboardingRequired,
        String accessToken,
        DomainEnums.Role role
    ) {
    }

    public record AccountDeletionResponse(String status, UUID userId) {
    }

    public record ChatRequest(UUID conversationId, @NotBlank String message) {
    }

    public record ChatResponse(UUID conversationId, String response, Map<String, Object> evidence) {
    }

    public record ProductCleanupSuggestion(UUID productId, String productName, String normalizedName, String brand, String category, String unitSize, List<String> issues) {
    }

    public record DuplicateProductGroup(String matchKey, int similarityScore, List<ProductCleanupSuggestion> products, ProductCleanupSuggestion suggestedCanonical) {
    }

    public record DataQualitySummary(long totalProducts, long duplicateGroups, long productsWithMissingFields, long productsWithoutRecentSales, long productsWithoutPurchaseCost, int qualityScore) {
    }

    public record MissingProductFields(UUID productId, String productName, List<String> missingFields, ProductCleanupSuggestion suggestion) {
    }

    public record ApplyProductSuggestionRequest(String normalizedName, String brand, String category, String unitCode, String hsnCode, BigDecimal gstPercentage) {
    }

    public record MergeProductsRequest(@NotNull UUID targetProductId, @NotEmpty List<UUID> sourceProductIds, String confirmation) {
    }

    public record ProfitReason(String reason, BigDecimal impactAmount, String evidence) {
    }

    public record ProfitDropResponse(String summary, BigDecimal profitChange, BigDecimal marginChangePercent, List<ProfitReason> topReasons, List<String> recommendedActions, Map<String, Object> evidence) {
    }

    public record TallyIntegrationStatus(String status, LocalDate lastSuccessfulImportDate, long productsImported, long customersImported, long suppliersImported, long salesVouchersImported, long purchaseVouchersImported, long importErrors, List<String> unmappedFields, long duplicateProductsDetected, long missingGstHsnUnitCategoryWarnings, int dataQualityScore, long aiInsightsGenerated) {
    }

    public record PortalProduct(UUID productId, String productName, BigDecimal price, BigDecimal availableStock) {
    }

    public record PortalOrderLine(@NotNull UUID productId, @NotNull @Positive BigDecimal quantity) {
    }

    public record PortalOrderRequest(@NotEmpty List<@Valid PortalOrderLine> items) {
    }

    public record PortalOrderResponse(UUID orderId, String orderNumber, String status, BigDecimal totalAmount) {
    }

    public record PortalInvoice(UUID invoiceId, String invoiceNumber, LocalDate invoiceDate, BigDecimal totalAmount) {
    }
}
