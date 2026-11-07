package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.repo.Repositories;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "app.demo.seed-enabled", havingValue = "true", matchIfMissing = false)
public class DemoDataSeeder implements CommandLineRunner {
    private static final int HISTORY_DAYS = 90;
    private final Repositories.UserRepository users;
    private final Repositories.TenantRepository tenants;
    private final Repositories.MembershipRepository memberships;
    private final Repositories.WarehouseRepository warehouses;
    private final Repositories.ProductRepository products;
    private final Repositories.ProductBarcodeRepository barcodes;
    private final Repositories.CustomerRepository customers;
    private final Repositories.SupplierRepository suppliers;
    private final Repositories.SalesInvoiceRepository salesInvoices;
    private final Repositories.SalesInvoiceItemRepository salesItems;
    private final Repositories.PurchaseInvoiceRepository purchaseInvoices;
    private final Repositories.PurchaseInvoiceItemRepository purchaseItems;
    private final Repositories.CustomerPaymentRepository payments;
    private final Repositories.PaymentReminderRepository paymentReminders;
    private final Repositories.ImportBatchRepository importBatches;
    private final Repositories.ImportErrorRepository importErrors;
    private final Repositories.StagingProductRepository stagingProducts;
    private final Repositories.StagingCustomerRepository stagingCustomers;
    private final Repositories.StagingSupplierRepository stagingSuppliers;
    private final Repositories.StagingStockMovementRepository stagingMovements;
    private final Repositories.ReorderSuggestionRepository reorderSuggestions;
    private final Repositories.DeadStockInsightRepository deadStockInsights;
    private final PasswordEncoder passwordEncoder;
    private final CatalogService catalog;
    private final StockLedgerService stockLedger;

