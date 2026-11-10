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

    @Test
    void classifiesExplicitSalesPurchaseAndMixedRowsInCsvAndExcel() throws Exception {
        for (boolean excel:new boolean[]{false,true}) {
            assertThat(tabular(excel,"Sales").detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.SALES_VOUCHERS);
            assertThat(tabular(excel,"Purchase").detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.PURCHASE_VOUCHERS);
            var mixed=tabular(excel,"Sales","Purchase");
            assertThat(mixed.detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.MIXED_TALLY_VOUCHERS);
            assertThat(mixed.rowCount()).isEqualTo(2);
            assertThat(mixed.companyName()).isEqualTo("Sales Company");
            assertThat(mixed.dateRangeStart()).hasToString("2026-06-15");
        }
    }

    @Test
    void unsupportedOrMissingTypesStayUnknownDespiteSalesAndPurchaseInNames() throws Exception {
        for(boolean excel:new boolean[]{false,true}) {
            assertThat(tabular(excel,"").detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.UNKNOWN);
            assertThat(tabular(excel,"Sales","Custom Adjustment").detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.UNKNOWN);
        }
    }

    @Test
    void quotedCsvNewlinesDoNotShiftVoucherTypeOrDateColumns() throws Exception {
        var text="Voucher Number,Product Name,Voucher Type,Date,Party Name,Quantity,Rate,Amount,Unit,Warehouse\nS-1,\"Purchase widget\nextra name\",Sales,2026-06-15,Buyer,2,15,30,PCS,Main\n";
        var result=classifier.classify("export.csv",new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(result.detectedFileType()).isEqualTo(DomainEnums.DetectedFileType.SALES_VOUCHERS);
        assertThat(result.rowCount()).isEqualTo(1);
    }

    private ImportFileClassifierService.DetectionResult tabular(boolean excel,String... types) throws Exception {
        var headers=new String[]{"Voucher Number","Notes","Voucher Type","Date","Party Name","Product Name","Quantity","Rate","Amount","Unit","Warehouse","Company Name"};
        if(!excel) {
            var text=new StringBuilder(String.join(",",headers)+"\n");
            for(int i=0;i<types.length;i++) text.append("V-").append(i).append(",,").append(types[i]).append(",2026-06-15,Buyer,Purchase Widget,2,15,30,PCS,Main,Sales Company\n");
            return classifier.classify("export.csv",new java.io.ByteArrayInputStream(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var bytes=new java.io.ByteArrayOutputStream()) {
            var sheet=workbook.createSheet();var header=sheet.createRow(0);
            for(int c=0;c<headers.length;c++)header.createCell(c).setCellValue(headers[c]);
            for(int i=0;i<types.length;i++) {
                var row=sheet.createRow(i+1);row.createCell(0).setCellValue("V-"+i); // Column1 deliberately absent.
                row.createCell(2).setCellValue(types[i]);row.createCell(3).setCellValue("2026-06-15");row.createCell(4).setCellValue("Buyer");row.createCell(5).setCellValue("Purchase Widget");
                row.createCell(6).setCellValue(2);row.createCell(7).setCellValue(15);row.createCell(8).setCellFormula("G"+(i+2)+"*H"+(i+2));row.createCell(9).setCellValue("PCS");row.createCell(10).setCellValue("Main");row.createCell(11).setCellValue("Sales Company");
            }
            workbook.write(bytes);return classifier.classify("export.xlsx",new java.io.ByteArrayInputStream(bytes.toByteArray()));
        }
    }

    private ImportFileClassifierService.DetectionResult classify(String fileName) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/import-fixtures/smart-session/" + fileName)) {
            assertThat(input).as(fileName).isNotNull();
            return classifier.classify(fileName, input);
        }
    }
}
