package com.saas.common.context;

import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;

/**
 * 自定义 Redis Key 序列化器：自动拼接租户前缀
 */
public class TenantRedisKeySerializer implements RedisSerializer<String> {

    private final StringRedisSerializer delegate = new StringRedisSerializer();

    @Override
    public byte[] serialize(String key) {
        if (key == null) {
            return null;
        }

        // 🌟 加入白名单：以 tenant:status: 开头的系统级 Key，不拼接租户前缀
        if (key.startsWith("tenant:status:") || key.startsWith("rate_limit:")) {
            return delegate.serialize(key);
        }

        // 获取当前租户
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = "default_tenant";
        }

        // 拼接前缀，比如: tenant_001:user:1
        String prefix = tenantId + ":";

        // 防止重复拼接 (如果 key 已经带了当前租户前缀)
        if (key.startsWith(prefix)) {
            return delegate.serialize(key);
        }

        return delegate.serialize(prefix + key);
    }

    @Override
    public String deserialize(byte[] bytes) {
        // 反序列化直接返回，不用去掉前缀，这样业务代码查出来的还是带前缀的
        return delegate.deserialize(bytes);
    }
}