package com.stockpilot.ai.config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
@Configuration @EnableScheduling
@ConditionalOnProperty(name="app.import-jobs.enabled",havingValue="true",matchIfMissing=true)
public class ImportJobConfiguration {
    @Bean public ThreadPoolTaskScheduler taskScheduler(){
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("import-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(30);return scheduler;
    }
}
