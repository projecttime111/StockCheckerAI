package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.domain.ImportSessionFile;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SmartImportCanonicalParserService {
    private final Map<DomainEnums.SourceType, ErpImportAdapter> adapters;
    private final GenericXmlImportAdapter genericXml;
    private final ObjectStorageService storage;

    public SmartImportCanonicalParserService(
        List<ErpImportAdapter> adapters,
        @Qualifier("genericXmlImportAdapter") GenericXmlImportAdapter genericXml,
        ObjectStorageService storage
    ) {
        this.adapters = adapters.stream().collect(Collectors.toMap(ErpImportAdapter::sourceType, Function.identity()));
        this.genericXml = genericXml;
        this.storage = storage;
    }

    public List<Map<String, String>> parse(UUID tenantId, UUID sessionId, ImportSessionFile file) {
        var type = effectiveType(file);
        if (type == DomainEnums.DetectedFileType.UNKNOWN || file.status == DomainEnums.ImportSessionFileStatus.DUPLICATE) {
            return List.of();
        }
        try (var input = storage.read(file.storageKey)) {
            if (isXml(file) && type == DomainEnums.DetectedFileType.CASH_BOOK) {
                return cashbookRows(input);
            }
            if (isXml(file) && type == DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS) {
                return debtorCreditorRows(input, file);
            }
            if (isXml(file) && type == DomainEnums.DetectedFileType.STOCK_AGEING) {
                return stockAgeingRows(input);
            }
            var adapter = adapters.get(sourceType(file, type));
            if (adapter == null) {
                throw new IllegalArgumentException("No parser is available for " + file.originalFileName);
            }
            return adapter.parse(new ErpImportAdapter.ImportRequest(tenantId, sessionId, file.originalFileName, input)).rows();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not stage " + file.originalFileName + ": " + safeMessage(ex), ex);
        }
    }

    private DomainEnums.SourceType sourceType(ImportSessionFile file, DomainEnums.DetectedFileType type) {
        var extension = extension(file.originalFileName);
        if ("xml".equals(extension) && isTally(file, type)) {
            return DomainEnums.SourceType.TALLY_XML;
        }
        return switch (extension) {
            case "csv" -> DomainEnums.SourceType.CSV;
            case "xlsx", "xls" -> DomainEnums.SourceType.EXCEL;
            case "json" -> DomainEnums.SourceType.JSON;
            case "xml" -> DomainEnums.SourceType.XML;
            default -> DomainEnums.SourceType.OTHER_ERP;
        };
    }

    private boolean isTally(ImportSessionFile file, DomainEnums.DetectedFileType type) {
        if (Boolean.parseBoolean(String.valueOf(file.metadataJson.getOrDefault("tally", false)))) {
            return true;
        }
        return switch (type) {
            case INVENTORY_MASTER, ACCOUNTING_MASTER, MIXED_TALLY_MASTERS, STOCK_SNAPSHOT,
                SALES_VOUCHERS, PURCHASE_VOUCHERS, CREDIT_NOTES, DEBIT_NOTES, STOCK_JOURNAL,
                MIXED_TALLY_VOUCHERS -> file.detectedFileType == type && file.confidence != null
                    && file.confidence.compareTo(new java.math.BigDecimal("0.90")) >= 0;
            default -> false;
        };
    }

    private List<Map<String, String>> cashbookRows(InputStream input) throws Exception {
        var document = genericXml.parseDocument(input);
        var rows = new ArrayList<Map<String, String>>();
        var vouchers = document.getElementsByTagName("VOUCHER");
        for (int index = 0; index < vouchers.getLength(); index++) {
            var voucher = (Element) vouchers.item(index);
            var row = new LinkedHashMap<String, String>();
            row.put("Date", first(voucher, "DATE", "VOUCHERDATE"));
            row.put("Voucher Type", first(voucher, "VOUCHERTYPENAME", "VCHTYPE"));
            row.put("Party Name", first(voucher, "PARTYLEDGERNAME", "LEDGERNAME"));
            row.put("Amount", first(voucher, "AMOUNT", "TOTALAMOUNT"));
            row.put("Voucher Number", first(voucher, "VOUCHERNUMBER", "REFERENCENUMBER"));
            row.put("Invoice Reference", first(voucher, "REFERENCENUMBER", "BILLREF", "BILLNAME"));
            row.put("Reference Number", first(voucher, "REFERENCENUMBER", "BILLREF", "BILLNAME", "VOUCHERNUMBER"));
            row.put("GSTIN", first(voucher, "PARTYGSTIN", "GSTIN"));
            row.put("Payment Mode", first(voucher, "PAYMENTMODE", "INSTRUMENTTYPE", "TRANSACTIONTYPE"));
            row.put("Source Entity", "CASHBOOK");
            rows.add(row);
        }
        return rows.isEmpty() ? genericRows(document) : rows;
    }

    private List<Map<String, String>> debtorCreditorRows(InputStream input, ImportSessionFile file) throws Exception {
        var document = genericXml.parseDocument(input);
        var rows = new ArrayList<Map<String, String>>();
        addTallyOutstandingRows(document, rows, outstandingPartyType(file));
        addPartyNodes(document, rows, "DEBTOR", "CUSTOMER");
        addPartyNodes(document, rows, "CREDITOR", "SUPPLIER");
        if (rows.isEmpty()) {
            var ledgers = document.getElementsByTagName("LEDGER");
            for (int index = 0; index < ledgers.getLength(); index++) {
                var ledger = (Element) ledgers.item(index);
                rows.add(partyRow(ledger, "UNKNOWN"));
            }
        }
        return rows.isEmpty() ? genericRows(document) : rows;
    }

    private void addTallyOutstandingRows(Document document, List<Map<String, String>> rows, String partyType) {
        var names = document.getElementsByTagName("DSPACCNAME");
        for (int index = 0; index < names.getLength(); index++) {
            var name = (Element) names.item(index);
            var info = nextElementSibling(name, "DSPACCINFO");
            if (info == null) continue;

            var partyName = first(name, "DSPDISPNAME", "NAME");
            if (partyName.isBlank()) continue;
            var debit = first(info, "DSPCLDRAMTA", "DSPCLDRAMT");
            var credit = first(info, "DSPCLCRAMTA", "DSPCLCRAMT");
            var balance = firstNonZeroAmount(debit, credit);

            var row = new LinkedHashMap<String, String>();
            row.put("Party Name", partyName);
            row.put("Opening Balance", absoluteAmount(balance));
            row.put("Snapshot Date", fileDate(document));
            row.put("Party Type", partyType);
            row.put("Debit Balance", debit);
            row.put("Credit Balance", credit);
            row.put("Source Entity", "DEBTOR_CREDITOR");
            rows.add(row);
        }
    }

    private String outstandingPartyType(ImportSessionFile file) {
        var metadataType = file.metadataJson == null ? null : file.metadataJson.get("outstandingPartyType");
        if (metadataType != null && !metadataType.toString().isBlank()) {
            return metadataType.toString().toUpperCase(Locale.ROOT);
        }
        var name = file.originalFileName == null ? "" : file.originalFileName.toLowerCase(Locale.ROOT);
        if (name.contains("creditor") || name.contains("payable")) return "SUPPLIER";
        if (name.contains("debtor") || name.contains("receivable")) return "CUSTOMER";
        return "UNKNOWN";
    }

    private String fileDate(Document document) {
        var root = document.getDocumentElement();
        return root == null ? "" : first(root, "SVTODATE", "TODATE", "ASSONDATE", "REPORTDATE");
    }

    private Element nextElementSibling(Element element, String tagName) {
        var current = element.getNextSibling();
        while (current != null) {
            if (current instanceof Element sibling) {
                return tagName.equalsIgnoreCase(sibling.getTagName()) ? sibling : null;
            }
            current = current.getNextSibling();
        }
        return null;
    }

    private String firstNonZeroAmount(String... values) {
        for (var value : values) {
            if (value == null || value.isBlank()) continue;
            try {
                var parsed = new java.math.BigDecimal(value.replace(",", "").replaceAll("[^0-9.\\-]", ""));
                if (parsed.signum() != 0) return value;
            } catch (NumberFormatException ignored) {
                // Keep looking for a usable balance field.
            }
        }
        return "0";
    }

    private String absoluteAmount(String value) {
        try {
            var cleaned = value == null ? "" : value.replace(",", "").replaceAll("[^0-9.\\-]", "");
            return cleaned.isBlank() ? "0" : new java.math.BigDecimal(cleaned).abs().stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return value == null ? "0" : value;
        }
    }

    private List<Map<String, String>> stockAgeingRows(InputStream input) throws Exception {
        var document = genericXml.parseDocument(input);
        var rows = new ArrayList<Map<String, String>>();
        for (var tag : List.of("ITEM", "STOCKITEM", "ROW", "AGEINGROW")) {
            var nodes = document.getElementsByTagName(tag);
            for (int index = 0; index < nodes.getLength(); index++) {
                var item = (Element) nodes.item(index);
                var row = new LinkedHashMap<String, String>();
                row.put("Item Name", first(item, "ITEMNAME", "STOCKITEMNAME", "NAME", "DSPDISPNAME"));
                row.put("Unit", first(item, "UNIT", "BASEUNITS"));
                row.put("Quantity", first(item, "QUANTITY", "QTY", "CLOSINGQTY"));
                row.put("Ageing Bucket", first(item, "AGEINGBUCKET", "BUCKET", "AGEBUCKET"));
                row.put("Days", first(item, "DAYS", "DAYSOLD", "AGE"));
                row.put("Value", first(item, "VALUE", "AMOUNT", "STOCKVALUE"));
                row.put("Source Entity", "STOCK_AGEING");
                if (!row.get("Item Name").isBlank()) {
                    rows.add(row);
                }
            }
            if (!rows.isEmpty()) {
                break;
            }
        }
        return rows.isEmpty() ? genericRows(document) : rows;
    }

    private void addPartyNodes(Document document, List<Map<String, String>> rows, String tag, String partyType) {
        var nodes = document.getElementsByTagName(tag);
        for (int index = 0; index < nodes.getLength(); index++) {
            rows.add(partyRow((Element) nodes.item(index), partyType));
        }
    }

    private Map<String, String> partyRow(Element element, String partyType) {
        var row = new LinkedHashMap<String, String>();
        row.put("Party Name", first(element, "LEDGERNAME", "PARTYNAME", "NAME", "DSPDISPNAME"));
        row.put("GSTIN", first(element, "GSTIN", "PARTYGSTIN"));
        row.put("Phone", first(element, "PHONE", "MOBILE"));
        row.put("Email", first(element, "EMAIL"));
        row.put("Opening Balance", first(element, "OUTSTANDING", "BALANCE", "CLOSINGBALANCE"));
        row.put("Snapshot Date", first(element, "DATE", "ASSONDATE", "REPORTDATE"));
        row.put("Overdue Amount", first(element, "OVERDUE", "OVERDUEAMOUNT"));
        row.put("Party Type", partyType);
        row.put("Source Entity", "DEBTOR_CREDITOR");
        return row;
    }

    private List<Map<String, String>> genericRows(Document document) {
        var rows = new ArrayList<Map<String, String>>();
        var children = document.getDocumentElement().getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element element) {
                rows.add(genericXml.elementToRow(element));
            }
        }
        return rows;
    }

    private String first(Element element, String... tags) {
        for (var tag : tags) {
            var exact = element.getElementsByTagName(tag);
            if (exact.getLength() > 0) {
                var value = exact.item(0).getTextContent().trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
            var all = element.getElementsByTagName("*");
            for (int index = 0; index < all.getLength(); index++) {
                if (all.item(index) instanceof Element child && tag.equalsIgnoreCase(child.getTagName())) {
                    var value = child.getTextContent().trim();
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
        }
        return "";
    }

    private boolean isXml(ImportSessionFile file) {
        return "xml".equals(extension(file.originalFileName));
    }

    private String extension(String fileName) {
        var normalized = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var dot = normalized.lastIndexOf('.');
        return dot < 0 ? "" : normalized.substring(dot + 1);
    }

    private DomainEnums.DetectedFileType effectiveType(ImportSessionFile file) {
        return file.selectedFileType == null ? file.detectedFileType : file.selectedFileType;
    }

    private String safeMessage(Exception ex) {
        var message = ex.getMessage();
        return message == null || message.isBlank() ? "invalid file content" : message.replaceAll("[\\r\\n]+", " ");
    }
}
