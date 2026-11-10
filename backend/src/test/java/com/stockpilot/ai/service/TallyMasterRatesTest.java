package com.stockpilot.ai.service;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class TallyMasterRatesTest {
    private Map<String,String> parse(String detail) {
        return new TallyXmlImportAdapter().parse(new ErpImportAdapter.ImportRequest(UUID.randomUUID(),UUID.randomUUID(),"master.xml",new ByteArrayInputStream(("<ENVELOPE><STOCKITEM NAME='Widget'><BASEUNITS>PCS</BASEUNITS><RATEOFVAT>12.5</RATEOFVAT>"+detail+"</STOCKITEM></ENVELOPE>").getBytes(StandardCharsets.UTF_8)))).rows().getFirst();
    }
    private String gst(String date,String components) {return "<GSTDETAILS.LIST><APPLICABLEFROM>"+date+"</APPLICABLEFROM>"+components+"</GSTDETAILS.LIST>";}
    private String rate(String head,String value) {return "<RATEDETAILS.LIST><GSTRATEDUTYHEAD>"+head+"</GSTRATEDUTYHEAD><GSTRATEVALUATIONTYPE>Based on Value</GSTRATEVALUATIONTYPE><GSTRATE>"+value+"</GSTRATE></RATEDETAILS.LIST>";}
    @Test void vatNeverBecomesPriceAndTaxComponentsAreAddedOnce() {
        var row=parse(gst("20250101",rate("CGST","9")+rate("SGST/UTGST","9")+rate("IGST","18")+rate("Cess","1")));
        assertThat(row.get("Sales Price")).isEmpty(); assertThat(row.get("VAT Rate Source Value")).isEqualTo("12.5");
        assertThat(row.get("GST %")).isEqualTo("18"); assertThat(row.get("Cess %")).isEqualTo("1"); assertThat(row).doesNotContainKey("GST Mapping Error");
    }
    @Test void latestEffectivePeriodSelectedAndFutureIgnored() {
        var row=parse(gst("20200101",rate("IGST","5"))+gst("20250101",rate("IGST","12"))+gst("20990101",rate("IGST","28")));
        assertThat(row.get("GST %")).isEqualTo("12"); assertThat(row.get("GST Effective Date")).isEqualTo("2025-01-01");
    }
    @Test void disagreementsAndIncompleteComponentsRequireReview() {
        assertThat(parse(gst("20250101",rate("CGST","9"))).get("GST Mapping Error")).contains("Both");
        assertThat(parse(gst("20250101",rate("CGST","9")+rate("SGST","9")+rate("IGST","12"))).get("GST Mapping Error")).contains("disagree");
        assertThat(parse(gst("20250101",rate("IGST","5"))+gst("20250101",rate("IGST","12"))).get("GST Mapping Error")).contains("Conflicting");
    }
    @Test void explicitStandardSellingPriceUsesApplicableDate() {
        var row=parse("<STANDARDPRICELIST.LIST><DATE>20250101</DATE><RATE>125.50/PCS</RATE></STANDARDPRICELIST.LIST><STANDARDPRICELIST.LIST><DATE>20990101</DATE><RATE>999/PCS</RATE></STANDARDPRICELIST.LIST>");
        assertThat(row.get("Sales Price")).isEqualTo("125.50");
    }
}
