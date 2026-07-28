# AI-Group：拼团交易与可计费 Agent 平台

> 能力摘要：**Agent 3 点 + 拼团与支付 3 点**，所有描述均以本仓代码和可复跑证据为准。
> 不做：Plan-Execute、节点 checkpoint、一体化助手、Bitmap「降存 90%」、ELK/Prometheus 主亮点（本仓不以运维 MCP 为主叙事）。
> 在线 Agent 评测成功率未复跑前不记录稳定性百分比。

---

## 项目能力 HTML

```html
<div class="proj-item">
    <div class="flex-row">
        <div class="text-base font-bold">AI-Group：拼团获额与按量计费 Agent 平台</div>
        <div class="text-sm font-bold">2025.11 – 至今</div>
    </div>
    <div class="text-sm proj-desc">
        <div class="tech-line"><span class="font-bold">技术栈：</span>Java 21、Spring Boot、Spring Cloud、Spring AI、MyBatis、MySQL、PostgreSQL / pgvector、Redis / Redisson、Kafka、XXL-JOB、OpenFeign、Jackson、JAXB、Nacos、Alipay SDK</div>
        <span class="font-bold">项目描述：</span>同仓两个独立业务子系统，仅通过会员额度账户关联。拼团侧 DDD 划分活动、标签、交易域，覆盖营销试算、锁单占位、成团结算、支付回调、退款与权益入账全流程；Agent 侧以统一 Agent Loop 承载工具调用、Todo 证据、上下文/记忆、Skills/MCP 与完成门禁，按调用级预冻/确认/释放消耗额度，支撑深度调研与报告类复杂任务。产品可演示「先获额再跑 Agent」，工程边界解耦，额度是唯一接头。<br>
        <span class="font-bold">核心职责：</span>
        <ul>
            <li><span class="font-bold">统一 Agent Loop、完成门禁与 Deep Research：</span>普通复杂任务收敛为单 Loop（<code>AgentRuntime → AgentLoopFactory → AgentLoop</code>），模型在受控工具视图内循环决策；<code>AUTO</code> 归一化为 <code>STANDARD</code>，<code>DEEP</code> 路由到独立的 <code>DeepResearchGraphRunner</code>。DEEP 使用 <code>todo_write</code> 维护任务清单与证据策略，研究分支共享父级取消和预算边界，最终统一合并、审阅并生成报告。CompletionGate 在终态前检查未完成 Todo、失败工具与必需产物，拒绝时把原因和修正动作回灌同一运行。对外统一 <code>run_started → verification / completion_blocked → run_finished → result</code> 生命周期与类型化 <code>stopReason</code>。运行、LLM、工具与产物分层写入执行账本，支持投影回放与跨工具 artifact 复用。</li>
            <li><span class="font-bold">上下文压缩与结构化记忆：</span>ContextManager 按统一 Token 预算治理消息、工具 Schema 与安全余量，按 user turn 保留 tool-call/tool-result 原子单元；PostgreSQL 分层保存 qa_pair、session/cross summary 与结构化画像，按 owner/conversation 隔离，并用 pgvector 余弦、pg_trgm 与 RRF 混合召回。固定离线集只记录实际 token、费用和成功率，不把单次样本包装成生产指标。</li>
            <li><span class="font-bold">Skills / MCP 工具分层与调用级额度结算：</span>实现 <code>SKILL.md</code> 描述符路由与按需加载（命中后再读正文并截断保护），避免全量技能指令撑爆 Prompt；MCP Registry 支持 SSE、STDIO、Streamable HTTP 发现与调用，延迟暴露下通过 <code>tool_search</code> + <code>execute_extra_tool</code> 代理执行以稳定模型可见 schema。PermissionPolicy 校验暴露边界与身份字段，失败结果 typed 回灌 Loop。调用级额度：每次 LLM 调用先 freeze 上界，结束后按供应商 usage confirm 并释放余量，经 requestId、账户行锁、条件更新与冻结单状态守卫保证幂等；遗留冻结由补偿任务扫描释放。9 项 Skills 离线基准中平均估算 Prompt 由 24,851 降至 3,432 Token（降低 86.2%），加载校验 9/9；100 个并发唯一冻结及冻结/确认各 100 次重复调用中重复扣减为 0，50 条遗留冻结全部释放。配套 <code>docs/evals</code> 可复跑在线与离线评测，稳定性指标以复跑报告为准。</li>
            <li><span class="font-bold">规则树试算 + 责任链交易校验 + 优惠/退款策略化：</span>DDD 划分活动、标签、交易三域。试算链路将活动开关、流量切分、人群过滤、优惠计算、兜底等拆成规则树串行节点（Root→Switch→Market→Tag），新场景靠重配节点复用；Market 节点支持 <code>CompletableFuture</code> 并行加载活动折扣与 SKU，响应时间由串行之和降为最慢依赖。优惠计算用策略 + 模板方法拆分直减/满减/折扣/N 元购等，Spring Map 注入动态选策略，开闭原则扩展新类型。锁单、结算、退款采用通用责任链模板：规则节点独立、工厂组装成链、节点可跨链复用；退款侧策略覆盖未支付、已支付未成团、已支付已成团，并联动库存与营销额度回补，降低逆向交易耦合。</li>
            <li><span class="font-bold">高并发库存占位、成团一致性与动态配置：</span>锁单采用「原子操作优先、锁兜底」：Redis 原子计数无锁预占/回补团库存，降低 DB 行锁竞争；成团用 MySQL 条件更新与状态 CAS，叠加业务唯一约束与幂等键防止超卖、重复锁单与重复团队；Redisson 分布式锁仅在补偿 Job、极端竞态等路径兜底。支持锁单结果查询，承载超时后的幂等重试。并发正确性基准中未出现超卖、重复团队或重复结算副作用；本地开发配置可靠档并发 20 下成功锁单 QPS 约 422、P99 约 136 ms（同机开发基准，非生产 SLA）。动态配置中心（DCC）基于 Redis Pub/Sub 热更新降级、切量等运行参数，无需改码重启即可调策略。</li>
            <li><span class="font-bold">支付回调、本地消息可靠通知与权益最终一致：</span>支付域对接支付宝沙箱，完成验签、金额校验与订单状态守卫；拼团与支付拆服务协作，支付完成驱动成团结算与权益履约。成团/退款回调支持 HTTP + MQ 双通道，本地消息表（notify_task / Outbox）先落库再异步投递，配合 Publisher Confirm、重试/DLQ、消费幂等；定时补偿任务以 Redisson 独占锁保证集群下单实例执行，收敛通知与资金状态最终一致。权益事件投影至 member 额度账户，形成「拼团获额 → Agent 按次结算」唯一接头。权益 MQ 基准（50 唯一 + 50 重复）到账率 100%、重复发放 0；支付域离线故障回归 20/20，覆盖超时关单对账、退款重试、未支付权益守卫与退款状态机。</li>
        </ul>
    </div>
</div>
```

---

## 六条能力摘要

| # | 归属 | 标题 | 实现依据 |
|---|---|---|---|
| 1 | Agent | 统一 Agent Loop、完成门禁与 Deep Research | 运行时、图执行与账本测试 |
| 2 | Agent | 上下文压缩与结构化记忆 | 离线数据集与存储契约 |
| 3 | Agent | Skills/MCP 与调用级额度 | 工具互操作与额度基准 |
| 4 | 拼团 | 规则树 + 责任链 + 策略 + CF 并行 | 领域服务与规则节点 |
| 5 | 拼团 | Redis 原子预占 + CAS/唯一索引 + DCC | 并发正确性测试与配置实现 |
| 6 | 拼团 | HTTP/Kafka + 本地消息 + 退款策略 + 发额 | 回调、Outbox 与补偿任务 |

**能力边界：** 当前不声明 Bitmap 固定节省比例、完整 Event Sourcing、通用派生子 Agent、生产 SLA，或把额度账户集成表述为拼团直接调用 MCP。
