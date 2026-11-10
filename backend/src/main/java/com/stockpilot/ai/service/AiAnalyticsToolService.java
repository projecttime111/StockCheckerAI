package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;

/** Read-only, explicitly authorized business queries. Model arguments cannot select SQL, tenant, user or URL. */
@Service
public class AiAnalyticsToolService {
    private static final Map<String,String> PERMISSIONS=Map.ofEntries(
        Map.entry("summary","dashboard.view"),Map.entry("inventory","stock.view"),Map.entry("profit_comparison","insights.profit.view"),
        Map.entry("receivables","customers.view_outstanding"),Map.entry("dead_stock","dead_stock.view"),
        Map.entry("reorder","reorder.view"),Map.entry("forecast","forecast.view"),Map.entry("data_quality","data_quality.view"),Map.entry("supplier_margin","purchases.view_cost"));
    private final PermissionService permissions;
    private final SensitiveDataService sensitive;
    private final DashboardService dashboard;
    private final ProfitInsightService profit;
    private final OutstandingService outstanding;
    private final ReorderEngineService reorder;
    private final ForecastService forecast;
    private final DataQualityService quality;
    private final StockLedgerService stock;
    private final Repositories.ProductRepository products;
    private final Repositories.WarehouseRepository warehouses;
    private final Repositories.CustomerRepository customers;
    private final Repositories.SalesInvoiceRepository invoices;
    private final AnalyticsReadinessService readiness;
    public AiAnalyticsToolService(PermissionService permissions,SensitiveDataService sensitive,DashboardService dashboard,
        ProfitInsightService profit,OutstandingService outstanding,ReorderEngineService reorder,ForecastService forecast,
        DataQualityService quality,StockLedgerService stock,Repositories.ProductRepository products,
        Repositories.WarehouseRepository warehouses,Repositories.CustomerRepository customers,
        Repositories.SalesInvoiceRepository invoices,AnalyticsReadinessService readiness) {
        this.permissions=permissions;this.sensitive=sensitive;this.dashboard=dashboard;this.profit=profit;this.outstanding=outstanding;
        this.reorder=reorder;this.forecast=forecast;this.quality=quality;this.stock=stock;this.products=products;
        this.warehouses=warehouses;this.customers=customers;this.invoices=invoices;this.readiness=readiness;
    }
    public List<AiProvider.Tool> definitions() {
        return PERMISSIONS.keySet().stream().sorted().filter(name->permissions.has(PERMISSIONS.get(name))).map(name->{
            Map<String,Object> props=new LinkedHashMap<>();
            props.put("limit",Map.of("type","INTEGER","minimum",1,"maximum",25,"description","Maximum evidence rows; defaults to 10."));
            if(name.equals("inventory")) {
                props.put("query",Map.of("type","STRING","description","Optional product-name search, maximum 100 characters."));
                props.put("productId",Map.of("type","STRING","description","Existing product UUID from prior evidence, if known."));
                props.put("warehouseId",Map.of("type","STRING","description","Existing warehouse UUID from prior evidence, if known."));
            }
            if(name.equals("profit_comparison")) {
                props.put("from",Map.of("type","STRING","description","Inclusive start date YYYY-MM-DD. Default current month to date."));
                props.put("through",Map.of("type","STRING","description","Inclusive end date YYYY-MM-DD, no future dates; maximum 366 days."));
            }
            return new AiProvider.Tool(name,description(name),Map.of("type","OBJECT","properties",props));
        }).toList();
    }
    private String description(String name) {return switch(name){
        case "summary" -> "Current business dashboard metrics, with fields restricted to the user's permissions.";
        case "inventory" -> "Current ledger stock by product and warehouse, optionally filter UUIDs returned by previous evidence.";
        case "profit_comparison" -> "Revenue, gross profit and margin for a bounded period versus the preceding equal-length period; estimates have limitations.";
        case "receivables" -> "Customers with the highest current outstanding balances, including imported outstanding snapshots and subsequent payments.";
        case "dead_stock" -> "Products with stock and no recent demand; historic coverage limitations must be stated.";
        case "reorder" -> "Warehouse reorder recommendations based on demand, current stock, pending orders, lead time and minimum quantities.";
        case "forecast" -> "Latest stored demand and stockout estimates; no new forecast is published by chat.";
        case "data_quality" -> "Catalog cleanup findings and business data coverage warnings.";
        default -> "Explain whether realized supplier margins can currently be calculated.";};}

