package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.SmartStagedCashbookEntry;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static com.stockpilot.ai.domain.DomainEnums.*;
import static com.stockpilot.ai.service.SmartImportCashbookMatchingService.*;
import static org.assertj.core.api.Assertions.assertThat;

class SmartImportCashbookMatchingServiceTest {
    private final Party party = new Party(SmartPartyType.CUSTOMER, null, "Customer A", "GST-A");
    private final LocalDate date = LocalDate.of(2026, 8, 3);

    @Test void paymentOwnNumberIsNotAnInvoiceReference() {
        var row = row("Cash");
        row.referenceNumber = "50";
        row.rawMetadataJson.put("Reference Number", "50");
        row.rawMetadataJson.put("Voucher Number", "50");
        assertThat(invoiceReference(row)).isNull();
        match(row, List.of(party), List.of(invoice("Customer A", "50", "200")));
        assertThat(row.cashbookMatchStatus).isEqualTo(CashbookMatchStatus.UNMATCHED_REVIEW);
        assertThat(ready(row)).isFalse();
    }

    @Test void bankLedgerCannotBecomeCustomerByNumberAlone() {
        var row = row("Example Bank-1044");
        row.rawMetadataJson.put("Invoice Reference", "50");
        match(row, List.of(party), List.of(invoice("Customer A", "50", "200")));
        assertThat(row.matchedStagedVoucherId).isNull();
        assertThat(ready(row)).isFalse();
    }

    @Test void referenceMustAgreeWithParty() {
        var row = row("Customer A");
        row.rawMetadataJson.put("Invoice Reference", "50");
        match(row, List.of(party), List.of(invoice("Customer B", "50", "200")));
        assertThat(row.cashbookMatchStatus).isEqualTo(CashbookMatchStatus.LOW_CONFIDENCE_REVIEW);
    }

    @Test void amountDateMatchRetainsExactStagedIdentityAndOriginalReference() {
        var row = row("Customer A");
        row.referenceNumber = "BANK-TRANSACTION-42";
        row.rawMetadataJson.put("Invoice Reference", "");
        var invoice = invoice("Customer A", "S-10", "200");
        match(row, List.of(party), List.of(invoice));
        assertThat(row.matchedStagedVoucherId).isEqualTo(invoice.stagedId());
        assertThat(row.matchedPartyName).isEqualTo("Customer A");
        assertThat(row.referenceNumber).isEqualTo("BANK-TRANSACTION-42");
        assertThat(ready(row)).isTrue();
    }

    @Test void partialPaymentNeedsActualReferenceAndCannotMatchFutureInvoice() {
        var row = row("Customer A");
        row.rawMetadataJson.put("Invoice Reference", "S-10");
        var invoice = invoice("Customer A", "S-10", "300");
        match(row, List.of(party), List.of(invoice));
        assertThat(ready(row)).isTrue();
        row.entryDate = date.minusDays(1);
        match(row, List.of(party), List.of(invoice));
        assertThat(ready(row)).isFalse();
    }

    @Test void repeatedInvoiceNumbersAreNotResolvedByFirstMatch() {
        var row = row("Customer A");
        row.rawMetadataJson.put("Invoice Reference", "S-10");
        match(row, List.of(party), List.of(invoice("Customer A", "S-10", "200"), invoice("Customer A", "S-10", "200")));
        assertThat(row.cashbookMatchStatus).isEqualTo(CashbookMatchStatus.LOW_CONFIDENCE_REVIEW);
    }

    @Test void gstinAliasesResolveToSameCounterparty() {
        var row = row("Customer A Alias");
        var alias = new Party(SmartPartyType.CUSTOMER, null, "Customer A Alias", "GST-A");
        match(row, List.of(party, alias), List.of(invoice("Customer A", "S-10", "200")));
        assertThat(ready(row)).isTrue();
    }

    @Test void unsupportedReferenceDoesNotInventInvoiceAllocation() {
        var row = row("Customer A");
        row.rawMetadataJson.put("Invoice Reference", "NOT-IMPORTED");
        match(row, List.of(party), List.of(invoice("Customer A", "S-10", "200")));
        assertThat(row.cashbookMatchStatus).isEqualTo(CashbookMatchStatus.MATCHED_CUSTOMER_PAYMENT);
        assertThat(row.matchedStagedVoucherId).isNull();
    }

    @Test void matchedLabelWithoutIdentityIsNotReady() {
        var row = row("Customer A");
        row.cashbookMatchStatus = CashbookMatchStatus.MATCHED_SALES_INVOICE;
        row.matchedPartyType = SmartPartyType.CUSTOMER;
        row.reviewStatus = SmartReviewStatus.AUTO_RESOLVED;
        assertThat(ready(row)).isFalse();
        row.matchedPartyName = "Customer A";
        assertThat(ready(row)).isFalse();
    }

    private SmartStagedCashbookEntry row(String name) {
        var row = new SmartStagedCashbookEntry();
        row.partyName = name;
        row.entryDate = date;
        row.amount = new BigDecimal("200");
        row.direction = SmartCashbookDirection.RECEIPT;
        return row;
    }

    private Invoice invoice(String name, String number, String amount) {
        return new Invoice(SmartPartyType.CUSTOMER, null, name, null, UUID.randomUUID(), number, date, new BigDecimal(amount));
    }
}
