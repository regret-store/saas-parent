package com.saas.tenant.config;

import com.saas.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ThreadPoolConfig 用了 TtlRunnable 做 TaskDecorator，
 * TenantContext 用的是 TransmittableThreadLocal。这里验证两者的配合，
 * 重点验证"提交时没有上下文，就不该看到上一个人的租户"。
 */
class ThreadPoolTenantContextTest {

    private final ThreadPoolTaskExecutor executor =
            (ThreadPoolTaskExecutor) new ThreadPoolConfig().tenantAsyncExecutor();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        executor.shutdown();
    }

    private String runAndCapture(Runnable body) throws InterruptedException {
        AtomicReference<String> seen = new AtomicReference<>("任务没执行");
        CountDownLatch latch = new CountDownLatch(1);
        executor.execute(() -> {
            body.run();
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "异步任务没跑起来，检查 tenantAsyncExecutor");
        return seen.get() == null ? null : seen.get();
    }

    @Test
    @DisplayName("主线程的租户上下文能被工作线程看到（@Async 业务不会丢租户）")
    void transmitsTenantToWorkerThread() throws InterruptedException {
        TenantContext.setTenantId("t_001");
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        executor.execute(() -> {
            seen.set(TenantContext.getTenantId());
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("t_001", seen.get(), "TTL 没透传 → 异步任务会写错租户");
    }

    @Test
    @DisplayName("role 也要一起透传，否则异步任务里的 ignoreTable 判断会走偏")
    void transmitsRoleToo() throws InterruptedException {
        TenantContext.setTenantId("t_001");
        TenantContext.setRole("SUPER_ADMIN");
        AtomicReference<String> seenRole = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        executor.execute(() -> {
            seenRole.set(TenantContext.getRole());
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("SUPER_ADMIN", seenRole.get());
    }

    @Test
    @DisplayName("提交时主线程已 clear → 工作线程必须是 null，不能吃到上一个任务的租户（串号）")
    void doesNotLeakPreviousTenantIntoWorkerThread() throws InterruptedException {
        TenantContext.setTenantId("t_001");
        CountDownLatch first = new CountDownLatch(1);
        executor.execute(first::countDown);
        assertTrue(first.await(5, TimeUnit.SECONDS));

        // 换个"请求"：主线程已经没有上下文了
        TenantContext.clear();
        AtomicReference<String> seen = new AtomicReference<>("初始值");
        CountDownLatch second = new CountDownLatch(1);
        executor.execute(() -> {
            seen.set(TenantContext.getTenantId());
            second.countDown();
        });

        assertTrue(second.await(5, TimeUnit.SECONDS));
        assertNull(seen.get(), "复用线程残留了上一个租户的上下文！");
    }

    @Test
    @DisplayName("两个租户交替提交 → 每个任务只看到自己的租户")
    void interleavedTenantsStayIsolated() throws InterruptedException {
        int n = 20;
        CountDownLatch latch = new CountDownLatch(n);
        AtomicReference<String> wrong = new AtomicReference<>();

        for (int i = 0; i < n; i++) {
            String tenantId = "t_" + (i % 2);
            TenantContext.setTenantId(tenantId);
            executor.execute(() -> {
                String seen = TenantContext.getTenantId();
                if (!tenantId.equals(seen)) {
                    wrong.compareAndSet(null, "期望 " + tenantId + " 实际 " + seen);
                }
                latch.countDown();
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertNull(wrong.get(), "线程池里租户串号: " + wrong.get());
    }
}