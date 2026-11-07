type Init = RequestInit;
type DemoMovement = { id: string; productId: string; warehouseId: string; quantityDelta: number; rate: number; movementType: string; movementDate: string };
type DemoLine = { productId: string; quantity: number; rate: number; costRate: number; lineTotal: number; taxAmount: number };
type DemoInvoice = { id: string; invoiceNumber: string; invoiceDate: string; subtotal: number; taxAmount: number; totalAmount: number; warehouseId?: string; customerId?: string; supplierId?: string; items?: DemoLine[] };
type DemoOrder = { orderId: string; orderNumber: string; status: string; totalAmount: number; customerId: string; items: { productId: string; quantity: number; price: number }[] };
let portalOrders: DemoOrder[] = [];
let draftPurchaseOrders: { purchaseOrderId: string; orderNumber: string; totalAmount: number; itemCount: number }[] = [];
let adjustmentResults: Record<string, DemoMovement> = {};
let paymentResults: Record<string, { id: string; customerId: string; amount: number; paymentDate: string; notes: string }> = {};


let products = seedProducts();
function seedProducts() {
  return [
  product("p1", "MAGGI70", "Maggi Masala Noodles 70g", "Instant Noodles", "Maggi", "PCS", "8901058844505", 8.5, 12, 120, 94),
  product("p2", "PARLEG250", "Parle-G 250g", "Biscuits", "Parle", "PCS", "8901719101018", 19, 25, 80, 18),
  product("p3", "DM50", "Dairy Milk 50g", "Confectionery", "Dairy Milk", "PCS", "7622201145213", 32, 45, 45, 38),
  product("p4", "SURF1KG", "Surf Excel 1kg", "Laundry", "Surf Excel", "PCS", "8901030865476", 124, 145, 25, 21),
  product("p5", "TATASALT1", "Tata Salt 1kg", "Staples", "Tata", "PCS", "8904043901015", 18, 24, 70, 124),
  product("p6", "DETTOL75", "Dettol Soap 75g", "Personal Care", "Dettol", "PCS", "8901396311829", 27, 36, 55, 42),
  product("p7", "FORTOIL1", "Fortune Oil 1L", "Edible Oil", "Fortune", "PCS", "8906007280012", 112, 135, 35, 26),
  product("p8", "COL100", "Colgate Toothpaste 100g", "Oral Care", "Colgate", "PCS", "8901314011759", 44, 58, 35, 31),
  product("p9", "DQ-MAGGI-70-A", "MAGGI 70GM", undefined, undefined, "PCS", undefined, 0, 0, 10, 24),
  product("p10", "DQ-MAGGI-70-B", "MAGGI MASLA 70", undefined, undefined, "PCS", undefined, 0, 0, 10, 24),
  product("p11", "DQ-MAGGI-70-C", "MAGGI NOODLES 70GM", undefined, undefined, "PCS", undefined, 0, 0, 10, 24),
  product("p12", "DQ-NOCOST-SALES", "Imported Promo Pack 100g", "Imported Uncategorized", undefined, "PCS", undefined, 0, 49, 10, 37)
  ];
}

let warehouses = seedWarehouses();
function seedWarehouses() {
  return [
  { id: "w1", name: "Main Godown", code: "MAIN", address: "Industrial Area" },
  { id: "w2", name: "Retail Counter", code: "RETAIL", address: "Front shop" }
  ];
}

let stockMovements: DemoMovement[] = seedStockMovements(products, warehouses);

let demoTenant = seedTenant();
function seedTenant() {
  return {
  id: "tenant-demo",
  name: "Demo FMCG Distributor",
  businessMode: "HYBRID" as "RETAIL" | "WHOLESALE" | "HYBRID",
  currency: "INR",
  gstEnabled: true,
  allowNegativeStock: false,
  portalShowAllActiveProducts: false
};
}

let customers = seedCustomers();
function seedCustomers() {
  return [
  { id: "c1", name: "Ravi Traders", phone: "9876543210", email: "ravi@example.com", gstin: "27RAVI1234F1Z5", creditLimit: 45000, outstanding: 23850 },
  { id: "c2", name: "Kumar Retail Store", phone: "9876543211", email: "kumar@example.com", gstin: "27KUMA1234F1Z5", creditLimit: 25000, outstanding: 12800 },
  { id: "c3", name: "City Supermart", phone: "9876543212", email: "city@example.com", gstin: "27CITY1234F1Z5", creditLimit: 100000, outstanding: 62400 },
  { id: "c4", name: "Balaji Kirana", phone: "9876543213", email: "balaji@example.com", gstin: "27BALA1234F1Z5", creditLimit: 30000, outstanding: 9500 }
  ];
}

let paymentReminders = seedPaymentReminders();
function seedPaymentReminders() {
  return [
  { id: "rem-1", customerId: "c3", amountDue: 32000, reminderDate: today(-1), reminderTime: "11:00", status: "OPEN", notes: "Call City Supermart accounts team for pending invoice follow-up." },
  { id: "rem-2", customerId: "c1", amountDue: 12000, reminderDate: today(), reminderTime: "16:00", status: "OPEN", notes: "Ravi Traders promised UPI payment today." },
  { id: "rem-3", customerId: "c2", amountDue: 8000, reminderDate: today(3), reminderTime: "10:30", status: "OPEN", notes: "Follow up after weekly delivery." }
  ];
}

let suppliers = seedSuppliers();
function seedSuppliers() {
  return [
  { id: "s1", name: "ABC FMCG Supplier", phone: "9000000001", email: "abc@example.com", gstin: "27ABCF1234F1Z5", creditDays: 14, payable: 0 },
  { id: "s2", name: "National Distributors", phone: "9000000002", email: "national@example.com", gstin: "27NATD1234F1Z5", creditDays: 21, payable: 0 },
  { id: "s3", name: "Metro Wholesale", phone: "9000000003", email: "metro@example.com", gstin: "27METR1234F1Z5", creditDays: 7, payable: 0 }
  ];
}

let salesInvoices = seedSalesInvoices();
function seedSalesInvoices() {
  return [
  invoice("sale1", "SAL-DEMO-001", 18450),
  invoice("sale2", "SAL-DEMO-002", 21680),
  invoice("sale3", "SAL-DEMO-003", 12890)
  ];
}

let purchaseInvoices = seedPurchaseInvoices();
function seedPurchaseInvoices() {
  return [
  invoice("pur1", "PUR-DEMO-001", 48200),
  invoice("pur2", "PUR-DEMO-002", 61340),
  invoice("pur3", "PUR-DEMO-003", 37850)
  ];
}

const importBatchId = "demo-tally-batch";
const smartImportSessionId = "demo-smart-import-session";
let smartDryRunAvailable = false;
let smartCommitResult: ReturnType<typeof smartDemoCommit> | null = null;
type DemoSmartImportSession = Omit<ReturnType<typeof seedSmartImportSession>, "plan"> & {
  plan: ReturnType<typeof smartDemoPlan> | Record<string, never>;
};
let smartImportSession: DemoSmartImportSession = seedSmartImportSession();

function seedSmartImportSession() {
  return {
    id: smartImportSessionId,
    name: "Demo multi-file Tally import",
    status: "PLAN_READY",
    recommendedStrategy: "HYBRID_RECONCILIATION",
    createdAt: new Date().toISOString(),
    files: [
      smartFile("smart-stock", "closing-stock.xml", "STOCK_SNAPSHOT", 0.99, "Tally closing-stock tags were found.", 12),
      smartFile("smart-sales", "sales.xml", "SALES_VOUCHERS", 0.95, "Sales voucher evidence was found.", 38),
      smartFile("smart-purchase", "purchase.xml", "PURCHASE_VOUCHERS", 0.95, "Purchase voucher evidence was found.", 24)
    ],
    issues: [
      { id: "smart-issue-1", severity: "WARNING", code: "TRANSACTION_FILES_WITH_SNAPSHOT", message: "Transaction files and a stock snapshot were uploaded together.", affectedFileId: "", suggestedAction: "Use hybrid reconciliation so stock is not double-counted." }
    ],
    plan: smartDemoPlan()
  };
}

function smartFile(id: string, originalFileName: string, detectedFileType: string, confidence: number, detectionReason: string, rowCount: number) {
  return { id, originalFileName, detectedFileType, selectedFileType: "", confidence, detectionReason, status: "CLASSIFIED", rowCount, errorCount: 0, warningCount: 0, dateRangeStart: "2026-04-01", dateRangeEnd: "2026-06-30", companyName: "Demo FMCG Distributor", metadata: {} };
}

function smartDemoPlan() {
  const names = ["Units", "Warehouses / godowns", "Products", "Customers / suppliers / parties", "Stock snapshot", "Purchases", "Sales", "Credit / debit notes", "Cashbook", "Stock ageing", "Reconciliation"];
  return {
    id: "demo-smart-plan",
    strategy: "HYBRID_RECONCILIATION",
    status: "READY",
    details: {
      explanation: "Both stock snapshot and transaction files were detected. StockPilot will use snapshot reconciliation to prevent double-counting.",
      dependencyNote: "Files may be uploaded in any order. StockPilot builds the safe internal sequence.",
      commitEnabled: false,
      stagingComplete: true,
      filesConsidered: 3,
      duplicateFilesSkipped: 0,
      steps: names.map((name, index) => ({ order: index + 1, name, purpose: `Prepare ${name.toLowerCase()} for the future commit phase`, status: "PLANNED" }))
    }
  };
}

function smartDemoDryRun() {
  return {
    available: smartDryRunAvailable,
    id: "demo-smart-dry-run",
    importSessionId: smartImportSessionId,
    status: smartDryRunAvailable ? "COMPLETED" : "NOT_RUN",
    strategy: "HYBRID_RECONCILIATION",
    startedAt: new Date().toISOString(),
    finishedAt: new Date().toISOString(),
    summary: {
      productsToCreate: 1, productsMatchedExisting: 3, purchaseInvoicesPreviewed: 1, salesInvoicesPreviewed: 1,
      creditNotesPreviewed: 1, debitNotesPreviewed: 1, snapshotAdjustmentsPreviewed: 1, stockMovementsPreviewed: 1,
      stockMovementsSkippedDueToInvoiceOnly: 2, stockMovementsSkippedBeforeSnapshotDate: 0,
      voucherItemsReady: 4, vouchersBlocked: 0, duplicateVouchersSkipped: 0,
      taxLinesCaptured: 3, discountLinesCaptured: 1, freightLinesCaptured: 2,
      roundOffLinesCaptured: 2, otherChargeLinesCaptured: 0,
      warnings: 2, blockingErrors: 0, reviewRequiredCount: 0,
      stockImpactMode: "CREATE_INVOICES_ONLY", dryRunOnly: true, finalBusinessWrites: 0
    }
  };
}

