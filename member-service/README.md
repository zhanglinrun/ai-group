# `member-service`（额度账户服务）

这是 `ai-group` 里管理「免费额度 + 付费额度」的钱包服务。用户注册后获得每月免费额度，购买额度包并完成拼团后获得付费额度。Agent 创建 Run 时冻结一笔上界，图内多次 LLM 只累计 usage，终态再 `confirm` / `release`。

它默认运行在端口 `18082`，数据存储在 `member_db`（额度库，工程库名历史保留），持久层使用 `MyBatis-Plus`。

---

## 它管的两件事

### 1. 额度包权益

用户支付成团后，支付/结算侧会发送带订单快照的权益消息。member 只信任消息里的 `productCode`、`baseQuota` 和可选 `bonusQuota`，将额度统一换算成 microcredits 后计入付费余额，避免套餐后来改价或改额度影响历史订单。

关键点是**按订单 + 事件类型幂等**：同一笔订单的权益消息即使重复投递，也只会真正发放一次，靠 `benefit_grant_event` 的幂等键去重。额度已经发放后的撤销不会自动扣回，以免用户已消费后出现负账；系统记录 `REJECTED_GRANTED`，交由运营审核处理。

### 2. 对话配额（两阶段扣减）

Agent 消耗配额用「预授权 + 确认」两阶段，**当前是一个 Run 一笔冻结**，不是每次 LLM 各冻一笔：

- **预扣（freeze）**：创建 Run 时按输入估算与最大输出冻结一笔上界，`requestId=agent:{run_id}`。
- **确认（confirm）**：Run 终态按累计真实 Token usage 扣减（缺失时使用有界本地估算），并释放未使用余量。
- **释放（release）**：预留后未发起供应商调用，或供应商拒绝且没有 usage/输出证据时，释放整笔冻结。普通旧调用的僵尸冻结由 member 定时任务兜底；`ownerService=agent-service` 的冻结由 Agent 的持久化结算命令负责恢复，member 只告警，绝不按超时自动释放，避免供应商已经消耗后被误判成免费调用。

 `freeze` 的 `requestId` 是幂等键。同一用户用相同 `requestId` 重试时，请求额度上界、最小额度、能力编码和结算所有者必须完全一致；member 会保存服务端 SHA-256 指纹并拒绝参数漂移。结算所有者只接受 `legacy` 与 `agent-service`，避免未知调用方制造无法自动释放的冻结。`confirm` 与 `release` 都返回冻结的真实终态以及原始请求参数，因此调用方能识别 `CONFIRMED` / `RELEASED` 冲突、核验找回的冻结，并处理网络响应不确定。

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
| `POST /internal/members/init-free` | 内部补偿入口；正常注册通过 `UserRegistered` RabbitMQ 事件创建账户 |
| `POST /internal/member/quota/reservations` | 预扣配额（Agent 合同） |
| `POST /internal/member/quota/reservations/{reservationId}/confirm` | 确认扣减 |
| `POST /internal/member/quota/reservations/{reservationId}/release` | 释放冻结 |
| `GET /internal/member/quota/reservations/{reservationId}` | 按预留 ID 查询真实终态 |
| `GET /internal/member/quota/reservations/by-request?userId=...&requestId=...` | 在预扣响应丢失时按幂等键找回冻结 |
| `GET /internal/member/quota/{userId}` | 内部查用户额度摘要 |
| `GET /internal/benefits/orders/{orderId}/status` | 查某订单的权益发放状态 |

### 运营端（`/api/member/admin/**`，需要 `ADMIN` 角色）

用户额度详情查询、权益事件查看、配额人工调整、手动触发月度发放，以及套餐（SKU）的增删改查。用户端定价页和运营端读同一张 SKU 表，运营改完启用后立即可见。

---

## 两个定时任务

- `MonthlyQuotaGrantJob`（月度免费额度发放）：每月将账户免费额度重置为 5 credits，付费额度保持不变。
 - `ExpiredFreezeReleaseJob`（过期冻结释放）：释放无持久化结算所有者的旧式僵尸冻结；对 `agent-service` 托管冻结只记录告警，等待 Agent 的启动扫描和定时重试收敛。

---

## 消息消费

`BenefitEventConsumer`（权益事件消费者）监听成团消息队列，收到后触发权益发放；
`UserRegisteredEventConsumer` 监听注册事件并幂等开通免费账户。监听器手动 ACK：成功 `basicAck`，失败抛出让现有 `retry.max-attempts=3` 生效，耗尽后按 `default-requeue-rejected: true` 重入队。仓库没有独立死信交换机。

---

## 数据模型

| 表 / 实体 | 存什么 |
| --- | --- |
| `ProductSku`（额度包） | 套餐价格、基础额度和拼团商品/活动映射 |
| `QuotaAccount`（额度账户） | 免费额度、付费额度、冻结额度和最近免费发放月份 |
| `QuotaFreeze`（配额冻结） | 每笔预扣的冻结记录及其状态 |
| `QuotaLedger`（配额流水） | 配额变动的流水账 |
| `BenefitGrantEvent`（权益发放事件） | 按订单幂等的权益发放记录 |

---

## 本地运行

依赖 `MySQL`（数据库，`member_db`）、`Redis`（缓存）、`RabbitMQ`（Topic exchange 消息队列）、`Nacos`（注册中心）。表结构在 `src/main/resources/schema.sql`。

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
- `BenefitEventConsumer`（权益事件消费者）：监听成团消息。
- `MonthlyQuotaGrantJob` / `ExpiredFreezeReleaseJob`：两个定时任务。

---

## 提醒

- 权益发放和配额确认都要保持幂等，重复消息不能重复发、重复扣。
 - 两阶段扣减的 `confirm` / `release` 必须成对兜底。普通冻结由 `ExpiredFreezeReleaseJob` 清理；`agent-service` 托管冻结必须由 Agent durable settlement 恢复任务收敛，不能改回 member 超时自动释放。
- `/internal/**` 接口只走内部令牌，不要暴露给外部直连。
