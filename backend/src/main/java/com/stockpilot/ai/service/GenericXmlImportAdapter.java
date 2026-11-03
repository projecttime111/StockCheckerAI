package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GenericXmlImportAdapter implements ErpImportAdapter {
    @Override
    public DomainEnums.SourceType sourceType() {
        return DomainEnums.SourceType.XML;
    }

    @Override
    public ImportPreview parse(ImportRequest request) {
        try {
            var document = parseDocument(request.inputStream());
            document.getDocumentElement().normalize();
            var nodes = document.getDocumentElement().getChildNodes();
            var rows = new ArrayList<Map<String, String>>();
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i) instanceof Element element) {
                    rows.add(elementToRow(element));
                }
            }
            return new ImportPreview(rows);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not parse XML file: " + ex.getMessage(), ex);
        }
    }

    protected Document parseDocument(InputStream inputStream) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (var reader = sanitizingReader(inputStream)) {
            return factory.newDocumentBuilder().parse(new InputSource(reader));
        }
    }

    static Reader sanitizingReader(InputStream inputStream) throws java.io.IOException {
        var input = new PushbackInputStream(inputStream, 4);
        var prefix = input.readNBytes(4);
        if (prefix.length > 0) {
            input.unread(prefix);
        }
        return new SanitizingXmlReader(new InputStreamReader(input, detectXmlCharset(prefix)));
    }

    static String sanitizeInvalidXmlCharacters(byte[] bytes) {
        var xml = decodeXmlBytes(bytes);
        if (!xml.isEmpty() && xml.charAt(0) == '\uFEFF') {
            xml = xml.substring(1);
        }
        var firstTag = xml.indexOf('<');
        if (firstTag > 0 && xml.substring(0, firstTag).isBlank()) {
            xml = xml.substring(firstTag);
        }
        var output = new StringBuilder(xml.length());
        int index = 0;
        while (index < xml.length()) {
            var replacementEnd = invalidCharacterReferenceEnd(xml, index);
            if (replacementEnd > index) {
                output.append(' ');
                index = replacementEnd;
            } else {
                var codePoint = xml.codePointAt(index);
                output.append(isValidXml10CodePoint(codePoint) ? Character.toString(codePoint) : " ");
                index += Character.charCount(codePoint);
            }
        }
        return output.toString();
    }

    private static String decodeXmlBytes(byte[] bytes) {
        var charset = detectXmlCharset(bytes);
        return new String(bytes, charset);
    }

    static Charset detectXmlCharset(byte[] bytes) {
        if (bytes.length >= 2) {
            var first = bytes[0] & 0xFF;
            var second = bytes[1] & 0xFF;
            if (first == 0xFF && second == 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
            if (first == 0xFE && second == 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
            if (first == 0x3C && second == 0x00) {
                return StandardCharsets.UTF_16LE;
            }
            if (first == 0x00 && second == 0x3C) {
                return StandardCharsets.UTF_16BE;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static int invalidCharacterReferenceEnd(String xml, int start) {
        if (xml.charAt(start) != '&' || start + 3 >= xml.length() || xml.charAt(start + 1) != '#') {
            return -1;
        }
        int cursor = start + 2;
        boolean hex = false;
        if (cursor < xml.length() && (xml.charAt(cursor) == 'x' || xml.charAt(cursor) == 'X')) {
            hex = true;
            cursor++;
        }
        var digitStart = cursor;
        while (cursor < xml.length() && isReferenceDigit(xml.charAt(cursor), hex) && cursor - start <= 12) {
            cursor++;
        }
        if (cursor == digitStart) {
            return -1;
        }
        var hasTerminatingSemicolon = cursor < xml.length() && xml.charAt(cursor) == ';';
        try {
            var raw = xml.substring(digitStart, cursor);
            var codePoint = Integer.parseInt(raw, hex ? 16 : 10);
            if (isValidXml10CodePoint(codePoint)) {
                return -1;
            }
            return hasTerminatingSemicolon ? cursor + 1 : cursor;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static boolean isReferenceDigit(char value, boolean hex) {
        return (value >= '0' && value <= '9')
            || (hex && ((value >= 'a' && value <= 'f') || (value >= 'A' && value <= 'F')));
    }

    private static boolean isValidXml10CodePoint(int codePoint) {
        return codePoint == 0x9
            || codePoint == 0xA
            || codePoint == 0xD
            || (codePoint >= 0x20 && codePoint <= 0xD7FF)
            || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
            || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }

    private static final class SanitizingXmlReader extends Reader {
        private static final int PUSHBACK_SIZE = 32;
        private final PushbackReader input;
        private boolean firstCharacter = true;
        private int pendingCharacter = -1;

        private SanitizingXmlReader(Reader input) {
            this.input = new PushbackReader(input, PUSHBACK_SIZE);
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws java.io.IOException {
            if (length == 0) {
                return 0;
            }
            var count = 0;
            while (count < length) {
                var value = nextSanitizedCharacter();
                if (value < 0) {
                    return count == 0 ? -1 : count;
                }
                buffer[offset + count++] = (char) value;
            }
            return count;
        }

        private int nextSanitizedCharacter() throws java.io.IOException {
            var value = pendingCharacter >= 0 ? takePendingCharacter() : input.read();
            if (value < 0) {
                return -1;
            }
            if (firstCharacter) {
                firstCharacter = false;
                if (value == '\uFEFF') {
                    return nextSanitizedCharacter();
                }
            }
            if (value == '&') {
                return sanitizedCharacterReference();
            }
            if (Character.isHighSurrogate((char) value)) {
                var low = input.read();
                if (low >= 0 && Character.isLowSurrogate((char) low)) {
                    var codePoint = Character.toCodePoint((char) value, (char) low);
                    if (isValidXml10CodePoint(codePoint)) {
                        pendingCharacter = low;
                        return value;
                    }
                } else if (low >= 0) {
                    input.unread(low);
                }
                return ' ';
            }
            return isValidXml10CodePoint(value) ? value : ' ';
        }

        private int sanitizedCharacterReference() throws java.io.IOException {
            var consumed = new StringBuilder();
            var next = input.read();
            if (next != '#') {
                if (next >= 0) input.unread(next);
                return '&';
            }
            consumed.append((char) next);
            next = input.read();
            var hex = next == 'x' || next == 'X';
            if (hex) {
                consumed.append((char) next);
                next = input.read();
            }
            var digits = new StringBuilder();
            while (next >= 0 && isReferenceDigit((char) next, hex) && digits.length() < 10) {
                consumed.append((char) next);
                digits.append((char) next);
                next = input.read();
            }
            if (next == ';') {
                consumed.append(';');
            } else if (next >= 0) {
                input.unread(next);
            }
            if (!digits.isEmpty()) {
                try {
                    var codePoint = Integer.parseInt(digits.toString(), hex ? 16 : 10);
                    if (!isValidXml10CodePoint(codePoint)) {
                        return ' ';
                    }
                } catch (NumberFormatException ignored) {
                    // Preserve malformed references so the XML parser reports them normally.
                }
            }
            var suffix = consumed.toString().toCharArray();
            for (var index = suffix.length - 1; index >= 0; index--) {
                input.unread(suffix[index]);
            }
            return '&';
        }

        private int takePendingCharacter() {
            var value = pendingCharacter;
            pendingCharacter = -1;
            return value;
        }

        @Override
        public void close() throws java.io.IOException {
            input.close();
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

    protected Map<String, String> elementToRow(Element element) {
        var row = new LinkedHashMap<String, String>();
        row.put("node", element.getTagName());
        if (element.hasAttribute("NAME")) {
            row.put("name", element.getAttribute("NAME"));
        }
        var children = element.getChildNodes();
        for (int c = 0; c < children.getLength(); c++) {
            if (children.item(c) instanceof Element child) {
                row.put(child.getTagName(), child.getTextContent().trim());
            }
        }
        return row;
    }
}
