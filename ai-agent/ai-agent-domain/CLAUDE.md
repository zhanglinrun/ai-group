[Agent 项目说明](../README.md) > **ai-agent-domain**

# ai-agent-domain 模块

## 职责

核心领域层。Agent 主链路按 `runtime / service / ledger / memory / rag / role`
收敛，负责统一 Harness、领域调度与执行策略、客户端装配、会话归属、任务调度查询、
工具目录、上下文、完成条件、执行证据、记忆和仓储端口。HTTP/SSE、Feign 和
MyBatis 实现不进入本层。

## 领域服务

| 服务 | 职责 |
| --- | --- |
| `AgentDispatchService` | 将所有请求收敛到统一 Agent Loop，兼容保留 `AgentType` 作为协议字段 |
| `AgentLoopExecuteStrategy` | 组装会话记忆与输出样式，将请求交给 `AgentRuntime` |
| `AgentArmoryService` | 从仓储读取 Agent/Client 关系，经装配节点加载 Client、Model、API、Advisor 和 MCP 运行时 |
| `ConversationSessionOwnershipService` | 首次访问绑定 session owner，已有会话校验归属，删除前再做授权检查 |
| `AgentTaskService` | 通过仓储端口查询有效调度和已失效任务 ID；定时触发与无头输出留在 trigger |

`AgentDispatchService` 只有一条真实产品路径：统一 Agent Loop。
`AUTO / STANDARD / DEEP` 是同一 Agent Loop 的 profile，不是三套执行策略；已退役账本标识仅由 replay 包内只读适配器解释。

## 统一 Harness

| 类型 | 职责 |
| --- | --- |
| `AgentRuntime` | 准备 run-local 上下文、工具目录、账本与终态事件 |
| `AgentLoopFactory` | 注入权限、ordered Hooks 与 run customizer；每次创建独立 `AgentLoop / HookBus` |
| `AgentLoop` | 执行一条模型—工具循环 |
| `ContextPipeline` | 构造稳定 Prompt、当前轮上下文和工具视图 |
| `ModelGateway` | 统一模型调用、超时与取消边界 |
| `ToolDispatcher` | ID/Schema、权限、Hook、并发、执行、证据、artifact 与账本 |
| `StopGate` | turns、时间、Token、额度和重复轮次机械停止 |
| `AgentContext` | 单次运行的 owner/session、memory、tools、artifact 与 metrics |
| `AgentRunBudget` | turns、tool calls、completion attempts、时间、Token、额度上限 |
| `AgentFutureWaiter` | 将模型与工具等待 clamp 到调用上限和 run 剩余时间 |
| `CancellationToken` | 父子任务共享的结构化取消与 deadline |
| `PermissionPolicy` / `HookBus` | 当前调用权限与 Pre/Post 扩展点 |
| `ContextCompactor` | 模型调用前的上下文预算与信任边界 |
| `AgentStopReason` | 类型化终止原因 |
| `DefaultCompletionGate` | 确定性完成门禁 |
| `DeterministicFinalVerifier` | 所有 profile 的零 Token 结构与覆盖验证 |
| `TodoService` | 唯一 run-local Todo 状态源；`TodoWriteTool` 是模型适配器 |

三种 profile 都经过确定性 FinalVerifier；`DEEP` 额外强制 Todo。最终回答由通过
CompletionGate 的候选答案直接输出。

## 工具选择

1. `AgentToolCollectionFactory` 根据请求、在线开关、文件、Skills、系统 MCP 和用户 MCP 构建
   run-local 完整目录。
2. `ToolExposurePolicy.selectForTurn` 根据当前 query/Todo 和工具规模生成当前轮可见、可执行的浅视图。
3. 内置工具优先直接暴露；MCP 数量超过阈值时只预取相关工具，并附加 `tool_search` 做延迟发现。
4. `online=false` 在 discovery 前排除 MCP、`tool_search`、搜索和网页抓取；明确禁用工具时使用 `ToolChoice.NONE` 并清空当前轮视图。
5. `ExplicitToolChoicePolicy` 仅在用户明确要求某类能力时施加有界 tool choice，不绕过目录权限。
6. `ToolDispatcher` 在权限、Hook 和副作用之前校验 tool call ID 与服务端 Schema，再统一重试、结果截断、artifact、证据和账本。
7. 工具批次默认串行，只有显式声明 `isConcurrencySafe(input)` 的连续调用才并行；重试同样要求 `isRetryable()` 显式 opt-in。

模型只能选择当前轮浅视图里的工具；完整 `ToolCollection` 是本次运行的执行目录，不等于系统中
所有已安装工具。

## Todo 与完成闭环

