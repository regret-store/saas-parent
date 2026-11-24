package com.saas.tenant.filter;

import com.saas.common.context.TenantContext;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class TenantFilter implements Filter {

    @Value("${saas.internal.secret}")
    private String expectedSecret;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)throws IOException, ServletException
    {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getRequestURI();

        // 🌟 放行登录接口，让它能在没有内部密钥的情况下正常执行（因为网关已经盖章了，但这里为了保险）
        if(path.startsWith("/auth/")||path.startsWith("/inner/"))
        {
            chain.doFilter(request,response);
            return;
        }

        // 🌟 核心：校验请求是否来自网关
        String internalSecret = httpRequest.getHeader("X-Internal-Secret");


        if(!isSecretValid(internalSecret))
        {
            // 如果不是网关来的，直接返回 403，绝不继续往下走
            httpResponse.setStatus(403);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write("{\"code\":403, \"message\":\"Forbidden: 拒绝绕过网关直接访问\"}");
            return;
        }

        // 从请求头获取租户 ID（通常由网关统一下发）
        String tenantId = httpRequest.getHeader("X-Tenant-ID");
        String role = httpRequest.getHeader("X-Role");

        try {
            if(tenantId != null && !tenantId.isEmpty())
            {
                TenantContext.setTenantId(tenantId);
            }


            if (role != null && !role.isEmpty()) {
                TenantContext.setRole(role);
            }
            // 放行请求
            chain.doFilter(request,response);
        }finally {
            // 核心：请求结束必须清理，否则线程池复用会导致租户串号！
            TenantContext.clear();
        }
    }

    /**
     60
     * 恒定时间比较，避免通过响应时间推断密钥内容
     61
     */
    private boolean isSecretValid(String actual) {
        if (!StringUtils.hasText(expectedSecret) || !StringUtils.hasText(actual)) {
            return false;
        }
        byte[] expectedBytes = expectedSecret.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }
}
