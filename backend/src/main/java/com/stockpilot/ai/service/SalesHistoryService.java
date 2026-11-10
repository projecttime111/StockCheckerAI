package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.repo.AnalyticsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** Sales demand is document history; stock snapshots must never manufacture sales movements. */
@Service
public class SalesHistoryService {
    private final Repositories.SalesInvoiceRepository invoices;
    private final Repositories.SalesInvoiceItemRepository items;
    private final AnalyticsRepository analytics;
    private final StockLedgerService stock;
    private final com.stockpilot.ai.repo.AnalyticsCoverageRepository coverage;
    private final jakarta.persistence.EntityManager em;

    public SalesHistoryService(Repositories.SalesInvoiceRepository invoices,
        Repositories.SalesInvoiceItemRepository items,
        AnalyticsRepository analytics, StockLedgerService stock, com.stockpilot.ai.repo.AnalyticsCoverageRepository coverage, jakarta.persistence.EntityManager em) {
        this.invoices = invoices; this.items = items;
        this.analytics = analytics; this.stock = stock; this.coverage=coverage; this.em=em;
    }

    @Transactional(readOnly = true)
    public History history(UUID tenantId) {
        return history(tenantId, LocalDate.of(1900,1,1), LocalDate.now(ZoneOffset.UTC));
    }

    /** Bound demand queries to the confirmed period, including historical uploads. */
    @Transactional(readOnly = true)
    public History demandHistory(UUID tenantId, LocalDate today) {
        var period = coverage.findByTenantId(tenantId).orElse(null);
        var end = period != null && period.complete && period.throughDate.isBefore(today) ? period.throughDate : today;
        return history(tenantId, end.minusDays(365), end);
    }

