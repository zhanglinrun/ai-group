# AI Agent 对话平台与会员拼团系统

**项目时间：** 2025.11 - 至今

**技术栈：** Java 21、Spring Boot、Spring Cloud、Spring AI、MySQL、Redis / Redisson、RabbitMQ、Qdrant、MinIO、Nacos

**项目描述：** 面向 C 端用户开发 ChatGPT 类 AI Agent 对话产品，支持普通对话、ReAct、Plan-and-Execute、Skills 与 MCP 工具调用，并通过分层记忆、执行账本和评测体系支撑复杂任务的持续执行与效果优化；基于 Spring Cloud 拆分网关、认证、额度账户、拼团、支付和 Agent 服务，用户可通过拼团购买额度包，支付成团后自动完成权益履约。

**核心职责：**

- **规划执行与反思优化：** 实现 ReAct、Routing、Parallelization 与 Plan-and-Execute，由 Planner 动态拆解任务、Executor 并行执行无依赖步骤，Evaluator 结合规则断言与 LLM Judge 检查完整性、事实一致性和工具证据；低于阈值时携带失败原因定向重规划，并以最大轮次和 Token 预算限制反思成本。在同一 5 条 Plan-and-Execute 小样本单次 A/B 中，Evaluator 开启后端到端成功率由 80% 提升至 100%，平均定向重规划 0.4 轮。

- **分层记忆与上下文压缩：** 构建运行级工作记忆、会话级滚动摘要与跨会话长期记忆；近期对话保留原文，早期对话复用滚动摘要并按 Token 预算裁剪，长期记忆基于 Qdrant 按用户隔离召回并加入时间衰减，首次保存时按真实 Embedding 维度幂等创建集合与过滤索引，向量服务异常时 Fail-open 回退至会话记忆。本地真实 smoke 使用两个隔离账号，验证账号 A 在会话 1 写入 Qdrant 后可由新会话召回、账号 B 无法召回；在相同 12K Token 预算、30 轮和 30 个锚点事实的确定性离线基准中，关键信息召回率由 68.9% 提升至 100.0%，平均估算输入 Token 由 9,502 降至 4,578，降低 51.8%。

- **Skills 与 MCP 工具生态：** 参考 Claude Skills 的渐进式加载机制，扫描并注册 9 项 `SKILL.md`，仅向模型暴露名称与用途，命中后按需加载完整指令；支持技能目录检索、脚本自动发现与受限路径执行。构建 MCP 工具注册中心，对客户端和工具列表预热与缓存，兼容 SSE、STDIO、Streamable HTTP；基于官方 Python MCP SDK（FastMCP 1.28.1）实现 `project-knowledge`、`agent-utility` 两个只读 STDIO Server，提供 `project_search_knowledge`、`project_get_flow`、`utility_estimate_llm_quota`、`utility_explain_quota_formula` 4 个命名空间工具。Python MCP 测试 4/4、Java STDIO 互操作与 Streamable HTTP 运行时测试 5/5 通过；本地真实 ReAct 请求成功调用 `utility_estimate_llm_quota`，SSE 观测到 `tool_call/tool_result/tool_thought`，执行账本记录 `tool_provider=mcp`、`status=1`。当前业务运行时消费 MCP Tools，不宣称已经接入 Resources、Prompts、Sampling 等全部协议能力。9 项 Skills 发现与加载校验 9/9 通过，描述符加单项按需加载的平均估算 Prompt 由 24,853 Token 降至 3,432 Token，降低 86.2%；10 条线上工具任务端到端通过 10/10。

- **执行账本与评测闭环：** 对任务运行、LLM 调用、工具调用及文件产物分层持久化，记录步骤状态、Token、耗时、输入输出和运行终态；通过投影回放重建历史对话与工具结果，并以文件产物引用支持跨工具、跨步骤复用。建立 20 条确定性任务和 10 条工具任务评测集，结合关键词断言、工具轨迹校验与可选 LLM-as-Judge；两组端到端通过率均为 100%，P95 分别为 2.651 s 和 376.731 s。工具集延迟由联网 Plan-and-Execute 长任务主导；供应商 usage 回传不完整，未将局部样本伪装成全量平均 Token。

