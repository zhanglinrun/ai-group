# `ai-group-common`（平台共享库）

这是 `ai-group`（平台层）几个平台服务共用的基础库。它本身不启动、不对外提供接口，只把网关、认证、额度服务、`BFF`（前端聚合层）都会用到的东西集中放在一处：统一返回结构、异常处理、身份上下文、服务间调用的安全过滤器。

把这些放进一个共享库，是为了让各服务对「一个请求里的用户是谁」「服务之间怎么互信」有一致的理解，不用各写一套。

---

## 里面主要有什么

### 统一返回与异常

- `Result`（统一返回对象）：所有平台服务的接口都用它包装返回，固定 `code` / `message` / `data` 三段。
- `BusinessException`（业务异常）、`TokenException`（令牌异常）：业务里主动抛出的异常类型。
- `GlobalExceptionHandler`（全局异常处理器）：兜底把抛出的异常转成统一 `Result`，避免异常细节直接暴露给前端。
- `ErrorCodeEnum`（错误码枚举）、`CommonConstant`（通用常量）：错误码和公共常量（请求头名、Bearer 前缀等）集中定义。

### 身份上下文

- `RequestUserContext`（请求用户上下文）：把当前请求的用户身份绑定到线程上，业务代码用 `RequestUserContext.getUserId()` 这类方法直接取，不用层层传参。

### 服务间安全

- `GatewayUserContextFilter`（网关身份上下文过滤器）：确认网关证明后验 `X-Internal-Jwt`（HS256），用 JWT claims 绑定用户；JWT 缺失或无效直接 401，不以裸 `X-User-Id` 为准。
- `InternalIdentityJwt`：Gateway 签发、下游验签的短时效内部 JWT。
- `InternalApiAuthFilter`（内部接口鉴权过滤器）：保护 `/internal/**` 这类只允许服务间调用的接口，校验共享的内部令牌。
- `InternalTokenProperties`（内部令牌配置属性）：服务之间互信用的共享令牌配置。
- `ProductionSecurityValidator`（生产安全校验器）：启动时检查生产环境下的安全配置（内部令牌与 identity signing secret 不能过短），不合规就拦下。

### 缓存

- `RedisConfig`（Redis 配置）：平台服务共用的 `Redis`（缓存）序列化等基础配置。

---

## 谁在用它

平台层的 `gateway-service`、`auth-service`、`member-service`、`bff-service` 都依赖它。两条主要用途：

- **统一表达**：返回结构、异常、错误码保持一致。
- **身份互信**：网关校验完 Sa-Token 会话后签发 HS256 内部 JWT；下游过滤器验签后再信任用户信息。

---

## 使用提醒

- 它是被依赖的库，改动会同时影响所有平台服务，动之前先想清楚影响面。
- 涉及内部令牌、身份头的部分是安全边界，改的时候要格外小心。
- 密钥、内部令牌这类敏感值走配置和环境变量，不要硬编码进代码。
