# saas-parent 多租户 SaaS 平台

一个 Spring Cloud 微服务 demo，做的事情就一件：**一个请求进来，全程知道它是谁的租户，并且只能看到自己的数据**。

隔离分三级——普通租户共享库共享表（SQL 行级注入）、VIP 租户切独立库（数据源动态路由）、超级管理员跨租户；配套的还有网关统一鉴权与限流、租户上下文跨进程/跨线程透传、Redis Key 按租户加前缀。

## 模块与端口

| 模块 | 端口 | 职责 |
| --- | --- | --- |
| `saas-gateway` | 8080 | 唯一入口：JWT 解析、租户头覆盖、状态熔断、令牌桶限流、路由与 CORS |
| `saas-tenant-service` | 8081 | 租户/用户业务，多租户数据隔离的主战场 |
| `saas-ai-service` | 8082 | 调 DeepSeek 的示例服务，带 Redis Lua 配额扣减（Spring AI 只是包装外部 API） |
| `saas-common` | — | 公共 SDK：JWT 工具、`TenantContext`、Feign 透传拦截器、Redis Key 序列化器、`@RequireRole` |

## 技术栈

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| JDK / Maven | 21 / 3.9+ | 根 pom `maven.compiler.release=21` |
| Spring Boot | 3.5.6 | Web、AOP、事务 |
| Spring Cloud / Alibaba | 2025.0.0 / 2025.0.0.0 | Gateway(WebFlux)、OpenFeign、Nacos、LoadBalancer |
| MyBatis-Plus + jsqlparser | 3.5.9 | `TenantLineInnerInterceptor` 行级隔离 |
| dynamic-datasource | 4.3.0 | 自定义 `DsProcessor` 做库级路由 |
| MySQL | 双实例 3307 / 3308 | `saas_db`（共享库）与 `vip_db`（独立库） |
| Redis | 7.x，6379 | 租户状态、库路由、限流、缓存前缀 |
| jjwt | 0.11.5 | HS256 签发/解析，claims 带 `tenantId`、`role` |
| TransmittableThreadLocal | 2.14.x | 跨线程池透传租户上下文 |
| Spring AI | 1.0.0 | 仅 ai-service 接 DeepSeek Chat API |

## 一个请求的完整链路

```
GET http://localhost:8080/api/tenant/test/users
Authorization: Bearer <JWT>
    │
    ├─ GlobalTenantFilter (order=-100)
    │     OPTIONS 直接放行（否则预检挂掉）
    │     /api/tenant/auth/**  ── 免 Token，但盖上 X-Internal-Secret 放行
    │     其余路径：解析 JWT → 若客户端自带 X-Tenant-ID 且与 Token 不符 → 403
    │              → 读 Redis tenant:status:{tid}，为 0 → 403
    │              → 覆盖 X-Tenant-ID / X-Role / X-Internal-Secret（下游只认这三个头）
    │
    ├─ RateLimitFilter (order=-50)
    │     登录接口按 IP：rate_limit:ip:{ip}
    │     业务接口按租户：rate_limit:tenant:{tid}
    │     rate_limit.lua 令牌桶：容量 10、每秒补 5，返回 0 → 429
    │
    └─ lb://saas-tenant-service → StripPrefix=2 → GET /test/users
          │
          ├─ TenantFilter：校验 X-Internal-Secret（MessageDigest.isEqual 恒定时间比较）
          │                不通过 → 403「拒绝绕过网关直接访问」
          │                通过 → TenantContext.set(tenantId/role)，finally 里 clear
          │
          ├─ AuthAspect：@RequireRole({"TENANT_ADMIN","SUPER_ADMIN"}) 角色不够 → 拒绝
          │
          ├─ RedisTemplate：TenantRedisKeySerializer 把 key 变成 {tenantId}:xxx
          │                  （tenant:status: / rate_limit: 前缀走白名单，不加前缀）
          │
          ├─ TenantDsProcessor：datasource key 以 #tenant 开头时，
          │                    读 Redis tenant:db_route:{tid} → 命中 vip 就切独立库
          │
          └─ TenantLineInnerInterceptor.ignoreTable()
                sys_tenant            → 跳过（平台级表）
                role == SUPER_ADMIN   → 跳过（查全平台）
                db_route == vip       → 跳过（独立库不需要行级隔离）
                其余                  → 自动拼 WHERE tenant_id = '{tid}'
```

