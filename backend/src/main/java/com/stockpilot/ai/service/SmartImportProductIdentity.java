package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.SmartStagedProduct;

import java.util.Locale;
import java.util.Map;

final class SmartImportProductIdentity {
    private SmartImportProductIdentity() { }

    static String externalId(SmartStagedProduct row) {
        var explicit = explicitProductId(row.rawMetadataJson);
        if (explicit != null) return explicit;
        // Older staged voucher rows copied their parent voucher GUID into externalId.
        // Only inventory masters can use an unqualified GUID as a product identity.
        return row.sourceType == DomainEnums.DetectedFileType.INVENTORY_MASTER && !voucherMetadata(row.rawMetadataJson)
            ? nonBlank(row.externalId) : null;
    }

    static String fromSource(DomainEnums.DetectedFileType sourceType, Map<String, ?> metadata) {
        var explicit = explicitProductId(metadata);
        if (explicit != null) return explicit;
        return sourceType == DomainEnums.DetectedFileType.INVENTORY_MASTER ? fromProductMetadata(metadata) : null;
    }

    static String fromProductMetadata(Map<String, ?> metadata) {
        var explicit = explicitProductId(metadata);
        if (explicit != null) return explicit;
        if (voucherMetadata(metadata)) return null;
        return value(metadata, "Tally GUID", "GUID", "MASTERID", "Master ID", "Tally Master ID");
    }

    private static String explicitProductId(Map<String, ?> metadata) {
        return value(metadata, "Stock Item GUID", "Product GUID", "STOCKITEMGUID", "Product External ID");
    }

    private static boolean voucherMetadata(Map<String, ?> metadata) {
        var entity = value(metadata, "Source Entity");
        return (entity != null && entity.toUpperCase(Locale.ROOT).startsWith("VOUCHER"))
            || value(metadata, "Voucher Type", "VCHTYPE", "VOUCHERTYPENAME") != null;
    }

    private static String value(Map<String, ?> metadata, String... keys) {
        if (metadata == null) return null;
        for (var key : keys) {
            for (var entry : metadata.entrySet()) {
                if (key.equalsIgnoreCase(entry.getKey())) {
                    var text = nonBlank(entry.getValue() == null ? null : entry.getValue().toString());
                    if (text != null) return text;
                }
            }
        }
        return null;
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
