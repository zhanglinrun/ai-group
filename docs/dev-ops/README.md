# AI Group 本地开发与启动

## 1. 基础设施（Docker）

```bash
cd docs/dev-ops
docker compose -f docker-compose-platform.yml up -d
```

| 服务 | 端口 | 默认账号 |
|------|------|----------|
| MySQL | 13306 | root / 123456 |
| Redis | 16379 | 无密码 |
| RabbitMQ | 5672 / 15672 | admin / admin |
| Nacos | 8848 | nacos / nacos |
| Qdrant | 6333 | 无 Key |
| MinIO | 9000 / 9001 | minioadmin / minioadmin |

根目录 `.env` 提供大模型、支付、MinIO 等密钥；MySQL/JWT 已内置默认值。

## 2. 初始化数据库

Docker 首次启动会自动执行 `docs/dev-ops/mysql/sql/00-init-schemas.sql` 创建库。

各服务 schema：

- `auth_db` — `auth-service/src/main/resources/schema.sql`
- `member_db` — `member-service/src/main/resources/schema.sql`
- `agent_db` — `docs/dev-ops/mysql/sql/agent_db/01-agent_db.sql`（或 `ai-agent/docs/dev-ops/mysql/sql/`）
- `group_buy_market` — `group/docs/dev-ops/mysql/sql/`
- `s_pay_mall_ddd_market` — `s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/`

## 3. 微服务启动顺序

仓库根目录采用 **docAI 风格** 扁平布局：`pom.xml` 父工程 + `ai-group-common`、`gateway-service`、`auth-service`、`member-service`、`bff-service` 与 `group/`、`s-pay-mall-ddd-market/`、`ai-agent/` 并列。

一键启动（推荐）：

```powershell
cd docs/dev-ops
# 若 8080 被旧 ai-agent 占用，加 -StopPort8080Conflict
./start-platform.ps1
```

手动启动：

```bash
# 1. 基础设施已启动后
mvn clean install -DskipTests

# 2. 按顺序启动（各开终端）
cd gateway-service && mvn spring-boot:run          # :8080
cd auth-service && mvn spring-boot:run             # :8081
cd member-service && mvn spring-boot:run           # :8082
cd bff-service && mvn spring-boot:run              # :8083

# 3. 业务服务
cd group/group-buy-market-app && mvn spring-boot:run           # :8091
cd s-pay-mall-ddd-market/s-pay-mall-ddd-app && mvn spring-boot:run  # :8070
cd ai-agent/Reactor-agent-app && mvn spring-boot:run         # :8090

# 4. Python 工具（可选）
cd ai-agent/reactor-tool && uv run python -m reactor_tool.main  # :1601

# 5. 前端
cd ai-agent/ui && pnpm install && pnpm dev                     # :5173（host:true 同时支持 IPv4/IPv6）
```

一键启动（基础设施 + 微服务 + group/pay + ai-agent + reactor-tool + 前端）：

```powershell
cd docs/dev-ops
./start-full-stack.ps1
```

`start-full-stack.ps1` 会自动：
- 初始化 `agent_db` 角色种子（`02-dev-seed.sql`）并从根 `.env` 注入 LLM API Key
- 导入 `group_buy_market` / `s_pay_mall_ddd_market` 演示数据
- 同步 `ai-agent/reactor-tool/.env`
- 启动 group(:8091)、pay(:8070)、reactor-tool(:1601)

## 4. 访问入口

- 前端：**http://localhost:5173/login**（`127.0.0.1:5173` 亦可；需先 `cd ai-agent/ui && pnpm dev`）
- API 网关：http://localhost:8080
- 注册：POST `/api/auth/register`
- 登录：POST `/api/auth/login`
- 定价：GET `/api/bff/pricing`
- 聊天：经 Gateway `/api/agent/**` 与 `/web/**`（SSE）；需 `agent_db` 有 `channel=fix` 角色且 ai-agent 开启 `spring.ai.agent.auto-config.enabled=true`
- 拼团定价：GET `/api/bff/pricing` 返回 `groupBuy.activityId`（需 group:8091 运行）
- 订单：GET `/api/bff/orders`（需 pay:8070 运行）

## 5. 全链路验证

```powershell
./smoke-test.ps1
./smoke-benefit-event.ps1
```

`smoke-test.ps1`：注册 → 登录 → 定价 → 会员摘要（FREE + 20 点）。  
`smoke-benefit-event.ps1`：模拟成团 MQ 事件 → 验证 Pro 开通与配额发放（无需支付宝沙箱）。  
`smoke-benefit-revoke.ps1`：模拟成团 → 退款撤销 MQ → 验证降级 FREE + 20 点。

Gateway 统一入口（含 SSE `/web/**`）；本地可用 `application-local.yml` 关闭 Nacos 并使用固定端口。内部服务调用需 `AI_GROUP_INTERNAL_TOKEN`（与 `ai-group.internal.token` 一致）。group 的 `/api/v1/gbm/trade/**` 默认开启内部鉴权（`AI_GROUP_INTERNAL_AUTH_ENABLED=true`），仅接受正式 pay→group 调用；旧静态演示页匿名直连不再受支持。临时回滚可设 `AI_GROUP_INTERNAL_AUTH_ENABLED=false`。

### 内部回调拓扑（group → pay 成团通知）

| 路径 | 说明 |
|------|------|
| **推荐（dev）** | group 直接调用 pay：`http://127.0.0.1:8070/api/v1/alipay/group_buy_notify`（见 `s-pay-mall-ddd-app/application-dev.yml`） |
| **经 Gateway** | `http://localhost:8080/api/v1/alipay/group_buy_notify`，请求头须带 `X-Internal-Token`；Gateway 校验后重新注入 token 再转发 pay |
| **禁止** | 无 token 的公网白名单放行（已移除） |

`GroupBuyNotifyService` 出站会附加 `X-Internal-Token`；`AliPayController.groupBuyNotify` 与 Gateway `AuthGlobalFilter` 均校验该头。

手动步骤（完整拼团支付链路）：
1. 注册 → 自动初始化 Free 会员（20 点配额）
2. 浏览定价页 → 发起拼团下单
3. 支付宝沙箱支付 → 等待成团
4. 成团后 member-service 消费 MQ → 开通 Pro
5. 进入聊天 → 预扣配额 → 对话成功确认扣费

## 6. 构建验证

> 平台基线：**JDK 21**、Spring Boot 3.5.16、Spring Cloud 2025.0.3、Spring Cloud Alibaba 2025.0.0.0。

```bash
mvn clean install -DskipTests
cd gateway-service && mvn test
cd group && mvn test
cd s-pay-mall-ddd-market && mvn test
cd ai-agent && mvn test
cd ai-agent/ui && pnpm build
```

- `group`：默认跑 infrastructure 单元测试（内部 token 回调头）；app 模块集成测需 MySQL，已排除。
- `ai-agent`：默认跑离线单元测试；全量 Spring 集成测与外部 LLM/MCP 测在 surefire excludes 中。
