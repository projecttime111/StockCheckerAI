package com.stockpilot.ai.service;

import org.w3c.dom.Element;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** Reads named tax components, never concatenated list text or VAT as a sale price. */
final class TallyMasterRates {
    private TallyMasterRates() { }
    static void apply(Element item, Map<String,String> row, LocalDate asOf) {
        row.put("Sales Price", "");
        var rates = item.getElementsByTagName("STANDARDPRICELIST.LIST");
        Element latestPrice = null; LocalDate priceDate = LocalDate.MIN;
        for (int i=0; i<rates.getLength(); i++) {
            var element=(Element)rates.item(i); var date=date(element);
            if (date!=null && !date.isAfter(asOf) && !date.isBefore(priceDate)) {
                latestPrice=element; priceDate=date;
            }
        }
        if (latestPrice!=null) {
            var value=text(latestPrice,"RATE");
            var match=java.util.regex.Pattern.compile("^[\\s]*([+-]?[0-9,]+(?:\\.[0-9]+)?)").matcher(value);
            if (match.find()) row.put("Sales Price", match.group(1).replace(",",""));
            row.put("Sales Price Source", "STANDARDPRICELIST.LIST");
            row.put("Sales Price Effective Date", priceDate.equals(LocalDate.MIN)?"":priceDate.toString());
        }
        row.put("VAT Rate Source Value", text(item,"RATEOFVAT"));
        row.put("Master Rates As Of",asOf.toString());
        var details=item.getElementsByTagName("GSTDETAILS.LIST");
        var selected=new ArrayList<Element>(); LocalDate latest=LocalDate.MIN;
        for(int i=0;i<details.getLength();i++) {
            var detail=(Element)details.item(i); var date=date(detail);
            if(date==null) { row.put("GST Mapping Error","Unrecognized GST effective date"); continue; }
            if(date.isAfter(asOf)) continue;
            if(date.isAfter(latest)) { selected.clear(); latest=date; }
            if(date.equals(latest)) selected.add(detail);
        }
        if(details.getLength()==0) selected.add(item);
        var values=new LinkedHashSet<String>(); var evidence=new ArrayList<String>();
        for(var detail:selected) {
            var components=new LinkedHashMap<String,BigDecimal>();
            var lists=detail.getElementsByTagName("RATEDETAILS.LIST");
            for(int i=0;i<lists.getLength();i++) {
                var rate=(Element)lists.item(i); var head=text(rate,"GSTRATEDUTYHEAD").toUpperCase(Locale.ROOT);
                var raw=text(rate,"GSTRATE"); evidence.add(head+"="+raw);
                if(raw.isBlank()) continue;
                BigDecimal value;
                try { value=new BigDecimal(raw.trim().replace("%", "")); }
                catch(NumberFormatException ex) { row.put("GST Mapping Error","Invalid GST rate: "+raw); continue; }
                if(value.signum()<0 || value.compareTo(BigDecimal.valueOf(100))>0) row.put("GST Mapping Error","GST component outside 0–100");
                String key=head.contains("IGST")?"IGST":head.contains("CGST")?"CGST":head.contains("SGST")||head.contains("UTGST")?"SGST":head.contains("CESS")?"CESS":head.equals("GST")||head.isBlank()?"GST":null;
                if(key==null) { row.put("GST Mapping Error","Unknown GST duty head: "+head); continue; }
                var previous=components.putIfAbsent(key,value);
                if(previous!=null && previous.compareTo(value)!=0) row.put("GST Mapping Error","Conflicting GST duty rates");
            }
            var integrated=components.get("IGST"); var combined=components.containsKey("CGST")&&components.containsKey("SGST")
                ?components.get("CGST").add(components.get("SGST")):null;
            if((components.containsKey("CGST") != components.containsKey("SGST"))) row.put("GST Mapping Error","Both CGST and SGST/UTGST are required");
            if(integrated!=null && combined!=null && integrated.compareTo(combined)!=0) row.put("GST Mapping Error","IGST and CGST+SGST disagree");
            var value=integrated!=null?integrated:combined!=null?combined:components.get("GST");
            if(value!=null) values.add(value.stripTrailingZeros().toPlainString());
            if(components.containsKey("CESS")) row.put("Cess %",components.get("CESS").toPlainString());
        }
        if(values.size()>1) row.put("GST Mapping Error","Conflicting GST rates on the same effective date");
        row.put("GST %", values.size()==1?values.iterator().next():"");
        row.put("GST Rate Evidence",String.join("; ",evidence));
        row.put("GST Effective Date",latest.equals(LocalDate.MIN)?"":latest.toString());
    }
    private static LocalDate date(Element e) {
        var raw=text(e,"APPLICABLEFROM"); if(raw.isBlank()) raw=text(e,"DATE");
        return raw.isBlank()?LocalDate.MIN:ImportDates.parse(raw);
    }
    private static String text(Element e,String tag) {
        var nodes=e.getElementsByTagName(tag); return nodes.getLength()==0?"":nodes.item(0).getTextContent().trim();
    }
}
