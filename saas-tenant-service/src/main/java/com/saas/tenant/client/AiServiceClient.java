package com.saas.tenant.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "saas-ai-service")
public interface AiServiceClient {
    @GetMapping("/ai/test-tenant")
    String testTenant();
}