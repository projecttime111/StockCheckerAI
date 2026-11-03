package com.stockpilot.ai.web;

import com.stockpilot.ai.service.RetailCounterSaleService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RetailCounterSaleController {
    private final RetailCounterSaleService sales;

    public RetailCounterSaleController(RetailCounterSaleService sales) {
        this.sales = sales;
    }

    @GetMapping("/api/sales/retail-counter")
    @PreAuthorize("@permissionService.has('sales.view')")
    public Page<RetailCounterSaleService.SaleResponse> list(Pageable pageable) {
        return sales.list(pageable);
    }
}