- **额度结算一致性：** 为每次 LLM 调用设计额度预冻结、按实际用量确认扣减与余量释放流程，以调用级请求 ID 去重，并结合账户行锁、条件更新和冻结单状态守卫保证同一预扣不会重复结算；通过定时任务扫描并释放服务异常遗留的冻结记录。在 100 个并发唯一冻结请求、各 100 次重复冻结/确认及 50 条遗留冻结测试中，唯一冻结成功率 100%，P99 1,949 ms，重复扣减 0，遗留冻结释放率 100%，873 ms 内完成释放，最终冻结余额为 0。

- **拼团锁单与容量边界：** 实现活动试算、锁单参团、状态 CAS 与唯一约束，并以 Redis 原子预占和 MySQL 条件更新控制库存竞态。完成 20/50/100/200 闭环并发阶梯测试，每档预热 2 分钟、稳态 10 分钟并重复 3 次；在本地单实例可靠档并发 20 下，成功锁单 QPS 为 422.02，P99 平均/最差为 136.01/151.69 ms，传输失败最高 0.0124%。12 个 run 的已提交 team/order/trade 行均保持一一唯一；同时定位到开发环境 Tomcat 20 连接/线程上限导致的连接拒绝，以及高并发共 86 笔已提交但客户端未确认事务，明确后续需调优服务容量并补充请求结果查询、幂等重试和独立管理端口。

- **支付与权益最终一致性：** 通过支付宝回调验签、金额校验、状态守卫、本地消息表、RabbitMQ 重试/DLQ、消费幂等及定时对账补偿推进权益最终到账。权益 MQ 基准投递 50 个唯一事件和 50 次重复事件，在并发 4 下到账率 100%、重复发放 0、到账 P95 983 ms，毒消息按 4 次尝试后进入 DLQ；支付域 6 个测试类、20 个离线 Mock 故障场景 20/20 通过，覆盖超时关单对账、退款重试/ACK、未支付权益守卫、锁单恢复、本地消息重投和退款状态机。

## 指标边界

- 拼团阶梯测试运行于 Windows 11、单 Group 实例、k6/MySQL/Redis 同一物理机，JVM 为 `spring-boot:run` 的 C1-only 开发配置，Tomcat 上限为 20 连接/20 线程；422.02 QPS 是当前开发配置下的本地成功新队锁单基准，不是生产 SLA，也不是完整支付成团链路吞吐。
- 并发 20 的业务失败最高为 0.1770%，主要来自 8 位随机 `teamId` 的生日碰撞。简历中不应写“错误率为 0”。
- “已提交行一一唯一”与“客户端/数据库结果完全对账”是两个指标。前者 12/12 run 通过，后者只有并发 20 的 3/12 run 通过；不能写“高并发终态一致率 100%”。
- 工具评测只有 2/10 个运行返回供应商 Token usage，因此不提供工具集平均 Token；支付故障回归使用 Mockito Stub，不能写成支付宝沙箱故障注入。
- 跨会话长期记忆的双账号 smoke 是本地功能验收，只证明 Qdrant 落库、同 owner 新会话召回和不同 owner 隔离链路可用，不是生产召回率、并发容量或 SLA；30 轮离线基准衡量的是滚动摘要与上下文裁剪，不能冒充长期向量记忆的线上效果指标。
- MCP 的 4/4 Python 测试、5/5 Java 测试和单次真实 ReAct 调用均为本地协议与链路验收；当前业务运行时只消费 MCP Tools，不能据此声称已实现 Resources、Prompts、Sampling 或完整 MCP 协议面。

原始证据见 [`README.md`](README.md)、[`smoke-agent-memory.ps1`](../dev-ops/smoke-agent-memory.ps1)、[`test_mcp_servers.py`](../../ai-agent/reactor-tool/tests/test_mcp_servers.py)、[`McpStdioInteropTest.java`](../../ai-agent/Reactor-agent-app/src/test/java/org/wwz/ai/test/spring/ai/McpStdioInteropTest.java)、[`McpStreamableHttpSupportTest.java`](../../ai-agent/Reactor-agent-app/src/test/java/org/wwz/ai/test/spring/ai/McpStreamableHttpSupportTest.java) 和 [`reports/group-load-benchmark.json`](reports/group-load-benchmark.json)。
