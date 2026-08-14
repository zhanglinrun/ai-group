# `gateway-service`（API 网关）

这是 `ai-group`（平台层）的统一入口。所有前端请求都先到这里，由它校验登录、识别用户身份，再转发到后面的各个服务。它基于 `Spring Cloud Gateway`（响应式网关），跑在 `WebFlux`（响应式 Web 框架）上。

一句话概括它的职责：**在请求进入业务系统之前，先把「你是谁、能不能进」这件事定下来。**

---

## 它主要做四件事

### 1. 校验 Sa-Token 会话

浏览器通过 HttpOnly Cookie（或 `Authorization: Bearer` 里的 Sa-Token 值）携带会话。网关用共享 Redis 中的 Sa-Token 存储校验登录态；不过就直接挡回 `401`，不会到达下游。

### 2. 注入可信身份头，剥离伪造身份头

会话校验通过后，网关签发约 60 秒的 HS256 内部 JWT（`X-Internal-Jwt`），并继续注入 `X-User-Id` / `X-Username` / `X-Role`、内部令牌。白名单请求会主动剥掉外部可能伪造的身份头（含伪造 JWT）。平台 Java 服务验 JWT 后再绑定用户；拼团/支付模块本轮仍主要信网关隔离 + `X-User-Id`。

### 3. 放行白名单和内部回调

- **白名单**：登录、注册、支付宝异步回调等，不需要登录就能访问。
- **内部回调**：拼团成团通知等服务间回调，用共享内部令牌校验。

### 4. 处理跨域

`GlobalCorsConfig`（全局跨域配置）统一处理 `CORS`（跨域），让前端能正常调用。

---

## 端口与路由

- 服务端口：`8080`
- 路由和下游服务通过 `Nacos`（注册中心）发现，转发到 auth、member、bff、group、pay、agent 等服务。

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
