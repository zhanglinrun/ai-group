[Agent 项目说明](../README.md) > **ai-agent-app**

# ai-agent-app 模块

## 职责

六模块结构中的 Spring Boot 启动与组合根。它负责配置绑定、线程池、domain port
与 infrastructure adapter 注入、MyBatis Mapper、Skill/MCP 初始化和集中集成测试，
不承载 Agent Loop 的路由、工具选择或完成判定。

启动类：`com.linrun.agent.Application`。

## 当前 Harness 装配

- `ReactorRuntimeAutoConfiguration` 把 LLM、MCP、远端 HTTP/流式、文件 artifact、
  模型目录、配额 port 与专用执行器组装为 domain runtime bundle。
- `AgentLoopFactoryConfiguration` 聚合可替换的 `PermissionPolicy`、有序 `HookBus.Hook` 与
  `AgentLoopFactory.RunCustomizer`；可变 `HookBus` 和 `AgentLoop` 仍由工厂按 run 新建。
- `ReplayProjectorAutoConfiguration` 注册 canonical history projector，包括
  `TodoWriteToolInvocationProjector`。
- `AiAgentSkillAutoConfiguration` 与 `AiAgentSkillProperties` 装配系统 Skills；开关为
  `agent-loop-enabled`。
- `DataAgentInitRunner` 仅初始化独立的数据问答产品路径，不参与默认 Agent Loop 分流。
- 运行时只装配一套 `AgentRuntime -> AgentLoop`；profile 差异由请求配置和确定性门禁表达。

## 模块装配边界

```text
ai-agent-app
  -> ai-agent-trigger
       -> ai-agent-api
       -> ai-agent-domain
       -> ai-agent-types
       -> ai-agent-infrastructure
  -> ai-agent-infrastructure
```

app 负责让 Spring 发现 Controller、领域服务与技术适配器，但不在配置类中复制它们的业务逻辑。

## 核心配置

```yaml
autobots:
  autoagent:
    agent-loop:
      model-name: ${AGENT_GROUP_LLM_CHAT_MODEL:qwen-plus}
      max-turns: ${AGENT_LOOP_MAX_TURNS:40}
      max-tool-calls: ${AGENT_LOOP_MAX_TOOL_CALLS:64}
      max-completion-attempts: ${AGENT_LOOP_MAX_COMPLETION_ATTEMPTS:3}
      max-duration-seconds: ${AGENT_LOOP_MAX_DURATION_SECONDS:900}
      max-total-tokens: ${AGENT_LOOP_MAX_TOTAL_TOKENS:200000}
      max-microcredits: ${AGENT_LOOP_MAX_MICROCREDITS:10000000}
    tool:
      max_attempts: ${AGENT_TOOL_MAX_ATTEMPTS:2}
      exposure:
        mode: auto
        max-inline-mcp-tools: 8
        max-selected-mcp-tools: 6
```

`AUTO / STANDARD / DEEP` 通过请求 `executionMode` 进入同一 Harness；配置中不要恢复按模式
复制 system prompt、模型或 bean 拓扑的做法。

## 持久化资源

- baseline：`../../docs/dev-ops/mysql/sql/agent_db/01-agent_db.sql`
- 增量迁移：`../../docs/dev-ops/mysql/sql/agent_db/03-agent-loop-migrate.sql`
- Todo 结构化输出 Mapper：
  `src/main/resources/mybatis/mapper/tool_output_todo_write_mapper.xml`

数据库中使用 `tool_output_todo_write`；不要创建旧式规划输出表或运行恢复表。

## 关键测试

| 测试 | 关注点 |
| --- | --- |
| `AgentDispatchServiceTest` | 所有请求强制收敛到统一 Agent Loop |
| `GptQueryIngressServiceTest` | `executionMode` 请求收敛 |
| `AgentLoopCompletionGateTest` | 门禁拒绝后同循环继续 |
| `AgentHarnessComponentsTest` | 取消、权限、Hook 与安全并发契约 |
| `AgentLoopFactoryConfigurationTest` | Spring 注入与 run-local HookBus 隔离 |
| `ToolDispatcherBoundaryTest` | ID/Schema 在权限、Hook 和副作用之前拒绝 |
| `LLMFallbackCancellationTest` | 模型 Flux 与 fallback 取消传播 |
| `DefaultCompletionGateTest` | Todo、工具证据、artifact 验收 |
| `ToolExposurePolicyTest` | 每轮工具可见范围 |
| `ReplayProjectorTest` | Todo 与工具事件历史回放 |
| `AgentSessionPrinter*Test` | canonical events 与终态顺序 |

PowerShell 验证示例：

```powershell
$ErrorActionPreference = 'Stop'
mvn -pl ai-agent-app -am test
if ($LASTEXITCODE -ne 0) { throw "tests failed: $LASTEXITCODE" }
```

## 边界规则

- Spring bean 装配留在 app；领域规则留在 domain。
- 不在配置层重新实现工具选择或完成判断。
- 新 Mapper 必须与 `com.linrun.agent` DAO namespace 对齐。
- 敏感密钥只通过环境变量注入，不写入仓库配置。

## 关键路径

| 路径 | 说明 |
| --- | --- |
| `src/main/java/com/linrun/agent/Application.java` | Spring Boot 启动类 |
| `config/AgentExecutorConfiguration.java` | LLM、任务、工具执行器与心跳调度器 |
| `config/reactor/ReactorRuntimeAutoConfiguration.java` | domain runtime 依赖组装 |
| `config/reactor/AgentLoopFactoryConfiguration.java` | Permission、Hook 与 run customizer 扩展边界 |
| `config/reactor/ReplayProjectorAutoConfiguration.java` | typed 历史回放投影注册 |