    public DemoDataSeeder(
        Repositories.UserRepository users,
        Repositories.TenantRepository tenants,
        Repositories.MembershipRepository memberships,
        Repositories.WarehouseRepository warehouses,
        Repositories.ProductRepository products,
        Repositories.ProductBarcodeRepository barcodes,
        Repositories.CustomerRepository customers,
        Repositories.SupplierRepository suppliers,
        Repositories.SalesInvoiceRepository salesInvoices,
        Repositories.SalesInvoiceItemRepository salesItems,
        Repositories.PurchaseInvoiceRepository purchaseInvoices,
        Repositories.PurchaseInvoiceItemRepository purchaseItems,
        Repositories.CustomerPaymentRepository payments,
        Repositories.PaymentReminderRepository paymentReminders,
        Repositories.ImportBatchRepository importBatches,
        Repositories.ImportErrorRepository importErrors,
        Repositories.StagingProductRepository stagingProducts,
        Repositories.StagingCustomerRepository stagingCustomers,
        Repositories.StagingSupplierRepository stagingSuppliers,
        Repositories.StagingStockMovementRepository stagingMovements,
        Repositories.ReorderSuggestionRepository reorderSuggestions,
        Repositories.DeadStockInsightRepository deadStockInsights,
        PasswordEncoder passwordEncoder,
        CatalogService catalog,
        StockLedgerService stockLedger
    ) {
        this.users = users;
        this.tenants = tenants;
        this.memberships = memberships;
        this.warehouses = warehouses;
        this.products = products;
        this.barcodes = barcodes;
        this.customers = customers;
        this.suppliers = suppliers;
        this.salesInvoices = salesInvoices;
        this.salesItems = salesItems;
        this.purchaseInvoices = purchaseInvoices;
        this.purchaseItems = purchaseItems;
        this.payments = payments;
        this.paymentReminders = paymentReminders;
        this.importBatches = importBatches;
        this.importErrors = importErrors;
        this.stagingProducts = stagingProducts;
        this.stagingCustomers = stagingCustomers;
        this.stagingSuppliers = stagingSuppliers;
        this.stagingMovements = stagingMovements;
        this.reorderSuggestions = reorderSuggestions;
        this.deadStockInsights = deadStockInsights;
        this.passwordEncoder = passwordEncoder;
        this.catalog = catalog;
        this.stockLedger = stockLedger;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (users.existsByEmailIgnoreCase("owner@demo.com")) {
            var owner = users.findByEmailIgnoreCase("owner@demo.com").orElseThrow();
            var membership = memberships.findFirstByUserId(owner.id).orElseThrow();
            TenantContext.set(membership.tenantId, owner.id, membership.role);
            seedDemoRoleAccounts(membership.tenantId);
            seedDemoShowcaseData(membership.tenantId);
            TenantContext.clear();
            return;
        }
        var tenant = new Tenant();
        tenant.name = "Demo FMCG Distributor";
        tenant.businessMode = DomainEnums.BusinessMode.HYBRID;
        tenant.currency = "INR";
        tenant.gstEnabled = true;
        tenants.save(tenant);

        var owner = new UserAccount();
        owner.email = "owner@demo.com";
        owner.fullName = "Demo Owner";
        owner.passwordHash = passwordEncoder.encode("password123");
        owner.emailVerified = true;
        owner.emailVerifiedAt = Instant.now();
        users.save(owner);

        var membership = new UserTenantMembership();
        membership.tenantId = tenant.id;
        membership.userId = owner.id;
        membership.role = DomainEnums.Role.OWNER;
        memberships.save(membership);
        TenantContext.set(tenant.id, owner.id, DomainEnums.Role.OWNER);

        var main = warehouse(tenant.id, "Main Godown", "MAIN");
        var retail = warehouse(tenant.id, "Retail Counter", "RETAIL");
        var unit = catalog.getOrCreateUnit("PCS");

        var suppliersList = List.of(
            supplier(tenant.id, "ABC FMCG Supplier"),
            supplier(tenant.id, "National Distributors"),
            supplier(tenant.id, "Metro Wholesale")
        );
        var customersList = List.of(
            customer(tenant.id, "Ravi Traders", new BigDecimal("45000")),
            customer(tenant.id, "Kumar Retail Store", new BigDecimal("25000")),
            customer(tenant.id, "City Supermart", new BigDecimal("100000")),
            customer(tenant.id, "Balaji Kirana", new BigDecimal("30000"))
        );
        var dealer = new UserAccount();
        dealer.email = "ravi@demo.com";
        dealer.fullName = "Ravi Traders Portal";
        dealer.passwordHash = passwordEncoder.encode("password123");
        dealer.emailVerified = true;
        dealer.emailVerifiedAt = Instant.now();
        users.save(dealer);
        var dealerMembership = new UserTenantMembership();
        dealerMembership.tenantId = tenant.id;
        dealerMembership.userId = dealer.id;
        dealerMembership.customerId = customersList.getFirst().id;
        dealerMembership.role = DomainEnums.Role.CUSTOMER_USER;
        memberships.save(dealerMembership);
        seedDemoRoleAccounts(tenant.id);

        var seeds = List.of(
            new ProductSeed("MAGGI70", "Maggi Masala Noodles 70g", "Instant Noodles", "Maggi", "8901058844505", "8.50", "12.00", "120", "35"),
            new ProductSeed("PARLEG250", "Parle-G 250g", "Biscuits", "Parle", "8901719101018", "19.00", "25.00", "80", "25"),
            new ProductSeed("DM50", "Dairy Milk 50g", "Confectionery", "Dairy Milk", "7622201145213", "32.00", "45.00", "45", "15"),
            new ProductSeed("SURF1KG", "Surf Excel 1kg", "Laundry", "Surf Excel", "8901030865476", "118.00", "145.00", "25", "10"),
            new ProductSeed("TATASALT1", "Tata Salt 1kg", "Staples", "Tata", "8904043901015", "18.00", "24.00", "70", "25"),
            new ProductSeed("DETTOL75", "Dettol Soap 75g", "Personal Care", "Dettol", "8901396311829", "27.00", "36.00", "55", "20"),
            new ProductSeed("FORTOIL1", "Fortune Oil 1L", "Edible Oil", "Fortune", "8906007280012", "112.00", "135.00", "35", "12"),
            new ProductSeed("COL100", "Colgate Toothpaste 100g", "Oral Care", "Colgate", "8901314011759", "44.00", "58.00", "35", "12"),
            new ProductSeed("GOODDAY", "Good Day Biscuit", "Biscuits", "Good Day", "8901063162113", "8.00", "12.00", "140", "40"),
            new ProductSeed("REDTEA250", "Red Label Tea 250g", "Beverages", "Red Label", "8901030972365", "92.00", "120.00", "20", "8")
        );
        var productList = seeds.stream().map(seed -> product(tenant.id, seed, unit.id)).toList();

        var start = LocalDate.now(ZoneOffset.UTC).minusDays(105);
        for (int i = 0; i < productList.size(); i++) {
            var product = productList.get(i);
            var qty = BigDecimal.valueOf(i == 0 ? 450 : 180 + (i * 25L));
            stockLedger.createMovement(tenant.id, product.id, main.id, DomainEnums.MovementType.OPENING_BALANCE, qty, unit.id, product.defaultPurchasePrice, "DEMO_SEED", UUID.randomUUID(), start.atStartOfDay().toInstant(ZoneOffset.UTC), "Demo opening balance");
            // Purchases replenish only Main Godown. Fund the counter's entire sales
            // history up front, retaining the intended stock for the current demo.
            var retailClosingStock = BigDecimal.valueOf(60 + (i * 5L));
            var retailOpeningStock = retailClosingStock.add(retailSalesDuringHistory(i));
            stockLedger.createMovement(tenant.id, product.id, retail.id, DomainEnums.MovementType.OPENING_BALANCE, retailOpeningStock, unit.id, product.defaultPurchasePrice, "DEMO_SEED", UUID.randomUUID(), start.atStartOfDay().toInstant(ZoneOffset.UTC), "Demo retail counter opening balance");
        }

        for (int day = HISTORY_DAYS; day >= 0; day--) {
            var date = LocalDate.now(ZoneOffset.UTC).minusDays(day);
            if (day % 18 == 0) {
                createPurchase(tenant.id, main.id, suppliersList.get(day / 18 % suppliersList.size()).id, productList, unit.id, date);
            }
            createSale(tenant.id, retailSaleDay(day) ? retail.id : main.id, customersList.get(day % customersList.size()).id, productList, unit.id, date, day);
        }

        var payment = new CustomerPayment();
        payment.tenantId = tenant.id;
        payment.customerId = customersList.getFirst().id;
        payment.amount = new BigDecimal("18000");
        payment.paymentDate = LocalDate.now(ZoneOffset.UTC).minusDays(8);
        payment.notes = "Demo part payment";
        payments.save(payment);
        seedDemoShowcaseData(tenant.id);
        TenantContext.clear();
    }

