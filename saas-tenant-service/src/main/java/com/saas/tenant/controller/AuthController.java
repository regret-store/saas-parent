package com.saas.tenant.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.saas.common.result.Result;
import com.saas.common.utils.JwtUtils;
import com.saas.tenant.entity.SysUser;
import com.saas.tenant.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private JwtUtils jwtUtils; // 🌟 注入

    @Autowired
    private SysUserMapper sysUserMapper;

    // 🌟 创建 BCrypt 加密器实例
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody Map<String, String> loginData) {
        String username = loginData.get("username");
        String password = loginData.get("password");
        String tenantId = loginData.get("tenantId");

        if (tenantId == null || tenantId.isEmpty()) {
            return Result.fail(400, "必须指定租户ID");
        }

        try {
            // 🌟 核心：手动设置租户上下文，让 MyBatis-Plus 拦截器知道查哪个租户
            com.saas.common.context.TenantContext.setTenantId(tenantId);

            SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, username));

            if (user == null) {
                return Result.fail(401, "用户名或密码错误");
            }

            // 🌟 核心改动 2：使用 BCrypt 校验明文密码和数据库中的密文
            if(!passwordEncoder.matches(password,user.getPassword()))
            {
                return Result.fail(401,"用户名或密码错误");
            }

            // 生成 JWT Token
            String token = jwtUtils.generateToken(user.getTenantId(), user.getUsername(), user.getRole());

            Map<String, String> res = new HashMap<>();
            res.put("token", token);
            res.put("tenantId", user.getTenantId());
            return Result.ok(res);

        } finally {
            // 请求结束，必须清理上下文
            com.saas.common.context.TenantContext.clear();
        }
    }
}
