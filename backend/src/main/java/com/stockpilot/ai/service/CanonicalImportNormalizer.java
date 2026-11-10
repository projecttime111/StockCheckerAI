package com.stockpilot.ai.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Source-independent, additive provenance. Original source fields are retained verbatim. */
public final class CanonicalImportNormalizer {
    public static final String VERSION = "2";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private CanonicalImportNormalizer() { }
    public static List<Map<String,String>> normalize(List<Map<String,String>> rows, String source) {
        var result = new ArrayList<Map<String,String>>();
        int number = 0;
        for (var original : rows) {
            var row = new LinkedHashMap<>(original);
            row.putIfAbsent("Source System", source);
            row.putIfAbsent("Source Company", first(original,"Company Name","Company","COMPANYNAME"));
            row.putIfAbsent("Source Record ID", first(original,"Tally GUID","GUID","External ID","Record ID","MASTERID"));
            row.putIfAbsent("Source Revision", first(original,"ALTERID","Revision","Version"));
            row.put("Canonical Schema Version", VERSION);
            row.put("Source Row", Integer.toString(++number));
            row.put("Canonical Row Hash", hashValues(new TreeMap<>(original)));
            result.add(row);
        }
        return result;
    }
    private static String first(Map<String,String> row,String... names) {
        for(var name:names) for(var entry:row.entrySet()) if(entry.getKey().equalsIgnoreCase(name)&&entry.getValue()!=null&&!entry.getValue().isBlank()) return entry.getValue();
        return "";
    }
    public static String encoded(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("Canonical source content is not serializable",ex); }
    }
    public static String hashValues(Object value) { return hash(encoded(value)); }
    public static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
