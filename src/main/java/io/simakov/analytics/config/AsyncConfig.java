package io.simakov.analytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    @Bean(name = "syncTaskExecutor")
    public Executor syncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("sync-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Bean(name = "mrProcessingExecutor")
    public Executor mrProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mr-sync-");
        // When queue is full, the calling thread executes the task — no tasks dropped for large repos
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }

    @Bean(name = "commitStatsExecutor")
    public Executor commitStatsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // I/O-bound: each thread blocks on a single GitLab HTTP call (GET /repository/commits/:sha).
        // 7 threads × ~5 calls/s ≈ 2100 req/min — stays within GitLab.com limit (2000 req/min).
        // Self-managed instances typically have higher or no rate limits, so this is conservative.
        executor.setCorePoolSize(7);
        executor.setMaxPoolSize(7);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("commit-stats-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}