## 三级隔离怎么落到代码上

| 级别 | 适用 | 实现 | 数据边界 |
| --- | --- | --- | --- |
| 行级（共享库共享表） | 默认所有租户 | `TenantLineInnerInterceptor` 注入 `tenant_id` 条件；无上下文时兜底成 `default_tenant`，宁可查不到也不查全量 | `saas_db` |
| 库级（独立库） | VIP 套餐 | `TenantConfigController` 改 `sys_tenant.db_key` 并写 Redis `tenant:db_route:{tid}`，`TenantDsProcessor` 据此切到 `vip` 数据源，同时行级拦截自动跳过 | `vip_db` |
| 平台级 | SUPER_ADMIN | 上下文里 role=SUPER_ADMIN 时 `ignoreTable` 恒真 | 全库 |

缓存侧同一套思路：业务 Key 一律加 `{tenantId}:` 前缀，系统级 Key（租户状态、限流桶）在白名单里保持全局唯一。

## 快速开始

### 1. 起中间件

```bash
# 共享库实例（宿主机 3307）
docker run -d --name saas-mysql-master -p 3307:3306 \
  -e MYSQL_ROOT_PASSWORD=你的口令 -e TZ=Asia/Shanghai mysql:8.0

# VIP 独立库实例（宿主机 3308）
docker run -d --name saas-mysql-vip -p 3308:3306 \
  -e MYSQL_ROOT_PASSWORD=你的口令 -e TZ=Asia/Shanghai mysql:8.0

docker run -d --name saas-redis -p 6379:6379 redis:7 --appendonly yes

# Nacos 2.x：8848 是 HTTP，9848 是 gRPC，缺 9848 客户端会连不上
docker run -d --name saas-nacos -p 8848:8848 -p 9848:9848 \
  -e MODE=standalone -e PREFER_HOST_MODE=ip nacos/nacos-server:v2.4.3
```

MySQL 端口与实例名在 `saas-tenant-service/src/main/resources/application.yml` 里写死为 3307 / 3308，换端口要同步改配置。

### 2. 建库建表

两个库结构完全一致（VIP 租户进独立库后不再带 `tenant_id` 条件，所以表结构必须相同）：

```sql
CREATE DATABASE IF NOT EXISTS saas_db DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS vip_db  DEFAULT CHARSET utf8mb4;

-- 下面 4 条语句在 saas_db 和 vip_db 各执行一遍
USE saas_db;

CREATE TABLE sys_tenant (
  id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64)  NOT NULL COMMENT '租户标识',
  name      VARCHAR(128) NOT NULL,
  status    TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0冻结',
  db_key    VARCHAR(32)  COMMENT '绑定的数据源，如 vip',
  UNIQUE KEY uk_tenant (tenant_id)
);

CREATE TABLE sys_user (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id   VARCHAR(64)  NOT NULL,
  username    VARCHAR(64)  NOT NULL,
  password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
  role        VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT 'USER / TENANT_ADMIN / SUPER_ADMIN',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tenant_username (tenant_id, username)
);

INSERT INTO sys_tenant (tenant_id, name, status, db_key) VALUES
  ('tenant_001', '普通租户A', 1, NULL),
  ('tenant_002', '普通租户B', 1, NULL),
  ('tenant_003', 'VIP 租户C', 1, 'vip');

-- password 列存的是 BCrypt 结果，这里给出的是明文 123456 的密文（已用 BCryptPasswordEncoder 验证）
INSERT INTO sys_user (tenant_id, username, password, role) VALUES
  ('tenant_001', 'alice', '$2a$10$ZjzhsezRSvDreYYvoTXUmeVF.ebv/D9wvmOU9SPbhB.ZHQ6tTaYiW', 'USER'),
  ('tenant_001', 'admin', '$2a$10$ZjzhsezRSvDreYYvoTXUmeVF.ebv/D9wvmOU9SPbhB.ZHQ6tTaYiW', 'TENANT_ADMIN'),
  ('tenant_002', 'bob',   '$2a$10$ZjzhsezRSvDreYYvoTXUmeVF.ebv/D9wvmOU9SPbhB.ZHQ6tTaYiW', 'USER'),
  ('tenant_003', 'carol', '$2a$10$ZjzhsezRSvDreYYvoTXUmeVF.ebv/D9wvmOU9SPbhB.ZHQ6tTaYiW', 'TENANT_ADMIN');
-- SUPER_ADMIN 账号同理，role 填 SUPER_ADMIN，tenant_id 填 platform
```

