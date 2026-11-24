package com.saas.gateway.filter;

import com.saas.common.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Component
public class GlobalTenantFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GlobalTenantFilter.class);

    // 在类的顶部注入
    private final JwtUtils jwtUtils;
    private final ReactiveStringRedisTemplate redisTemplate;


    @Value("${saas.internal.secret}")
    private String internalSecret;


    public GlobalTenantFilter(ReactiveStringRedisTemplate redisTemplate, JwtUtils jwtUtils) {
        this.redisTemplate = redisTemplate;
        this.jwtUtils = jwtUtils;
    }


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        //浏览器在发跨域请求前，会先发一个 OPTIONS 探路请求，网关必须放行，否则前端直接报跨域错误。
        if (org.springframework.http.HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getURI().getPath();

        // 1. 如果是白名单，直接放行（通常登录接口会走这里）
        if(path.startsWith("/api/tenant/auth"))
        {
            // 必须给下游盖章，否则下游的 Filter 会拦截
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .headers(headers -> {
                        headers.set("X-Internal-Secret", internalSecret);
                    })
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }

        // 2. 获取 Authorization Header
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null || token.isEmpty() || !token.startsWith("Bearer ")) {
            return unauthorizedResponse(exchange);
        }
        token = token.substring(7);

        try {
            // 3. 解析 JWT Token
            Claims claims = jwtUtils.parseToken(token);
            String tenantId = claims.get("tenantId", String.class);
            String role = claims.get("role", String.class);

            // 🌟 新增：校验客户端传来的租户 ID 是否匹配
            String clientTenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-ID");
            if (clientTenantId != null && !clientTenantId.equals(tenantId)) {
                // 伪造租户，直接拒绝！
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }

            // 覆盖并放行

            // 4. 将真实解密的租户信息覆盖到请求头中（防止黑客伪造 X-Tenant-ID）
            final String finalTenantId = tenantId;

            // 查 Redis 看租户状态 (防止欠费)
            return redisTemplate.opsForValue().get("tenant:status:" + tenantId)
                    .defaultIfEmpty("1")
                    .flatMap(status -> {
                        if ("0".equals(status)) {
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return exchange.getResponse().setComplete();
                        }


                        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                .headers(headers -> {
                                    headers.set("X-Tenant-ID", finalTenantId);
                                    headers.set("X-Role", role != null ? role : "USER");
                                    headers.set("X-Internal-Secret", internalSecret);
                                })
                                .build();

                        return chain.filter(exchange.mutate().request(mutatedRequest).build());
                    });

        } catch (Exception e) {
            // Token 失效或解析失败
            return unauthorizedResponse(exchange);
        }
    }

    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}