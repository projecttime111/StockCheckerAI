package com.stockpilot.ai.web;

import com.stockpilot.ai.service.StockLedgerService;
import com.stockpilot.ai.web.dto.ApiDtos;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/stock")
public class StockController {
    private final StockLedgerService stock;

    public StockController(StockLedgerService stock) {
        this.stock = stock;
    }

    @GetMapping("/current")
    @PreAuthorize("@permissionService.has('stock.view')")
    public List<ApiDtos.StockResponse> current(@RequestParam(required = false) UUID productId, @RequestParam(required = false) UUID warehouseId) {
        return stock.currentStockReport(productId, warehouseId);
    }

    @GetMapping("/product/{productId}")
    @PreAuthorize("@permissionService.has('stock.view')")
    public List<ApiDtos.MovementResponse> product(@PathVariable UUID productId) {
        return stock.productMovements(productId);
    }

    @PostMapping("/adjustment")
    @PreAuthorize("@permissionService.has('stock.adjust')")
    public ApiDtos.MovementResponse adjustment(@Valid @RequestBody ApiDtos.StockAdjustmentRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return stock.adjust(request, key);
    }

    @PostMapping("/transfer")
    @PreAuthorize("@permissionService.has('stock.transfer')")
    public List<ApiDtos.MovementResponse> transfer(@Valid @RequestBody ApiDtos.StockTransferRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return stock.transfer(request, key);
    }

    @GetMapping("/movements")
    @PreAuthorize("@permissionService.has('stock.view')")
    public Page<ApiDtos.MovementResponse> movements(Pageable pageable) {
        return stock.movementPage(pageable);
    }
}