    @Transactional(readOnly = true)
    public History history(UUID tenantId, LocalDate from, LocalDate through) {
        var invoiceById = new HashMap<UUID, SalesInvoice>();
        em.createQuery("select i from SalesInvoice i where i.tenantId=:tenant and i.invoiceDate between :from and :through",SalesInvoice.class)
            .setParameter("tenant",tenantId).setParameter("from",from).setParameter("through",through).getResultList().stream()
            .filter(i->!"CANCELLED".equals(i.financialMetadata.get("sourceDocumentState"))).forEach(invoice -> invoiceById.put(invoice.id, invoice));
        var productById = new HashMap<UUID, Product>();
        analytics.findProductsByTenantId(tenantId).forEach(product -> productById.put(product.id, product));
        var multiplierByUnit = new HashMap<ProductUnit, BigDecimal>();
        var represented = new HashSet<DocumentProduct>();
        var representedItems = new HashSet<UUID>();
        var sales = new ArrayList<Sale>();
        for (var item : em.createQuery("select l from SalesInvoiceItem l join SalesInvoice i on l.salesInvoiceId=i.id and l.tenantId=i.tenantId where l.tenantId=:tenant and i.invoiceDate between :from and :through",com.stockpilot.ai.domain.SalesInvoiceItem.class)
            .setParameter("tenant",tenantId).setParameter("from",from).setParameter("through",through).getResultList()) {
            var invoice = invoiceById.get(item.salesInvoiceId);
            if (invoice == null || invoice.invoiceDate == null) continue;
            var product = productById.get(item.productId);
            if (product == null) continue;
            var multiplier = multiplierByUnit.computeIfAbsent(new ProductUnit(product.id, item.unitId),
                key -> stock.toBaseQuantity(tenantId, product, item.unitId, BigDecimal.ONE));
            var quantity = item.quantity.multiply(multiplier).abs();
            represented.add(new DocumentProduct(invoice.id, product.id));
            representedItems.add(item.id);
            sales.add(new Sale(product.id, item.warehouseId == null ? invoice.warehouseId : item.warehouseId, invoice.invoiceDate, quantity));
        }
        for (var movement : em.createQuery("select m from StockMovement m where m.tenantId=:tenant and m.movementType=:type and m.movementDate>=:from and m.movementDate<:through and (m.decisionReason is null or m.decisionReason<>'SOURCE_REVISION_SUPERSEDED')",com.stockpilot.ai.domain.StockMovement.class)
            .setParameter("tenant",tenantId).setParameter("type",DomainEnums.MovementType.SALE).setParameter("from",from.atStartOfDay().toInstant(ZoneOffset.UTC)).setParameter("through",through.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)).getResultList()) {
            if ("SALES_INVOICE".equals(movement.referenceType)
                && represented.contains(new DocumentProduct(movement.referenceId, movement.productId))) continue;
            if ("SALES_INVOICE_ITEM".equals(movement.referenceType) && representedItems.contains(movement.referenceId)) continue;
            sales.add(new Sale(movement.productId, movement.warehouseId,
                movement.movementDate.atZone(ZoneOffset.UTC).toLocalDate(), movement.baseQuantity.abs()));
        }
        return new History(List.copyOf(sales),coverage.findByTenantId(tenantId).orElse(null));
    }

    private record ProductUnit(UUID productId, UUID unitId) { }
    private record DocumentProduct(UUID documentId, UUID productId) { }
    public record Sale(UUID productId, UUID warehouseId, LocalDate date, BigDecimal quantity) { }
    public static final class History {
        private final List<Sale> sales;
        private final com.stockpilot.ai.domain.AnalyticsCoverage coverage;
        private final Map<UUID, List<Sale>> byProduct = new HashMap<>();
        public History(List<Sale> sales) { this(sales,null); }
        public History(List<Sale> sales,com.stockpilot.ai.domain.AnalyticsCoverage coverage) {
            this.coverage=coverage;
            this.sales = List.copyOf(sales);
            for (var sale : sales) byProduct.computeIfAbsent(sale.productId, ignored -> new ArrayList<>()).add(sale);
        }
        public List<Sale> sales() { return sales; }
        public boolean coverageConfirmed(){return coverage!=null&&coverage.complete;}
        public LocalDate windowEnd(LocalDate today){return coverageConfirmed()&&coverage.throughDate.isBefore(today)?coverage.throughDate:today;}
        public LocalDate windowStart(LocalDate today,int days){
            var end=windowEnd(today);var earliest=end.minusDays(days-1L);
            var start=coverageConfirmed()?coverage.fromDate:sales.stream().map(Sale::date).filter(d->!d.isAfter(end)).min(LocalDate::compareTo).orElse(end);
            return start.isAfter(earliest)?(start.isAfter(end)?end:start):earliest;
        }
        public int observationDays(LocalDate today,int days){return (int)java.time.temporal.ChronoUnit.DAYS.between(windowStart(today,days),windowEnd(today))+1;}
        private List<Sale> forProduct(UUID productId) { return byProduct.getOrDefault(productId, List.of()); }
        public BigDecimal quantity(UUID productId, UUID warehouseId, LocalDate from, LocalDate through) {
            return forProduct(productId).stream()
                .filter(s -> warehouseId == null || warehouseId.equals(s.warehouseId))
                .filter(s -> !s.date.isBefore(from) && !s.date.isAfter(through))
                .map(Sale::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        public LocalDate lastSale(UUID productId, UUID warehouseId, LocalDate through) {
            return forProduct(productId).stream()
                .filter(s -> warehouseId == null || warehouseId.equals(s.warehouseId))
                .filter(s -> s.quantity.signum() > 0 && !s.date.isAfter(through))
                .map(Sale::date).max(Comparator.naturalOrder()).orElse(null);
        }
        public List<BigDecimal> daily(UUID productId, UUID warehouseId, LocalDate from, int days) {
            var totals = new HashMap<LocalDate, BigDecimal>();
            forProduct(productId).stream()
                .filter(s -> warehouseId == null || warehouseId.equals(s.warehouseId))
                .forEach(s -> totals.merge(s.date, s.quantity, BigDecimal::add));
            var result = new ArrayList<BigDecimal>(days);
            for (int i = 0; i < days; i++) result.add(totals.getOrDefault(from.plusDays(i), BigDecimal.ZERO));
            return result;
        }
    }
}
