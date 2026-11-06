package com.stockpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.MutationReceipt;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.repo.MutationReceiptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    private final MutationReceiptRepository receipts;
    private final TenantWriteLockService locks;
    private final ObjectMapper json;
    public IdempotencyService(MutationReceiptRepository receipts, TenantWriteLockService locks, ObjectMapper json) {
        this.receipts = receipts; this.locks = locks; this.json = json;
    }
    @Transactional
    public <T> T execute(String operation, String key, Object request, Supplier<UUID> create, Function<UUID,T> read) {
        var tenantId = TenantContext.tenantId();
        locks.lock(tenantId);
        if (key == null || key.isBlank()) return read.apply(create.get());
        if (!key.matches("[A-Za-z0-9._:-]{1,128}")) throw ApiErrors.badRequest("Invalid Idempotency-Key");
        final String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(request))); }
        catch (Exception ex) { throw new IllegalStateException("Cannot fingerprint operation", ex); }
        var prior = receipts.findByTenantIdAndUserIdAndOperationAndRequestKey(tenantId, TenantContext.userId(), operation, key);
        if (prior.isPresent()) {
            if (!hash.equals(prior.get().requestHash)) throw ApiErrors.conflict("Idempotency-Key was already used for different details");
            return read.apply(prior.get().resultId);
        }
        var resultId = create.get();
        var receipt = new MutationReceipt();
        receipt.tenantId = tenantId; receipt.userId = TenantContext.userId(); receipt.operation = operation;
        receipt.requestKey = key; receipt.requestHash = hash; receipt.resultId = resultId;
        receipts.save(receipt);
        return read.apply(resultId);
    }
}