    private void seedDemoShowcaseData(UUID tenantId) {
        var main = warehouses.findByTenantIdAndNameIgnoreCase(tenantId, "Main Godown").orElseGet(() -> warehouse(tenantId, "Main Godown", "MAIN"));
        var unit = catalog.getOrCreateUnit("PCS");
        seedMessyDataQualityProducts(tenantId, main.id, unit.id);
        seedDemoTallyImportStatus(tenantId);
        seedGeneratedInsightRows(tenantId, main.id);
        seedPaymentReminders(tenantId);
    }

    private void seedDemoRoleAccounts(UUID tenantId) {
        var raviCustomerId = customers.findByTenantIdAndNameIgnoreCase(tenantId, "Ravi Traders").map(customer -> customer.id).orElse(null);
        List.of(
            new DemoUserSeed("owner@demo.com", "Demo Owner", DomainEnums.Role.OWNER, null),
            new DemoUserSeed("admin@demo.com", "Demo Admin", DomainEnums.Role.ADMIN, null),
            new DemoUserSeed("manager@demo.com", "Demo Manager", DomainEnums.Role.MANAGER, null),
            new DemoUserSeed("warehouse@demo.com", "Demo Warehouse Staff", DomainEnums.Role.WAREHOUSE_STAFF, null),
            new DemoUserSeed("sales@demo.com", "Demo Sales Staff", DomainEnums.Role.SALES_STAFF, null),
            new DemoUserSeed("purchase@demo.com", "Demo Purchase Manager", DomainEnums.Role.PURCHASE_MANAGER, null),
            new DemoUserSeed("accountant@demo.com", "Demo Accountant", DomainEnums.Role.ACCOUNTANT, null),
            new DemoUserSeed("viewer@demo.com", "Demo Viewer", DomainEnums.Role.VIEWER, null),
            new DemoUserSeed("auditor@demo.com", "Demo Auditor", DomainEnums.Role.AUDITOR, null),
            new DemoUserSeed("ravi@demo.com", "Ravi Traders Portal", DomainEnums.Role.CUSTOMER_USER, raviCustomerId)
        ).forEach(seed -> seedDemoUser(tenantId, seed));
    }

