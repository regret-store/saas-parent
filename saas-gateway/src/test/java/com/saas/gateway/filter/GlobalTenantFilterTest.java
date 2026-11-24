package com.saas.gateway.filter;

import com.saas.common.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class GlobalTenantFilterTest {

    private static final String JWT_SECRET = "unit-test-only-secret-0123456789abcdef";
    private static final String INTERNAL_SECRET = "saas-gateway-test";

    private ReactiveStringRedisTemplate redisTemplate;
    private ReactiveValueOperations<String, String> valueOps;
    private JwtUtils jwtUtils;
    private GatewayFilterChain chain;
    private GlobalTenantFilter filter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 默认：租户状态正常（key 不存在）
        when(valueOps.get(anyString())).thenReturn(Mono.empty());

        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secret", JWT_SECRET);
        ReflectionTestUtils.setField(jwtUtils, "expirationTime", 86400000L);
        jwtUtils.init();

        filter = new GlobalTenantFilter(redisTemplate, jwtUtils);
        ReflectionTestUtils.setField(filter, "internalSecret", INTERNAL_SECRET);

        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    private MockServerWebExchange exchange(String method, String path, String authHeader, String clientTenantId) {
        MockServerHttpRequest.BaseBuilder<?> builder =
        MockServerHttpRequest.method(HttpMethod.valueOf(method), path);
        builder = builder.header("X-Internal-Secret", "客户端乱传的应该被覆盖");
        if (authHeader != null) builder = builder.header(HttpHeaders.AUTHORIZATION, authHeader);
        if (clientTenantId != null) builder = builder.header("X-Tenant-ID", clientTenantId);
        return MockServerWebExchange.from(builder.build());
    }

    private HttpHeaders downstreamHeaders(MockServerWebExchange exchange) {
        filter.filter(exchange, chain).block();
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        return captor.getValue().getRequest().getHeaders();
    }

    @Test
    @DisplayName("登录白名单：不带 token 也要放行，且必须给下游盖章（否则下游过滤器会拦死）")
    void whitelistAuthPathStampsInternalSecret() {
        HttpHeaders headers = downstreamHeaders(
                exchange("POST", "/api/tenant/auth/login", null, null));

        assertEquals(INTERNAL_SECRET, headers.getFirst("X-Internal-Secret"));
    }

    @Test
    @DisplayName("OPTIONS 预检直接放行，不校验 token（否则前端跨域全挂）")
    void optionsPreflightPassesThrough() {
        MockServerWebExchange exchange = exchange("OPTIONS", "/api/tenant/test/users", null, null);

        filter.filter(exchange, chain).block();

        verify(chain).filter(any(ServerWebExchange.class));
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("没有 Authorization → 401，不放行")
    void missingTokenIsUnauthorized() {
        MockServerWebExchange exchange = exchange("GET", "/api/tenant/test/users", null, null);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("不是 Bearer 前缀的 Authorization → 401")
    void nonBearerTokenIsUnauthorized() {
        MockServerWebExchange exchange =
                exchange("GET", "/api/tenant/test/users", jwtUtils.generateToken("t_001", "alice", "USER"), null);
        // 重新构造：把 "Bearer " 前缀去掉
        String rawToken = exchange.getRequest().getHeaders().getFirst("Authorization")
                .replace("Bearer ", "");
        MockServerWebExchange bad = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/tenant/test/users")
                        .header("Authorization", rawToken)
                        .build());

        filter.filter(bad, chain).block();
        assertEquals(HttpStatus.UNAUTHORIZED, bad.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("垃圾 token → 401，异常不能穿透到 500")
    void garbageTokenIsUnauthorized() {
        MockServerWebExchange exchange =
                exchange("GET", "/api/tenant/test/users", "Bearer abc.def.ghi", null);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("合法 token → 放行，且下游拿到的是 token 里的租户和角色，不是客户端传的")
    void legalTokenOverridesTenantAndRole() {
        String token = "Bearer " + jwtUtils.generateToken("t_001", "alice", "TENANT_ADMIN");

        HttpHeaders headers = downstreamHeaders(
                exchange("GET", "/api/tenant/test/users", token, null));

        assertEquals("t_001", headers.getFirst("X-Tenant-ID"), "X-Tenant-ID 必须来自 token");
        assertEquals("TENANT_ADMIN", headers.getFirst("X-Role"));
        assertEquals(INTERNAL_SECRET, headers.getFirst("X-Internal-Secret"));
    }

    @Test
    @DisplayName("客户端伪造 X-Tenant-ID 与 token 不一致 → 403，这是防跨租户的第一道闸")
    void spoofedTenantHeaderIsForbidden() {
        String token = "Bearer " + jwtUtils.generateToken("t_001", "alice", "USER");

        MockServerWebExchange exchange =
                exchange("GET", "/api/tenant/test/users", token, "t_999");

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("客户端传的 X-Tenant-ID 与 token 一致 → 正常放行（别误伤合法前端）")
    void matchingTenantHeaderIsAllowed() {
        String token = "Bearer " + jwtUtils.generateToken("t_001", "alice", "USER");

        HttpHeaders headers = downstreamHeaders(
                exchange("GET", "/api/tenant/test/users", token, "t_001"));

        assertEquals("t_001", headers.getFirst("X-Tenant-ID"));
    }

    @Test
    @DisplayName("租户被冻结（tenant:status=0）→ 403，token 再合法也没用")
    void frozenTenantIsForbidden() {
        when(valueOps.get("tenant:status:t_001")).thenReturn(Mono.just("0"));
        String token = "Bearer " + jwtUtils.generateToken("t_001", "alice", "USER");

        MockServerWebExchange exchange = exchange("GET", "/api/tenant/test/users", token, null);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("状态 key 不存在时默认当成正常租户（fail-open，欠费冻结靠显式写 0）")
    void missingStatusKeyIsTreatedAsActive() {
        when(valueOps.get("tenant:status:t_001")).thenReturn(Mono.empty());
        String token = "Bearer " + jwtUtils.generateToken("t_001", "alice", "USER");

        HttpHeaders headers = downstreamHeaders(exchange("GET", "/api/tenant/test/users", token, null));

        assertEquals("t_001", headers.getFirst("X-Tenant-ID"));
    }

    @Test
    @DisplayName("token 里没有 role 时下游兜底成 USER，不能塞 null 让 AuthAspect 报错")
    void missingRoleFallsBackToUser() {
        String token = "Bearer " + jwtUtils.generateToken("t_001", "alice", null);

        HttpHeaders headers = downstreamHeaders(exchange("GET", "/api/tenant/test/users", token, null));

        assertEquals("USER", headers.getFirst("X-Role"));
    }

    @Test
    @DisplayName("客户端自己带的 X-Internal-Secret 必须被网关覆盖，否则可以拿来冒充网关")
    void clientSuppliedSecretIsOverwritten() {
        String token = "Bearer " + jwtUtils.generateToken("t_001", "alice", "USER");

        HttpHeaders headers = downstreamHeaders(exchange("GET", "/api/tenant/test/users", token, null));

        assertEquals(INTERNAL_SECRET, headers.getFirst("X-Internal-Secret"));
    }
}