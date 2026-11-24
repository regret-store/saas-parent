package com.saas.common.annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE})
//表示这个注解可以加在 方法 上（比如某个具体的 API 接口），也可以加在 类 上（比如整个 Controller 类）
@Retention(RetentionPolicy.RUNTIME)
//表示这个注解在运行时有效。这是关键，因为只有在运行时保留，程序才能通过“反射”技术读取到它并做权限判断。
@Documented
//表示这个注解会包含在生成 JavaDoc 文档中。
public @interface RequireRole {
    // 允许访问的角色，比如 {"TENANT_ADMIN", "SUPER_ADMIN"}
    String[] value();
}

//这只是一张“标签”，真正让它起作用的是 Spring 框架里的拦截器（Interceptor）或切面（AOP）