    private void seedDemoUser(UUID tenantId, DemoUserSeed seed) {
        var user = users.findByEmailIgnoreCase(seed.email()).orElseGet(() -> {
            var account = new UserAccount();
            account.email = seed.email();
            account.fullName = seed.fullName();
            account.passwordHash = passwordEncoder.encode("password123");
            account.emailVerified = true;
            account.emailVerifiedAt = Instant.now();
            return users.save(account);
        });
        if (!user.emailVerified) {
            user.emailVerified = true;
            user.emailVerifiedAt = Instant.now();
            users.save(user);
        }
        memberships.findByTenantIdAndUserId(tenantId, user.id).ifPresentOrElse(membership -> {
            membership.role = seed.role();
            membership.customerId = seed.customerId();
            memberships.save(membership);
        }, () -> {
            var membership = new UserTenantMembership();
            membership.tenantId = tenantId;
            membership.userId = user.id;
            membership.role = seed.role();
            membership.customerId = seed.customerId();
            memberships.save(membership);
        });
    }

    private void seedMessyDataQualityProducts(UUID tenantId, UUID warehouseId, UUID unitId) {
        var oldDate = LocalDate.now(ZoneOffset.UTC).minusDays(130).atStartOfDay().toInstant(ZoneOffset.UTC);
        var messyA = dataQualityProduct(tenantId, "DQ-MAGGI-70-A", "MAGGI 70GM", unitId, false);
        var messyB = dataQualityProduct(tenantId, "DQ-MAGGI-70-B", "MAGGI MASLA 70", unitId, false);
        var messyC = dataQualityProduct(tenantId, "DQ-MAGGI-70-C", "MAGGI NOODLES 70GM", unitId, false);
        for (var product : List.of(messyA, messyB, messyC)) {
            if (stockLedger.currentStock(tenantId, product.id, warehouseId).compareTo(BigDecimal.ZERO) == 0) {
                stockLedger.createMovement(tenantId, product.id, warehouseId, DomainEnums.MovementType.OPENING_BALANCE, new BigDecimal("24"), unitId, BigDecimal.ZERO, "DEMO_QUALITY_SEED", UUID.randomUUID(), oldDate, "Demo messy import stock");
            }
        }

        var noCost = dataQualityProduct(tenantId, "DQ-NOCOST-SALES", "Imported Promo Pack 100g", unitId, true);
        if (stockLedger.currentStock(tenantId, noCost.id, warehouseId).compareTo(BigDecimal.ZERO) == 0) {
            stockLedger.createMovement(tenantId, noCost.id, warehouseId, DomainEnums.MovementType.OPENING_BALANCE, new BigDecimal("40"), unitId, BigDecimal.ZERO, "DEMO_QUALITY_SEED", UUID.randomUUID(), LocalDate.now(ZoneOffset.UTC).minusDays(45).atStartOfDay().toInstant(ZoneOffset.UTC), "Demo no-cost stock");
        }
        if (!salesInvoices.existsByTenantIdAndInvoiceNumberIgnoreCase(tenantId, "DQ-SALE-NOCOST")) {
            var customerId = customers.findByTenantIdOrderByNameAsc(tenantId).stream().findFirst().map(customer -> customer.id).orElse(null);
            var invoice = new SalesInvoice();
            invoice.tenantId = tenantId;
            invoice.customerId = customerId;
            invoice.warehouseId = warehouseId;
            invoice.invoiceNumber = "DQ-SALE-NOCOST";
            invoice.invoiceDate = LocalDate.now(ZoneOffset.UTC).minusDays(7);
            salesInvoices.save(invoice);
            var item = new SalesInvoiceItem();
            item.tenantId = tenantId;
            item.salesInvoiceId = invoice.id;
            item.productId = noCost.id;
            item.quantity = new BigDecimal("3");
            item.unitId = unitId;
            item.rate = new BigDecimal("49");
            item.costRate = BigDecimal.ZERO;
            item.taxPercentage = BigDecimal.ZERO;
            item.taxAmount = BigDecimal.ZERO;
            item.lineTotal = new BigDecimal("147");
            salesItems.save(item);
            stockLedger.createMovement(tenantId, noCost.id, warehouseId, DomainEnums.MovementType.SALE, item.quantity, unitId, BigDecimal.ZERO, "SALES_INVOICE", invoice.id, invoice.invoiceDate.atStartOfDay().toInstant(ZoneOffset.UTC), "Demo sale with missing purchase cost");
            invoice.subtotal = item.lineTotal;
            invoice.taxAmount = BigDecimal.ZERO;
            invoice.totalAmount = item.lineTotal;
            salesInvoices.save(invoice);
        }
    }

