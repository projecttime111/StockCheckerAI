package com.stockpilot.ai.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TenantIsolationGuardrailTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Pattern UNSAFE_REPOSITORY_ACCESS = Pattern.compile(
        "\\b([A-Za-z][A-Za-z0-9_]*)\\s*\\.\\s*(findById|findAll|deleteById|existsById|getReferenceById)\\s*\\("
    );
    private static final Pattern TENANT_OWNED_ENTITY = Pattern.compile("class\\s+([A-Za-z0-9_]+)\\s+extends\\s+TenantOwnedEntity\\b");
    private static final Pattern REPOSITORY_INTERFACE = Pattern.compile(
        "public\\s+interface\\s+([A-Za-z0-9_]+)\\s+extends\\s+JpaRepository\\s*<\\s*([A-Za-z0-9_]+)\\s*,\\s*UUID\\s*>\\s*\\{(?<body>.*?)\\n\\s*\\}",
        Pattern.DOTALL
    );
    private static final Pattern DERIVED_REPOSITORY_METHOD = Pattern.compile(
        "\\b(?:Optional|List|Page|boolean|long|BigDecimal)\\s*(?:<[^;]+>)?\\s+((?:find|findFirst|exists|delete|count)[A-Z][A-Za-z0-9_]*)\\s*\\("
    );
    private static final Set<String> TENANT_OWNED_TABLES = Set.of(
        "products",
        "warehouses",
        "customers",
        "suppliers",
        "sales_invoices",
        "sales_invoice_items",
        "purchase_invoices",
        "purchase_invoice_items",
        "stock_movements",
        "import_batches",
        "import_files",
        "import_errors",
        "forecast_results",
        "reorder_suggestions",
        "dead_stock_insights",
        "audit_logs",
        "customer_price_lists",
        "sales_orders",
        "sales_order_items",
        "purchase_orders",
        "purchase_order_items",
        "customer_payments",
        "payment_reminders"
    );

    // Documented safe exceptions: these are auth or tenant-setting lookups, not tenant-owned business records.
    private static final Map<String, List<String>> UNSCOPED_ACCESS_ALLOWLIST = Map.of(
        "config/JwtAuthenticationFilter.java", List.of("users.findById"),
        "service/PermissionService.java", List.of("tenants.findById"),
        "service/PortalService.java", List.of("tenants.findById"),
        "service/StockLedgerService.java", List.of("tenants.findById"),
        "service/TenantService.java", List.of("tenants.findById")
    );

    // Exact call-site exceptions, not permission to read arbitrary IDs from these services:
    // - Global app_users identity reads use only the authenticated account ID (including workspaceless invitations).
    //   ClerkAuthIntegrationTest and WorkspaceTeamIntegrationTest cover cross-account linking/acceptance and denial.
    // - Account deletion checks tenants reached only through the caller's OWNER memberships, including suspended
    //   tenants, so account deletion cannot orphan an owned business (ClerkAuthIntegrationTest).
    // - PlatformAdminAudit is a separate global operational audit, not tenant audit_logs. Its service requires a
    //   tenantless session, current global DB role, and platform.audit.view; PlatformAdminIntegrationTest covers
    //   business-user denial, platform-role separation, filtering, and tenant business-data isolation.
    // - Durable import workers reauthorize the server-recorded requesting account before establishing context;
    //   the persisted job tenant and current membership/operation permission are independently checked.
    private static final Map<String, List<String>> VERIFIED_IDENTITY_AND_PLATFORM_CALLS = Map.of(
        "service/AuthSessionService.java", List.of("users.findById(TenantContext.userId())"),
        "service/WorkspaceInvitationService.java", List.of("users.findById(TenantContext.userId())"),
        "service/AccountLifecycleService.java", List.of("tenants.findById(membership.tenantId)"),
        "service/PlatformAdminService.java", List.of("audits.findAll(specification,PageRequest.of("),
        "service/ImportJobService.java", List.of("users.findById(job.requestedBy)")
    );

    @Test
    void productionSourceDoesNotUseUnsafeUnscopedTenantOwnedAccess() throws IOException {
        assertThat(scanUnsafeRepositoryAccess(SOURCE_ROOT)).isEmpty();
    }

    @Test
    void guardrailDetectsUnsafeFindByIdUsage() {
        var source = """
            class UnsafeProductService {
                void load(java.util.UUID id) {
                    products.findById(id);
                }
            }
            """;

        assertThat(scanUnsafeRepositoryAccess("service/UnsafeProductService.java", source))
            .anySatisfy(violation -> assertThat(violation.message()).contains("products.findById"));
    }

    @Test
    void allowlistedAuthUserLookupDoesNotFailGuardrail() {
        var source = """
            class JwtAuthenticationFilter {
                void authenticate(java.util.UUID id) {
                    users.findById(id);
                }
            }
            """;

        assertThat(scanUnsafeRepositoryAccess("config/JwtAuthenticationFilter.java", source)).isEmpty();
    }

    @Test
    void approvedIdentityReadsDoNotAllowClientSelectedUsersOrBusinessRecords() {
        assertThat(scanUnsafeRepositoryAccess("service/ImportJobService.java", "users.findById(job.requestedBy);")).isEmpty();
        assertThat(scanUnsafeRepositoryAccess("service/ImportJobService.java", "users.findById(request.userId());")).hasSize(1);
        assertThat(scanUnsafeRepositoryAccess("service/ImportJobService.java", "products.findById(job.requestedBy);")).hasSize(1);
        for (var path : List.of("service/AuthSessionService.java", "service/WorkspaceInvitationService.java")) {
            assertThat(scanUnsafeRepositoryAccess(path, "users.findById(TenantContext.userId());")).isEmpty();
            assertThat(scanUnsafeRepositoryAccess(path, "users.findById(request.userId());")).hasSize(1);
            assertThat(scanUnsafeRepositoryAccess(path, "users.findById(TenantContext.userId()); users.findById(request.userId());")).hasSize(1);
            assertThat(scanUnsafeRepositoryAccess(path, "users.findById(TenantContext.userId()); products.findById(id);"))
                .singleElement().satisfies(violation -> assertThat(violation.message()).contains("products.findById"));
        }
        assertThat(scanUnsafeRepositoryAccess("service/AccountLifecycleService.java", "tenants.findById(request.tenantId());")).hasSize(1);
        assertThat(scanUnsafeRepositoryAccess("service/PlatformAdminService.java", "audits.findAll(); products.findAll();")).hasSize(2);
        assertThat(scanUnsafeRepositoryAccess("service/OtherService.java", "audits.findAll(specification,PageRequest.of(0,10));")).hasSize(1);
    }

    @Test
    void tenantOwnedRepositoryDerivedMethodsRemainTenantScoped() throws IOException {
        var repositories = Files.readString(SOURCE_ROOT.resolve("com/stockpilot/ai/repo/Repositories.java"));
        var tenantOwnedEntities = tenantOwnedEntities();

        assertThat(scanTenantOwnedRepositoryMethods(repositories, tenantOwnedEntities)).isEmpty();
    }

    @Test
    void nativeQueriesAgainstTenantOwnedTablesFilterByTenantId() throws IOException {
        var violations = new ArrayList<Violation>();
        try (var paths = javaFiles(SOURCE_ROOT)) {
            paths.forEach(path -> {
                try {
                    violations.addAll(scanNativeQueries(path, Files.readString(path)));
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }

        assertThat(violations).isEmpty();
    }

    private Set<String> tenantOwnedEntities() throws IOException {
        var entities = new java.util.TreeSet<String>();
        try (var paths = javaFiles(SOURCE_ROOT.resolve("com/stockpilot/ai/domain"))) {
            paths.forEach(path -> {
                try {
                    var matcher = TENANT_OWNED_ENTITY.matcher(Files.readString(path));
                    if (matcher.find()) {
                        entities.add(matcher.group(1));
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
        return entities;
    }

    private List<Violation> scanUnsafeRepositoryAccess(Path root) throws IOException {
        var violations = new ArrayList<Violation>();
        try (var paths = javaFiles(root)) {
            paths.forEach(path -> {
                try {
                    violations.addAll(scanUnsafeRepositoryAccess(relativePath(path), Files.readString(path)));
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
        return violations;
    }

    private List<Violation> scanUnsafeRepositoryAccess(String relativePath, String source) {
        var violations = new ArrayList<Violation>();
        var lines = source.lines().toList();
        for (var index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            var matcher = UNSAFE_REPOSITORY_ACCESS.matcher(line);
            while (matcher.find()) {
                var token = matcher.group(1) + "." + matcher.group(2);
                if (!isAllowlisted(relativePath, token, line.substring(matcher.start()))) {
                    violations.add(new Violation(relativePath, index + 1, "Unsafe unscoped repository access: " + token));
                }
            }
        }
        return violations;
    }

    private List<Violation> scanTenantOwnedRepositoryMethods(String repositories, Set<String> tenantOwnedEntities) {
        var violations = new ArrayList<Violation>();
        var matcher = REPOSITORY_INTERFACE.matcher(repositories);
        while (matcher.find()) {
            var repositoryName = matcher.group(1);
            var entityName = matcher.group(2);
            if (!tenantOwnedEntities.contains(entityName)) {
                continue;
            }
            var body = matcher.group("body");
            var methodMatcher = DERIVED_REPOSITORY_METHOD.matcher(body);
            while (methodMatcher.find()) {
                var methodName = methodMatcher.group(1);
                if (!methodName.contains("ByTenantId")) {
                    violations.add(new Violation(
                        "repo/Repositories.java",
                        lineNumber(repositories, matcher.start() + methodMatcher.start()),
                        "Tenant-owned repository " + repositoryName + " has unscoped derived method " + methodName
                    ));
                }
            }
        }
        return violations;
    }

    private List<Violation> scanNativeQueries(Path path, String source) {
        var violations = new ArrayList<Violation>();
        var relativePath = relativePath(path);
        var lines = source.lines().toList();
        for (var index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            if (!line.contains("@Query")) {
                continue;
            }
            var block = new StringBuilder(line);
            var end = index;
            while (end + 1 < lines.size() && !lines.get(end).contains("nativeQuery = true")) {
                end++;
                block.append('\n').append(lines.get(end));
            }
            var query = block.toString();
            if (query.contains("nativeQuery = true") && referencesTenantOwnedTable(query) && !query.toLowerCase().contains("tenant_id")) {
                violations.add(new Violation(relativePath, index + 1, "Native query against tenant-owned table must filter tenant_id"));
            }
        }
        return violations;
    }

    private boolean referencesTenantOwnedTable(String query) {
        var lower = query.toLowerCase();
        return TENANT_OWNED_TABLES.stream().anyMatch(lower::contains);
    }

    private boolean isAllowlisted(String relativePath, String token, String callSource) {
        var normalized = relativePath.replace('\\', '/');
        return UNSCOPED_ACCESS_ALLOWLIST.entrySet().stream()
            .filter(entry -> normalized.endsWith(entry.getKey()))
            .flatMap(entry -> entry.getValue().stream())
            .anyMatch(token::equals)
            || VERIFIED_IDENTITY_AND_PLATFORM_CALLS.entrySet().stream()
                .filter(entry -> normalized.endsWith(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .anyMatch(call -> call.startsWith(token + "(") && callSource.startsWith(call));
    }

    private Stream<Path> javaFiles(Path root) throws IOException {
        return Files.walk(root)
            .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"));
    }

    private String relativePath(Path path) {
        return SOURCE_ROOT.relativize(path).toString().replace('\\', '/');
    }

    private int lineNumber(String source, int offset) {
        var count = 1;
        for (var index = 0; index < offset && index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }

    private record Violation(String path, int line, String message) {
        @Override
        public String toString() {
            return path + ":" + line + " - " + message;
        }
    }
}
