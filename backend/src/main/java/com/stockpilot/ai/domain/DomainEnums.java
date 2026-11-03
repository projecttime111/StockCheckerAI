package com.stockpilot.ai.domain;

public final class DomainEnums {
    private DomainEnums() {
    }

    public enum BusinessMode {
        RETAIL, WHOLESALE, HYBRID
    }

    public enum TenantStatus {
        ACTIVE, DELETED
    }

    public enum Role {
        OWNER,
        ADMIN,
        MANAGER,
        STAFF,
        WAREHOUSE_STAFF,
        SALES_STAFF,
        PURCHASE_MANAGER,
        ACCOUNTANT,
        VIEWER,
        AUDITOR,
        CUSTOMER_USER,
        SUPPLIER_USER,
        PLATFORM_SUPER_ADMIN,
        PLATFORM_SUPPORT,
        PLATFORM_BILLING_ADMIN
    }

    public enum MovementType {
        PURCHASE, SALE, RETURN_IN, RETURN_OUT, TRANSFER_IN, TRANSFER_OUT, ADJUSTMENT, OPENING_BALANCE
    }

    public enum SourceType {
        TALLY, EXCEL, CSV, JSON, XML, OTHER_ERP, TALLY_EXCEL, TALLY_XML
    }

    public enum ImportStatus {
        UPLOADED, PARSED, MAPPED, VALIDATED, VALIDATION_FAILED, COMMITTING, COMMITTED, ROLLED_BACK, FAILED
    }

    public enum ImportPurpose {
        OPENING_BALANCE, STOCK_SNAPSHOT, TRANSACTION_IMPORT, MASTER_IMPORT
    }

    public enum ImportSessionStatus {
        CREATED,
        FILES_UPLOADED,
        CLASSIFIED,
        STAGED,
        PLAN_READY,
        NEEDS_REVIEW,
        COMMITTING,
        COMMITTED,
        FAILED,
        CANCELLED
    }

    public enum ImportStrategy {
        SNAPSHOT_FIRST, TRANSACTION_HISTORY, HYBRID_RECONCILIATION, UNKNOWN
    }

    public enum DetectedFileType {
        INVENTORY_MASTER,
        ACCOUNTING_MASTER,
        MIXED_TALLY_MASTERS,
        STOCK_SNAPSHOT,
        STOCK_AGEING,
        SALES_VOUCHERS,
        PURCHASE_VOUCHERS,
        CREDIT_NOTES,
        DEBIT_NOTES,
        MIXED_TALLY_VOUCHERS,
        CASH_BOOK,
        DEBTOR_CREDITOR_ANALYSIS,
        STOCK_JOURNAL,
        UNKNOWN
    }

    public enum ImportSessionFileStatus {
        UPLOADED, DUPLICATE, CLASSIFIED, FAILED, CANCELLED
    }

    public enum ImportPlanStatus {
        DRAFT, NEEDS_REVIEW, READY, CANCELLED
    }

    public enum ImportDryRunStatus {
        RUNNING, COMPLETED, FAILED
    }

    public enum ImportDryRunItemType {
        FILE,
        PRODUCT,
        CUSTOMER,
        SUPPLIER,
        WAREHOUSE,
        UNIT,
        STOCK_SNAPSHOT,
        STOCK_MOVEMENT,
        SALES_INVOICE,
        PURCHASE_INVOICE,
        CREDIT_NOTE,
        DEBIT_NOTE,
        CASHBOOK_ENTRY,
        OUTSTANDING_SNAPSHOT,
        STOCK_JOURNAL,
        WAREHOUSE_TRANSFER,
        STOCK_AGEING
    }

    public enum ImportDryRunAction {
        CREATE, MATCH_EXISTING, UPDATE, SKIP, REVIEW_REQUIRED, BLOCKED, NO_CHANGE
    }

    public enum SmartImportCommitStatus {
        COMMITTING, COMMITTED, FAILED, ROLLED_BACK
    }

    public enum SmartImportEffectEntityType {
        PRODUCT, CUSTOMER, SUPPLIER, WAREHOUSE, UNIT, CATEGORY, STOCK_MOVEMENT,
        SALES_INVOICE, SALES_INVOICE_ITEM, PURCHASE_INVOICE, PURCHASE_INVOICE_ITEM,
        CREDIT_NOTE, CREDIT_NOTE_ITEM, DEBIT_NOTE, DEBIT_NOTE_ITEM,
        CUSTOMER_PAYMENT, SUPPLIER_PAYMENT, OUTSTANDING_SNAPSHOT, WAREHOUSE_TRANSFER, IMPORT_MAPPING
    }

    public enum FinancialAdjustmentType {
        CREDIT_NOTE, DEBIT_NOTE
    }

    public enum ReturnClassification {
        FINANCIAL_ONLY,
        PHYSICAL_SALES_RETURN,
        PHYSICAL_PURCHASE_RETURN,
        MIXED_SALES_RETURN,
        MIXED_PURCHASE_RETURN,
        AMBIGUOUS_RETURN,
        NOT_A_RETURN
    }

    public enum ReturnResolutionStatus {
        AUTO_CONFIRMED_PHYSICAL,
        USER_CONFIRMED_PHYSICAL,
        USER_CONFIRMED_FINANCIAL_ONLY,
        REVIEW_REQUIRED,
        STOCK_EFFECT_DEFERRED,
        STOCK_EFFECT_COMMITTED
    }

    public enum ReturnDirection {
        SALES_RETURN_IN, PURCHASE_RETURN_OUT, NONE
    }

    public enum ReturnDisposition {
        RESTOCK_TO_AVAILABLE, FINANCIAL_ONLY, REVIEW_REQUIRED
    }

