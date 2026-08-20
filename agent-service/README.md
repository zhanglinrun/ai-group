# ai-group Agent Service

这是唯一的 Python Agent 服务入口。它负责 LangGraph 运行、Postgres checkpoint、证据/报告、SSE 事件和 Token 计费；浏览器不能直接访问它，所有请求经 Gateway 转发并附加签发的 HS256 内部 JWT。Agent 用 PyJWT 验签，不接入 Sa-Token。

## Token 计费口径

Agent 按模型返回的输入/输出 Token 逐个结算，不按 1K Token 向上取整：

当前费率为输入每百万 Token 5 积分、输出每百万 Token 30 积分。内部按微积分计算，
因此实际扣费公式为：`扣费微积分 = 输入 Token × 5 + 输出 Token × 30`。

Member 侧 `1 积分 = 1,000,000 微积分`。运行开始时显示的预留积分只是额度冻结，运行结束后按实际 Token 用量 confirm。缺 usage 或 Member 暂时不可达时进入 `PENDING_RECONCILIATION`，由 `app/service/billing_settlement.py` 进程内扫描重试，不估算、不自动吐回。`ALLOW_ANONYMOUS_DEV` 默认关闭，只在隔离单测里打开。

## 代码边界

| 目录 | 责任 |
|---|---|
| `app/router` | FastAPI 入站协议与 SSE |
| `app/agents` | LangGraph 状态、节点、工具和子图 |
| `app/service` | Run 应用服务、事件总线、Token 计费、进程内结算扫描、证据与关注列表 |
| `app/models` / `app/alembic` | Postgres 持久化和全新 Agent Schema |
| `app/security` | Gateway HS256 内部 JWT 校验 |
| `app/service/llm` | Provider 路由、重试、用量捕获和价格版本 |

目录按运行时职责划分：入站协议、图编排、应用服务、持久化与身份校验。工程名是 ai-group；前端产品品牌仍是熊博士。

## 本地运行

```powershell
python -m pip install -r app/requirements.txt
$env:PYTHONPATH = "app"
alembic -c app/alembic.ini upgrade head
uvicorn main:app --host 0.0.0.0 --port 8090
```

生产/Compose 环境必须设置 `INTERNAL_TOKEN`、`IDENTITY_SIGNING_SECRET`、Postgres DSN 和至少一个 LLM Provider。`ALLOW_ANONYMOUS_DEV=true` 只用于隔离开发测试。内部 JWT 的 `iss`/`aud` 与 Java Gateway 一致：`ai-group-gateway` / `ai-group-internal`。

Compose 下 Agent 用 HTTP Naming 注册 Nacos（服务名 `agent-service`）。注册/心跳失败只打日志并重试，不把进程打死。Gateway 在 Compose 里用 `GATEWAY_ROUTE_AGENT_URI=http://agent-service:8090` 直连 Docker DNS（不赌 Python Nacos 成功）；local profile 直连 `127.0.0.1:8090`。浏览器只打 Gateway，看不到 Agent 端口。
