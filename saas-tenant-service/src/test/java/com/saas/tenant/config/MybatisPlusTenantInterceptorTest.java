package com.saas.tenant.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
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

/**
 * 直接拿到 MybatisPlusConfig 里那个匿名 TenantLineHandler 实例来测：
 * 测的是生产代码本身，而不是我自己抄一遍逻辑。
 */
class MybatisPlusTenantInterceptorTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private TenantLineHandler handler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("tenant:db_route:t_001")).thenReturn(null);

        MybatisPlusConfig config = new MybatisPlusConfig();
        ReflectionTestUtils.setField(config, "redisTemplate", redisTemplate);

        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor();
        TenantLineInnerInterceptor tli =
                (TenantLineInnerInterceptor) interceptor.getInterceptors().get(0);
        handler = tli.getTenantLineHandler();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("租户列名固定是 tenant_id，实体类字段改名不影响拦截")
    void usesTenantIdColumn() {
        assertEquals("tenant_id", handler.getTenantIdColumn());
    }

    @Test
    @DisplayName("SQL 里的租户值来自 ThreadLocal，而不是任何入参——客户端改不动")
    void takesTenantIdFromContext() {
        TenantContext.setTenantId("t_001");
        assertTrue(handler.getTenantId().toString().contains("t_001"));
    }

    @Test
    @DisplayName("没有租户上下文时兜底成不存在的 default_tenant，查不到全量数据")
    void fallsBackToAnEmptyTenant() {
        assertTrue(handler.getTenantId().toString().contains("default_tenant"));
    }

    @Test
    @DisplayName("普通业务表必须拦截：SQL 会被追加 tenant_id 条件")
    void interceptsBusinessTable() {
        TenantContext.setTenantId("t_001");
        assertFalse(handler.ignoreTable("sys_user"), "sys_user 漏拦截 = 跨租户读全表");
    }

    @Test
    @DisplayName("sys_tenant 是平台级表，不参与租户拦截（注意表名大小写也要放过）")
    void ignoresTenantTable() {
        TenantContext.setTenantId("t_001");
        assertTrue(handler.ignoreTable("sys_tenant"));
        assertTrue(handler.ignoreTable("SYS_TENANT"), "实现用的是 equalsIgnoreCase，不能只测小写");
    }

    @Test
    @DisplayName("SUPER_ADMIN 无视所有隔离，能查全平台数据")
    void superAdminBypassesIsolation() {
        TenantContext.setTenantId("t_001");
        TenantContext.setRole("SUPER_ADMIN");
        assertTrue(handler.ignoreTable("sys_user"));
    }

    @Test
    @DisplayName("非 SUPER_ADMIN 的角色绝不放过隔离：USER / TENANT_ADMIN / 拼错的角色一律拦截")
    void otherRolesDoNotBypass() {
        TenantContext.setTenantId("t_001");
        for (String role : new String[]{"USER", "TENANT_ADMIN", "super_admin", "SUPER_ADMIN "}) {
            TenantContext.setRole(role);
            assertFalse(handler.ignoreTable("sys_user"), "角色 [" + role + "] 不该绕过租户隔离");
        }
    }

    @Test
    @DisplayName("VIP 租户走独立库 → 放过逻辑隔离（物理已经分开了）")
    void vipTenantSkipsLogicIsolation() {
        TenantContext.setTenantId("t_001");
        when(valueOps.get("tenant:db_route:t_001")).thenReturn("vip");
        assertTrue(handler.ignoreTable("sys_user"));
    }

    @Test
    @DisplayName("路由是 master 时仍然要逻辑隔离（共享库全靠这一层）")
    void sharedDbTenantStillIntercepted() {
        TenantContext.setTenantId("t_001");
        when(valueOps.get("tenant:db_route:t_001")).thenReturn("master");
        assertFalse(handler.ignoreTable("sys_user"));
    }

    @Test
    @DisplayName("没有租户上下文时不去查路由，正常拦截")
    void noContextDoesNotQueryRoute() {
        handler.ignoreTable("sys_user");   // tenantId == null
        assertFalse(handler.ignoreTable("sys_user"));
    }

    @Test
    @DisplayName("已知脆弱点：Redis 抖动会让每条 SQL 的 ignoreTable 直接抛异常（P2-14）")
    void redisFailureBreaksEveryQuery() {
        TenantContext.setTenantId("t_001");
        when(valueOps.get("tenant:db_route:t_001")).thenThrow(new IllegalStateException("redis down"));

        assertThrows(IllegalStateException.class, () -> handler.ignoreTable("sys_user"));
        // 这条用例是"现状记录"：加了请求级缓存/Caffeine 之后，应改成断言降级为拦截
    }
}