function smartDemoCommit() {
  return {
    available: true,
    id: "demo-smart-commit",
    importSessionId: smartImportSessionId,
    dryRunId: "demo-smart-dry-run",
    status: "COMMITTED",
    strategy: "HYBRID_RECONCILIATION",
    startedAt: new Date().toISOString(),
    finishedAt: new Date().toISOString(),
    committedAt: new Date().toISOString(),
    committedBy: "demo-owner",
    idempotentRetry: false,
    summary: {
      productsCreated: 1,
      productsMatchedExisting: 3,
      productsUpdated: 1,
      partiesCreated: 1,
      partiesMatchedExisting: 1,
      warehousesCreated: 1,
      warehousesMatchedExisting: 1,
      unitsCreated: 0,
      snapshotRowsProcessed: 2,
      snapshotMovementsCreated: 1,
      snapshotRowsNoChange: 1,
      negativeStockRowsFound: 0,
      negativeStockRowsSkipped: 0,
      salesInvoicesCreated: 1,
      salesInvoiceItemsCreated: 1,
      purchaseInvoicesCreated: 1,
      purchaseInvoiceItemsCreated: 1,
      duplicateSalesVouchersSkipped: 0,
      duplicatePurchaseVouchersSkipped: 0,
      creditNotesCreated: 1,
      creditNoteItemsCreated: 1,
      debitNotesCreated: 1,
      debitNoteItemsCreated: 1,
      duplicateCreditNotesSkipped: 0,
      duplicateDebitNotesSkipped: 0,
      taxLinesCaptured: 3,
      discountLinesCaptured: 1,
      freightLinesCaptured: 2,
      roundOffLinesCaptured: 2,
      otherChargeLinesCaptured: 0,
      voucherStockMovementsCreated: 0,
      stockMovementsCreatedFromReturns: 0,
      returnStockMovementsDeferred: 2,
      stockMovementsSkippedDueToInvoiceOnly: 2,
      vouchersDeferredForFuturePhase: 0,
      futurePhaseVoucherRowsSkipped: 0,
      futurePhaseCashbookRowsSkipped: 1,
      futurePhaseAgeingRowsSkipped: 2,
      warnings: 0,
      blockingErrors: 0,
      phase: "4B-2A",
      invoiceOnlyMessage: "Sales/Purchase vouchers were imported as invoice-only. Stock was not changed because this phase uses invoice-only posting.",
      financialAdjustmentMessage: "Credit and debit notes were imported as financial adjustments. Return stock movements remain deferred.",
      futurePhaseMessage: "Cashbook matching, stock ageing, and stock-affecting voucher posting remain deferred."
    }
  };
}

function smartDemoDryRunItems() {
  return [
    { id: "dry-product", itemType: "PRODUCT", action: "MATCH_EXISTING", sourceRowNumber: 1, preview: { name: "Maggi Masala Noodles 70g" }, warningCode: "", errorCode: "" },
    { id: "dry-snapshot", itemType: "STOCK_SNAPSHOT", action: "CREATE", sourceRowNumber: 1, preview: { productName: "Maggi Masala Noodles 70g", snapshotDate: "2026-06-30", stockAtSnapshotDate: 94, importedSnapshotQuantity: 100, deltaAtSnapshotDate: 6, projectedCurrentStockAfterApplyingLaterTransactions: 100 }, warningCode: "", errorCode: "" },
    { id: "dry-voucher-before", itemType: "STOCK_MOVEMENT", action: "SKIP", sourceRowNumber: 1, preview: { voucherNumber: "SAL-1001", productName: "Maggi Masala Noodles 70g", reason: "Skipped to avoid double-counting stock before the snapshot date." }, warningCode: "STOCK_MOVEMENT_SKIPPED_BEFORE_SNAPSHOT", errorCode: "" }
  ];
}

function smartDemoWorkspace() {
  const reviewItems = smartImportSession.issues.map((issue) => ({
    ...issue,
    affectedFile: issue.affectedFileId ? smartImportSession.files.find((file) => file.id === issue.affectedFileId)?.originalFileName : "Session",
    affectedRows: [],
    resolved: false,
    availableChoices: issue.code === "UNKNOWN_WAREHOUSE"
      ? [
          { action: "MAP_WAREHOUSE", label: "Map to Main Godown", targetId: "w1" },
          { action: "CREATE_WAREHOUSE_LATER", label: "Create this warehouse later" }
        ]
      : [{ action: "IGNORE_WARNING", label: "Acknowledge for this planning session" }]
  }));
  return {
    summary: {
      productsStaged: 5,
      partiesStaged: 2,
      warehousesStaged: 2,
      unitsStaged: 1,
      stockSnapshotsStaged: 2,
      vouchersStaged: 2,
      voucherItemsStaged: 4,
      creditNotesStaged: 1,
      debitNotesStaged: 1,
      ledgerAdjustmentLinesStaged: 8,
      cashbookEntriesStaged: 1,
      stockAgeingRowsStaged: 2
    },
    matching: {
      productsToCreate: 1,
      productsMatchedExisting: 3,
      possibleDuplicateProducts: 1,
      partiesToCreate: 1,
      partiesMatchedExisting: 1,
      warehousesToCreateOrMap: 1,
      warehousesMatchedExisting: 1,
      vouchersLikelyDuplicates: 0
    },
    products: [
      { id: "sp-1", row: 1, name: "Maggi Masala Noodles 70g", unitCode: "PCS", sku: "MAGGI70", matchStatus: "MATCH_EXISTING", reviewStatus: "AUTO_RESOLVED" },
      { id: "sp-2", row: 2, name: "MAGGI MASLA 70", unitCode: "PCS", matchStatus: "POSSIBLE_DUPLICATE_REVIEW", reviewStatus: "PENDING" },
      { id: "sp-3", row: 3, name: "New Sample Product", unitCode: "PCS", matchStatus: "CREATE_NEW", reviewStatus: "AUTO_RESOLVED" }
    ],
    parties: [
      { id: "party-1", row: 1, name: "Ravi Traders", partyType: "CUSTOMER", gstin: "27RAVI1234F1Z5", matchStatus: "MATCH_EXISTING", reviewStatus: "AUTO_RESOLVED" },
      { id: "party-2", row: 2, name: "New Wholesale Party", partyType: "SUPPLIER", matchStatus: "CREATE_NEW", reviewStatus: "AUTO_RESOLVED" }
    ],
    warehouses: [
      { id: "sw-1", name: "Main Godown", matchStatus: "MATCH_EXISTING", reviewStatus: "AUTO_RESOLVED" },
      { id: "sw-2", name: "Branch Store", matchStatus: "CREATE_NEW", reviewStatus: "PENDING" }
    ],
    units: [{ id: "su-1", code: "PCS", matchStatus: "MATCH_EXISTING" }],
    snapshots: [
      { id: "ss-1", row: 1, productName: "Maggi Masala Noodles 70g", unitCode: "PCS", warehouseName: "Main Godown", currentStock: 94, importedStock: 100, deltaPreview: 6, matchStatus: "MATCH_EXISTING", reviewStatus: "AUTO_RESOLVED", action: "CREATE_SNAPSHOT_ADJUSTMENT" },
      { id: "ss-2", row: 2, productName: "Parle-G 250g", unitCode: "PCS", warehouseName: "Main Godown", currentStock: 18, importedStock: 18, deltaPreview: 0, matchStatus: "MATCH_EXISTING", reviewStatus: "AUTO_RESOLVED", action: "NO_CHANGE" }
    ],
    vouchers: [
      { id: "sv-1", row: 1, voucherType: "SALES", voucherNumber: "SAL-1001", voucherDate: "2026-06-28", partyName: "Ravi Traders", totalAmount: 18450, matchStatus: "CREATE_NEW", reviewStatus: "AUTO_RESOLVED", stockImpactModeSuggestion: "CREATE_INVOICES_ONLY" },
      { id: "sv-2", row: 1, voucherType: "PURCHASE", voucherNumber: "PUR-1001", voucherDate: "2026-06-20", partyName: "ABC FMCG Supplier", totalAmount: 48200, matchStatus: "CREATE_NEW", reviewStatus: "AUTO_RESOLVED", stockImpactModeSuggestion: "CREATE_INVOICES_ONLY" }
    ],
    voucherItems: [{ id: "svi-1" }, { id: "svi-2" }],
    cashbookEntries: [{ id: "cash-1" }],
    stockAgeing: [{ id: "age-1" }, { id: "age-2" }],
    reviewItems,
    previewLimit: 100
  };
}
let importRows = seedImportRows();
function seedImportRows() {
  return [
  { type: "PRODUCT", importPurpose: "STOCK_SNAPSHOT", snapshotType: "Stock Snapshot Import", rowNumber: 1, productName: "MAGGI MASLA 70", unitCode: "PCS", currentStock: 20, importedStock: 24, delta: 4, matchStatus: "MATCH_EXISTING_PRODUCT", action: "CREATE_SNAPSHOT_ADJUSTMENT", openingStock: 24, purchasePrice: 8.5, salesPrice: 12, warehouseName: "Main Godown", rawMetadata: { "Item Name": "MAGGI MASLA 70", Godown: "Main Godown", "Source Entity": "DSPSTKCL" } },
  { type: "PRODUCT", importPurpose: "STOCK_SNAPSHOT", snapshotType: "Stock Snapshot Import", rowNumber: 2, productName: "Parle-G 250g", unitCode: "PCS", currentStock: 18, importedStock: -6, delta: -24, matchStatus: "MATCH_EXISTING_PRODUCT", action: "BLOCKED_NEGATIVE_STOCK", openingStock: -6, purchasePrice: 19, salesPrice: 25, warehouseName: "Main Godown", rawMetadata: { "Item Name": "Parle-G 250g", Godown: "Main Godown", "Source Entity": "DSPSTKCL" } },
  { type: "STOCK_MOVEMENT", rowNumber: 3, productName: "Maggi Masala Noodles 70g", warehouseName: "Main Godown", movementType: "SALE", quantity: 18, rate: 12, movementDate: today(-2), rawMetadata: { "Voucher Type": "Sales", Qty: "18" } }
  ];
}

let importErrors = seedImportErrors();
function seedImportErrors() {
  return [
  { rowNumber: 1, fieldName: "category", errorCode: "CATEGORY_MISSING", message: "Product category is missing", severity: "WARNING", suggestedFix: "Add a category to improve dashboards and cleanup insights." },
  { rowNumber: 2, fieldName: "openingStock", errorCode: "NEGATIVE_QUANTITY", message: "Negative stock quantity found. Choose an import resolution policy or fix the source data.", severity: "ERROR", rawValue: "-6", suggestedFix: "Choose Skip negative stock snapshot or enable tenant negative stock and import as-is." },
  { rowNumber: 2, fieldName: "category", errorCode: "CATEGORY_MISSING", message: "Product category is missing", severity: "WARNING", suggestedFix: "Add a category to improve dashboards and cleanup insights." }
  ];
}

