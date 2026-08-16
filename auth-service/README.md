# `auth-service`（认证服务）

这是 ai-group 里管「账号」的服务：注册、登录、登出、查当前用户都归它。用户身份的源头在这里，浏览器会话由 Sa-Token 保存到 Redis。

它跑在端口 `8081`，账号数据存在 `auth_db`（认证库）。

---

## 对外接口

都在 `/api/auth` 下：

| 接口 | 作用 |
| --- | --- |
| `POST /api/auth/register` | 注册。密码用 `BCrypt`（加盐哈希）存储 |
| `POST /api/auth/login` | 登录。成功后设置 HttpOnly Sa-Token Cookie |
| `POST /api/auth/logout` | 登出。清除 Sa-Token 会话并清空 Cookie |
| `GET /api/auth/me` | 查当前登录用户（读 Sa-Token 会话） |
| `GET /api/auth/profile` | 查当前用户资料（读网关注入的身份头） |

其中注册、登录在网关白名单里（不需要先登录）；`me` / `profile` 和登出需要有效会话。

---

## 两个关键设计

### 1. Sa-Token 会话

浏览器只使用 HttpOnly、SameSite Cookie，不把身份令牌写入 localStorage。会话存在 Redis，登出立刻失效。

### 2. 注册与积分账户解耦

注册成功后，auth 在自己的事务中写入 `auth_outbox_event`，由 RabbitMQ 投递
`UserRegistered` 事件；member 消费事件并幂等创建免费积分账户。Auth 不直接访问 Member 数据库，也不通过同步 HTTP 调用耦合 Member。

---

## 本地运行

依赖 `MySQL`（数据库，`auth_db`）、`Redis`（会话）、`RabbitMQ`（注册事件）、`Nacos`（注册中心）。表结构在 `src/main/resources/schema.sql`。

跟平台一套启动脚本走：

```powershell
docker compose --env-file .env -f dev-ops/compose/docker-compose.full.yml up --build
```

单独跑（先确认中间件就绪）：

```bash
cd auth-service && mvn spring-boot:run
```

配置在 `application.yml`，通过环境变量覆盖连接信息和 Cookie 安全策略。

---

## 相关代码

- `AuthController`（认证接口）：登录 / 注册 / 登出 / 资料入口。
- `AuthServiceImpl`（认证服务实现）：注册、登录、登出的主逻辑。
- `AuthOutboxService`（注册事件可靠投递）：事务外盒、Rabbit publisher confirm 和失败重试。
- `AuthOutboxPublishJob`：XXL-JOB `authOutboxPublishJob` 扫描 Outbox；本地 `@Scheduled` 仅作无 Admin 兜底。
- `SecurityConfig`（安全配置）：密码加密、请求放行规则。
- `User`（用户实体）、`UserMapper`（用户数据访问）。

---

## 安全提醒

- 密码只存 `BCrypt` 哈希，任何情况下都不要记录明文。
- Cookie / 内部令牌 / 签名密钥走配置和环境变量，不要硬编码，也不要用默认值上生产。
