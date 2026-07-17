[Agent 项目说明](../README.md) > **ai-agent-types**

# ai-agent-types 模块

## 职责

六模块结构中的最小公共类型层，提供基础常量、异常、owner 请求上下文、
执行器配置与通用任务调度 SPI。本模块不依赖 Agent Harness 领域实现，
也不定义模型—工具循环状态。

## 主要类型

| 类型 | 职责 |
| --- | --- |
| `Constants` | 通用常量 |
| `ResponseCode` | 统一响应码 |
| `AppException` / `BizException` | 应用异常基类 |
| `OwnerRequestContext` | 可信 owner 请求上下文 |
| `AgentExecutorProperties` | 执行线程池配置 |
| `AgentExecutorNames` | 执行器 bean 名称 |
| `AgentExecutorBusyException` | 执行器拒绝异常 |
| `TaskJob` | 任务调度注解 |
| `TaskScheduleVO` | 调度数据模型 |
| `ITaskDataProvider` | 外部任务数据提供 SPI |
| `ITaskJobService` / `TaskJobService` | 通用调度注册与刷新服务 |

`AgentRunBudget`、`AgentStopReason`、`AgentState` 和 `AgentType` 属于 domain runtime，
不要为了“共享方便”搬到 types；它们表达的是 Harness 领域语义。

## 边界规则

- 包根统一为 `com.linrun.agent.types`。
- 不依赖 domain、trigger 或 infrastructure。
- 不在本模块引入 `ToolCollection`、Todo、CompletionGate 或 SSE event 类型。
- owner/request 上下文只保存可信身份，不保存模型密钥或会话正文。
- Agent 特有的调度查询留在 domain，调度触发适配留在 trigger；types 只提供通用 SPI。

## 关键路径

| 路径 | 说明 |
| --- | --- |
| `src/main/java/com/linrun/agent/types/agent/config/` | 执行器配置 |
| `src/main/java/com/linrun/agent/types/agent/owner/OwnerRequestContext.java` | owner 上下文 |
| `src/main/java/com/linrun/agent/types/exception/` | 通用异常 |
| `src/main/java/com/linrun/agent/types/job/` | 任务调度基础类型 |

## 验证

修改公共类型后运行 Maven 聚合编译，并检查下游模块没有形成反向依赖或循环依赖。
