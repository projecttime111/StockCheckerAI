package com.stockpilot.ai.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Limits of final business fields, checked before values reach narrower destination tables. */
final class ImportFieldBounds {
    private ImportFieldBounds() { }
    record Violation(String field, String message) { }
    static List<Violation> validate(Map<String, ?> source) {
        var result = new ArrayList<Violation>();
        for (var entry : source.entrySet()) {
            if (entry.getValue() == null) continue;
            var field = entry.getKey();
            var key = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            var value = entry.getValue().toString().trim();
            int length = switch (key) {
                case "itemname", "productname", "stockitem", "stockitemname", "rawname", "normalizedname", "name" -> 220;
                case "partyname", "customer", "supplier", "ledgername" -> 180;
                case "sku", "stockkeepingunit", "itemcode", "invoicenumber", "invoiceno", "vouchernumber", "voucherno" -> 80;
                case "unit", "unitcode", "baseunit", "baseunits" -> 24;
                case "hsn", "hsncode" -> 32;
                case "barcode", "ean", "upc" -> 120;
                case "category", "stockgroup", "brand", "warehouse", "warehousename", "godown", "godownname" -> 160;
                default -> 0;
            };
            if (length > 0 && value.length() > length) result.add(new Violation(field, field + " exceeds " + length + " characters"));
            int scale = switch (key) {
                case "quantity", "qty", "billedquantity", "actualquantity", "openingstock", "closingstock" -> 3;
                case "rate", "unitprice", "purchaseprice", "salesprice", "amount", "lineamount", "totalamount", "openingbalance", "outstanding", "balance", "taxamount", "discountamount", "freightamount", "otherchargesamount" -> 2;
                default -> -1;
            };
            if (scale < 0 || value.isBlank()) continue;
            try {
                var cleaned = value.replace(",", "").replaceAll("[^0-9.\\-]", "");
                if (cleaned.isBlank()) continue; // Other validators report unparseable values.
                var numeric = new BigDecimal(cleaned);
                if (numeric.setScale(scale, RoundingMode.HALF_UP).abs().compareTo(BigDecimal.TEN.pow(14 - scale)) >= 0)
                    result.add(new Violation(field, field + " exceeds the supported numeric range"));
                if (scale == 3 && numeric.stripTrailingZeros().scale() > 3)
                    result.add(new Violation(field, field + " supports at most 3 decimal places"));
            } catch (NumberFormatException ignored) { }
        }
        var normalized = new java.util.HashMap<String, String>();
        source.forEach((key, value) -> { if (value != null) normalized.put(key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""), value.toString()); });
        if (normalized.containsKey("vouchernumber") || normalized.containsKey("invoicenumber")) {
            var quantity = number(normalized, "billedquantity", "actualquantity", "quantity", "qty");
            var rate = number(normalized, "rate", "parsedrate", "unitprice", "price");
            if (quantity != null && rate != null && quantity.multiply(rate).abs().setScale(2, RoundingMode.HALF_UP)
                .compareTo(BigDecimal.TEN.pow(12)) >= 0)
                result.add(new Violation("Line Amount", "Quantity multiplied by rate exceeds the supported invoice amount range"));
        }
        return result;
    }
    private static BigDecimal number(Map<String, String> values, String... keys) {
        for (var key : keys) {
            var raw = values.get(key);
            if (raw == null || raw.isBlank()) continue;
            try { return new BigDecimal(raw.replace(",", "").replaceAll("[^0-9.\\-]", "")); }
            catch (NumberFormatException ignored) { }
        }
        return null;
    }

}
