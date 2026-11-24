package com.saas.ai.filter;

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
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 🌟 核心：AI 服务没有登录接口，全量校验内部密钥！
        String internalSecret = httpRequest.getHeader("X-Internal-Secret");
        if(!isSecretValid(internalSecret))
        {
            httpResponse.setStatus(403);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write("{\"code\":403, \"message\":\"Forbidden: 拒绝绕过网关直接访问 AI 服务\"}");
            return;
        }

        // 校验通过，将租户信息存入上下文
        String tenantId = httpRequest.getHeader("X-Tenant-ID");
        String role = httpRequest.getHeader("X-Role");

        try {
            if (tenantId != null && !tenantId.isEmpty()) {
                TenantContext.setTenantId(tenantId);
            }
            if (role != null && !role.isEmpty()) {
                TenantContext.setRole(role);
            }
            chain.doFilter(request, response);
        } finally {
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