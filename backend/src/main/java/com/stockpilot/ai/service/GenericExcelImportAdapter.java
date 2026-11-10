package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GenericExcelImportAdapter implements ErpImportAdapter {
    @Override
    public DomainEnums.SourceType sourceType() {
        return DomainEnums.SourceType.EXCEL;
    }

    @Override
    public ImportPreview parse(ImportRequest request) {
        try (var workbook = WorkbookFactory.create(request.inputStream())) {
            var formatter = new DataFormatter();
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            var sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                return new ImportPreview(List.of());
            }
            var headerRow = sheet.getRow(sheet.getFirstRowNum());
            var headers = new ArrayList<String>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                headers.add(formatter.formatCellValue(headerRow.getCell(c), evaluator).trim());
            }
            headers = new ArrayList<>(GenericCsvImportAdapter.uniqueHeaders(headers));
            var rows = new ArrayList<Map<String, String>>();
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                var values = new LinkedHashMap<String, String>();
                var blank = true;
                for (int c = 0; c < headers.size(); c++) {
                    var value = cellValue(row.getCell(c), formatter, evaluator);
                    if (!value.isBlank()) {
                        blank = false;
                    }
                    values.put(headers.get(c), value);
                }
                if (!blank) {
                    rows.add(values);
                }
            }
            return new ImportPreview(rows);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not parse Excel file: " + ex.getMessage(), ex);
        }
    }

    static String cellValue(org.apache.poi.ss.usermodel.Cell cell, DataFormatter formatter,
                             org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        if (cell == null) return "";
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) evaluator.evaluateFormulaCell(cell);
        var type = cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
            ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == org.apache.poi.ss.usermodel.CellType.NUMERIC
            && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        return formatter.formatCellValue(cell, evaluator).trim();
    }

    @Override
    public ValidationResult validate(ImportPreview preview) {
        return new ValidationResult(preview.rows().size(), 0, List.of());
    }

    @Override
    public ImportResult commit(ImportCommitRequest request) {
        return new ImportResult(0);
    }
}
