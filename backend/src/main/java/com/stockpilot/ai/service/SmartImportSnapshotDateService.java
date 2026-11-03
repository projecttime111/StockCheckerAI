package com.stockpilot.ai.service;

import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class SmartImportSnapshotDateService {
    public static final int POLICY_VERSION = 1;
    private final Repositories.SmartStagedStockSnapshotRepository stock;
    private final Repositories.SmartStagedPartyRepository parties;

    public SmartImportSnapshotDateService(Repositories.SmartStagedStockSnapshotRepository stock,
                                         Repositories.SmartStagedPartyRepository parties) {
        this.stock = stock;
        this.parties = parties;
    }

    public Selection fromRequest(SmartImportDtos.DryRunRequest request) {
        return request == null ? new Selection(null, false, null, false)
            : new Selection(request.snapshotDate(), Boolean.TRUE.equals(request.snapshotDateConfirmed()),
                request.outstandingSnapshotDate(), Boolean.TRUE.equals(request.outstandingSnapshotDateConfirmed()));
    }

    public Selection fromSummary(Map<String, Object> summary) {
        if (!Integer.toString(POLICY_VERSION).equals(Objects.toString(summary.get("snapshotDatePolicyVersion"), ""))) {
            throw ApiErrors.badRequest("Run a new dry run with confirmed snapshot dates before committing this preview");
        }
        return new Selection(parse(summary.get("requestedSnapshotDate")),
            Boolean.TRUE.equals(summary.get("snapshotDateConfirmed")),
            parse(summary.get("outstandingSnapshotDate")),
            Boolean.TRUE.equals(summary.get("outstandingSnapshotDateConfirmed")));
    }

    public void validateSession(UUID tenantId, UUID sessionId, Selection dates) {
        stock.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId)
            .forEach(row -> dates.stockDate(row.snapshotDate));
        parties.findByTenantIdAndImportSessionIdOrderBySourceRowNumberAsc(tenantId, sessionId).stream()
            .filter(row -> row.sourceType == DomainEnums.DetectedFileType.DEBTOR_CREDITOR_ANALYSIS)
            .forEach(row -> dates.outstandingDate(row.snapshotDate));
    }

    private static LocalDate parse(Object value) {
        var text = Objects.toString(value, "");
        try {
            return text.isBlank() ? null : LocalDate.parse(text);
        } catch (DateTimeParseException ex) {
            throw ApiErrors.badRequest("The preview contains an invalid snapshot date. Run a new dry run.");
        }
    }

    public record Selection(LocalDate stockDate, boolean stockConfirmed,
                            LocalDate outstandingDate, boolean outstandingConfirmed) {
        public Selection {
            validatePair(stockDate, stockConfirmed, "closing-stock");
            validatePair(outstandingDate, outstandingConfirmed, "outstanding");
        }

        private static void validatePair(LocalDate date, boolean confirmed, String label) {
            if ((date != null) != confirmed) {
                throw ApiErrors.badRequest("Enter and confirm the " + label + " as-of date before running a dry run");
            }
        }

        public LocalDate stockDate(LocalDate sourceDate) {
            return required(sourceDate, stockDate, "closing-stock");
        }

        public LocalDate outstandingDate(LocalDate sourceDate) {
            return required(sourceDate, outstandingDate, "outstanding");
        }

        private LocalDate required(LocalDate sourceDate, LocalDate confirmedDate, String label) {
            // Dates parsed from the export take precedence; manual dates fill missing evidence only.
            if (sourceDate != null) return sourceDate;
            if (confirmedDate != null) return confirmedDate;
            throw ApiErrors.badRequest("The export has undated " + label
                + " rows. Enter and confirm their as-of date in Dry Run.");
        }

        public Map<String, Object> summary() {
            return Map.of("snapshotDatePolicyVersion", POLICY_VERSION,
                "requestedSnapshotDate", stockDate == null ? "" : stockDate.toString(),
                "snapshotDateConfirmed", stockConfirmed,
                "outstandingSnapshotDate", outstandingDate == null ? "" : outstandingDate.toString(),
                "outstandingSnapshotDateConfirmed", outstandingConfirmed);
        }

        public String fingerprint() {
            return POLICY_VERSION + "|" + stockDate + "|" + stockConfirmed + "|"
                + outstandingDate + "|" + outstandingConfirmed;
        }
    }
}
