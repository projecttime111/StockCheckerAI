package com.stockpilot.ai.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class ForecastMath {
    private ForecastMath() {
    }

    public static java.time.LocalDate stockoutDate(BigDecimal currentStock, BigDecimal dailyDemand, java.time.LocalDate today) {
        if (currentStock.signum() <= 0) return today;
        if (dailyDemand.signum() <= 0) return null;
        return today.plusDays(currentStock.divide(dailyDemand, 0, RoundingMode.CEILING).longValue());
    }

    public static BigDecimal averageDailyDemand(List<BigDecimal> dailySales) {
        if (dailySales == null || dailySales.isEmpty()) {
            return BigDecimal.ZERO;
        }
        var total = dailySales.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(dailySales.size()), 3, RoundingMode.HALF_UP);
    }

    public static BigDecimal weightedDailyDemand(List<BigDecimal> dailySales) {
        if (dailySales == null || dailySales.isEmpty()) {
            return BigDecimal.ZERO;
        }
        var size = dailySales.size();
        var recent = dailySales.subList(Math.max(0, size - 30), size);
        var middle = dailySales.subList(Math.max(0, size - 60), Math.max(0, size - 30));
        var older = dailySales.subList(0, Math.max(0, size - 60));
        var recentAvg = averageDailyDemand(recent);
        var middleAvg = averageDailyDemand(middle);
        var olderAvg = averageDailyDemand(older);
        return recentAvg.multiply(BigDecimal.valueOf(0.6))
            .add(middleAvg.multiply(BigDecimal.valueOf(0.3)))
            .add(olderAvg.multiply(BigDecimal.valueOf(0.1)))
            .setScale(3, RoundingMode.HALF_UP);
    }

    public static BigDecimal reorderPoint(BigDecimal dailyDemand, int leadTimeDays, BigDecimal safetyStock) {
        return dailyDemand.multiply(BigDecimal.valueOf(Math.max(0, leadTimeDays)))
            .add(safetyStock == null ? BigDecimal.ZERO : safetyStock)
            .setScale(3, RoundingMode.HALF_UP);
    }

    public static BigDecimal suggestedQuantity(BigDecimal reorderPoint, BigDecimal next30DaysDemand, BigDecimal currentStock, BigDecimal minimumOrderQuantity) {
        var needed = reorderPoint.add(next30DaysDemand).subtract(currentStock).max(BigDecimal.ZERO);
        var moq = minimumOrderQuantity == null ? BigDecimal.ZERO : minimumOrderQuantity;
        if (needed.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return needed.max(moq).setScale(3, RoundingMode.HALF_UP);
    }
}
