package com.stockpilot.ai.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;

final class ImportDates {
    private ImportDates() { }
    private static final List<DateTimeFormatter> FORMATS = java.util.stream.Stream.of(
        "uuuuMMdd", "uuuu-MM-dd", "d-M-uuuu", "d/M/uuuu", "d-MMM-uuuu", "d-MMM-uu", "d MMM uuuu", "d MMM uu")
        .map(pattern -> new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
            .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)).toList();
    static LocalDate parse(String value) {
        if (value == null || value.isBlank()) return null;
        var text = value.trim();
        if (text.matches("\\d{4}-\\d{2}-\\d{2}T.*")) text = text.substring(0, 10);
        for (var formatter : FORMATS) {
            try { return LocalDate.parse(text, formatter); } catch (java.time.format.DateTimeParseException ignored) { }
        }
        return null;
    }
}
