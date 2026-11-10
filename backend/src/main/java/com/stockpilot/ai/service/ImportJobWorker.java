package com.stockpilot.ai.service;

import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.exception.ApiErrors;
import com.stockpilot.ai.web.dto.SmartImportDtos;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Component @ConditionalOnProperty(name="app.import-jobs.enabled",havingValue="true",matchIfMissing=true)
public class ImportJobWorker {
    private final String worker=UUID.randomUUID().toString();
    private final AtomicReference<UUID> active=new AtomicReference<>();
    private final ImportJobService jobs;
    private final SmartImportSessionService sessions;
    private final SmartImportStagingService staging;
    private final SmartImportDryRunService dryRuns;
    private final SmartImportCommitService commits;
    private final ForecastService forecasts;
    public ImportJobWorker(ImportJobService jobs,SmartImportSessionService sessions,SmartImportStagingService staging,
        SmartImportDryRunService dryRuns,SmartImportCommitService commits,ForecastService forecasts){
        this.jobs=jobs;this.sessions=sessions;this.staging=staging;this.dryRuns=dryRuns;this.commits=commits;this.forecasts=forecasts;
    }
    @Scheduled(fixedDelayString="${app.import-jobs.poll-ms:1500}")
    public void process(){
        var job=jobs.claim(worker);if(job==null)return;
        active.set(job.id);Map<String,Object> result=new LinkedHashMap<>();Throwable failure=null;
        try{
            jobs.authorize(job);
            ImportExecutionGuard.bind(job.id,worker);
            switch(job.operation){
                case "CLASSIFY" -> {sessions.classify(job.importSessionId);result.put("sessionId",job.importSessionId);result.put("status",sessions.get(job.importSessionId).status);}
                case "STAGE" -> {var staged=staging.stage(job.importSessionId);result.put("sessionId",job.importSessionId);result.put("summary",staged.getOrDefault("summary",Map.of()));}
                case "DRY_RUN" -> result.putAll(dryRuns.run(job.importSessionId,jobs.convert(job.payload,SmartImportDtos.DryRunRequest.class)));
                case "COMMIT" -> {
                    result.putAll(commits.commit(job.importSessionId,jobs.convert(job.payload,SmartImportDtos.CommitRequest.class)));
                    if("FAILED".equals(String.valueOf(result.get("status"))))throw ApiErrors.conflict("Import commit failed. Review its result before retrying.");
                    try{forecasts.run();result.put("analyticsRefresh","SUCCEEDED");}catch(Exception e){result.put("analyticsRefresh","FAILED");result.put("analyticsWarning","Import committed. Forecast refresh failed; rerun forecasts before relying on them.");}
                }
                default -> throw ApiErrors.badRequest("Unsupported import operation");
            }
        }catch(Exception e){failure=e;}
        finally{try{jobs.finish(job.id,worker,result,failure);}finally{active.set(null);ImportExecutionGuard.clear();TenantContext.clear();}}
    }
    @Scheduled(fixedDelay=15000)
    public void heartbeat(){var id=active.get();if(id!=null)jobs.heartbeat(id,worker);}
}
