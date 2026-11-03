package com.stockpilot.ai.repo;

import com.stockpilot.ai.domain.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

public final class Repositories {
    private Repositories() {
    }

    public interface TenantRepository extends JpaRepository<Tenant, UUID> {
        Optional<Tenant> findByIdAndStatus(UUID id, DomainEnums.TenantStatus status);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select t from Tenant t where t.id = :id")
        Optional<Tenant> findByIdForCashbookUpdate(@Param("id") UUID id);
    }

    public interface RetailCounterSaleRepository extends JpaRepository<RetailCounterSale, UUID> {
        Optional<RetailCounterSale> findByTenantIdAndSourceFingerprint(UUID tenantId, String sourceFingerprint);
        List<RetailCounterSale> findByTenantIdAndSourceFingerprintIn(UUID tenantId, List<String> sourceFingerprints);
        Page<RetailCounterSale> findByTenantId(UUID tenantId, Pageable pageable);
        long countByTenantId(UUID tenantId);
    }

    public interface UserRepository extends JpaRepository<UserAccount, UUID> {
        Optional<UserAccount> findByEmailIgnoreCase(String email);
        Optional<UserAccount> findByGoogleSubject(String googleSubject);
        Optional<UserAccount> findByVerificationTokenHash(String verificationTokenHash);
        Optional<UserAccount> findByPasswordResetTokenHash(String passwordResetTokenHash);
        boolean existsByEmailIgnoreCase(String email);

        @Query("select user from UserAccount user where user.id = :userId")
        Optional<UserAccount> findAccountForLifecycle(@Param("userId") UUID userId);
    }

    public interface MembershipRepository extends JpaRepository<UserTenantMembership, UUID> {
        Optional<UserTenantMembership> findFirstByUserId(UUID userId);
        List<UserTenantMembership> findByUserId(UUID userId);
        Optional<UserTenantMembership> findByTenantIdAndUserId(UUID tenantId, UUID userId);
        List<UserTenantMembership> findByTenantId(UUID tenantId);
        long countByTenantIdAndRole(UUID tenantId, DomainEnums.Role role);
    }

    public interface BranchRepository extends JpaRepository<Branch, UUID> {
        List<Branch> findByTenantId(UUID tenantId);
    }

    public interface CategoryRepository extends JpaRepository<ProductCategory, UUID> {
        Optional<ProductCategory> findByTenantIdAndId(UUID tenantId, UUID id);
        Optional<ProductCategory> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
        List<ProductCategory> findByTenantIdOrderByNameAsc(UUID tenantId);
    }

    public interface BrandRepository extends JpaRepository<Brand, UUID> {
        Optional<Brand> findByTenantIdAndId(UUID tenantId, UUID id);
        Optional<Brand> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
    }

    public interface UnitRepository extends JpaRepository<UnitOfMeasure, UUID> {
        Optional<UnitOfMeasure> findByTenantIdAndId(UUID tenantId, UUID id);
        Optional<UnitOfMeasure> findByTenantIdAndCodeIgnoreCase(UUID tenantId, String code);
        List<UnitOfMeasure> findByTenantIdOrderByCodeAsc(UUID tenantId);
    }

    public interface UnitConversionRepository extends JpaRepository<UnitConversion, UUID> {
        Optional<UnitConversion> findByTenantIdAndProductIdAndFromUnitIdAndToUnitId(UUID tenantId, UUID productId, UUID fromUnitId, UUID toUnitId);
    }

    public interface ProductRepository extends JpaRepository<Product, UUID> {
        Page<Product> findByTenantIdAndActiveTrue(UUID tenantId, Pageable pageable);
        Page<Product> findByTenantIdAndActiveTrueAndNameContainingIgnoreCase(UUID tenantId, String query, Pageable pageable);
        List<Product> findByTenantIdAndActiveTrueOrderByNameAsc(UUID tenantId);
        Optional<Product> findByTenantIdAndId(UUID tenantId, UUID id);
        Optional<Product> findByTenantIdAndSkuIgnoreCase(UUID tenantId, String sku);
        Optional<Product> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
        Optional<Product> findByTenantIdAndNormalizedNameIgnoreCaseAndBaseUnitId(UUID tenantId, String normalizedName, UUID baseUnitId);
        List<Product> findByTenantIdAndNormalizedNameIgnoreCase(UUID tenantId, String normalizedName);