换自己的密码时重新生成密文即可：

```bash
jshell -R --class-path ~/.m2/repository/org/springframework/security/spring-security-crypto/6.5.5/spring-security-crypto-6.5.5.jar
# jshell> new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("你的明文")
```

### 3. 填本地配置（仓库里不含任何真实凭据）

`application.yml` 里所有敏感项都是占位符，没有默认值：

```yaml
spring: datasource: dynamic: datasource: master: password: ${MASTER_PASS}
saas:   internal: secret: ${INTERNAL_SECRET}
saas:   jwt:      secret: ${JWT_SECRET}
spring: ai: openai: api-key: ${DEEPSEEK_API_KEY}   # 仅 ai-service
```

由于每个模块的 `application.yml` 都写死了 `spring.profiles.active: local`，**不放本地配置文件的话占位符解析失败、服务起不来**——这是故意的，宁可启动报错也不要悄悄用默认密钥上线。两种给法二选一：

```bash
# 方式 A：环境变量
export MASTER_PASS='...' INTERNAL_SECRET='...' JWT_SECRET='...' DEEPSEEK_API_KEY='sk-...'

# 方式 B：本地文件（每个模块一份模板，复制后填值；*.example 进仓库，副本不进仓库）
cp saas-gateway/src/main/resources/application-local.yml.example \
   saas-gateway/src/main/resources/application-local.yml
# 同理 saas-tenant-service、saas-ai-service
```

`JWT_SECRET` 至少 32 字节，`JwtUtils.init()` 会在启动时校验长度，不够直接抛 `IllegalArgumentException`。三个服务的 `INTERNAL_SECRET` 必须完全一致，否则所有业务接口都是 403。

### 4. 启动

```bash
mvn clean install -DskipTests          # saas-common 先装进本地仓库，三个服务依赖它
```

跑服务：在各模块 `src/main/java` 下的主类（`GatewayApplication` / `TenantApplication` / `AiApplication`）直接 IDEA 运行，三个都要注册到同一个 Nacos。

命令行启动需要先给可运行模块补上插件——本仓库各模块 pom 都没有声明 `spring-boot-maven-plugin`，直接 `mvn spring-boot:run` 会得到 `No plugin found for prefix 'spring-boot'`：

```xml
<!-- 加到 saas-gateway / saas-tenant-service / saas-ai-service 的 <build><plugins> 里 -->
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <version>3.5.6</version>
</plugin>
```

加上之后 `mvn -pl saas-gateway spring-boot:run` 与 `java -jar target/saas-gateway-1.0.0-SNAPSHOT.jar` 才可用。


## 验证隔离效果（照着打一遍就能看出行为）

