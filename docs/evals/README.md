# Agent 与业务链路评测

本目录保存可复现的本地评测集、运行脚本和原始 JSON 报告。Agent 在线评测经 Gateway 调用真实 SSE 链路，业务基准使用本地 MySQL、Redis、Kafka 或明确标注的离线 Mock。所有结果均来自 Windows 11、Java 21 的本地开发环境，不代表生产 SLA。

## 证据使用边界（2026-07-26）

- Agent 能力：统一 Agent Loop、DEEP 并行研究分支、CompletionGate、上下文/记忆、Skill·MCP、工具分层和本目录评测报告。
- 交易能力：quota / group / payment-fault 当前报告；`benefit-mq` 只作 RabbitMQ 迁移前历史数据。
- **在线** `run-evals.ps1`：schema `4` 报告已生成；当前每条用例只运行 `1` 次，只能称为本地真实模型样本，不能写成稳定成功率或生产 SLA。
- **离线** memory/skills：2026-07-24 已复跑，可引用下方表与 `reports/memory-skills-benchmark.json`。
- **并行研究边界**：DEEP 图只允许受控研究分支并行，统一合并、审阅和生成报告，不提供通用模型派生子 Agent 工具。

## 评测组成

- `cases.jsonl`：20 条确定性问答（18 条 `STANDARD`、2 条 `AUTO`），全部经过统一 Agent Loop、完成门禁和执行账本。
- `cases-tools.jsonl`：10 条工具任务，覆盖联网检索、网页抓取、代码执行、文件/报告产物、Skill 加载，以及 2 条要求 `todo_write` 的 `DEEP` 任务。
- `cases-deep.jsonl`：5 条 `DEEP` 小样本，验证显式 Todo、逐项完成、最终验证与同一循环内纠错。
- `cases-tools-todo-basic.jsonl`、`cases-tools-todo-search.jsonl`：分别验证 `todo_write`，以及 `todo_write + deep_search` 的定向工具轨迹。
- `run-evals.ps1`：注册隔离用户、补充隔离额度、调用 Gateway SSE、校验关键词与工具轨迹，并验收 `run_started -> verification_started/result -> run_finished -> result`、`completionGatePassed`、`completionBlocked`、`stopReason` 和执行账本终态。
- `run-offline-benchmarks.ps1`：运行 30 轮记忆和 9 项 Skills 渐进加载的确定性离线基准。
- `run-quota-benchmark.ps1`：运行配额本地集成基准。
- `run-group-benchmark.ps1`：运行同机、单次有限突发的拼团竞争正确性冒烟；不用于容量或生产性能结论。
- `run-payment-fault-tests.ps1`：运行支付域离线 Mock 故障与状态守卫回归，不连接支付宝沙箱。

## 当前证据与复测状态

### 确定性组件回归

2026-07-26 在当前工作区执行：Java `582/582`、Python `154 passed`（另有 `1 skipped`、`3 subtests passed`）、前端 `199/199`；前端 ESLint 与生产构建也通过。这些数字证明组件和契约回归，不等于真实模型任务成功率。

### Agent 在线真实模型样本

schema `4` 报告经过 Gateway SSE、统一 Agent Loop、CompletionGate 与账本终态校验。当前归档结果如下：

| 报告 | 数据集与 trial | 本地样本结果 | 可引用边界 |
| --- | --- | ---: | --- |
| [`agent-deterministic.json`](reports/agent-deterministic.json) | 20 条，每条 1 次 | 20/20 端到端通过 | 确定性问答样本，不是 pass@k |
| [`agent-tools.json`](reports/agent-tools.json) | 10 条，每条 1 次 | 10/10 端到端通过 | 含真实工具与 2 条 DEEP；不代表跨模型稳定率 |

这里的“通过”要求关键词/工具断言、规范生命周期、完成门禁、`stopReason=COMPLETED` 和账本成功同时满足。供应商 usage 缺失时写 `null`，不以本地估算冒充计费 Token。

两份报告保存 `gitHead`、`gitDirty`、用例 SHA-256 与 runner SHA-256；当前均标记 `gitDirty=true`，因此能证明当时本地工作区行为，但不能把报告唯一绑定到最终源码快照。对外发布成功率前，应在干净提交上使用 `-Trials 3` 或更多 trial 重跑，再引用 `pass@k / pass^k`；当前结果不作生产 SLA。

### 记忆与 Skills 离线基准

| 能力 | 数据集 | 基线 | 当前策略 | 变化 |
| --- | --- | ---: | ---: | ---: |
| 滚动摘要记忆 | 30 轮、30 个锚点事实、相同 12K Token 预算 | 召回率 68.9% | 召回率 100.0% | +31.1 个百分点 |
| 上下文大小 | 同一 30 轮数据与 12K Token 预算 | 平均估算 9,502 Token | 平均估算 4,578 Token | 降低 51.8% |
| Skills 渐进加载 | 9 项 `SKILL.md` | 全量注入平均估算 24,851 Token | 描述符 + 单项按需加载平均估算 3,432 Token | 降低 86.2% |
| Skills 加载校验 | 9 项 `SKILL.md` | - | 9/9（100%） | 仅表示加载校验 |

