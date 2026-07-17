# Reactor Agent Harness 前端

React 19 + TypeScript + Vite 工作台。前端严格适配后端 canonical Agent Loop 协议，
不在浏览器中复刻后端规划、验收或路由状态机。

## 产品路径

| 路径 | 前端输入 | 说明 |
| --- | --- | --- |
| Agent Harness | `executionMode: AUTO | STANDARD | DEEP` | 同一后端 Agent Loop 的不同执行强度 |
| DataAgent | `outputStyle: dataAgent` | 独立的数据问答与图表产品路径 |
`executionMode` 是唯一 Agent Loop 模式字段。不要增加布尔深度开关、Agent 类型选择器或前端自动路由
来推导另一套后端运行时。

## Canonical event 模型

`src/utils/agentEvents.ts` 是实时 SSE 和历史回放的统一规范化入口。允许的主事件：

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

工具专属的 file、browser、report、image、data 等 artifact 事件继续按 typed renderer 展示。
前端不得重新识别已经退出协议的旧生命周期事件，也不得根据文本内容猜测 Todo 或验证状态。

## 状态与组件

| 路径 | 职责 |
| --- | --- |
| `src/components/ChatView/useConversationStream.ts` | SSE 连接与 turn 生命周期 |
| `src/components/ChatView/streamState.ts` | canonical 运行状态归并 |
| `src/utils/agentEvents.ts` | 事件规范化 |
| `src/utils/agentRequest.ts` | 生成含 `executionMode` 的请求 |
| `src/utils/conversationHistory.ts` | 将历史投影映射为同一 UI 模型 |
| `src/components/Dialogue/TodoSection.tsx` | Todo 列表与进度 |
| `src/components/Dialogue/VerificationCard.tsx` | 验证结果和修正动作 |
| `src/components/Dialogue/Timeline.tsx` | phase/tool/verification 时间线 |
| `src/components/GeneralInput/inputMode.ts` | AUTO/STANDARD/DEEP 映射 |

`run_finished` 更新权威运行终态，随后 `result` 提供最终内容。前端只有收到
`status=SUCCESS && completionGatePassed=true` 的 `run_finished` 才能显示成功；失败、预算耗尽或门禁
未通过时必须保留后端的 `status/runStatus/completionGatePassed/stopReason`，不能因为存在文本回答就显示成功。

## 目录概览

```text
ui/src/
├── pages/
│   ├── Home/                    # 对话与会话入口
│   ├── UserExtensions/          # 用户 Skill/MCP 管理
│   ├── WorkspaceImageGeneration/
│   └── WorkspaceMRag/
├── components/
│   ├── ChatView/                # SSE 会话状态
│   ├── Dialogue/                # Todo、验证、时间线和消息渲染
│   ├── GeneralInput/            # executionMode 输入
│   ├── ActionView/              # 工具与 artifact 侧栏
│   └── DataChat/                # DataAgent 展示
├── services/                    # HTTP API
├── types/                       # chat/message 类型
└── utils/                       # request/event/history 纯函数
```

## 开发命令

```powershell
$ErrorActionPreference = 'Stop'
npm test
if ($LASTEXITCODE -ne 0) { throw "frontend tests failed: $LASTEXITCODE" }
npm run build
if ($LASTEXITCODE -ne 0) { throw "frontend build failed: $LASTEXITCODE" }
```

## 修改规则

- 后端协议变化先更新 `agentEvents.ts`、类型和测试，再更新组件。
- 实时流和历史回放必须使用同一事件模型，不能维护两套解析器。
- Todo 状态由后端 `todo_snapshot` 决定；前端只投影，不自动补步骤。
- 前端只投影后端的 Todo `evidencePolicy`：`NONE` 表示过程/认知步骤，`TOOL` 表示必须展示当前 activation
  的真实工具证据，`LEGACY` 仅用于缺少新字段的历史记录兼容。UI 不推断、补造、跨步骤关联或重复消费 evidence。
- `TodoSection` 同时展示“过程步骤/工具证据”，实时 SSE 与历史回放必须保持相同的 policy、activation 和 evidence 语义。
- 验证失败展示 `reasons` 与 `requiredActions`，不要把它转成终态失败后立即清空。
- 不展示内部模型草稿或 chain-of-thought；只展示公开 phase、Todo、工具和验证证据。
- DataAgent 保持独立产品边界，不为兼容旧 Agent 模式污染统一 Harness 请求。

## 测试重点

- `agentEvents.test.ts`：canonical-only 事件规范化。
- `agentRequest.test.ts`：请求只发送 `executionMode`。
- `AgentLoopCards.test.tsx`：Todo 与 Verification 卡片。
- Todo 组件测试覆盖 `NONE / TOOL / LEGACY` evidence policy，以及实时与历史投影一致性。
- `streamState.test.ts` / `useConversationStream.test.ts`：终态和事件顺序。
- `conversationHistory.test.ts`：历史与实时语义一致。
