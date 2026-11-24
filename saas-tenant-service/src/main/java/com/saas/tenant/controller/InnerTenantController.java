package com.saas.tenant.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.saas.tenant.entity.SysTenant;
import com.saas.tenant.mapper.SysTenantMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/inner/tenant")
public class InnerTenantController {

    @Autowired
    private SysTenantMapper sysTenantMapper;

    // 提供给 AI 服务调用的接口：获取所有正常状态的租户
    @GetMapping("/active-list")
    public List<SysTenant> getActiveTenants()
    {
        return sysTenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                .eq(SysTenant::getStatus,1));
    }
}
