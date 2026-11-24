package com.saas.tenant.filter;

import com.saas.common.context.TenantContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TenantFilterTest {

    /**
     * 这个字符串目前硬编码在 TenantFilter:32，另外还出现在 gateway yml、
     * ai 服务的 TenantFilter:20、FeignTenantInterceptor:22 —— 指向配置文件。
     * 外部化成 ${INTERNAL_SECRET} 之后，把这里改成注入常量即可。
     */
    private static final String SECRET = "saas-gateway-super-secret-key";

    private final TenantFilter filter = new TenantFilter();

    @BeforeEach
    void setUp() {
        // 手动注入 @Value 的值
        ReflectionTestUtils.setField(filter, "expectedSecret", SECRET);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletRequest request(String uri, String secret, String tenantId, String role) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        if (secret != null) req.addHeader("X-Internal-Secret", secret);
        if (tenantId != null) req.addHeader("X-Tenant-ID", tenantId);
        if (role != null) req.addHeader("X-Role", role);
        return req;
    }

    @Test
    @DisplayName("没有内部密钥 → 403，业务链一次都不能走")
    void rejectsRequestWithoutInternalSecret() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/test/users", null, "t_001", "USER"), res, chain);

        assertEquals(403, res.getStatus());
        assertNull(chain.getRequest(), "被拦截的请求绝不能继续传递到业务链");
    }

    @Test
    @DisplayName("密钥错误 → 403（拒绝绕过网关直连 8081）")
    void rejectsWrongInternalSecret() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/test/users", "guessed-secret", "t_001", "USER"), res, chain);

        assertEquals(403, res.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("登录接口在放行名单里：不需要密钥也能执行（否则谁都登录不了）")
    void allowsAuthPathWithoutSecret() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/auth/login", null, null, null), res, chain);

        assertEquals(200, res.getStatus());
        assertNotNull(chain.getRequest(), "/auth/** 应该被放行");
    }

    @Test
    @DisplayName("合法请求 → 租户上下文在业务执行期间必须可见")
    void populatesContextDuringRequest() throws Exception {
        AtomicReference<String> tenantDuring = new AtomicReference<>();
        AtomicReference<String> roleDuring = new AtomicReference<>();
        FilterChain capture = (rq, rs) -> {
            tenantDuring.set(TenantContext.getTenantId());
            roleDuring.set(TenantContext.getRole());
        };

        filter.doFilter(request("/test/users", SECRET, "t_001", "TENANT_ADMIN"),
                new MockHttpServletResponse(), capture);

        assertEquals("t_001", tenantDuring.get());
        assertEquals("TENANT_ADMIN", roleDuring.get());
    }

    @Test
    @DisplayName("请求结束必须清干净 ThreadLocal，否则 Tomcat 复用线程会租户串号")
    void clearsContextAfterRequest() throws Exception {
        filter.doFilter(request("/test/users", SECRET, "t_001", "TENANT_ADMIN"),
                new MockHttpServletResponse(), new MockFilterChain());
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getRole());

        // 同一个测试线程接着处理下一个"请求"：上一个人的租户不许还在
        filter.doFilter(request("/test/users", SECRET, "t_002", "USER"),
                new MockHttpServletResponse(), new MockFilterChain());
        // 第二次进入时看到的是 t_002，不是残留的 t_001 —— 由下一个用例继续保证
        assertNull(TenantContext.getTenantId());
    }

    @Test
    @DisplayName("有密钥但没传租户头 → 放行，但上下文为空（拦截器会走 default_tenant）")
    void passesThroughWhenHeadersMissing() throws Exception {
        AtomicReference<String> tenantDuring = new AtomicReference<>("初始化");
        FilterChain capture = (rq, rs) -> tenantDuring.set(TenantContext.getTenantId());

        filter.doFilter(request("/test/users", SECRET, null, null), new MockHttpServletResponse(), capture);

        assertNull(tenantDuring.get(), "没有 X-Tenant-ID 时不该凭空造一个租户");
    }
}