package com.saas.ai.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
public class AiTestController {

    @GetMapping("/test-tenant")
    public String testTenant(@RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        // 打印一下，方便在控制台看到 Feign 到底传没传过来
        System.out.println("==== AI 服务收到请求，租户 ID 是: " + tenantId + " ====");
        return "AI服务收到的租户ID是: " + tenantId;
    }
}