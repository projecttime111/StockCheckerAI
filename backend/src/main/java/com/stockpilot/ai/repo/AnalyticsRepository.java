package com.stockpilot.ai.repo;

import com.stockpilot.ai.domain.*;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface AnalyticsRepository extends Repository<Tenant, UUID> {
    @Query("select p from Product p where p.tenantId = :tenantId")
    List<Product> findProductsByTenantId(@Param("tenantId") UUID tenantId);

    @Query("select m from StockMovement m where m.tenantId = :tenantId and m.movementType = :movementType")
    List<StockMovement> findMovementsByTenantIdAndMovementType(@Param("tenantId") UUID tenantId, @Param("movementType") DomainEnums.MovementType movementType);

    @Query("select i from PurchaseOrderItem i, PurchaseOrder o where i.tenantId = :tenantId and o.tenantId = :tenantId and i.purchaseOrderId = o.id and o.status in ('OPEN', 'DRAFT')")
    List<PurchaseOrderItem> findPendingPurchasesByTenantId(@Param("tenantId") UUID tenantId);

    @Query("select i from SalesOrderItem i, SalesOrder o where i.tenantId = :tenantId and o.tenantId = :tenantId and i.salesOrderId = o.id and o.status in ('OPEN', 'DRAFT')")
    List<SalesOrderItem> findPendingSalesByTenantId(@Param("tenantId") UUID tenantId);
}
