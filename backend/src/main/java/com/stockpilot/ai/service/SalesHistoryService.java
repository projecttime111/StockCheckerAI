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

    public SalesHistoryService(Repositories.SalesInvoiceRepository invoices,
        Repositories.SalesInvoiceItemRepository items,
        AnalyticsRepository analytics, StockLedgerService stock) {
        this.invoices = invoices; this.items = items;
        this.analytics = analytics; this.stock = stock;
    }

    @Transactional(readOnly = true)
    public History history(UUID tenantId) {
        var invoiceById = new HashMap<UUID, SalesInvoice>();
        invoices.findByTenantId(tenantId).forEach(invoice -> invoiceById.put(invoice.id, invoice));
        var productById = new HashMap<UUID, Product>();
        analytics.findProductsByTenantId(tenantId).forEach(product -> productById.put(product.id, product));
        var multiplierByUnit = new HashMap<ProductUnit, BigDecimal>();
        var represented = new HashSet<DocumentProduct>();
        var sales = new ArrayList<Sale>();
        for (var item : items.findByTenantId(tenantId)) {
            var invoice = invoiceById.get(item.salesInvoiceId);
            if (invoice == null || invoice.invoiceDate == null) continue;
            var product = productById.get(item.productId);
            if (product == null) continue;
            var multiplier = multiplierByUnit.computeIfAbsent(new ProductUnit(product.id, item.unitId),
                key -> stock.toBaseQuantity(tenantId, product, item.unitId, BigDecimal.ONE));
            var quantity = item.quantity.multiply(multiplier).abs();
            represented.add(new DocumentProduct(invoice.id, product.id));
            sales.add(new Sale(product.id, invoice.warehouseId, invoice.invoiceDate, quantity));
        }
        for (var movement : analytics.findMovementsByTenantIdAndMovementType(tenantId, DomainEnums.MovementType.SALE)) {
            if ("SALES_INVOICE".equals(movement.referenceType)
                && represented.contains(new DocumentProduct(movement.referenceId, movement.productId))) continue;
            sales.add(new Sale(movement.productId, movement.warehouseId,
                movement.movementDate.atZone(ZoneOffset.UTC).toLocalDate(), movement.baseQuantity.abs()));
        }
        return new History(List.copyOf(sales));
    }

    private record ProductUnit(UUID productId, UUID unitId) { }
    private record DocumentProduct(UUID documentId, UUID productId) { }
    public record Sale(UUID productId, UUID warehouseId, LocalDate date, BigDecimal quantity) { }
    public static final class History {
        private final List<Sale> sales;
        private final Map<UUID, List<Sale>> byProduct = new HashMap<>();
        public History(List<Sale> sales) {
            this.sales = List.copyOf(sales);
            for (var sale : sales) byProduct.computeIfAbsent(sale.productId, ignored -> new ArrayList<>()).add(sale);
        }
        public List<Sale> sales() { return sales; }
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