```bash
# ① 登录拿 token
TOKEN=$(curl -s -X POST http://localhost:8080/api/tenant/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"123456","tenantId":"tenant_001"}' | jq -r .data.token)

# ② 上下文透传：返回 "当前租户是: tenant_001"
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/tenant/test/tenant

# ③ 行级隔离：/test/users 只返回 tenant_001 的两个用户，看不到 bob
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/tenant/test/users

# ④ 角色鉴权：alice 是 USER，@RequireRole 抛 SecurityException，
#    被 GlobalExceptionHandler 包成 Result{code:403}（HTTP 状态仍是 200）；换成 admin 的 token 才通
# ⑤ 伪造租户头被拒：带 tenant_001 的 token 冒充 tenant_002 → 403
curl -i -H "Authorization: Bearer $TOKEN" -H "X-Tenant-ID: tenant_002" \
     http://localhost:8080/api/tenant/test/tenant

# ⑥ 绕过网关直连服务端口 → 403「拒绝绕过网关直接访问」
curl -i -H "Authorization: Bearer $TOKEN" -H "X-Tenant-ID: tenant_001" \
     http://localhost:8081/test/tenant

# ⑦ 令牌桶限流：容量 10、每秒补 5，瞬间打 20 发会看到一批 429
for i in $(seq 1 20); do curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/tenant/test/tenant; done

# ⑧ 冻结租户：写完 Redis 状态后，该租户所有非白名单请求在网关就 403
curl -s -H "Authorization: Bearer $SUPER_ADMIN_TOKEN" \
  "http://localhost:8080/api/tenant/test/sync/tenant/status?tenantId=tenant_001&status=0"

# ⑨ 切独立库：VIP 租户的数据源路由写进 Redis，后续 SQL 打到 vip_db
curl -s -X POST -H "Authorization: Bearer $SUPER_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant_003","dbKey":"vip"}' \
  http://localhost:8080/api/tenant/config/tenant/update-db-route

# ⑩ 跨线程透传：子线程日志里应能打出同一个 tenantId（TtlRunnable + TaskDecorator）
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/tenant/test/async
```

## 接口一览

走网关时统一带 `/api/tenant` 前缀（`StripPrefix=2` 后转发到服务的 `/...`），下表写的是服务内路径。

| 方法 | 路径 | 说明 | 需要什么 |
| --- | --- | --- | --- |
| POST | `/auth/login` | 用户名 + 密码 + tenantId 换 JWT | 免 Token（网关白名单） |
| GET | `/test/tenant` | 回显当前线程的租户上下文 | Bearer Token |
| GET | `/test/users` | 本租户用户列表，验证行级隔离 | TENANT_ADMIN / SUPER_ADMIN |
| GET | `/test/redis/set?key=&value=` | 观察 Redis Key 是否被加了租户前缀 | Bearer Token |
| GET | `/test/sync/tenant/status?tenantId=&status=` | 写租户状态，验证网关熔断 | Bearer Token |
| GET | `/test/feign` | 租户服务 → ai-service 的 Feign 调用，验证跨进程透传 | Bearer Token |
| GET | `/test/async` | 提交异步任务，验证跨线程透传 | Bearer Token |
| POST | `/config/tenant/update-db-route` | 切换租户数据源并同步 Redis 路由 | SUPER_ADMIN |
| GET | `/inner/tenant/active-list` | 服务间内部接口：启用中的租户列表 | 当前实现免鉴权，见「已知局限」 |
| GET | `/api/ai/chat?message=` | ai-service 对话，先扣 Redis 配额 | Bearer Token |

拒绝分两层：**网关层直接给 HTTP 状态码**（401 无 Token 或 Token 解析失败 / 403 伪造租户头或租户被冻结 / 429 触发限流），响应体为空；**服务层走统一响应体** `Result{code, message, data}`，HTTP 仍是 200，例如角色不足时 `code=403`、绕过网关直连时 `TenantFilter` 直接写 HTTP 403 带 JSON 体。

## Redis Key

| Key | 类型 | 谁写 | 谁读 | 说明 |
| --- | --- | --- | --- | --- |
| `tenant:status:{tid}` | String | 租户服务 | 网关 | 0=冻结，网关直接 403 |
| `tenant:db_route:{tid}` | String | `TenantConfigController` | `TenantDsProcessor`、`MybatisPlusConfig` | 值为 `vip` 时切独立库并跳过行级隔离 |
| `rate_limit:ip:{ip}` | Hash | 网关 Lua | 网关 Lua | 登录接口桶，`last_refill_time` / `tokens`，TTL 60s |
| `rate_limit:tenant:{tid}` | Hash | 同上 | 同上 | 业务接口按租户桶，防止单租户挤占配额 |
| `{tid}:业务key` | 任意 | 业务 | 业务 | 由 `TenantRedisKeySerializer` 自动加前缀 |
| `ai:quota:{tid}` | String | ai-service Lua | ai-service | 对话配额扣减 |

## 测试

```bash
mvn test          # 70 个用例，全部不连数据库/Redis/Nacos
```

