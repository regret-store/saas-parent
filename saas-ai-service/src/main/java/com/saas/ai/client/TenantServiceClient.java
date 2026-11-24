package com.saas.ai.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import java.util.Map;

@FeignClient(name = "saas-tenant-service")
public interface TenantServiceClient {

    @GetMapping("/inner/tenant/active-list")
    List<Map<String, Object>> getActiveTenants();
}