- `TodoService` 支持创建、推进、标记和完成 Todo，`TodoWriteTool` 负责 Schema/调用适配，并发送 `todo_snapshot`；完成项保存 `evidenceRefs`。
- 每轮 `ContextPipeline` 在 ephemeral system prompt 尾部注入 fresh `current_todo_state`；Todo 变化同步 `context.task`，因此压缩后仍能只重做当前 `in_progress`，工具预选也跟随当前步骤。
- `DEEP` 完成项必须引用成功且非 `todo_write` 的工具调用；有业务工具证据的 AUTO/STANDARD 同样要求引用。
- `DefaultCompletionGate` 检查运行失败、空答案、Todo 未完成、每类工具仍未解决的失败和必需报告 artifact。
- 多对象比较逐 `subject × dimension` 验收，拒绝关键词壳、占位单元格和错列；`DEEP` 模式还要求先创建 Todo。
- 显式联网要求在离线/无网络能力时 fail-fast；存在工具但没有成功网络 evidence 时门禁仍拒绝。
- 门禁拒绝时返回 `reasons + requiredActions`，`AgentLoop` 将反馈加入同一 memory 后继续循环。
- 完成尝试耗尽时使用 `COMPLETION_ATTEMPT_BUDGET` 失败终止，不把未完成结果标记为成功。

## Canonical events

运行控制事件固定为：

```text
run_started
phase_changed
todo_snapshot
tool_call / tool_result / artifact events
verification_started / verification_result
completion_blocked
run_finished
result
```

`run_finished` 是权威终态，只有 `SUCCESS + completionGatePassed=true` 才表示成功。内部工具决策草稿
不作为公开推理链流式输出；历史回放通过 typed projector 合成同样的
`run_started -> run_finished -> result` 生命周期。

## 持久化与端口

- `IExecutionLedgerReadRepository` / `IExecutionLedgerWriteRepository`：执行事实端口。
- `AgentExecutionRecorder`：run、LLM、tool、artifact 记录入口。
- `ToolArtifactRegistry`：运行内可见产物绑定。
- `SessionContextMemoryService` / `ConversationMemoryManager`：会话与长期记忆。
- `RemoteHttpPort`、`RemoteStreamPort`、`FileArtifactPort`：外部技术能力端口。
- `ModelCatalogPort`：可用模型与 LLM 计费参数端口。
- `QuotaBillingPort`：调用级配额预留、结算和释放端口。
- `AgentMessageStream` / `Printer`：领域输出抽象，不依赖 Servlet/SSE 实现。

## 边界规则

- 不在 domain 新增 Controller、`SseEmitter`、MyBatis Mapper 或具体 HTTP 客户端。
- 不建立第二套模型—工具循环、第二份任务状态或第二次总结模型调用。
- 尚未交付的工作图、持久恢复与委派能力不得以空壳类冒充实现。
- 新工具必须进入 `AgentToolCollectionFactory`，并服从 exposure、budget、ledger 与 artifact 规则。
- 新完成条件优先扩展 typed evidence/`CompletionGate`，不从自然语言猜测执行成功。

## 关键路径

| 路径 | 说明 |
| --- | --- |
| `runtime/AgentRuntime.java` | 运行时组合根与终态协议 |
| `runtime/AgentLoopFactory.java` | 可注入的 run-local Harness 工厂 |
| `runtime/agent/AgentLoop.java` | 统一循环 |
| `runtime/loop/ContextPipeline.java` | 上下文与当前轮工具视图 |
| `runtime/loop/ModelGateway.java` | 模型边界 |
| `runtime/tool/dispatch/ToolDispatcher.java` | 工具分发管线 |
| `runtime/tool/dispatch/ToolInputSchemaValidator.java` | 副作用前的服务端 Schema 校验 |
| `runtime/harness/StopGate.java` | 机械停止门 |
| `runtime/harness/CancellationToken.java` | 结构化取消 |
| `runtime/harness/PermissionPolicy.java` | 工具权限 |
| `runtime/harness/HookBus.java` | typed hooks |
| `runtime/harness/AgentRunBudget.java` | 运行预算 |
| `runtime/harness/AgentFutureWaiter.java` | 共享 run deadline |
| `runtime/completion/DefaultCompletionGate.java` | 完成门禁 |
| `runtime/completion/DefaultEvidenceValidator.java` | typed evidence 验证 |
| `runtime/work/TodoService.java` | Todo 状态服务 |
| `runtime/tool/common/TodoWriteTool.java` | Todo 模型适配器 |
| `runtime/tool/exposure/ToolExposurePolicy.java` | 每轮工具视图 |
| `runtime/tool/factory/AgentToolCollectionFactory.java` | run-local 工具目录 |
| `ledger/replay/projector/impl/TodoWriteToolInvocationProjector.java` | Todo 历史投影 |
| `service/dispatch/AgentDispatchService.java` | 统一 Agent Loop 领域入口 |
| `service/execute/agentloop/AgentLoopExecuteStrategy.java` | Agent Runtime 执行边界 |
| `service/armory/AgentArmoryService.java` | Agent/Client 运行时装配 |
| `service/session/ConversationSessionOwnershipService.java` | 会话归属与删除授权 |
| `service/task/AgentTaskService.java` | 调度配置查询 |
