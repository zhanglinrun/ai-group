[Agent 项目说明](../README.md) > **ai-agent-infrastructure**

# ai-agent-infrastructure 模块

## 职责

基础设施适配层，负责 MyBatis DAO/PO、仓储实现、远端 HTTP/流式调用、文件 artifact、
数据查询、配额服务调用与工具结构化输出持久化。domain 定义规则和 port，
本模块实现外部技术细节，不参与 Agent 路由、工具选择或完成判定。

## Agent Harness 持久化

| 组件 | 职责 |
| --- | --- |
| `ExecutionLedgerWriteRepository` | 写入 run、LLM、tool、artifact 执行事实 |
| `ExecutionLedgerReadRepository` | 历史会话与工具调用查询 |
| `ToolOutputWriterImpl` | 按工具类型写 structured output |
| `ToolOutputReaderImpl` | 读取 typed tool output 供回放投影 |
| `IToolOutputTodoWriteDao` | TodoWrite 结构化输出 DAO |
| `ToolOutputTodoWritePO` | `tool_output_todo_write` 持久化对象 |

TodoWrite 的 MyBatis XML 位于 app：
`../ai-agent-app/src/main/resources/mybatis/mapper/tool_output_todo_write_mapper.xml`。

数据库迁移使用 `../../docs/dev-ops/mysql/sql/agent_db/03-agent-loop-migrate.sql`。迁移会建立
`tool_output_todo_write`，但不会在滚动部署或重复启动时自动 DROP 历史表；退出运行时的旧
DAO/PO/Mapper 已从生产代码删除，历史表如需物理清理必须经过独立、显式的数据迁移。

## 外部适配

- `OkHttpRemoteHttpAdapter`：同步 HTTP port。
- `OkHttpRemoteStreamAdapter`：远端流式 port。
- `ReactorToolFileArtifactAdapter`：工具产物解析与绑定。
- `ModelCatalogAdapter`：从模型元数据仓储解析可用模型和 LLM 计费参数。
- `DataQueryExecutionAdapter` / `DataQueryMetadataAdapter`：数据问答 JDBC 适配。
- `AgentRepository`：Agent 配置仓储。
- `ChatModelMetadataRepository`：模型元数据仓储。

MCP 的运行时选择和调用语义位于 domain；本模块不得绕过 `ToolCollection` 或
`ToolExposurePolicy` 直接向模型暴露工具。

## 配额适配

`MemberQuotaBillingAdapter` 实现 domain `QuotaSettlementRemotePort`，通过
`MemberQuotaFeignClient` 调用会员配额服务；`DurableQuotaBillingCoordinator` 才是生产
`QuotaBillingPort`，负责先落本地命令、再调用远端并最终收敛：

1. `reserve` 按 requested/minimum microcredits 预冻结，并通过稳定 requestId 查询完整冻结状态。
2. `confirm` 以真实消耗确认结算；已决定确认后不会反向改成 release。
3. `release` 只处理明确不计费的调用；相反远端终态进入 `CONFLICT`。
4. member 错误码 `621` 才转换为 `QuotaInsufficientException`；无响应、500/503、缺字段或状态
   不一致转换为 `QuotaRemoteCallException`，由 durable command 重试或进入人工审核。
5. `findBy*` 查无记录返回显式 `NOT_FOUND`，不以 `null` 表示协议状态。

配额预留金额、实际 token 成本和结算时机由 domain 决定；infrastructure 只翻译外部协议。

## 数据边界

- MyBatis Mapper 与 PO 统一位于 `infrastructure.dao`。
- domain 只依赖 repository/port，不依赖 DAO、PO 或 Mapper XML。
- structured output 必须以真实 invocation tool name 路由；Todo 使用 `todo_write`。
- 历史回放读取 typed output 后由 domain projector 生成 canonical events。
- artifact、tool invocation 和 output 必须保留 request/run/invocation 关联键。

## 关键路径

| 路径 | 说明 |
| --- | --- |
| `adapter/repository/ExecutionLedgerWriteRepository.java` | 账本写适配 |
| `adapter/repository/ExecutionLedgerReadRepository.java` | 账本读适配 |
| `adapter/port/ModelCatalogAdapter.java` | 模型目录 port 适配 |
| `tooloutput/ToolOutputWriterImpl.java` | typed output 写入 |
| `tooloutput/ToolOutputReaderImpl.java` | typed output 读取 |
| `dao/reactor/IToolOutputTodoWriteDao.java` | Todo DAO |
| `dao/po/ToolOutputTodoWritePO.java` | Todo PO |
| `gateway/quota/MemberQuotaBillingAdapter.java` | 配额预冻结/结算/释放适配 |
| `gateway/quota/MemberQuotaFeignClient.java` | 会员配额远程客户端 |

## 验证重点

- Mapper namespace 与 `com.linrun.agent` DAO 全限定名一致。
- Todo 调用写入 `tool_output_todo_write`，历史读取能恢复 `todo_snapshot`。
- 不在日志中输出密钥、完整 prompt 或不受限工具结果。
