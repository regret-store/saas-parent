package com.saas.tenant.config;

import com.saas.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantDsProcessorTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private TenantDsProcessor processor;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        processor = new TenantDsProcessor();
        ReflectionTestUtils.setField(processor, "redisTemplate", redisTemplate);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("matches 只认 #tenant 前缀，别的注解值不许抢")
    void matchesOnlyTenantPrefix() {
        assertTrue(processor.matches("#tenant"));
        assertTrue(processor.matches("#tenant_id"));
        assertFalse(processor.matches("#header"));
        assertFalse(processor.matches("tenant"));
    }

    @Test
    @DisplayName("Redis 里有路由 → 切到独立库")
    void routesToCachedDbKey() {
        TenantContext.setTenantId("t_001");
        when(valueOps.get("tenant:db_route:t_001")).thenReturn("vip");

        assertEquals("vip", processor.doDetermineDatasource(null, "#tenant"));
    }

    @Test
    @DisplayName("无租户上下文 → 走 master（登录、定时任务这类平台级操作）")
    void noContextUsesMaster() {
        assertEquals("master", processor.doDetermineDatasource(null, "#tenant"));
    }

    @Test
    @DisplayName("缓存 miss → 当前实现降级 master，且没有回 MySQL 兜底、没有回写缓存（P2 待办）")
    void cacheMissDegradesToMaster() {
        TenantContext.setTenantId("t_001");
        when(valueOps.get("tenant:db_route:t_001")).thenReturn(null);

        assertEquals("master", processor.doDetermineDatasource(null, "#tenant"));
        // 补上"MySQL 兜底 + 回写 Redis"之后，这条要改成 assertEquals("vip", ...)
    }

    @Test
    @DisplayName("空字符串路由也不能当成数据源名用")
    void emptyRouteUsesMaster() {
        TenantContext.setTenantId("t_001");
        when(valueOps.get("tenant:db_route:t_001")).thenReturn("");

        assertEquals("master", processor.doDetermineDatasource(null, "#tenant"));
    }

    @Test
    @DisplayName("两个租户交替请求时，路由各自独立，不互相串")
    void routesAreIndependentPerTenant() {
        when(valueOps.get("tenant:db_route:t_001")).thenReturn("vip");
        when(valueOps.get("tenant:db_route:t_002")).thenReturn(null);

        TenantContext.setTenantId("t_001");
        assertEquals("vip", processor.doDetermineDatasource(null, "#tenant"));

        TenantContext.clear();
        TenantContext.setTenantId("t_002");
        assertEquals("master", processor.doDetermineDatasource(null, "#tenant"));
    }
}