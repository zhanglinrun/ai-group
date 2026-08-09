# `auth-service`（认证服务）

这是熊博士平台里管「账号」的服务：注册、登录、登出、刷新会话都归它。用户身份的源头在这里，浏览器会话由 Sa-Token 保存到 Redis。

它跑在端口 `8081`，账号数据存在 `auth_db`（认证库）。

---

## 对外接口

都在 `/api/auth` 下：

| 接口 | 作用 |
| --- | --- |
| `POST /api/auth/register` | 注册。密码用 `BCrypt`（加盐哈希）存储 |
| `POST /api/auth/login` | 登录。成功后设置 HttpOnly Sa-Token Cookie |
| `POST /api/auth/refresh` | 用刷新令牌换一对新令牌 |
| `POST /api/auth/logout` | 登出。把当前访问令牌加入黑名单 |
| `GET /api/auth/profile` | 查当前登录用户信息 |

其中注册、登录、刷新在网关白名单里（不需要先登录）；`profile` 和登出需要带有效令牌。

---

## 两个关键设计

### 1. Sa-Token 会话 + 兼容刷新令牌

浏览器只使用 HttpOnly、SameSite Cookie，不把身份令牌写入 localStorage。旧客户端仍可通过
`Authorization: Bearer` 走有界兼容路径，刷新令牌在 Redis 中原子轮换。

### 2. 登出即拉黑

登出时把当前访问令牌写进 `Redis` 黑名单。网关每次校验都会查黑名单，所以登出后哪怕令牌还没到期，也立刻失效。

刷新令牌的存取逻辑在 `RefreshTokenStore`（刷新令牌存储），`Redis` 实现是 `RedisRefreshTokenStore`。

---

## 和其他服务的关系

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

- `AuthController`（认证接口）：五个接口入口。
- `AuthServiceImpl`（认证服务实现）：注册、登录、刷新、登出的主逻辑。
- `AuthOutboxService`（注册事件可靠投递）：事务外盒、Rabbit publisher confirm 和失败重试。
- `RedisRefreshTokenStore`（兼容刷新令牌存储实现）：刷新令牌的原子轮换。
- `SecurityConfig`（安全配置）：密码加密、请求放行规则。
- `User`（用户实体）、`UserMapper`（用户数据访问）。

---

## 安全提醒

- 密码只存 `BCrypt` 哈希，任何情况下都不要记录明文。
- `JWT` 兼容密钥、内部令牌、Rabbit 凭据走配置和环境变量，不要硬编码，也不要用默认值上生产。
- 刷新令牌的「取出即删除」是防重放的关键，改这块逻辑时别把原子性弄丢了。