    @Transactional(readOnly=true,timeout=15)
    public Map<String,Object> execute(AiProvider.Call call) {
        String permission=PERMISSIONS.get(call.name());
        if(permission==null) throw ApiErrors.badRequest("Unsupported analytics tool");
        if(!permissions.has(permission)) throw ApiErrors.forbidden("You do not have permission to access this information.");
        var args=call.arguments(); var allowed=new HashSet<>(Set.of("limit"));
        if(call.name().equals("inventory")) allowed.addAll(Set.of("productId","warehouseId","query"));
        if(call.name().equals("profit_comparison")) allowed.addAll(Set.of("from","through"));
        if(!allowed.containsAll(args.keySet())) throw ApiErrors.badRequest("Unsupported analytics filter");
        int limit=10;
        if(args.containsKey("limit")) {
            try { var number=new BigDecimal(String.valueOf(args.get("limit"))); limit=number.intValueExact(); }
            catch(RuntimeException ex) {throw ApiErrors.badRequest("Limit must be an integer from 1 to 25");}
            if(limit<1||limit>25) throw ApiErrors.badRequest("Limit must be from 1 to 25");
        }
        var tenant=TenantContext.tenantId(); var today=LocalDate.now(ZoneOffset.UTC);
        var evidence=new LinkedHashMap<String,Object>(); evidence.put("id",call.name());evidence.put("tool",call.name());
        evidence.put("title",description(call.name()));evidence.put("asOf",Instant.now());
        evidence.put("period",Map.of("from",today.withDayOfMonth(1),"through",today,"timeZone","UTC"));
        var coverage=readiness.overview(); evidence.put("coverage",coverage);
        var limitations=new ArrayList<String>();
        if(coverage.get("warnings") instanceof List<?> rows) rows.forEach(w->limitations.add(String.valueOf(w)));
        if(!"READY".equals(coverage.get("status"))) limitations.add("Available records may cover only part of the business period; absence of records does not prove no activity.");
        Object data; String summary;
        if("NO_DATA".equals(coverage.get("status")) && !Set.of("receivables", "supplier_margin").contains(call.name())) {
            evidence.put("data",Map.of("businessDataAvailable",false)); evidence.put("limitations",limitations);
            evidence.put("summary","No business data is available yet. Import Tally/Excel data or add products, purchases, and sales first."); return evidence;
        }
        switch(call.name()) {
            case "summary" -> {
                var value=sensitive.summary(dashboard.summary()); data=value;
                summary="Current recorded monthly sales are "+value.monthlySales()+", with "+value.lowStockCount()+" low-stock products."
                    +(sensitive.canViewProfit()?" Estimated gross profit from recorded costs is "+value.grossProfit()+".":"");
            }
            case "inventory" -> {
                UUID product=id(args.get("productId")),warehouse=id(args.get("warehouseId"));
                if(product!=null) products.findByTenantIdAndId(tenant,product).orElseThrow(()->ApiErrors.notFound("Product not found"));
                if(warehouse!=null) warehouses.findByTenantIdAndId(tenant,warehouse).orElseThrow(()->ApiErrors.notFound("Warehouse not found"));
                var query=args.get("query")==null?"":String.valueOf(args.get("query")).trim();
                if(query.length()>100)throw ApiErrors.badRequest("Product search must not exceed 100 characters");
                var page=org.springframework.data.domain.PageRequest.of(0,limit,org.springframework.data.domain.Sort.by("name","id"));
                var selected=product!=null?List.of(products.findByTenantIdAndId(tenant,product).orElseThrow())
                    :query.isBlank()?products.findByTenantIdAndActiveTrue(tenant,page).getContent()
                    :products.findByTenantIdAndActiveTrueAndNameContainingIgnoreCase(tenant,query,page).getContent();
                var locations=warehouse!=null?List.of(warehouses.findByTenantIdAndId(tenant,warehouse).orElseThrow())
                    :warehouses.findByTenantIdAndActiveTrueOrderByNameAsc(tenant);
                var rows=new ArrayList<ApiDtos.StockResponse>();
                outer:for(var item:selected)for(var location:locations){
                    if(rows.size()>=limit)break outer;
                    rows.addAll(stock.currentStockReport(item.id,location.id));
                }
                data=rows;evidence.put("filters",Map.of("query",query,"limit",limit));
                evidence.put("period",Map.of("through",today,"timeZone","UTC"));
                limitations.add("Showing at most "+limit+" matching product/warehouse rows in name order; this is not a complete inventory export. Quantities are in each product's base unit.");
                summary=rows.isEmpty()?"No stock records match these filters.":"Current ledger balances are shown for "+rows.size()+" matching product/warehouse rows.";
            }
            case "profit_comparison" -> {
                var through=date(args.get("through"),today);var from=date(args.get("from"),through.withDayOfMonth(1));
                long days=java.time.temporal.ChronoUnit.DAYS.between(from,through)+1;
                if(days<1||days>366||through.isAfter(today)) throw ApiErrors.badRequest("Choose a past or current date range of 1 to 366 days");
                var previousEnd=from.minusDays(1);var value=profit.analyze(from,through,previousEnd.minusDays(days-1),previousEnd);
                data=value;evidence.put("period",Map.of("from",from,"through",through,"previousFrom",previousEnd.minusDays(days-1),"previousThrough",previousEnd,"timeZone","UTC"));
                summary="Estimated recorded gross profit changed by "+value.profitChange()+" and margin by "+value.marginChangePercent()+" percentage points compared with the preceding "+days+" days.";
                limitations.add("Gross profit uses available recorded costs; this comparison does not establish causation or net profit.");
            }
            case "receivables" -> {
                var rows=customers.findByTenantIdOrderByNameAsc(tenant).stream().map(c->{var row=new LinkedHashMap<String,Object>(); row.put("customerId",c.id);row.put("name",c.name);row.put("outstanding",outstanding.customerOutstanding(tenant,c.id));return row;})
                    .sorted(Comparator.comparing(row->(BigDecimal)row.get("outstanding"),Comparator.reverseOrder())).limit(limit).toList();
                data=rows;summary=rows.isEmpty()?"No customer balances are available.":"Highest outstanding is "+rows.getFirst().get("name")+" with "+rows.getFirst().get("outstanding")+".";
                evidence.put("period",Map.of("through",today,"timeZone","UTC"));
            }
            case "dead_stock" -> {
                var rows=dashboard.deadStock().stream().map(sensitive::deadStock).limit(limit).toList();data=rows;
                summary=rows.isEmpty()?"No dead stock was identified in the available records.":"Potential dead stock includes "+rows.stream().map(ApiDtos.DeadStockResponse::productName).toList()+".";
                evidence.put("period",Map.of("from",today.minusDays(89),"through",today,"timeZone","UTC"));
                limitations.add("No recorded sale may indicate missing history; verify sales coverage before taking action.");
            }
            case "reorder" -> {var rows=reorder.suggestions().stream().limit(limit).toList();data=rows;
                summary=rows.isEmpty()?"No reorder suggestions are currently available.":"Reorder candidates: "+rows.stream().map(r->r.productName()+" ("+r.recommendedQuantity()+")").toList()+".";
                var period=new LinkedHashMap<String,Object>();period.put("inventoryAsOf",today);period.put("salesFrom",coverage.get("salesFrom"));period.put("salesThrough",coverage.get("salesThrough"));period.put("timeZone","UTC");
                evidence.put("period",period);limitations.add("Recommendations are planning estimates; unallocated sales demand is assigned once across warehouses.");}
            case "forecast" -> {var rows=forecast.results().stream().limit(limit).toList();data=rows;
                summary=rows.isEmpty()?"No saved forecast exists. Run a forecast from the Forecast page first.":"Latest stored demand estimates are shown in the evidence. Check their generation time and available history.";
                var generated=coverage.get("latestForecastAt") instanceof Instant value?value:null;
                evidence.put("asOf",generated);
                var period=new LinkedHashMap<String,Object>();period.put("generatedAt",generated);period.put("timeZone","UTC");
                period.put("historyDays",rows.isEmpty()?0:rows.getFirst().coverageDays());
                evidence.put("period",period);
                limitations.add("Forecasts are estimates from the last saved run; this chat does not regenerate them.");}
            case "data_quality" -> {data=quality.summary();summary="Catalog quality and data coverage are shown in the evidence. A high catalog score does not prove complete sales history.";}
            default -> {data=Map.of("available",false);summary="Realized supplier margin is unavailable because sales are not linked to supplier batches or landed-cost lots.";limitations.add(summary);}
        }
        evidence.put("data",data);evidence.put("summary",summary);evidence.put("limitations",limitations.stream().distinct().toList());return evidence;
    }
    private UUID id(Object value){if(value==null)return null;try{return UUID.fromString(String.valueOf(value));}catch(RuntimeException ex){throw ApiErrors.badRequest("Choose an existing product or warehouse ID");}}
    private LocalDate date(Object value,LocalDate fallback){if(value==null)return fallback;try{return LocalDate.parse(String.valueOf(value));}catch(RuntimeException ex){throw ApiErrors.badRequest("Dates must use YYYY-MM-DD");}}
}
