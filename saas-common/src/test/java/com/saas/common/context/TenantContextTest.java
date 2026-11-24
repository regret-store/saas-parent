package com.saas.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("tenantId 和 role 是两个独立的 ThreadLocal，都要能读写")
    void storesBothTenantAndRole() {
        TenantContext.setTenantId("t_001");
        TenantContext.setRole("SUPER_ADMIN");

        assertEquals("t_001", TenantContext.getTenantId());
        assertEquals("SUPER_ADMIN", TenantContext.getRole());
    }

    @Test
    @DisplayName("clear() 必须把两个都清掉——漏掉 ROLE 会让 SUPER_ADMIN 权限粘在复用线程上")
    void clearRemovesBothFields() {
        TenantContext.setTenantId("t_001");
        TenantContext.setRole("SUPER_ADMIN");

        TenantContext.clear();

        assertNull(TenantContext.getTenantId(), "TENANT_ID 没清干净");
        assertNull(TenantContext.getRole(), "ROLE 没清干净");
    }

    @Test
    @DisplayName("没有上下文时读取应为 null，而不是抛异常（业务代码靠这个走 default_tenant）")
    void readingWithoutContextIsNull() {
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getRole());
    }
}