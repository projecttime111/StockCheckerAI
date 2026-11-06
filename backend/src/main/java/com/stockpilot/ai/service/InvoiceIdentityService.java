package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.PurchaseInvoice;
import com.stockpilot.ai.domain.SalesInvoice;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Own sales series is tenant/year scoped; supplier document series also includes supplier. */
@Service
public class InvoiceIdentityService {
    private final EntityManager entityManager;
    public InvoiceIdentityService(EntityManager entityManager) { this.entityManager = entityManager; }
    public static LocalDate fiscalYearStart(LocalDate date) {
        return LocalDate.of(date.getMonthValue() < 4 ? date.getYear() - 1 : date.getYear(), 4, 1);
    }
    public Optional<PurchaseInvoice> matchPurchase(UUID tenantId, UUID supplierId, String number, LocalDate date) {
        if (number == null || date == null) return Optional.empty();
        var start = fiscalYearStart(date);
        return entityManager.createQuery("select i from PurchaseInvoice i where i.tenantId = :tenantId "
                + "and ((:supplierId is null and i.supplierId is null) or i.supplierId = :supplierId) "
                + "and lower(trim(i.invoiceNumber)) = lower(:number) and i.invoiceDate >= :start and i.invoiceDate < :end", PurchaseInvoice.class)
            .setParameter("tenantId", tenantId).setParameter("supplierId", supplierId)
            .setParameter("number", number.trim()).setParameter("start", start).setParameter("end", start.plusYears(1))
            .getResultStream().findFirst();
    }
    public Optional<SalesInvoice> matchSale(UUID tenantId, String number, LocalDate date) {
        if (number == null || date == null) return Optional.empty();
        var start = fiscalYearStart(date);
        return entityManager.createQuery("select i from SalesInvoice i where i.tenantId = :tenantId "
                + "and lower(trim(i.invoiceNumber)) = lower(:number) and i.invoiceDate >= :start and i.invoiceDate < :end", SalesInvoice.class)
            .setParameter("tenantId", tenantId).setParameter("number", number.trim())
            .setParameter("start", start).setParameter("end", start.plusYears(1)).getResultStream().findFirst();
    }
}
