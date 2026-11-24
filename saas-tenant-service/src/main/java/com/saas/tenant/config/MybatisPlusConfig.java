package com.saas.tenant.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.saas.common.context.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class MybatisPlusConfig {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                // 从 ThreadLocal 拿租户 ID
                String tenantId = TenantContext.getTenantId();
                // 兜底：如果没有租户ID，给个不存在的值，防止查出全量数据
                if (tenantId == null) {
                    tenantId = "default_tenant";
                }
                return new StringValue(tenantId);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id"; // 数据库里的租户字段名
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // 租户表是平台级的，不参与多租户拦截
                if ("sys_tenant".equalsIgnoreCase(tableName)) return true;
                // 🌟 核心逻辑：如果是超级管理员，无视所有租户隔离，查出全平台数据
                if ("SUPER_ADMIN".equals(com.saas.common.context.TenantContext.getRole())) {
                    return true;
                }

                // 3. 🌟 核心联动：如果是 VIP 走独立库，放过逻辑隔离
                String tenantId = TenantContext.getTenantId();
                if(tenantId!=null)
                {
                    String dbKey = redisTemplate.opsForValue().get("tenant:db_route:"+tenantId);
                    if("vip".equals(dbKey))
                    {
                        // 如果是走独立库，且表不是全局配置表，就可以放过
                        // System.out.println("=== 独立库租户，跳过 TenantLine 拦截 ===");
                        return true;
                    }
                }

                return false; // 所有的表都要拦截
            }
        }));

        return interceptor;
    }
}