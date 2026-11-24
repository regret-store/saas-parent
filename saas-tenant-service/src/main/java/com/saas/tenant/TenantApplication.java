package com.saas.tenant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = "com.saas") // 🌟 扩大扫描范围到 com.saas
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.saas.tenant.client")
@EnableAsync
public class TenantApplication {
    public static void main(String[] args){
        SpringApplication.run(TenantApplication.class,args);
    }
}
