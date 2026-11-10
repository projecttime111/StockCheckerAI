package com.stockpilot.ai.service;

import com.stockpilot.ai.repo.Repositories;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Rechecks actual destination values, including sessions staged by an older application version. */
@Service
public class SmartImportDestinationValidator {
    private final Repositories.SmartStagedProductRepository products;
    private final Repositories.SmartStagedPartyRepository parties;
    private final Repositories.SmartStagedWarehouseRepository warehouses;
    private final Repositories.SmartStagedUnitRepository units;
    private final Repositories.SmartStagedVoucherRepository vouchers;
    private final Repositories.SmartStagedVoucherItemRepository items;
    public SmartImportDestinationValidator(Repositories.SmartStagedProductRepository products,
        Repositories.SmartStagedPartyRepository parties, Repositories.SmartStagedWarehouseRepository warehouses,
        Repositories.SmartStagedUnitRepository units, Repositories.SmartStagedVoucherRepository vouchers,
        Repositories.SmartStagedVoucherItemRepository items) {
        this.products=products; this.parties=parties; this.warehouses=warehouses; this.units=units;
        this.vouchers=vouchers; this.items=items;
    }
    public record Violation(UUID fileId, int rowNumber, String field, String message) { }
    public List<Violation> validate(UUID tenantId, UUID sessionId) {
        var result=new ArrayList<Violation>();
        for(var row:products.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId,sessionId))
            add(result,row.sessionFileId,row.sourceRowNumber,fields("Product Name",row.rawName,"SKU",row.sku,
                "Unit",row.unitCode,"HSN",row.hsn,"Barcode",row.barcode,"Purchase Price",row.purchasePrice,"Sales Price",row.salesPrice,
                "Category",row.categoryName,"Brand",row.brandName));
        for(var row:products.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId,sessionId)) {
            var error=row.rawMetadataJson.get("GST Mapping Error");
            if(error!=null && !error.toString().isBlank()) result.add(new Violation(row.sessionFileId,row.sourceRowNumber,"GST %",error.toString()));
            if(row.gstPercent!=null && (row.gstPercent.signum()<0 || row.gstPercent.compareTo(BigDecimal.valueOf(100))>0)) result.add(new Violation(row.sessionFileId,row.sourceRowNumber,"GST %","GST must be between 0 and 100"));
        }
        for(var row:parties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId,sessionId))
            add(result,row.sessionFileId,row.sourceRowNumber,fields("Party Name",row.rawName,"Opening Balance",row.openingBalance));
        for(var row:warehouses.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId,sessionId))
            add(result,row.sessionFileId,row.sourceRowNumber,fields("Warehouse",row.sourceWarehouseName));
        for(var row:units.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId,sessionId))
            add(result,row.sessionFileId,row.sourceRowNumber,fields("Unit",row.normalizedCode));
        var excluded=new java.util.HashSet<UUID>();
        for(var row:vouchers.findByTenantIdAndImportSessionIdOrderByVoucherDateAscSourceRowNumberAsc(tenantId,sessionId)) {
            if(Boolean.parseBoolean(String.valueOf(row.rawMetadataJson.getOrDefault("excludedFromCommit",false)))) {
                excluded.add(row.id); continue;
            }
            for(var bound:Map.of("Source System",80,"Source Company",500,"Source Record ID",500,"Source Revision",200).entrySet()) {
                var value=row.rawMetadataJson.get(bound.getKey());
                if(value!=null && value.toString().length()>bound.getValue()) result.add(new Violation(row.sessionFileId,row.sourceRowNumber,bound.getKey(),bound.getKey()+" exceeds "+bound.getValue()+" characters"));
            }
            add(result,row.sessionFileId,row.sourceRowNumber,fields("Voucher Number",row.voucherNumber,
                "Party Name",row.partyName,"Total Amount",row.totalAmount));
        }
        for(var row:items.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId,sessionId)) {
            if(excluded.contains(row.stagedVoucherId)) continue;
            var lineAmount=row.amount;
            if(lineAmount==null && row.quantity!=null && row.rate!=null) lineAmount=row.quantity.multiply(row.rate);
            add(result,row.sessionFileId,row.sourceRowNumber,fields("Qty",row.quantity,"Rate",row.rate,
                "Line Amount",lineAmount,"Unit",row.unitCode,"Warehouse",row.warehouseName));
        }
        return result;
    }
    private void add(List<Violation> result,UUID fileId,int row,Map<String,Object> values) {
        for(var violation:ImportFieldBounds.validate(values))
            result.add(new Violation(fileId,row,violation.field(),violation.message()));
    }
    private Map<String,Object> fields(Object... values) {
        var result=new LinkedHashMap<String,Object>();
        for(int i=0;i<values.length;i+=2) result.put(values[i].toString(),values[i+1]);
        return result;
    }
}
