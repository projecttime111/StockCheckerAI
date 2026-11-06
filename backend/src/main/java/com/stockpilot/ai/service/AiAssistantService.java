package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.AiConversation;
import com.stockpilot.ai.domain.AiMessage;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AiAssistantService {
    private static final String ACCESS_DENIED_RESPONSE = "You do not have permission to access this information.";
    private static final String NO_BUSINESS_DATA_RESPONSE = "No business data is available yet. Import Tally/Excel data or add products, purchases, and sales first.";

    private final DashboardService dashboard;
    private final ForecastService forecast;
    private final Repositories.AiConversationRepository conversations;
    private final Repositories.AiMessageRepository messages;
    private final Repositories.CustomerRepository customers;
    private final Repositories.SupplierRepository suppliers;
    private final Repositories.SalesInvoiceRepository invoices;
    private final OutstandingService outstanding;
    private final ProductCategorizationService categorization;
    private final ProfitInsightService profitInsights;
    private final ReorderEngineService reorderEngine;
    private final DataQualityService dataQuality;
    private final PermissionService permissions;
    private final AuditService audit;
    private final SensitiveDataService sensitive;

    public AiAssistantService(
        DashboardService dashboard,
        ForecastService forecast,
        Repositories.AiConversationRepository conversations,
        Repositories.AiMessageRepository messages,
        Repositories.CustomerRepository customers,
        Repositories.SupplierRepository suppliers,
        Repositories.SalesInvoiceRepository invoices,
        OutstandingService outstanding,
        ProductCategorizationService categorization,
        ProfitInsightService profitInsights,
        ReorderEngineService reorderEngine,
        DataQualityService dataQuality,
        PermissionService permissions,
        AuditService audit, SensitiveDataService sensitive
    ) {
        this.dashboard = dashboard;
        this.forecast = forecast;
        this.conversations = conversations;
        this.messages = messages;
        this.customers = customers;
        this.suppliers = suppliers;
        this.invoices = invoices;
        this.outstanding = outstanding;
        this.categorization = categorization;
        this.profitInsights = profitInsights;
        this.reorderEngine = reorderEngine;
        this.dataQuality = dataQuality;
        this.permissions = permissions;
        this.audit = audit;
        this.sensitive = sensitive;
    }

    @Transactional
    public ApiDtos.ChatResponse chat(ApiDtos.ChatRequest request) {
        var tenantId = TenantContext.tenantId();
        var conversation = request.conversationId() == null
            ? newConversation(tenantId, request.message())
            : conversations.findByTenantIdAndId(tenantId, request.conversationId()).orElseGet(() -> newConversation(tenantId, request.message()));
        saveMessage(tenantId, conversation.id, DomainEnums.AiRole.USER, request.message(), Map.of());
        var lower = request.message().toLowerCase();
        Map<String, Object> evidence = new LinkedHashMap<>();
        var noBusinessData = isBusinessDataQuestion(lower) && noCommittedBusinessData();
        String response;
        if (lower.contains("profit") || lower.contains("drop") || lower.contains("decrease")) {
            if (lacks("insights.profit.view", conversation.id, "profit")) {
                response = ACCESS_DENIED_RESPONSE;
            } else if (noBusinessData) {
                response = noBusinessData(evidence);
            } else {
                var analysis = profitInsights.currentMonth();
                evidence.put("profitDrop", analysis);
                response = analysis.summary() + " Profit change: " + analysis.profitChange() + ", margin change: " + analysis.marginChangePercent() + " percentage points.";
            }
        } else if (lower.contains("reorder")) {
            if (lacks("reorder.view", conversation.id, "reorder")) {
                response = ACCESS_DENIED_RESPONSE;
            } else if (noBusinessData) {
                response = noBusinessData(evidence);
            } else {
                var suggestions = reorderEngine.suggestions();
                evidence.put("suggestions", suggestions);
                response = suggestions.isEmpty()
                    ? "I do not have enough sales and stock movement data to recommend reorders yet."
                    : "Reorder candidates are " + suggestions.stream().limit(5).map(s -> s.productName() + " (" + s.recommendedQuantity() + ")").toList() + ".";
            }
        } else if (lower.contains("outstanding") || lower.contains("receivable")) {
            response = lacks("customers.view_outstanding", conversation.id, "outstanding")
                ? ACCESS_DENIED_RESPONSE
                : noBusinessData ? noBusinessData(evidence) : outstanding(evidence);
        } else if (lower.contains("supplier") && lower.contains("margin")) {
            response = lacks("purchases.view_cost", conversation.id, "supplier_margin")
                ? ACCESS_DENIED_RESPONSE
                : noBusinessData ? noBusinessData(evidence) : supplierMargin(evidence);
        } else if (lower.contains("supplier") && lower.contains("cost")) {
            if (lacks("purchases.view_cost", conversation.id, "supplier_cost")) {
                response = ACCESS_DENIED_RESPONSE;
            } else if (noBusinessData) {
                response = noBusinessData(evidence);
            } else {
                var analysis = profitInsights.currentMonth();
                evidence.put("purchaseCostReasons", analysis.topReasons().stream()
                    .filter(reason -> reason.reason().toLowerCase().contains("purchase cost")).toList());
                response = analysis.topReasons().stream().filter(reason -> reason.reason().toLowerCase().contains("purchase cost")).findFirst()
                    .map(reason -> reason.evidence() + ".")
                    .orElse("I do not see enough comparable purchase data to identify which supplier or item increased cost the most.");
            }
        } else if (lower.contains("best margin")) {
            if (lacks("insights.profit.view", conversation.id, "best_margin")) {
                response = ACCESS_DENIED_RESPONSE;
            } else if (noBusinessData) {
                response = noBusinessData(evidence);
            } else {
                var margins = profitInsights.bestMarginProducts();
                evidence.put("bestMarginProducts", margins);
                response = "Best-margin products from available sales lines: " + margins.get("topProducts") + ".";
            }
        } else if (lower.contains("cleanup") || lower.contains("imported products need")) {
            if (lacks("data_quality.view", conversation.id, "data_quality")) {
                response = ACCESS_DENIED_RESPONSE;
            } else if (noBusinessData) {
                response = noBusinessData(evidence);
            } else {
                var missing = dataQuality.missingFields();
                var duplicates = dataQuality.duplicates();
                evidence.put("missingFields", missing);
                evidence.put("duplicates", duplicates);
                response = "Product cleanup needs attention on " + missing.size() + " products with missing fields and " + duplicates.size() + " possible duplicate groups.";
            }
        } else if (lower.contains("dead") || lower.contains("not moving") || lower.contains("slow")) {
            if (lacks("dead_stock.view", conversation.id, "dead_stock")) {
                response = ACCESS_DENIED_RESPONSE;
            } else if (noBusinessData) {
                response = noBusinessData(evidence);
            } else {
                var dead = dashboard.deadStock().stream().map(sensitive::deadStock).toList();
                evidence.put("deadStock", dead);
                response = dead.isEmpty()
                    ? "No dead-stock products were detected from current stock and the 90 day sales window."
                    : "The highest dead-stock risk is " + dead.getFirst().productName()
                        + (sensitive.canViewCost() ? " valued at " + dead.getFirst().stockValue() : " with " + dead.getFirst().stockQuantity() + " units on hand") + ".";
            }
        } else if (lower.contains("stock out") || lower.contains("stockout") || lower.contains("this week")) {
            if (lacks("stock.view", conversation.id, "stockout")) {
                response = ACCESS_DENIED_RESPONSE;
            } else if (noBusinessData) {
                response = noBusinessData(evidence);
            } else {
                var results = forecast.results();
                if (results.isEmpty()) {
                    forecast.run();
                    results = forecast.results();
                }
                evidence.put("forecastResults", results);
                var urgent = results.stream().filter(r -> r.stockoutDate() != null && !r.stockoutDate().isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(7))).toList();
                response = urgent.isEmpty()
                    ? "No product is currently projected to stock out this week based on the latest forecast run."
                    : "Products that may stock out this week: " + urgent.stream().map(ApiDtos.ForecastResponse::productName).toList() + ".";
            }
        } else if (lower.contains("warehouse") && lower.contains("mismatch")) {
            if (lacks("stock.view", conversation.id, "warehouse_mismatch")) {
                response = ACCESS_DENIED_RESPONSE;
            } else if (noBusinessData) {
                response = noBusinessData(evidence);
            } else {
                var low = dashboard.lowStock();
                evidence.put("lowStock", low);
                response = low.isEmpty()
                    ? "I do not see obvious warehouse stock mismatch risk from current low-stock data. Cycle counts and transfer history would improve this answer."
                    : "Warehouse mismatch risk should be reviewed for low-stock products: " + low.stream().limit(5).map(ApiDtos.LowStockResponse::productName).toList() + ".";
            }
        } else if (lower.contains("report") || lower.contains("export")) {
            var permission = lower.contains("export") ? "reports.export" : "reports.view";
            response = lacks(permission, conversation.id, "reports") ? ACCESS_DENIED_RESPONSE : "Reports are available from the Reports page for the datasets your role can access.";
        } else if (lower.contains("category") || lower.contains("categorize")) {
            if (lacks("data_quality.view", conversation.id, "categorization")) {
                response = ACCESS_DENIED_RESPONSE;
            } else {
                var suggestion = categorization.suggest(request.message());
                evidence.put("categorization", suggestion);
                response = "Suggested category is " + suggestion.get("category") + ", brand " + (suggestion.get("brand").isBlank() ? "unknown" : suggestion.get("brand")) + ", normalized name " + suggestion.get("normalizedName") + ".";
            }
        } else {
            if (lacks("dashboard.view", conversation.id, "dashboard_summary")) {
                response = ACCESS_DENIED_RESPONSE;
            } else if (noBusinessData) {
                response = noBusinessData(evidence);
            } else {
                var summary = sensitive.summary(dashboard.summary());
                evidence.put("summary", summary);
                response = "I can answer using your tenant data. Current summary: monthly sales " + summary.monthlySales()
                    + (sensitive.canViewProfit() ? ", gross profit " + summary.grossProfit() : "")
                    + ", low-stock products " + summary.lowStockCount() + ".";
            }
        }
        saveMessage(tenantId, conversation.id, DomainEnums.AiRole.ASSISTANT, response, evidence);
        return new ApiDtos.ChatResponse(conversation.id, response, evidence);
    }

    public Map<String, Object> insights() {
        return Map.of(
            "summary", sensitive.summary(dashboard.summary()),
            "lowStock", dashboard.lowStock(),
            "deadStock", dashboard.deadStock().stream().map(sensitive::deadStock).toList(),
            "reorderSuggestions", forecast.suggestions()
        );
    }

    @Transactional
    public Map<String, Object> generateInsights() {
        forecast.run();
        return insights();
    }

    private String profitDrop(Map<String, Object> evidence) {
        var today = LocalDate.now(ZoneOffset.UTC);
        var thisStart = today.withDayOfMonth(1);
        var prevStart = thisStart.minusMonths(1);
        var prevEnd = thisStart.minusDays(1);
        var thisRevenue = dashboard.summary().monthlySales();
        var prevRevenue = invoices.salesTotal(TenantContext.tenantId(), prevStart, prevEnd);
        var thisProfit = dashboard.grossProfit(thisStart, today);
        var prevProfit = dashboard.grossProfit(prevStart, prevEnd);
        var dead = dashboard.deadStock();
        evidence.put("currentMonthRevenue", thisRevenue);
        evidence.put("previousMonthRevenue", prevRevenue);
        evidence.put("currentMonthGrossProfit", thisProfit);
        evidence.put("previousMonthGrossProfit", prevProfit);
        evidence.put("deadStockValue", dead.stream().map(ApiDtos.DeadStockResponse::stockValue).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (thisRevenue.compareTo(prevRevenue) < 0 && thisProfit.compareTo(prevProfit) < 0) {
            return "Profit is down because revenue fell from " + prevRevenue + " last month to " + thisRevenue + " this month, and gross profit moved from " + prevProfit + " to " + thisProfit + ". Dead-stock value currently visible is " + evidence.get("deadStockValue") + ".";
        }
        if (thisProfit.compareTo(prevProfit) < 0) {
            return "Gross profit fell from " + prevProfit + " last month to " + thisProfit + " this month. Revenue did not fall as sharply, so check cost price increases, discounts, and product mix in recent invoices.";
        }
        return "I do not see a profit drop in the current month compared with the previous month. Current gross profit is " + thisProfit + " versus " + prevProfit + ".";
    }

    private String outstanding(Map<String, Object> evidence) {
        var tenantId = TenantContext.tenantId();
        var rows = customers.findByTenantIdOrderByNameAsc(tenantId).stream()
            .map(customer -> Map.entry(customer.name, outstanding.customerOutstanding(tenantId, customer.id)))
            .sorted(Map.Entry.<String, BigDecimal>comparingByValue(Comparator.reverseOrder())).toList();
        evidence.put("customerOutstanding", rows);
        if (rows.isEmpty()) {
            return "Customer data is missing, so I cannot calculate outstanding balances yet.";
        }
        var top = rows.getFirst();
        return "Highest outstanding is " + top.getKey() + " with " + top.getValue() + ".";
    }

    private String supplierMargin(Map<String, Object> evidence) {
        var tenantId = TenantContext.tenantId();
        var supplierNames = suppliers.findByTenantIdOrderByNameAsc(tenantId).stream().map(supplier -> supplier.name).toList();
        evidence.put("suppliers", supplierNames);
        evidence.put("availableMetric", "purchase suppliers are tracked, but sales invoice items are not linked to supplier batches");
        if (supplierNames.isEmpty()) {
            return "Supplier data is missing, so I cannot compare supplier-wise margins yet.";
        }
        return "I cannot honestly rank supplier margin yet because sales are not linked back to supplier batches or landed-cost lots. I can see these suppliers: " + supplierNames + ". Once purchases are batch-linked to sales, I can compare realized margin by supplier.";
    }

    private boolean isBusinessDataQuestion(String lower) {
        return lower.contains("profit")
            || lower.contains("drop")
            || lower.contains("decrease")
            || lower.contains("reorder")
            || lower.contains("outstanding")
            || lower.contains("receivable")
            || lower.contains("supplier")
            || lower.contains("best margin")
            || lower.contains("cleanup")
            || lower.contains("imported products need")
            || lower.contains("dead")
            || lower.contains("not moving")
            || lower.contains("slow")
            || lower.contains("stock out")
            || lower.contains("stockout")
            || lower.contains("this week")
            || lower.contains("warehouse")
            || lower.contains("dashboard")
            || lower.contains("summary");
    }

    private boolean noCommittedBusinessData() {
        var tenantId = TenantContext.tenantId();
        return dataQuality.summary().totalProducts() == 0
            && customers.findByTenantIdOrderByNameAsc(tenantId).isEmpty()
            && suppliers.findByTenantIdOrderByNameAsc(tenantId).isEmpty()
            && invoices.findByTenantId(tenantId).isEmpty();
    }

    private String noBusinessData(Map<String, Object> evidence) {
        evidence.put("businessDataAvailable", false);
        return NO_BUSINESS_DATA_RESPONSE;
    }

    private boolean lacks(String permission, UUID conversationId, String intent) {
        if (permissions.has(permission)) {
            return false;
        }
        audit.logCurrent("AI_ACCESS_DENIED", "AiConversation", conversationId, Map.of("intent", intent, "permission", permission));
        return true;
    }

    private AiConversation newConversation(UUID tenantId, String message) {
        var conversation = new AiConversation();
        conversation.tenantId = tenantId;
        conversation.userId = TenantContext.userId();
        conversation.title = message.length() > 80 ? message.substring(0, 80) : message;
        return conversations.save(conversation);
    }

    private void saveMessage(UUID tenantId, UUID conversationId, DomainEnums.AiRole role, String content, Map<String, Object> evidence) {
        var message = new AiMessage();
        message.tenantId = tenantId;
        message.conversationId = conversationId;
        message.role = role;
        message.content = content;
        message.evidenceJson = evidence;
        messages.save(message);
    }
}
