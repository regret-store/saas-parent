package com.saas.tenant.config;

import com.saas.common.annotation.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

@Aspect
@Component
public class AuthAspect {

    @Around("@annotation(requireRole)")
    public Object checkRole(ProceedingJoinPoint joinPoint, RequireRole requireRole) throws Throwable {
        // 获取当前请求
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new RuntimeException("非 Web 请求，无法鉴权");
        }
        HttpServletRequest request = attributes.getRequest();

        // 从网关解析 Token 后塞进来的 Header 中获取角色
        String role = request.getHeader("X-Role");

        if (role == null) {
            throw new RuntimeException("未获取到角色信息，请重新登录");
        }

        // 判断当前角色是否在注解允许的列表中
        boolean hasRole = Arrays.asList(requireRole.value()).contains(role);
        if (!hasRole) {
            // 抛出一个自定义异常，等会儿让全局异常处理器捕获并返回 403
            throw new SecurityException("权限不足，需要角色: " + Arrays.toString(requireRole.value()));
        }

        return joinPoint.proceed();
    }
}