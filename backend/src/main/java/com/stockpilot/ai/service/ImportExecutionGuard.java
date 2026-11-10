package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Lock order is tenant, then claimed job. The job row stays locked until the existing operation transaction ends. */
@Service
public class ImportExecutionGuard {
    private record Claim(UUID id,String worker) { }
    private static final ThreadLocal<Claim> CLAIM=new ThreadLocal<>();
    private final TenantWriteLockService locks;
    private final ImportJobService jobs;
    public ImportExecutionGuard(TenantWriteLockService locks,ImportJobService jobs){this.locks=locks;this.jobs=jobs;}
    public static void bind(UUID id,String worker){CLAIM.set(new Claim(id,worker));}
    public static void clear(){CLAIM.remove();}
    @Transactional(propagation=Propagation.MANDATORY)
    public void lockAndAuthorize(String operation,UUID sessionId){
        locks.lock(TenantContext.tenantId());
        var claim=CLAIM.get();
        if(claim!=null)jobs.fence(claim.id,claim.worker,operation,sessionId);
    }
}
