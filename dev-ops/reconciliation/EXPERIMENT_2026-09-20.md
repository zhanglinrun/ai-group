# 交易与积分对账实验记录（2026-09-20）

## 环境与事实源

仅在 `ai-group-bench` 独立网络和卷中操作。Group/Pay/Member、Kafka、XXL-JOB 与三套 schema 位于同一台本地 Docker 主机；支付宝在测试覆盖配置中关闭。**不是支付宝真实付款、生产分库或异机故障注入。**合成订单 `bench-recon-20260920-1`、用户 `9000000000009`，实验后按这些精确 ID 清除业务行；审计 JSON 保留在忽略的 `dev-ops/reconciliation/reports/`。

四层事实：Group 团/成员明细、Pay 订单、Pay `benefit_event` outbox、Member `benefit_grant_event` + `quota_account` + `quota_ledger`。Pay 的 Kafka `SENT` 仅代表 broker 确认，不代表 Member 入账。`benefitReconciliationJob` 每分钟扫最多 50 条年龄大于 10 分钟的已发授予事件；需要 Member 响应 code 200，核对用户、SKU、微积分数量和人工审核标记。PENDING 且 Pay 订单仍为 MARKET/DEAL_DONE、无撤销事件时用原 eventId 有界重放，最多 3 次；冲突进 MANUAL。三库只读审计另行发现成团/订单/outbox/权益的陈旧差异，不自动修改钱或积分。

## 已发布未入账

1. 插入一笔旧于 10 分钟的合成已成团成员、Pay `MARKET` 订单与 `SENT` outbox，Member 有免费账户但无该订单权益事件。只读审计报告 `20260919T224823522811Z-3c79ee19` 非零退出，`SENT_COMPLETED_WITHOUT_MEMBER_RECORD=1`。
2. 启动 Pay 对账任务后，同一 `event_id=bench-recon-event-20260920-1` 被记录 `REPLAYED`，`replay_attempts=1`。Member Kafka 消费插入一条 `GRANTED`，发放 60,000,000 微积分；账户付费余额为 60,000,000，对应付费 GRANT 流水一条。
3. 下一轮核对确认用户、SKU、金额一致，Pay 状态为 `GRANTED/CONFIRMED`，尝试数仍为 1；审计报告 `20260919T225808339236Z-c1a46477` 为 0 差异。向 Kafka 重投相同事件后，余额仍为 60,000,000，订单授予事件和对应付费 GRANT 流水仍各一条。测试期间曾尝试提前该合成行的 `next_check_at`，影响行数为 0，确认由自然调度完成。

## 退款后权益冲突

仅对同一合成订单模拟本地退款终态并投递撤销事件，不调用支付宝。Member 保留授予记录 `GRANTED(60,000,000)`，记录撤销 `REJECTED_GRANTED(0)` 与零额 REVOKE 流水；付费余额保持 60,000,000，不从可能属于其他订单的汇总余额盲扣。审计报告 `20260919T230757410283Z-e5748e73` 非零退出，`REFUNDED_PAY_WITH_ACTIVE_MEMBER_GRANT=1`。该项须核对实际 Token 消费归属后人工处理，**不能称为退款自动追回积分**。

合成行清理后，Pay/outbox/Member/Group 指定 ID 的五项计数均为 0；审计报告 `20260919T231021437887Z-34c574e8` 回到 0 异常。原始实验日志与报告位于本机，脚本和判定规则见 `dev-ops/reconciliation/README.md` 与 `pay-service/docs/benefit-reconciliation.md`。

## 成团通知到权益入账（隔离链路）

在同一 `ai-group-bench`、`ALIPAY_ENABLED=false` 下，使用另一组精确合成 ID：`bench-notify-20260920-1`、团队 `bench-notify-team-20260920-1`、用户 `9000000000199`。先将 Pay 的 Group 订单锁定身份（订单/用户/团队/活动/source/channel）持久化，再投递携带相同身份的 `group.team_success` Kafka 消息。Pay 从 `PAY_SUCCESS` 推进到 `MARKET`，写一条已发送的 `GROUP_BUY_COMPLETED` outbox；Member 仅记一条 60,000,000 微积分授予及一条付费 GRANT 流水。再次投递同一成团通知后，outbox、Member 授予及 GRANT 流水仍各一条。错误团队与错误渠道的 Pay CAS 各影响 0 行，正确完整身份影响 1 行（探针事务回滚），说明通知不能仅凭订单号或当前配置跨渠道结算。这验证的是该组合下的幂等与身份约束，不是 Kafka 恰好一次或真实支付宝链路。

新 Pay `V10_group_order_identity.sql` 在隔离卷上执行两次均保留旧订单 NULL 身份快照；历史行不得从现行配置猜测补值，须按 `pay-service/docs/group-notification-identity.md` 核对后人工迁移。只读审计现增加 `LEGACY_GROUP_IDENTITY_MISSING`，对观察窗口内超过 10 分钟、已成团且 Pay 来源/渠道快照缺失的订单报告证据，不自动修复。上述合成业务行按精确 ID 清理后五项计数均为 0；当时最终审计 `20260920T045210499583Z-be25f31b` 为 0 异常。新增审计分类的 live SQL 复验尚未完成：后续 Docker 引擎未启动，报告 `20260920T050420893956Z-dcd2d391` 是查询失败而非零差异证据。

## 账本不变量与边界

Member 每日按 user_id 游标分页，比较 `free+paid` 与 GRANT/DEBIT/CONFIRM/MONTHLY_GRANT/ADMIN_ADJUST 经济流水净额、`frozen_balance` 与 PENDING 冻结额；差异按日/用户/类型留痕，**不自动修余额**。隔离 MySQL 的回滚探针：正常为 `150=150`、`20=20`，人为破坏快照后为 `151≠150`、`21≠20`，事务回滚后无测试账户残留。没有逐授予消费归属，因此已发积分的退款撤销目前保守记人工冲突。

跨库审计在本地三 schema 共用一个 MySQL 实例，可用一致性只读事务；生产若是不同物理数据库，需要分开采样、水位与幂等修复，不能称为跨库原子事务。Kafka/Pay 退款的最终状态检查仍非一个全局事务；可用 tombstone 和差异任务收敛，但不保证绝对恰好一次。Pay/Member 整链路峰值 TPS、支付宝沙箱回调、真实退款与跨主机容量尚未由该实验测得。

后续修复了 DLT 消费失败误 ack、原主题投递默认 `-dlt` 与监听 `.DLT` 不一致，以及异步 DLT 发送失败被当成恢复的问题；单测覆盖停止处理器、发送失败和跨分区目标，不等于真实 broker 的 offset/重启故障注入。已有 broker 如曾产生 `-dlt` 历史记录，须逐条核对原业务状态后人工处置，不能用这次新配置宣称历史消息已自动恢复。
