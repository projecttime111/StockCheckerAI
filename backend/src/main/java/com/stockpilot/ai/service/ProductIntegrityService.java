package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.Product;
import com.stockpilot.ai.domain.TenantOwnedEntity;
import com.stockpilot.ai.domain.UnitConversion;
import com.stockpilot.ai.exception.ApiErrors;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductIntegrityService {
    private final EntityManager entityManager;
    public ProductIntegrityService(EntityManager entityManager) { this.entityManager = entityManager; }

    public void assertBaseUnitChangeAllowed(Product product, UUID newUnitId) {
        if (product.id == null || Objects.equals(product.baseUnitId, newUnitId)) return;
        assertNoOtherReferences(product, Set.of("ProductBarcode", "ProductTaxInfo"),
            "Cannot change the base unit of a product with stock, documents, conversions or import history");
    }

    public void prepareMerge(Product source, Product target) {
        if (!source.active || !target.active) throw ApiErrors.conflict("Only active products can be merged");
        if (source.baseUnitId == null || !Objects.equals(source.baseUnitId, target.baseUnitId)) {
            throw ApiErrors.conflict("Products must have the same base unit before merging");
        }
        // Imported records retain source fingerprints and replay history. Do not rewrite those identities
        // or leave pending orders/batches referring to an inactive product during a simple master merge.
        assertNoOtherReferences(source, Set.of("StockMovement", "SalesInvoiceItem", "PurchaseInvoiceItem",
            "ProductBarcode", "UnitConversion", "ForecastResult", "ReorderSuggestion", "DeadStockInsight"),
            "Cannot merge a product linked to imports, orders, batches or other dependent records; resolve those links first");
        var sourceConversions = entityManager.createQuery("select c from UnitConversion c where c.tenantId = :tenantId and c.productId = :productId", UnitConversion.class)
            .setParameter("tenantId", source.tenantId).setParameter("productId", source.id).getResultList();
        for (var conversion : sourceConversions) {
            var targetConversions = entityManager.createQuery("select c from UnitConversion c where c.tenantId = :tenantId and c.productId = :productId and c.fromUnitId = :fromUnit and c.toUnitId = :toUnit", UnitConversion.class)
                .setParameter("tenantId", source.tenantId).setParameter("productId", target.id)
                .setParameter("fromUnit", conversion.fromUnitId).setParameter("toUnit", conversion.toUnitId).getResultList();
            if (targetConversions.stream().anyMatch(c -> c.multiplier.compareTo(conversion.multiplier) != 0)) {
                throw ApiErrors.conflict("Products have conflicting unit conversions");
            }
            if (targetConversions.isEmpty()) {
                var copy = new UnitConversion();
                copy.tenantId = source.tenantId; copy.productId = target.id;
                copy.fromUnitId = conversion.fromUnitId; copy.toUnitId = conversion.toUnitId; copy.multiplier = conversion.multiplier;
                entityManager.persist(copy);
            }
        }
        for (var type : Set.of("ForecastResult", "ReorderSuggestion", "DeadStockInsight")) {
            entityManager.createQuery("delete from " + type + " e where e.tenantId = :tenantId and (e.productId = :sourceId or e.productId = :targetId)")
                .setParameter("tenantId", source.tenantId).setParameter("sourceId", source.id).setParameter("targetId", target.id).executeUpdate();
        }
    }

    private void assertNoOtherReferences(Product product, Set<String> allowed, String message) {
        for (var entity : entityManager.getMetamodel().getEntities()) {
            if (!TenantOwnedEntity.class.isAssignableFrom(entity.getJavaType()) || allowed.contains(entity.getName())
                || entity.getName().equals("AuditLog")) continue;
            for (var field : Set.of("productId", "matchedProductId", "localEntityId", "entityId")) {
                if (entity.getAttributes().stream().noneMatch(a -> a.getName().equals(field) && a.getJavaType().equals(UUID.class))) continue;
                var count = entityManager.createQuery("select count(e) from " + entity.getName()
                    + " e where e.tenantId = :tenantId and e." + field + " = :productId", Long.class)
                    .setParameter("tenantId", product.tenantId).setParameter("productId", product.id).getSingleResult();
                if (count > 0) throw ApiErrors.conflict(message);
            }
        }
    }
}
