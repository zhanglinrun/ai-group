[Agent 项目说明](../README.md) > **ai-agent-api**

# ai-agent-api 模块

## 职责

六模块结构中的对外契约层。本模块只定义跨模块、跨 HTTP 边界使用的服务接口、
请求/响应 DTO 与统一响应体，包根为 `com.linrun.agent.api`。它不实现 Agent Loop、
工具选择、SSE 编码、业务编排或持久化。

## 边界规则

- API DTO 只描述外部契约，不持有 `AgentContext`、`ToolCollection` 或领域服务实现。
- Agent Harness 的运行模式由请求字段 `executionMode` 表达；不要为 `AUTO / STANDARD / DEEP`
  新增三套服务接口或三类 Agent DTO。
- canonical events 属于流式协议，由 trigger 的 `AgentSessionPrinter` 与
  `SseEmitterAgentSessionStream` 适配；本模块不复制事件状态机。
- 新增字段要保持向后可读，并通过 Bean Validation 表达输入约束。

## 主要契约

| 类型 | 职责 |
| --- | --- |
| `IAiAgentService` | Agent 查询与装配服务契约 |
| `IAdminUserAdminService` | 管理员账号管理 |
| `IAiAgentDataStatisticsAdminService` | Agent 数据统计查询 |
| `IAiClient*AdminService` | 客户端、模型、API、Advisor、System Prompt、RAG 与 MCP 配置管理 |
| `Response<T>` | 统一响应包装 |

旧 Workflow 绘图配置管理接口已经随多运行时架构一并删除；工作区生图使用 trigger 中独立的
`/api/agent/image-generation` 产品接口，不恢复旧 `ai-agent-draw` 管理面。

请求与响应 DTO 位于 `src/main/java/com/linrun/agent/api/dto/`。主包名统一使用
`com.linrun.agent`，不得重新引入旧包根。

## 依赖

- Lombok
- Jakarta Validation
- Spring Web MVC 注解
- Servlet API

## 验证

本模块以编译期契约和 app 集成测试为主。修改 DTO 后至少运行聚合模块的相关 Controller、
序列化和参数校验测试。

## 关键路径

| 路径 | 说明 |
| --- | --- |
| `src/main/java/com/linrun/agent/api/` | 服务接口 |
| `src/main/java/com/linrun/agent/api/dto/` | 请求/响应 DTO |
| `src/main/java/com/linrun/agent/api/response/Response.java` | 通用响应体 |

## 当前架构说明

API 文档以统一 Agent Loop 为准。执行细节见 domain 的
`AgentRuntime`、`AgentLoop`、`ContextPipeline`、`ModelGateway`、`ToolDispatcher`、
`TodoService / TodoWriteTool` 和 `CompletionGate`。
