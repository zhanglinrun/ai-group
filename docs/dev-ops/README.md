# AI Group 本地开发与启动

## 1. 基础设施（Docker）

```powershell
Set-Location docs/dev-ops
if (-not (Test-Path ../../.env)) { Copy-Item ../../.env.example ../../.env }
docker compose --env-file ../../.env -f docker-compose-platform.yml up -d
```

| 服务 | 端口 | 默认账号 |
|------|------|----------|
| MySQL | 13306 | root / 123456 |
| Redis | 16379 | 无密码 |
| RabbitMQ | 5672 / 15672 | admin / admin |
| Nacos | 8848 | nacos / nacos |
| Qdrant | 6333 | 无 Key |
| MinIO | 9000 / 9001 | minioadmin / minioadmin |

根目录 `.env` 同时提供基础设施凭据与各服务配置；不传 `--env-file` 时，Compose 使用表中的本地开发默认值。

## 2. 初始化数据库

Docker 首次启动会自动执行 `docs/dev-ops/mysql/sql/00-init-schemas.sql` 创建库。

> Compose 这里只创建数据库，不会递归执行下面各子目录的建表、迁移和 seed。首次运行完整业务请使用本页后面的 `start-full-stack.ps1`；`start-platform.ps1` 只初始化平台层的 auth/member。

各服务 schema：

- `auth_db` — `auth-service/src/main/resources/schema.sql`
- `member_db` — `member-service/src/main/resources/schema.sql`
- `agent_db` — `docs/dev-ops/mysql/sql/agent_db/01-agent_db.sql`（或 `ai-agent/docs/dev-ops/mysql/sql/`）；
  `03-agent-loop-migrate.sql` 迁移统一 Agent Loop，`04-task-graph.sql` 创建 ChatGPT Work 工作区、任务图、依赖与事件结构，
  `05-dialogue-run-role.sql` 为旧库补齐固定角色快照列，`06-dialogue-run-claim-hardening.sql`
  增加请求指纹、运行截止时间与 worker 心跳恢复字段，`07-quota-settlement-command.sql` 建立
  provider 调用前落库的 durable 额度结算命令，`08-tool-result-replay.sql` 分离保存 UI `tool_result`
  与模型 `llm_observation`；一键启动会在 seed 前按
  `03 → 04 → 05 → 06 → 07 → 08` 顺序幂等执行这些迁移
- `group_buy_market` — `group/docs/dev-ops/mysql/sql/`
- `s_pay_mall_ddd_market` — `s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/`

## 3. 微服务启动顺序

仓库根目录采用 **docAI 风格** 扁平布局：`pom.xml` 父工程 + `ai-group-common`、`gateway-service`、`auth-service`、`member-service`、`bff-service` 与 `group/`、`s-pay-mall-ddd-market/`、`ai-agent/` 并列。

平台基础服务启动：

```powershell
cd docs/dev-ops
# 仅启动平台层时使用
./start-platform.ps1

# 默认 member 使用 18082，避免与 ai-interview 的 8082 重叠；仍可指定其他空闲端口
./start-platform.ps1 -MemberPort 19082
```

手动启动（仅用于已经成功执行过一次 `start-full-stack.ps1` 的数据库环境）：

> 每个独立终端都必须先从仓库根 `.env` 导入同一套环境变量。尤其是
> `AI_GROUP_INTERNAL_TOKEN`：Gateway 与 ai-agent 若分别落到不同默认值，JWT
> 登录仍可能成功，但 Agent 请求会因无法验证网关身份而缺失 ownerId。

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

function Import-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
        if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
        $key, $value = $_ -split '=', 2
        $key = $key.Trim()
        $value = $value.Trim().Trim('"')
        if ($key) { Set-Item -Path "env:$key" -Value $value }
    }
}

# 在每个用于启动 Java/Python 服务的终端先执行：
Import-DotEnv (Resolve-Path '.\.env')

# 1. 基础设施已启动后
mvn clean install -DskipTests
Push-Location group
mvn clean install -DskipTests
Pop-Location
Push-Location s-pay-mall-ddd-market
mvn clean install -DskipTests
Pop-Location
Push-Location ai-agent
mvn clean install -DskipTests
Pop-Location

