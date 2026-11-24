package com.saas.tenant.config;

import com.baomidou.dynamic.datasource.processor.DsProcessor;
import com.saas.common.context.TenantContext;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class TenantDsProcessor extends DsProcessor {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public boolean matches(String key) {
        return key.startsWith("#tenant");
    }

    @Override
    public String doDetermineDatasource(MethodInvocation invocation, String key) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return "master";
        }

        // 直接从 Redis 里获取该租户的配置
        String routeKey = "tenant:db_route:" + tenantId;
        String targetDb = redisTemplate.opsForValue().get(routeKey);

        if (targetDb != null && !targetDb.isEmpty()) {
            System.out.println("=== 动态路由生效：租户 " + tenantId + " 切换到独立库 [" + targetDb + "] ===");
            return targetDb;
        }

        // 如果 Redis 里没查到（比如第一次启动或缓存失效），去 MySQL 兜底，并回写到 Redis
        // 为了简单，这里先直接返回 master
        return "master";
    }
}