    private Product dataQualityProduct(UUID tenantId, String sku, String name, UUID unitId, boolean hasCategory) {
        return products.findByTenantIdAndSkuIgnoreCase(tenantId, sku).orElseGet(() -> {
            var product = new Product();
            product.tenantId = tenantId;
            product.sku = sku;
            product.name = name;
            product.normalizedName = CatalogService.normalizeName(name);
            product.baseUnitId = unitId;
            product.categoryId = hasCategory ? catalog.getOrCreateCategory("Imported Uncategorized").id : null;
            product.hsnCode = null;
            product.gstPercentage = BigDecimal.ZERO;
            product.defaultPurchasePrice = BigDecimal.ZERO;
            product.defaultSalesPrice = hasCategory ? new BigDecimal("49") : BigDecimal.ZERO;
            product.reorderPoint = new BigDecimal("10");
            product.safetyStock = new BigDecimal("5");
            product.leadTimeDays = 5;
            product.minimumOrderQuantity = new BigDecimal("12");
            product.rawMetadata = Map.<String, Object>of("demoPurpose", "Data quality cleanup demo");
            return products.save(product);
        });
    }

    private void seedDemoTallyImportStatus(UUID tenantId) {
        var exists = importBatches.findByTenantId(tenantId, PageRequest.of(0, 50)).getContent().stream()
            .anyMatch(batch -> "demo-tally-vouchers.xml".equals(batch.originalFileName));
        if (exists) {
            return;
        }
        var batch = new ImportBatch();
        batch.tenantId = tenantId;
        batch.sourceType = DomainEnums.SourceType.TALLY_XML;
        batch.status = DomainEnums.ImportStatus.COMMITTED;
        batch.originalFileName = "demo-tally-vouchers.xml";
        batch.rowCount = 8;
        batch.validCount = 6;
        batch.errorCount = 2;
        batch.mappingJson = Map.<String, Object>of(
            "Item Name", "productName",
            "Voucher Date", "movementDate",
            "Godown", "warehouseName",
            "Qty", "quantity"
        );
        importBatches.save(batch);

        stagingProducts.save(stagingProduct(tenantId, batch.id, 1, "MAGGI MASLA 70", true));
        stagingProducts.save(stagingProduct(tenantId, batch.id, 2, "Parle-G 250g", true));
        stagingCustomers.save(stagingCustomer(tenantId, batch.id, 3, "Ravi Traders"));
        stagingCustomers.save(stagingCustomer(tenantId, batch.id, 4, "City Supermart"));
        stagingSuppliers.save(stagingSupplier(tenantId, batch.id, 5, "ABC FMCG Supplier"));
        stagingMovements.save(stagingMovement(tenantId, batch.id, 6, "Maggi Masala Noodles 70g", "SALE", new BigDecimal("18"), true));
        stagingMovements.save(stagingMovement(tenantId, batch.id, 7, "Parle-G 250g", "PURCHASE", new BigDecimal("60"), true));

        importErrors.save(importError(tenantId, batch.id, 8, "GST %", "UNMAPPED_FIELD", "GST percentage column was present but not mapped."));
        importErrors.save(importError(tenantId, batch.id, 8, "HSN Code", "MISSING_FIELD", "HSN code missing for imported item."));
    }