当前原始数据见 [`memory-skills-benchmark.json`](reports/memory-skills-benchmark.json)。报告使用 `o200k_base` 估算器做同数据集 Prompt 大小比较，不是供应商计费 usage；9/9 是技能发现和加载校验成功率，不是线上 Skill 任务成功率。

### 配额、拼团与支付

| 链路 | 实测负载 | 结果 | 原始报告 |
| --- | --- | --- | --- |
| 额度配额 | 100 个并发唯一冻结请求；各 100 次重复冻结/确认；50 条遗留冻结 | 唯一冻结成功率 100%，P99 2,377 ms；重复扣减 0；遗留冻结释放率 100%，1,043 ms；最终冻结余额 0 | [`quota-benchmark.json`](reports/quota-benchmark.json) |
| 拼团竞争正确性 | 同机闭环、并发 20 的 100 次锁单突发；20 个结算请求，每单重复结算 2 次 | 9 次锁单成功、91 次满员后业务拒绝；超卖 0、重复团队 0、重复结算副作用 0；终态一致率 100% | [`group-benchmark.json`](reports/group-benchmark.json) |
| 拼团成功锁单阶梯压测 | 20/50/100/200 闭环并发，每档预热 2 分钟、稳态 10 分钟、重复 3 次 | 20 并发可靠档成功 QPS 422.02，P99 平均/最差 136.01/151.69 ms，传输失败最高 0.0124%；50 并发起进入饱和区 | [`group-load-benchmark.json`](reports/group-load-benchmark.json) |
| 迁移前 RabbitMQ 权益 MQ（2026-07-13 历史） | 50 个唯一事件 + 50 次重复投递，并发 4；1 条毒消息 | 到账率 100%，P95 983 ms；重复发放 0；毒消息按 4 次尝试后进入 DLQ | [`benefit-mq-benchmark.json`](reports/benefit-mq-benchmark.json) |
| 支付故障回归 | 6 个测试类、20 个离线 Mock 场景 | 20/20（100%）；覆盖超时关单对账、退款重试/ACK、未支付权益守卫、锁单恢复、本地消息重投和退款状态机 | [`payment-fault-regression.json`](reports/payment-fault-regression.json) |

支付报告使用 Mockito Stub，不连接真实支付宝、MySQL、Kafka 或 HTTP，因此只能表述为"支付域离线故障回归 20/20"，不能表述为"支付宝沙箱故障注入"或线上支付可用性。

### 拼团成功锁单阶梯压测

2026-07-14 完成 12 个正式 run：20/50/100/200 闭环并发，每个 run 预热 120 秒、稳态 600 秒，每档重复 3 次，共产生 8,143,112 次稳态尝试和 3,082,879 笔稳态成功锁单。每个请求创建独立队伍，只有 HTTP 200、业务码 `0000` 且返回 `teamId` 才计入成功吞吐，满员或快速失败路径不能抬高 QPS。

| 并发 | 成功 QPS（均值 ± SD，CV） | P50 均值 | P95 均值/最差 | P99 均值/最差 | 业务失败均值/最高 | 传输失败均值/最高 | 已提交未确认 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 20 | 422.02 ± 33.40（7.91%） | 42.16 ms | 71.71/86.19 ms | 136.01/151.69 ms | 0.1683%/0.1770% | 0.0072%/0.0124% | 0 |
| 50 | 465.75 ± 9.49（2.04%） | 42.12 ms | 61.59/64.99 ms | 802.11/824.66 ms | 0.1612%/0.1704% | 12.3459%/13.4878% | 27 |
| 100 | 476.38 ± 6.08（1.28%） | 41.47 ms | 58.35/58.71 ms | 786.56/970.10 ms | 0.1234%/0.1262% | 33.8965%/34.8361% | 29 |
| 200 | 348.55 ± 5.50（1.58%） | 55.45 ms | 81.75/83.71 ms | 364.66/434.99 ms | 0.0164%/0.0168% | 87.7274%/88.0930% | 30 |

延迟只统计被服务接纳并成功返回的请求。200 并发下 P99 下降是 88% 连接失败造成的选择偏差，不代表性能改善。按传输可靠性，20 并发是本次环境中的最高可靠档；50/100 并发成功吞吐只提高约 10%/13%，但传输失败升至 11%-35%，200 并发则发生吞吐塌陷。直接原因是当前 `application-dev.yml` 将 Tomcat `max-connections` 和 `threads.max` 均设为 20、`accept-count` 设为 10；这与实测 Tomcat busy/Hikari active 峰值吻合，因此高并发档表示当前开发配置的拒绝边界，而不是调优后的服务器容量。

20 并发三轮的应用 CPU 均值/采样峰值为 10.46%/13.39%，MySQL Docker CPU 为 124.19%/220.98%（多核口径），Hikari active 峰值 20、pending 峰值 0，Tomcat busy 峰值 20；三轮共观测 1,913 次 GC pause、总计 2,125 ms。50/100 并发的应用 CPU 均值仅 11.07%/11.41%，Hikari 和 Tomcat 峰值没有继续增长，新增 VU 主要转化为连接拒绝。200 并发下 Actuator 与业务共用的 8091 端口几乎不可用，183 次采样失败、仅 8 次成功，因此该档 CPU、GC、Hikari 和 Tomcat 均值在最终报告中主动置空。

