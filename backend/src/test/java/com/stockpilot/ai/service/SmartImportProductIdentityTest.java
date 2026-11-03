package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.SmartStagedProduct;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmartImportProductIdentityTest {
    @Test
    void stockMasterGuidIsAProductIdentity() {
        var row = new SmartStagedProduct();
        row.sourceType = DomainEnums.DetectedFileType.INVENTORY_MASTER;
        row.externalId = "stock-123";
        row.rawMetadataJson = Map.of("Source Entity", "STOCKITEM", "Tally GUID", "stock-123");
        assertThat(SmartImportProductIdentity.externalId(row)).isEqualTo("stock-123");
        assertThat(SmartImportProductIdentity.fromSource(row.sourceType, row.rawMetadataJson)).isEqualTo("stock-123");
    }

    @Test
    void parentVoucherGuidIsNeverAProductIdentityEvenInLegacyStaging() {
        for (var type : new DomainEnums.DetectedFileType[] { DomainEnums.DetectedFileType.SALES_VOUCHERS,
                DomainEnums.DetectedFileType.PURCHASE_VOUCHERS, DomainEnums.DetectedFileType.CREDIT_NOTES,
                DomainEnums.DetectedFileType.DEBIT_NOTES, DomainEnums.DetectedFileType.STOCK_JOURNAL }) {
            var row = new SmartStagedProduct();
            row.sourceType = type;
            row.externalId = "voucher-123";
            row.rawMetadataJson = Map.of("Source Entity", "VOUCHER", "Tally GUID", "voucher-123");
            assertThat(SmartImportProductIdentity.externalId(row)).isNull();
            assertThat(SmartImportProductIdentity.fromSource(type, row.rawMetadataJson)).isNull();
            assertThat(SmartImportProductIdentity.fromProductMetadata(row.rawMetadataJson)).isNull();
        }
    }

    @Test
    void explicitProductGuidIsKeptSeparateFromVoucherGuid() {
        var row = new SmartStagedProduct();
        row.sourceType = DomainEnums.DetectedFileType.SALES_VOUCHERS;
        row.externalId = "voucher-123";
        row.rawMetadataJson = Map.of("Source Entity", "VOUCHER", "Tally GUID", "voucher-123", "Stock Item GUID", "stock-456");
        assertThat(SmartImportProductIdentity.externalId(row)).isEqualTo("stock-456");
        assertThat(SmartImportProductIdentity.fromSource(row.sourceType, row.rawMetadataJson)).isEqualTo("stock-456");
    }
}
