# `auth-service`（认证服务）

这是 `ai-group`（平台层）里管「账号」的服务：注册、登录、登出、刷新令牌都归它。用户身份的源头在这里，网关后续校验的 `JWT`（登录令牌）也是这里签发的。

它跑在端口 `8081`，账号数据存在 `auth_db`（认证库）。

---

## 对外接口

都在 `/api/auth` 下：

| 接口 | 作用 |
| --- | --- |
| `POST /api/auth/register` | 注册。密码用 `BCrypt`（加盐哈希）存储 |
| `POST /api/auth/login` | 登录。成功后返回访问令牌 + 刷新令牌 |
| `POST /api/auth/refresh` | 用刷新令牌换一对新令牌 |
| `POST /api/auth/logout` | 登出。把当前访问令牌加入黑名单 |
| `GET /api/auth/profile` | 查当前登录用户信息 |

其中注册、登录、刷新在网关白名单里（不需要先登录）；`profile` 和登出需要带有效令牌。

---

## 两个关键设计

### 1. 双令牌 + 刷新令牌轮换

登录后发两个令牌：

- **访问令牌**：有效期短，日常请求带它。
- **刷新令牌**：有效期长，专门用来换新的访问令牌。

刷新时用的是「原子轮换」：拿旧刷新令牌换新令牌的一瞬间，旧的立即作废（`Redis`（缓存）里 `getAndDelete` 一步取出并删除）。这样即使旧刷新令牌泄露，也没法重复使用，降低被盗用的风险。

### 2. 登出即拉黑

登出时把当前访问令牌写进 `Redis` 黑名单。网关每次校验都会查黑名单，所以登出后哪怕令牌还没到期，也立刻失效。

刷新令牌的存取逻辑在 `RefreshTokenStore`（刷新令牌存储），`Redis` 实现是 `RedisRefreshTokenStore`。

---

## 和其他服务的关系

注册成功后，auth 会调用 member 服务给新用户开一个免费账户（`initFree`），让用户一注册就有基础配额。这个调用走服务间内部令牌鉴权，对应代码 `MemberClient`（member 调用客户端）。

---

## 本地运行

依赖 `MySQL`（数据库，`auth_db`）、`Redis`（缓存）、`Nacos`（注册中心）。表结构在 `src/main/resources/schema.sql`。

跟平台一套启动脚本走：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File docs/dev-ops/start-full-stack.ps1
```

单独跑（先确认中间件就绪）：

```bash
cd auth-service && mvn spring-boot:run
```

配置在 `application.yml`（主配置）和 `application-local.yml`（本地配置）。

---

## 相关代码

- `AuthController`（认证接口）：五个接口入口。
- `AuthServiceImpl`（认证服务实现）：注册、登录、刷新、登出的主逻辑。
- `RedisRefreshTokenStore`（刷新令牌存储实现）：刷新令牌的原子轮换。
- `SecurityConfig`（安全配置）：密码加密、请求放行规则。
- `User`（用户实体）、`UserMapper`（用户数据访问）。

---

## 安全提醒

- 密码只存 `BCrypt` 哈希，任何情况下都不要记录明文。
- `JWT` 密钥、内部令牌走配置和环境变量，不要硬编码，也不要用默认值上生产。
- 刷新令牌的「取出即删除」是防重放的关键，改这块逻辑时别把原子性弄丢了。
