package com.stockpilot.ai.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReorderAllocationTest {
    @Test void unassignedDemandIsAllocatedExactlyOnceIncludingExcessAndNegativeStock() {
        var stock = new LinkedHashMap<UUID, BigDecimal>();
        var a = UUID.randomUUID(); var b = UUID.randomUUID(); var c = UUID.randomUUID();
        stock.put(a, new BigDecimal("-2")); stock.put(b, new BigDecimal("5")); stock.put(c, new BigDecimal("8"));
        for (var amount : List.of("0", "3", "13", "20", "2.125")) {
            var result = ReorderEngineService.allocateUnassignedSales(new BigDecimal(amount), stock);
            assertThat(result.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(amount);
            assertThat(result.values()).allMatch(value -> value.signum() >= 0);
        }
        var result = ReorderEngineService.allocateUnassignedSales(new BigDecimal("7"), stock);
        assertThat(result.get(a)).isZero(); assertThat(result.get(b)).isEqualByComparingTo("5"); assertThat(result.get(c)).isEqualByComparingTo("2");
    }
}
