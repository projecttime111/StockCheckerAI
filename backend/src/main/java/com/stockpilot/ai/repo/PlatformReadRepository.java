package com.stockpilot.ai.repo;

import com.stockpilot.ai.web.dto.PlatformDtos;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cross-tenant metadata queries. Only PlatformAdminService may expose these reads. */
@Repository
public class PlatformReadRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public PlatformReadRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String IMPORTS = """
        select b.id, b.tenant_id, 'LEGACY' as kind, cast(b.source_type as varchar(32)) as source, cast(b.status as varchar(32)) as status,
               b.original_file_name as name, b.created_at, b.committed_at, b.updated_at,
               b.row_count, b.error_count,
               (select count(*) from import_files f where f.tenant_id=b.tenant_id and f.import_batch_id=b.id) as file_count
        from import_batches b
        union all
        select s.id, s.tenant_id, 'SMART' as kind, 'SMART_IMPORT' as source, cast(s.status as varchar(32)) as status,
               s.name, s.created_at, s.committed_at, s.updated_at,
               coalesce((select sum(f.row_count) from import_session_files f where f.tenant_id=s.tenant_id and f.import_session_id=s.id),0),
               coalesce((select sum(f.error_count) from import_session_files f where f.tenant_id=s.tenant_id and f.import_session_id=s.id),0),
               (select count(*) from import_session_files f where f.tenant_id=s.tenant_id and f.import_session_id=s.id)
        from import_sessions s
        """;
    private static final String BUSINESS_FROM = " from tenants t left join platform_client_profiles p on p.tenant_id=t.id ";
    private static final String BUSINESS_SELECT = """
        select t.id,t.name,t.business_mode,t.status,t.currency,t.gst_enabled,t.created_at,
               coalesce(p.plan,'FREE') as plan,coalesce(p.subscription_status,'TRIAL') as subscription_status,p.subscription_ends_at,
               (select count(*) from user_tenant_memberships m where m.tenant_id=t.id) as member_count,
               (select count(*) from products v where v.tenant_id=t.id) as product_count,
               ((select count(*) from import_batches b where b.tenant_id=t.id)+(select count(*) from import_sessions s where s.tenant_id=t.id)) as import_count,
               (select max(i.created_at) from (select tenant_id,created_at from import_batches union all select tenant_id,created_at from import_sessions) i where i.tenant_id=t.id) as last_import_at
        """;

    public PlatformDtos.Page<PlatformDtos.BusinessSummary> businesses(int page, int size, String search, String status, String plan, String subscriptionStatus) {
        var parameters = pageParameters(page, size);
        var where = new StringBuilder(" where 1=1");
        filter(where, parameters, "t.status", "status", status);
        filter(where, parameters, "coalesce(p.plan,'FREE')", "plan", plan);
        filter(where, parameters, "coalesce(p.subscription_status,'TRIAL')", "subscriptionStatus", subscriptionStatus);
        if (search != null) {
            where.append(" and lower(t.name) like :search escape '!'");
            parameters.addValue("search", searchPattern(search));
        }
        long total = scalar("select count(*)" + BUSINESS_FROM + where, parameters);
        var rows = jdbc.query(BUSINESS_SELECT + BUSINESS_FROM + where + " order by t.created_at desc,t.id limit :limit offset :offset", parameters, (rs, n) -> business(rs));
        return page(rows, page, size, total);
    }

    public PlatformDtos.BusinessSummary business(UUID tenantId) {
        var rows = jdbc.query(BUSINESS_SELECT + BUSINESS_FROM + " where t.id=:tenant", new MapSqlParameterSource("tenant", tenantId), (rs, n) -> business(rs));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public PlatformDtos.Usage usage(UUID tenantId) {
        var p = new MapSqlParameterSource("tenant", tenantId);
        var business = business(tenantId);
        return new PlatformDtos.Usage(business.memberCount(), business.productCount(), countTenant("customers", p),
            countTenant("suppliers", p), countTenant("warehouses", p), countTenant("sales_invoices", p),
            countTenant("purchase_invoices", p), countTenant("stock_movements", p), business.importCount(),
            countTenant("import_files", p) + countTenant("import_session_files", p), business.lastImportAt());
    }

    public PlatformDtos.Page<PlatformDtos.Member> members(UUID tenantId, int page, int size) {
        var p = pageParameters(page, size).addValue("tenant", tenantId);
        long total = scalar("select count(*) from user_tenant_memberships where tenant_id=:tenant", p);
        var rows = jdbc.query("""
            select u.id,u.email,u.full_name,u.active,m.role,m.created_at from user_tenant_memberships m
            join app_users u on u.id=m.user_id where m.tenant_id=:tenant
            order by m.created_at desc,m.id limit :limit offset :offset
            """, p, (rs, n) -> new PlatformDtos.Member(uuid(rs,"id"),rs.getString("email"),rs.getString("full_name"),rs.getString("role"),rs.getBoolean("active"),instant(rs,"created_at")));
        return page(rows, page, size, total);
    }

    public PlatformDtos.Page<PlatformDtos.ImportSummary> imports(int page, int size, String search, String status, String source, boolean failedOnly, UUID tenantId) {
        var p = pageParameters(page, size);
        var where = new StringBuilder(" where 1=1");
        filter(where, p, "i.status", "status", status);
        filter(where, p, "i.source", "source", source);
        if (tenantId != null) { where.append(" and i.tenant_id=:tenant"); p.addValue("tenant", tenantId); }
        if (failedOnly) where.append(" and i.status in ('FAILED','VALIDATION_FAILED')");
        if (search != null) {
            where.append(" and (lower(t.name) like :search escape '!' or lower(i.name) like :search escape '!' or exists (select 1 from import_session_files f where f.tenant_id=i.tenant_id and f.import_session_id=i.id and lower(f.original_file_name) like :search escape '!'))");
            p.addValue("search", searchPattern(search));
        }
        String from = " from (" + IMPORTS + ") i join tenants t on t.id=i.tenant_id";
        long total = scalar("select count(*)" + from + where, p);
        var rows = jdbc.query("select i.*,t.name as business_name" + from + where + " order by i.created_at desc,i.id,i.kind limit :limit offset :offset", p,
            (rs,n) -> new PlatformDtos.ImportSummary(uuid(rs,"id"),uuid(rs,"tenant_id"),rs.getString("business_name"),rs.getString("kind"),rs.getString("source"),rs.getString("status"),rs.getString("name"),instant(rs,"created_at"),instant(rs,"committed_at"),rs.getLong("row_count"),rs.getLong("error_count"),rs.getLong("file_count"),List.of()));
        var names = fileNames(rows);
        rows = rows.stream().map(row -> new PlatformDtos.ImportSummary(row.id(),row.tenantId(),row.businessName(),row.kind(),row.source(),row.status(),row.name(),row.createdAt(),row.committedAt(),row.rowCount(),row.errorCount(),row.fileCount(),names.getOrDefault(row.kind()+row.id(), List.of()))).toList();
        return page(rows, page, size, total);
    }

    private Map<String,List<String>> fileNames(List<PlatformDtos.ImportSummary> rows) {
        var result = new LinkedHashMap<String,List<String>>();
        for (String kind : List.of("LEGACY", "SMART")) {
            var ids = rows.stream().filter(row -> row.kind().equals(kind)).map(PlatformDtos.ImportSummary::id).toList();
            if (ids.isEmpty()) continue;
            String sql = kind.equals("LEGACY")
                ? "select import_batch_id as import_id,file_name as file_name from import_files where import_batch_id in (:ids) order by created_at,id"
                : "select import_session_id as import_id,original_file_name as file_name from import_session_files where import_session_id in (:ids) order by created_at,id";
            jdbc.query(sql, new MapSqlParameterSource("ids", ids), rs -> {
                String fileName = rs.getString("file_name");
                if (fileName != null) result.computeIfAbsent(kind + rs.getObject("import_id"), ignored -> new ArrayList<>()).add(fileName);
            });
        }
        return result;
    }

    public PlatformDtos.Dashboard dashboard() {
        var noParameters = new MapSqlParameterSource();
        var tenantCounts = grouped("select status,count(*) as total from tenants group by status", "status");
        var imports = importStatusCounts();
        return new PlatformDtos.Dashboard(Instant.now(),tenantCounts.values().stream().mapToLong(Long::longValue).sum(),
            tenantCounts.getOrDefault("ACTIVE",0L),tenantCounts.getOrDefault("SUSPENDED",0L),tenantCounts.getOrDefault("DELETED",0L),
            scalar("select count(*) from app_users",noParameters),imports.values().stream().mapToLong(Long::longValue).sum(),
            imports.getOrDefault("FAILED",0L)+imports.getOrDefault("VALIDATION_FAILED",0L),imports.getOrDefault("COMMITTING",0L),
            grouped("select coalesce(p.plan,'FREE') as metric_key,count(*) as total"+BUSINESS_FROM+" group by coalesce(p.plan,'FREE')","metric_key"),
            grouped("select coalesce(p.subscription_status,'TRIAL') as metric_key,count(*) as total"+BUSINESS_FROM+" group by coalesce(p.subscription_status,'TRIAL')","metric_key"));
    }

    public Map<String,Long> importStatusCounts() {
        return grouped("select status,count(*) as total from ("+IMPORTS+") i group by status", "status");
    }
    public long failuresSince(Instant since) {
        return scalar("select count(*) from ("+IMPORTS+") i where status in ('FAILED','VALIDATION_FAILED') and updated_at>=:since", new MapSqlParameterSource("since", java.sql.Timestamp.from(since)));
    }
    public Instant lastFailure() { return importInstant("max(updated_at)","status in ('FAILED','VALIDATION_FAILED')"); }
    public Instant oldestCommitting() { return importInstant("min(updated_at)","status='COMMITTING'"); }
    private Instant importInstant(String function, String where) {
        return jdbc.query("select "+function+" as recorded_at from ("+IMPORTS+") i where "+where, new MapSqlParameterSource(), rs -> rs.next() ? instant(rs,"recorded_at") : null);
    }
    public boolean databaseAvailable() { return scalar("select 1", new MapSqlParameterSource()) == 1; }

    private Map<String,Long> grouped(String sql, String key) {
        var result = new LinkedHashMap<String,Long>();
        jdbc.query(sql, new MapSqlParameterSource(), rs -> { result.put(rs.getString(key),rs.getLong("total")); });
        return result;
    }
    private long countTenant(String table, MapSqlParameterSource p) { return scalar("select count(*) from "+table+" where tenant_id=:tenant",p); }
    private long scalar(String sql, MapSqlParameterSource p) { var value=jdbc.queryForObject(sql,p,Long.class); return value == null ? 0 : value; }
    private static void filter(StringBuilder where, MapSqlParameterSource p, String column, String name, String value) {
        if(value!=null) { where.append(" and ").append(column).append("=:").append(name); p.addValue(name,value); }
    }
    private static MapSqlParameterSource pageParameters(int page, int size) { return new MapSqlParameterSource("limit",size).addValue("offset",(long)page*size); }
    private static String searchPattern(String value) { return "%"+value.toLowerCase(java.util.Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
    private static UUID uuid(ResultSet rs,String column) throws SQLException { return rs.getObject(column,UUID.class); }
    private static Instant instant(ResultSet rs,String column) throws SQLException { var ts=rs.getTimestamp(column); return ts==null ? null : ts.toInstant(); }
    private static PlatformDtos.BusinessSummary business(ResultSet rs) throws SQLException {
        return new PlatformDtos.BusinessSummary(uuid(rs,"id"),rs.getString("name"),rs.getString("business_mode"),rs.getString("status"),rs.getString("currency"),rs.getBoolean("gst_enabled"),instant(rs,"created_at"),rs.getString("plan"),rs.getString("subscription_status"),instant(rs,"subscription_ends_at"),rs.getLong("member_count"),rs.getLong("product_count"),rs.getLong("import_count"),instant(rs,"last_import_at"));
    }
    public static <T> PlatformDtos.Page<T> page(List<T> content,int page,int size,long total) { return new PlatformDtos.Page<>(content,page,size,total,(total+size-1)/size); }
}
