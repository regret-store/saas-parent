package com.saas.tenant.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.saas.common.annotation.RequireRole;
import com.saas.common.result.Result;
import com.saas.tenant.entity.SysTenant;
import com.saas.tenant.mapper.SysTenantMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/config/tenant")
public class TenantConfigController {

    @Autowired
    private SysTenantMapper sysTenantMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // 平台管理员：修改租户绑定的数据源
    @RequireRole({"SUPER_ADMIN"})
    @PostMapping("/update-db-route")
    public Result<String> updateDbRoute(@RequestBody Map<String, String> data) {
        String tenantId = data.get("tenantId");
        String dbKey = data.get("dbKey");

        if (tenantId == null || dbKey == null) {
            return Result.fail(400, "参数不完整");
        }

        // 1. 更新 MySQL 数据库
        SysTenant tenant = sysTenantMapper.selectOne(new LambdaQueryWrapper<SysTenant>()
                .eq(SysTenant::getTenantId, tenantId));
        if (tenant == null) {
            return Result.fail(404, "租户不存在");
        }

        tenant.setDbKey(dbKey);
        sysTenantMapper.updateById(tenant); // 落库

        // 2. 同步更新 Redis 缓存（真实场景可以用 MQ 异步解耦，这里同步写保证强一致）
        String cacheKey = "tenant:db_route:" + tenantId;
        redisTemplate.opsForValue().set(cacheKey, dbKey);

        return Result.ok("租户 " + tenantId + " 的数据源已成功切换为 [" + dbKey + "] 并同步缓存");
    }
}