# 2. 按顺序启动（以下每条从仓库根目录在独立终端执行）
mvn -f gateway-service/pom.xml spring-boot:run       # :8080
mvn -f auth-service/pom.xml spring-boot:run          # :8081
mvn -f member-service/pom.xml spring-boot:run '-Dspring-boot.run.arguments=--server.port=18082' # :18082
mvn -f bff-service/pom.xml spring-boot:run           # :8083

# 3. 业务服务
mvn -f group/group-buy-market-app/pom.xml spring-boot:run                   # :8091
mvn -f s-pay-mall-ddd-market/s-pay-mall-ddd-app/pom.xml spring-boot:run     # :8070
mvn -f ai-agent/ai-agent-app/pom.xml spring-boot:run                   # :8090

# 4. Python 工具（可选；首次先执行 uv sync 与 SQLite 初始化）
Set-Location ai-agent/reactor-tool
uv sync
uv run python -m reactor_tool.db.db_engine
./start.ps1                                                   # :1601（Linux/macOS 用 ./start.sh）

# 5. 前端
Set-Location ai-agent/ui
pnpm install
pnpm dev                                                       # :5173（host:true 同时支持 IPv4/IPv6）
```

一键启动（基础设施 + 微服务 + group/pay + ai-agent + reactor-tool + 前端）：

```powershell
cd docs/dev-ops
./start-full-stack.ps1

# 默认 member=:18082，不占用 ai-interview=:8082；需要时可覆盖
./start-full-stack.ps1 -MemberPort 19082

