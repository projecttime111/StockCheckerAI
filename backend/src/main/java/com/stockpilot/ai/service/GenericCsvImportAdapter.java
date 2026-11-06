package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GenericCsvImportAdapter implements ErpImportAdapter {
    @Override
    public DomainEnums.SourceType sourceType() {
        return DomainEnums.SourceType.CSV;
    }

    @Override
    public ImportPreview parse(ImportRequest request) {
        try (var reader = new BufferedReader(new InputStreamReader(request.inputStream(), StandardCharsets.UTF_8))) {
            var records = readRecords(reader);
            if (records.isEmpty()) return new ImportPreview(List.of());
            var headers = uniqueHeaders(records.getFirst());
            var rows = new ArrayList<Map<String, String>>();
            for (int i = 1; i < records.size(); i++) {
                var values = records.get(i);
                if (values.stream().allMatch(String::isBlank)) continue;
                if (values.size() > headers.size()) {
                    throw new IllegalArgumentException("CSV record " + (i + 1) + " contains more fields than the header");
                }
                var row = new LinkedHashMap<String, String>();
                for (int c = 0; c < headers.size(); c++) row.put(headers.get(c), c < values.size() ? values.get(c) : "");
                rows.add(row);
            }
            return new ImportPreview(rows);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not parse CSV file: " + ex.getMessage(), ex);
        }
    }

    @Override
    public ValidationResult validate(ImportPreview preview) {
        return new ValidationResult(preview.rows().size(), 0, List.of());
    }

    @Override
    public ImportResult commit(ImportCommitRequest request) {
        return new ImportResult(0);
    }

    static List<String> splitCsvLine(String line) {
        try {
            var records = readRecords(new java.io.StringReader(line));
            return records.isEmpty() ? List.of("") : records.getFirst();
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Invalid CSV", ex);
        }
    }

    // Parse logical records, retaining newlines and doubled quotes within quoted fields.
    private static List<List<String>> readRecords(java.io.Reader source) throws java.io.IOException {
        var reader = new java.io.PushbackReader(source, 1);
        var records = new ArrayList<List<String>>();
        var fields = new ArrayList<String>();
        var value = new StringBuilder();
        boolean quoted = false, closedQuote = false, first = true, recordStarted = false;
        for (int next; (next = reader.read()) != -1;) {
            char ch = (char) next;
            if (first && ch == '\uFEFF') { first = false; continue; }
            first = false;
            recordStarted = true;
            if (quoted) {
                if (ch == '"') {
                    int following = reader.read();
                    if (following == '"') value.append('"');
                    else { quoted = false; closedQuote = true; if (following != -1) reader.unread(following); }
                } else value.append(ch);
                continue;
            }
            if (ch == '"' && value.toString().isBlank() && !closedQuote) {
                value.setLength(0); quoted = true; continue;
            }
            if (ch == ',' || ch == '\r' || ch == '\n') {
                fields.add(closedQuote ? value.toString() : value.toString().trim());
                value.setLength(0); closedQuote = false;
                if (ch != ',') {
                    if (ch == '\r') { int following = reader.read(); if (following != '\n' && following != -1) reader.unread(following); }
                    if (fields.stream().anyMatch(field -> !field.isBlank())) records.add(List.copyOf(fields));
                    fields.clear(); recordStarted = false;
                }
            } else if (closedQuote) {
                if (!Character.isWhitespace(ch)) throw new IllegalArgumentException("Unexpected character after a quoted CSV field");
            } else {
                if (ch == '"') throw new IllegalArgumentException("Quote inside an unquoted CSV field");
                value.append(ch);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quoted CSV field");
        if (recordStarted || !fields.isEmpty()) {
            fields.add(closedQuote ? value.toString() : value.toString().trim());
            if (fields.stream().anyMatch(field -> !field.isBlank())) records.add(List.copyOf(fields));
        }
        return records;
    }

    static List<String> uniqueHeaders(List<String> headers) {
        var seen = new LinkedHashMap<String, Integer>();
        var result = new ArrayList<String>();
        for (var header : headers) {
            var trimmed = header == null ? "" : header.trim();
            var key = trimmed.toLowerCase(java.util.Locale.ROOT);
            var count = seen.merge(key, 1, Integer::sum);
            result.add(count == 1 ? trimmed : trimmed + "_" + count);
        }
        return result;
    }
}
