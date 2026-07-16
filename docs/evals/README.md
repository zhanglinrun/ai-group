# Agent 与业务链路评测

本目录保存可复现的本地评测集、运行脚本和原始 JSON 报告。Agent 在线评测经 Gateway 调用真实 SSE 链路，业务基准使用本地 MySQL、Redis、RabbitMQ 或明确标注的离线 Mock。所有结果均来自 Windows 11、Java 21 的本地开发环境，不代表生产 SLA。

## 评测组成

- `cases.jsonl`：20 条确定性问答，其中 18 条走 Agent 执行账本，2 条走固定 chat 流程。
- `cases-tools.jsonl`：10 条工具任务，覆盖联网检索、网页抓取、代码执行、文件/报告产物、Skill 加载和 2 条 Plan-and-Execute。
- `cases-plan.jsonl`：5 条 Plan-and-Execute 小样本，用于比较 Evaluator 开关前后的单次 A/B 结果。
- `cases-tools-plan-basic.jsonl`、`cases-tools-plan-search.jsonl`：分别验证纯规划使用 `AUTO`、联网任务使用 `REQUIRED` 的定向回归集。
- `run-evals.ps1`：注册隔离用户、补充隔离额度、调用 Gateway SSE、执行关键词与工具轨迹断言，并从 `dialogue_run`、`llm_invocation`、`tool_invocation` 回读账本指标。
- `run-offline-benchmarks.ps1`：运行 30 轮记忆和 9 项 Skills 渐进加载的确定性离线基准。
- `run-quota-benchmark.ps1`、`run-benefit-mq-benchmark.ps1`：运行配额和权益 MQ 的本地集成基准。
- `run-group-benchmark.ps1`：运行同机、单次有限突发的拼团竞争正确性冒烟；不用于容量或生产性能结论。
- `run-payment-fault-tests.ps1`：运行支付域离线 Mock 故障与状态守卫回归，不连接支付宝沙箱。

## 最新实测结果

### Agent 在线评测

| 数据集 | 端到端成功 | 账本成功 | P95 | Provider usage | 原始报告 |
| --- | ---: | ---: | ---: | --- | --- |
| 确定性任务 | 20/20（100%） | 18/18（100%）；2 条 chat 无账本 | 2.651 s | 0/20 返回 usage，平均 Token 为 `null` | [`agent-deterministic-conditional-final.json`](reports/agent-deterministic-conditional-final.json) |
| 工具任务 | 10/10（100%） | 10/10（100%） | 376.731 s | 2/10 返回 usage；仅已观测样本平均 41,526 Token | [`agent-tools-conditional-final.json`](reports/agent-tools-conditional-final.json) |

工具集 10 条用例均产生并通过声明的工具轨迹。P95 由联网 Plan-and-Execute 长任务主导，不能外推为普通对话延迟。由于只有 2/10 个工具运行返回供应商 usage，41,526 只表示已观测样本均值，不能表述为完整工具集平均 Token。

纯规划定向回归只使用 `planning` 一类工具，1/1 通过、耗时 158.246 s；联网规划定向回归包含 `planning + deep_search`，1/1 通过、耗时 382.700 s，并发生 1 轮定向重规划。对应报告为 [`agent-tool-plan-basic-conditional-final.json`](reports/agent-tool-plan-basic-conditional-final.json) 和 [`agent-tool-plan-search-conditional-final.json`](reports/agent-tool-plan-search-conditional-final.json)。

### Evaluator 小样本 A/B

| 配置 | 成功率 | 平均定向重规划 | P95 | 原始报告 |
| --- | ---: | ---: | ---: | --- |
| Evaluator 关闭 | 4/5（80%） | 不适用 | 105.671 s | [`agent-plan-evaluator-disabled.json`](reports/agent-plan-evaluator-disabled.json) |
| Evaluator 开启 | 5/5（100%） | 0.4 轮 | 215.489 s | [`agent-plan-evaluator-enabled.json`](reports/agent-plan-evaluator-enabled.json) |

该对比仅为同一 5 条数据集的单次小样本冒烟，模型输出存在随机性，不应解释为统计显著结论。它验证的是 Evaluator、失败原因回传、定向重规划和反思预算链路能够改变失败任务的执行结果，同时也展示了额外 LLM 调用带来的延迟和 Token 成本。

### 记忆与 Skills 离线基准

| 能力 | 数据集 | 基线 | 当前策略 | 变化 |
| --- | --- | ---: | ---: | ---: |
| 滚动摘要记忆 | 30 轮、30 个锚点事实、相同 12K Token 预算 | 召回率 68.9% | 召回率 100.0% | +31.1 个百分点 |
| 上下文大小 | 同一 30 轮数据与 12K Token 预算 | 平均估算 9,502 Token | 平均估算 4,578 Token | 降低 51.8% |
| Skills 渐进加载 | 9 项 `SKILL.md` | 全量注入平均估算 24,853 Token | 描述符 + 单项按需加载平均估算 3,432 Token | 降低 86.2% |
| Skills 加载校验 | 9 项 `SKILL.md` | - | 9/9（100%） | 仅表示加载校验 |

原始数据见 [`offline-benchmark.json`](reports/offline-benchmark.json)。该报告使用 `o200k_base` 估算器做同数据集 Prompt 大小比较，不是供应商计费 usage；9/9 是技能发现和加载校验成功率，不是线上 Skill 任务成功率。

### 配额、拼团与支付

