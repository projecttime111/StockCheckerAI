package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.ForecastResult;
import com.stockpilot.ai.domain.ReorderSuggestion;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Service
public class ForecastService {
    private final Repositories.ProductRepository products;
    private final Repositories.ForecastResultRepository forecasts;
    private final Repositories.ReorderSuggestionRepository suggestions;
    private final StockLedgerService stockLedger;
    private final SalesHistoryService salesHistory;
    private final TenantWriteLockService locks;

    public ForecastService(
        Repositories.ProductRepository products,
        Repositories.ForecastResultRepository forecasts,
        Repositories.ReorderSuggestionRepository suggestions,
        StockLedgerService stockLedger, SalesHistoryService salesHistory, TenantWriteLockService locks
    ) {
        this.products = products;
        this.forecasts = forecasts;
        this.suggestions = suggestions;
        this.stockLedger = stockLedger;
        this.salesHistory = salesHistory;
        this.locks = locks;
    }

    @Transactional
    public List<ApiDtos.ForecastResponse> run() {
        var tenantId = TenantContext.tenantId();
        // Serialize complete generations, including the first run when no forecast rows exist to lock.
        locks.lock(tenantId);
        forecasts.deleteByTenantId(tenantId);
        suggestions.deleteByTenantId(tenantId);
        forecasts.flush();
        suggestions.flush();
        var today = LocalDate.now(ZoneOffset.UTC);
        var since = today.minusDays(89);
        var history = salesHistory.history(tenantId);

        var responses = new ArrayList<ApiDtos.ForecastResponse>();
        for (var product : products.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)) {
            var daily = history.daily(product.id, null, since, 90);
            var avg = ForecastMath.averageDailyDemand(daily);
            var weighted = ForecastMath.weightedDailyDemand(daily);
            var demand = weighted.compareTo(BigDecimal.ZERO) > 0 ? weighted : avg;
            var current = stockLedger.currentStock(tenantId, product.id, null);
            var next7 = demand.multiply(BigDecimal.valueOf(7)).setScale(3, java.math.RoundingMode.HALF_UP);
            var next30 = demand.multiply(BigDecimal.valueOf(30)).setScale(3, java.math.RoundingMode.HALF_UP);
            var stockout = ForecastMath.stockoutDate(current, demand, today);
            var forecast = new ForecastResult();
            forecast.tenantId = tenantId;
            forecast.productId = product.id;
            forecast.averageDailyDemand = avg;
            forecast.weightedDailyDemand = weighted;
            forecast.next7DaysDemand = next7;
            forecast.next30DaysDemand = next30;
            forecast.currentStock = current;
            forecast.stockoutDate = stockout;
            forecast.generatedAt = Instant.now();
            forecasts.save(forecast);

            var reorderPoint = ForecastMath.reorderPoint(demand, product.leadTimeDays, product.safetyStock);
            var suggested = ForecastMath.suggestedQuantity(reorderPoint, next30, current, product.minimumOrderQuantity);
            if (current.compareTo(reorderPoint) <= 0 || suggested.compareTo(BigDecimal.ZERO) > 0) {
                var suggestion = new ReorderSuggestion();
                suggestion.tenantId = tenantId;
                suggestion.productId = product.id;
                suggestion.reorderPoint = reorderPoint;
                suggestion.suggestedQuantity = suggested;
                suggestion.reason = "Demand forecast uses last 90 days sales. Current stock " + current + ", reorder point " + reorderPoint + ".";
                suggestion.generatedAt = Instant.now();
                suggestions.save(suggestion);
            }
            responses.add(new ApiDtos.ForecastResponse(product.id, product.name, current, avg, next7, next30, stockout, history.lastSale(product.id, null, today) != null));
        }
        return responses;
    }

    public List<ApiDtos.ForecastResponse> results() {
        var tenantId = TenantContext.tenantId();
        var history = salesHistory.history(tenantId);
        var today = LocalDate.now(ZoneOffset.UTC);
        return forecasts.findByTenantIdOrderByGeneratedAtDesc(tenantId).stream()
            .map(result -> {
                var product = products.findByTenantIdAndId(tenantId, result.productId).orElse(null);
                return new ApiDtos.ForecastResponse(result.productId, product == null ? "Unknown product" : product.name, result.currentStock, result.averageDailyDemand, result.next7DaysDemand, result.next30DaysDemand, result.stockoutDate, history.lastSale(result.productId, null, today) != null);
            })
            .toList();
    }

    public List<ApiDtos.ReorderSuggestionResponse> suggestions() {
        var tenantId = TenantContext.tenantId();
        return suggestions.findByTenantIdOrderByGeneratedAtDesc(tenantId).stream()
            .map(suggestion -> {
                var product = products.findByTenantIdAndId(tenantId, suggestion.productId).orElse(null);
                return new ApiDtos.ReorderSuggestionResponse(suggestion.productId, product == null ? "Unknown product" : product.name, suggestion.reorderPoint, suggestion.suggestedQuantity, suggestion.reason, suggestion.status);
            })
            .toList();
    }
}
