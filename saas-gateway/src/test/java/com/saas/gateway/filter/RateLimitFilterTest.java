package com.saas.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class RateLimitFilterTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private GatewayFilterChain chain;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
        filter = new RateLimitFilter(redisTemplate);
    }

    private MockServerWebExchange exchange(String path, String tenantId) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path)
                .remoteAddress(new InetSocketAddress("1.2.3.4", 5555));
        if (tenantId != null) builder = builder.header("X-Tenant-ID", tenantId);
        // 用静态工厂方法，自动补全其他参数
        MockServerHttpRequest request = builder.build();
        MockServerHttpResponse response = new MockServerHttpResponse();

        return MockServerWebExchange.from(request);
    }

    private void stubScript(Flux<Long> result) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(result);
    }

    @Test
    @DisplayName("脚本返回 1 → 放行到下一个过滤器")
    void allowsWhenScriptSaysOne() {
        stubScript(Flux.just(1L));

        filter.filter(exchange("/api/tenant/test/users", "t_001"), chain).block();

        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("脚本返回 0 → 直接 429，业务链一次都不走")
    void rejectsWhenScriptSaysZero() {
        stubScript(Flux.just(0L));

        MockServerWebExchange exchange = exchange("/api/tenant/test/users", "t_001");
        exchange.getResponse().setComplete();   // 便于观察：429 后自己 complete
        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("压测事故回归：Redis 返回空 Mono 时必须默认放行，绝不能再挂起请求")
    void emptyMonoMustNotHangTheRequest() {
        stubScript(Flux.empty());   // 修复前：这里 .next() 拿到空 Mono，flatMap 不执行，请求永久挂住

        MockServerWebExchange exchange = exchange("/api/tenant/test/users", "t_001");
        // block 带超时：如果又挂起来了，这条用例会超时失败，而不是永远不返回
        filter.filter(exchange, chain).block(java.time.Duration.ofSeconds(2));

        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("登录接口没有 token → 按 IP 维度限流，防爆破别租户")
    void usesIpKeyForAuthPath() {
        stubScript(Flux.just(1L));

        filter.filter(exchange("/api/tenant/auth/login", null), chain).block();

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), anyList());
        assertTrue(keys.getValue().get(0).startsWith("rate_limit:ip:"), "登录接口应按 IP 限流");
        assertTrue(keys.getValue().get(0).contains("1.2.3.4"), "IP 要从请求里取到");
    }

    @Test
    @DisplayName("业务接口 → 按租户维度限流，单个租户刷爆不影响别人")
    void usesTenantKeyForBusinessPath() {
        stubScript(Flux.just(1L));

        filter.filter(exchange("/api/tenant/test/users", "t_001"), chain).block();

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), anyList());
        assertEquals("rate_limit:tenant:t_001", keys.getValue().get(0));
    }

    @Test
    @DisplayName("令牌桶参数：容量 10、速率 5/秒、每次消耗 1，必须按顺序传给脚本")
    void passesBucketArgumentsInOrder() {
        stubScript(Flux.just(1L));

        filter.filter(exchange("/api/tenant/test/users", "t_001"), chain).block();

        ArgumentCaptor<List<Object>> args = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), args.capture());
        assertEquals("10", args.getValue().get(0), "capacity");
        assertEquals("5", args.getValue().get(1), "rate");
        assertEquals("1", args.getValue().get(3), "requested");
        assertTrue(Long.parseLong(String.valueOf(args.getValue().get(2))) > 1_700_000_000L, "now 要换算成秒");
    }

    @Test
    @DisplayName("Redis 直接报错时不是空 Mono，而是 error 信号 → 现状会抛出去（不做降级）")
    void redisErrorPropagates() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("connection refused")));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> filter.filter(exchange("/api/tenant/test/users", "t_001"), chain).block());
        assertTrue(ex.getMessage().contains("connection refused"));
        // 这条也是"现状记录"：将来加了降级放行/熔断，改这里
    }
}