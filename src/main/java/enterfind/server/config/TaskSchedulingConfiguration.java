package enterfind.server.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configuration for async task execution.
 * Configures a thread pool for @Async annotated methods like Scanner.scanAsync().
 */
@Configuration
@EnableAsync
public class TaskSchedulingConfiguration implements AsyncConfigurer {
    
    /**
     * Configure the executor for async task execution.
     * Uses a thread pool with core size based on available processors
     * to handle concurrent scans efficiently.
     */
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        executor.setMaxPoolSize(Math.max(2, Runtime.getRuntime().availableProcessors()));
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("scan-async-");
        executor.initialize();
        return executor;
    }
}