    public enum ReturnResolutionAction {
        CONFIRM_PHYSICAL_SALES_RETURN,
        CONFIRM_PHYSICAL_PURCHASE_RETURN,
        CONFIRM_FINANCIAL_ONLY,
        KEEP_UNRESOLVED
    }

    public enum ReturnStockImpactMode {
        FINANCIAL_ONLY,
        APPLY_PHYSICAL_RETURNS_AFTER_SNAPSHOT_DATE
    }

    public enum StockJournalClassification {
        WAREHOUSE_TRANSFER,
        STOCK_ADJUSTMENT_IN,
        STOCK_ADJUSTMENT_OUT,
        MANUFACTURING_CONSUMPTION,
        MANUFACTURING_PRODUCTION,
        MIXED_MANUFACTURING_JOURNAL,
        UNBALANCED_STOCK_JOURNAL,
        AMBIGUOUS_STOCK_JOURNAL,
        ZERO_EFFECT_STOCK_JOURNAL,
        UNSUPPORTED_STOCK_JOURNAL
    }

    public enum TransferResolutionStatus {
        AUTO_CONFIRMED,
        USER_CONFIRMED,
        REVIEW_REQUIRED,
        DEFERRED_MANUFACTURING,
        DEFERRED_ADJUSTMENT,
        IGNORED,
        STOCK_EFFECT_COMMITTED
    }

    public enum TransferResolutionAction {
        CONFIRM_WAREHOUSE_TRANSFER,
        MARK_MANUFACTURING_DEFERRED,
        MARK_UNSUPPORTED_ADJUSTMENT,
        IGNORE,
        KEEP_UNRESOLVED
    }

    public enum StockJournalImpactMode {
        DEFER_STOCK_JOURNALS,
        APPLY_CONFIRMED_WAREHOUSE_TRANSFERS_AFTER_SNAPSHOT_DATE
    }

    public enum SmartImportEffectAction {
        CREATED, UPDATED, MATCHED_EXISTING, SKIPPED, REVERSED
    }

    public enum ImportPlanIssueSeverity {
        INFO, WARNING, ERROR, REVIEW_REQUIRED
    }

    public enum SmartMatchStatus {
        CREATE_NEW, MATCH_EXISTING, POSSIBLE_DUPLICATE_REVIEW, AMBIGUOUS_REVIEW, SKIP_DUPLICATE, UNKNOWN
    }

    public enum SmartReviewStatus {
        PENDING, AUTO_RESOLVED, RESOLVED, IGNORED
    }

    public enum SmartPartyType {
        CUSTOMER, SUPPLIER, UNKNOWN
    }

    public enum SmartCashbookDirection {
        RECEIPT, PAYMENT, UNKNOWN
    }

    public enum CashbookMatchStatus {
        MATCHED_CUSTOMER_PAYMENT,
        MATCHED_SUPPLIER_PAYMENT,
        MATCHED_SALES_INVOICE,
        MATCHED_PURCHASE_INVOICE,
        UNMATCHED_REVIEW,
        LOW_CONFIDENCE_REVIEW
    }

    public enum CashbookResolutionStatus {
        UNRESOLVED, PAYMENT_POSTED, IGNORED, RETAIL_SALE_RECORDED
    }

    public enum CashbookResolutionAction {
        MAP_CUSTOMER,
        MAP_SUPPLIER,
        MAP_SALES_INVOICE,
        MAP_PURCHASE_INVOICE,
        RECORD_RETAIL_SALE,
        IGNORE,
        KEEP_UNRESOLVED
    }

    public enum VoucherRateResolutionAction {
        CONFIRM_ZERO_VALUE,
        DERIVE_RATE,
        TREAT_AS_FINANCIAL_ADJUSTMENT,
        ENTER_VERIFIED_RATE,
        EXCLUDE_VOUCHER,
        KEEP_UNRESOLVED
    }

    public enum PaymentMode {
        CASH, BANK, UPI, CHEQUE, OTHER
    }

    public enum RetailCollectionMethod {
        CASH, UPI, CASH_AND_UPI
    }

    public enum OutstandingPartyType {
        CUSTOMER, SUPPLIER
    }

    public enum SmartSnapshotAction {
        CREATE_NEW_PRODUCT, MATCH_EXISTING_PRODUCT, CREATE_SNAPSHOT_ADJUSTMENT, NO_CHANGE, REVIEW_REQUIRED
    }

    public enum SmartVoucherStockImpactSuggestion {
        CREATE_INVOICES_ONLY, CREATE_INVOICES_AND_STOCK_MOVEMENTS, APPLY_AFTER_SNAPSHOT_DATE
    }

    public enum ImportEffectEntityType {
        PRODUCT,
        CUSTOMER,
        SUPPLIER,
        SALES_INVOICE,
        PURCHASE_INVOICE,
        STOCK_MOVEMENT,
        WAREHOUSE,
        CATEGORY,
        UNIT
    }

    public enum ImportEffectAction {
        CREATED, UPDATED, SKIPPED, REVERSED
    }

    public enum ImportUndoStrategy {
        SAFE_REVERSAL, DEV_HARD_DELETE
    }

    public enum NegativeStockImportPolicy {
        BLOCK, IMPORT_AS_IS, SKIP_STOCK_MOVEMENT
    }

    public enum VoucherStockImpactMode {
        CREATE_INVOICES_AND_STOCK_MOVEMENTS,
        CREATE_INVOICES_ONLY,
        APPLY_STOCK_MOVEMENTS_AFTER_SNAPSHOT_DATE,
        BLOCK_IF_SNAPSHOT_EXISTS
    }

    public enum AiRole {
        USER, ASSISTANT, SYSTEM
    }
}