function demoImportErrorsForPolicy(policy: string) {
  const warnings = seedImportErrors().filter((row) => row.errorCode === "CATEGORY_MISSING");
  if (policy === "SKIP_STOCK_MOVEMENT") {
    return [
      ...warnings,
      { rowNumber: 2, fieldName: "openingStock", errorCode: "NEGATIVE_STOCK_SKIPPED", message: "Product will be imported, but negative stock snapshot movement will be skipped.", severity: "WARNING", rawValue: "-6", suggestedFix: "Product imported, negative stock snapshot skipped." }
    ];
  }
  if (policy === "IMPORT_AS_IS" && demoTenant.allowNegativeStock) {
    return [
      ...warnings,
      { rowNumber: 2, fieldName: "openingStock", errorCode: "NEGATIVE_STOCK_IMPORTED", message: "Negative stock will be imported because tenant allows negative stock.", severity: "WARNING", rawValue: "-6", suggestedFix: "Review this product after import and reconcile stock with the physical count." }
    ];
  }
  if (policy === "IMPORT_AS_IS") {
    return [
      ...warnings,
      { rowNumber: 2, fieldName: "openingStock", errorCode: "NEGATIVE_STOCK_NOT_ALLOWED", message: "Tenant must allow negative stock before importing negative stock snapshot.", severity: "ERROR", rawValue: "-6", suggestedFix: "Enable negative stock in business settings or choose Skip negative stock snapshot." }
    ];
  }
  return seedImportErrors();
}

const permissionCatalog = [
  "dashboard.view", "today_actions.view",
  "products.view", "products.create", "products.update", "products.delete", "products.merge",
  "stock.view", "stock.adjust", "stock.transfer", "stock.approve_adjustment", "stock.view_cost",
  "sales.view", "sales.create", "sales.update", "sales.cancel", "sales.view_margin",
  "purchases.view", "purchases.create", "purchases.update", "purchases.approve", "purchases.view_cost",
  "customers.view", "customers.create", "customers.update", "customers.view_outstanding",
  "suppliers.view", "suppliers.create", "suppliers.update",
  "imports.view", "imports.upload", "imports.map", "imports.validate", "imports.commit", "imports.rollback",
  "integrations.tally.view", "integrations.tally.manage",
  "forecast.view", "reorder.view", "reorder.create_purchase_order",
  "dead_stock.view", "dead_stock.action",
  "data_quality.view", "data_quality.apply_suggestion", "data_quality.merge",
  "insights.profit.view", "ai.assistant.use",
  "reports.view", "reports.export",
  "users.view", "users.manage", "roles.view", "roles.manage",
  "settings.view", "settings.manage", "billing.view", "billing.manage", "audit.view",
  "portal.customer.view", "portal.customer.order_create", "portal.customer.invoice_view", "portal.customer.outstanding_view",
  "portal.supplier.view", "portal.supplier.po_view", "portal.supplier.invoice_upload", "portal.supplier.delivery_update",
  "platform.tenants.view", "platform.tenants.manage", "platform.subscriptions.manage", "platform.system_health.view", "platform.support_access.manage", "platform.audit.view"
];

const demoUsers = [
  demoUser("owner@demo.com", "Demo Owner", "OWNER"),
  demoUser("admin@demo.com", "Demo Admin", "ADMIN"),
  demoUser("manager@demo.com", "Demo Manager", "MANAGER"),
  demoUser("warehouse@demo.com", "Demo Warehouse Staff", "WAREHOUSE_STAFF"),
  demoUser("sales@demo.com", "Demo Sales Staff", "SALES_STAFF"),
  demoUser("purchase@demo.com", "Demo Purchase Manager", "PURCHASE_MANAGER"),
  demoUser("accountant@demo.com", "Demo Accountant", "ACCOUNTANT"),
  demoUser("viewer@demo.com", "Demo Viewer", "VIEWER"),
  demoUser("auditor@demo.com", "Demo Auditor", "AUDITOR"),
  demoUser("ravi@demo.com", "Ravi Traders Portal", "CUSTOMER_USER", "c1"),
  demoUser("supplier@demo.com", "ABC Supplier Portal", "SUPPLIER_USER"),
  demoUser("platform@demo.com", "Platform Super Admin", "PLATFORM_SUPER_ADMIN"),
  demoUser("support@demo.com", "Platform Support", "PLATFORM_SUPPORT"),
  demoUser("billing@demo.com", "Platform Billing Admin", "PLATFORM_BILLING_ADMIN")
];

type DemoState = {
  tenant: typeof demoTenant;
  products: typeof products;
  warehouses: typeof warehouses;
  customers: typeof customers;
  paymentReminders: typeof paymentReminders;
  suppliers: typeof suppliers;
  salesInvoices: typeof salesInvoices;
  purchaseInvoices: typeof purchaseInvoices;
  importRows: typeof importRows;
  importErrors: typeof importErrors;
  stockMovements?: DemoMovement[];
  portalOrders?: DemoOrder[];
  draftPurchaseOrders?: typeof draftPurchaseOrders;
  adjustmentResults?: typeof adjustmentResults;
  paymentResults?: typeof paymentResults;
};

const DEMO_WORKSPACE_KEY = "stockpilot.demo.workspace";
const DEMO_EMAIL_KEY = "stockpilot.demo.email";
const DEMO_STATE_PREFIX = "stockpilot.demo.state.";

function seedDemoState(): DemoState {
  return {
    tenant: seedTenant(),
    products: seedProducts(),
    warehouses: seedWarehouses(),
    customers: seedCustomers(),
    paymentReminders: seedPaymentReminders(),
    suppliers: seedSuppliers(),
    salesInvoices: seedSalesInvoices(),
    purchaseInvoices: seedPurchaseInvoices(),
    importRows: seedImportRows(),
    importErrors: seedImportErrors(),
    stockMovements: seedStockMovements(seedProducts(), seedWarehouses()),
    portalOrders: [], paymentResults: {}, draftPurchaseOrders: [], adjustmentResults: {}
  };
}

function blankWorkspaceState(workspaceId: string, businessName?: string, businessMode?: string): DemoState {
  return {
    tenant: {
      ...seedTenant(),
      id: workspaceId,
      name: businessName?.trim() || "New StockPilot Workspace",
      businessMode: businessMode === "WHOLESALE" || businessMode === "HYBRID" ? businessMode : "RETAIL"
    },
    products: [],
    warehouses: [{ id: "w1", name: "Main Godown", code: "MAIN", address: "" }],
    customers: [],
    paymentReminders: [],
    suppliers: [],
    salesInvoices: [],
    purchaseInvoices: [],
    importRows: [],
    importErrors: [], stockMovements: [], portalOrders: [], paymentResults: {}, draftPurchaseOrders: [], adjustmentResults: {}
  };
}

function applyDemoState(state: DemoState) {
  demoTenant = clone(state.tenant);
  products = clone(state.products);
  warehouses = clone(state.warehouses);
  customers = clone(state.customers);
  paymentReminders = clone(state.paymentReminders);
  suppliers = clone(state.suppliers);
  salesInvoices = clone(state.salesInvoices);
  purchaseInvoices = clone(state.purchaseInvoices);
  importRows = clone(state.importRows);
  importErrors = clone(state.importErrors);
  stockMovements = clone(state.stockMovements ?? seedStockMovements(products, warehouses));
  portalOrders = clone(state.portalOrders ?? []);
  draftPurchaseOrders = clone(state.draftPurchaseOrders ?? []);
  adjustmentResults = clone(state.adjustmentResults ?? {});
  paymentResults = clone(state.paymentResults ?? {});
  syncProductStocks();
}

function currentDemoState(): DemoState {
  return {
    tenant: demoTenant,
    products,
    warehouses,
    customers,
    paymentReminders,
    suppliers,
    salesInvoices,
    purchaseInvoices,
    importRows,
    importErrors, stockMovements, portalOrders, paymentResults, draftPurchaseOrders, adjustmentResults
  };
}

function persistDemoState() {
  if (typeof window === "undefined") return;
  const workspaceId = window.localStorage.getItem(DEMO_WORKSPACE_KEY) || demoTenant.id || "tenant-demo";
  window.localStorage.setItem(`${DEMO_STATE_PREFIX}${workspaceId}`, JSON.stringify(currentDemoState()));
}

function activateDemoWorkspace(email?: string, options: { reset?: boolean; businessName?: string; businessMode?: string } = {}) {
  if (typeof window === "undefined") return "tenant-demo";
  const account = demoUserForEmail(email);
  const builtInDemo = isBuiltInDemoEmail(account.email);
  const workspaceId = builtInDemo ? "tenant-demo" : workspaceIdForEmail(account.email);
  const storageKey = `${DEMO_STATE_PREFIX}${workspaceId}`;
  const stored = window.localStorage.getItem(storageKey);
  const state = options.reset
    ? blankWorkspaceState(workspaceId, options.businessName, options.businessMode)
    : stored
      ? JSON.parse(stored) as DemoState
      : builtInDemo
        ? seedDemoState()
        : blankWorkspaceState(workspaceId, options.businessName, options.businessMode);
  if (options.businessName) {
    state.tenant.name = options.businessName;
  }
  if (options.businessMode === "RETAIL" || options.businessMode === "WHOLESALE" || options.businessMode === "HYBRID") {
    state.tenant.businessMode = options.businessMode;
  }
  state.tenant.id = workspaceId;
  applyDemoState(state);
  window.localStorage.setItem(DEMO_WORKSPACE_KEY, workspaceId);
  window.localStorage.setItem(DEMO_EMAIL_KEY, account.email);
  window.localStorage.setItem(storageKey, JSON.stringify(currentDemoState()));
  return workspaceId;
}

function ensureActiveDemoWorkspace() {
  if (typeof window === "undefined") return;
  const workspaceId = window.localStorage.getItem(DEMO_WORKSPACE_KEY);
  if (!workspaceId) {
    activateDemoWorkspace("owner@demo.com");
    return;
  }
  const stored = window.localStorage.getItem(`${DEMO_STATE_PREFIX}${workspaceId}`);
  if (stored) {
    applyDemoState(JSON.parse(stored) as DemoState);
  }
}

function workspaceIdForEmail(email: string) {
  return `tenant-${email.toLowerCase().replaceAll(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "local"}`;
}

