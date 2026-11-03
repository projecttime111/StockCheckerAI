package com.stockpilot.ai.service;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.domain.DomainEnums;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ImportFileClassifierService {
    private static final int MAX_EVIDENCE_VALUES = 2_000;
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
        DateTimeFormatter.BASIC_ISO_DATE,
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d-MMM-uu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d-M-uuuu", Locale.ENGLISH)
    );

    private final ObjectMapper objectMapper;

    public ImportFileClassifierService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DetectionResult classify(String fileName, InputStream input) throws IOException {
        var extension = extension(fileName);
        return switch (extension) {
            case "xml" -> classifyXml(fileName, input);
            case "csv" -> classifyDelimited(input, "CSV");
            case "xlsx", "xls" -> classifyWorkbook(input);
            case "json" -> classifyJson(input);
            default -> unknown("The file extension is not supported by smart classification.", extension.toUpperCase(Locale.ROOT));
        };
    }

    private DetectionResult classifyXml(String fileName, InputStream input) throws IOException {
        var evidence = new Evidence("XML", fileName);
        var factory = XMLInputFactory.newFactory();
        setXmlProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setXmlProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        setXmlProperty(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        setXmlProperty(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setXmlProperty(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        try (var sanitized = GenericXmlImportAdapter.sanitizingReader(input)) {
            var reader = factory.createXMLStreamReader(sanitized);
            Deque<String> elementStack = new ArrayDeque<>();
            Deque<StringBuilder> textStack = new ArrayDeque<>();
            var currentVoucherHasTypeAttribute = false;
            while (reader.hasNext()) {
                var event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    var tag = normalize(reader.getLocalName());
                    elementStack.push(tag);
                    textStack.push(new StringBuilder());
                    evidence.tags.add(tag);
                    evidence.countElement(tag);
                    if ("VOUCHER".equals(tag)) {
                        currentVoucherHasTypeAttribute = false;
                        for (var index = 0; index < reader.getAttributeCount(); index++) {
                            if ("VCHTYPE".equals(normalize(reader.getAttributeLocalName(index)))) {
                                evidence.acceptVoucherType(reader.getAttributeValue(index));
                                currentVoucherHasTypeAttribute = true;
                            }
                        }
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && !textStack.isEmpty()) {
                    var text = textStack.peek();
                    if (text.length() < 1_000) text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT && !elementStack.isEmpty()) {
                    var tag = elementStack.pop();
                    var text = textStack.pop().toString();
                    var insideVoucher = elementStack.contains("VOUCHER");
                    if (insideVoucher && "VOUCHERTYPENAME".equals(tag) && !currentVoucherHasTypeAttribute) {
                        evidence.acceptVoucherType(text);
                        currentVoucherHasTypeAttribute = true;
                    }
                    evidence.acceptValue(tag, text, insideVoucher);
                    if ("VOUCHER".equals(tag)) {
                        currentVoucherHasTypeAttribute = false;
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException ex) {
            throw new IOException("XML could not be classified: " + safeMessage(ex), ex);
        }

        evidence.tally = evidence.tags.contains("ENVELOPE")
            || evidence.tags.contains("TALLYMESSAGE")
            || evidence.tags.contains("DSPACCNAME")
            || evidence.tags.contains("VOUCHERTYPENAME");
        return resultFromEvidence(evidence);
    }

    private DetectionResult classifyDelimited(InputStream input, String format) throws IOException {
        var evidence = new Evidence(format);
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            String headerLine = null;
            while ((line = reader.readLine()) != null) {
                if (headerLine == null && !line.isBlank()) {
                    headerLine = stripBom(line);
                    evidence.headers.addAll(parseHeaders(headerLine));
                    continue;
                }
                if (!line.isBlank()) {
                    evidence.rowCount++;
                    evidence.scanDelimitedDates(line);
                }
            }
        }
        return resultFromEvidence(evidence);
    }

    private DetectionResult classifyWorkbook(InputStream input) throws IOException {
        var evidence = new Evidence("EXCEL");
        var formatter = new DataFormatter(Locale.ENGLISH);
        try (var workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                return unknown("The workbook does not contain any sheets.", "EXCEL");
            }
            var sheet = workbook.getSheetAt(0);
            var headerFound = false;
            for (var row : sheet) {
                var nonBlank = false;
                var values = new ArrayList<String>();
                for (var cell : row) {
                    var value = formatter.formatCellValue(cell).trim();
                    values.add(value);
                    nonBlank = nonBlank || !value.isBlank();
                    if (headerFound && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                        evidence.dates.add(cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
                    }
                }
                if (!nonBlank) {
                    continue;
                }
                if (!headerFound) {
                    values.stream().map(ImportFileClassifierService::normalizeHeader).filter(value -> !value.isBlank()).forEach(evidence.headers::add);
                    headerFound = true;
                } else {
                    evidence.rowCount++;
                    values.forEach(value -> parseDate(value).ifPresent(evidence.dates::add));
                }
            }
        } catch (RuntimeException ex) {
            throw new IOException("Workbook could not be classified: " + safeMessage(ex), ex);
        }
        return resultFromEvidence(evidence);
    }

    private DetectionResult classifyJson(InputStream input) throws IOException {
        var evidence = new Evidence("JSON");
        try (var parser = objectMapper.getFactory().createParser(input)) {
            String field = null;
            var firstToken = true;
            var rootObject = false;
            while (parser.nextToken() != null) {
                if (firstToken) {
                    rootObject = parser.currentToken() == JsonToken.START_OBJECT;
                    firstToken = false;
                }
                if (parser.currentToken() == JsonToken.FIELD_NAME) {
                    field = normalize(parser.currentName());
                    evidence.tags.add(field);
                } else if (parser.currentToken() == JsonToken.START_OBJECT) {
                    evidence.rowCount++;
                } else if (parser.currentToken().isScalarValue() && field != null) {
                    evidence.acceptValue(field, parser.getValueAsString(""), false);
                }
            }
            if (rootObject) {
                evidence.rowCount = Math.max(0, evidence.rowCount - 1);
            }
        }
        return resultFromEvidence(evidence);
    }

    private DetectionResult resultFromEvidence(Evidence evidence) {
        var tags = evidence.tags;
        var values = String.join(" ", evidence.values).toUpperCase(Locale.ROOT);
        var headers = String.join(" ", evidence.headers).toUpperCase(Locale.ROOT);
        var all = tagsAsText(tags) + " " + values + " " + headers;

        DomainEnums.DetectedFileType type;
        BigDecimal confidence;
        String reason;

        var voucherCount = evidence.elementCounts.getOrDefault("VOUCHER", 0);
        var stockItemCount = evidence.elementCounts.getOrDefault("STOCKITEM", 0);
        var ledgerCount = evidence.elementCounts.getOrDefault("LEDGER", 0);
        var voucherKinds = evidence.voucherTypeCounts.keySet();

        if (has(tags, "DSPACCNAME") && (has(tags, "DSPSTKINFO") || has(tags, "DSPCLQTY"))) {
            type = DomainEnums.DetectedFileType.STOCK_SNAPSHOT;
            confidence = confidence("0.99");
            reason = "Tally closing-stock tags DSPACCNAME and DSPSTKINFO/DSPCLQTY were found.";
        } else if (voucherCount > 0 && !voucherKinds.isEmpty() && voucherKinds.stream().allMatch(this::isCashbookVoucherType)) {
            type = DomainEnums.DetectedFileType.CASH_BOOK;
            confidence = confidence("0.98");
            reason = "Tally receipt/payment vouchers were found.";
        } else if (voucherCount > 0 && voucherKinds.size() > 1) {
            type = DomainEnums.DetectedFileType.MIXED_TALLY_VOUCHERS;
            confidence = confidence("0.99");
            reason = "Mixed Tally voucher export detected with " + voucherCount + " vouchers across "
                + voucherKinds.size() + " voucher types.";
        } else if (voucherCount > 0 && voucherKinds.size() == 1) {
            var voucherType = voucherKinds.iterator().next();
            type = detectedVoucherType(voucherType);
            confidence = type == DomainEnums.DetectedFileType.UNKNOWN ? confidence("0.60") : confidence("0.98");
            reason = type == DomainEnums.DetectedFileType.UNKNOWN
                ? "Tally vouchers were found, but the voucher type is not yet supported for posting."
                : "Tally voucher type " + voucherType + " was found.";
        } else if (stockItemCount > 0 && ledgerCount > 0) {
            type = DomainEnums.DetectedFileType.MIXED_TALLY_MASTERS;
            confidence = confidence("0.99");
            reason = "Mixed Tally master export detected with " + stockItemCount + " stock items and "
                + ledgerCount + " ledgers.";
        } else if (stockItemCount > 0) {
            type = DomainEnums.DetectedFileType.INVENTORY_MASTER;
            confidence = confidence("0.98");
            reason = "Tally STOCKITEM master fields were found.";
        } else if (ledgerCount > 0) {
            type = DomainEnums.DetectedFileType.ACCOUNTING_MASTER;
            confidence = confidence("0.97");
            reason = "Tally LEDGER master fields were found.";
        } else if (has(tags, "DSPACCNAME") && has(tags, "DSPACCINFO")
            && (has(tags, "DSPCLDRAMT") || has(tags, "DSPCLCRAMT"))) {
            type = DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS;
            confidence = confidence("0.97");
            reason = "Tally Sundry Debtor/Creditor closing-balance rows were found.";
        } else if (containsAny(all, "STOCK AGEING", "STOCKAGING", "AGEING BUCKET", "AGE BUCKET") || (containsAny(all, "AGEING", "BUCKET") && containsAny(all, "DAYS", "DAY"))) {
            type = DomainEnums.DetectedFileType.STOCK_AGEING;
            confidence = confidence("0.88");
            reason = "Stock-ageing fields or bucket columns were found.";
        } else if (containsAny(all, "DEBTOR", "CREDITOR", "OUTSTANDING") && containsAny(all, "BALANCE", "AMOUNT", "BILL")) {
            type = DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS;
            confidence = confidence("0.88");
            reason = "Debtor/creditor outstanding and balance fields were found.";
        } else if (containsAny(values, "CREDIT NOTE", "CREDITNOTE")) {
            type = DomainEnums.DetectedFileType.CREDIT_NOTES;
            confidence = confidence("0.97");
            reason = "Tally voucher type Credit Note was found.";
        } else if (containsAny(values, "DEBIT NOTE", "DEBITNOTE")) {
            type = DomainEnums.DetectedFileType.DEBIT_NOTES;
            confidence = confidence("0.97");
            reason = "Tally voucher type Debit Note was found.";
        } else if (containsAny(values, "STOCK JOURNAL", "STOCKJOURNAL")) {
            type = DomainEnums.DetectedFileType.STOCK_JOURNAL;
            confidence = confidence("0.96");
            reason = "Tally voucher type Stock Journal was found.";
        } else if (looksLikeCashBook(all, values)) {
            type = DomainEnums.DetectedFileType.CASH_BOOK;
            confidence = confidence("0.84");
            reason = "Cash/bank receipt or payment fields were found.";
        } else if (has(tags, "VOUCHER") || containsAny(headers, "VOUCHER", "INVOICE")) {
            var sales = containsAny(values + " " + headers, "SALES", "SALE", "CUSTOMER");
            var purchase = containsAny(values + " " + headers, "PURCHASE", "SUPPLIER");
            if (sales && !purchase) {
                type = DomainEnums.DetectedFileType.SALES_VOUCHERS;
                confidence = confidence("0.95");
                reason = "Sales voucher and line-item evidence was found.";
            } else if (purchase && !sales) {
                type = DomainEnums.DetectedFileType.PURCHASE_VOUCHERS;
                confidence = confidence("0.95");
                reason = "Purchase voucher and line-item evidence was found.";
            } else {
                type = DomainEnums.DetectedFileType.UNKNOWN;
                confidence = confidence("0.45");
                reason = "Voucher data was found, but its business type is mixed or uncertain.";
            }
        } else if (looksLikeDelimitedPurchase(headers)) {
            type = DomainEnums.DetectedFileType.PURCHASE_VOUCHERS;
            confidence = confidence("0.90");
            reason = "Supplier/purchase, item, quantity, and rate columns were found.";
        } else if (looksLikeDelimitedSales(headers)) {
            type = DomainEnums.DetectedFileType.SALES_VOUCHERS;
            confidence = confidence("0.90");
            reason = "Sales/customer, item, quantity, and rate columns were found.";
        } else if (looksLikeStockSnapshot(headers)) {
            type = DomainEnums.DetectedFileType.STOCK_SNAPSHOT;
            confidence = confidence("0.90");
            reason = "Item and closing-stock quantity/value columns were found.";
        } else if (has(tags, "STOCKITEM") || containsAny(headers, "PRODUCT NAME", "ITEM NAME", "STOCK ITEM")) {
            type = DomainEnums.DetectedFileType.INVENTORY_MASTER;
            confidence = confidence(has(tags, "STOCKITEM") ? "0.94" : "0.78");
            reason = "Product or Tally STOCKITEM master fields were found.";
        } else if (has(tags, "LEDGER") || containsAny(headers, "LEDGER NAME", "PARTY NAME", "GSTIN")) {
            type = DomainEnums.DetectedFileType.ACCOUNTING_MASTER;
            confidence = confidence(has(tags, "LEDGER") ? "0.91" : "0.76");
            reason = "Ledger or party-master fields were found.";
        } else {
            return unknownWithEvidence("No supported StockPilot import signature was detected.", evidence);
        }

        return buildResult(type, confidence, reason, evidence);
    }

    private boolean isCashbookVoucherType(String voucherType) {
        return voucherType.contains("RECEIPT") || voucherType.contains("PAYMENT");
    }

    private DetectionResult unknown(String reason, String format) {
        return buildResult(DomainEnums.DetectedFileType.UNKNOWN, confidence("0.10"), reason, new Evidence(format));
    }

    private DetectionResult unknownWithEvidence(String reason, Evidence evidence) {
        return buildResult(DomainEnums.DetectedFileType.UNKNOWN, confidence("0.20"), reason, evidence);
    }

    private DetectionResult buildResult(DomainEnums.DetectedFileType type, BigDecimal confidence, String reason, Evidence evidence) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("format", evidence.format);
        metadata.put("tally", evidence.tally);
        metadata.put("matchedTags", evidence.tags.stream().limit(60).toList());
        metadata.put("matchedHeaders", evidence.headers.stream().limit(60).toList());
        metadata.put("contentCounts", evidence.contentCounts());
        if (evidence.outstandingPartyType != null) {
            metadata.put("outstandingPartyType", evidence.outstandingPartyType);
        }

        var relevantDates = evidence.voucherDates.isEmpty() ? evidence.dates : evidence.voucherDates;
        var start = relevantDates.stream().min(LocalDate::compareTo).orElse(null);
        var end = relevantDates.stream().max(LocalDate::compareTo).orElse(null);
        var rowCount = evidence.inferredRowCount(type);
        return new DetectionResult(type, confidence, reason, rowCount, start, end, evidence.companyName, metadata);
    }

    private static boolean looksLikeStockSnapshot(String headers) {
        return containsAny(headers, "ITEM", "PRODUCT")
            && containsAny(headers, "CLOSING QTY", "CLOSING QUANTITY", "CLOSING STOCK", "STOCK QUANTITY")
            && containsAny(headers, "VALUE", "RATE", "QUANTITY", "QTY");
    }

    private static boolean looksLikeDelimitedSales(String headers) {
        return containsAny(headers, "SALES", "CUSTOMER")
            && containsAny(headers, "ITEM", "PRODUCT")
            && containsAny(headers, "QTY", "QUANTITY")
            && containsAny(headers, "RATE", "PRICE", "AMOUNT");
    }

    private static boolean looksLikeDelimitedPurchase(String headers) {
        return containsAny(headers, "PURCHASE", "SUPPLIER")
            && containsAny(headers, "ITEM", "PRODUCT")
            && containsAny(headers, "QTY", "QUANTITY")
            && containsAny(headers, "RATE", "PRICE", "AMOUNT");
    }

    private static boolean looksLikeCashBook(String all, String values) {
        var tallyCashbook = containsAny(all, "CASH", "BANK")
            && containsAny(values + " " + all, "RECEIPT", "PAYMENT");
        var delimitedCashbook = containsAny(all, "DIRECTION", "RECEIPT/PAYMENT")
            && containsAny(all, "DATE")
            && containsAny(all, "AMOUNT")
            && containsAny(all, "PARTY", "LEDGER");
        return tallyCashbook || delimitedCashbook;
    }

    private static boolean has(Set<String> values, String expected) {
        return values.contains(expected);
    }

    private static boolean containsAny(String source, String... terms) {
        for (var term : terms) {
            if (source.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String tagsAsText(Set<String> tags) {
        return String.join(" ", tags).toUpperCase(Locale.ROOT);
    }

    private static BigDecimal confidence(String value) {
        return new BigDecimal(value);
    }

    private static DomainEnums.DetectedFileType detectedVoucherType(String voucherType) {
        var normalized = normalizeHeader(voucherType);
        if (normalized.contains("CREDIT NOTE")) return DomainEnums.DetectedFileType.CREDIT_NOTES;
        if (normalized.contains("DEBIT NOTE")) return DomainEnums.DetectedFileType.DEBIT_NOTES;
        if (normalized.contains("STOCK JOURNAL")) return DomainEnums.DetectedFileType.STOCK_JOURNAL;
        if (normalized.contains("PURCHASE")) return DomainEnums.DetectedFileType.PURCHASE_VOUCHERS;
        if (normalized.contains("SALE")) return DomainEnums.DetectedFileType.SALES_VOUCHERS;
        if (normalized.contains("RECEIPT") || normalized.contains("PAYMENT")) return DomainEnums.DetectedFileType.CASH_BOOK;
        return DomainEnums.DetectedFileType.UNKNOWN;
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        var clean = fileName.toLowerCase(Locale.ROOT);
        var dot = clean.lastIndexOf('.');
        return dot < 0 ? "" : clean.substring(dot + 1);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9]+", "").toUpperCase(Locale.ROOT);
    }

    private static String normalizeHeader(String value) {
        return value == null ? "" : stripBom(value).trim().replaceAll("[_-]+", " ").replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }

    private static Set<String> parseHeaders(String line) {
        var delimiter = line.contains("\t") ? "\t" : line.contains(";") ? ";" : ",";
        var headers = new LinkedHashSet<String>();
        for (var value : line.split(delimiter, -1)) {
            var normalized = normalizeHeader(value.replaceAll("^\"|\"$", ""));
            if (!normalized.isBlank()) {
                headers.add(normalized);
            }
        }
        return headers;
    }

    private static java.util.Optional<LocalDate> parseDate(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        var clean = value.trim();
        for (var format : DATE_FORMATS) {
            try {
                return java.util.Optional.of(LocalDate.parse(clean, format));
            } catch (DateTimeParseException ignored) {
                // Try the next known Tally/export date format.
            }
        }
        return java.util.Optional.empty();
    }

    private static String safeMessage(Exception ex) {
        var message = ex.getMessage();
        return message == null || message.isBlank() ? "invalid document structure" : message.replaceAll("[\\r\\n]+", " ");
    }

    private static void setXmlProperty(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // XML implementations may omit optional properties; external resolution remains disabled where supported.
        }
    }

    public record DetectionResult(
        DomainEnums.DetectedFileType detectedFileType,
        BigDecimal confidence,
        String reason,
        int rowCount,
        LocalDate dateRangeStart,
        LocalDate dateRangeEnd,
        String companyName,
        Map<String, Object> metadata
    ) {
    }

    private static final class Evidence {
        private final String format;
        private final Set<String> tags = new LinkedHashSet<>();
        private final Set<String> headers = new LinkedHashSet<>();
        private final Set<String> values = new LinkedHashSet<>();
        private final Map<String, Integer> elementCounts = new LinkedHashMap<>();
        private final List<LocalDate> dates = new ArrayList<>();
        private final List<LocalDate> voucherDates = new ArrayList<>();
        private final Map<String, Integer> voucherTypeCounts = new LinkedHashMap<>();
        private int rowCount;
        private String companyName;
        private boolean tally;
        private String outstandingPartyType;

        private Evidence(String format) {
            this(format, null);
        }

        private Evidence(String format, String fileName) {
            this.format = format;
            var normalizedFileName = normalizeHeader(fileName);
            if (normalizedFileName.contains("DEBTOR") || normalizedFileName.contains("RECEIVABLE")) {
                outstandingPartyType = "CUSTOMER";
            } else if (normalizedFileName.contains("CREDITOR") || normalizedFileName.contains("PAYABLE")) {
                outstandingPartyType = "SUPPLIER";
            }
        }

        private void countElement(String tag) {
            elementCounts.merge(tag, 1, Integer::sum);
        }

        private void acceptValue(String tag, String rawValue, boolean insideVoucher) {
            if (rawValue == null) {
                return;
            }
            var value = rawValue.trim().replaceAll("\\s+", " ");
            if (value.isBlank()) {
                return;
            }
            if (values.size() < MAX_EVIDENCE_VALUES && value.length() <= 500) {
                values.add(value.toUpperCase(Locale.ROOT));
            }
            if (Set.of("DATE", "VOUCHERDATE", "SVFROMDATE", "SVTODATE", "FROMDATE", "TODATE").contains(tag)) {
                parseDate(value).ifPresent(parsed -> {
                    dates.add(parsed);
                    if (insideVoucher && Set.of("DATE", "VOUCHERDATE").contains(tag)) voucherDates.add(parsed);
                });
            }
            if (companyName == null && Set.of("SVCURRENTCOMPANY", "COMPANYNAME", "CMPNAME").contains(tag)) {
                companyName = value;
            }
        }

        private void acceptVoucherType(String rawValue) {
            var normalized = normalizeHeader(rawValue);
            if (!normalized.isBlank()) voucherTypeCounts.merge(normalized, 1, Integer::sum);
        }

        private Map<String, Integer> contentCounts() {
            var counts = new LinkedHashMap<String, Integer>();
            counts.put("products", elementCounts.getOrDefault("STOCKITEM", 0));
            counts.put("ledgers", elementCounts.getOrDefault("LEDGER", 0));
            counts.put("vouchers", elementCounts.getOrDefault("VOUCHER", 0));
            counts.put("stockSnapshotRows", elementCounts.getOrDefault("DSPACCNAME", 0));
            voucherTypeCounts.forEach((type, count) -> counts.put(voucherCountKey(type), count));
            return counts;
        }

        private String voucherCountKey(String type) {
            var words = type.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
            var key = new StringBuilder("voucher");
            for (var word : words) {
                if (!word.isBlank()) key.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return key.toString();
        }

        private void scanDelimitedDates(String line) {
            for (var value : line.split("[,;\\t]")) {
                parseDate(value.replace("\"", "").trim()).ifPresent(dates::add);
            }
        }

        private int inferredRowCount(DomainEnums.DetectedFileType type) {
            if (rowCount > 0) {
                return rowCount;
            }
            return switch (type) {
                case STOCK_SNAPSHOT -> elementCounts.getOrDefault("DSPACCNAME", 0);
                case INVENTORY_MASTER -> elementCounts.getOrDefault("STOCKITEM", 0);
                case ACCOUNTING_MASTER -> elementCounts.getOrDefault("LEDGER", 0);
                case MIXED_TALLY_MASTERS -> elementCounts.getOrDefault("STOCKITEM", 0) + elementCounts.getOrDefault("LEDGER", 0);
                case SALES_VOUCHERS, PURCHASE_VOUCHERS, CREDIT_NOTES, DEBIT_NOTES, MIXED_TALLY_VOUCHERS,
                    CASH_BOOK, STOCK_JOURNAL -> elementCounts.getOrDefault("VOUCHER", 0);
                case DEBTOR_CREDITOR_ANALYSIS -> elementCounts.getOrDefault("DSPACCNAME", 0);
                default -> 0;
            };
        }
    }
}
