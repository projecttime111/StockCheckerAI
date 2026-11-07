package com.stockpilot.ai.service;

import com.stockpilot.ai.config.JwtService;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.Tenant;
import com.stockpilot.ai.domain.UserTenantMembership;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountLifecycleService {
    private static final String RESET_CONFIRMATION = "RESET WORKSPACE DATA";
    private static final String DELETE_WORKSPACE_CONFIRMATION = "DELETE WORKSPACE";
    private static final String DELETE_ACCOUNT_CONFIRMATION = "DELETE MY ACCOUNT";

    private final EntityManager entityManager;
    private final Repositories.TenantRepository tenants;
    private final Repositories.UserRepository users;
    private final Repositories.MembershipRepository memberships;
    private final ObjectStorageService objectStorage;
    private final AuditService audit;
    private final JwtService jwtService;
    private final TenantWriteLockService locks;

    public AccountLifecycleService(
        EntityManager entityManager,
        Repositories.TenantRepository tenants,
        Repositories.UserRepository users,
        Repositories.MembershipRepository memberships,
        ObjectStorageService objectStorage,
        AuditService audit,
        JwtService jwtService, TenantWriteLockService locks
    ) {
        this.entityManager = entityManager;
        this.tenants = tenants;
        this.users = users;
        this.memberships = memberships;
        this.objectStorage = objectStorage;
        this.audit = audit;
        this.jwtService = jwtService;
        this.locks = locks;
    }

    @Transactional(readOnly = true)
    public ApiDtos.DeleteImpactResponse deleteImpact() {
        var tenantId = TenantContext.tenantId();
        return new ApiDtos.DeleteImpactResponse(
            count("products", tenantId),
            count("customers", tenantId),
            count("suppliers", tenantId),
            count("warehouses", tenantId),
            count("stock_movements", tenantId),
            count("sales_invoices", tenantId),
            count("purchase_invoices", tenantId),
            count("import_batches", tenantId),
            count("import_files", tenantId) + count("import_session_files", tenantId),
            count("forecast_results", tenantId),
            count("dead_stock_insights", tenantId),
            count("audit_logs", tenantId),
            count("import_files", tenantId) + count("import_session_files", tenantId)
        );
    }

    @Transactional
    public ApiDtos.WorkspaceResetResponse resetCurrentWorkspace(String confirmation) {
        requireOwner();
        requireConfirmation(confirmation, RESET_CONFIRMATION);
        var tenantId = TenantContext.tenantId();
        var actorUserId = TenantContext.userId();
        var outcome = deleteTenantBusinessData(tenantId);
        audit.log(tenantId, actorUserId, "WORKSPACE_DATA_RESET", "Tenant", tenantId, Map.of("deleted", outcome.deleted));
        scheduleFileCleanup(tenantId, actorUserId, outcome.storageKeys);
        return new ApiDtos.WorkspaceResetResponse(tenantId, outcome.deleted, outcome.storageKeys.size());
    }

    @Transactional
    public ApiDtos.WorkspaceDeletionResponse deleteCurrentWorkspace(String confirmation) {
        requireOwner();
        requireConfirmation(confirmation, DELETE_WORKSPACE_CONFIRMATION);
        var tenantId = TenantContext.tenantId();
        var actorUserId = TenantContext.userId();
        var tenant = activeTenant(tenantId);
        var account = users.findAccountForLifecycle(actorUserId).orElseThrow(() -> ApiErrors.notFound("User account not found"));
        var outcome = deleteTenantBusinessData(tenantId);

        tenant.status = DomainEnums.TenantStatus.DELETED;
        tenant.deletedAt = Instant.now();
        tenants.save(tenant);
        audit.log(tenantId, actorUserId, "WORKSPACE_DELETED", "Tenant", tenantId, Map.of("deleted", outcome.deleted));

        memberships.deleteAll(memberships.findByTenantId(tenantId));
        memberships.flush();

        var nextMembership = memberships.findByUserId(actorUserId).stream()
            .filter(candidate -> tenants.findByIdAndStatus(candidate.tenantId, DomainEnums.TenantStatus.ACTIVE).isPresent())
            .findFirst();
        var onboardingRequired = nextMembership.isEmpty();
        var token = "clerk".equals(TenantContext.authProvider()) ? null : nextMembership
            .map(activeMembership -> jwtService.issue(account.id, activeMembership.tenantId, account.email, activeMembership.role, account.authVersion))
            .orElseGet(() -> jwtService.issueAccountOnly(account.id, account.email, account.authVersion));
        scheduleFileCleanup(tenantId, actorUserId, outcome.storageKeys);
        return new ApiDtos.WorkspaceDeletionResponse(
            "DELETED",
            tenantId,
            nextMembership.map(activeMembership -> activeMembership.tenantId).orElse(null),
            onboardingRequired,
            token,
            nextMembership.map(activeMembership -> activeMembership.role).orElse(null)
        );
    }

    @Transactional
    public ApiDtos.AuthResponse createWorkspace(ApiDtos.WorkspaceCreateRequest request) {
        if ("clerk".equals(TenantContext.authProvider())) throw ApiErrors.badRequest("Create or select a Clerk organization and use Clerk workspace onboarding");
        var userId = TenantContext.userId();
        var account = users.findAccountForLifecycle(userId).orElseThrow(() -> ApiErrors.notFound("User account not found"));
        var hasActiveWorkspace = memberships.findByUserId(userId).stream()
            .anyMatch(membership -> tenants.findByIdAndStatus(membership.tenantId, DomainEnums.TenantStatus.ACTIVE).isPresent());
        if (TenantContext.tenantIdOrNull() != null || hasActiveWorkspace) {
            throw ApiErrors.conflict("This account already has an active workspace");
        }

        var tenant = new Tenant();
        tenant.name = request.businessName();
        tenant.businessMode = request.businessMode();
        tenant.currency = request.currency();
        tenant.gstEnabled = request.gstEnabled();
        tenants.save(tenant);

        var membership = new UserTenantMembership();
        membership.tenantId = tenant.id;
        membership.userId = userId;
        membership.role = DomainEnums.Role.OWNER;
        memberships.save(membership);
        audit.log(tenant.id, userId, "WORKSPACE_CREATED", "Tenant", tenant.id, Map.of("onboarding", true));
        var token = jwtService.issue(userId, tenant.id, account.email, membership.role, account.authVersion);
        return new ApiDtos.AuthResponse(token, tenant.id, userId, membership.role, account.email, account.fullName);
    }

    @Transactional
    public ApiDtos.AccountDeletionResponse deleteMyAccount(String confirmation) {
        requireConfirmation(confirmation, DELETE_ACCOUNT_CONFIRMATION);
        var userId = TenantContext.userId();
        var account = users.findAccountForLifecycle(userId).orElseThrow(() -> ApiErrors.notFound("User account not found"));
        var userMemberships = memberships.findByUserId(userId);
        var ownsActiveWorkspace = userMemberships.stream().anyMatch(membership ->
            membership.role == DomainEnums.Role.OWNER
                && tenants.findById(membership.tenantId).filter(tenant -> tenant.status != DomainEnums.TenantStatus.DELETED).isPresent()
        );
        if (ownsActiveWorkspace) {
            throw ApiErrors.conflict("Delete or transfer owned workspaces before deleting your account.");
        }

        audit.log(TenantContext.tenantIdOrNull(), userId, "ACCOUNT_DEACTIVATED", "UserAccount", userId, Map.of("selfService", true));
        memberships.deleteAll(userMemberships);
        account.active = false;
        account.email = "deleted+" + userId + "@deleted.local";
        account.fullName = "Deleted User";
        users.save(account);
        return new ApiDtos.AccountDeletionResponse("DEACTIVATED", userId);
    }

    private ResetOutcome deleteTenantBusinessData(UUID tenantId) {
        locks.lock(tenantId);
        List<?> storedKeys = entityManager.createNativeQuery("""
                select storage_key from import_files where tenant_id = :tenantId
                union all
                select storage_key from import_session_files where tenant_id = :tenantId
                """)
            .setParameter("tenantId", tenantId)
            .getResultList();
        var storageKeys = storedKeys.stream()
            .map(String.class::cast)
            .toList();

        entityManager.createNativeQuery("update user_tenant_memberships set customer_id = null where tenant_id = :tenantId")
            .setParameter("tenantId", tenantId)
            .executeUpdate();

        var deleted = new LinkedHashMap<String, Integer>();
        for (var table : deletionOrder()) {
            deleted.put(table, entityManager.createNativeQuery("delete from " + table + " where tenant_id = :tenantId")
                .setParameter("tenantId", tenantId)
                .executeUpdate());
        }
        return new ResetOutcome(deleted, storageKeys);
    }

    private List<String> deletionOrder() {
        return List.of(
            "mutation_receipts",
            "financial_adjustment_items",
            "financial_adjustments",
            "outstanding_snapshots",
            "external_record_mappings",
            "smart_import_effects",
            "smart_import_commits",
            "import_dry_run_items",
            "import_dry_runs",
            "legacy_import_raw_rows",
            "smart_staged_cashbook_entries",
            "ai_messages",
            "delivery_challans",
            "payment_reminders",
            "sales_order_items",
            "purchase_order_items",
            "sales_invoice_items",
            "purchase_invoice_items",
            "customer_payments",
            "retail_counter_sales",
            "supplier_payments",
            "smart_staged_transfer_candidates",
            "stock_movements",
            "stock_snapshot_baselines",
            "forecast_results",
            "reorder_suggestions",
            "dead_stock_insights",
            "profit_insights",
            "ai_conversations",
            "import_effects",
            "import_plan_issues",
            "import_plans",
            "smart_staged_voucher_items",
            "smart_staged_stock_snapshots",
            "smart_staged_stock_ageing",
            "smart_staged_vouchers",
            "smart_staged_products",
            "smart_staged_parties",
            "smart_staged_warehouses",
            "smart_staged_units",
            "smart_import_resolutions",
            "import_errors",
            "staging_stock_movements",
            "staging_invoices",
            "staging_suppliers",
            "staging_customers",
            "staging_products",
            "import_session_files",
            "import_sessions",
            "import_files",
            "import_mapping_templates",
            "import_batches",
            "sales_orders",
            "purchase_orders",
            "sales_invoices",
            "purchase_invoices",
            "customer_price_lists",
            "product_barcodes",
            "product_tax_infos",
            "product_batches",
            "reorder_settings",
            "unit_conversions",
            "products",
            "customer_groups",
            "customers",
            "suppliers",
            "warehouses",
            "branches",
            "brands",
            "product_categories",
            "units_of_measure",
            "audit_logs"
        );
    }

    private long count(String table, UUID tenantId) {
        return ((Number) entityManager.createNativeQuery("select count(*) from " + table + " where tenant_id = :tenantId")
            .setParameter("tenantId", tenantId)
            .getSingleResult()).longValue();
    }

    private Tenant activeTenant(UUID tenantId) {
        return tenants.findByIdAndStatus(tenantId, DomainEnums.TenantStatus.ACTIVE)
            .orElseThrow(() -> ApiErrors.notFound("Active workspace not found"));
    }

    private void requireOwner() {
        if (TenantContext.role() != DomainEnums.Role.OWNER) {
            throw ApiErrors.forbidden("Only the workspace owner can perform this action");
        }
    }

    private void requireConfirmation(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw ApiErrors.badRequest("Typed confirmation must be " + expected);
        }
    }

    private void scheduleFileCleanup(UUID tenantId, UUID actorUserId, List<String> storageKeys) {
        if (storageKeys.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (var storageKey : storageKeys) {
                    try {
                        objectStorage.delete(storageKey);
                    } catch (Exception ex) {
                        audit.log(tenantId, actorUserId, "UPLOADED_FILE_DELETE_FAILED", "ImportFile", null, Map.of("storageKey", storageKey));
                    }
                }
            }
        });
    }

    private record ResetOutcome(LinkedHashMap<String, Integer> deleted, List<String> storageKeys) {
    }
}
