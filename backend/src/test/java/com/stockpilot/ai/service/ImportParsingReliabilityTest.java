package com.stockpilot.ai.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ImportParsingReliabilityTest {
    private ErpImportAdapter.ImportRequest request(String name, byte[] data) {
        return new ErpImportAdapter.ImportRequest(UUID.randomUUID(), UUID.randomUUID(), name, new ByteArrayInputStream(data));
    }
    @Test void preservesQuotesCommasNewlinesAndUnicodeInLogicalCsvRecords() {
        var csv = "\uFEFFItem Name,Unit,Note\r\n\"12\"\" wrench, बड़ा\",PCS,\"first\nsecond\"\r\n,,\r\n";
        var rows = new GenericCsvImportAdapter().parse(request("items.csv", csv.getBytes(StandardCharsets.UTF_8))).rows();
        assertThat(rows).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("Item Name", "12\" wrench, बड़ा").containsEntry("Note", "first\nsecond"));
    }
    @Test void rejectsBrokenCsvRatherThanDroppingData() {
        for (var csv : new String[]{"Name,Unit\n\"unclosed,PCS", "Name,Unit\na,b,c"})
            assertThatThrownBy(() -> new GenericCsvImportAdapter().parse(request("bad.csv", csv.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void excelDateCellsAndFormulaDatesPreserveCalendarDateAcrossDisplayFormats() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(); sheet.createRow(0).createCell(0).setCellValue("Voucher Date");
            var formats = new String[]{"d-mmm-yy", "dd/mm/yyyy", "yyyy-mm-dd"};
            for (int i=0; i<formats.length; i++) {
                var cell=sheet.createRow(i+1).createCell(0); cell.setCellValue(LocalDate.of(2024,2,29));
                var style=workbook.createCellStyle(); style.setDataFormat(workbook.createDataFormat().getFormat(formats[i])); cell.setCellStyle(style);
            }
            var formula=sheet.createRow(4).createCell(0); formula.setCellFormula("DATE(2024,2,29)"); formula.setCellStyle(sheet.getRow(1).getCell(0).getCellStyle());
            workbook.write(output);
            var rows=new GenericExcelImportAdapter().parse(request("dates.xlsx",output.toByteArray())).rows();
            assertThat(rows).hasSize(4).allSatisfy(row -> assertThat(row).containsEntry("Voucher Date","2024-02-29"));
        }
    }
    @Test void textDatesAreStrictAndSupportTallyDisplayFormats() {
        for (var date : new String[]{"5-Oct-26", "5-oct-2026", "05/10/2026", "20261005", "2026-10-05"})
            assertThat(ImportDates.parse(date)).isEqualTo(LocalDate.of(2026,10,5));
        assertThat(ImportDates.parse("29-Feb-2023")).isNull();
        assertThat(ImportDates.parse("31/04/2026")).isNull();
        assertThat(ImportDates.parse("29-Feb-2024")).isEqualTo(LocalDate.of(2024,2,29));
    }
    @Test void destinationBoundsCoverTextQuantityAndRoundedNumericOverflow() {
        assertThat(ImportFieldBounds.validate(Map.of("Item Name","a".repeat(220),"SKU","s".repeat(80),"Unit","U".repeat(24),"HSN","1".repeat(32),"Rate","999999999999.99"))).isEmpty();
        assertThat(ImportFieldBounds.validate(Map.of("Item Name","a".repeat(221),"SKU","s".repeat(81),"Unit","U".repeat(25),"HSN","1".repeat(33),"Rate","1000000000000","Qty","1.0001")))
            .hasSize(6);
        assertThat(ImportFieldBounds.validate(Map.of("Voucher Number","BIG-1","Qty","10000000000","Rate","1000")))
            .anySatisfy(error -> assertThat(error.field()).isEqualTo("Line Amount"));
    }
}
