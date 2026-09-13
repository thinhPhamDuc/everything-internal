package com.app.internal.sync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

// Task 9: executor RIÊNG cho việc fetch provider - KHÔNG dùng chung
// ForkJoinPool.commonPool() (có thể bị Tomcat/tác vụ nền khác tranh thread,
// và commonPool() vốn tối ưu cho tác vụ CPU-bound ngắn, không hợp cho I/O
// chờ HTTP có thể "treo" lâu như provider-b/provider-c).
@Configuration
public class SyncFetchExecutorConfig {

    @Bean(name = "syncProviderFetchExecutor", destroyMethod = "shutdown")
    public ExecutorService syncProviderFetchExecutor(SyncProviderProperties properties) {
        int poolSize = Math.max(1, properties.providers().size());
        return Executors.newFixedThreadPool(poolSize, new SyncFetchThreadFactory());
    }

    // Đặt tên thread rõ ràng ("sync-provider-fetch-0", "-1"...) để dễ nhận ra
    // trong log/thread dump/Tempo trace khi debug 1 provider bị treo.
    private static class SyncFetchThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "sync-provider-fetch-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