| 模块 | 测试类 | 用例 | 覆盖点 |
| --- | --- | --- | --- |
| saas-common | `JwtUtilsTest` | 6 | 签发解析、篡改签名、换密钥、过期 Token、密钥长度不足 fail-fast |
| saas-common | `TenantContextTest` | 3 | 上下文设置/清理、跨线程池可见性 |
| saas-common | `TenantRedisKeySerializerTest` | 7 | 前缀拼接、系统级 Key 白名单、重复拼接防护、无上下文兜底、跨租户物理 Key 隔离 |
| saas-gateway | `GlobalTenantFilterTest` | 12 | OPTIONS 放行、登录白名单盖章、无 Token 401、伪造租户头 403、冻结 403、头覆盖 |
| saas-gateway | `RateLimitFilterTest` | 7 | IP/租户两种 Key 维度、Lua 参数顺序、放行与 429，含限流事故回归（脚本返回空 Flux 必须放行） |
| saas-tenant-service | `TenantFilterTest` | 6 | 缺密钥 403、密钥错误 403、上下文写入与 finally 清理、`/auth/` 与 `/inner/` 放行分支 |
| saas-tenant-service | `AuthAspectTest` | 8 | 角色命中/不命中、多角色数组、无注解方法、SUPER_ADMIN 特权 |
| saas-tenant-service | `MybatisPlusTenantInterceptorTest` | 11 | 从拦截器链里取出真实的 `TenantLineHandler`，断言 `sys_tenant`/SUPER_ADMIN/VIP/Redis 异常四条 `ignoreTable` 决策与无上下文兜底值 |
| saas-tenant-service | `TenantDsProcessorTest` | 6 | `matches` 前缀判定、无上下文回落 master、命中 vip 路由、Redis 返回空 |
| saas-tenant-service | `ThreadPoolTenantContextTest` | 4 | TTL 透传到工作线程、线程复用不串号、交替租户提交 |

用例都是纯逻辑 + Mockito，不起 Spring 上下文；`@SpringBootTest` 一个都没有，所以 `mvn test` 不需要任何外部中间件。需要真实 Redis 才能跑的 Lua 脚本级用例（限流桶的补牌精度）没有写进来，属于当前覆盖的空白。

## 目录结构

```
saas-parent
├── saas-common          JWT / TenantContext / Feign 拦截器 / Redis Key 序列化器 / @RequireRole
├── saas-gateway         filter: GlobalTenantFilter(-100) RateLimitFilter(-50) ; rate_limit.lua
├── saas-tenant-service  filter: TenantFilter ; config: MybatisPlus / TenantDsProcessor /
│                        ThreadPool / Redis / AuthAspect ; controller: auth / config / inner / test
├── saas-ai-service      Spring AI 调 DeepSeek + ai_quota.lua 配额扣减
└── index.html           Element Plus 写的单文件管理后台（浏览器直接打开，无需构建）
```

## 已知局限

- **`/inner/**` 在租户 Filter 里先于密钥校验放行**，等于内部接口对能直连 8081 的调用方不设防；当前只用于服务间联调，上生产必须补内部鉴权。
- `TestController` 是压测与演示用的调试接口，能任意写 Redis Key、冻结任意租户、`KEYS *` 扫库，同样不应出现在生产环境。
- CORS 配置是 `allowedOriginPatterns: "*"` + 允许任意方法与头，本地联调方便，生产需要收敛到具体域名。
- 令牌桶用秒级整数时间戳（`System.currentTimeMillis()/1000`），速率精度只有 1 秒；容量与速率硬编码在过滤器里，未做成配置项。
- `TenantDsProcessor` 只在 Redis 命中时切库，缓存缺失直接回落 `master`，没有回源 MySQL 并回填，路由信息依赖 `TenantConfigController` 主动写入。
- JWT 无刷新与吊销机制，密钥轮换需要重启服务；`role` 写在 Token 里，改角色后旧 Token 仍然有效直到过期。
- 网关、Redis、MySQL 都是单实例，未涉及 Redis 集群下 Lua 的跨 slot 限制，也没有 CI 和多环境配置。
- ai-service 只是把 Spring AI 接到第三方 API 上做配额演示，不含模型侧逻辑。