    private StagingProduct stagingProduct(UUID tenantId, UUID batchId, int rowNumber, String name, boolean committed) {
        var row = new StagingProduct();
        row.tenantId = tenantId;
        row.importBatchId = batchId;
        row.rowNumber = rowNumber;
        row.productName = name;
        row.unitCode = "PCS";
        row.openingStock = new BigDecimal("24");
        row.purchasePrice = new BigDecimal("8.50");
        row.salesPrice = new BigDecimal("12.00");
        row.warehouseName = "Main Godown";
        row.rawMetadata = Map.<String, Object>of("Item Name", name, "Godown", "Main Godown", "Source", "Demo Tally XML");
        row.committed = committed;
        return row;
    }

    private StagingCustomer stagingCustomer(UUID tenantId, UUID batchId, int rowNumber, String name) {
        var row = new StagingCustomer();
        row.tenantId = tenantId;
        row.importBatchId = batchId;
        row.rowNumber = rowNumber;
        row.name = name;
        row.gstin = "27DEMO1234F1Z5";
        row.rawMetadata = Map.<String, Object>of("Ledger Name", name, "Ledger Group", "Sundry Debtors");
        row.committed = true;
        return row;
    }

    private StagingSupplier stagingSupplier(UUID tenantId, UUID batchId, int rowNumber, String name) {
        var row = new StagingSupplier();
        row.tenantId = tenantId;
        row.importBatchId = batchId;
        row.rowNumber = rowNumber;
        row.name = name;
        row.gstin = "27SUPP1234F1Z5";
        row.rawMetadata = Map.<String, Object>of("Ledger Name", name, "Ledger Group", "Sundry Creditors");
        row.committed = true;
        return row;
    }

    private StagingStockMovement stagingMovement(UUID tenantId, UUID batchId, int rowNumber, String productName, String type, BigDecimal quantity, boolean committed) {
        var row = new StagingStockMovement();
        row.tenantId = tenantId;
        row.importBatchId = batchId;
        row.rowNumber = rowNumber;
        row.productName = productName;
        row.warehouseName = "Main Godown";
        row.movementType = type;
        row.quantity = quantity;
        row.rate = new BigDecimal("10.00");
        row.movementDate = LocalDate.now(ZoneOffset.UTC).minusDays(2);
        row.rawMetadata = Map.<String, Object>of("Item Name", productName, "Voucher Type", type, "Qty", quantity.toPlainString());
        row.committed = committed;
        return row;
    }

    private ImportError importError(UUID tenantId, UUID batchId, int rowNumber, String fieldName, String code, String message) {
        var error = new ImportError();
        error.tenantId = tenantId;
        error.importBatchId = batchId;
        error.rowNumber = rowNumber;
        error.fieldName = fieldName;
        error.errorCode = code;
        error.message = message;
        return error;
    }

