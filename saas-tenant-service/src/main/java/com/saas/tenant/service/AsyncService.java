package com.saas.tenant.service;

import com.saas.common.context.TenantContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AsyncService {

    // 注意括号里要写我们在 ThreadPoolConfig 里定义的名字
    @Async("tenantAsyncExecutor")
    public void doAsyncTask() {
        // 模拟耗时任务
        try { Thread.sleep(1000); } catch (InterruptedException e) {}

        // 打印当前线程和租户 ID
        System.out.println("【异步任务】当前线程: " + Thread.currentThread().getName()
                + " , 获取到的租户 ID: " + TenantContext.getTenantId());
    }
}
