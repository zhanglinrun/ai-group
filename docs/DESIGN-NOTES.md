# 设计取舍与面试答辩

面向 2027 届校招（后端 / Agent 应用开发）整理：清楚区分「教程底座」与「自研增量」，并为高频追问准备可自辩的答案。

---

## 一、教程底座 vs 自研增量（务必讲清边界）

本项目的三套业务系统起步于公开教程架构（小傅哥 xfg 的拼团 / s-pay-mall / ai-agent-station），
在其上做了成体系的二次开发。面试官大概率认识这些教程，主动讲清「哪些沿用、哪些是我加的」是加分项。

| 模块 | 沿用教程的部分 | 自研 / 重写的增量 |
|------|----------------|-------------------|
| 拼团 group | 试算责任链、四类折扣计算器骨架、`cn.bugstack.wrench` 责任链/策略树框架 | 结算成团并发竞态修复（DB 重查 + CAS 只成团一次）、本地消息表 + DLQ、退款终态双保险、**折扣金额分级精度修复**、**已支付超时未成团自动退款闭环**、`out_trade_no` 唯一索引 |
| 支付 pay | DDD 六模块骨架、支付宝下单/回调样例 | 回调「验签+金额+存在性+SQL 状态守卫」四件套、**掉单恢复重锁单**、**取消先关支付宝交易**、**WAIT_REFUND 补偿闭环**、权益 outbox + 重发、**结算确认位区分未结算/未成团**、四个补偿 Job |
| Agent | armory 从 DB 装配 Spring AI Bean、ReAct/Plan-Execute 骨架、`ai_client_*` 表结构 | **执行账本 + 历史回放（event sourcing）**、**Claude-skills 风格技能系统**、**deep_search 深度研究**、结构化工具输出协议、**配额计费闭环**（freeze→confirm/release）、受控线程池 + SSE 心跳 |
| 平台层 | —（自研） | 网关 JWT 校验 + 身份头剥离/注入、刷新令牌原子轮换、两阶段配额扣减、BFF 聚合降级、Nacos 服务发现 |

一句话定位：**这是一个「认真改造过教程、补齐了一致性与可观测短板」的复合型项目**，
账本回放 + skills + deep_search 是能讲 20 分钟的差异化亮点；拼团超卖防护与支付掉单/退款一致性经得起深挖。

---

## 二、高频追问预案

### 后端 / 一致性

- **拼团如何防超卖？** Redis 预占库存（原子 incr + 上限）+ DB 条件更新（`where lock_count < target` CAS）+ `uq_biz_id`/`uq_sc_out_trade_no` 唯一索引三层；成团判定在事务内重查最新 complete/target，`updateOrderStatus2COMPLETE` 依赖 `where status=0` 只成团一次。
- **支付掉单怎么办？** 下单/锁单/预支付分阶段；CREATE 态订单重试按「扣减额为 0/null」重新锁单（group 侧按 outTradeNo 幂等），杜绝退化为全价单；掉单扫描 Job 查支付宝对账恢复。
- **MQ 与本地事务如何保证最终一致？** 权益/成团用本地消息表（outbox）：业务与消息写在同一事务，异步投递 + 分钟级重发 Job；消费端有界重试 + DLQ，避免毒消息无限重投。
- **退款为什么不直接退？** 已支付拼团单先落 WAIT_REFUND 记录意图 + 通知 group 释放库存，等 team_refund 回调执行支付宝退款；回调若丢失，WAIT_REFUND 补偿 Job 重发通知 + 支付宝直退兜底，保证钱一定退回（均幂等）。
- **配额扣费如何不多扣/不少扣？** 两阶段：对话前 `freeze`（行锁 + 条件原子 UPDATE 冻结），成功 `confirm`（扣减）/失败 `release`（回滚冻结）；按执行账本 run 终态结算，`settled` CAS 保证 confirm/release 至多一次；freeze 以请求 ID 幂等，重试不重复冻结。
- **Nacos 起了什么作用？** 平台服务与 agent 注册到 Nacos，网关 `lb://服务名` 经服务发现 + 负载均衡转发，Feign 亦按 name 解析；group/pay 为未接入注册中心的集成业务系统，走配置端点。local profile 可关 Nacos 全直连。

### Agent / LLM 应用

- **手画你的 agent loop。** ReAct：`think → act → observe` 状态机，`maxSteps` 兜底；Plan-Execute：planner 拆任务 → executor 并发子任务 → summary 汇总。
- **执行账本怎么设计？** `dialogue_run`（run 级指标/终态）+ `llm_invocation` + `tool_invocation` + 按工具类型分表的 `tool_output_*` + `artifact_record`；`ReplayProjector` 按类型回放，支持会话历史精确重建。这是本项目最有分量的原创点。
- **异常语义为何靠账本终态结算？** think/summary 内 `catch(Exception)` 会吞异常降级到 summary，故不以「是否抛异常」而以 run 账本终态（成功/失败/超时）决定 confirm/release；chat 固定流策略已改为失败显式上抛，前端可见、配额释放（本次修复）。
- **技能沙箱安全边界？** `SkillPathGuard` 用绝对路径 normalize + `startsWith` 组件级校验挡住 `..`/越界；已知局限：未用 `toRealPath` 解析 symlink（可作为「下一步」讲）。真正执行在 Python reactor-tool 的临时工作区，剔除密钥环境变量。
- **RAG / 向量库选型？** 附属能力用 Spring AI VectorStore + Qdrant（原生支持、独立服务不侵入业务库）；对比 pgvector（有 PG 时零新增运维）/ Milvus（大规模分布式）。主打 RAG 的召回/重排故事在另一项目。
- **prompt injection 如何防？** 现状：deep_search/web_fetch 抓取正文直接进记忆（已知风险，未闭环）；思路：入站内容隔离 + 工具调用白名单 + 输出审查，作为「下一步」讲。

### 已知局限（主动交代，避免被当场问穿）

- 技能沙箱未解析 symlink（`toRealPath`）；工具外部正文无入站注入防护；`TokenCounter` 以字符数近似 token；内部令牌为共享密钥（拿到即可冒充，生产需独立密钥/签名传播/mTLS）；group/pay 未接入 Nacos。

---

## 三、技术选型理由速记

- **RabbitMQ 而非 RocketMQ**：本地起停轻、Spring 集成成熟、满足权益/成团/退款的可靠投递（有界重试 + DLQ）；若强调顺序/事务消息与更高吞吐可换 RocketMQ。
- **MyBatis(-Plus) 而非 JPA**：SQL 可控，便于写状态守卫 UPDATE（`where status=...`）实现幂等与防回退。
- **Spring AI 而非裸 HTTP**：统一 ChatModel/Advisor/VectorStore 抽象，保留 OkHttp legacy 回退兼容。
