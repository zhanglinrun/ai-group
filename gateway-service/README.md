# `gateway-service`（API 网关）

这是 `ai-group`（平台层）的统一入口。所有前端请求都先到这里，由它校验登录、识别用户身份，再转发到后面的各个服务。它基于 `Spring Cloud Gateway`（响应式网关），跑在 `WebFlux`（响应式 Web 框架）上。

一句话概括它的职责：**在请求进入业务系统之前，先把「你是谁、能不能进」这件事定下来。**

---

## 它主要做四件事

### 1. 校验 Sa-Token 会话

浏览器通过 HttpOnly Cookie（或 `Authorization: Bearer` 里的 Sa-Token 值）携带会话。网关用共享 Redis 中的 Sa-Token 存储校验登录态；不过就直接挡回 `401`，不会到达下游。

### 2. 注入可信身份头，剥离伪造身份头

会话校验通过后，网关签发约 60 秒的 HS256 内部 JWT（`X-Internal-Jwt`），并继续注入 `X-User-Id` / `X-Username` / `X-Role`、内部令牌。白名单请求会主动剥掉外部可能伪造的身份头（含伪造 JWT）。用户 API（Auth / Member / Group 查询与锁单 / Pay 下单 / Agent `/api/runs/**`）验 JWT 后再绑定用户；支付宝回调和补偿 Job 只认内部令牌。

### 3. 放行白名单

- **白名单**：登录、注册、支付宝异步回调等，不需要登录就能访问。
- 成团不再走 Gateway HTTP 回调；`group.team_success` 由 Pay 直接消费。服务间 `/internal/**` 仍用共享内部令牌，不经过这条用户会话过滤器。

### 4. 处理跨域

`GlobalCorsConfig`（全局跨域配置）统一处理 `CORS`（跨域），让前端能正常调用。

---

## 端口与路由

- 服务端口：`8080`
- 路由和下游服务通过 `Nacos`（注册中心）发现，转发到 auth、member、group、pay，以及 `agent-service`（`/api/runs/**` 等）。Agent 进网关路由表，但不进浏览器 Origin；JSON 45s、SSE 30 分钟分路由超时。
- 网关不做全局限流；拼团热点限流在 Group 的 Redis 固定窗口（`RateLimiterAOP`）。

核心代码：

- `AuthGlobalFilter`（全局鉴权过滤器）：上面四件事的主要逻辑都在这里。
- `GatewayIdentityHeaderSupport`（身份头处理支持）：注入可信身份、剥离伪造身份、补内部令牌。
- `GatewayConfig`（网关配置）、`GlobalCorsConfig`（跨域配置）。

---

## 本地运行

依赖 `Nacos`（注册中心）和 `Redis`（Sa-Token 会话）。通常不用单独起，跟着平台一套启动脚本走即可：

```powershell
docker compose --env-file .env -f dev-ops/compose/docker-compose.full.yml up --build
```

只想单独跑，先确认 `Nacos`、`Redis` 已就绪，再执行：

```bash
cd gateway-service && mvn spring-boot:run
```

配置文件在 `src/main/resources/application.yml`（主配置）和 `application-local.yml`（本地配置）。

---

## 安全提醒

- 白名单只放真正不需要登录的路径。
- 身份头的「注入 + 剥离」是一对，少了剥离那一半，伪造身份头就能直连下游。
- 内部 JWT 不是浏览器登录态：只在网关后一跳传播，TTL 约 60 秒，密钥与 Sa-Token 会话分离。
- 内部令牌是服务间互信凭证（`/internal/**`），必须走配置和环境变量。
