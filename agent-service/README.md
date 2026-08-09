# 熊博士 Agent Service

这是唯一的 Python Agent 服务入口。它负责 LangGraph 运行、Postgres checkpoint、证据/报告、SSE 事件和 Token 计费；浏览器不能直接访问它，所有请求由 Java BFF 透传并附加 Gateway 生成的 HMAC 身份信封。

## Token 计费口径

Agent 按模型返回的输入/输出 Token 逐个结算，不按 1K Token 向上取整：

当前费率为输入每百万 Token 5 积分、输出每百万 Token 30 积分。内部按微积分计算，
因此实际扣费公式为：`扣费微积分 = 输入 Token × 5 + 输出 Token × 30`。

Member 侧 `1 积分 = 1,000,000 微积分`。运行开始时显示的预留积分只是额度冻结，运行结束后会按实际 Token 用量确认扣费并释放未使用部分。

## 代码边界

| 目录 | 责任 |
|---|---|
| `app/router` | FastAPI 入站协议与 SSE |
| `app/agents` | LangGraph 状态、节点、工具和子图 |
| `app/service` | Run 应用服务、事件总线、Token 计费、证据与关注列表 |
| `app/models` / `app/alembic` | Postgres 持久化和全新 Agent Schema |
| `app/security` | Java Gateway HMAC 身份校验 |
| `app/service/llm` | Provider 路由、重试、用量捕获和价格版本 |

目录名沿用参考项目的核心运行时领域边界，但仓库不保留第二份参考项目；对外品牌和运行配置全部属于“熊博士”。

## 本地运行

```powershell
python -m pip install -r app/requirements.txt
$env:PYTHONPATH = "app"
alembic -c app/alembic.ini upgrade head
uvicorn main:app --host 0.0.0.0 --port 8090
```

生产/Compose 环境必须设置 `INTERNAL_TOKEN`、`IDENTITY_SIGNING_SECRET`、Postgres DSN 和至少一个 LLM Provider。`ALLOW_ANONYMOUS_DEV=true` 只用于隔离开发测试。
