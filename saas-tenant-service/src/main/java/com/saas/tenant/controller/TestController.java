package com.saas.tenant.controller;

import com.saas.common.annotation.RequireRole;
import com.saas.common.context.TenantContext;
import com.saas.common.result.Result;
import com.saas.tenant.entity.SysUser;
import com.saas.tenant.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/tenant")
    public Result<String> getTenant() {
        return Result.ok("当前租户是: " + TenantContext.getTenantId());
    }

    @RequireRole({"TENANT_ADMIN", "SUPER_ADMIN"}) // 🌟 加上这行
    @GetMapping("/users")
    public Result<List<SysUser>> getAllUsers() {
        return Result.ok(sysUserMapper.selectList(null));
    }

    // 注入 Feign 客户端
    @Autowired
    private com.saas.tenant.client.AiServiceClient aiServiceClient;

    // 注入我们马上要定义的异步测试服务
    @Autowired
    private com.saas.tenant.service.AsyncService asyncService;

    // 1. 往 Redis 存数据（业务代码不写任何租户前缀）
    // 1. 往 Redis 存数据（业务代码不写任何租户前缀）
    @GetMapping("/redis/set")
    public Result<String> setRedis(
            @RequestParam("key") String key,
            @RequestParam("value") String value) {
        redisTemplate.opsForValue().set(key, value);
        return Result.ok("存入成功，业务 Key 是: " + key);
    }


    // 模拟租户状态同步到 Redis
    @GetMapping("/sync/tenant/status")
    public Result<String> syncTenantStatus(@RequestParam("tenantId") String tenantId, @RequestParam("status") String status) {
        // 强制存入 Redis，Key 为 tenant:status:{tenantId}，值为 1 或 0
        redisTemplate.opsForValue().set("tenant:status:" + tenantId, status);
        return Result.ok("租户 " + tenantId + " 状态同步成功: " + status);
    }

    // 测试 Feign 透传
    @GetMapping("/feign")
    public Result<String> testFeign() {
        // 正常情况下，这里调用是通过 Feign 发起 HTTP 请求到 ai-service
        String result = aiServiceClient.testTenant();
        return Result.ok(result);
    }

    @GetMapping("/async")
    public Result<String> testAsync() {
        asyncService.doAsyncTask();
        return Result.ok("异步任务已提交，请查看控制台日志！");
    }
}