    private void seedGeneratedInsightRows(UUID tenantId, UUID warehouseId) {
        var product = products.findByTenantIdAndSkuIgnoreCase(tenantId, "PARLEG250")
            .orElseGet(() -> products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).getFirst());
        if (reorderSuggestions.findByTenantIdOrderByGeneratedAtDesc(tenantId).isEmpty()) {
            var suggestion = new ReorderSuggestion();
            suggestion.tenantId = tenantId;
            suggestion.productId = product.id;
            suggestion.warehouseId = warehouseId;
            suggestion.reorderPoint = new BigDecimal("95");
            suggestion.suggestedQuantity = new BigDecimal("45");
            suggestion.reason = "Demo insight: sales increased in the last 4 weeks and stock is near reorder point.";
            suggestion.status = "OPEN";
            suggestion.generatedAt = Instant.now();
            reorderSuggestions.save(suggestion);
        }
        if (deadStockInsights.findByTenantIdOrderByStockValueDesc(tenantId).isEmpty()) {
            var deadProduct = products.findByTenantIdAndSkuIgnoreCase(tenantId, "GOODDAY").orElse(product);
            var insight = new DeadStockInsight();
            insight.tenantId = tenantId;
            insight.productId = deadProduct.id;
            insight.warehouseId = warehouseId;
            insight.stockQuantity = new BigDecimal("180");
            insight.stockValue = new BigDecimal("1440");
            insight.lastSoldDate = null;
            insight.suggestedAction = "bundle with fast-moving item";
            insight.explanation = "Demo insight: stock is available but no recent sales were found.";
            insight.generatedAt = Instant.now();
            deadStockInsights.save(insight);
        }
    }

    private void seedPaymentReminders(UUID tenantId) {
        if (!paymentReminders.findByTenantIdOrderByReminderDateAscCreatedAtDesc(tenantId).isEmpty()) {
            return;
        }
        paymentReminder(tenantId, "City Supermart", new BigDecimal("32000"), -1, "11:00", "Call City Supermart accounts team for pending invoice follow-up.");
        paymentReminder(tenantId, "Ravi Traders", new BigDecimal("12000"), 0, "16:00", "Ravi Traders promised UPI payment today.");
        paymentReminder(tenantId, "Kumar Retail Store", new BigDecimal("8000"), 3, "10:30", "Follow up after weekly delivery.");
    }

    private void paymentReminder(UUID tenantId, String customerName, BigDecimal amount, int dayOffset, String time, String notes) {
        customers.findByTenantIdAndNameIgnoreCase(tenantId, customerName).ifPresent(customer -> {
            var reminder = new PaymentReminder();
            reminder.tenantId = tenantId;
            reminder.customerId = customer.id;
            reminder.amountDue = amount;
            reminder.reminderDate = LocalDate.now(ZoneOffset.UTC).plusDays(dayOffset);
            reminder.reminderTime = time;
            reminder.status = "OPEN";
            reminder.notes = notes;
            paymentReminders.save(reminder);
        });
    }

    private Warehouse warehouse(UUID tenantId, String name, String code) {
        var warehouse = new Warehouse();
        warehouse.tenantId = tenantId;
        warehouse.name = name;
        warehouse.code = code;
        return warehouses.save(warehouse);
    }

    private Supplier supplier(UUID tenantId, String name) {
        var supplier = new Supplier();
        supplier.tenantId = tenantId;
        supplier.name = name;
        supplier.gstin = "27ABCDE" + Math.abs(name.hashCode() % 9999) + "F1Z5";
        return suppliers.save(supplier);
    }

    private Customer customer(UUID tenantId, String name, BigDecimal creditLimit) {
        var customer = new Customer();
        customer.tenantId = tenantId;
        customer.name = name;
        customer.creditLimit = creditLimit;
        customer.openingBalance = BigDecimal.ZERO;
        return customers.save(customer);
    }

    private Product product(UUID tenantId, ProductSeed seed, UUID unitId) {
        var product = new Product();
        product.tenantId = tenantId;
        product.sku = seed.sku();
        product.name = seed.name();
        product.normalizedName = CatalogService.normalizeName(seed.name());
        product.categoryId = catalog.getOrCreateCategory(seed.category()).id;
        product.brandId = catalog.getOrCreateBrand(seed.brand()).id;
        product.baseUnitId = unitId;
        product.hsnCode = "1905";
        product.gstPercentage = new BigDecimal("5.00");
        product.defaultPurchasePrice = new BigDecimal(seed.cost());
        product.defaultSalesPrice = new BigDecimal(seed.price());
        product.reorderPoint = new BigDecimal(seed.reorderPoint());
        product.safetyStock = new BigDecimal(seed.safetyStock());
        product.leadTimeDays = 5;
        product.minimumOrderQuantity = new BigDecimal("24");
        products.save(product);
        var barcode = new ProductBarcode();
        barcode.tenantId = tenantId;
        barcode.productId = product.id;
        barcode.barcode = seed.barcode();
        barcodes.save(barcode);
        return product;
    }

    private void createPurchase(UUID tenantId, UUID warehouseId, UUID supplierId, List<Product> productList, UUID unitId, LocalDate date) {
        var invoice = new PurchaseInvoice();
        invoice.tenantId = tenantId;
        invoice.supplierId = supplierId;
        invoice.warehouseId = warehouseId;
        invoice.invoiceNumber = "PUR-DEMO-" + date;
        invoice.invoiceDate = date;
        purchaseInvoices.save(invoice);
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (int i = 0; i < productList.size(); i++) {
            var product = productList.get(i);
            var qty = BigDecimal.valueOf(i < 8 ? 40 + (i * 4L) : 16);
            var lineSubtotal = qty.multiply(product.defaultPurchasePrice);
            var lineTax = lineSubtotal.multiply(product.gstPercentage).divide(BigDecimal.valueOf(100));
            var item = new PurchaseInvoiceItem();
            item.tenantId = tenantId;
            item.purchaseInvoiceId = invoice.id;
            item.productId = product.id;
            item.quantity = qty;
            item.unitId = unitId;
            item.rate = product.defaultPurchasePrice;
            item.taxPercentage = product.gstPercentage;
            item.taxAmount = lineTax;
            item.lineTotal = lineSubtotal.add(lineTax);
            purchaseItems.save(item);
            stockLedger.createMovement(tenantId, product.id, warehouseId, DomainEnums.MovementType.PURCHASE, qty, unitId, product.defaultPurchasePrice, "PURCHASE_INVOICE", invoice.id, date.atStartOfDay().toInstant(ZoneOffset.UTC), "Demo purchase");
            subtotal = subtotal.add(lineSubtotal);
            tax = tax.add(lineTax);
        }
        invoice.subtotal = subtotal;
        invoice.taxAmount = tax;
        invoice.totalAmount = subtotal.add(tax);
        purchaseInvoices.save(invoice);
    }

    private void createSale(UUID tenantId, UUID warehouseId, UUID customerId, List<Product> productList, UUID unitId, LocalDate date, int day) {
        var invoice = new SalesInvoice();
        invoice.tenantId = tenantId;
        invoice.customerId = customerId;
        invoice.warehouseId = warehouseId;
        invoice.invoiceNumber = "SAL-DEMO-" + date + "-" + day;
        invoice.invoiceDate = date;
        salesInvoices.save(invoice);
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (int i = 0; i < Math.min(8, productList.size()); i++) {
            var qty = saleQuantity(i, day);
            if (qty.signum() == 0) {
                continue;
            }
            var product = productList.get(i);
            var lineSubtotal = qty.multiply(product.defaultSalesPrice);
            var lineTax = lineSubtotal.multiply(product.gstPercentage).divide(BigDecimal.valueOf(100));
            var item = new SalesInvoiceItem();
            item.tenantId = tenantId;
            item.salesInvoiceId = invoice.id;
            item.productId = product.id;
            item.quantity = qty;
            item.unitId = unitId;
            item.rate = product.defaultSalesPrice;
            item.costRate = product.defaultPurchasePrice;
            item.taxPercentage = product.gstPercentage;
            item.taxAmount = lineTax;
            item.lineTotal = lineSubtotal.add(lineTax);
            salesItems.save(item);
            stockLedger.createMovement(tenantId, product.id, warehouseId, DomainEnums.MovementType.SALE, qty, unitId, product.defaultPurchasePrice, "SALES_INVOICE", invoice.id, date.atStartOfDay().toInstant(ZoneOffset.UTC), "Demo sale");
            subtotal = subtotal.add(lineSubtotal);
            tax = tax.add(lineTax);
        }
        invoice.subtotal = subtotal;
        invoice.taxAmount = tax;
        invoice.totalAmount = subtotal.add(tax);
        salesInvoices.save(invoice);
    }

    private static boolean retailSaleDay(int day) { return day % 3 == 0; }

    private static BigDecimal saleQuantity(int productIndex, int day) {
        if (productIndex >= 8 || (productIndex == 7 && day % 4 != 0)) return BigDecimal.ZERO;
        return BigDecimal.valueOf(1 + ((day + productIndex) % (productIndex < 3 ? 8 : 4)));
    }

    private static BigDecimal retailSalesDuringHistory(int productIndex) {
        var total = BigDecimal.ZERO;
        for (int day = HISTORY_DAYS; day >= 0; day--) {
            if (retailSaleDay(day)) total = total.add(saleQuantity(productIndex, day));
        }
        return total;
    }

    private record ProductSeed(String sku, String name, String category, String brand, String barcode, String cost, String price, String reorderPoint, String safetyStock) {
    }

    private record DemoUserSeed(String email, String fullName, DomainEnums.Role role, UUID customerId) {
    }
}