| 链路 | 实测负载 | 结果 | 原始报告 |
| --- | --- | --- | --- |
| 会员配额 | 100 个并发唯一冻结请求；各 100 次重复冻结/确认；50 条遗留冻结 | 唯一冻结成功率 100%，P99 1,949 ms；重复扣减 0；遗留冻结释放率 100%，873 ms；最终冻结余额 0 | [`quota-benchmark.json`](reports/quota-benchmark.json) |
| 拼团竞争正确性 | 同机闭环、并发 20 的 100 次锁单突发；20 个结算请求，每单重复结算 2 次 | 9 次锁单成功、91 次满员后业务拒绝；超卖 0、重复团队 0、重复结算副作用 0；终态一致率 100% | [`group-benchmark.json`](reports/group-benchmark.json) |
| 拼团成功锁单阶梯压测 | 20/50/100/200 闭环并发，每档预热 2 分钟、稳态 10 分钟、重复 3 次 | 20 并发可靠档成功 QPS 422.02，P99 平均/最差 136.01/151.69 ms，传输失败最高 0.0124%；50 并发起进入饱和区 | [`group-load-benchmark.json`](reports/group-load-benchmark.json) |
| 权益 MQ | 50 个唯一事件 + 50 次重复投递，并发 4；1 条毒消息 | 到账率 100%，P95 983 ms；重复发放 0；毒消息按 4 次尝试后进入 DLQ | [`benefit-mq-benchmark.json`](reports/benefit-mq-benchmark.json) |
| 支付故障回归 | 6 个测试类、20 个离线 Mock 场景 | 20/20（100%）；覆盖超时关单对账、退款重试/ACK、未支付权益守卫、锁单恢复、本地消息重投和退款状态机 | [`payment-fault-regression.json`](reports/payment-fault-regression.json) |

支付报告使用 Mockito Stub，不连接真实支付宝、MySQL、RabbitMQ 或 HTTP，因此只能表述为“支付域离线故障回归 20/20”，不能表述为“支付宝沙箱故障注入”或线上支付可用性。

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

12 个 run 的已提交 team/order/trade 行均保持一一唯一，数据库内部唯一性通过；但 50/100/200 并发分别出现 27/29/30 笔“服务端已提交、客户端未确认”的歧义事务，共 86 笔，不能表述为端到端终态 100% 一致。业务失败主要来自 8 位随机 `teamId` 在几十万次创建下的生日碰撞，需改为更大 ID 空间后复测。

测试环境为 Windows 11、AMD Ryzen 5 9600X（6C12T）、约 48 GB 内存、单 Group 实例、MySQL 8.0.32 和 Redis 7.2.14；k6、应用与中间件共享同一物理机，容器未设置 CPU/内存上限。Group 服务通过 `spring-boot:run` 启动且带 `-XX:TieredStopAtLevel=1`，并使用上述 20 连接/线程的开发配置，因此这是本地开发环境的容量与失效边界，不是生产 SLA。闭环模型还存在 coordinated omission；正式 SLO 仍需独立压测机、生产 JVM 参数、合理的 Tomcat/连接池容量和固定到达率复测。

原先 `371.7 QPS/P99 106 ms` 来自包含 91 次满员快速拒绝的 100 请求突发，只保留为并发正确性冒烟，不再作为容量指标。

## 指标口径

- **断言通过**：满足用例声明的任一/全部关键词，并通过工具名称和最少调用次数校验。
- **端到端成功**：断言通过，且 ReAct/Plan-and-Execute 用例的 `dialogue_run` 到达成功终态。
- **账本成功**：仅统计存在 `dialogue_run` 的运行；固定 chat 流程不生成该账本。
- **Token**：只使用 Spring AI 响应元数据中供应商上报并持久化的 usage。未返回 usage 的运行写为 `null`，不以 `TokenCounter.countText()` 补值。
- **反思 Token**：Evaluator 为预算控制使用的保守估算值，不是供应商计费 usage。
- **时延**：从 Gateway 请求开始到收到终态 SSE 帧的客户端墙钟时间。
- **LLM-as-Judge**：`run-evals.ps1 -Judge` 是可选的最终答复评分；它与 Plan-and-Execute 内部的 Evaluator LLM Judge 是两个独立指标。
- **离线估算 Token**：只用于同数据集、同预算下比较 Prompt 大小，不与在线 provider usage 混用。

## 运行方式

```powershell
# 先启动本地全栈并配置可用 LLM Key
pwsh docs/dev-ops/start-full-stack.ps1

# Agent 在线集
pwsh docs/evals/run-evals.ps1 -CasesFile cases.jsonl -ReportName agent-deterministic.json
pwsh docs/evals/run-evals.ps1 -CasesFile cases-tools.jsonl -ReportName agent-tools.json -TimeoutSec 600

# 离线与业务集成基准
pwsh docs/evals/run-offline-benchmarks.ps1
pwsh docs/evals/run-quota-benchmark.ps1
pwsh docs/evals/run-group-benchmark.ps1
pwsh docs/evals/run-group-load-benchmark.ps1
pwsh docs/evals/build-group-load-consolidated-report.ps1
pwsh docs/evals/run-benefit-mq-benchmark.ps1
pwsh docs/evals/run-payment-fault-tests.ps1
```

`run-evals.ps1` 在任一端到端用例失败时返回非零退出码，可直接作为回归门禁。报告保留数据集、环境、逐用例结果和指标语义，简历或面试材料中的数字应引用对应 JSON，而不是从控制台日志人工估算。
