package com.saas.tenant.config;

import com.saas.common.context.FeignTenantInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignConfig {
    // FeignConfig.java（全仓库只有这一处 new，改完不会漏）
    @Value("${saas.internal.secret}")
    private String internalSecret;

    @Bean
    public FeignTenantInterceptor feignTenantInterceptor() {
        return new FeignTenantInterceptor(internalSecret);
    }
}
