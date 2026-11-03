package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.exception.ApiException;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CashbookBulkRetailService {
    private static final Logger log = LoggerFactory.getLogger(CashbookBulkRetailService.class);

    private final Repositories.ImportSessionRepository sessions;
    private final Repositories.SmartStagedCashbookEntryRepository cashbook;
    private final CashbookReviewService reviews;
    private final AuditService audit;

    public CashbookBulkRetailService(Repositories.ImportSessionRepository sessions,
        Repositories.SmartStagedCashbookEntryRepository cashbook,
        CashbookReviewService reviews, AuditService audit) {
        this.sessions = sessions;
        this.cashbook = cashbook;
        this.reviews = reviews;
        this.audit = audit;
    }

    public Map<String, Object> record(UUID sessionId, SmartImportDtos.BulkRetailSaleResolutionRequest request) {
        var tenantId = TenantContext.tenantId();
        sessions.findByTenantIdAndId(tenantId, sessionId)
            .orElseThrow(() -> ApiErrors.notFound("Import session not found"));
        var entryIds = new ArrayList<>(new LinkedHashSet<>(request.entryIds()));
        if (entryIds.size() != request.entryIds().size()) {
            throw ApiErrors.badRequest("Each selected cashbook entry must appear only once");
        }
        var expectedConfirmation = "RECORD " + entryIds.size() + " RETAIL SALES";
        if (!expectedConfirmation.equals(request.confirmation())) {
            throw ApiErrors.badRequest("Confirm this exact bulk selection before recording retail sales");
        }
        if (!Boolean.TRUE.equals(request.receivedFromRetailSales())
            || !Boolean.TRUE.equals(request.notAlreadyRecorded())) {
            throw ApiErrors.badRequest("Confirm the selected entries are retail money received and are not already recorded");
        }

        var rowRequest = new SmartImportDtos.CashbookReviewResolutionRequest(
            DomainEnums.CashbookResolutionAction.RECORD_RETAIL_SALE,
            null,
            "RECORD RETAIL SALES",
            request.note(),
            request.collectionMethod(),
            true,
            true
        );
        var results = new ArrayList<Map<String, Object>>();
        var recorded = 0;
        var duplicates = 0;
        var failed = 0;
        var recordedAmount = BigDecimal.ZERO;
        var acceptedAmount = BigDecimal.ZERO;

        for (var entryId : entryIds) {
            var source = cashbook.findByTenantIdAndImportSessionIdAndId(tenantId, sessionId, entryId).orElse(null);
            var sourceRowNumber = source == null ? null : source.sourceRowNumber;
            var sourceAmount = source == null ? null : source.amount;
            try {
                var outcome = reviews.resolve(sessionId, entryId, rowRequest);
                var duplicate = Boolean.TRUE.equals(outcome.get("duplicateRetailSaleSkipped"));
                var amount = outcome.get("amount") instanceof BigDecimal value ? value : BigDecimal.ZERO;
                acceptedAmount = acceptedAmount.add(amount);
                if (duplicate) {
                    duplicates++;
                } else {
                    recorded++;
                    recordedAmount = recordedAmount.add(amount);
                }
                results.add(result(entryId, duplicate ? "DUPLICATE_SKIPPED" : "RECORDED",
                    duplicate ? "This source entry was already recorded." : "Retail counter sale recorded.",
                    outcome.getOrDefault("sourceRowNumber", sourceRowNumber), amount,
                    outcome.get("retailCounterSaleId"), null));
            } catch (ApiException ex) {
                failed++;
                results.add(result(entryId, "FAILED", ex.getMessage(), sourceRowNumber,
                    sourceAmount, null, ex.errorCode));
            } catch (RuntimeException ex) {
                failed++;
                log.error("Bulk retail recording failed for session {} and entry {}", sessionId, entryId, ex);
                results.add(result(entryId, "FAILED", "This entry could not be recorded. Review it individually.",
                    sourceRowNumber, sourceAmount, null, "INTERNAL_ERROR"));
            }
        }

        var summary = new LinkedHashMap<String, Object>();
        summary.put("requestedCount", entryIds.size());
        summary.put("recordedCount", recorded);
        summary.put("duplicateCount", duplicates);
        summary.put("failedCount", failed);
        summary.put("recordedAmount", recordedAmount);
        summary.put("acceptedAmount", acceptedAmount);
        summary.put("collectionMethod", request.collectionMethod());
        summary.put("partialSuccess", failed > 0 && recorded + duplicates > 0);
        summary.put("results", results);
        audit.logCurrent("CASHBOOK_BULK_RETAIL_RESOLVED", "ImportSession", sessionId, Map.of(
            "requestedCount", entryIds.size(), "recordedCount", recorded, "duplicateCount", duplicates,
            "failedCount", failed, "recordedAmount", recordedAmount,
            "collectionMethod", request.collectionMethod()));
        return summary;
    }

    private Map<String, Object> result(UUID entryId, String status, String message, Object sourceRowNumber,
        Object amount, Object retailCounterSaleId, Object errorCode) {
        var result = new LinkedHashMap<String, Object>();
        result.put("entryId", entryId);
        result.put("status", status);
        result.put("message", message);
        result.put("sourceRowNumber", sourceRowNumber);
        result.put("amount", amount);
        result.put("retailCounterSaleId", retailCounterSaleId);
        result.put("errorCode", errorCode);
        return result;
    }
}
