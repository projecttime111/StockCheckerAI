package com.stockpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.ImportJob;
import com.stockpilot.ai.domain.DomainEnums;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.exception.ApiException;
import com.stockpilot.ai.repo.ImportJobRepository;
import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import jakarta.validation.Validator;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class ImportJobService {
    private final ImportJobRepository jobs;
    private final Repositories.ImportSessionRepository sessions;
    private final Repositories.MembershipRepository memberships;
    private final Repositories.UserRepository users;
    private final Repositories.TenantRepository tenants;
    private final PermissionService permissions;
    private final TenantWriteLockService locks;
    private final ObjectMapper json;
    private final Validator validator;
    private final AuditService audit;
    public ImportJobService(ImportJobRepository jobs, Repositories.ImportSessionRepository sessions,
        Repositories.MembershipRepository memberships, Repositories.UserRepository users,
        Repositories.TenantRepository tenants, PermissionService permissions, TenantWriteLockService locks,
        ObjectMapper json, Validator validator, AuditService audit) {
        this.jobs=jobs; this.sessions=sessions; this.memberships=memberships; this.users=users;
        this.tenants=tenants; this.permissions=permissions; this.locks=locks; this.json=json; this.validator=validator; this.audit=audit;
    }

    @Transactional
    public Map<String,Object> submit(UUID sessionId, String operation, UUID requestId, Map<String,Object> payload) {
        String op=operation==null?"":operation.toUpperCase(Locale.ROOT);
        if (!Set.of("CLASSIFY","STAGE","DRY_RUN","COMMIT").contains(op)) throw ApiErrors.badRequest("Unsupported import operation");
        requirePermission(op);
        if (requestId==null) throw ApiErrors.badRequest("A request ID is required for retry-safe imports");
        var tenant=TenantContext.tenantId(); locks.lock(tenant); requireSession(tenant,sessionId);
        var input=payload==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(payload);
        if (op.equals("COMMIT")) convert(input,SmartImportDtos.CommitRequest.class);
        if (op.equals("DRY_RUN")) convert(input,SmartImportDtos.DryRunRequest.class);
        if ((op.equals("CLASSIFY")||op.equals("STAGE"))&&!input.isEmpty()) throw ApiErrors.badRequest("This operation does not accept a payload");
        String hash=hash(sessionId,op,input);
        var prior=jobs.findByTenantIdAndRequestId(tenant,requestId).orElse(null);
        if (prior!=null) {
            if (!prior.requestHash.equals(hash)) throw ApiErrors.conflict("This request ID was already used for different import data");
            return response(prior);
        }
        var active=jobs.findByTenantIdAndStatusIn(tenant,List.of("QUEUED","RUNNING"));
        if (!active.isEmpty()) {
            var job=active.getFirst();
            if (job.requestHash.equals(hash)&&job.requestedBy.equals(TenantContext.userId())) return response(job);
            throw ApiErrors.conflict("Another import operation is still processing in this workspace. Wait for it to finish.");
        }
        var job=new ImportJob(); job.tenantId=tenant; job.importSessionId=sessionId; job.requestId=requestId;
        job.requestedBy=TenantContext.userId(); job.createdBy=TenantContext.userId(); job.requestHash=hash;
        job.operation=op; job.payload=input; job.message="Waiting to process "+op.toLowerCase(Locale.ROOT).replace('_',' ');
        jobs.saveAndFlush(job); audit.logCurrent("IMPORT_JOB_QUEUED","ImportJob",job.id,Map.of("operation",op,"sessionId",sessionId));
        return response(job);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(UUID sessionId) {
        requireSession(TenantContext.tenantId(),sessionId);
        return jobs.findByTenantIdAndImportSessionIdOrderByCreatedAtDesc(TenantContext.tenantId(),sessionId,PageRequest.of(0,20)).stream().map(this::response).toList();
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(UUID sessionId,UUID id) {
        return response(jobs.findByTenantIdAndImportSessionIdAndId(TenantContext.tenantId(),sessionId,id).orElseThrow(()->ApiErrors.notFound("Import job not found")));
    }
    @Transactional
    public ImportJob claim(String worker) {
        var candidates=jobs.claimable(Instant.now(),1);
        if(candidates.isEmpty())return null;
        var job=candidates.getFirst();
        if(job.attempts>=3){job.status="FAILED";job.error="Processing was interrupted repeatedly. Review the import and try again.";job.message=job.error;job.leaseUntil=null;return null;}
        job.status="RUNNING";job.workerId=worker;job.leaseUntil=Instant.now().plusSeconds(120);job.attempts++;
        job.progress=10;job.message="Processing "+job.operation.toLowerCase(Locale.ROOT).replace('_',' ');job.error=null;
        return jobs.saveAndFlush(job);
    }
    @Transactional
    public void heartbeat(UUID id,String worker) {
        jobs.locked(id).filter(j->"RUNNING".equals(j.status)&&worker.equals(j.workerId)).ifPresent(j->j.leaseUntil=Instant.now().plusSeconds(120));
    }
    @Transactional(readOnly=true)
    public void authorize(ImportJob job) {
        if(tenants.findByIdAndStatus(job.tenantId,DomainEnums.TenantStatus.ACTIVE).isEmpty()
            || users.findById(job.requestedBy).filter(u->u.active&&u.emailVerified).isEmpty()) throw ApiErrors.forbidden("The requesting account or workspace is no longer active");
        var member=memberships.findByTenantIdAndUserId(job.tenantId,job.requestedBy).orElseThrow(()->ApiErrors.forbidden("Workspace access was removed"));
        TenantContext.set(job.tenantId,job.requestedBy,member.role);
        requirePermission(job.operation); requireSession(job.tenantId,job.importSessionId);
    }
    /** Called inside the operation transaction after its tenant lock; holds the job lock through commit. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void fence(UUID id,String worker,String operation,UUID sessionId) {
        var job=jobs.locked(id).orElseThrow(()->ApiErrors.conflict("Import job is no longer available"));
        if(!TenantContext.tenantId().equals(job.tenantId)||!sessionId.equals(job.importSessionId)
            ||!operation.equals(job.operation)||!"RUNNING".equals(job.status)||!worker.equals(job.workerId)
            ||job.leaseUntil==null||!job.leaseUntil.isAfter(Instant.now()))
            throw ApiErrors.conflict("This worker no longer owns the import job");
        // Re-read current account/membership after waiting for serialization; queued permissions are not grants.
        authorize(job);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    // Long operations hold this row, so heartbeat cannot update it. Renew before releasing
                    // the transaction lock to leave time for finish() and avoid a spurious lease takeover.
                    job.leaseUntil=Instant.now().plusSeconds(120);
                }
            });
    }
    @Transactional
    public void finish(UUID id,String worker,Map<String,Object> result,Throwable failure) {
        var job=jobs.locked(id).filter(j->worker.equals(j.workerId)&&"RUNNING".equals(j.status)).orElse(null);
        if(job==null)return;
        job.workerId=null;job.leaseUntil=null;
        if(failure==null){job.status="SUCCEEDED";job.progress=100;job.message="Processing complete";job.result=new LinkedHashMap<>(result);}
        else if(!(failure instanceof ApiException)&&job.attempts<3){job.status="QUEUED";job.leaseUntil=Instant.now().plusSeconds(10L*job.attempts);job.message="Processing interrupted; retrying safely";}
        else {job.status="FAILED";job.error=failure instanceof ApiException?bounded(failure.getMessage()):"Import processing failed. Review the import and retry; no partial commit is accepted.";job.message=job.error;}
        audit.log(job.tenantId,job.requestedBy,"IMPORT_JOB_"+job.status,"ImportJob",job.id,Map.of("operation",job.operation,"attempts",job.attempts));
    }
    public <T>T convert(Map<String,Object> payload,Class<T> type){
        final T value;
        try{value=json.convertValue(payload,type);}catch(IllegalArgumentException e){throw ApiErrors.badRequest("Invalid import operation payload");}
        var violations=validator.validate(value);
        if(!violations.isEmpty())throw ApiErrors.badRequest(violations.iterator().next().getMessage());
        return value;
    }
    private void requirePermission(String operation){if(!permissions.has(operation.equals("COMMIT")?"imports.commit":"imports.validate"))throw ApiErrors.forbidden("You do not have permission to run this import operation");}
    private void requireSession(UUID tenant,UUID id){if(sessions.findByTenantIdAndId(tenant,id).isEmpty())throw ApiErrors.notFound("Import session not found");}
    private String hash(UUID session,String operation,Map<String,Object> payload){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((session+":"+operation+":"+json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(payload)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Cannot fingerprint import request",e);}}
    private static String bounded(String message){return message==null?"Import processing failed":message.substring(0,Math.min(250,message.length()));}
    private Map<String,Object> response(ImportJob j){
        var result=new LinkedHashMap<String,Object>(); result.put("id",j.id);result.put("sessionId",j.importSessionId);result.put("operation",j.operation);
        result.put("status",j.status);result.put("progress",j.progress);result.put("message",j.message);result.put("error",j.error);
        result.put("result",j.result);result.put("attempts",j.attempts);result.put("createdAt",j.createdAt);result.put("updatedAt",j.updatedAt);return result;
    }
}