function isBuiltInDemoEmail(email: string) {
  return demoUsers.some((user) => user.email.toLowerCase() === email.toLowerCase());
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

export async function demoApi<T>(path: string, init: Init = {}): Promise<T> {
  const response = await demoResponse<unknown>(path, init);
  if (typeof response === "string" || response == null || path.startsWith("/api/auth/")) return response as T;
  const result = clone(response);
  const permissions = rolePermissions(currentDemoUser().role);
  const cost = permissions.includes("stock.view_cost") || permissions.includes("purchases.view_cost");
  const profit = permissions.includes("insights.profit.view") || permissions.includes("sales.view_margin");
  const redact = (value: unknown): void => {
    if (Array.isArray(value)) { value.forEach(redact); return; }
    if (!value || typeof value !== "object") return;
    for (const [key, item] of Object.entries(value)) {
      if ((!cost && ["defaultPurchasePrice", "stockValue", "totalStockValue", "deadStockValue", "blockedCapital", "costRate"].includes(key)) || (!profit && ["grossProfit", "grossMarginPercent", "profitChange", "marginChangePercent"].includes(key))) (value as Record<string, unknown>)[key] = null;
      else redact(item);
    }
  };
  redact(result);
  return result as T;
}

async function demoResponse<T>(path: string, init: Init = {}): Promise<T> {
  if (process.env.NEXT_PUBLIC_DEMO_MODE !== "true") {
    if (process.env.NODE_ENV !== "production") {
      console.warn("StockPilot demoApi was called while NEXT_PUBLIC_DEMO_MODE is not true.", { path });
    }
    throw new Error("Demo API cannot be used while Backend mode is active.");
  }
  await new Promise((resolve) => setTimeout(resolve, 120));
  const method = (init.method ?? "GET").toUpperCase();
  ensureActiveDemoWorkspace();

  if (path === "/api/auth/login" || path === "/api/auth/register") {
    const body = parseBody(init);
    const workspaceId = activateDemoWorkspace(body.email, {
      reset: path === "/api/auth/register",
      businessName: body.businessName,
      businessMode: body.businessMode
    });
    if (path === "/api/auth/register") {
      demoTenant.name = body.businessName || demoTenant.name;
      if (["RETAIL", "WHOLESALE", "HYBRID"].includes(body.businessMode)) {
        demoTenant.businessMode = body.businessMode;
      }
      persistDemoState();
    }
    const account = demoUserForEmail(body.email);
    const role = account.role;
    return { accessToken: `demo-token-${role}-${workspaceId}`, tenantId: workspaceId, userId: account.userId, role, email: account.email, fullName: account.fullName } as T;
  }

  if (path === "/api/auth/session") {
    const account = currentDemoUser();
    return { ...account, tenantId: demoTenant.id, platformRole: null, permissions: rolePermissions(account.role),
      workspaces: [{tenantId: demoTenant.id, name: demoTenant.name, businessMode: demoTenant.businessMode, role: account.role, clerkOrganizationId: null}],
      authProvider: "local", clerkOrganizationId: null, accessState: "WORKSPACE_READY", emailVerified: true, clerkLinked: false } as T;
  }

  if (path === "/api/auth/me") {
    const account = currentDemoUser();
    return { userId: account.userId, email: account.email, fullName: account.fullName, tenantId: demoTenant.id, role: account.role } as T;
  }

  if (path === "/api/auth/me/permissions") {
    const role = currentDemoUser().role;
    return { role, permissions: rolePermissions(role), catalog: permissionCatalog.map((code) => ({ code, module: code.split(".")[0], name: code })) } as T;
  }

  if (path === "/api/auth/me/tenants") {
    const account = currentDemoUser();
    return [{ tenantId: demoTenant.id, name: demoTenant.name, businessMode: demoTenant.businessMode, role: account.role, customerId: account.customerId }] as T;
  }

  if (path.startsWith("/api/products")) {
    if (method === "POST") {
      const body = parseBody(init);
      const row = product(demoId("product"), body.sku || `SKU${products.length + 1}`, body.name, body.categoryName, body.brandName, body.unitCode || "PCS", body.barcode, Number(body.defaultPurchasePrice || 0), Number(body.defaultSalesPrice || 0), Number(body.reorderPoint || 0), 0);
      products.unshift(row);
      persistDemoState();
      return row as T;
    }
    return page(filterByQuery(products, path), path) as T;
  }

  if (path === "/api/warehouses") {
    if (method === "POST") {
      const body = parseBody(init);
      const row = { id: demoId("warehouse"), name: body.name, code: body.code, address: body.address };
      warehouses.push(row);
      persistDemoState();
      return row as T;
    }
    return warehouses as T;
  }
  if (path === "/api/customers/summary") return { outstandingReceivables: outstandingTotal() } as T;
  if (path === "/api/customers/payment-reminders") return paymentReminders.map(reminderResponse) as T;
  const reminderMatch = path.match(/^\/api\/customers\/([^/]+)\/payment-reminders$/);
  if (reminderMatch && method === "POST") {
    const customer = customers.find((row) => row.id === reminderMatch[1]);
    if (!customer) throw new Error("Customer not found");
    const body = parseBody(init);
    const row = {
      id: `rem-${paymentReminders.length + 1}`,
      customerId: customer.id,
      amountDue: Number(body.amountDue || customer.outstanding || 0),
      reminderDate: body.reminderDate || today(1),
      reminderTime: body.reminderTime || "10:00",
      status: "OPEN",
      notes: body.notes || "Payment follow-up"
    };
    paymentReminders.unshift(row);
    persistDemoState();
    return reminderResponse(row) as T;
  }
  const paymentMatch = path.match(/^\/api\/customers\/([^/]+)\/payments$/);
  if (paymentMatch && method === "POST") {
    const customer = customers.find((row) => row.id === paymentMatch[1]);
    if (!customer) throw new Error("Customer not found");
    const body = parseBody(init);
    const amount = roundMoney(Number(body.amount));
    if (!Number.isFinite(amount) || amount <= 0) throw new Error("Enter a positive payment amount");
    const requestKey = new Headers(init.headers).get("Idempotency-Key");
    if (requestKey && paymentResults[requestKey]) return paymentResults[requestKey] as T;
    customer.outstanding = roundMoney(Number(customer.outstanding || 0) - amount);
    if (body.reminderId) {
      const reminder = paymentReminders.find((row) => row.id === body.reminderId && row.customerId === customer.id);
      if (reminder) { reminder.amountDue = roundMoney(Math.max(0, reminder.amountDue - amount)); if (reminder.amountDue === 0) reminder.status = "PAID"; }
    }
    const result = { id: demoId("pay"), customerId: customer.id, amount, paymentDate: body.paymentDate || today(), notes: [body.mode, body.referenceNumber, body.notes].filter(Boolean).join(" - ") };
    if (requestKey) paymentResults[requestKey] = result;
    persistDemoState();
    return result as T;
  }
  if (path === "/api/customers?size=100" || path.startsWith("/api/customers?")) return page(filterByQuery(customers, path), path) as T;
  if (path === "/api/suppliers?size=100" || path.startsWith("/api/suppliers?")) return page(filterByQuery(suppliers, path), path) as T;
  if (path === "/api/customers" && method === "POST") {
    const row = { id: demoId("customer"), ...parseBody(init), outstanding: 0 };
    customers.unshift(row);
    persistDemoState();
    return row as T;
  }
  if (path === "/api/suppliers" && method === "POST") {
    const row = { id: demoId("supplier"), payable: 0, ...parseBody(init) };
    suppliers.unshift(row);
    persistDemoState();
    return row as T;
  }

  if (path === "/api/dashboard/summary") return dashboardSummary() as T;
  if (path === "/api/dashboard/sales-trend") return invoiceTrend(false) as T;
  if (path === "/api/dashboard/profit-loss") return invoiceTrend(true) as T;
  if (path === "/api/dashboard/low-stock") return lowStock() as T;
  if (path === "/api/alerts/low-stock") return lowStock() as T;
  if (path === "/api/dashboard/dead-stock") return deadStockSimple() as T;
  if (path === "/api/dashboard/actions") return actions() as T;
  if (path === "/api/dashboard/top-products") return topProducts() as T;
  if (path === "/api/dashboard/slow-moving-products") return slowMovingProducts() as T;
  if (path.startsWith("/api/insights/profit-drop")) return profitDrop() as T;

  if (path.startsWith("/api/stock/current")) {
    const params = new URL(`http://demo${path}`).searchParams;
    return stockRows().filter(row => (!params.get("productId") || row.productId === params.get("productId")) && (!params.get("warehouseId") || row.warehouseId === params.get("warehouseId"))) as T;
  }
  if (path === "/api/stock/adjustment" && method === "POST") {
    const body = parseBody(init);
    const requestKey = new Headers(init.headers).get("Idempotency-Key");
    if (requestKey && adjustmentResults[requestKey]) return adjustmentResults[requestKey] as T;
    const product = products.find(row => row.id === body.productId);
    if (!product || !warehouses.some(row => row.id === body.warehouseId)) throw new Error("Select a valid product and warehouse");
    const delta = Number(body.quantityDelta);
    const rate = Number(body.rate || 0);
    if (!Number.isFinite(delta) || delta === 0 || !Number.isFinite(rate) || rate < 0) throw new Error("Enter a non-zero quantity and non-negative rate");
    if (!demoTenant.allowNegativeStock && stockAt(product.id, body.warehouseId) + delta < 0) throw new Error("Insufficient stock in selected warehouse");
    const movement = { id: demoId("movement"), productId: product.id, warehouseId: body.warehouseId, quantityDelta: delta, rate, movementType: "ADJUSTMENT", movementDate: new Date().toISOString() };
    stockMovements.push(movement);
    if (requestKey) adjustmentResults[requestKey] = movement;
    syncProductStocks(); persistDemoState();
    return movement as T;
  }
  if (path.startsWith("/api/purchases")) {
    if (method === "POST") return createDemoInvoice(parseBody(init), false) as T;
    return page(purchaseInvoices, path) as T;
  }
  if (path.startsWith("/api/sales/retail-counter")) return page([], path) as T;
  if (path.startsWith("/api/sales")) {
    if (method === "POST") return createDemoInvoice(parseBody(init), true) as T;
    return page(salesInvoices, path) as T;
  }

  if (path === "/api/integrations/tally/status") return tallyStatus() as T;
  if (path === "/api/data-quality/summary") return { totalProducts: products.length, duplicateGroups: duplicateGroups().length, productsWithMissingFields: missingFields().length, productsWithoutRecentSales: deadStockSimple().length, productsWithoutPurchaseCost: products.filter(row => row.defaultPurchasePrice <= 0).length, qualityScore: dashboardSummary().importDataQualityScore } as T;
  if (path === "/api/data-quality/products/duplicates") return duplicateGroups() as T;
  if (path === "/api/data-quality/products/missing-fields") return missingFields() as T;
  if (path.includes("/api/data-quality/products/") && path.endsWith("/apply-suggestion")) {
    const product = products.find(row => path.split("/").includes(row.id));
    if (!product) throw new Error("Product not found");
    const body = parseBody(init);
    product.name = body.normalizedName || product.name;
    product.normalizedName = product.name.toUpperCase();
    product.brandName = body.brand || product.brandName;
    product.categoryName = body.category || product.categoryName;
    persistDemoState(); return product as T;
  }
  if (path === "/api/data-quality/products/merge") {
    if (parseBody(init).confirmation !== "MERGE PRODUCTS") throw new Error("Type MERGE PRODUCTS to confirm duplicate product merge");
    const body = parseBody(init);
    const target = products.find(row => row.id === body.targetProductId);
    if (!target) throw new Error("Target product not found");
    const sources = products.filter(row => row.id !== target.id && body.sourceProductIds?.includes(row.id));
    const sourceIds = new Set(sources.map(row => row.id));
    stockMovements.forEach(row => { if (sourceIds.has(row.productId)) row.productId = target.id; });
    [...salesInvoices, ...purchaseInvoices].forEach(invoice => invoice.items?.forEach(row => { if (sourceIds.has(row.productId)) row.productId = target.id; }));
    products = products.filter(row => !sourceIds.has(row.id));
    syncProductStocks(); persistDemoState();
    return { targetProductId: target.id, mergedProducts: sources.length } as T;
  }

  if (path === "/api/dead-stock") return deadStockActionRows() as T;
  if (path.startsWith("/api/dead-stock/") && path.endsWith("/action")) return { status: "RECORDED", ...parseBody(init) } as T;

  if (path === "/api/forecast/results" || path === "/api/forecast/run") return forecasts() as T;
  if (path === "/api/reorder/suggestions") return reorderSuggestions() as T;
  if (path === "/api/reorder/generate-purchase-order") {
    const ids: string[] = parseBody(init).productIds ?? [];
    const rows = reorderSuggestions().filter(row => !ids.length || ids.includes(row.productId));
    if (!rows.length) throw new Error("No reorder suggestions are available");
    const order = { purchaseOrderId: demoId("po"), orderNumber: demoId("DRAFT-PO"), totalAmount: roundMoney(rows.reduce((sum, row) => sum + row.recommendedQuantity * (products.find(product => product.id === row.productId)?.defaultPurchasePrice ?? 0), 0)), itemCount: rows.length };
    draftPurchaseOrders.push(order); persistDemoState(); return order as T;
  }

  if (path.startsWith("/api/import-sessions?")) return page([smartImportSession], path) as T;
  if (path === "/api/import-sessions" && method === "POST") {
    const body = parseBody(init);
    smartImportSession = { ...seedSmartImportSession(), name: body.name || "New smart import session", status: "CREATED", files: [], issues: [], plan: {} };
    smartDryRunAvailable = false;
    smartCommitResult = null;
    return smartImportSession as T;
  }
  if (path === `/api/import-sessions/${smartImportSessionId}`) return smartImportSession as T;
  if (path === `/api/import-sessions/${smartImportSessionId}/files` && method === "POST") {
    const uploaded = init.body instanceof FormData ? init.body.getAll("files").filter((value): value is File => value instanceof File) : [];
    smartImportSession.files = uploaded.map((file, index) => smartFile(`smart-upload-${index}`, file.name, "UNKNOWN", 0, "Awaiting analysis", 0));
    smartImportSession.status = "FILES_UPLOADED";
    return smartImportSession as T;
  }
  if (path === `/api/import-sessions/${smartImportSessionId}/classify` && method === "POST") {
    smartImportSession.files = smartImportSession.files.map((file) => {
      const lower = file.originalFileName.toLowerCase();
      const detected = lower.includes("stock") ? "STOCK_SNAPSHOT" : lower.includes("purchase") ? "PURCHASE_VOUCHERS" : lower.includes("sale") ? "SALES_VOUCHERS" : "UNKNOWN";
      return { ...file, detectedFileType: detected, confidence: detected === "UNKNOWN" ? 0.2 : 0.9, detectionReason: detected === "UNKNOWN" ? "No supported signature was detected." : "Demo content signature was detected.", status: "CLASSIFIED" };
    });
    smartImportSession.status = smartImportSession.files.some((file) => file.detectedFileType === "UNKNOWN") ? "NEEDS_REVIEW" : "CLASSIFIED";
    return smartImportSession as T;
  }
  if (path === `/api/import-sessions/${smartImportSessionId}/plan` && method === "POST") {
    smartImportSession.plan = smartDemoPlan();
    smartImportSession.recommendedStrategy = "HYBRID_RECONCILIATION";
    smartImportSession.status = "PLAN_READY";
    return { ...smartImportSession.plan, issues: smartImportSession.issues } as T;
  }
  if (path === `/api/import-sessions/${smartImportSessionId}/plan`) return { ...smartImportSession.plan, issues: smartImportSession.issues } as T;
  if ((path === `/api/import-sessions/${smartImportSessionId}/stage` || path === `/api/import-sessions/${smartImportSessionId}/rebuild`) && method === "POST") {
    smartImportSession.status = smartImportSession.issues.some((issue) => issue.severity === "ERROR" || issue.severity === "REVIEW_REQUIRED") ? "NEEDS_REVIEW" : "STAGED";
    return smartDemoWorkspace() as T;
  }
  if (path === `/api/import-sessions/${smartImportSessionId}/staging`) return smartDemoWorkspace() as T;
  if (path === `/api/import-sessions/${smartImportSessionId}/dry-run` && method === "POST") {
    smartDryRunAvailable = true;
    return smartDemoDryRun() as T;
  }
  if (path === `/api/import-sessions/${smartImportSessionId}/dry-run`) return smartDemoDryRun() as T;
  if (path.startsWith(`/api/import-sessions/${smartImportSessionId}/dry-run/items`)) {
    const query = new URLSearchParams(path.split("?")[1] || "");
    const itemType = query.get("itemType");
    const issue = query.get("issue");
    const content = smartDemoDryRunItems().filter((item) => (!itemType || item.itemType === itemType)
      && (!issue || (issue === "WARNING" && !!item.warningCode) || (issue === "ERROR" && !!item.errorCode) || (issue === "REVIEW_REQUIRED" && item.action === "REVIEW_REQUIRED")));
    return page(content) as T;
  }
  if (path === `/api/import-sessions/${smartImportSessionId}/commit-result`) {
    return (smartCommitResult ?? { available: false, status: "NOT_COMMITTED" }) as T;
  }
  if (path === `/api/import-sessions/${smartImportSessionId}/commit` && method === "POST") {
    const body = parseBody(init);
    if (!smartDryRunAvailable) throw new Error("Run a completed dry run before committing");
    if (body.confirmation !== "COMMIT IMPORT PLAN") throw new Error("Enter COMMIT IMPORT PLAN to confirm the safe foundation commit");
    if (smartCommitResult) return { ...smartCommitResult, idempotentRetry: true } as T;
    smartCommitResult = smartDemoCommit();
    smartImportSession.status = "COMMITTED";
    return smartCommitResult as T;
  }
  if (path === `/api/import-sessions/${smartImportSessionId}/review-items`) return smartDemoWorkspace().reviewItems as T;
  if (path.startsWith(`/api/import-sessions/${smartImportSessionId}/review-items/`) && path.endsWith("/resolve") && method === "POST") {
    const itemId = path.split("/").at(-2);
    smartImportSession.issues = smartImportSession.issues.filter((issue) => issue.id !== itemId);
    return smartDemoWorkspace() as T;
  }
  if (path.startsWith(`/api/import-sessions/${smartImportSessionId}/files/`) && path.endsWith("/type") && method === "PATCH") {
    const fileId = path.split("/").at(-2);
    const selectedFileType = parseBody(init).selectedFileType;
    smartImportSession.files = smartImportSession.files.map((file) => file.id === fileId
      ? { ...file, selectedFileType, status: "CLASSIFIED", confidence: 1, detectionReason: `File type manually selected as ${selectedFileType}.` }
      : file);
    return smartDemoWorkspace() as T;
  }
  if (path.startsWith(`/api/import-sessions/${smartImportSessionId}/files/`) && method === "DELETE") {
    const fileId = path.split("/").at(-1);
    smartImportSession.files = smartImportSession.files.filter((file) => file.id !== fileId);
    return smartDemoWorkspace() as T;
  }
  if (path === `/api/import-sessions/${smartImportSessionId}/cancel` && method === "POST") {
    smartImportSession.status = "CANCELLED";
    return smartImportSession as T;
  }

  if (path.startsWith("/api/imports/upload")) return { batchId: importBatchId, status: "PARSED", rowCount: importRows.length } as T;
  if (path.startsWith("/api/imports?")) return page([{ id: importBatchId, sourceType: "TALLY_XML", status: "PARSED", originalFileName: "demo-tally-vouchers.xml", rowCount: 3, validCount: 2, errorCount: importErrors.filter((row) => row.severity !== "WARNING").length, negativeStockPolicy: "BLOCK", importPurpose: "STOCK_SNAPSHOT", rollbackStatus: "NOT_ROLLED_BACK" }], path) as T;
  if (path === `/api/imports/${importBatchId}/preview`) return importRows as T;
  if (path === `/api/imports/${importBatchId}/errors`) return importErrors as T;
  if (path === `/api/imports/${importBatchId}/reconciliation`) return {
    batchId: importBatchId,
    status: "COMMITTED",
    sourceType: "TALLY_XML",
    rowsStaged: importRows.length,
    committedProducts: 2,
    productsCreated: 0,
    productsMatchedExisting: 2,
    productsUpdated: 2,
    customersCreated: 0,
    suppliersCreated: 0,
    stockMovementsCreated: 2,
    openingBalanceMovementsCreated: 0,
    voucherStockMovementsCreated: 1,
    stockSnapshotRowsProcessed: 2,
    stockSnapshotRowsUnchanged: 0,
    stockSnapshotAdjustmentsCreated: 1,
    stockSnapshotPositiveAdjustments: 1,
    stockSnapshotNegativeAdjustments: 0,
    rollbackStatus: "NOT_ROLLED_BACK",
    warnings: 2
  } as T;
  if (path === `/api/imports/${importBatchId}/undo-preview`) return {
    batchId: importBatchId,
    status: "COMMITTED",
    canUndo: true,
    reasonIfCannotUndo: "",
    productsToDelete: [{ id: "p9", name: "MAGGI 70GM" }],
    customersToDelete: [],
    suppliersToDelete: [],
    salesInvoicesToVoidOrDelete: [],
    purchaseInvoicesToVoidOrDelete: [],
    stockMovementsToReverse: [{ id: "mov-demo", productName: "MAGGI 70GM", warehouseName: "Main Godown", baseQuantity: 24, reversalQuantity: -24 }],
    stockMovementsToDeleteIfDevMode: [{ id: "mov-demo" }],
    categoriesToDeleteIfUnused: [],
    unitsToDeleteIfUnused: [],
    warnings: ["Products with later sales or manual adjustments would be kept instead of deleted."],
    irreversibleItems: []
  } as T;
  if (path === `/api/imports/${importBatchId}/undo` || path === `/api/imports/${importBatchId}/replace`) return {
    batchId: importBatchId,
    status: "ROLLED_BACK",
    strategy: "SAFE_REVERSAL",
    reversedMovements: 1,
    deletedProducts: 1,
    warnings: []
  } as T;
  if (path.includes("/mapping")) return { id: importBatchId, status: "MAPPED" } as T;
  if (path.includes("/validate")) {
    const policy = parseBody(init).negativeStockPolicy ?? "BLOCK";
    importErrors = demoImportErrorsForPolicy(policy);
    return { id: importBatchId, status: importErrors.some((row) => row.severity !== "WARNING") ? "VALIDATION_FAILED" : "VALIDATED", errorCount: importErrors.filter((row) => row.severity !== "WARNING").length, negativeStockPolicy: policy } as T;
  }
  if (path.includes("/commit")) {
    const policy = parseBody(init).negativeStockPolicy ?? "BLOCK";
    importErrors = demoImportErrorsForPolicy(policy);
    if (importErrors.some((row) => row.severity !== "WARNING")) throw new Error("Fix validation errors before committing import");
    const stockSnapshotAdjustmentsCreated = policy === "IMPORT_AS_IS" ? 2 : 1;
    const voucherStockMovementsCreated = 1;
    const stockMovementsCreated = stockSnapshotAdjustmentsCreated + voucherStockMovementsCreated;
    return {
      batchId: importBatchId,
      committedProducts: 2,
      productsCreated: 0,
      productsMatchedExisting: 2,
      productsUpdated: 2,
      committedParties: 0,
      committedMovements: stockMovementsCreated,
      stockMovementsCreated,
      openingBalanceMovementsCreated: 0,
      voucherStockMovementsCreated,
      stockSnapshotRowsProcessed: 2,
      stockSnapshotRowsUnchanged: 0,
      stockSnapshotAdjustmentsCreated,
      stockSnapshotPositiveAdjustments: 1,
      stockSnapshotNegativeAdjustments: policy === "IMPORT_AS_IS" ? 1 : 0,
      negativeStockRowsFound: 1,
      negativeStockRowsImported: policy === "IMPORT_AS_IS" ? 1 : 0,
      negativeStockRowsSkipped: policy === "SKIP_STOCK_MOVEMENT" ? 1 : 0
    } as T;
  }

  if (path === "/api/ai/assistant/chat") return assistant(parseBody(init).message) as T;

  if (path.startsWith("/api/audit-logs")) return page(auditLogs(), path) as T;
  if (path.startsWith("/api/reports/export/")) return reportCsv(path) as T;

  if (path === "/api/portal/products" || path === "/api/portal/price-list") return products.filter(row => row.active).map(row => ({ productId: row.id, productName: row.name, price: row.defaultSalesPrice, availableStock: row.currentStock })) as T;
  if (path === "/api/portal/orders" && method === "POST") {
    const body = parseBody(init);
    if (!Array.isArray(body.items) || !body.items.length) throw new Error("Add at least one item");
    const items = body.items.map((item: {productId: string; quantity: number}) => {
      const product = products.find(row => row.id === item.productId && row.active);
      if (!product || !Number.isFinite(item.quantity) || item.quantity <= 0) throw new Error("Invalid order item");
      return { productId: product.id, quantity: item.quantity, price: product.defaultSalesPrice };
    });
    const order: DemoOrder = { orderId: demoId("order"), orderNumber: demoId("PORTAL-SO"), status: "OPEN", totalAmount: roundMoney(items.reduce((sum: number, item: {quantity: number; price: number}) => sum + item.quantity * item.price, 0)), customerId: currentDemoUser().customerId ?? "c1", items };
    portalOrders.unshift(order); persistDemoState(); return order as T;
  }
  if (path === "/api/portal/orders") return portalOrders.filter(row => row.customerId === (currentDemoUser().customerId ?? "c1")) as T;
  if (path === "/api/portal/outstanding") return { customerName: customers.find(row => row.id === (currentDemoUser().customerId ?? "c1"))?.name ?? "Customer", outstanding: customers.find(row => row.id === (currentDemoUser().customerId ?? "c1"))?.outstanding ?? 0 } as T;
  if (path === "/api/portal/invoices") return salesInvoices.filter(row => row.customerId === (currentDemoUser().customerId ?? "c1")).map(row => ({ invoiceId: row.id, invoiceNumber: row.invoiceNumber, invoiceDate: row.invoiceDate, totalAmount: row.totalAmount })) as T;

  if (path === "/api/tenants/current/delete-impact") return {
    products: products.length,
    customers: customers.length,
    suppliers: suppliers.length,
    warehouses: warehouses.length,
    stockMovements: stockRows().length,
    salesInvoices: salesInvoices.length,
    purchaseInvoices: purchaseInvoices.length,
    importBatches: 1,
    importFiles: 1,
    forecastResults: reorderSuggestions().length,
    deadStockInsights: deadStockSimple().length,
    auditLogs: auditLogs().length,
    uploadedFiles: 1
  } as T;
  if (path === "/api/tenants/current/reset-business-data" && method === "POST") {
    if (parseBody(init).confirmation !== "RESET WORKSPACE DATA") throw new Error("Type RESET WORKSPACE DATA to confirm");
    const tenant = clone(demoTenant); applyDemoState(blankWorkspaceState(tenant.id, tenant.name, tenant.businessMode)); demoTenant = tenant; persistDemoState();
    return { tenantId: demoTenant.id, deleted: {}, uploadedFilesScheduledForDeletion: 0 } as T;
  }
  if (path === "/api/tenants/current" && method === "DELETE") return { status: "DELETED", deletedTenantId: demoTenant.id, nextTenantId: null, onboardingRequired: true, accessToken: "demo-account-token", role: null } as T;
  if (path === "/api/tenants" && method === "POST") return { accessToken: "demo-token", tenantId: "demo-clean", userId: "demo-user", role: "OWNER", email: "owner@demo.com", fullName: "Demo Owner" } as T;
  if (path === "/api/account" && method === "DELETE") throw new Error("Delete or transfer owned workspaces before deleting your account.");
  if (path === "/api/tenants/current") {
    if (method === "PUT") {
      const body = parseBody(init);
      demoTenant.name = body.name || demoTenant.name;
      demoTenant.currency = body.currency || demoTenant.currency;
      demoTenant.gstEnabled = Boolean(body.gstEnabled);
      demoTenant.allowNegativeStock = Boolean(body.allowNegativeStock);
      demoTenant.portalShowAllActiveProducts = Boolean(body.portalShowAllActiveProducts);
      persistDemoState();
    }
    return demoTenant as T;
  }
  if (path === "/api/tenants/current/business-mode") {
    const body = parseBody(init);
    if (["RETAIL", "WHOLESALE", "HYBRID"].includes(body.businessMode)) {
      demoTenant.businessMode = body.businessMode;
      persistDemoState();
    }
    return demoTenant as T;
  }
  if (path.startsWith("/api/tenants/current")) return { status: "OK" } as T;

  return {} as T;
}

function product(id: string, sku: string, name: string, categoryName: string | undefined, brandName: string | undefined, unitCode: string, barcode: string | undefined, defaultPurchasePrice: number, defaultSalesPrice: number, reorderPoint: number, currentStock: number) {
  return { id, sku, name, normalizedName: name.toUpperCase(), categoryName, brandName, unitCode, barcode, hsnCode: categoryName ? "1905" : undefined, gstPercentage: categoryName ? 5 : 0, defaultPurchasePrice, defaultSalesPrice, reorderPoint, currentStock, active: true };
}

function demoId(prefix: string) { return `${prefix}-${crypto.randomUUID()}`; }
function roundMoney(value: number) { return Math.round((value + Number.EPSILON) * 100) / 100; }

function seedStockMovements(items: typeof products, locations: typeof warehouses): DemoMovement[] {
  if (!locations.length) return [];
  return items.flatMap(product => {
    const primary = locations.length > 1 ? Math.round(product.currentStock * 0.75) : product.currentStock;
    return [primary, product.currentStock - primary].map((quantityDelta, index) => ({ id: `opening-${product.id}-${index}`, productId: product.id, warehouseId: locations[index]?.id ?? locations[0].id, quantityDelta, rate: product.defaultPurchasePrice, movementType: "OPENING", movementDate: new Date().toISOString() })).filter(row => row.quantityDelta !== 0);
  });
}

function stockAt(productId: string, warehouseId?: string) {
  return Math.round(stockMovements.filter(row => row.productId === productId && (!warehouseId || row.warehouseId === warehouseId)).reduce((sum, row) => sum + row.quantityDelta, 0) * 1000) / 1000;
}

function syncProductStocks() { products.forEach(product => { product.currentStock = stockAt(product.id); }); }

function createDemoInvoice(body: { invoiceNumber: string; invoiceDate: string; warehouseId: string; customerId?: string; supplierId?: string; items: { productId: string; quantity: number; rate: number }[] }, sale: boolean): DemoInvoice {
  const invoices = sale ? salesInvoices : purchaseInvoices;
  if (!body.invoiceNumber?.trim() || !body.invoiceDate) throw new Error("Invoice number and date are required");
  if (invoices.some(row => row.invoiceNumber.toLowerCase() === body.invoiceNumber.trim().toLowerCase())) throw new Error("Invoice number already exists");
  if (!warehouses.some(row => row.id === body.warehouseId)) throw new Error("Select a valid warehouse");
  const customer = body.customerId ? customers.find(row => row.id === body.customerId) : undefined;
  const supplier = body.supplierId ? suppliers.find(row => row.id === body.supplierId) : undefined;
  if ((body.customerId && !customer) || (body.supplierId && !supplier)) throw new Error("Party not found");
  if (!Array.isArray(body.items) || !body.items.length) throw new Error("Add at least one invoice item");
  const quantities = new Map<string, number>();
  const items = body.items.map(line => {
    const product = products.find(row => row.id === line.productId);
    if (!product || !Number.isFinite(line.quantity) || line.quantity <= 0 || !Number.isFinite(line.rate) || line.rate < 0) throw new Error("Invalid product, quantity, or rate");
    quantities.set(product.id, (quantities.get(product.id) ?? 0) + line.quantity);
    const subtotal = roundMoney(line.quantity * line.rate);
    const taxAmount = demoTenant.gstEnabled ? roundMoney(subtotal * product.gstPercentage / 100) : 0;
    return { productId: product.id, quantity: line.quantity, rate: line.rate, costRate: product.defaultPurchasePrice, taxAmount, lineTotal: roundMoney(subtotal + taxAmount) };
  });
  if (sale && !demoTenant.allowNegativeStock) for (const [productId, quantity] of quantities) {
    if (quantity > stockAt(productId, body.warehouseId)) throw new Error("Insufficient stock in selected warehouse");
  }
  const totalAmount = roundMoney(items.reduce((sum, line) => sum + line.lineTotal, 0));
  const taxAmount = roundMoney(items.reduce((sum, line) => sum + line.taxAmount, 0));
  const invoice: DemoInvoice = { id: demoId(sale ? "sale" : "purchase"), invoiceNumber: body.invoiceNumber.trim(), invoiceDate: body.invoiceDate, warehouseId: body.warehouseId, customerId: body.customerId, supplierId: body.supplierId, subtotal: roundMoney(totalAmount - taxAmount), taxAmount, totalAmount, items };
  items.forEach(line => {
    stockMovements.push({ id: demoId("movement"), productId: line.productId, warehouseId: body.warehouseId, quantityDelta: line.quantity * (sale ? -1 : 1), rate: line.rate, movementType: sale ? "SALE" : "PURCHASE", movementDate: `${body.invoiceDate}T12:00:00.000Z` });
    if (!sale) { const product = products.find(row => row.id === line.productId)!; product.defaultPurchasePrice = line.rate; }
  });
  invoices.unshift(invoice);
  if (sale && customer) customer.outstanding = roundMoney(customer.outstanding + totalAmount);
  if (!sale && supplier) supplier.payable = roundMoney((supplier.payable ?? 0) + totalAmount);
  syncProductStocks(); persistDemoState(); return invoice;
}

function invoiceProfit(invoice: DemoInvoice) {
  return roundMoney((invoice.items ?? []).reduce((sum, line) => sum + line.lineTotal - line.taxAmount - line.quantity * line.costRate, 0));
}

function dashboardSummary() {
  const month = today().slice(0, 7);
  const current = salesInvoices.filter(row => row.invoiceDate.startsWith(month));
  const monthlySales = roundMoney(current.reduce((sum, row) => sum + row.totalAmount, 0));
  const grossProfit = roundMoney(current.reduce((sum, row) => sum + invoiceProfit(row), 0));
  return { totalStockValue: roundMoney(stockRows().reduce((sum, row) => sum + row.stockValue, 0)), monthlySales, grossProfit, lowStockCount: lowStock().length, deadStockValue: roundMoney(deadStockSimple().reduce((sum, row) => sum + row.stockValue, 0)), outstandingReceivables: roundMoney(outstandingTotal()), grossMarginPercent: monthlySales ? roundMoney(grossProfit / monthlySales * 100) : 0, totalProducts: products.length, totalCustomers: customers.length, totalSuppliers: suppliers.length, importDataQualityScore: products.length ? Math.round((products.length - missingFields().length) / products.length * 100) : 0 };
}

function invoiceTrend(profit: boolean) {
  return Array.from({ length: 30 }, (_, index) => {
    const date = today(index - 29);
    const rows = salesInvoices.filter(row => row.invoiceDate === date);
    return { date, value: roundMoney(rows.reduce((sum, row) => sum + (profit ? invoiceProfit(row) : row.totalAmount), 0)) };
  });
}

function invoice(id: string, invoiceNumber: string, totalAmount: number): DemoInvoice {
  return { id, invoiceNumber, invoiceDate: today(-Math.floor(Math.random() * 20)), subtotal: Math.round(totalAmount / 1.05), taxAmount: Math.round(totalAmount - totalAmount / 1.05), totalAmount };
}

function page<T>(content: T[], path?: string) {
  const params = new URL(`http://demo${path ?? "/"}`).searchParams;
  const size = Math.max(1, Math.min(200, Number(params.get("size") || content.length || 25)));
  const number = Math.max(0, Number(params.get("page") || 0));
  return { content: content.slice(number * size, (number + 1) * size), totalElements: content.length, totalPages: Math.ceil(content.length / size), number };
}

function parseBody(init: Init) {
  return typeof init.body === "string" ? JSON.parse(init.body || "{}") : {};
}

function filterByQuery<T extends { name: string }>(rows: T[], path: string) {
  const query = new URL(`http://demo${path}`).searchParams.get("query");
  return query ? rows.filter((row) => row.name.toLowerCase().includes(query.toLowerCase())) : rows;
}

function today(offset = 0) {
  const date = new Date();
  date.setDate(date.getDate() + offset);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}


function lowStock() {
  return products.filter((row) => row.currentStock <= row.reorderPoint).map((row) => ({ productId: row.id, productName: row.name, currentStock: row.currentStock, reorderPoint: row.reorderPoint }));
}

function stockRows() {
  return products.flatMap(product => warehouses.map(warehouse => {
    const currentStock = stockAt(product.id, warehouse.id);
    return { productId: product.id, productName: product.name, warehouseId: warehouse.id, warehouseName: warehouse.name, currentStock, stockValue: roundMoney(currentStock * product.defaultPurchasePrice) };
  })).filter(row => row.currentStock !== 0);
}

function topProducts() {
  return products.map(row => {
    const entries = salesInvoices.flatMap(invoice => (invoice.items ?? []).filter(item => item.productId === row.id).map(item => ({ ...item, date: invoice.invoiceDate })));
    return { productId: row.id, productName: row.name, quantity: entries.reduce((sum, line) => sum + line.quantity, 0), revenue: roundMoney(entries.reduce((sum, line) => sum + line.lineTotal - line.taxAmount, 0)), stockQuantity: row.currentStock, stockValue: roundMoney(row.currentStock * row.defaultPurchasePrice), lastSoldDate: entries.map(row => row.date).sort().at(-1) };
  }).filter(row => row.quantity > 0).sort((a, b) => b.revenue - a.revenue);
}

function slowMovingProducts() {
  return deadStockSimple().map((row) => ({
    productId: row.productId,
    productName: row.productName,
    quantity: 0,
    revenue: 0,
    stockQuantity: row.stockQuantity,
    stockValue: row.stockValue,
    lastSoldDate: row.lastSoldDate
  }));
}

function deadStockSimple() {
  return products.filter(product => product.currentStock > 0 && !salesInvoices.some(invoice => invoice.invoiceDate >= today(-90) && invoice.items?.some(item => item.productId === product.id))).map(product => ({ productId: product.id, productName: product.name, stockQuantity: product.currentStock, stockValue: roundMoney(product.currentStock * product.defaultPurchasePrice), lastSoldDate: undefined as string | undefined, suggestedAction: "Review stock without recent sales", explanation: "No sales in the recent window." }));
}

function actions() {
  const rows = [];
  if (outstandingTotal() > 0) rows.push({ title: "Collect customer balances", reason: "Recorded customer receivables need attention", estimatedImpact: outstandingTotal(), href: "/customers", cta: "Open customers" });
  if (lowStock().length) rows.push({ title: "Review reorder points", reason: `${lowStock().length} products are at or below their reorder point`, estimatedImpact: 0, href: "/forecasts", cta: "View reorder" });
  if (deadStockSimple().length) rows.push({ title: "Review stock without sales", reason: "Review recorded stock without recent sales before clearing it", estimatedImpact: deadStockSimple().reduce((sum, row) => sum + row.stockValue, 0), href: "/dead-stock", cta: "View dead stock" });
  return rows;
}

function profitDrop() {
  const current = dashboardSummary();
  return { summary: "Demo profit reflects recorded sales lines and their captured purchase costs. Imported historical invoice headers without item costs are excluded from profit.", profitChange: current.grossProfit, marginChangePercent: current.grossMarginPercent, topReasons: [], recommendedActions: [], evidence: { currentMonthRevenue: current.monthlySales, currentMonthGrossProfit: current.grossProfit } };
}

function tallyStatus() {
  return { status: "CONNECTED_BY_EXPORT", lastSuccessfulImportDate: today(-1), productsImported: 2, customersImported: 2, suppliersImported: 1, salesVouchersImported: 1, purchaseVouchersImported: 1, importErrors: 2, unmappedFields: ["GST %", "HSN Code"], duplicateProductsDetected: 1, missingGstHsnUnitCategoryWarnings: 4, dataQualityScore: 72, aiInsightsGenerated: 2 };
}

function suggestion(id: string, productName: string, normalizedName = "Maggi Masala Noodles 70g") {
  return { productId: id, productName, normalizedName, brand: "Maggi", category: "Instant Noodles", unitSize: "70g", issues: ["Missing HSN", "Missing GST", "Missing category", "Missing purchase cost"] };
}

function duplicateGroups() {
  const candidates = products.filter(row => ["p9", "p10", "p11"].includes(row.id));
  const canonical = products.find(row => row.id === "p1");
  return canonical && candidates.length > 1 ? [{ matchKey: "maggi-70g", similarityScore: 82, products: candidates.map(row => suggestion(row.id, row.name)), suggestedCanonical: suggestion(canonical.id, canonical.name) }] : [];
}

function missingFields() {
  return products.flatMap(product => {
    const missing = [!product.categoryName && "category", !product.unitCode && "unit", !product.hsnCode && "HSN", product.defaultPurchasePrice <= 0 && "purchase cost"].filter((value): value is string => Boolean(value));
    return missing.length ? [{ productId: product.id, productName: product.name, missingFields: missing, suggestion: { productId: product.id, productName: product.name, normalizedName: product.name.trim().replace(/\s+/g, " "), brand: product.brandName ?? "", category: product.categoryName ?? "Uncategorized", unitSize: product.unitCode, issues: missing } }] : [];
  });
}

function deadStockActionRows() {
  return deadStockSimple().map((row) => ({ productId: row.productId, productName: row.productName, warehouseName: "Main Godown", quantity: row.stockQuantity, stockValue: row.stockValue, lastSoldDate: row.lastSoldDate, daysSinceLastSale: 9999, averageMonthlySale: 0, blockedCapital: row.stockValue, recommendedAction: "bundle with fast-moving item" }));
}

function forecasts() {
  return reorderSuggestions().map((row) => ({ productId: row.productId, productName: row.productName, currentStock: row.currentStock, averageDailyDemand: row.averageDailyDemand, next7DaysDemand: row.last7DaysDemand, next30DaysDemand: row.last30DaysDemand, stockoutDate: row.expectedStockoutDate, salesHistoryAvailable: row.salesHistoryAvailable }));
}

function reorderSuggestions() {
  return products.flatMap(product => warehouses.map(warehouse => {
    const sold = stockMovements.filter(row => row.productId === product.id && row.warehouseId === warehouse.id && row.movementType === "SALE" && row.movementDate.slice(0, 10) >= today(-30));
    const quantity = -sold.reduce((sum, row) => sum + row.quantityDelta, 0);
    const averageDailyDemand = quantity / 30;
    const currentStock = stockAt(product.id, warehouse.id);
    return { productId: product.id, productName: product.name, warehouseId: warehouse.id, warehouseName: warehouse.name, currentStock, averageDailyDemand, last7DaysDemand: averageDailyDemand * 7, last30DaysDemand: quantity, expectedStockoutDate: currentStock <= 0 ? today() : averageDailyDemand > 0 ? today(Math.floor(currentStock / averageDailyDemand)) : undefined, salesHistoryAvailable: sold.length > 0, supplierLeadTimeDays: 7, safetyStock: 5, minimumOrderQuantity: 1, pendingPurchaseQuantity: 0, pendingSalesQuantity: 0, recommendedQuantity: Math.max(0, Math.ceil(Math.max(product.reorderPoint, averageDailyDemand * 14) - currentStock)), reason: sold.length ? "Based on recorded warehouse sales and current stock" : "No sales history; recommendation uses the configured reorder point" };
  })).filter(row => row.recommendedQuantity > 0);
}

function assistant(message = "") {
  const lower = message.toLowerCase();
  let response = `Based on recorded demo transactions: monthly sales ${dashboardSummary().monthlySales}, gross profit ${dashboardSummary().grossProfit} (${demoTenant.currency}).`;
  let evidence: Record<string, unknown> = { summary: dashboardSummary() };
  if (lower.includes("profit")) {
    const analysis = profitDrop();
    response = `${analysis.summary} Profit change: ₹${analysis.profitChange}.`;
    evidence = { profitDrop: analysis };
  } else if (lower.includes("reorder") || lower.includes("stock out") || lower.includes("stockout")) {
    const rows = reorderSuggestions();
    response = `Reorder candidates are ${rows.map((row) => `${row.productName} (${row.recommendedQuantity})`).join(", ")}.`;
    evidence = { suggestions: rows };
  } else if (lower.includes("cleanup") || lower.includes("imported")) {
    response = "Product cleanup needs attention on 4 products with missing fields and 1 duplicate Maggi group.";
    evidence = { duplicates: duplicateGroups(), missingFields: missingFields() };
  } else if (lower.includes("dead") || lower.includes("slow")) {
    response = "The highest dead-stock risk is MAGGI 70GM with ₹2,040 blocked.";
    evidence = { deadStock: deadStockSimple() };
  } else if (lower.includes("outstanding")) {
    response = "Highest outstanding is City Supermart with ₹62,400.";
    evidence = { customerOutstanding: customers };
  } else if (lower.includes("payment") || lower.includes("reminder") || lower.includes("collect") || lower.includes("money")) {
    const due = paymentReminders.map(reminderResponse).filter((row) => row.status === "OPEN" && (row.dueStatus === "OVERDUE" || row.dueStatus === "DUE_TODAY"));
    response = due.length
      ? `You have ${due.length} payment follow-ups due. Start with ${due[0].customerName} for ${due[0].amountDue}.`
      : "There are no payment reminders due today.";
    evidence = { paymentReminders: due };
  }
  return { conversationId: "demo-conversation", response, evidence };
}

function outstandingTotal() {
  return customers.reduce((sum, row) => sum + Number(row.outstanding || 0), 0);
}

function auditLogs() {
  return [
    { id: "audit-1", actorUserId: "user-owner", action: "IMPORT_COMMITTED", entityType: "ImportBatch", entityId: importBatchId, details: { products: 2, parties: 2, movements: 1 }, createdAt: new Date().toISOString() },
    { id: "audit-2", actorUserId: "user-owner", action: "STOCK_MOVEMENT_CREATED", entityType: "StockMovement", entityId: "movement-demo", details: { movementType: "ADJUSTMENT", baseQuantity: 12, referenceType: "STOCK_ADJUSTMENT" }, createdAt: new Date(Date.now() - 3600000).toISOString() },
    { id: "audit-3", actorUserId: "user-owner", action: "CUSTOMER_PAYMENT_RECEIVED", entityType: "CustomerPayment", entityId: "pay-demo", details: { customerId: "c1", amount: 12000 }, createdAt: new Date(Date.now() - 7200000).toISOString() }
  ];
}

function reportCsv(path: string) {
  const report = path.split("/api/reports/export/")[1]?.split("?")[0] ?? "current-stock";
  const rows = reportRows(report);
  return rows.map((row) => row.map(csvCell).join(",")).join("\n") + "\n";
}

function reportRows(report: string) {
  switch (report) {
    case "current-stock":
    case "stock":
      return [["product_id", "product_name", "warehouse", "current_stock", "stock_value"], ...stockRows().map((row) => [row.productId, row.productName, row.warehouseName, row.currentStock, row.stockValue])];
    case "low-stock":
      return [["product_id", "product_name", "current_stock", "reorder_point"], ...lowStock().map((row) => [row.productId, row.productName, row.currentStock, row.reorderPoint])];
    case "dead-stock":
      return [["product_id", "product_name", "quantity", "stock_value", "recommended_action"], ...deadStockActionRows().map((row) => [row.productId, row.productName, row.quantity, row.stockValue, row.recommendedAction])];
    case "reorder-suggestions":
      return [["product_id", "product_name", "warehouse", "current_stock", "recommended_quantity", "reason"], ...reorderSuggestions().map((row) => [row.productId, row.productName, row.warehouseName, row.currentStock, row.recommendedQuantity, row.reason])];
    case "customers":
      return [["customer_id", "name", "phone", "gstin", "outstanding"], ...customers.map((row) => [row.id, row.name, row.phone, row.gstin, row.outstanding])];
    case "suppliers":
      return [["supplier_id", "name", "phone", "gstin", "credit_days"], ...suppliers.map((row) => [row.id, row.name, row.phone, row.gstin, row.creditDays])];
    case "sales":
      return [["invoice_id", "invoice_number", "invoice_date", "total_amount"], ...salesInvoices.map((row) => [row.id, row.invoiceNumber, row.invoiceDate, row.totalAmount])];
    case "purchases":
      return [["invoice_id", "invoice_number", "invoice_date", "total_amount"], ...purchaseInvoices.map((row) => [row.id, row.invoiceNumber, row.invoiceDate, row.totalAmount])];
    case "top-products":
      return [["product_id", "product_name", "quantity", "revenue"], ...topProducts().map((row) => [row.productId, row.productName, row.quantity, row.revenue])];
    case "slow-moving-products":
      return [["product_id", "product_name", "stock_quantity", "stock_value"], ...slowMovingProducts().map((row) => [row.productId, row.productName, row.stockQuantity, row.stockValue])];
    case "audit-logs":
    case "audit":
      return [["audit_id", "created_at", "action", "entity_type", "details"], ...auditLogs().map((row) => [row.id, row.createdAt, row.action, row.entityType, JSON.stringify(row.details)])];
    case "products":
    default:
      return [["product_id", "sku", "name", "category", "brand", "purchase_price", "sales_price", "stock"], ...products.map((row) => [row.id, row.sku, row.name, row.categoryName ?? "", row.brandName ?? "", row.defaultPurchasePrice, row.defaultSalesPrice, row.currentStock])];
  }
}

function csvCell(value: unknown) {
  const raw = String(value ?? "");
  const text = /^[=+@\-\t\r]/.test(raw) ? `'${raw}` : raw;
  return /[",\n\r]/.test(text) ? `"${text.replaceAll("\"", "\"\"")}"` : text;
}

function reminderResponse(reminder: { id: string; customerId: string; amountDue: number; reminderDate: string; reminderTime?: string; status: string; notes?: string }) {
  const customer = customers.find((row) => row.id === reminder.customerId);
  return {
    ...reminder,
    customerName: customer?.name ?? "Unknown customer",
    outstanding: customer?.outstanding ?? 0,
    dueStatus: dueStatus(reminder)
  };
}

function dueStatus(reminder: { reminderDate: string; status: string }) {
  if (reminder.status !== "OPEN") return reminder.status;
  if (reminder.reminderDate < today()) return "OVERDUE";
  if (reminder.reminderDate === today()) return "DUE_TODAY";
  return "UPCOMING";
}

function roleFromStorage() {
  if (typeof window === "undefined") return "OWNER";
  const stored = window.localStorage.getItem("stockpilot.role");
  if (stored) return stored;
  const token = window.localStorage.getItem("stockpilot.token") ?? "";
  return demoUsers.find((user) => token.includes(user.role))?.role ?? "OWNER";
}

function demoUser(email: string, fullName: string, role: string, customerId?: string) {
  return {
    email,
    fullName,
    role,
    customerId,
    userId: `user-${role.toLowerCase().replaceAll("_", "-")}`
  };
}

function demoUserForEmail(email?: string) {
  if (!email) return demoUsers[0];
  return demoUsers.find((user) => user.email.toLowerCase() === email.toLowerCase())
    ?? demoUser(email.toLowerCase(), email.split("@")[0] || "Workspace Owner", "OWNER");
}

function currentDemoUser() {
  if (typeof window !== "undefined") {
    const email = window.localStorage.getItem(DEMO_EMAIL_KEY);
    if (email) {
      return demoUserForEmail(email);
    }
  }
  const role = roleFromStorage();
  return demoUsers.find((user) => user.role === role) ?? demoUsers[0];
}

function rolePermissions(role: string) {
  const ownerPermissions = permissionCatalog.filter((code) => !code.startsWith("portal.") && !code.startsWith("platform."));
  const platformPermissions = permissionCatalog.filter((code) => code.startsWith("platform."));
  if (role === "OWNER") return ownerPermissions;
  if (role === "ADMIN") return ownerPermissions.filter((code) => code !== "billing.manage");
  if (role === "MANAGER") {
    return [
      "dashboard.view", "today_actions.view", "products.view", "products.create", "products.update",
      "stock.view", "stock.transfer", "sales.view", "sales.create", "sales.update",
      "purchases.view", "purchases.create", "purchases.update",
      "customers.view", "customers.create", "customers.update", "customers.view_outstanding",
      "suppliers.view", "imports.view", "imports.upload", "imports.map", "imports.validate",
      "integrations.tally.view", "forecast.view", "reorder.view", "dead_stock.view", "dead_stock.action",
      "data_quality.view", "data_quality.apply_suggestion", "insights.profit.view", "ai.assistant.use", "reports.view"
    ];
  }
  if (role === "CUSTOMER_USER") return ["portal.customer.view", "portal.customer.order_create", "portal.customer.invoice_view", "portal.customer.outstanding_view"];
  if (role === "WAREHOUSE_STAFF" || role === "STAFF") return ["dashboard.view", "products.view", "stock.view", "stock.adjust", "stock.transfer"];
  if (role === "SALES_STAFF") return ["dashboard.view", "products.view", "stock.view", "sales.view", "sales.create", "customers.view", "customers.create", "customers.view_outstanding"];
  if (role === "PURCHASE_MANAGER") return ["dashboard.view", "products.view", "stock.view", "suppliers.view", "suppliers.create", "suppliers.update", "purchases.view", "purchases.create", "purchases.update", "purchases.view_cost", "forecast.view", "reorder.view", "reorder.create_purchase_order", "reports.view"];
  if (role === "ACCOUNTANT") return ["dashboard.view", "sales.view", "purchases.view", "customers.view", "customers.view_outstanding", "reports.view", "reports.export", "insights.profit.view", "audit.view"];
  if (role === "AUDITOR") return ["dashboard.view", "products.view", "stock.view", "sales.view", "purchases.view", "customers.view", "suppliers.view", "imports.view", "integrations.tally.view", "forecast.view", "reorder.view", "dead_stock.view", "data_quality.view", "insights.profit.view", "reports.view", "reports.export", "audit.view"];
  if (role === "SUPPLIER_USER") return ["portal.supplier.view", "portal.supplier.po_view", "portal.supplier.invoice_upload", "portal.supplier.delivery_update"];
  if (role === "PLATFORM_SUPER_ADMIN") return platformPermissions;
  if (role === "PLATFORM_SUPPORT") return ["platform.tenants.view", "platform.system_health.view", "platform.support_access.manage", "platform.audit.view"];
  if (role === "PLATFORM_BILLING_ADMIN") return ["platform.tenants.view", "platform.subscriptions.manage"];
  return ["dashboard.view", "today_actions.view", "products.view", "stock.view", "reports.view", "forecast.view", "reorder.view", "dead_stock.view", "data_quality.view"];
}
