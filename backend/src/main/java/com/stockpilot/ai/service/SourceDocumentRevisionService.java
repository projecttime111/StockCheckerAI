package com.stockpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.exception.ApiErrors;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** All document corrections are explicit, reviewed, tenant-locked and append audited. */
@Service
public class SourceDocumentRevisionService {
    private final EntityManager em;
    private final InvoiceIdentityService identity;
    private final TenantWriteLockService locks;
    private final StockLedgerService stock;
    private final AuditService audit;
    private final ObjectMapper json;
    private final PermissionService permissions;
    public SourceDocumentRevisionService(EntityManager em, InvoiceIdentityService identity,
        TenantWriteLockService locks, StockLedgerService stock, AuditService audit, ObjectMapper json, PermissionService permissions) {
        this.em=em; this.identity=identity; this.locks=locks; this.stock=stock; this.audit=audit; this.json=json; this.permissions=permissions;
    }
    public boolean supported(SmartStagedVoucher v) {
        var type=norm(v.voucherType);
        return !type.contains("RETURN")&&!type.contains("CREDIT")&&!type.contains("DEBIT")
            &&(type.contains("SALE")||type.contains("PURCHASE"));
    }
    private boolean sale(SmartStagedVoucher v) { return norm(v.voucherType).contains("SALE"); }
    public boolean cancelled(SmartStagedVoucher v) {
        var status=norm(Objects.toString(v.rawMetadataJson.get("Cancelled"),""));
        return Set.of("YES","TRUE","1","CANCEL","CANCELLED","DELETE","DELETED").contains(status);
    }
    public List<SmartStagedVoucherItem> items(SmartStagedVoucher v) {
        return em.createQuery("select i from SmartStagedVoucherItem i where i.tenantId=:tenant and i.stagedVoucherId=:id order by i.sourceRowNumber",SmartStagedVoucherItem.class)
            .setParameter("tenant",v.tenantId).setParameter("id",v.id).getResultList();
    }
    public String sourceHash(SmartStagedVoucher v) {
        var lines=items(v).stream().map(i->CanonicalImportNormalizer.encoded(List.of(norm(i.productName),norm(i.unitCode),n(i.quantity),n(i.rate),n(lineAmount(i)),norm(i.warehouseName)))).sorted().toList();
        return CanonicalImportNormalizer.hashValues(List.of(sale(v)?"SALES_INVOICE":"PURCHASE_INVOICE",norm(v.voucherNumber),Objects.toString(v.voucherDate,""),norm(v.partyName),n(v.totalAmount),n(v.taxAmount),n(v.discountAmount),n(v.freightAmount),n(v.roundOffAmount),n(v.otherChargesAmount),cancelled(v),lines));
    }
    private String proposalHash(SmartStagedVoucher v) {
        var mappings=items(v).stream().map(i->{
            var product=product(v.tenantId,i.matchedProductId);var unit=unit(v.tenantId,i.unitCode);
            String converted="UNRESOLVED";
            if(product!=null&&unit!=null) try{converted=n(stock.toBaseQuantity(v.tenantId,product,unit.id,z(i.quantity).abs()));}catch(com.stockpilot.ai.exception.ApiException ignored) { }
            return CanonicalImportNormalizer.encoded(List.of(Objects.toString(i.id,""),Objects.toString(i.matchedProductId,""),Objects.toString(i.matchedWarehouseId,""),unit==null?"":unit.id.toString(),converted));
        }).sorted().toList();
        return CanonicalImportNormalizer.hashValues(List.of(sourceHash(v),Objects.toString(v.matchedPartyId,""),sourceIdentityHash(v),Objects.toString(v.rawMetadataJson.get("Source Revision"),""),mappings));
    }
    public Object existing(SmartStagedVoucher v) {
        if(!supported(v)||v.voucherNumber==null||v.voucherDate==null) return null;
        if(v.externalId!=null && !v.externalId.isBlank()) {
            var previous=em.createQuery("select r from SourceDocumentRevision r where r.tenantId=:tenant and r.sourceIdentityHash=:identity order by r.createdAt desc",SourceDocumentRevision.class)
                .setParameter("tenant",v.tenantId).setParameter("identity",sourceIdentityHash(v)).setMaxResults(1).getResultList();
            if(!previous.isEmpty()) return em.createQuery("select i from "+(sale(v)?"SalesInvoice":"PurchaseInvoice")+" i where i.tenantId=:tenant and i.id=:id")
                .setParameter("tenant",v.tenantId).setParameter("id",previous.getFirst().invoiceId).getResultStream().findFirst().orElse(null);
        }
        // Number/FY remains a destination invariant; a company conflict is explicitly blocked below.
        return sale(v)?identity.matchSale(v.tenantId,v.voucherNumber,v.voucherDate).orElse(null)
            :identity.matchPurchase(v.tenantId,v.matchedPartyId,v.voucherNumber,v.voucherDate).orElse(null);
    }
    public record Assessment(String outcome, boolean eligible, String blockingReason, UUID invoiceId,
        String sourceHash, String destinationHash, boolean approved, int stockMovementCount,
        BigDecimal existingTotal, BigDecimal proposedTotal) { }
    public Assessment assess(SmartStagedVoucher v) {
        var invoice=existing(v); var contentHash=sourceHash(v); var sourceHash=proposalHash(v);
        if(invoice==null) return new Assessment(cancelled(v)?"CANCELLED":"NEW",!cancelled(v),cancelled(v)?"No existing imported invoice matches this cancellation":"",null,sourceHash,"",false,0,BigDecimal.ZERO,z(v.totalAmount));
        var id=(UUID)get(invoice,"id"); var metadata=metadata(invoice); var before=destination(invoice);
        var hash=destinationHash(invoice); var movements=movements(v.tenantId,invoice);
        var same=contentHash.equals(metadata.get("sourceCanonicalHash"))
            &&hash.equals(metadata.get("sourceDestinationHash"));
        if(metadata.get("sourceCanonicalHash")==null) same=legacySame(v,invoice);
        if(cancelled(v)&&"CANCELLED".equals(metadata.get("sourceDocumentState"))) same=true;
        if(!Objects.toString(metadata.get("sourceCompany"),"").equals(Objects.toString(v.rawMetadataJson.get("Source Company"),""))) same=false;
        String outcome=same?"UNCHANGED":cancelled(v)?"CANCELLED":"CHANGED";
        var blocked="UNCHANGED".equals(outcome)?"":blocked(v,invoice,movements);
        boolean approved=blocked.isBlank() && sourceHash.equals(v.rawMetadataJson.get("revisionApprovedSourceHash"))
            &&hash.equals(v.rawMetadataJson.get("revisionApprovedDestinationHash"));
        return new Assessment(outcome,blocked.isBlank(),blocked,id,sourceHash,hash,approved,movements.size(),(BigDecimal)get(invoice,"totalAmount"),cancelled(v)?BigDecimal.ZERO:z(v.totalAmount));
    }
    private String blocked(SmartStagedVoucher v,Object invoice,List<StockMovement> movements) {
        var meta=metadata(invoice); var id=(UUID)get(invoice,"id");
        var imported=meta.containsKey("sourceCanonicalHash") || count("ExternalRecordMapping","localEntityId",id,v.tenantId)>0;
        if(!imported) return "This invoice was not created by Smart Import; revise it through its original workflow";
        if("CANCELLED".equals(meta.get("sourceDocumentState")) && !cancelled(v)) return "A cancelled document cannot be reactivated automatically; reconcile and reissue it under a new source document identity";
        if(!Objects.equals(get(invoice,"invoiceDate"),v.voucherDate)||!Objects.equals(norm((String)get(invoice,"invoiceNumber")),norm(v.voucherNumber))||!Objects.equals(get(invoice,sale(v)?"customerId":"supplierId"),v.matchedPartyId)) return "Changing an invoice date or party requires separate financial reconciliation";
        var company=Objects.toString(v.rawMetadataJson.get("Source Company"),"");
        if(!Objects.toString(meta.get("sourceCompany"),"").equals(company)) return "Source company differs from the original invoice; do not combine businesses";
        if(count(sale(v)?"CustomerPayment":"SupplierPayment",sale(v)?"salesInvoiceId":"purchaseInvoiceId",id,v.tenantId)>0) return "Linked payments must be reconciled before changing or cancelling this invoice";
        if(count("FinancialAdjustment",sale(v)?"customerId":"supplierId",v.matchedPartyId,v.tenantId)>0) return "This party has credit/debit notes; reconcile those dependencies before revision";
        var covering=em.createQuery("select count(s) from OutstandingSnapshot s where s.tenantId=:tenant and s."+(sale(v)?"customerId":"supplierId")+"=:party and s.snapshotDate>=:date",Long.class)
            .setParameter("tenant",v.tenantId).setParameter("party",v.matchedPartyId).setParameter("date",v.voucherDate).getSingleResult();
        if(covering>0) return "An outstanding snapshot covers this invoice; revise the snapshot and reconcile the account first";
        if(count("SmartStagedVoucher","importSessionId",v.importSessionId,v.tenantId)!=1) return "Use a dedicated session containing exactly one voucher for a correction or cancellation";
        if(count("SmartStagedStockSnapshot","importSessionId",v.importSessionId,v.tenantId)>0 || count("SmartStagedCashbookEntry","importSessionId",v.importSessionId,v.tenantId)>0) return "Revision sessions cannot also contain snapshots or cashbook entries";
        for(var m:movements) {
            var newer=em.createQuery("select count(s) from StockSnapshotBaseline s where s.tenantId=:tenant and s.productId=:product and (s.warehouseId is null or s.warehouseId=:warehouse) and s.snapshotDate>=:date",Long.class)
                .setParameter("tenant",v.tenantId).setParameter("product",m.productId).setParameter("warehouse",m.warehouseId).setParameter("date",v.voucherDate).getSingleResult();
            if(newer>0) return "A later closing-stock snapshot covers the original stock effect; reconcile the snapshot before revision";
        }
        if(!cancelled(v)) for(var item:items(v)) {
            if(item.matchedProductId==null || product(v.tenantId,item.matchedProductId)==null || unit(v.tenantId,item.unitCode)==null) return "All replacement products and units must already be matched";
            if(!movements.isEmpty() && item.matchedWarehouseId==null) return "All replacement warehouses must already be matched";
            if(item.matchedWarehouseId!=null && !Objects.equals(item.matchedWarehouseId,get(invoice,"warehouseId"))) return "Changing an invoice warehouse requires separate warehouse reconciliation";
            if(!movements.isEmpty()) {
                var covered=em.createQuery("select count(s) from StockSnapshotBaseline s where s.tenantId=:tenant and s.productId=:product and (s.warehouseId is null or s.warehouseId=:warehouse) and s.snapshotDate>=:date",Long.class)
                    .setParameter("tenant",v.tenantId).setParameter("product",item.matchedProductId).setParameter("warehouse",item.matchedWarehouseId).setParameter("date",v.voucherDate).getSingleResult();
                if(covered>0) return "A closing-stock snapshot already covers a replacement product; reconcile it before revision";
                var baseline=em.createQuery("select count(s) from StockSnapshotBaseline s where s.tenantId=:tenant and s.productId=:product and (s.warehouseId is null or s.warehouseId=:warehouse) and s.snapshotDate<:date",Long.class)
                    .setParameter("tenant",v.tenantId).setParameter("product",item.matchedProductId).setParameter("warehouse",item.matchedWarehouseId).setParameter("date",v.voucherDate).getSingleResult();
                if(baseline==0) return "A replacement product needs a prior closing-stock baseline before stock can be revised";
            }
        }
        if(!movements.isEmpty()) {
            var deltas=deltas(v,movements);
            for(var entry:deltas.entrySet()) if(stock.currentStock(v.tenantId,entry.getKey().product,entry.getKey().warehouse).add(entry.getValue()).signum()<0 && !stock.negativeStockAllowed(v.tenantId)) return "The revised document would make stock negative; reconcile dependent stock before revision";
        }
        return "";
    }
    public boolean needsReview(SmartStagedVoucher v) { if(!supported(v)) return false; var a=assess(v); return !Set.of("NEW","UNCHANGED").contains(a.outcome())&&!a.approved(); }
    public boolean approved(SmartStagedVoucher v) { return supported(v)&&assess(v).approved(); }
    public Map<String,Object> view(SmartStagedVoucher v) {
        var a=assess(v); var result=new LinkedHashMap<String,Object>();
        result.put("voucherId",v.id); result.put("documentType",sale(v)?"SALES_INVOICE":"PURCHASE_INVOICE");
        result.put("number",v.voucherNumber); result.put("date",v.voucherDate); result.put("partyName",v.partyName);
        result.put("sourceSystem",v.rawMetadataJson.getOrDefault("Source System","IMPORT")); result.put("sourceCompany",v.rawMetadataJson.getOrDefault("Source Company","")); result.put("sourceRecordId",v.externalId);
        result.put("sourceRevision",v.rawMetadataJson.getOrDefault("Source Revision","")); result.put("sourceHash",a.sourceHash()); result.put("destinationHash",a.destinationHash());
        result.put("existingInvoiceId",a.invoiceId()); result.put("outcome",a.outcome()); result.put("eligible",a.eligible());
        result.put("blockingReason",a.blockingReason()); result.put("approved",a.approved()); result.put("stockMovementCount",a.stockMovementCount());
        result.put("existingTotal",a.existingTotal()); result.put("proposedTotal",a.proposedTotal());
        var invoice=existing(v);
        var previousLines=invoice==null?List.of():invoiceItems(v.tenantId,invoice); var proposedLines=items(v);
        result.put("existingLineCount",previousLines.size()); result.put("proposedLineCount",cancelled(v)?0:proposedLines.size());
        result.put("linePreviewTruncated",previousLines.size()>200||(!cancelled(v)&&proposedLines.size()>200));
        result.put("existingLines",previousLines.stream().limit(200).map(i->{
            var line=new LinkedHashMap<String,Object>();var p=product(v.tenantId,(UUID)get(i,"productId"));
            line.put("productName",p==null?"":p.name);line.put("quantity",get(i,"quantity"));line.put("rate",get(i,"rate"));line.put("amount",get(i,"lineTotal"));return line;
        }).toList());
        result.put("proposedLines",cancelled(v)?List.of():proposedLines.stream().limit(200).map(i->Map.of("productName",Objects.toString(i.productName,""),"unitCode",Objects.toString(i.unitCode,""),"quantity",z(i.quantity),"rate",z(i.rate),"amount",lineAmount(i),"sourceLineId",Objects.toString(i.rawMetadataJson.get("Source Line ID"),Integer.toString(i.sourceRowNumber)),"lineHash",CanonicalImportNormalizer.hash(CanonicalImportNormalizer.encoded(List.of(norm(i.productName),norm(i.unitCode),n(i.quantity),n(i.rate),n(lineAmount(i)),norm(i.warehouseName)))))).toList());
        result.put("stockChanges",invoice==null?List.of():deltas(v,movements(v.tenantId,invoice)).entrySet().stream().map(entry->{
            var current=stock.currentStock(v.tenantId,entry.getKey().product,entry.getKey().warehouse);
            var p=product(v.tenantId,entry.getKey().product);var warehouse=warehouse(v.tenantId,entry.getKey().warehouse);
            return Map.of("productId",entry.getKey().product,"productName",p==null?"":p.name,"warehouseId",entry.getKey().warehouse,"warehouseName",warehouse==null?"":warehouse.name,"currentStock",current,"change",entry.getValue(),"projectedStock",current.add(entry.getValue()));
        }).toList());
        if(!sale(v) && !permissions.has("purchases.view_cost")) {
            result.put("financialValuesRedacted",true);result.put("existingTotal",null);result.put("proposedTotal",null);
            for(var key:List.of("existingLines","proposedLines")) {
                @SuppressWarnings("unchecked") var lines=(List<Map<String,Object>>)result.get(key);
                result.put(key,lines.stream().map(line->{var safe=new LinkedHashMap<>(line);safe.remove("rate");safe.remove("amount");return safe;}).toList());
            }
        }
        return result;
    }
    @Transactional(readOnly=true) public Map<String,Object> list(UUID sessionId,int page,int size,boolean changedOnly) {
        requireSession(sessionId,false);var tenant=TenantContext.tenantId();
        int boundedPage=Math.max(0,page),boundedSize=Math.max(1,Math.min(50,size));
        var base=" where v.tenantId=:tenant and v.importSessionId=:session and (upper(v.voucherType) like '%SALE%' or upper(v.voucherType) like '%PURCHASE%') and upper(v.voucherType) not like '%RETURN%' and upper(v.voucherType) not like '%CREDIT%' and upper(v.voucherType) not like '%DEBIT%' and v.reviewStatus<>:ignored";
        var filter=changedOnly?" and v.matchStatus in :statuses":"";
        var query=em.createQuery("select v from SmartStagedVoucher v"+base+filter+" order by v.sourceRowNumber,v.id",SmartStagedVoucher.class)
            .setParameter("tenant",tenant).setParameter("session",sessionId).setParameter("ignored",DomainEnums.SmartReviewStatus.IGNORED);
        var count=em.createQuery("select count(v) from SmartStagedVoucher v"+base+filter,Long.class)
            .setParameter("tenant",tenant).setParameter("session",sessionId).setParameter("ignored",DomainEnums.SmartReviewStatus.IGNORED);
        if(changedOnly) {var statuses=Set.of(DomainEnums.SmartMatchStatus.AMBIGUOUS_REVIEW,DomainEnums.SmartMatchStatus.MATCH_EXISTING);query.setParameter("statuses",statuses);count.setParameter("statuses",statuses);}
        long total=count.getSingleResult();int offset=(int)Math.min(Integer.MAX_VALUE,(long)boundedPage*boundedSize);
        var result=new LinkedHashMap<String,Object>();result.put("items",query.setFirstResult(offset).setMaxResults(boundedSize).getResultList().stream().map(this::view).toList());
        result.put("page",boundedPage);result.put("size",boundedSize);result.put("totalElements",total);result.put("totalPages",(total+boundedSize-1)/boundedSize);result.put("changedOnly",changedOnly);
        for(var entry:Map.of("newCount",DomainEnums.SmartMatchStatus.CREATE_NEW,"unchangedCount",DomainEnums.SmartMatchStatus.SKIP_DUPLICATE).entrySet()) {
            var value=em.createQuery("select count(v) from SmartStagedVoucher v"+base+" and v.matchStatus=:status",Long.class).setParameter("tenant",tenant).setParameter("session",sessionId).setParameter("ignored",DomainEnums.SmartReviewStatus.IGNORED).setParameter("status",entry.getValue()).getSingleResult();result.put(entry.getKey(),value);
        }
        return result;
    }
    @Transactional public Map<String,Object> review(UUID sessionId,UUID voucherId,String action,String reason,String expectedSourceHash,String expectedDestinationHash,String confirmation) {
        locks.lock(TenantContext.tenantId()); requireSession(sessionId,true);
        if(!"APPLY DOCUMENT REVISION".equals(confirmation)) throw ApiErrors.badRequest("Enter APPLY DOCUMENT REVISION to confirm");
        var v=vouchers(sessionId).stream().filter(row->row.id.equals(voucherId)).findFirst().orElseThrow(()->ApiErrors.notFound("Voucher not found"));
        var a=assess(v); if(!a.sourceHash().equals(expectedSourceHash)) throw ApiErrors.conflict("Source content or mappings changed; refresh the revision preview");
        if(!a.destinationHash().equals(expectedDestinationHash)) throw ApiErrors.conflict("The stored invoice changed; refresh the revision preview");
        if(!a.eligible()) throw ApiErrors.conflict(a.blockingReason());
        if(!Set.of("CHANGED","CANCELLED").contains(a.outcome())) throw ApiErrors.conflict("This document does not need a revision");
        if(!(cancelled(v)?"CANCEL_DOCUMENT":"APPLY_CORRECTION").equals(action)) throw ApiErrors.badRequest("Revision action does not match the source document");
        v.rawMetadataJson.put("revisionApprovedSourceHash",a.sourceHash()); v.rawMetadataJson.put("revisionApprovedDestinationHash",a.destinationHash());
        v.rawMetadataJson.put("revisionReason",reason.trim()); v.rawMetadataJson.put("revisionReviewedBy",TenantContext.userId().toString());
        v.reviewStatus=DomainEnums.SmartReviewStatus.RESOLVED; v.matchStatus=DomainEnums.SmartMatchStatus.MATCH_EXISTING;
        for(var issue:em.createQuery("select i from ImportPlanIssue i where i.tenantId=:tenant and i.importSessionId=:id",ImportPlanIssue.class).setParameter("tenant",v.tenantId).setParameter("id",sessionId).getResultList()) {
            if(v.id.toString().equals(Objects.toString(issue.contextJson.get("stagingId"),"")) && Set.of("SOURCE_DOCUMENT_REVISION","DUPLICATE_VOUCHER_CONFLICT").contains(issue.code)) { issue.resolved=true; issue.resolutionJson=new LinkedHashMap<>(Map.of("action",action,"reason",reason.trim())); }
        }
        em.createQuery("delete from ImportDryRunItem i where i.tenantId=:tenant and i.importSessionId=:id").setParameter("tenant",v.tenantId).setParameter("id",sessionId).executeUpdate();
        em.createQuery("delete from ImportDryRun d where d.tenantId=:tenant and d.importSessionId=:id").setParameter("tenant",v.tenantId).setParameter("id",sessionId).executeUpdate();
        audit.logCurrent("SOURCE_DOCUMENT_REVISION_REVIEWED","SmartStagedVoucher",v.id,Map.of("action",action,"reason",reason.trim(),"sourceHash",a.sourceHash()));
        return view(v);
    }
    /** Called only in the enclosing atomic Smart Import transaction after its tenant lock. */
    public boolean applyIfReviewed(SmartStagedVoucher v,SmartImportCommit commit) {
        if(!supported(v)) return false; var a=assess(v);
        if(Set.of("NEW","UNCHANGED").contains(a.outcome())) return false;
        if(!a.approved()) throw ApiErrors.conflict(a.blockingReason().isBlank()?"Review this changed document before commit":a.blockingReason());
        var invoice=existing(v); var oldItems=invoiceItems(v.tenantId,invoice); var originalMovements=movements(v.tenantId,invoice);
        var revision=newRevision(v,invoice,cancelled(v)?"CANCEL":"CORRECT",Objects.toString(v.rawMetadataJson.get("revisionReason"),""));
        revision.beforeJson=destination(invoice); revision.beforeJson.put("stockMovements",originalMovements.stream().map(this::asMap).toList());
        for(var key:deltas(v,originalMovements).keySet().stream().sorted(Comparator.comparing(Object::toString)).toList()) stock.lockStockKey(v.tenantId,key.product,key.warehouse);
        var recheck=blocked(v,invoice,originalMovements); if(!recheck.isBlank()) throw ApiErrors.conflict(recheck);
        for(var old:originalMovements) {
            var reversal=new StockMovement(); own(reversal,v); reversal.productId=old.productId; reversal.warehouseId=old.warehouseId;
            reversal.unitId=old.unitId; reversal.movementType=DomainEnums.MovementType.ADJUSTMENT;
            reversal.quantity=old.quantity.abs().multiply(BigDecimal.valueOf(old.baseQuantity.signum()*-1)); reversal.baseQuantity=old.baseQuantity.negate();
            reversal.rate=old.rate; reversal.totalValue=old.totalValue.negate(); reversal.referenceType="SOURCE_DOCUMENT_REVISION"; reversal.referenceId=revision.id;
            reversal.linkedMovementId=old.id; reversal.decisionReason="SOURCE_REVISION_REVERSAL"; reversal.sourceFingerprint=CanonicalImportNormalizer.hash(revision.id+"|REVERSE|"+old.id);
            reversal.movementDate=old.movementDate; reversal.notes=revision.reason; em.persist(reversal);
            old.decisionReason="SOURCE_REVISION_SUPERSEDED";
        }
        // Previous content remains in the immutable revision evidence; destination ID remains stable.
        oldItems.forEach(em::remove); em.flush();
        BigDecimal subtotal=BigDecimal.ZERO;
        if(!cancelled(v)) for(var item:items(v)) {
            var p=product(v.tenantId,item.matchedProductId); var u=unit(v.tenantId,item.unitCode);
            var line=sale(v)?new SalesInvoiceItem():new PurchaseInvoiceItem(); own((TenantOwnedEntity)line,v);
            set(line,sale(v)?"salesInvoiceId":"purchaseInvoiceId",get(invoice,"id")); set(line,"productId",p.id); set(line,"unitId",u.id);
            set(line,"quantity",z(item.quantity).abs()); set(line,"rate",z(item.rate).abs()); set(line,"lineTotal",lineAmount(item));
            if(sale(v)) { set(line,"costRate",z(p.defaultPurchasePrice)); set(line,"warehouseId",item.matchedWarehouseId==null?get(invoice,"warehouseId"):item.matchedWarehouseId); } em.persist(line); subtotal=subtotal.add(lineAmount(item));
            if(!originalMovements.isEmpty() && z(item.quantity).signum()!=0) {
                var m=new StockMovement(); own(m,v); m.productId=p.id; m.warehouseId=item.matchedWarehouseId; m.unitId=u.id;
                m.movementType=sale(v)?DomainEnums.MovementType.SALE:DomainEnums.MovementType.PURCHASE;
                m.quantity=z(item.quantity).abs(); m.baseQuantity=stock.toBaseQuantity(v.tenantId,p,u.id,m.quantity).multiply(BigDecimal.valueOf(sale(v)?-1:1));
                m.rate=z(item.rate).abs(); m.totalValue=lineAmount(item); m.referenceType=sale(v)?"SALES_INVOICE":"PURCHASE_INVOICE"; m.referenceId=(UUID)get(invoice,"id");
                m.movementDate=v.voucherDate.atStartOfDay(ZoneOffset.UTC).toInstant(); m.sourceImportSessionId=v.importSessionId; m.sourceFileId=item.sessionFileId; m.sourceRowNumber=item.sourceRowNumber;
                m.sourceFingerprint=CanonicalImportNormalizer.hash(revision.id+"|REPLACE|"+item.id); m.decisionReason="SOURCE_REVISION_REPLACEMENT"; m.notes=revision.reason; em.persist(m);
            }
        }
        set(invoice,"subtotal",subtotal);
        set(invoice,"taxAmount",cancelled(v)?BigDecimal.ZERO:z(v.taxAmount)); set(invoice,"discountAmount",cancelled(v)?BigDecimal.ZERO:z(v.discountAmount));
        set(invoice,"freightAmount",cancelled(v)?BigDecimal.ZERO:z(v.freightAmount)); set(invoice,"roundOffAmount",cancelled(v)?BigDecimal.ZERO:z(v.roundOffAmount));
        set(invoice,"otherChargesAmount",cancelled(v)?BigDecimal.ZERO:z(v.otherChargesAmount)); set(invoice,"totalAmount",cancelled(v)?BigDecimal.ZERO:z(v.totalAmount));
        set(invoice,"updatedBy",TenantContext.userId()); stamp(v,invoice,revision.id); em.flush();
        revision.afterJson=destination(invoice); em.persist(revision);
        var effect=new SmartImportEffect(); own(effect,v); effect.importSessionId=v.importSessionId; effect.commitId=commit.id; effect.sourceFileId=v.sessionFileId; effect.sourceRowNumber=v.sourceRowNumber;
        effect.entityType=sale(v)?DomainEnums.SmartImportEffectEntityType.SALES_INVOICE:DomainEnums.SmartImportEffectEntityType.PURCHASE_INVOICE;
        effect.entityId=(UUID)get(invoice,"id"); effect.action=DomainEnums.SmartImportEffectAction.UPDATED; effect.oldValueJson=revision.beforeJson; effect.newValueJson=revision.afterJson; effect.reversible=false; em.persist(effect);
        audit.logCurrent("SOURCE_DOCUMENT_"+revision.operation,"SourceDocumentRevision",revision.id,Map.of("invoiceId",revision.invoiceId,"reason",revision.reason,"sourceHash",revision.sourceHash));
        return true;
    }
    public void recordCreated(SmartStagedVoucher v,Object invoice) {
        var revision=newRevision(v,invoice,"CREATE",""); stamp(v,invoice,revision.id); em.flush(); revision.afterJson=destination(invoice); em.persist(revision);
    }
    private void stamp(SmartStagedVoucher v,Object invoice,UUID revisionId) {
        var meta=metadata(invoice); meta.put("sourceSystem",Objects.toString(v.rawMetadataJson.get("Source System"),"IMPORT")); meta.put("sourceCompany",Objects.toString(v.rawMetadataJson.get("Source Company"),""));
        meta.put("sourceRecordId",Objects.toString(v.externalId,"")); meta.put("sourceRevision",Objects.toString(v.rawMetadataJson.get("Source Revision"),""));
        meta.put("sourceCanonicalHash",sourceHash(v)); meta.put("sourceSchemaVersion",CanonicalImportNormalizer.VERSION);
        meta.put("sourceRevisionId",revisionId.toString()); meta.put("sourceDocumentState",cancelled(v)?"CANCELLED":"ACTIVE");
        meta.put("sourceImportSessionId",v.importSessionId.toString()); meta.put("sourceFileId",v.sessionFileId.toString());
        if(sale(v)) meta.put("costProvenance","ESTIMATED_CURRENT_COST");
        meta.put("sourceDestinationHash",destinationHash(invoice)); set(invoice,"financialMetadata",meta);
    }
    private SourceDocumentRevision newRevision(SmartStagedVoucher v,Object invoice,String operation,String reason) {
        var r=new SourceDocumentRevision(); own(r,v); r.importSessionId=v.importSessionId;r.sourceFileId=v.sessionFileId;r.invoiceId=(UUID)get(invoice,"id");
        r.documentType=sale(v)?"SALES_INVOICE":"PURCHASE_INVOICE";r.sourceSystem=Objects.toString(v.rawMetadataJson.get("Source System"),"IMPORT");r.sourceCompany=Objects.toString(v.rawMetadataJson.get("Source Company"),"");
        r.sourceRecordId=Objects.toString(v.externalId,"");r.sourceRevision=Objects.toString(v.rawMetadataJson.get("Source Revision"),"");r.sourceHash=sourceHash(v);r.sourceIdentityHash=sourceIdentityHash(v);r.operation=operation;r.reason=reason;return r;
    }
    private String sourceIdentityHash(SmartStagedVoucher v) {
        return CanonicalImportNormalizer.hashValues(List.of(Objects.toString(v.rawMetadataJson.get("Source System"),"IMPORT"),Objects.toString(v.rawMetadataJson.get("Source Company"),""),sale(v)?"SALES_INVOICE":"PURCHASE_INVOICE",Objects.toString(v.externalId,"")));
    }
    private boolean legacySame(SmartStagedVoucher v,Object invoice) {
        if(cancelled(v)||!Objects.equals(v.voucherDate,get(invoice,"invoiceDate"))||!Objects.equals(v.matchedPartyId,get(invoice,sale(v)?"customerId":"supplierId"))||z(v.totalAmount).compareTo((BigDecimal)get(invoice,"totalAmount"))!=0) return false;
        var expected=items(v).stream().map(i->CanonicalImportNormalizer.encoded(List.of(Objects.toString(i.matchedProductId,""),norm(i.unitCode),n(i.quantity),money(i.rate),money(lineAmount(i))))).sorted().toList();
        var actual=invoiceItems(v.tenantId,invoice).stream().map(i->{ var u=em.find(UnitOfMeasure.class,get(i,"unitId"));return CanonicalImportNormalizer.encoded(List.of(get(i,"productId").toString(),u==null?"":norm(u.code),n((BigDecimal)get(i,"quantity")),money((BigDecimal)get(i,"rate")),money((BigDecimal)get(i,"lineTotal"))));}).sorted().toList();
        return actual.equals(expected);
    }
    public List<StockMovement> movements(UUID tenant,Object invoice) {
        var ids=invoiceItems(tenant,invoice).stream().map(i->(UUID)get(i,"id")).collect(Collectors.toSet()); ids.add((UUID)get(invoice,"id"));
        return em.createQuery("select m from StockMovement m where m.tenantId=:tenant and m.referenceId in :ids and (m.decisionReason is null or m.decisionReason<>'SOURCE_REVISION_SUPERSEDED')",StockMovement.class)
            .setParameter("tenant",tenant).setParameter("ids",ids).getResultList();
    }
    private record StockKey(UUID product,UUID warehouse) { }
    private Map<StockKey,BigDecimal> deltas(SmartStagedVoucher v,List<StockMovement> old) {
        var result=new LinkedHashMap<StockKey,BigDecimal>(); if(old.isEmpty()) return result;
        for(var m:old) result.merge(new StockKey(m.productId,m.warehouseId),m.baseQuantity.negate(),BigDecimal::add);
        if(!cancelled(v)) for(var i:items(v)) { var p=product(v.tenantId,i.matchedProductId);var u=unit(v.tenantId,i.unitCode); if(p==null||u==null||i.matchedWarehouseId==null) continue;
            result.merge(new StockKey(p.id,i.matchedWarehouseId),stock.toBaseQuantity(v.tenantId,p,u.id,z(i.quantity).abs()).multiply(BigDecimal.valueOf(sale(v)?-1:1)),BigDecimal::add); }
        return result;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> metadata(Object o) { return new LinkedHashMap<>((Map<String,Object>)get(o,"financialMetadata")); }
    private Map<String,Object> destination(Object invoice) { var map=new LinkedHashMap<String,Object>();map.put("invoice",asMap(invoice));map.put("items",invoiceItems((UUID)get(invoice,"tenantId"),invoice).stream().map(this::asMap).toList());return map; }
    private String destinationHash(Object invoice) {
        var fields=new TreeMap<String,Object>();
        for(var name:List.of("invoiceNumber","invoiceDate","subtotal","taxAmount","discountAmount","freightAmount","roundOffAmount","otherChargesAmount","totalAmount","warehouseId")) {var value=get(invoice,name);fields.put(name,value instanceof BigDecimal decimal?money(decimal):Objects.toString(value,""));}
        fields.put("party",get(invoice,invoice instanceof SalesInvoice?"customerId":"supplierId"));
        fields.put("items",invoiceItems((UUID)get(invoice,"tenantId"),invoice).stream().map(i->CanonicalImportNormalizer.encoded(List.of(Objects.toString(get(i,"productId"),""),Objects.toString(get(i,"unitId"),""),i instanceof SalesInvoiceItem saleItem?Objects.toString(saleItem.warehouseId,""):"",n((BigDecimal)get(i,"quantity")),money((BigDecimal)get(i,"rate")),money((BigDecimal)get(i,"lineTotal"))))).sorted().toList());
        return CanonicalImportNormalizer.hashValues(fields);
    }
    private List<?> invoiceItems(UUID tenant,Object invoice) {return em.createQuery("select i from "+(invoice instanceof SalesInvoice?"SalesInvoiceItem":"PurchaseInvoiceItem")+" i where i.tenantId=:tenant and i."+(invoice instanceof SalesInvoice?"salesInvoiceId":"purchaseInvoiceId")+"=:id order by i.id").setParameter("tenant",tenant).setParameter("id",get(invoice,"id")).getResultList();}
    @SuppressWarnings("unchecked") private Map<String,Object> asMap(Object o) {return json.convertValue(o,LinkedHashMap.class);}
    private Product product(UUID tenant,UUID id) {if(id==null)return null;return em.createQuery("select p from Product p where p.tenantId=:tenant and p.id=:id",Product.class).setParameter("tenant",tenant).setParameter("id",id).getResultStream().findFirst().orElse(null);}
    private Warehouse warehouse(UUID tenant,UUID id) {if(id==null)return null;return em.createQuery("select w from Warehouse w where w.tenantId=:tenant and w.id=:id",Warehouse.class).setParameter("tenant",tenant).setParameter("id",id).getResultStream().findFirst().orElse(null);}
    private UnitOfMeasure unit(UUID tenant,String code) {return em.createQuery("select u from UnitOfMeasure u where u.tenantId=:tenant and upper(u.code)=:code",UnitOfMeasure.class).setParameter("tenant",tenant).setParameter("code",norm(code)).getResultStream().findFirst().orElse(null);}
    private long count(String entity,String field,UUID value,UUID tenant) {return em.createQuery("select count(e) from "+entity+" e where e.tenantId=:tenant and e."+field+"=:value",Long.class).setParameter("tenant",tenant).setParameter("value",value).getSingleResult();}
    private void requireSession(UUID id,boolean editable) {var s=em.createQuery("select s from ImportSession s where s.tenantId=:tenant and s.id=:id",ImportSession.class).setParameter("tenant",TenantContext.tenantId()).setParameter("id",id).getResultStream().findFirst().orElseThrow(()->ApiErrors.notFound("Import session not found"));if(editable && Set.of(DomainEnums.ImportSessionStatus.COMMITTED,DomainEnums.ImportSessionStatus.CANCELLED,DomainEnums.ImportSessionStatus.COMMITTING).contains(s.status))throw ApiErrors.conflict("Session is no longer editable");}
    private List<SmartStagedVoucher> vouchers(UUID id) {return em.createQuery("select v from SmartStagedVoucher v where v.tenantId=:tenant and v.importSessionId=:id",SmartStagedVoucher.class).setParameter("tenant",TenantContext.tenantId()).setParameter("id",id).getResultList();}
    private void own(TenantOwnedEntity entity,SmartStagedVoucher v) {entity.tenantId=v.tenantId;entity.createdBy=TenantContext.userId();entity.updatedBy=TenantContext.userId();}
    private static Object get(Object o,String field) {try{return o.getClass().getField(field).get(o);}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}}
    private static void set(Object o,String field,Object value) {try{o.getClass().getField(field).set(o,value);}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}}
    private static String norm(String s){return s==null?"":s.trim().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);}
    private static BigDecimal z(BigDecimal v){return v==null?BigDecimal.ZERO:v;}
    private static String money(BigDecimal v){return z(v).setScale(2,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
    private static String n(BigDecimal v){return z(v).stripTrailingZeros().toPlainString();}
    private static BigDecimal lineAmount(SmartStagedVoucherItem i){return i.amount==null?z(i.quantity).multiply(z(i.rate)).abs():i.amount.abs();}
}
