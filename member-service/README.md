# `member-service`（额度账户服务）

这是 `ai-group` 里管理「免费额度 + 付费额度」的钱包服务。用户注册后获得每月免费额度，购买额度包并在直购支付成功或拼团成团后获得付费额度。Agent 每次打模型前检查可用余额，调用结束后按真实 Token `debit`。额度不够时任务暂停，充值后从当前进度继续。

它默认运行在端口 `18082`，数据存储在 `member_db`（额度库，工程库名历史保留），持久层使用 `MyBatis-Plus`。

---

## 它管的两件事

### 1. 额度包权益

用户直购支付成功或拼团成团后，支付/结算侧会发送带订单快照的权益消息。member 只信任消息里的 `productCode` 和 `baseQuota`，将额度统一换算成 microcredits 后计入付费余额，避免套餐后来改价或改额度影响历史订单。订单展示状态：发放前撤销为 `REVOKED`；额度已发放后的撤销仍为 `GRANTED`，并标记 `manualReview`。

关键点是**按订单 + 事件类型幂等**：同一笔订单的权益消息即使重复投递，也只会真正发放一次，靠 `benefit_grant_event` 的幂等键去重。撤销先于发放时写入 tombstone，后到的发放不再入账。额度已发放时，撤销只记录 `REJECTED_GRANTED` 和零额 `REVOKE` 审计流水，留待人工处理，不自动改动付费余额。总付费余额即使足够覆盖原发放额度，也可能来自之后的其他订单，不能证明该订单额度尚未消费；缺少按发放逐笔归属的消费记录时无法安全自动扣回。这里只处理额度权益，不会自动退款。

### 2. 对话配额（调用后扣减）

Agent 消耗配额以 **调用后原子扣减（debit）** 为主，旧的 freeze/confirm/release 仍保留给历史冻结对账：

- **门禁**：每次打模型前读取可用额度；低于本次估算则拒绝，Run 暂停，充值后从检查点续跑。估算只用于门禁，不作为扣费上限。
- **扣减（debit）**：供应商返回真实 Token usage 后，按 `requestId=agent:{run_id}:call:{uuid}` 幂等扣减。同一 `requestId` 重试必须指纹一致，否则拒绝。
- **缺 usage**：不以估算扣费，也不按 0 自动退回；Attempt 挂 `PENDING_RECONCILIATION`，由 Agent 进程内扫描重试。
- **未发起调用**：不打 debit。历史 freeze 行仍由 `ExpiredFreezeReleaseJob` / Agent 扫描收敛。

客户端断开会阻止后续步骤，但已在途或已完成的供应商调用仍会按可得 usage 结算，并不承诺“断开即免费”。账户分为**免费额度**（每月重置为 5 credits）和**付费额度**（购买额度包获得、不按月清零），内部统一使用 `1 credit = 1,000,000 microcredits` 计量，变动记录在 `quota_ledger`。Agent 当前按每百万 Token 输入 5 积分、输出 30 积分计费，仍按实际 Token 逐个结算，不按 1K Token 向上取整。

---

## 对外接口

### 用户端

| 接口 | 作用 |
| --- | --- |
| `GET /api/member/skus` | 查在售套餐列表（用户端定价页用） |
| `GET /api/member/summary` | 查免费、付费、冻结及可用额度摘要 |
| `GET /api/member/quota-ledger` | 查最近的额度流水 |

### 服务间内部接口（`/internal/**`，只允许服务间带内部令牌调用）

| 接口 | 作用 |
| --- | --- |
| `POST /internal/member/quota/debits` | 按真实 Token 原子扣减（Agent 热路径） |
| `POST /internal/member/quota/reservations` | 预扣配额（历史冻结对账） |
| `POST /internal/member/quota/reservations/{reservationId}/confirm` | 确认扣减 |
| `POST /internal/member/quota/reservations/{reservationId}/release` | 释放冻结 |
| `GET /internal/member/quota/reservations/{reservationId}` | 按预留 ID 查询真实终态 |
| `GET /internal/member/quota/reservations/by-request?userId=...&requestId=...` | 在预扣响应丢失时按幂等键找回冻结 |
| `GET /internal/member/quota/{userId}` | 内部查用户额度摘要 |
| `GET /internal/benefits/orders/{orderId}/status` | 查某订单的权益发放状态 |

### 运营端（`/api/member/admin/**`，需要 `ADMIN` 角色）

用户额度详情查询、权益事件查看、配额人工调整、手动触发月度发放，以及套餐（SKU）的增删改查。用户端定价页和运营端读同一张 SKU 表，运营改完启用后立即可见。

---

## 定时任务

