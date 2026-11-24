package com.saas.tenant.config;

import com.saas.common.annotation.RequireRole;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 不起 Spring，直接调用切面方法：注解实例靠反射从 dummy 方法上取，
 * joinPoint 用 Mockito 假一个。这样能精确测到"放行/拒绝"这条判断本身。
 */
class AuthAspectTest {

    static class Dummy {
        @RequireRole({"TENANT_ADMIN", "SUPER_ADMIN"})
        public String users() {
            return "ok";
        }

        @RequireRole({"USER"})
        public String profile() {
            return "me";
        }
    }

    private final AuthAspect aspect = new AuthAspect();

    private static RequireRole annotationOf(String method) {
        try {
            Method m = Dummy.class.getMethod(method);
            return m.getAnnotation(RequireRole.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private void mockRequestRole(String role) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test/users");
        if (role != null) request.addHeader("X-Role", role);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("角色命中白名单 → 放行，业务方法执行一次")
    void allowsDeclaredRole() throws Throwable {
        mockRequestRole("TENANT_ADMIN");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.checkRole(pjp, annotationOf("users")));
        verify(pjp).proceed();
    }

    @Test
    @DisplayName("SUPER_ADMIN 也在白名单里 → 放行（跨租户查询全靠这个角色）")
    void allowsSuperAdmin() throws Throwable {
        mockRequestRole("SUPER_ADMIN");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("all");

        assertEquals("all", aspect.checkRole(pjp, annotationOf("users")));
    }

    @Test
    @DisplayName("角色不在白名单 → 抛 SecurityException，业务方法一次都不许执行")
    void rejectsForeignRole() throws Throwable {
        mockRequestRole("USER");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

        SecurityException ex = assertThrows(SecurityException.class,
                () -> aspect.checkRole(pjp, annotationOf("users")));

        assertTrue(ex.getMessage().contains("TENANT_ADMIN"), "报错要把需要的角色带上，方便前端提示");
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("没带 X-Role（绕过网关直连）→ 直接拒绝，不能当成匿名用户放行")
    void rejectsWhenRoleHeaderMissing() throws Throwable {
        mockRequestRole(null);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

        assertThrows(RuntimeException.class, () -> aspect.checkRole(pjp, annotationOf("users")));
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("X-Role 是空字符串也不能蒙混过关")
    void rejectsEmptyRole() throws Throwable {
        mockRequestRole("");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

        assertThrows(RuntimeException.class, () -> aspect.checkRole(pjp, annotationOf("users")));
    }

    @Test
    @DisplayName("非 Web 线程（定时任务/Feign 回调）调用被注解方法 → 明确报错而不是 NPE")
    void rejectsWhenNoRequestContext() throws Throwable {
        RequestContextHolder.resetRequestAttributes();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

        assertThrows(RuntimeException.class, () -> aspect.checkRole(pjp, annotationOf("users")));
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("角色大小写敏感：admin != ADMIN，避免靠拼写绕过")
    void roleComparisonIsCaseSensitive() throws Throwable {
        mockRequestRole("tenant_admin");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

        assertThrows(SecurityException.class, () -> aspect.checkRole(pjp, annotationOf("users")));
    }

    @Test
    @DisplayName("注解只写了 USER 时，TENANT_ADMIN 也不该被放行（权限不是包含关系）")
    void narrowAnnotationIsReallyNarrow() throws Throwable {
        mockRequestRole("TENANT_ADMIN");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

        assertThrows(SecurityException.class, () -> aspect.checkRole(pjp, annotationOf("profile")));
    }
}