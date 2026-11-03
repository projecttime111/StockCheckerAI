package com.stockpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.domain.DomainEnums;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ImportFileClassifierServiceTest {
    private final ImportFileClassifierService classifier = new ImportFileClassifierService(new ObjectMapper());

    @Test
    void classifiesTallyStockSnapshotAndExtractsCompanyAndDateRange() throws Exception {
        var result = classify("closing_stock_full_company.xml");

        assertThat(result.detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.STOCK_SNAPSHOT);
        assertThat(result.confidence()).isGreaterThanOrEqualTo(new BigDecimal("0.95"));
        assertThat(result.companyName()).isEqualTo("Example Trading Co");
        assertThat(result.dateRangeStart()).hasToString("2026-04-01");
        assertThat(result.dateRangeEnd()).hasToString("2026-06-30");
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.metadata()).containsEntry("tally", true);
    }

    @Test
    void classifiesSalesVoucherXml() throws Exception {
        assertThat(classify("sales.xml").detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.SALES_VOUCHERS);
    }

    @Test
    void classifiesPurchaseVoucherXml() throws Exception {
        assertThat(classify("purchase.xml").detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.PURCHASE_VOUCHERS);
    }

    @Test
    void classifiesCreditAndDebitNotes() throws Exception {
        assertThat(classify("credit_note.xml").detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.CREDIT_NOTES);
        assertThat(classify("debit_note.xml").detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.DEBIT_NOTES);
    }

    @Test
    void classifiesDebtorCreditorReport() throws Exception {
        assertThat(classify("debtors_creditors.xml").detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS);
    }

    @Test
    void classifiesCashbook() throws Exception {
        assertThat(classify("cashbook.xml").detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.CASH_BOOK);
    }

    @Test
    void classifiesMixedTallyMastersAndReportsContentCounts() throws Exception {
        var result = classify("mixed_masters.xml");

        assertThat(result.detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.MIXED_TALLY_MASTERS);
        assertThat(result.rowCount()).isEqualTo(4);
        var counts = (java.util.Map<?, ?>) result.metadata().get("contentCounts");
        assertThat(counts.get("products")).isEqualTo(2);
        assertThat(counts.get("ledgers")).isEqualTo(2);
    }

    @Test
    void classifiesMixedTallyVouchersByActualVoucherTypeWithoutDoubleCounting() throws Exception {
        var result = classify("mixed_vouchers.xml");

        assertThat(result.detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.MIXED_TALLY_VOUCHERS);
        assertThat(result.rowCount()).isEqualTo(8);
        var counts = (java.util.Map<?, ?>) result.metadata().get("contentCounts");
        assertThat(counts.get("voucherSales")).isEqualTo(1);
        assertThat(counts.get("voucherPurchase")).isEqualTo(1);
        assertThat(counts.get("voucherReceipt")).isEqualTo(1);
        assertThat(counts.get("voucherPayment")).isEqualTo(1);
        assertThat(counts.get("voucherCreditNote")).isEqualTo(1);
        assertThat(counts.get("voucherDebitNote")).isEqualTo(1);
        assertThat(counts.get("voucherContra")).isEqualTo(1);
        assertThat(counts.get("voucherJournal")).isEqualTo(1);
    }

    @Test
    void classifiesTallyOutstandingReportsAndInfersPartyTypeFromFileName() throws Exception {
        var debtors = classify("sundry_debtors_report.xml");
        var creditors = classify("sundry_creditors_report.xml");

        assertThat(debtors.detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS);
        assertThat(debtors.rowCount()).isEqualTo(2);
        assertThat(debtors.metadata()).containsEntry("outstandingPartyType", "CUSTOMER");
        assertThat(creditors.detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS);
        assertThat(creditors.rowCount()).isEqualTo(1);
        assertThat(creditors.metadata()).containsEntry("outstandingPartyType", "SUPPLIER");
    }

    @Test
    void classifiesDelimitedCashbookFromPaymentDirectionColumns() throws Exception {
        assertThat(classify("cashbook_party_payments.csv").detectedFileType())
            .isEqualTo(DomainEnums.DetectedFileType.CASH_BOOK);
        assertThat(classify("cashbook_unmatched.csv").detectedFileType())
            .isEqualTo(DomainEnums.DetectedFileType.CASH_BOOK);
    }

    @Test
    void classifiesStockAgeingCsvFromHeaders() throws Exception {
        var result = classify("stock_ageing.csv");
        assertThat(result.detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.STOCK_AGEING);
        assertThat(result.rowCount()).isEqualTo(2);
    }

    @Test
    void unknownFileRequiresReview() throws Exception {
        var result = classify("unknown_file.xml");
        assertThat(result.detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.UNKNOWN);
        assertThat(result.confidence()).isLessThan(new BigDecimal("0.50"));
    }

    private ImportFileClassifierService.DetectionResult classify(String fileName) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/import-fixtures/smart-session/" + fileName)) {
            assertThat(input).as(fileName).isNotNull();
            return classifier.classify(fileName, input);
        }
    }
}