- `MonthlyQuotaGrantJob`（月度免费额度发放）：每月将账户免费额度重置为 5 credits，付费额度保持不变；流水只记免费余额的差额。
- `ExpiredFreezeReleaseJob`（过期冻结释放）：释放无持久化结算所有者的旧式僵尸冻结；对 `agent-service` 托管冻结只记录告警，等待 Agent 的启动扫描和定时重试收敛。
- `QuotaReconciliationJob`（只读额度对账）：XXL-JOB `member` 执行器的 `quotaReconciliationJob` 每日 02:30 执行，阻塞策略 `SERIAL_EXECUTION`。默认每页 200 户，可用 `AI_GROUP_MEMBER_RECONCILIATION_PAGE_SIZE` 配置（1..500）；完成日志记录扫描户数和两类不一致数，至多打印 5 条样本。失败会抛出异常供 XXL-JOB 告警，已写入的异常记录可安全重跑。

对账按 `user_id` 游标分页，以启动时最大 `user_id` 为上界；每页用一条一致性读取 SQL 比较 `free_quota_balance + paid_quota_balance` 与 `GRANT/DEBIT/CONFIRM/MONTHLY_GRANT/ADMIN_ADJUST` 流水之和、`frozen_balance` 与 `PENDING` 冻结金额之和。`FREEZE` 只是预留，`RELEASE`/`REVOKE` 不产生经济差额。每页独立快照，跨页并非全库同一时点；运行中开户或变更可能造成暂时遗漏或告警，建议低峰期执行并复查。不会修改账户、冻结或流水。

异常证据保存在 `quota_reconciliation_mismatch`，主键 `(check_date, user_id, check_type)`，类型 `LEDGER_BALANCE` / `PENDING_FREEZE`，含快照金额、来源金额及首次/最近发现时间；当日重跑更新同一条异常记录，不新增重复行。记录代表当日曾发现异常，健康重跑不会自动清除旧记录，需结合 `last_seen_at` 和后续运行结果人工复核。日期以 member JVM 本地时区为准，无自动清理历史记录。

新 MySQL volume 通过 Compose 初始化 `schema.sql` 和幂等建表迁移；已有 volume 不会重放 init SQL，启用任务前要对 `member_db` 执行 `src/main/resources/migrations/V1__quota_reconciliation_mismatch.sql`。全新 XXL-JOB 卷随种子 SQL 注册每日任务；已有 XXL 卷需执行 `dev-ops/xxl-job/migrations/2026-09-20-quota-reconciliation.sql`，该脚本按执行器名称查找并可重复执行。无运行时自动迁移。

---

## 消息消费

`BenefitEventConsumer` 监听 `member.benefit.completed`（直购与成团共用），收到后触发权益发放；
`UserRegisteredEventConsumer` 监听注册事件并幂等开通免费账户。监听器手动 ack：业务成功后再提交 offset，失败由 `DefaultErrorHandler` 有限重试，耗尽后发到 `{topic}.DLT`；DLT 监听器再走同一套幂等方法，仍失败只打 `kafka.dlt.exhausted` 后 ack。

---

## 数据模型

| 表 / 实体 | 存什么 |
| --- | --- |
| `ProductSku`（额度包） | 套餐价格、基础额度和拼团商品/活动映射 |
| `QuotaAccount`（额度账户） | 免费额度、付费额度、冻结额度和最近免费发放月份 |
| `QuotaFreeze`（配额冻结） | 历史预扣冻结记录及其状态 |
| `QuotaDebit`（配额扣减） | 每次按真实 Token 的幂等扣费 |
| `QuotaLedger`（配额流水） | 配额变动的流水账 |
| `BenefitGrantEvent`（权益发放事件） | 按订单幂等的权益发放记录 |
| `quota_reconciliation_mismatch`（对账证据） | 按日期/用户/类型保留发现的不一致 |

已有 `member_db` 不会自动重跑 `schema.sql`，历史环境若缺 `quota_debit` 也需补执行建表语句。

---

## 本地运行

依赖 `MySQL`（数据库，`member_db`）、`Redis`（缓存）、`Kafka`（领域事件）、`Nacos`（注册中心）。表结构在 `src/main/resources/schema.sql`。

在仓库根目录跟平台一起起：

```powershell
docker compose --env-file .env -f dev-ops/compose/docker-compose.full.yml up --build
```

单独跑（先确认中间件就绪）：

```bash
cd member-service && mvn spring-boot:run
```

权益与账本回归用模块测试和 `eval/http-smoke.ps1`，不要找已删除的独立 smoke 脚本。

---

## 相关代码

- `MemberController`（用户端 + 内部接口）、`MemberAdminController`（运营端接口）。
- `MemberServiceImpl`（额度服务实现）：权益发放、额度预留与结算的主逻辑。
- `BenefitEventConsumer`（权益事件消费者）：监听 `member.benefit.completed`。
- `MonthlyQuotaGrantJob` / `ExpiredFreezeReleaseJob` / `QuotaReconciliationJob`：三个定时任务。

---

## 提醒

- 权益发放和配额确认都要保持幂等，重复消息不能重复发、重复扣。
- 热路径 `debit` 必须按 `requestId` 幂等。历史冻结的 `confirm` / `release` 仍须成对兜底：普通冻结由 `ExpiredFreezeReleaseJob` 清理；`agent-service` 托管冻结必须由 Agent 进程内结算扫描收敛，不能改回 member 超时自动释放。
- `/internal/**` 接口只走内部令牌，不要暴露给外部直连。
