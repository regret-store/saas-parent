package com.saas.common.context;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 租户上下文，用于全链路传递 tenantId
 */
public class TenantContext {

    // 使用 TransmittableThreadLocal 保证线程池传递
    private static final ThreadLocal<String> TENANT_ID = new TransmittableThreadLocal<>();

    // 🌟 新增：存放角色
    private static final ThreadLocal<String> ROLE = new TransmittableThreadLocal<>();

    public static void setTenantId(String tenantId)
    {
        TENANT_ID.set(tenantId);
    }

    public static String getTenantId()
    {
        return TENANT_ID.get();
    }

    // 🌟 新增角色的 get/set
    public static void setRole(String role) { ROLE.set(role); }
    public static String getRole() { return ROLE.get(); }

    /**
     * 必须在请求结束时清理，防止内存泄漏（Tomcat 线程池复用）
     */
    public static void clear()
    {
        TENANT_ID.remove();
        ROLE.remove(); // 记得清理
    }
}