        @Modifying
        @Query(value = "update stock_movements set product_id = :targetProductId where tenant_id = :tenantId and product_id = :sourceProductId", nativeQuery = true)
        void moveStockMovements(@Param("tenantId") UUID tenantId, @Param("sourceProductId") UUID sourceProductId, @Param("targetProductId") UUID targetProductId);

        @Modifying
        @Query(value = "update sales_invoice_items set product_id = :targetProductId where tenant_id = :tenantId and product_id = :sourceProductId", nativeQuery = true)
        void moveSalesInvoiceItems(@Param("tenantId") UUID tenantId, @Param("sourceProductId") UUID sourceProductId, @Param("targetProductId") UUID targetProductId);

        @Modifying
        @Query(value = "update purchase_invoice_items set product_id = :targetProductId where tenant_id = :tenantId and product_id = :sourceProductId", nativeQuery = true)
        void movePurchaseInvoiceItems(@Param("tenantId") UUID tenantId, @Param("sourceProductId") UUID sourceProductId, @Param("targetProductId") UUID targetProductId);
    }

    public interface ProductBarcodeRepository extends JpaRepository<ProductBarcode, UUID> {
        Optional<ProductBarcode> findByTenantIdAndBarcode(UUID tenantId, String barcode);
        List<ProductBarcode> findByTenantIdAndProductId(UUID tenantId, UUID productId);
    }

    public interface ProductBatchRepository extends JpaRepository<ProductBatch, UUID> {
    }

    public interface ProductTaxInfoRepository extends JpaRepository<ProductTaxInfo, UUID> {
    }

    public interface ReorderSettingRepository extends JpaRepository<ReorderSetting, UUID> {
        Optional<ReorderSetting> findByTenantIdAndProductIdAndWarehouseId(UUID tenantId, UUID productId, UUID warehouseId);
    }

