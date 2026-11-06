package com.stockpilot.ai.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.service.AccountLifecycleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real FK coverage. Opt-in is deliberately pinned to the disposable local audit cluster. */
@SpringBootTest @AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="STOCKPILOT_AUDIT_PG_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:55434/stockpilot_audit")
@ActiveProfiles({"test","audit-postgres"})
@Import(LaunchAuditPostgresIntegrationTest.DatabaseGuard.class)
class SmartImportLifecyclePostgresTest {
    private static final String AUDIT_URL="jdbc:postgresql://127.0.0.1:55434/stockpilot_audit";
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",()->AUDIT_URL);
        properties.add("spring.datasource.username",()->"audit");
        properties.add("spring.datasource.password",()->"");
        properties.add("spring.flyway.url",()->AUDIT_URL);
        properties.add("spring.flyway.user",()->"audit");
        properties.add("spring.flyway.password",()->"");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccountLifecycleService lifecycle;
    @AfterEach void clearContext() { TenantContext.clear(); }

    @Test void resetRemovesCompleteImportGraphAndPreservesOtherTenant() throws Exception { exercise(false); }
    @Test void deleteRemovesCompleteImportGraphAndPreservesOtherTenant() throws Exception { exercise(true); }

    private void exercise(boolean delete) throws Exception {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stockpilot_audit");
        assertThat(jdbc.queryForObject("select inet_server_port()",Integer.class)).isEqualTo(55434);
        assertThat(jdbc.queryForObject("select current_user",String.class)).isEqualTo("audit");
        var owner=register(); var keeper=register();
        var ownedTables=seed(owner); var keptTables=seed(keeper);
        var before=count(keeper.tenantId,keptTables);
        assertThat(before.values()).allSatisfy(value->assertThat(value).isPositive());
        TenantContext.set(owner.tenantId,owner.userId,DomainEnums.Role.OWNER);
        if(delete) lifecycle.deleteCurrentWorkspace("DELETE WORKSPACE");
        else lifecycle.resetCurrentWorkspace("RESET WORKSPACE DATA");
        assertThat(count(owner.tenantId,ownedTables).values()).allSatisfy(value->assertThat(value).isZero());
        assertThat(count(keeper.tenantId,keptTables)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select status from tenants where id=?",String.class,owner.tenantId))
            .isEqualTo(delete?"DELETED":"ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from user_tenant_memberships where tenant_id=?",Long.class,owner.tenantId))
            .isEqualTo(delete?0L:1L);
        assertThat(jdbc.queryForObject("select status from tenants where id=?",String.class,keeper.tenantId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from user_tenant_memberships where tenant_id=?",Long.class,keeper.tenantId)).isEqualTo(1L);
    }

    private LinkedHashSet<String> seed(Owner owner) {
        var tables=new LinkedHashSet<String>(); var tenant=owner.tenantId;
        var unit=insert(tables,tenant,"units_of_measure",fields("code","PCS","name","Piece"));
        var product=insert(tables,tenant,"products",fields("name","Fixture product","base_unit_id",unit));
        var warehouse=insert(tables,tenant,"warehouses",fields("name","Fixture warehouse"));
        var customer=insert(tables,tenant,"customers",fields("name","Fixture customer"));
        var supplier=insert(tables,tenant,"suppliers",fields("name","Fixture supplier"));
        jdbc.update("update user_tenant_memberships set customer_id=? where tenant_id=?",customer,tenant);
        var batch=insert(tables,tenant,"import_batches",fields("source_type","CSV","status","COMMITTED"));
        insert(tables,tenant,"legacy_import_raw_rows",fields("import_batch_id",batch,"row_number",1));
        insert(tables,tenant,"import_files",fields("import_batch_id",batch,"storage_key",tenant+"/missing-legacy.csv"));
        insert(tables,tenant,"import_errors",fields("import_batch_id",batch,"row_number",1,"message","Fixture diagnostic"));
        insert(tables,tenant,"staging_products",fields("import_batch_id",batch,"row_number",1,"product_name","Fixture product"));
        var session=insert(tables,tenant,"import_sessions",fields("name","Committed fixture","status","COMMITTED","recommended_strategy","HYBRID_RECONCILIATION"));
        var file=insert(tables,tenant,"import_session_files",fields("import_session_id",session,"storage_key",tenant+"/missing-smart.xml","original_file_name","smart.xml","file_hash","f".repeat(64),"detected_file_type","MIXED_TALLY_VOUCHERS","status","CLASSIFIED"));
        insert(tables,tenant,"import_plans",fields("import_session_id",session,"status","READY","strategy","HYBRID_RECONCILIATION"));
        insert(tables,tenant,"import_plan_issues",fields("import_session_id",session,"affected_file_id",file,"severity","WARNING","code","FIXTURE_WARNING"));
        var stagedProduct=insert(tables,tenant,"smart_staged_products",fields("import_session_id",session,"session_file_id",file,"source_type","INVENTORY_MASTER","match_status","MATCH_EXISTING","review_status","RESOLVED","matched_product_id",product));
        insert(tables,tenant,"smart_staged_parties",fields("import_session_id",session,"session_file_id",file,"party_type","CUSTOMER","match_status","MATCH_EXISTING","review_status","RESOLVED","matched_customer_id",customer));
        insert(tables,tenant,"smart_staged_warehouses",fields("import_session_id",session,"session_file_id",file,"matched_warehouse_id",warehouse,"match_status","MATCH_EXISTING","review_status","RESOLVED"));
        insert(tables,tenant,"smart_staged_units",fields("import_session_id",session,"session_file_id",file,"matched_unit_id",unit,"match_status","MATCH_EXISTING","review_status","RESOLVED"));
        var voucher=insert(tables,tenant,"smart_staged_vouchers",fields("import_session_id",session,"session_file_id",file,"voucher_type","Sales","voucher_number","S-FIXTURE","match_status","CREATE_NEW","review_status","RESOLVED"));
        var stagedItem=insert(tables,tenant,"smart_staged_voucher_items",fields("import_session_id",session,"session_file_id",file,"staged_voucher_id",voucher,"match_status","MATCH_EXISTING","review_status","RESOLVED","matched_product_id",product));
        insert(tables,tenant,"smart_staged_stock_snapshots",fields("import_session_id",session,"session_file_id",file,"matched_product_id",product,"matched_warehouse_id",warehouse,"action","CREATE_SNAPSHOT_ADJUSTMENT","match_status","MATCH_EXISTING","review_status","RESOLVED"));
        var dryRun=insert(tables,tenant,"import_dry_runs",fields("import_session_id",session,"status","COMPLETED","strategy","HYBRID_RECONCILIATION"));
        insert(tables,tenant,"import_dry_run_items",fields("import_session_id",session,"dry_run_id",dryRun,"source_file_id",file,"item_type","STOCK_MOVEMENT","action","CREATE"));
        var commit=insert(tables,tenant,"smart_import_commits",fields("import_session_id",session,"dry_run_id",dryRun,"status","COMMITTED","strategy","HYBRID_RECONCILIATION"));
        insert(tables,tenant,"smart_import_effects",fields("import_session_id",session,"commit_id",commit,"entity_type","PRODUCT","entity_id",product,"action","CREATED"));
        insert(tables,tenant,"external_record_mappings",fields("import_session_id",session,"source_file_id",file,"source_system","TALLY","entity_type","PRODUCT","local_entity_type","PRODUCT","local_entity_id",product));
        var baseline=insert(tables,tenant,"stock_snapshot_baselines",fields("product_id",product,"warehouse_id",warehouse,"adjustment_warehouse_id",warehouse,"source_import_session_id",session,"source_file_id",file));
        var movement=insert(tables,tenant,"stock_movements",fields("product_id",product,"warehouse_id",warehouse,"unit_id",unit,"movement_type","RETURN_IN","source_import_session_id",session,"source_file_id",file,"snapshot_baseline_id",baseline));
        var paired=insert(tables,tenant,"stock_movements",fields("product_id",product,"warehouse_id",warehouse,"unit_id",unit,"movement_type","TRANSFER_OUT","source_import_session_id",session,"linked_movement_id",movement));
        jdbc.update("update stock_movements set linked_movement_id=? where id=?",paired,movement);
        insert(tables,tenant,"smart_staged_transfer_candidates",fields("import_session_id",session,"staged_voucher_id",voucher,"session_file_id",file,"source_item_id",stagedItem,"destination_item_id",stagedItem,"matched_product_id",product,"source_warehouse_id",warehouse,"destination_warehouse_id",warehouse,"source_snapshot_id",baseline,"destination_snapshot_id",baseline,"classification","WAREHOUSE_TRANSFER"));
        var sale=insert(tables,tenant,"sales_invoices",fields("customer_id",customer,"warehouse_id",warehouse,"invoice_number","S-FIXTURE"));
        var purchase=insert(tables,tenant,"purchase_invoices",fields("supplier_id",supplier,"warehouse_id",warehouse,"invoice_number","P-FIXTURE"));
        insert(tables,tenant,"sales_invoice_items",fields("sales_invoice_id",sale,"product_id",product,"unit_id",unit));
        insert(tables,tenant,"purchase_invoice_items",fields("purchase_invoice_id",purchase,"product_id",product,"unit_id",unit));
        var received=insert(tables,tenant,"customer_payments",fields("customer_id",customer,"sales_invoice_id",sale,"source_import_session_id",session,"source_file_id",file));
        var paid=insert(tables,tenant,"supplier_payments",fields("supplier_id",supplier,"purchase_invoice_id",purchase,"source_import_session_id",session,"source_file_id",file));
        var retail=insert(tables,tenant,"retail_counter_sales",fields("collection_method","CASH","source_import_session_id",session,"source_file_id",file,"source_fingerprint","r".repeat(64)));
        insert(tables,tenant,"smart_staged_cashbook_entries",fields("import_session_id",session,"session_file_id",file,"customer_payment_id",received,"supplier_payment_id",paid,"retail_counter_sale_id",retail,"matched_staged_voucher_id",voucher,"direction","IN","match_status","MATCH_EXISTING","review_status","RESOLVED"));
        var adjustment=insert(tables,tenant,"financial_adjustments",fields("adjustment_type","CREDIT_NOTE","customer_id",customer,"voucher_number","CN-FIXTURE"));
        insert(tables,tenant,"financial_adjustment_items",fields("financial_adjustment_id",adjustment,"product_id",product,"warehouse_id",warehouse,"unit_id",unit,"stock_movement_id",movement));
        insert(tables,tenant,"outstanding_snapshots",fields("party_type","CUSTOMER","customer_id",customer,"source_import_session_id",session,"source_file_id",file,"source_fingerprint","s".repeat(64)));
        insert(tables,tenant,"mutation_receipts",fields("user_id",owner.userId,"operation","CUSTOMER_PAYMENT","request_key","fixture-payment","request_hash","h".repeat(64),"result_id",received));
        return tables;
    }

    // Populate uninteresting mandatory scalar fields from the migrated schema; every relationship above is explicit.
    private UUID insert(LinkedHashSet<String> tables,UUID tenant,String table,Map<String,Object> overrides) {
        var values=new LinkedHashMap<String,Object>(); var id=UUID.randomUUID();
        values.put("id",id); values.put("tenant_id",tenant); values.putAll(overrides);
        var columns=jdbc.queryForList("select column_name,data_type from information_schema.columns where table_schema='public' and table_name=? and is_nullable='NO' and column_default is null order by ordinal_position",table);
        for(var column:columns) {
            var name=column.get("column_name").toString(); if(values.containsKey(name))continue;
            Object value=switch(column.get("data_type").toString()) {
                case "character varying","text" -> "FIXTURE";
                case "timestamp with time zone","timestamp without time zone" -> Timestamp.from(Instant.now());
                case "date" -> java.sql.Date.valueOf(LocalDate.of(2026,6,15));
                case "integer","smallint","bigint" -> 1;
                case "numeric","double precision","real" -> BigDecimal.ONE;
                case "boolean" -> false;
                default -> throw new IllegalStateException("Explicit fixture value required for "+table+"."+name);
            };
            values.put(name,value);
        }
        jdbc.update("insert into "+table+" ("+String.join(",",values.keySet())+") values ("+String.join(",",java.util.Collections.nCopies(values.size(),"?"))+")",values.values().toArray());
        tables.add(table); return id;
    }
    private Map<String,Long> count(UUID tenant,LinkedHashSet<String> tables) {
        var result=new LinkedHashMap<String,Long>(); for(var table:tables)result.put(table,jdbc.queryForObject("select count(*) from "+table+" where tenant_id=?",Long.class,tenant));return result;
    }
    private Map<String,Object> fields(Object... pairs) {var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1]);return result;}
    private Owner register() throws Exception {
        var response=mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"businessName\":\"PG lifecycle fixture\",\"businessMode\":\"HYBRID\",\"email\":\"lifecycle-"+UUID.randomUUID()+"@example.com\",\"password\":\"password123\",\"fullName\":\"Fixture owner\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var json=mapper.readTree(response); return new Owner(UUID.fromString(json.path("tenantId").asText()),UUID.fromString(json.path("userId").asText()));
    }
    private record Owner(UUID tenantId,UUID userId) { }
}
