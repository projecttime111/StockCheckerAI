package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class TallyXmlImportAdapter extends GenericXmlImportAdapter {
    private static final Set<String> STREAMED_ENTITIES = Set.of(
        "STOCKITEM", "LEDGER", "VOUCHER", "DSPACCNAME", "DSPSTKINFO");

    @Override
    public DomainEnums.SourceType sourceType() {
        return DomainEnums.SourceType.TALLY_XML;
    }

    @Override
    public ImportPreview parse(ImportRequest request) {
        try {
            var rows = new ArrayList<Map<String, String>>();
            var inputFactory = XMLInputFactory.newFactory();
            setXmlProperty(inputFactory, XMLInputFactory.SUPPORT_DTD, false);
            setXmlProperty(inputFactory, "javax.xml.stream.isSupportingExternalEntities", false);
            setXmlProperty(inputFactory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
            setXmlProperty(inputFactory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
            setXmlProperty(inputFactory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var outputFactory = XMLOutputFactory.newFactory();

            try (var sanitized = sanitizingReader(request.inputStream())) {
                var reader = inputFactory.createXMLEventReader(sanitized);
                String pendingDisplayName = null;
                while (reader.hasNext()) {
                    var event = reader.nextEvent();
                    if (!event.isStartElement()) continue;
                    var entityName = event.asStartElement().getName().getLocalPart().toUpperCase(Locale.ROOT);
                    if (!STREAMED_ENTITIES.contains(entityName)) continue;

                    var fragment = new StringWriter();
                    var writer = outputFactory.createXMLEventWriter(fragment);
                    writer.add(event);
                    var depth = 1;
                    while (reader.hasNext() && depth > 0) {
                        var nested = reader.nextEvent();
                        writer.add(nested);
                        if (nested.isStartElement()) depth++;
                        if (nested.isEndElement()) depth--;
                    }
                    writer.close();

                    var element = parseFragment(fragment.toString());
                    switch (entityName) {
                        case "STOCKITEM" -> addStockItemRow(element, rows);
                        case "LEDGER" -> addLedgerRow(element, rows);
                        case "VOUCHER" -> addVoucherRows(element, rows);
                        case "DSPACCNAME" -> pendingDisplayName = text(element, "DSPDISPNAME");
                        case "DSPSTKINFO" -> {
                            addDisplayStockRow(pendingDisplayName, element, rows);
                            pendingDisplayName = null;
                        }
                        default -> { }
                    }
                }
                reader.close();
            }
            return new ImportPreview(rows);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not parse Tally XML file: " + ex.getMessage(), ex);
        }
    }

    private void addDisplayStockRows(Document document, List<Map<String, String>> rows) {
        var names = document.getElementsByTagName("DSPACCNAME");
        for (int i = 0; i < names.getLength(); i++) {
            var name = (Element) names.item(i);
            var itemName = text(name, "DSPDISPNAME");
            var stockInfo = nextElementSibling(name, "DSPSTKINFO");
            var stockClose = stockInfo == null ? null : firstElement(stockInfo, "DSPSTKCL");
            if (itemName.isBlank() || stockClose == null) {
                continue;
            }
            var closingQty = text(stockClose, "DSPCLQTY");
            var closingRate = text(stockClose, "DSPCLRATE");
            var closingAmount = text(stockClose, "DSPCLAMTA");
            var row = new LinkedHashMap<String, String>();
            row.put("Item Name", itemName);
            row.put("Unit", unitFromQuantity(closingQty, ""));
            row.put("Opening Stock", numberPrefix(closingQty));
            row.put("Purchase Price", numberPrefix(closingRate));
            row.put("Stock Value", numberPrefix(closingAmount));
            row.put("Source Entity", "DSPSTKCL");
            rows.add(row);
        }
    }

    private String text(Element element, String tag) {
        var nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private String textIgnoreCase(Element element, String tag) {
        var nodes = element.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child && tag.equalsIgnoreCase(child.getTagName())) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }

    private Map<String, String> voucherHeaderRow(Element voucher) {
        var row = new LinkedHashMap<String, String>();
        var voucherType = firstNonBlank(voucher.getAttribute("VCHTYPE"), textIgnoreCase(voucher, "VOUCHERTYPENAME"));
        var partyName = firstNonBlank(textIgnoreCase(voucher, "PARTYLEDGERNAME"), cashbookPartyName(voucher));
        row.put("Voucher Type", voucherType);
        row.put("Voucher Date", textIgnoreCase(voucher, "DATE"));
        row.put("Date", textIgnoreCase(voucher, "DATE"));
        row.put("Voucher Number", textIgnoreCase(voucher, "VOUCHERNUMBER"));
        row.put("Invoice Number", textIgnoreCase(voucher, "VOUCHERNUMBER"));
        row.put("Party Name", partyName);
        row.put("Amount", cashbookAmount(voucher, partyName));
        row.put("Invoice Reference", firstNonBlank(textIgnoreCase(voucher, "REFERENCENUMBER"),
            textIgnoreCase(voucher, "BILLREF"), textIgnoreCase(voucher, "BILLNAME")));
        row.put("Reference Number", firstNonBlank(
            textIgnoreCase(voucher, "REFERENCENUMBER"),
            textIgnoreCase(voucher, "BILLREF"),
            textIgnoreCase(voucher, "BILLNAME"),
            textIgnoreCase(voucher, "VOUCHERNUMBER")));
        row.put("Payment Mode", cashbookPaymentMode(voucher));
        row.put("Tally GUID", firstNonBlank(voucher.getAttribute("GUID"), textIgnoreCase(voucher, "GUID"), textIgnoreCase(voucher, "MASTERID")));
        row.put("Cancelled", cancelled(voucher));
        row.put("Source Entity", "VOUCHER_HEADER");
        return row;
    }

    private String cashbookPartyName(Element voucher) {
        var ledgers = voucher.getElementsByTagName("ALLLEDGERENTRIES.LIST");
        for (int index = 0; index < ledgers.getLength(); index++) {
            var ledger = (Element) ledgers.item(index);
            var ledgerName = firstNonBlank(textIgnoreCase(ledger, "LEDGERNAME"), textIgnoreCase(ledger, "NAME"));
            if (!ledgerName.isBlank() && !isCashOrBankLedger(ledgerName)) return ledgerName;
        }
        return "";
    }

    private String cashbookAmount(Element voucher, String partyName) {
        String firstNonZero = "";
        var ledgers = voucher.getElementsByTagName("ALLLEDGERENTRIES.LIST");
        for (int index = 0; index < ledgers.getLength(); index++) {
            var ledger = (Element) ledgers.item(index);
            var ledgerName = firstNonBlank(textIgnoreCase(ledger, "LEDGERNAME"), textIgnoreCase(ledger, "NAME"));
            var amount = decimal(textIgnoreCase(ledger, "AMOUNT"));
            if (amount == null || amount.signum() == 0) continue;
            if (firstNonZero.isBlank()) firstNonZero = format(amount.abs());
            if (!partyName.isBlank() && normalizeLedger(ledgerName).equals(normalizeLedger(partyName))) {
                return format(amount.abs());
            }
        }
        return firstNonBlank(firstNonZero, numberPrefix(textIgnoreCase(voucher, "AMOUNT")));
    }

    private String cashbookPaymentMode(Element voucher) {
        var ledgers = voucher.getElementsByTagName("ALLLEDGERENTRIES.LIST");
        for (int index = 0; index < ledgers.getLength(); index++) {
            var ledger = (Element) ledgers.item(index);
            var ledgerName = firstNonBlank(textIgnoreCase(ledger, "LEDGERNAME"), textIgnoreCase(ledger, "NAME"));
            if (isCashOrBankLedger(ledgerName)) return ledgerName;
        }
        return firstNonBlank(textIgnoreCase(voucher, "PAYMENTMODE"), textIgnoreCase(voucher, "TRANSACTIONTYPE"));
    }

    private boolean isCashOrBankLedger(String ledgerName) {
        var normalized = normalizeLedger(ledgerName);
        return normalized.contains("CASH") || normalized.contains("BANK") || normalized.contains("UPI")
            || normalized.contains("NEFT") || normalized.contains("RTGS") || normalized.contains("IMPS");
    }

    private void applyVoucherFinancials(Map<String, String> row, Element voucher) {
        var financials = voucherFinancials(voucher);
        row.put("Tax Amount", format(financials.taxAmount));
        row.put("Discount Amount", format(financials.discountAmount));
        row.put("Freight Amount", format(financials.freightAmount));
        row.put("Round Off Amount", format(financials.roundOffAmount));
        row.put("Other Charges Amount", format(financials.otherChargesAmount));
        row.put("Tax Line Count", String.valueOf(financials.taxLines));
        row.put("Discount Line Count", String.valueOf(financials.discountLines));
        row.put("Freight Line Count", String.valueOf(financials.freightLines));
        row.put("Round Off Line Count", String.valueOf(financials.roundOffLines));
        row.put("Other Charge Line Count", String.valueOf(financials.otherChargeLines));
        row.put("Ledger Adjustment Details", financials.details.toString());
    }

    private VoucherFinancials voucherFinancials(Element voucher) {
        var result = new VoucherFinancials();
        var partyName = normalizeLedger(textIgnoreCase(voucher, "PARTYLEDGERNAME"));
        var voucherType = normalizeLedger(firstNonBlank(voucher.getAttribute("VCHTYPE"), textIgnoreCase(voucher, "VOUCHERTYPENAME")));
        var ledgers = voucher.getElementsByTagName("ALLLEDGERENTRIES.LIST");
        for (int index = 0; index < ledgers.getLength(); index++) {
            var ledger = (Element) ledgers.item(index);
            var ledgerName = firstNonBlank(textIgnoreCase(ledger, "LEDGERNAME"), textIgnoreCase(ledger, "NAME"));
            var normalized = normalizeLedger(ledgerName);
            var amount = decimal(textIgnoreCase(ledger, "AMOUNT"));
            var component = componentType(normalized, partyName, voucherType);
            if (component == null || amount == null) continue;
            result.add(component, ledgerName, amount);
        }
        return result;
    }

    private String componentType(String ledgerName, String partyName, String voucherType) {
        if (ledgerName.isBlank() || ledgerName.equals(partyName)) return null;
        if (ledgerName.contains("CGST") || ledgerName.contains("SGST") || ledgerName.contains("IGST")
            || ledgerName.contains("GST") || ledgerName.contains("TAX") || ledgerName.contains("DUTY")) return "TAX";
        if (ledgerName.contains("DISCOUNT") || ledgerName.contains("DISC ")) return "DISCOUNT";
        if (ledgerName.contains("FREIGHT") || ledgerName.contains("DELIVERY") || ledgerName.contains("LOADING")
            || ledgerName.contains("CARRIAGE") || ledgerName.contains("TRANSPORT") || ledgerName.contains("PACKING")) return "FREIGHT";
        if (ledgerName.contains("ROUND")) return "ROUND_OFF";
        if (ledgerName.equals(voucherType) || ledgerName.contains("SALES") || ledgerName.contains("PURCHASE")
            || ledgerName.contains("RETURN") || ledgerName.contains("CASH") || ledgerName.contains("BANK")) return null;
        return "OTHER_CHARGE";
    }

    private String normalizeLedger(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private String numberPrefix(String value) {
        if (value == null) {
            return "";
        }
        var normalized = value.replaceAll("[^0-9.\\-]", "");
        return normalized.isBlank() ? "" : normalized;
    }

    private PurchaseRateResolution resolvePurchaseRate(String rawRate, String rawAmount, String rawQuantity) {
        var rate = decimal(rawRate);
        var amount = decimal(rawAmount);
        var quantity = decimal(rawQuantity);
        if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
            return new PurchaseRateResolution(format(rate), "RATE_FIELD");
        }
        if (rate != null && rate.compareTo(BigDecimal.ZERO) < 0) {
            return new PurchaseRateResolution(format(rate.abs()), "SIGN_NORMALIZED");
        }
        if (amount != null && amount.compareTo(BigDecimal.ZERO) != 0 && quantity != null && quantity.compareTo(BigDecimal.ZERO) != 0) {
            var derived = amount.abs().divide(quantity.abs(), 2, RoundingMode.HALF_UP);
            return new PurchaseRateResolution(format(derived), "DERIVED_FROM_AMOUNT");
        }
        var invalidRate = rawRate != null && !rawRate.isBlank() && rate == null;
        var invalidAmount = rawAmount != null && !rawAmount.isBlank() && amount == null;
        if (invalidRate || invalidAmount || quantity == null || quantity.compareTo(BigDecimal.ZERO) == 0) {
            return new PurchaseRateResolution("", "INVALID");
        }
        return new PurchaseRateResolution("0", "ZERO_COST_ITEM");
    }

    private final class VoucherFinancials {
        private BigDecimal taxAmount = BigDecimal.ZERO;
        private BigDecimal discountAmount = BigDecimal.ZERO;
        private BigDecimal freightAmount = BigDecimal.ZERO;
        private BigDecimal roundOffAmount = BigDecimal.ZERO;
        private BigDecimal otherChargesAmount = BigDecimal.ZERO;
        private int taxLines;
        private int discountLines;
        private int freightLines;
        private int roundOffLines;
        private int otherChargeLines;
        private final StringBuilder details = new StringBuilder();

        private void add(String type, String ledgerName, BigDecimal amount) {
            if (!details.isEmpty()) details.append(" | ");
            details.append(type).append(':').append(ledgerName).append(':').append(format(amount));
            switch (type) {
                case "TAX" -> { taxAmount = taxAmount.add(amount.abs()); taxLines++; }
                case "DISCOUNT" -> { discountAmount = discountAmount.add(amount.abs()); discountLines++; }
                case "FREIGHT" -> { freightAmount = freightAmount.add(amount.abs()); freightLines++; }
                case "ROUND_OFF" -> { roundOffAmount = roundOffAmount.add(amount); roundOffLines++; }
                default -> { otherChargesAmount = otherChargesAmount.add(amount.abs()); otherChargeLines++; }
            }
        }
    }

    private Element parseFragment(String xml) throws Exception {
        try (var input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            return parseDocument(input).getDocumentElement();
        }
    }

    private void addStockItemRow(Element item, List<Map<String, String>> rows) {
        var row = new LinkedHashMap<String, String>();
        row.put("Item Name", item.getAttribute("NAME"));
        row.put("Unit", text(item, "BASEUNITS"));
        row.put("Category", text(item, "PARENT"));
        row.put("Opening Stock", numberPrefix(text(item, "OPENINGBALANCE")));
        row.put("Purchase Price", numberPrefix(text(item, "OPENINGRATE")));
        row.put("Sales Price", numberPrefix(text(item, "RATEOFVAT")));
        row.put("HSN", text(item, "HSNCODE"));
        row.put("HSN Code", text(item, "HSNCODE"));
        row.put("GST %", text(item, "RATEDETAILS.LIST"));
        row.put("Tally GUID", firstNonBlank(item.getAttribute("GUID"), textIgnoreCase(item, "GUID"), textIgnoreCase(item, "MASTERID")));
        row.put("Source Entity", "STOCKITEM");
        rows.add(row);
    }

    private void addLedgerRow(Element ledger, List<Map<String, String>> rows) {
        var row = new LinkedHashMap<String, String>();
        row.put("Party Name", ledger.getAttribute("NAME"));
        row.put("GSTIN", text(ledger, "PARTYGSTIN"));
        row.put("GST No", text(ledger, "PARTYGSTIN"));
        row.put("Ledger Group", text(ledger, "PARENT"));
        row.put("Tally GUID", firstNonBlank(ledger.getAttribute("GUID"), textIgnoreCase(ledger, "GUID"), textIgnoreCase(ledger, "MASTERID")));
        row.put("Source Entity", "LEDGER");
        rows.add(row);
    }

    private void addVoucherRows(Element voucher, List<Map<String, String>> rows) {
        var entries = voucher.getElementsByTagName("ALLINVENTORYENTRIES.LIST");
        for (int index = 0; index < entries.getLength(); index++) {
            var entry = (Element) entries.item(index);
            var row = voucherItemRow(voucher, entry);
            if (index == 0) applyVoucherFinancials(row, voucher);
            rows.add(row);
        }
        if (entries.getLength() == 0) {
            var row = voucherHeaderRow(voucher);
            applyVoucherFinancials(row, voucher);
            rows.add(row);
        }
    }

    private Map<String, String> voucherItemRow(Element voucher, Element entry) {
        var voucherType = firstNonBlank(voucher.getAttribute("VCHTYPE"), textIgnoreCase(voucher, "VOUCHERTYPENAME"));
        var rawBilledQuantity = textIgnoreCase(entry, "BILLEDQTY");
        var rawActualQuantity = textIgnoreCase(entry, "ACTUALQTY");
        var rawQuantity = firstNonBlank(rawBilledQuantity, rawActualQuantity);
        var rawRate = textIgnoreCase(entry, "RATE");
        var rawAmount = textIgnoreCase(entry, "AMOUNT");
        var purchaseLike = isPurchaseVoucher(voucherType) || isPurchaseReturnVoucher(voucherType);
        var quantity = numberPrefix(rawQuantity);
        if (isPurchaseReturnVoucher(voucherType)) quantity = absoluteNumber(quantity);
        var rateResolution = purchaseLike
            ? resolvePurchaseRate(rawRate, rawAmount, rawQuantity)
            : new PurchaseRateResolution(numberPrefix(rawRate), "RATE_FIELD");
        var row = voucherHeaderRow(voucher);
        row.put("Item Name", text(entry, "STOCKITEMNAME"));
        row.put("Unit", unitFromQuantity(rawBilledQuantity, rawActualQuantity));
        row.put("Qty", quantity);
        row.put("Billed Quantity", numberPrefix(rawBilledQuantity));
        row.put("Actual Quantity", numberPrefix(rawActualQuantity));
        row.put("Rate", rateResolution.rate());
        row.put("Amount", numberPrefix(rawAmount));
        row.put("Raw Rate", rawRate);
        row.put("Raw Amount", rawAmount);
        row.put("Raw Quantity", rawQuantity);
        row.put("Parsed Rate", rateResolution.rate());
        row.put("Rate Source", rateResolution.source());
        row.put("Ledger Lines Skipped", String.valueOf(voucher.getElementsByTagName("ALLLEDGERENTRIES.LIST").getLength()));
        row.put("Godown", text(entry, "GODOWNNAME"));
        row.put("Source Godown", firstNonBlank(textIgnoreCase(entry, "SOURCEGODOWN"), textIgnoreCase(voucher, "SOURCEGODOWN")));
        row.put("Destination Godown", firstNonBlank(textIgnoreCase(entry, "DESTINATIONGODOWN"), textIgnoreCase(voucher, "DESTINATIONGODOWN")));
        row.put("Is Deemed Positive", textIgnoreCase(entry, "ISDEEMEDPOSITIVE"));
        row.put("Batch", textIgnoreCase(entry, "BATCHNAME"));
        row.put("Tracking Number", firstNonBlank(textIgnoreCase(entry, "TRACKINGNUMBER"), textIgnoreCase(voucher, "TRACKINGNUMBER")));
        row.put("Narration", textIgnoreCase(voucher, "NARRATION"));
        row.put("Source Entity", "VOUCHER");
        return row;
    }

    private void addDisplayStockRow(String itemName, Element stockInfo, List<Map<String, String>> rows) {
        var stockClose = firstElement(stockInfo, "DSPSTKCL");
        if (itemName == null || itemName.isBlank() || stockClose == null) return;
        var closingQty = text(stockClose, "DSPCLQTY");
        var row = new LinkedHashMap<String, String>();
        row.put("Item Name", itemName);
        row.put("Unit", unitFromQuantity(closingQty, ""));
        row.put("Opening Stock", numberPrefix(closingQty));
        row.put("Purchase Price", numberPrefix(text(stockClose, "DSPCLRATE")));
        row.put("Stock Value", numberPrefix(text(stockClose, "DSPCLAMTA")));
        row.put("Source Entity", "DSPSTKCL");
        rows.add(row);
    }

    private void setXmlProperty(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // StAX providers differ; unsupported hardening properties are ignored individually.
        }
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            var cleaned = value.replace(",", "").replaceAll("[^0-9.\\-]", "");
            return cleaned.isBlank() || "-".equals(cleaned) ? null : new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String absoluteNumber(String value) {
        var parsed = decimal(value);
        return parsed == null ? value : format(parsed.abs());
    }

    private String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private boolean isPurchaseVoucher(String voucherType) {
        var normalized = voucherType == null ? "" : voucherType.toLowerCase(Locale.ROOT);
        return normalized.contains("purchase") && !isPurchaseReturnVoucher(voucherType);
    }

    private boolean isPurchaseReturnVoucher(String voucherType) {
        var normalized = voucherType == null ? "" : voucherType.toLowerCase(Locale.ROOT);
        return normalized.contains("debit note") || normalized.contains("purchase return") || (normalized.contains("purchase") && normalized.contains("return"));
    }

    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String unitFromQuantity(String billedQty, String actualQty) {
        var source = firstNonBlank(billedQty, actualQty);
        var unit = source.replaceAll("[-0-9.,\\s]", "").trim();
        return unit.isBlank() ? "PCS" : unit;
    }

    private String cancelled(Element voucher) {
        var action = voucher.getAttribute("ACTION");
        var isCancelled = firstNonBlank(text(voucher, "ISCANCELLED"), text(voucher, "ISDELETED"), action);
        return isCancelled == null ? "" : isCancelled;
    }

    private Element parentElement(Element element, String tagName) {
        Node current = element.getParentNode();
        while (current != null) {
            if (current instanceof Element parent && tagName.equals(parent.getTagName())) {
                return parent;
            }
            current = current.getParentNode();
        }
        return null;
    }

    private Element nextElementSibling(Element element, String tagName) {
        Node current = element.getNextSibling();
        while (current != null) {
            if (current instanceof Element sibling) {
                return tagName.equals(sibling.getTagName()) ? sibling : null;
            }
            current = current.getNextSibling();
        }
        return null;
    }

    private Element firstElement(Element element, String tagName) {
        var nodes = element.getElementsByTagName(tagName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private record PurchaseRateResolution(String rate, String source) {
    }
}
