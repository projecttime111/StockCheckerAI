package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.repo.Repositories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:demo-seed-regression;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DemoDataSeederIntegrationTest {
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Repositories.TenantRepository tenants;
    @AfterEach void clearContext() { TenantContext.clear(); }

    @Test void completeDemoHistorySeedsWithNonnegativeStockAndRestartDoesNotDuplicateIt() {
        // The seeder remains disabled under the test profile. Explicit construction
        // exercises its real repositories and ledger without enabling it in other tests.
        var seeder=context.getAutowireCapableBeanFactory().createBean(DemoDataSeeder.class);
        var tx=new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status->seeder.run());
        var tenant=jdbc.queryForObject("select tenant_id from user_tenant_memberships m join app_users u on u.id=m.user_id where u.email='owner@demo.com'",UUID.class);
        assertThat(tenants.findById(tenant).orElseThrow().allowNegativeStock).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from sales_invoices where tenant_id=?",Long.class,tenant)).isEqualTo(92);
        assertThat(jdbc.queryForObject("select count(*) from purchase_invoices where tenant_id=?",Long.class,tenant)).isEqualTo(6);
        var minimum=jdbc.queryForObject("""
            select min(balance) from (
                select sum(base_quantity) over (
                    partition by product_id,warehouse_id
                    order by movement_date,created_at,id rows unbounded preceding
                ) as balance from stock_movements where tenant_id=?
            ) history
            """,BigDecimal.class,tenant);
        assertThat(minimum).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("""
            select sum(m.base_quantity) from stock_movements m
            join products p on p.id=m.product_id join warehouses w on w.id=m.warehouse_id
            where m.tenant_id=? and p.sku='MAGGI70' and w.name='Retail Counter'
            """,BigDecimal.class,tenant)).isEqualByComparingTo("60");
        var movements=jdbc.queryForObject("select count(*) from stock_movements where tenant_id=?",Long.class,tenant);
        tx.executeWithoutResult(status->seeder.run());
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where tenant_id=?",Long.class,tenant)).isEqualTo(movements);
        assertThat(tenants.findById(tenant).orElseThrow().allowNegativeStock).isFalse();
        assertThat(TenantContext.tenantIdOrNull()).isNull();
    }
}
