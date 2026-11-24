package com.saas.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TenantRedisKeySerializerTest {

    private final TenantRedisKeySerializer serializer = new TenantRedisKeySerializer();

    private String ser(String key) {
        byte[] bytes = serializer.serialize(key);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("普通业务 key 自动带上租户前缀，业务代码一行都不用写 tenantId")
    void prefixesBusinessKey() {
        TenantContext.setTenantId("t_001");
        assertEquals("t_001:user:1", ser("user:1"));
    }

    @Test
    @DisplayName("系统级 key（租户状态、限流）在两个服务间共享，绝不能被加上前缀")
    void systemKeysStayUnprefixed() {
        TenantContext.setTenantId("t_001");
        assertEquals("tenant:status:t_002", ser("tenant:status:t_002"));
        assertEquals("rate_limit:tenant:t_001", ser("rate_limit:tenant:t_001"));
    }

    @Test
    @DisplayName("已经带当前租户前缀的 key 不能二次拼接")
    void doesNotDoublePrefix() {
        TenantContext.setTenantId("t_001");
        assertEquals("t_001:order:9", ser("t_001:order:9"));
    }

    @Test
    @DisplayName("不同租户写同一个逻辑 key → 落到不同物理 key，这就是隔离的全部秘密")
    void twoTenantsGetDifferentPhysicalKeys() {
        TenantContext.setTenantId("t_001");
        String a = ser("config:theme");
        TenantContext.setTenantId("t_002");
        String b = ser("config:theme");

        assertNotEquals(a, b);
        assertEquals("t_001:config:theme", a);
        assertEquals("t_002:config:theme", b);
    }

    @Test
    @DisplayName("无上下文时落到 default_tenant 命名空间（注意：这是静默串号，不是报错）")
    void fallsBackToDefaultTenant() {
        assertEquals("default_tenant:user:1", ser("user:1"));
    }

    @Test
    @DisplayName("null key 原样返回 null，不能 NPE")
    void nullKeyIsSafe() {
        assertNull(ser(null));
    }

    @Test
    @DisplayName("反序列化保留完整物理 key，便于排查；读出来的 key 带前缀是预期行为")
    void deserializeKeepsFullKey() {
        String out = serializer.deserialize("t_001:user:1".getBytes(StandardCharsets.UTF_8));
        assertEquals("t_001:user:1", out);
    }
}