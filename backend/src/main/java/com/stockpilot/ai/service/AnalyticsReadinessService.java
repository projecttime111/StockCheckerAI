package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.AnalyticsCoverage;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.AnalyticsCoverageRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AnalyticsReadinessService {
    private final EntityManager em;
    private final AnalyticsCoverageRepository coverage;
    private final TenantWriteLockService locks;
    private final PermissionService permissions;
    private final AuditService audit;
    public AnalyticsReadinessService(EntityManager em,AnalyticsCoverageRepository coverage,TenantWriteLockService locks,PermissionService permissions,AuditService audit){
        this.em=em;this.coverage=coverage;this.locks=locks;this.permissions=permissions;this.audit=audit;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> overview(){
        var tenant=TenantContext.tenantId();var today=LocalDate.now(ZoneOffset.UTC);var period=coverage.findByTenantId(tenant).orElse(null);
        var sales=em.createQuery("select min(i.invoiceDate), max(i.invoiceDate), count(i), max(i.updatedAt) from SalesInvoice i where i.tenantId=:tenant and i.totalAmount<>0",Object[].class).setParameter("tenant",tenant).getSingleResult();
        var products=count("select count(p) from Product p where p.tenantId=:tenant",tenant);
        var latestImport=em.createQuery("select max(s.committedAt) from ImportSession s where s.tenantId=:tenant",Instant.class).setParameter("tenant",tenant).getSingleResult();
        var latestClassicImport=em.createQuery("select max(b.committedAt) from ImportBatch b where b.tenantId=:tenant and b.rolledBackAt is null",Instant.class).setParameter("tenant",tenant).getSingleResult();
        if(latestImport==null || newer(latestClassicImport,latestImport))latestImport=latestClassicImport;
        var latestForecast=em.createQuery("select max(f.generatedAt) from ForecastResult f where f.tenantId=:tenant",Instant.class).setParameter("tenant",tenant).getSingleResult();
        var lastMovement=em.createQuery("select max(s.updatedAt) from StockMovement s where s.tenantId=:tenant",Instant.class).setParameter("tenant",tenant).getSingleResult();
        var lastProductChange=em.createQuery("select max(p.updatedAt) from Product p where p.tenantId=:tenant",Instant.class).setParameter("tenant",tenant).getSingleResult();
        LocalDate observedFrom=(LocalDate)sales[0],observedThrough=(LocalDate)sales[1];
        long observed=observedFrom==null?0:ChronoUnit.DAYS.between(observedFrom,observedThrough)+1;
        boolean confirmed=period!=null&&period.complete;
        var from=confirmed?period.fromDate:observedFrom;var through=confirmed?period.throughDate:observedThrough;
        long covered=confirmed?ChronoUnit.DAYS.between(period.fromDate,period.throughDate)+1:0;
        boolean stale=latestForecast==null||latestForecast.atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
            ||newer(latestImport,latestForecast)||newer((Instant)sales[3],latestForecast)||newer(lastMovement,latestForecast)
            ||newer(lastProductChange,latestForecast)||period!=null&&newer(period.updatedAt,latestForecast);
        long uncosted=count("select count(l) from SalesInvoiceItem l where l.tenantId=:tenant and l.quantity<>0 and (l.costRate is null or l.costRate<=0)",tenant);
        long estimated=count("select count(l) from SalesInvoiceItem l where l.tenantId=:tenant and l.quantity<>0 and (l.costProvenance is null or l.costProvenance<>'ACTUAL')",tenant);
        var warnings=new ArrayList<String>();
        boolean noData=products==0&&((Number)sales[2]).longValue()==0
            &&count("select count(c) from Customer c where c.tenantId=:tenant",tenant)==0
            &&count("select count(s) from Supplier s where s.tenantId=:tenant",tenant)==0;
        if(noData)warnings.add("No committed business data is available.");
        if(!confirmed)warnings.add("Sales-history coverage has not been confirmed. Missing dates are not proof of zero sales; demand estimates are provisional.");
        if(confirmed&&covered<30)warnings.add("Fewer than 30 complete days of sales history are available; forecasts have limited history.");
        if(through!=null&&through.isBefore(today.minusDays(1)))warnings.add("Sales history ends on "+through+". Current-period analytics may be incomplete.");
        if(stale)warnings.add("Forecasts have not been refreshed for the latest business data.");
        boolean canCost=permissions.has("insights.profit.view")||permissions.has("sales.view_margin")||permissions.has("purchases.view_cost");
        if(canCost&&uncosted>0)warnings.add(uncosted+" sales lines have missing/zero costs; margin is incomplete.");
        if(canCost&&estimated>0)warnings.add(estimated+" sales lines use estimated or unverified cost. Profit is an estimate, not reconciled accounting profit.");
        var result=new LinkedHashMap<String,Object>();
        result.put("status",noData?"NO_DATA":warnings.isEmpty()?"READY":"PARTIAL");
        result.put("asOf",Instant.now());result.put("timezone","UTC");result.put("salesFrom",from);result.put("salesThrough",through);
        result.put("observedDays",observed);result.put("coveredDays",covered);result.put("coverageConfirmed",confirmed);
        result.put("latestImportAt",latestImport);result.put("latestForecastAt",latestForecast);result.put("forecastStale",stale);
        result.put("uncostedSalesLines",canCost?uncosted:null);result.put("estimatedCostLines",canCost?estimated:null);result.put("warnings",warnings);
        return result;
    }
    @Transactional
    public Map<String,Object> confirm(LocalDate from,LocalDate through,boolean complete){
        if(!permissions.has("imports.validate"))throw ApiErrors.forbidden("You cannot confirm import coverage");
        if(from==null||through==null||through.isBefore(from)||through.isAfter(LocalDate.now(ZoneOffset.UTC))||ChronoUnit.DAYS.between(from,through)>36600)
            throw ApiErrors.badRequest("Choose a valid past sales-history date range");
        var tenant=TenantContext.tenantId();locks.lock(tenant);
        var row=coverage.findByTenantId(tenant).orElseGet(AnalyticsCoverage::new);
        row.tenantId=tenant;row.fromDate=from;row.throughDate=through;row.complete=complete;row.confirmedBy=TenantContext.userId();
        coverage.saveAndFlush(row);audit.logCurrent("ANALYTICS_COVERAGE_CONFIRMED","AnalyticsCoverage",row.id,Map.of("from",from,"through",through,"complete",complete));
        return overview();
    }
    private long count(String query,UUID tenant){return em.createQuery(query,Long.class).setParameter("tenant",tenant).getSingleResult();}
    private boolean newer(Instant value,Instant baseline){return value!=null&&value.isAfter(baseline);}
}
