package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.exception.ApiErrors;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Compares the parsed source scope to matching committed records, never unrelated dashboard totals. */
@Service
public class ImportReconciliationService {
    private final EntityManager em;
    private final StockLedgerService stock;
    private final SourceDocumentRevisionService revisions;
    public ImportReconciliationService(EntityManager em,StockLedgerService stock,SourceDocumentRevisionService revisions){this.em=em;this.stock=stock;this.revisions=revisions;}
    @Transactional(readOnly=true)
    public Map<String,Object> report(UUID sessionId,int page,int size){
        var tenant=TenantContext.tenantId();
        var sessions=em.createQuery("select s from ImportSession s where s.tenantId=:tenant and s.id=:session",ImportSession.class).setParameter("tenant",tenant).setParameter("session",sessionId).getResultList();
        if(sessions.isEmpty())throw ApiErrors.notFound("Import session not found");
        var session=sessions.getFirst();boolean committed=session.committedAt!=null;
        String currency=em.find(Tenant.class,tenant).currency;
        var vouchers=em.createQuery("select v from SmartStagedVoucher v where v.tenantId=:tenant and v.importSessionId=:session",SmartStagedVoucher.class).setParameter("tenant",tenant).setParameter("session",sessionId).getResultList();
        var warnings=new ArrayList<String>();
        warnings.add("This compares parsed source records with their matching stored records. It does not certify rows the parser could not recognize. Dashboard totals may include other files or periods.");
        long excluded=vouchers.stream().filter(v->v.reviewStatus==DomainEnums.SmartReviewStatus.IGNORED).count();
        long unresolved=em.createQuery("select count(i) from ImportPlanIssue i where i.tenantId=:tenant and i.importSessionId=:session and i.resolved=false and i.severity in :severities",Long.class)
            .setParameter("tenant",tenant).setParameter("session",sessionId).setParameter("severities",List.of(DomainEnums.ImportPlanIssueSeverity.ERROR,DomainEnums.ImportPlanIssueSeverity.REVIEW_REQUIRED)).getSingleResult();
        long failedFiles=em.createQuery("select count(f) from ImportSessionFile f where f.tenantId=:tenant and f.importSessionId=:session and (f.status=:failed or f.errorCount>0)",Long.class)
            .setParameter("tenant",tenant).setParameter("session",sessionId).setParameter("failed",DomainEnums.ImportSessionFileStatus.FAILED).getSingleResult();
        if(excluded>0)warnings.add(excluded+" ignored vouchers are excluded from these totals.");
        if(unresolved>0||failedFiles>0)warnings.add("Unresolved review items or failed files prevent a complete source reconciliation.");
        var from=vouchers.stream().map(v->v.voucherDate).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        var through=vouchers.stream().map(v->v.voucherDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        var checks=new ArrayList<Map<String,Object>>();
        for(boolean isSale:List.of(true,false)){
            BigDecimal sourceTotal=BigDecimal.ZERO,storedTotal=BigDecimal.ZERO,sourceTax=BigDecimal.ZERO,storedTax=BigDecimal.ZERO;
            long sourceCount=0,storedCount=0,matchedContents=0;var seen=new HashSet<UUID>();var seenSource=new HashSet<String>();
            for(var voucher:vouchers){
                if(voucher.reviewStatus==DomainEnums.SmartReviewStatus.IGNORED||!revisions.supported(voucher)||voucher.voucherType.toUpperCase(Locale.ROOT).contains("SALE")!=isSale)continue;
                var sourceKey=Objects.toString(voucher.rawMetadataJson.get("Source Company"),"")+"|"+key(voucher.voucherNumber,voucher.voucherDate)+"|"+voucher.matchedPartyId+"|"+revisions.sourceHash(voucher);
                if(!seenSource.add(sourceKey))continue;
                sourceCount++;boolean cancelled=revisions.cancelled(voucher);
                sourceTotal=sourceTotal.add(cancelled?BigDecimal.ZERO:n(voucher.totalAmount).abs());
                sourceTax=sourceTax.add(cancelled?BigDecimal.ZERO:n(voucher.taxAmount).abs());
                var found=revisions.existing(voucher);
                var sourceCompany=Objects.toString(voucher.rawMetadataJson.get("Source Company"),"");
                if(found instanceof SalesInvoice invoice && isSale && sourceCompany.equals(Objects.toString(invoice.financialMetadata.get("sourceCompany"),"")) && seen.add(invoice.id)) {
                    storedCount++;storedTotal=storedTotal.add(n(invoice.totalAmount));storedTax=storedTax.add(n(invoice.taxAmount));
                    if("UNCHANGED".equals(revisions.assess(voucher).outcome())) matchedContents++;
                } else if(found instanceof PurchaseInvoice invoice && !isSale && sourceCompany.equals(Objects.toString(invoice.financialMetadata.get("sourceCompany"),"")) && seen.add(invoice.id)) {
                    storedCount++;storedTotal=storedTotal.add(n(invoice.totalAmount));storedTax=storedTax.add(n(invoice.taxAmount));
                    if("UNCHANGED".equals(revisions.assess(voucher).outcome())) matchedContents++;
                }
            }
            if(sourceCount>0){var label=isSale?"Sales":"Purchases";var metric=isSale?"SALES":"PURCHASES";
                checks.add(check(metric+"_COUNT",label+" document count",BigDecimal.valueOf(sourceCount),BigDecimal.valueOf(storedCount),committed,"documents","Matching invoice identities in the source financial years; cancellations retain their document identity."));
                checks.add(check(metric+"_TOTAL",label+" total including tax",sourceTotal,storedTotal,committed,currency,"Gross invoice totals, including header discounts and charges; cancelled documents contribute zero."));
                checks.add(check(metric+"_TAX",label+" tax",sourceTax,storedTax,committed,currency,"Invoice-level tax. Product margins may use estimated purchase costs."));
                checks.add(check(metric+"_CONTENT",label+" matching complete documents",BigDecimal.valueOf(sourceCount),BigDecimal.valueOf(matchedContents),committed,"documents","Compares complete source line content and current destination evidence; same-total line changes remain visible."));
            }
        }
        var snapshots=em.createQuery("select s from SmartStagedStockSnapshot s where s.tenantId=:tenant and s.importSessionId=:session order by s.sourceRowNumber",SmartStagedStockSnapshot.class).setParameter("tenant",tenant).setParameter("session",sessionId).getResultList();
        var baselines=em.createQuery("select b from StockSnapshotBaseline b where b.tenantId=:tenant and b.sourceImportSessionId=:session",StockSnapshotBaseline.class).setParameter("tenant",tenant).setParameter("session",sessionId).getResultList();
        var baselineMap=new HashMap<String,StockSnapshotBaseline>();baselines.forEach(b->baselineMap.put(b.sourceFileId+"|"+b.sourceRowNumber,b));
        for(var snapshot:snapshots){
            if(snapshot.reviewStatus==DomainEnums.SmartReviewStatus.IGNORED){excluded++;continue;}
            var baseline=baselineMap.get(snapshot.sessionFileId+"|"+snapshot.sourceRowNumber);
            BigDecimal expected=normalizedSnapshotQuantity(tenant,snapshot,baseline);
            BigDecimal actual=baseline==null?null:stock.stockAt(tenant,baseline.productId,baseline.warehouseId,baseline.snapshotDate);
            checks.add(check("STOCK_"+snapshot.id,snapshot.productName+" — "+Objects.toString(snapshot.warehouseName,"all warehouses"),expected,actual,committed,"base units",
                baseline==null?"No matching committed baseline: resolve this snapshot or select its latest source import.":"Closing stock on "+baseline.snapshotDate+"; later movements intentionally excluded. Source quantity normalized to the product base unit."));
        }
        if(snapshots.isEmpty()&&vouchers.isEmpty())warnings.add("This session has no parsed stock snapshots or invoices to compare. Master-data-only files do not establish sales or margin totals.");
        long otherTypes=vouchers.stream().filter(v->!revisions.supported(v)).count();
        if(otherTypes>0)warnings.add(otherTypes+" other vouchers (such as payments, returns or journals) require their review/effect reports; they are not included in gross invoice totals.");
        var status=!committed?"NOT_COMMITTED":checks.stream().anyMatch(c->"DIFFERENCE".equals(c.get("status")))?"DIFFERENCES":
            checks.isEmpty()||excluded>0||unresolved>0||failedFiles>0||otherTypes>0||checks.stream().anyMatch(c->"NOT_AVAILABLE".equals(c.get("status")))?"PARTIAL":"MATCHED";
        int boundedSize=Math.max(1,Math.min(200,size)),boundedPage=Math.max(0,page),start=(int)Math.min(checks.size(),(long)boundedPage*boundedSize),end=Math.min(checks.size(),start+boundedSize);
        var result=new LinkedHashMap<String,Object>();result.put("sessionId",sessionId);result.put("status",status);result.put("generatedAt",Instant.now());result.put("currency",currency);
        result.put("coverageFrom",from);result.put("coverageThrough",through);result.put("checks",checks.subList(start,end));result.put("excludedRows",excluded);result.put("unresolvedRows",unresolved);
        result.put("warnings",warnings);result.put("page",boundedPage);result.put("size",boundedSize);result.put("totalChecks",checks.size());result.put("totalPages",(checks.size()+boundedSize-1)/boundedSize);return result;
    }
    private BigDecimal normalizedSnapshotQuantity(UUID tenant,SmartStagedStockSnapshot snapshot,StockSnapshotBaseline baseline) {
        var productId=snapshot.matchedProductId==null?(baseline==null?null:baseline.productId):snapshot.matchedProductId;
        if(productId==null||snapshot.importedStock==null)return null;
        var product=em.createQuery("select p from Product p where p.tenantId=:tenant and p.id=:id",Product.class).setParameter("tenant",tenant).setParameter("id",productId).getResultStream().findFirst().orElse(null);
        var unit=em.createQuery("select u from UnitOfMeasure u where u.tenantId=:tenant and upper(u.code)=:code",UnitOfMeasure.class).setParameter("tenant",tenant).setParameter("code",Objects.toString(snapshot.unitCode,"").trim().toUpperCase(Locale.ROOT)).getResultStream().findFirst().orElse(null);
        if(product==null||unit==null)return null;
        try{return stock.toBaseQuantity(tenant,product,unit.id,snapshot.importedStock);}catch(com.stockpilot.ai.exception.ApiException ex){return null;}
    }
    private Map<String,Object> check(String metric,String label,BigDecimal source,BigDecimal stored,boolean committed,String unit,String note){
        var row=new LinkedHashMap<String,Object>();var diff=source==null||stored==null?null:stored.subtract(source);
        row.put("metric",metric);row.put("label",label);row.put("sourceValue",source);row.put("committedValue",committed?stored:null);row.put("difference",committed?diff:null);
        row.put("status",!committed?"NOT_COMMITTED":diff==null?"NOT_AVAILABLE":diff.abs().compareTo(unit.equals("documents")?BigDecimal.ZERO:new BigDecimal(unit.equals("base units")?"0.0005":"0.005"))<=0?"MATCH":"DIFFERENCE");
        row.put("unit",unit);row.put("note",note);return row;
    }
    private String key(String number,LocalDate date){return Objects.toString(number,"").trim().toLowerCase(Locale.ROOT)+"|"+(date==null?"":InvoiceIdentityService.fiscalYearStart(date));}
    private BigDecimal n(BigDecimal value){return value==null?BigDecimal.ZERO:value;}
}