    public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
        List<Warehouse> findByTenantId(UUID tenantId);
        List<Warehouse> findByTenantIdAndActiveTrueOrderByNameAsc(UUID tenantId);
        Optional<Warehouse> findByTenantIdAndId(UUID tenantId, UUID id);
        Optional<Warehouse> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
    }

    public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {
        Page<StockMovement> findByTenantId(UUID tenantId, Pageable pageable);
        List<StockMovement> findByTenantIdAndProductIdOrderByMovementDateDesc(UUID tenantId, UUID productId);
        List<StockMovement> findByTenantIdAndReferenceTypeAndReferenceId(UUID tenantId, String referenceType, UUID referenceId);
        Optional<StockMovement> findByTenantIdAndSourceFingerprint(UUID tenantId, String sourceFingerprint);
        List<StockMovement> findByTenantIdAndTransferPairFingerprint(UUID tenantId, String transferPairFingerprint);
        List<StockMovement> findByTenantIdAndTransferLineFingerprint(UUID tenantId, String transferLineFingerprint);
        List<StockMovement> findByTenantIdAndMovementTypeAndMovementDateAfter(UUID tenantId, DomainEnums.MovementType movementType, Instant after);
        Optional<StockMovement> findFirstByTenantIdAndProductIdAndMovementTypeOrderByMovementDateDesc(UUID tenantId, UUID productId, DomainEnums.MovementType movementType);

        @Query(value = """
            select coalesce(sum(base_quantity), 0)
            from stock_movements
            where tenant_id = :tenantId
              and product_id = :productId
              and (:warehouseId is null or warehouse_id = :warehouseId)
            """, nativeQuery = true)
        BigDecimal currentStock(@Param("tenantId") UUID tenantId, @Param("productId") UUID productId, @Param("warehouseId") UUID warehouseId);

        @Query(value = """
            select coalesce(sum(base_quantity), 0)
            from stock_movements
            where tenant_id = :tenantId
              and product_id = :productId
              and (:warehouseId is null or warehouse_id = :warehouseId)
              and movement_date <= :asOf
            """, nativeQuery = true)
        BigDecimal stockAt(@Param("tenantId") UUID tenantId, @Param("productId") UUID productId,
                           @Param("warehouseId") UUID warehouseId, @Param("asOf") Instant asOf);

    }

    public interface SupplierRepository extends JpaRepository<Supplier, UUID> {
        Page<Supplier> findByTenantId(UUID tenantId, Pageable pageable);
        List<Supplier> findByTenantIdOrderByNameAsc(UUID tenantId);
        Optional<Supplier> findByTenantIdAndId(UUID tenantId, UUID id);
        Optional<Supplier> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
        Optional<Supplier> findByTenantIdAndGstinIgnoreCase(UUID tenantId, String gstin);
        List<Supplier> findByTenantIdAndPhone(UUID tenantId, String phone);
        List<Supplier> findByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);
    }

    public interface CustomerRepository extends JpaRepository<Customer, UUID> {
        Page<Customer> findByTenantId(UUID tenantId, Pageable pageable);
        List<Customer> findByTenantIdOrderByNameAsc(UUID tenantId);
        Optional<Customer> findByTenantIdAndId(UUID tenantId, UUID id);
        Optional<Customer> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
        Optional<Customer> findByTenantIdAndGstinIgnoreCase(UUID tenantId, String gstin);
        List<Customer> findByTenantIdAndPhone(UUID tenantId, String phone);
        List<Customer> findByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);
    }

    public interface CustomerPriceListRepository extends JpaRepository<CustomerPriceList, UUID> {
        Optional<CustomerPriceList> findByTenantIdAndCustomerIdAndProductId(UUID tenantId, UUID customerId, UUID productId);
        List<CustomerPriceList> findByTenantIdAndCustomerId(UUID tenantId, UUID customerId);
    }

    public interface PurchaseInvoiceRepository extends JpaRepository<PurchaseInvoice, UUID> {
        Page<PurchaseInvoice> findByTenantId(UUID tenantId, Pageable pageable);
        List<PurchaseInvoice> findByTenantId(UUID tenantId);
        Optional<PurchaseInvoice> findByTenantIdAndId(UUID tenantId, UUID id);
        Optional<PurchaseInvoice> findByTenantIdAndInvoiceNumberIgnoreCase(UUID tenantId, String invoiceNumber);
        boolean existsByTenantIdAndInvoiceNumberIgnoreCase(UUID tenantId, String invoiceNumber);
        List<PurchaseInvoice> findByTenantIdAndSupplierId(UUID tenantId, UUID supplierId);
        List<PurchaseInvoice> findByTenantIdAndSupplierIdAndInvoiceDateAfter(UUID tenantId, UUID supplierId, LocalDate invoiceDate);
    }

    public interface PurchaseInvoiceItemRepository extends JpaRepository<PurchaseInvoiceItem, UUID> {
        List<PurchaseInvoiceItem> findByTenantIdAndPurchaseInvoiceId(UUID tenantId, UUID purchaseInvoiceId);
        List<PurchaseInvoiceItem> findByTenantId(UUID tenantId);
    }

    public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, UUID> {
        Optional<SupplierPayment> findByTenantIdAndSourceFingerprint(UUID tenantId, String sourceFingerprint);
        List<SupplierPayment> findByTenantIdAndSourceFingerprintIn(UUID tenantId, List<String> sourceFingerprints);
        List<SupplierPayment> findByTenantIdAndSupplierIdOrderByPaymentDateDesc(UUID tenantId, UUID supplierId);
        List<SupplierPayment> findByTenantIdAndSupplierIdAndPaymentDateAfter(UUID tenantId, UUID supplierId, LocalDate paymentDate);
        long countByTenantIdAndSourceImportSessionId(UUID tenantId, UUID sourceImportSessionId);
    }

    public interface SalesInvoiceRepository extends JpaRepository<SalesInvoice, UUID> {
        Page<SalesInvoice> findByTenantId(UUID tenantId, Pageable pageable);
        List<SalesInvoice> findByTenantId(UUID tenantId);
        Optional<SalesInvoice> findByTenantIdAndId(UUID tenantId, UUID id);
        Optional<SalesInvoice> findByTenantIdAndInvoiceNumberIgnoreCase(UUID tenantId, String invoiceNumber);
        boolean existsByTenantIdAndInvoiceNumberIgnoreCase(UUID tenantId, String invoiceNumber);
        List<SalesInvoice> findByTenantIdAndCustomerId(UUID tenantId, UUID customerId);
        List<SalesInvoice> findByTenantIdAndCustomerIdAndInvoiceDateAfter(UUID tenantId, UUID customerId, LocalDate invoiceDate);

        @Query(value = """
            select coalesce(sum(total_amount), 0)
            from sales_invoices
            where tenant_id = :tenantId and invoice_date between :fromDate and :toDate
            """, nativeQuery = true)
        BigDecimal salesTotal(@Param("tenantId") UUID tenantId, @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

        List<SalesInvoice> findByTenantIdAndInvoiceDateBetweenOrderByInvoiceDateAsc(UUID tenantId, LocalDate fromDate, LocalDate toDate);
    }

    public interface SalesInvoiceItemRepository extends JpaRepository<SalesInvoiceItem, UUID> {
        List<SalesInvoiceItem> findByTenantIdAndSalesInvoiceId(UUID tenantId, UUID salesInvoiceId);
        List<SalesInvoiceItem> findByTenantId(UUID tenantId);
    }

    public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {
        List<PurchaseOrder> findByTenantIdAndStatus(UUID tenantId, String status);
        List<PurchaseOrder> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    }

    public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, UUID> {
        List<PurchaseOrderItem> findByTenantIdAndPurchaseOrderId(UUID tenantId, UUID purchaseOrderId);

        @Query(value = """
            select coalesce(sum(poi.quantity), 0)
            from purchase_order_items poi
            join purchase_orders po on po.id = poi.purchase_order_id
            where poi.tenant_id = :tenantId and poi.product_id = :productId and po.status in ('OPEN', 'DRAFT')
            """, nativeQuery = true)
        BigDecimal pendingQuantity(@Param("tenantId") UUID tenantId, @Param("productId") UUID productId);
    }

    public interface SalesOrderRepository extends JpaRepository<SalesOrder, UUID> {
        List<SalesOrder> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
        List<SalesOrder> findByTenantIdAndCustomerIdOrderByCreatedAtDesc(UUID tenantId, UUID customerId);
        List<SalesOrder> findByTenantIdAndStatus(UUID tenantId, String status);
    }

    public interface SalesOrderItemRepository extends JpaRepository<SalesOrderItem, UUID> {
        List<SalesOrderItem> findByTenantIdAndSalesOrderId(UUID tenantId, UUID salesOrderId);

        @Query(value = """
            select coalesce(sum(soi.quantity), 0)
            from sales_order_items soi
            join sales_orders so on so.id = soi.sales_order_id
            where soi.tenant_id = :tenantId and soi.product_id = :productId and so.status in ('OPEN', 'DRAFT')
            """, nativeQuery = true)
        BigDecimal pendingQuantity(@Param("tenantId") UUID tenantId, @Param("productId") UUID productId);
    }

    public interface CustomerPaymentRepository extends JpaRepository<CustomerPayment, UUID> {
        @Query(value = "select coalesce(sum(amount), 0) from customer_payments where tenant_id = :tenantId", nativeQuery = true)
        BigDecimal totalPayments(@Param("tenantId") UUID tenantId);

        @Query(value = "select coalesce(sum(amount), 0) from customer_payments where tenant_id = :tenantId and customer_id = :customerId", nativeQuery = true)
        BigDecimal totalPaymentsForCustomer(@Param("tenantId") UUID tenantId, @Param("customerId") UUID customerId);

        List<CustomerPayment> findByTenantIdAndCustomerIdOrderByPaymentDateDesc(UUID tenantId, UUID customerId);
        List<CustomerPayment> findByTenantIdAndCustomerIdAndPaymentDateAfter(UUID tenantId, UUID customerId, LocalDate paymentDate);
        Optional<CustomerPayment> findByTenantIdAndSourceFingerprint(UUID tenantId, String sourceFingerprint);
        List<CustomerPayment> findByTenantIdAndSourceFingerprintIn(UUID tenantId, List<String> sourceFingerprints);
        long countByTenantIdAndSourceImportSessionId(UUID tenantId, UUID sourceImportSessionId);
    }

    public interface OutstandingSnapshotRepository extends JpaRepository<OutstandingSnapshot, UUID> {
        Optional<OutstandingSnapshot> findByTenantIdAndSourceFingerprint(UUID tenantId, String sourceFingerprint);
        Optional<OutstandingSnapshot> findFirstByTenantIdAndCustomerIdOrderBySnapshotDateDescCreatedAtDesc(UUID tenantId, UUID customerId);
        Optional<OutstandingSnapshot> findFirstByTenantIdAndSupplierIdOrderBySnapshotDateDescCreatedAtDesc(UUID tenantId, UUID supplierId);
        List<OutstandingSnapshot> findByTenantIdAndSourceImportSessionIdOrderBySourceRowNumberAsc(UUID tenantId, UUID sourceImportSessionId);
        long countByTenantIdAndSourceImportSessionId(UUID tenantId, UUID sourceImportSessionId);
    }

    public interface PaymentReminderRepository extends JpaRepository<PaymentReminder, UUID> {
        List<PaymentReminder> findByTenantIdOrderByReminderDateAscCreatedAtDesc(UUID tenantId);
        Optional<PaymentReminder> findByTenantIdAndId(UUID tenantId, UUID id);
        List<PaymentReminder> findByTenantIdAndCustomerIdAndStatusOrderByReminderDateAsc(UUID tenantId, UUID customerId, String status);
    }

    public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {
        Optional<ImportBatch> findFirstByTenantIdAndSourceTypeInAndStatusOrderByUpdatedAtDesc(
            UUID tenantId, List<DomainEnums.SourceType> sourceTypes, DomainEnums.ImportStatus status);
        Optional<ImportBatch> findByTenantIdAndId(UUID tenantId, UUID id);
        Page<ImportBatch> findByTenantId(UUID tenantId, Pageable pageable);
        Page<ImportBatch> findByTenantIdAndSourceTypeIn(UUID tenantId, List<DomainEnums.SourceType> sourceTypes, Pageable pageable);
        Page<ImportBatch> findByTenantIdAndSourceTypeInOrderByUpdatedAtDesc(UUID tenantId, List<DomainEnums.SourceType> sourceTypes, Pageable pageable);
        Optional<ImportBatch> findFirstByTenantIdAndImportPurposeAndStatusOrderByCommittedAtDesc(UUID tenantId, DomainEnums.ImportPurpose importPurpose, DomainEnums.ImportStatus status);
    }

    public interface FinancialAdjustmentRepository extends JpaRepository<FinancialAdjustment, UUID> {
        List<FinancialAdjustment> findByTenantIdOrderByVoucherDateDesc(UUID tenantId);
        Optional<FinancialAdjustment> findByTenantIdAndId(UUID tenantId, UUID id);
        Optional<FinancialAdjustment> findByTenantIdAndAdjustmentTypeAndVoucherNumberIgnoreCase(
            UUID tenantId, DomainEnums.FinancialAdjustmentType adjustmentType, String voucherNumber);
        Optional<FinancialAdjustment> findByTenantIdAndAdjustmentTypeAndSourceFingerprint(
            UUID tenantId, DomainEnums.FinancialAdjustmentType adjustmentType, String sourceFingerprint);
    }

    public interface FinancialAdjustmentItemRepository extends JpaRepository<FinancialAdjustmentItem, UUID> {
        List<FinancialAdjustmentItem> findByTenantIdAndFinancialAdjustmentId(UUID tenantId, UUID financialAdjustmentId);
        List<FinancialAdjustmentItem> findByTenantId(UUID tenantId);
    }

    public interface ImportFileRepository extends JpaRepository<ImportFile, UUID> {
        Optional<ImportFile> findFirstByTenantIdAndImportBatchId(UUID tenantId, UUID importBatchId);
    }

    public interface ImportSessionRepository extends JpaRepository<ImportSession, UUID> {
        Optional<ImportSession> findByTenantIdAndId(UUID tenantId, UUID id);
        Page<ImportSession> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
    }

    public interface ImportSessionFileRepository extends JpaRepository<ImportSessionFile, UUID> {
        List<ImportSessionFile> findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(UUID tenantId, UUID importSessionId);
        Optional<ImportSessionFile> findByTenantIdAndImportSessionIdAndId(UUID tenantId, UUID importSessionId, UUID id);
        Optional<ImportSessionFile> findFirstByTenantIdAndImportSessionIdAndFileHashOrderByCreatedAtAsc(UUID tenantId, UUID importSessionId, String fileHash);
    }

    public interface ImportPlanRepository extends JpaRepository<ImportPlan, UUID> {
        Optional<ImportPlan> findByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface ImportPlanIssueRepository extends JpaRepository<ImportPlanIssue, UUID> {
        List<ImportPlanIssue> findByTenantIdAndImportSessionIdOrderByCreatedAtAsc(UUID tenantId, UUID importSessionId);
        Optional<ImportPlanIssue> findByTenantIdAndImportSessionIdAndId(UUID tenantId, UUID importSessionId, UUID id);

        @Modifying
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface ImportDryRunRepository extends JpaRepository<ImportDryRun, UUID> {
        Optional<ImportDryRun> findFirstByTenantIdAndImportSessionIdOrderByStartedAtDesc(UUID tenantId, UUID importSessionId);
        Optional<ImportDryRun> findByTenantIdAndImportSessionIdAndId(UUID tenantId, UUID importSessionId, UUID id);
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface ImportDryRunItemRepository extends JpaRepository<ImportDryRunItem, UUID> {
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
        List<ImportDryRunItem> findByTenantIdAndDryRunIdOrderByCreatedAtAsc(UUID tenantId, UUID dryRunId);
        List<ImportDryRunItem> findByTenantIdAndDryRunIdAndItemTypeOrderByCreatedAtAsc(
            UUID tenantId, UUID dryRunId, DomainEnums.ImportDryRunItemType itemType);

        @Query("""
            select item from ImportDryRunItem item
            where item.tenantId = :tenantId
              and item.dryRunId = :dryRunId
              and (:itemType is null or item.itemType = :itemType)
              and (:action is null or item.action = :action)
              and (:sourceFileId is null or item.sourceFileId = :sourceFileId)
              and (:issueKind is null
                or (:issueKind = 'ERROR' and item.errorCode is not null)
                or (:issueKind = 'WARNING' and item.warningCode is not null)
                or (:issueKind = 'REVIEW_REQUIRED' and item.action = com.stockpilot.ai.domain.DomainEnums.ImportDryRunAction.REVIEW_REQUIRED))
            order by item.createdAt asc
            """)
        Page<ImportDryRunItem> search(
            @Param("tenantId") UUID tenantId,
            @Param("dryRunId") UUID dryRunId,
            @Param("itemType") DomainEnums.ImportDryRunItemType itemType,
            @Param("action") DomainEnums.ImportDryRunAction action,
            @Param("issueKind") String issueKind,
            @Param("sourceFileId") UUID sourceFileId,
            Pageable pageable
        );
    }

    public interface SmartImportCommitRepository extends JpaRepository<SmartImportCommit, UUID> {
        Optional<SmartImportCommit> findFirstByTenantIdAndStatusOrderByFinishedAtDesc(
            UUID tenantId, DomainEnums.SmartImportCommitStatus status);
        Optional<SmartImportCommit> findByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
        Optional<SmartImportCommit> findByTenantIdAndImportSessionIdAndId(UUID tenantId, UUID importSessionId, UUID id);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select commit from SmartImportCommit commit where commit.tenantId = :tenantId and commit.importSessionId = :sessionId")
        Optional<SmartImportCommit> findByTenantIdAndImportSessionIdForUpdate(
            @Param("tenantId") UUID tenantId, @Param("sessionId") UUID sessionId);
    }

    public interface SmartEffectCount {
        DomainEnums.SmartImportEffectEntityType getEntityType();
        long getTotal();
    }

    public interface SmartImportEffectRepository extends JpaRepository<SmartImportEffect, UUID> {
        @Query("""
            select effect.entityType as entityType, count(distinct effect.entityId) as total
            from SmartImportEffect effect
            where effect.tenantId = :tenantId and effect.commitId = :commitId
              and effect.reversedAt is null and effect.action in :actions
            group by effect.entityType
            """)
        List<SmartEffectCount> countEntitiesByTenantIdAndCommitId(@Param("tenantId") UUID tenantId,
            @Param("commitId") UUID commitId, @Param("actions") List<DomainEnums.SmartImportEffectAction> actions);
        List<SmartImportEffect> findByTenantIdAndCommitIdOrderByCreatedAtAsc(UUID tenantId, UUID commitId);
        void deleteByTenantIdAndCommitId(UUID tenantId, UUID commitId);
    }

    public interface ExternalRecordMappingRepository extends JpaRepository<ExternalRecordMapping, UUID> {
        Optional<ExternalRecordMapping> findFirstByTenantIdAndSourceSystemAndEntityTypeAndExternalId(
            UUID tenantId, String sourceSystem, String entityType, String externalId);
        Optional<ExternalRecordMapping> findByTenantIdAndSourceSystemAndEntityTypeAndNormalizedKey(
            UUID tenantId, String sourceSystem, String entityType, String normalizedKey);
    }

    public interface SmartStagedProductRepository extends JpaRepository<SmartStagedProduct, UUID> {
        List<SmartStagedProduct> findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(UUID tenantId, UUID importSessionId);
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface SmartStagedPartyRepository extends JpaRepository<SmartStagedParty, UUID> {
        List<SmartStagedParty> findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(UUID tenantId, UUID importSessionId);
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface SmartStagedWarehouseRepository extends JpaRepository<SmartStagedWarehouse, UUID> {
        List<SmartStagedWarehouse> findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(UUID tenantId, UUID importSessionId);
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface SmartStagedUnitRepository extends JpaRepository<SmartStagedUnit, UUID> {
        List<SmartStagedUnit> findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(UUID tenantId, UUID importSessionId);
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface SmartStagedStockSnapshotRepository extends JpaRepository<SmartStagedStockSnapshot, UUID> {
        List<SmartStagedStockSnapshot> findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(UUID tenantId, UUID importSessionId);
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface StockSnapshotBaselineRepository extends JpaRepository<StockSnapshotBaseline, UUID> {
        List<StockSnapshotBaseline> findByTenantIdAndProductIdOrderBySnapshotDateDescCreatedAtDesc(
            UUID tenantId, UUID productId);
        List<StockSnapshotBaseline> findByTenantIdAndSourceImportSessionId(
            UUID tenantId, UUID sourceImportSessionId);
    }

    public interface SmartStagedVoucherRepository extends JpaRepository<SmartStagedVoucher, UUID> {
        List<SmartStagedVoucher> findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(UUID tenantId, UUID importSessionId);
        Optional<SmartStagedVoucher> findByTenantIdAndImportSessionIdAndId(UUID tenantId, UUID importSessionId, UUID id);
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface SmartStagedVoucherItemRepository extends JpaRepository<SmartStagedVoucherItem, UUID> {
        List<SmartStagedVoucherItem> findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(UUID tenantId, UUID importSessionId);
        Optional<SmartStagedVoucherItem> findByTenantIdAndImportSessionIdAndId(UUID tenantId, UUID importSessionId, UUID id);
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select row from SmartStagedVoucherItem row where row.tenantId = :tenantId and row.importSessionId = :sessionId and row.id = :id")
        Optional<SmartStagedVoucherItem> findByTenantIdAndImportSessionIdAndIdForUpdate(
            @Param("tenantId") UUID tenantId, @Param("sessionId") UUID sessionId, @Param("id") UUID id);
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface SmartStagedTransferCandidateRepository extends JpaRepository<SmartStagedTransferCandidate, UUID> {
        List<SmartStagedTransferCandidate> findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(
            UUID tenantId, UUID importSessionId);
        List<SmartStagedTransferCandidate> findByTenantIdAndImportSessionIdAndResolutionStatusOrderByVoucherDateAscSourceRowNumberAsc(
            UUID tenantId, UUID importSessionId, DomainEnums.TransferResolutionStatus resolutionStatus);
        Optional<SmartStagedTransferCandidate> findByTenantIdAndImportSessionIdAndId(
            UUID tenantId, UUID importSessionId, UUID id);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("""
            select candidate from SmartStagedTransferCandidate candidate
            where candidate.tenantId = :tenantId
              and candidate.importSessionId = :sessionId
              and candidate.id = :id
            """)
        Optional<SmartStagedTransferCandidate> findByTenantIdAndImportSessionIdAndIdForUpdate(
            @Param("tenantId") UUID tenantId, @Param("sessionId") UUID sessionId, @Param("id") UUID id);
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface SmartStagedCashbookEntryRepository extends JpaRepository<SmartStagedCashbookEntry, UUID> {
        List<SmartStagedCashbookEntry> findByTenantIdAndImportSessionIdOrderByEntryDateAscSourceRowNumberAsc(UUID tenantId, UUID importSessionId);
        List<SmartStagedCashbookEntry> findByTenantIdAndImportSessionIdAndResolutionStatusOrderByEntryDateAscSourceRowNumberAsc(
            UUID tenantId, UUID importSessionId, DomainEnums.CashbookResolutionStatus resolutionStatus);
        Optional<SmartStagedCashbookEntry> findByTenantIdAndImportSessionIdAndId(UUID tenantId, UUID importSessionId, UUID id);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select row from SmartStagedCashbookEntry row where row.tenantId = :tenantId and row.importSessionId = :sessionId and row.id = :id")
        Optional<SmartStagedCashbookEntry> findByTenantIdAndImportSessionIdAndIdForUpdate(
            @Param("tenantId") UUID tenantId, @Param("sessionId") UUID sessionId, @Param("id") UUID id);
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface SmartStagedStockAgeingRepository extends JpaRepository<SmartStagedStockAgeing, UUID> {
        List<SmartStagedStockAgeing> findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(UUID tenantId, UUID importSessionId);
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface SmartImportResolutionRepository extends JpaRepository<SmartImportResolution, UUID> {
        List<SmartImportResolution> findByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
        Optional<SmartImportResolution> findByTenantIdAndImportSessionIdAndResolutionKey(UUID tenantId, UUID importSessionId, String resolutionKey);
        void deleteByTenantIdAndImportSessionId(UUID tenantId, UUID importSessionId);
    }

    public interface ImportMappingTemplateRepository extends JpaRepository<ImportMappingTemplate, UUID> {
        List<ImportMappingTemplate> findByTenantIdOrderByNameAsc(UUID tenantId);
    }

    public interface ImportErrorRepository extends JpaRepository<ImportError, UUID> {
        List<ImportError> findByTenantIdAndImportBatchIdOrderByRowNumberAsc(UUID tenantId, UUID importBatchId);

        @Modifying
        void deleteByTenantIdAndImportBatchId(UUID tenantId, UUID importBatchId);
    }

    public interface ImportEffectRepository extends JpaRepository<ImportEffect, UUID> {
        List<ImportEffect> findByTenantIdAndImportBatchIdOrderByCreatedAtAsc(UUID tenantId, UUID importBatchId);
        List<ImportEffect> findByTenantIdAndImportBatchIdAndEntityTypeAndActionOrderByCreatedAtAsc(UUID tenantId, UUID importBatchId, DomainEnums.ImportEffectEntityType entityType, DomainEnums.ImportEffectAction action);
        boolean existsByTenantIdAndImportBatchIdAndEntityTypeAndEntityIdAndAction(UUID tenantId, UUID importBatchId, DomainEnums.ImportEffectEntityType entityType, UUID entityId, DomainEnums.ImportEffectAction action);
    }

    public interface StagingProductRepository extends JpaRepository<StagingProduct, UUID> {
        List<StagingProduct> findByTenantIdAndImportBatchIdOrderByRowNumberAsc(UUID tenantId, UUID importBatchId);
        long countByTenantIdAndImportBatchIdAndCommittedTrue(UUID tenantId, UUID importBatchId);
    }

    public interface StagingCustomerRepository extends JpaRepository<StagingCustomer, UUID> {
        List<StagingCustomer> findByTenantIdAndImportBatchIdOrderByRowNumberAsc(UUID tenantId, UUID importBatchId);
        long countByTenantIdAndImportBatchIdAndCommittedTrue(UUID tenantId, UUID importBatchId);
    }

    public interface StagingSupplierRepository extends JpaRepository<StagingSupplier, UUID> {
        List<StagingSupplier> findByTenantIdAndImportBatchIdOrderByRowNumberAsc(UUID tenantId, UUID importBatchId);
        long countByTenantIdAndImportBatchIdAndCommittedTrue(UUID tenantId, UUID importBatchId);
    }

    public interface StagingInvoiceRepository extends JpaRepository<StagingInvoice, UUID> {
    }

    public interface StagingStockMovementRepository extends JpaRepository<StagingStockMovement, UUID> {
        List<StagingStockMovement> findByTenantIdAndImportBatchIdOrderByRowNumberAsc(UUID tenantId, UUID importBatchId);
        long countByTenantIdAndImportBatchIdAndMovementTypeAndCommittedTrue(UUID tenantId, UUID importBatchId, String movementType);
    }

    public interface ForecastResultRepository extends JpaRepository<ForecastResult, UUID> {
        List<ForecastResult> findByTenantIdOrderByGeneratedAtDesc(UUID tenantId);

        @Modifying
        void deleteByTenantId(UUID tenantId);
    }

    public interface ReorderSuggestionRepository extends JpaRepository<ReorderSuggestion, UUID> {
        List<ReorderSuggestion> findByTenantIdOrderByGeneratedAtDesc(UUID tenantId);

        @Modifying
        void deleteByTenantId(UUID tenantId);
    }

    public interface DeadStockInsightRepository extends JpaRepository<DeadStockInsight, UUID> {
        List<DeadStockInsight> findByTenantIdOrderByStockValueDesc(UUID tenantId);

        @Modifying
        void deleteByTenantId(UUID tenantId);
    }

    public interface ProfitInsightRepository extends JpaRepository<ProfitInsight, UUID> {
        List<ProfitInsight> findByTenantIdOrderByGeneratedAtDesc(UUID tenantId);
    }

    public interface AiConversationRepository extends JpaRepository<AiConversation, UUID> {
        Optional<AiConversation> findByTenantIdAndId(UUID tenantId, UUID id);
    }

    public interface AiMessageRepository extends JpaRepository<AiMessage, UUID> {
    }

    public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
        Page<AuditLog> findByTenantId(UUID tenantId, Pageable pageable);
        List<AuditLog> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    }
}
