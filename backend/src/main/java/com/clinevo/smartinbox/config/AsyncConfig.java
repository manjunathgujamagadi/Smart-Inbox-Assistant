package com.clinevo.smartinbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * The "simple in-process queue" for document processing: bounded worker pool
 * consuming ProcessingJob rows, instead of synchronous request/response.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "processingExecutor")
    public Executor processingExecutor(AppProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int poolSize = Math.max(1, props.queue().workerPoolSize());
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("doc-processor-");
        executor.initialize();
        return executor;
    }
}
