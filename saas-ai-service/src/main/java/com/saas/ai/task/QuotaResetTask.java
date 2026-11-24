package com.saas.ai.task;

import com.saas.ai.client.TenantServiceClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class QuotaResetTask {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TenantServiceClient tenantServiceClient;

    /**
     * cron 表达式：每天凌晨 0 点执行
     * 为了你测试方便，先写  (每2分钟执行一次)
            */
    @Scheduled(cron = "0 */2 * * * ?")
    public void resetDailyQuota() {
        String lockKey = "lock:ai:quota:reset";
        String lockValue = UUID.randomUUID().toString(); // 唯一标识，防止误删别人的锁

        // 1. 尝试获取分布式锁 (SETNX)，有效期 2 分钟
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofMinutes(2));

        if (Boolean.TRUE.equals(locked)) {
            try {
                System.out.println("=== 成功获取分布式锁，开始重置租户 AI 额度 ===");

                // 2. 通过 Feign 调用租户服务，拉取真实名单
                List<Map<String, Object>> tenantList = tenantServiceClient.getActiveTenants();

                if (tenantList == null || tenantList.isEmpty()) {
                    System.out.println("未获取到活跃租户，任务结束");
                    return;
                }

                // 3. 遍历名单，重置 Redis 额度
                for (Map<String, Object> tenant : tenantList) {
                    String tenantId = (String) tenant.get("tenantId");

                    // 业务规则：VIP 给 1000，普通给 100
                    int quota = "tenant_vip".equals(tenantId) ? 1000 : 100;

                    // 写入 Redis，并设置 24 小时过期
                    redisTemplate.opsForValue().set("ai:quota:" + tenantId, String.valueOf(quota), Duration.ofHours(24));

                    System.out.println("租户 [" + tenantId + "] 额度已重置为: " + quota);
                }

            } catch (Exception e) {
                System.err.println("定时任务执行异常: " + e.getMessage());
            } finally {
                // 4. 释放锁（用 Lua 脚本保证判断和删除的原子性）
                String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
                redisTemplate.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
                        java.util.Collections.singletonList(lockKey), lockValue);
                System.out.println("=== 重置任务结束，分布式锁已释放 ===");
            }
        } else {
            System.out.println("=== 未获取到锁，其他节点正在执行，跳过 ===");
        }
    }
}