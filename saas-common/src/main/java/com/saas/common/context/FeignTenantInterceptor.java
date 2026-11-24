package com.saas.common.context;

import feign.RequestInterceptor;
import feign.RequestTemplate;

//这是一个专门给 Feign 用的请求拦截器
public class FeignTenantInterceptor implements RequestInterceptor {
    private final String internalSecret;
    public FeignTenantInterceptor(String internalSecret)
    {
        this.internalSecret = internalSecret;
    }
    @Override
    public void apply(RequestTemplate template) {
        //这是拦截器的核心方法，每次 Feign 要发请求时，都会先执行这个方法。template 就是那个即将要发出去的请求（你可以把它当成一个还没封口的快递包裹）

        // 从当前线程的 ThreadLocal 中获取租户 ID
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            // 把租户 ID 塞进 Feign 发出的请求头里
            // 把拿到的租户 ID，塞进了 HTTP 的请求头（Header）里，Key 叫 X-Tenant-ID
            template.header("X-Tenant-ID", tenantId);
        }

        // 🌟 新增：内部服务 Feign 互相调用时，也必须盖章
        template.header("X-Internal-Secret", internalSecret);
    }
}
