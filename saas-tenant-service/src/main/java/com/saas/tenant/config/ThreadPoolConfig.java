package com.saas.tenant.config;

import com.alibaba.ttl.TtlRunnable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ThreadPoolConfig {

    @Bean("tenantAsyncExecutor")
    public Executor tenantAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("tenant-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 🌟 核心魔法：使用 TaskDecorator 包装任务

        executor.setTaskDecorator(runnable -> {
            // 1. 使用 TTL 的 TtlRunnable 包装原始任务，自动捕获和透传 ThreadLocal
            return TtlRunnable.get(runnable);
        });



        executor.initialize();
        return executor;
    }
}