# 可选：同时启动 Prometheus/Grafana/ELK 等高资源观测组件
./start-full-stack.ps1 -IncludeObservability
```

`start-full-stack.ps1` 会自动：

- 默认只启动业务必需的 MySQL、Redis、RabbitMQ、Nacos、Qdrant 和 MinIO；观测栈须显式传 `-IncludeObservability`；
- 幂等初始化额度包、`agent_db` 的 Agent Loop / `todo_write` / TaskGraph 迁移、拼团和支付演示结构；
- 从根 `.env` 通过进程环境注入配置，不把模型 Key、内部令牌或数据库密码拼进命令行；
- 将 `REPORT_MODEL` 默认同步为主聊天模型，避免 report_tool 隐式调用未配置模型；
- 构建并启动 7 个 Java 服务、reactor-tool 和 Vite；
- 等待 Agent、Python 工具与前端真实就绪；
- 自动执行注册、额度包权益、撤销策略和入口安全 smoke。

真实支付宝默认关闭（`ALIPAY_ENABLED=false`）。本地联调可使用 `verify-e2e.ps1` 创建两个隔离账号，依次完成
开团、首个成员模拟支付后继续开放、第二个成员参团支付、显式封团、真实 group 结算和 MQ 双方权益到账；
脚本不再直接修改支付数据库，也不伪造 group 成团回调。

## 4. 访问入口

- 前端：**http://localhost:5173/login**（`127.0.0.1:5173` 亦可；需先 `cd ai-agent/ui && pnpm dev`）
- API 网关：http://localhost:8080
- 注册：POST `/api/auth/register`
- 登录：POST `/api/auth/login`
- 定价：GET `/api/bff/pricing`
- 聊天：经 Gateway `/api/agent/**` 与 `/web/**`（SSE）；需 `agent_db` 有 `channel=fix` 角色且 ai-agent 开启 `spring.ai.agent.auto-config.enabled=true`
- 拼团定价：GET `/api/bff/pricing` 返回三个额度包 SKU 及各自 `groupGoodsId/groupActivityId/baseQuota`
- 订单：GET `/api/bff/orders`（需 pay:8070 运行）

## 5. 全链路验证

```powershell
./smoke-test.ps1
./smoke-benefit-event.ps1
./smoke-benefit-revoke.ps1
./smoke-security.ps1
./verify-e2e.ps1
./smoke-agent-sse.ps1
./smoke-agent-memory.ps1

# 真实深度 Agent Loop：需已配置 LLM Key
./smoke-agent-sse.ps1 -ExecutionMode DEEP -OutputStyle text `
  -Query '不要联网。先用 todo_write 创建并完成清单，再用三点解释 Agent Harness 为什么需要完成门禁。'

# Provider 凭据故障验收：必须 MODEL_ERROR、冻结归零且可用额度不变
./smoke-agent-sse.ps1 -ExpectedOutcome MODEL_FAILURE_NO_CHARGE
```

`smoke-test.ps1` 验证注册后免费余额为 `5,000,000` microcredits；`verify-e2e.ps1` 通过浏览器同源的
dev-only API 验证双用户拼团状态机和双方权益到账；`smoke-benefit-event.ps1` 验证 `QUOTA_LIGHT` 的 60 credits
订单快照到账；`smoke-benefit-revoke.ps1` 验证已发放额度的撤销进入 `REJECTED_GRANTED` 人工审核且余额不被静默扣回。
`smoke-agent-sse.ps1` 不以 HTTP 200 冒充成功：`SUCCESS` 模式必须观察到真实 Agent 生命周期帧、非空最终结果、
冻结归零和正数额度结算；`MODEL_FAILURE_NO_CHARGE` 模式必须得到 `MODEL_ERROR`、冻结归零且可用额度不变。
`smoke-agent-memory.ps1` 使用两个隔离账号验证 Qdrant 落库、同 owner 跨会话召回、不同 owner 不泄漏，并在结束时按 owner 清理测试向量。

Gateway 统一入口（含 SSE `/web/**`）；本地可用 `application-local.yml` 关闭 Nacos 并使用固定端口。内部服务调用需 `AI_GROUP_INTERNAL_TOKEN`（与 `ai-group.internal.token` 一致）。group 的 `/api/v1/gbm/trade/**` 默认开启内部鉴权（`AI_GROUP_INTERNAL_AUTH_ENABLED=true`），仅接受正式 pay→group 调用；旧静态演示页匿名直连不再受支持。临时回滚可设 `AI_GROUP_INTERNAL_AUTH_ENABLED=false`。

### 内部回调拓扑（group → pay 成团通知）

| 路径 | 说明 |
|------|------|
| **推荐（dev）** | group 直接调用 pay：`http://127.0.0.1:8070/api/v1/alipay/group_buy_notify`（见 `s-pay-mall-ddd-app/application-dev.yml`） |
| **经 Gateway** | `http://localhost:8080/api/v1/alipay/group_buy_notify`，请求头须带 `X-Internal-Token`；Gateway 校验后重新注入 token 再转发 pay |
| **禁止** | 无 token 的公网白名单放行（已移除） |

`GroupBuyNotifyService` 出站会附加 `X-Internal-Token`；`AliPayController.groupBuyNotify` 与 Gateway `AuthGlobalFilter` 均校验该头。

手动步骤（完整拼团支付链路）：
1. 注册 → 自动初始化 5 credits 月度免费额度
2. 账号 A 在定价页选择额度包并“自己开团”
3. A 点击“模拟支付并等待参团”；此时只登记成员支付，队伍仍出现在拼团大厅
4. 账号 B 注册/登录后加入 A 的队伍并完成模拟支付
5. B 点击“第二个成员支付后，封团并结算”，group 按当前已支付成员封团
6. pay 消费成团通知并写入 outbox，member-service 经 MQ 为 A、B 分别发放基础额度与已达档位加赠
7. 进入聊天 → 每次 LLM 调用预留额度 → 成功调用按 provider usage（不可用或 `0/0` 时使用有界本地估算）结算并释放余量；供应商拒绝且没有 usage/输出证据时释放整笔预留

## 6. 构建验证

> 平台基线：**JDK 21**、Spring Boot 3.5.16、Spring Cloud 2025.0.3、Spring Cloud Alibaba 2025.0.0.0。

```powershell
$ErrorActionPreference = 'Stop'
mvn clean install -DskipTests
Push-Location gateway-service; mvn test; Pop-Location
Push-Location group; mvn test; Pop-Location
Push-Location s-pay-mall-ddd-market; mvn test; Pop-Location
Push-Location ai-agent; mvn test; Pop-Location
Push-Location ai-agent/ui; pnpm test; pnpm build; Pop-Location
```

- `group`：默认跑 infrastructure 单元测试（内部 token 回调头）；app 模块集成测需 MySQL，已排除。
- `ai-agent`：默认跑离线单元测试；全量 Spring 集成测与外部 LLM/MCP 测在 surefire excludes 中。

强制执行仓库内置 FastMCP 的 Java/Python STDIO 互操作测试：

```powershell
$ErrorActionPreference = 'Stop'
Push-Location '../../ai-agent'
try {
  mvn -pl ai-agent-app -am -Pmcp-stdio-it `
    '-Dtest=McpStdioInteropTest' `
    '-Dsurefire.failIfNoSpecifiedTests=false' test
} finally {
  Pop-Location
}
```
