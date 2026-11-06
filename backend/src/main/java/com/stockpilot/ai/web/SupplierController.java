package com.stockpilot.ai.web;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.Supplier;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.service.OutstandingService;
import com.stockpilot.ai.web.dto.ApiDtos;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {
    private final Repositories.SupplierRepository suppliers;
    private final OutstandingService outstanding;

    public SupplierController(Repositories.SupplierRepository suppliers, OutstandingService outstanding) {
        this.suppliers = suppliers;
        this.outstanding = outstanding;
    }

    @GetMapping
    @PreAuthorize("@permissionService.has('suppliers.view')")
    public Page<ApiDtos.SupplierResponse> list(@RequestParam(required = false) String query, Pageable pageable) {
        return (query == null || query.isBlank() ? suppliers.findByTenantId(TenantContext.tenantId(), pageable)
            : suppliers.findByTenantIdAndNameContainingIgnoreCase(TenantContext.tenantId(), query.trim(), pageable)).map(this::response);
    }

    @PostMapping
    @PreAuthorize("@permissionService.has('suppliers.create')")
    public ApiDtos.SupplierResponse create(@Valid @RequestBody ApiDtos.PartyRequest request) {
        var supplier = new Supplier();
        supplier.tenantId = TenantContext.tenantId();
        supplier.name = request.name();
        supplier.phone = request.phone();
        supplier.email = request.email();
        supplier.gstin = request.gstin();
        supplier.creditDays = request.creditDays() == null ? 0 : request.creditDays();
        return response(suppliers.save(supplier));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@permissionService.has('suppliers.view')")
    public ApiDtos.SupplierResponse get(@PathVariable UUID id) {
        return response(suppliers.findByTenantIdAndId(TenantContext.tenantId(), id).orElseThrow(() -> ApiErrors.notFound("Supplier not found")));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@permissionService.has('suppliers.update')")
    public ApiDtos.SupplierResponse update(@PathVariable UUID id, @Valid @RequestBody ApiDtos.PartyRequest request) {
        var supplier = suppliers.findByTenantIdAndId(TenantContext.tenantId(), id).orElseThrow(() -> ApiErrors.notFound("Supplier not found"));
        supplier.name = request.name();
        supplier.phone = request.phone();
        supplier.email = request.email();
        supplier.gstin = request.gstin();
        supplier.creditDays = request.creditDays() == null ? supplier.creditDays : request.creditDays();
        return response(suppliers.save(supplier));
    }

    private ApiDtos.SupplierResponse response(Supplier supplier) {
        return new ApiDtos.SupplierResponse(supplier.id, supplier.name, supplier.phone, supplier.email, supplier.gstin,
            supplier.creditDays, outstanding.supplierPayable(TenantContext.tenantId(), supplier.id));
    }
}
