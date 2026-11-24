package com.saas.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Collections;

@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<Long> limitScript;

    // 在构造方法里加载 Lua 脚本和注入响应式 Redis 模板
    public RateLimitFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        // 加载刚才写的 lua 脚本
        this.limitScript = RedisScript.of(new ClassPathResource("rate_limit.lua"), Long.class);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        String ip = "unknown";
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if(remoteAddress!=null)
        {
            ip = remoteAddress.getAddress().getHostAddress();
            //System.out.println(ip);
        }

        // 1. 获取当前租户 ID（因为在全局过滤器中已经透传了，直接拿）
        String path = exchange.getRequest().getURI().getPath();
        String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-ID");

        // 🌟 核心优化：判断限流维度
        String limitKey;
        if(path.startsWith("/api/tenant/auth"))
        {
            // 登录接口（没有 Token），按 IP 限流，防止有人恶意爆破另一个租户的登录
            limitKey = "rate_limit:ip:"+ip;
        }
        else
        {
            // 业务接口，按租户限流
            limitKey = "rate_limit:tenant:"+(tenantId!=null?tenantId:"unknown");
        }

        // 令牌桶参数：容量 10，每秒生成 5 个令牌（即每秒限制 5 次，允许突发 10 次）
        String capacity = "10";
        String rate = "5";
        String now = String.valueOf(System.currentTimeMillis() / 1000);
        String requested = "1";

//        if (tenantId == null || tenantId.isEmpty()) {
//            tenantId = "default_tenant";
//        }
//
//        // 2. 拼接限流的 Key
//        String redisKey = "rate_limit:" + tenantId;
//
//        // 3. 参数：最大允许 5 次，过期时间 60 秒
//        int limit = 5;
//        int expireSeconds = 60;

        // 4. 执行 Lua 脚本
        return redisTemplate.execute(limitScript, Collections.singletonList(limitKey),
                        java.util.Arrays.asList(capacity,rate,now,requested))
                .next() // 获取执行结果
                .defaultIfEmpty(1L)
                // 🌟 核心修复：处理空 Mono，防止请求挂起
                .flatMap(result -> {
                    if (result != null && result == 1L) {
                        // 返回 1 表示放行
                        return chain.filter(exchange);
                    } else {
                        // 返回 0 表示被限流
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        return exchange.getResponse().setComplete();
                    }
                });
                //.switchIfEmpty(chain.filter(exchange));// 如果 Redis 返回空，默认放行（或者你也可以选择直接返回 500）
    }

    @Override
    public int getOrder() {
        // 优先级要低于 GlobalTenantFilter（它塞 header），但高于路由
        return -50;
    }
}