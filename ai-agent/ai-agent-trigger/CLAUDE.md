[Agent 项目说明](../README.md) > **ai-agent-trigger**

# ai-agent-trigger 模块

## 职责

六模块结构中的入口与协议适配层，包根为 `com.linrun.agent.trigger`。本模块负责：

- `http`：HTTP/SSE、鉴权上下文、会话、文件、后台管理、Skill/MCP 与数据问答入口；
- `service`：将外部请求收敛为 `AgentRequest`，完成 owner、模型白名单、会话归属与用户并发守卫；
- `stream`：把 domain `Printer` 事件适配为对外响应，并提供无头任务输出；
- `job`：把有效调度配置转成非交互式 `AgentRequest` 后交给 domain dispatch。

`SseEmitter` 不得穿透到 domain。产品路由、模型—工具循环、Todo、权限和完成门由
domain 负责；trigger 不建立重复的执行层。

## Agent 请求入口

主要流式入口：

- `POST /web/api/v1/gpt/queryAgentStreamIncr`

请求使用 `executionMode: AUTO | STANDARD | DEEP`。三个值进入同一个 Agent Loop；
`outputStyle` 只用于输出样式适配。`DataAgentController` 提供独立的数据问答入口，
两者都不应借模式字段创建另一套自由循环。

会话历史入口：

- `GET /api/agent/conversation/sessions`
- `GET /api/agent/conversation/sessions/{sessionId}`
- `DELETE /api/agent/conversation/sessions/{sessionId}`

历史详情返回 `executionMode`，不返回运行恢复控制字段。

## Canonical SSE 协议

前端和外部调用方只依赖以下运行事件：

```text
run_started
phase_changed
todo_snapshot
tool_call
tool_result
verification_started
verification_result
completion_blocked
run_finished
result
```

此外保留工具专属的 browser、file、report、image 等 artifact 事件。`run_finished` 必须先于终态
`result` 发出；终态 payload 的 `status`、`runStatus`、`completionGatePassed` 和 `stopReason`
是权威状态，只有 `SUCCESS + completionGatePassed=true` 才表示成功。

## 入口与 SSE 适配

- `GptQueryIngressService`：在 Servlet 线程完成 owner、请求 ID、模型和会话归属校验，
  再将请求交给 `IAgentDispatchService`。
- `PerUserConcurrencyLimiter`：主入口的用户级并发守卫，不替代 domain run budget。
- `SseEmitterAgentSessionStream`：实现 domain `AgentMessageStream` 端口。
- `SseLifecycleSupport`：心跳、完成和错误生命周期。
- `SseClientDisconnectDetector`：识别客户端断开。
- `AgentSessionPrinter`：将 canonical event 与 artifact event 写入 `AgentMessageStream`。
- Trigger 不解释 Todo 或 CompletionGate 规则，只做协议适配和终态字段传递。

## 其他入口

| Controller | 职责 |
| --- | --- |
| `AgentConversationHistoryController` | 会话列表、详情、删除 |
| `AgentFileController` | 会话文件上传 |
| `AgentMemoryController` | 记忆检查 |
| `AgentModelController` | 可用模型 |
| `AgentImageGenerationController` | 工作区生图 |
| `UserSkillController` | 用户 Skill 扩展 |
| `UserMcpController` | 用户 MCP 扩展 |
| `DataAgentController` | 独立数据问答入口 |

## 边界规则

- Controller 依赖 trigger service 或 domain 稳定端口，不直接调用 Harness 内核或 DAO。
- 不在 trigger 根据 `executionMode` 复制路由逻辑。
- 不把内部模型草稿或完整 chain-of-thought 暴露给 SSE 客户端。
- 认证后的 owner 身份由可信上下文注入，不能接受请求体伪造 owner。

## 应用编排主链

```text
AiAgentController
  -> GptQueryIngressService
     -> IAgentDispatchService
       -> AgentDispatchService (domain)
            -> AgentLoopExecuteStrategy -> AgentRuntime
```

`AgentDispatchService` 只保留统一 Agent Loop 产品路径。`AUTO / STANDARD / DEEP`
是同一 Agent Loop 的执行强度，不得重新扩展成多个策略 Agent；历史兼容不能成为 HTTP 或调度入口。

## 定时任务

- `AgentTaskJob` 从 domain `ITaskService` 读取有效/失效调度，过滤不完整配置。
- `ScheduledAgentTaskExecutor` 为每次触发生成新的 request/session ID，使用
  `HeadlessAgentSessionStream` 同步执行统一 Agent Loop。
- 定时任务属于系统内部路径，当前不绑定用户身份，也不经过交互式 SSE。

## 关键路径

| 路径 | 说明 |
| --- | --- |
| `http/AiAgentController.java` | 主 Agent HTTP/SSE 入口 |
| `http/agent/AgentConversationHistoryController.java` | 历史接口 |
| `http/reactor/support/SseEmitterAgentSessionStream.java` | SSE 输出适配 |
| `http/auth/GatewayUserContextFilter.java` | 用户上下文 |
| `service/GptQueryIngressService.java` | 请求准备与 domain dispatch 入口 |
| `service/PerUserConcurrencyLimiter.java` | 用户级并发守卫 |
| `stream/AgentSessionPrinter.java` | canonical event 适配 |
| `stream/HeadlessAgentSessionStream.java` | 非交互式输出端口 |
| `job/AgentTaskJob.java` | 调度数据提供者 |
| `job/ScheduledAgentTaskExecutor.java` | 调度请求构建与执行 |
