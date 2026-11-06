package com.stockpilot.ai.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyMath {
    private MoneyMath() {}
    public static BigDecimal amount(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
    /** Currency columns are numeric(14,2); reject overflow before the persistence layer. */
    public static BigDecimal storedAmount(BigDecimal value) {
        var rounded = amount(value);
        if (rounded.precision() > 14) {
            throw com.stockpilot.ai.exception.ApiErrors.badRequest("Amount exceeds the supported limit of 999999999999.99");
        }
        return rounded;
    }
}