这份 2026-07-13 历史报告中，12 个 run 的数据库内部唯一性通过；但 50/100/200 并发分别出现 27/29/30 笔“服务端已提交、客户端未确认”的歧义事务，共 86 笔，不能表述为端到端终态 100% 一致。报告还记录了旧版 8 位随机 `teamId` 的碰撞风险；当前代码已改用 32 位无连字符 UUID，并由 `TradeIdentifierTest`、`CreateOrderIdentifierTest` 和字段宽度迁移契约测试覆盖，但尚未用修复后的版本重跑同等阶梯压测，因此旧报告不能冒充修复后的容量数据。

测试环境为 Windows 11、AMD Ryzen 5 9600X（6C12T）、约 48 GB 内存、单 Group 实例、MySQL 8.0.32 和 Redis 7.2.14；k6、应用与中间件共享同一物理机，容器未设置 CPU/内存上限。Group 服务通过 `spring-boot:run` 启动且带 `-XX:TieredStopAtLevel=1`，并使用上述 20 连接/线程的开发配置，因此这是本地开发环境的容量与失效边界，不是生产 SLA。闭环模型还存在 coordinated omission；正式 SLO 仍需独立压测机、生产 JVM 参数、合理的 Tomcat/连接池容量和固定到达率复测。

原先 `371.7 QPS/P99 106 ms` 来自包含 91 次满员快速拒绝的 100 请求突发，只保留为并发正确性冒烟，不再作为容量指标。

## 指标口径

- **断言通过**：满足用例声明的任一/全部关键词，并通过工具名称和最少调用次数校验。
- **规范生命周期**：必须依次观察到 Agent Loop 的启动、完成验证、`run_finished` 和终态 `result`；终态 `result` 不得早于 `run_finished`。
- **端到端成功**：断言和规范生命周期通过，`completionGatePassed=true`、`stopReason=COMPLETED`，且 `dialogue_run` 到达成功终态。
- **账本成功**：统一 Agent Loop 的每条在线用例都必须生成 `dialogue_run`，不再为 `chat` 输出样式设置无账本例外。
- **完成验证**：`verification_started/result` 表示 CompletionGate 尝试；`verifierExecuted=true` 才表示执行了独立最终验证器。
- **完成阻断**：`completionBlocked` 表示草稿未通过门禁，失败原因与修正动作已反馈给同一个模型/工具循环继续处理，不切换另一套执行内核。
- **停止原因**：`stopReason` 是终止类型；成功必须为 `COMPLETED`，预算耗尽、重复轮次、执行错误等均按失败统计。
- **Token**：只使用 Spring AI 响应元数据中供应商上报并持久化的 usage。未返回 usage 的运行写为 `null`，不以 `TokenCounter.countText()` 补值。
- **时延**：从 Gateway 请求开始到收到终态 SSE 帧的客户端墙钟时间。
- **LLM-as-Judge**：`run-evals.ps1 -Judge` 是独立的可选最终答复评分，不属于 Agent Loop 的 CompletionGate 指标。
- **离线估算 Token**：只用于同数据集、同预算下比较 Prompt 大小，不与在线 provider usage 混用。

## 运行方式

```powershell
# 先启动本地全栈并配置可用 LLM Key
pwsh docs/dev-ops/start-full-stack.ps1

# Agent 在线集
pwsh docs/evals/run-evals.ps1 -CasesFile cases.jsonl -ReportName agent-deterministic.json
pwsh docs/evals/run-evals.ps1 -CasesFile cases-tools.jsonl -ReportName agent-tools.json -TimeoutSec 600
pwsh docs/evals/run-evals.ps1 -CasesFile cases-deep.jsonl -ReportName agent-deep.json -TimeoutSec 600
pwsh docs/evals/run-evals.ps1 -CasesFile cases-tools-todo-basic.jsonl -ReportName agent-todo-basic.json -TimeoutSec 600
pwsh docs/evals/run-evals.ps1 -CasesFile cases-tools-todo-search.jsonl -ReportName agent-todo-search.json -TimeoutSec 600

# 离线与业务集成基准
pwsh docs/evals/run-offline-benchmarks.ps1
pwsh docs/evals/run-quota-benchmark.ps1
pwsh docs/evals/run-group-benchmark.ps1
pwsh docs/evals/run-group-load-benchmark.ps1
pwsh docs/evals/build-group-load-consolidated-report.ps1
pwsh docs/evals/run-payment-fault-tests.ps1
```

`run-evals.ps1` 在任一端到端用例失败时返回非零退出码，可直接作为回归门禁。报告保留数据集、环境、逐用例结果和指标语义，项目文档或技术报告中的数字应引用对应 JSON，而不是从控制台日志人工估算。

需要对外引用稳定性指标时，在干净提交上增加 `-Trials 3`；默认 `-Trials 1` 只适合本地冒烟。
