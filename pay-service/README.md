# 支付与拼团结算服务

该工程负责额度商品下单、支付宝支付、拼团结算、退款，以及支付结果到付费额度发放之间的可靠事件交付。

Java 包名 `com.aigroup.paymall`、库名 `s_pay_mall_ddd_market` 是历史保留，运行时服务名是 `pay-service`。内部同步调用走 OpenFeign + Nacos。

## 模块

- `pay-service-app`：Spring Boot 启动、配置、MyBatis 映射与测试。
- `pay-service-domain`：订单状态、权益 outbox 与补偿逻辑。
- `pay-service-infrastructure`：MySQL、Kafka、支付宝、拼团服务和 member-service 适配。
- `pay-service-trigger`：HTTP 接口、MQ 消费者和定时补偿任务。
- `pay-service-api` / `pay-service-types`：跨层 DTO、事件和公共类型。

## 身份边界

用户 API（创建订单、列表、退款）验 Gateway 签发的 `X-Internal-Jwt`，`userId` 取 JWT `sub`；body / `X-User-Id` 只能对照，不能当身份。Pay → Group 锁单时 Feign 转发 JWT + 内部令牌。支付宝 notify、Kafka `group.team_success` 成团结算、补偿 Job 只认内部令牌，`userId` 来自已落库订单。成团入口只有 Kafka，没有 HTTP `group/notify`。

## 主要链路

### 下单幂等边界

- 客户端每次显式购买生成一个稳定 `requestId`；服务端以 `(user_id, client_request_id)` 唯一键决定唯一订单 owner。
- 指纹基于服务端规范化后的 `userId/productId/productCode/marketType/activityId/teamId` 计算。相同键同载荷回放原订单，不同载荷返回 `409` 冲突。
- 只有唯一键插入成功者可以调用 group 锁单与支付宝预下单；并发插入输家查询并回放赢家，不重复外部副作用。
- 直购、开团、参团以及不同活动/队伍具有不同指纹，不能共用一个 `requestId`。
- 订单列表使用 `id < lastId ORDER BY id DESC` 的倒序 keyset 分页，新订单稳定出现在首屏。

### 直接购买

1. 支付结果在同一事务内把订单从 `CREATE/PAY_WAIT` 更新为 `PAY_SUCCESS` 再落到 `DEAL_DONE`。
2. 同一事务向 `benefit_event` 写入 `GROUP_BUY_COMPLETED`，按订单 `baseQuota` 快照发放额度。
3. 事务提交后，`OutboxEventPublishJob` 才能扫描并发送消息。

### 拼团购买

1. 支付成功后，回调线程同步 Feign 通知 group 登记成员已支付；通知丢失由 `settlement_notified` + 结算补偿任务重试。直购不走这条同步结算，只写 Outbox。
2. Kafka `TeamSuccessTopicListener` 只结算真正处于 `PAY_SUCCESS` 的订单。
3. 订单更新为 `MARKET` 与权益 outbox 写入处于同一个事务；额度只按订单里的 `baseQuota` 快照发放。`MARKET` 是成团终态，不会再被覆盖成 `DEAL_DONE`。
4. 过期、已完成、满员等终态拒绝会进入幂等支付宝退款，不把订单永久留在 `PAY_SUCCESS`。

### 退款与权益撤销

支付宝退款成功后，本地关单与 `GROUP_BUY_REVOKED` outbox 原子提交。若同一订单已有完成事件，发布器会先确保完成事件到达 Broker，再发送撤销；member 同时保留乱序 tombstone 防线，使先到的撤销能阻止后到完成事件误发额度。若额度早已发放，member 记录人工审核状态，不自动扣成负余额。

## Outbox 与 Kafka 门禁

现有 `benefit_event` 表作为统一交易 outbox 使用，表名为历史命名。`order_id + event_type` 唯一键保证重复回调只生成一条同类事件。

- 业务事务只写 outbox，不直接发送 MQ。
- 发布器采用 Kafka `acks=all` 幂等生产者，`send().get(timeout)` 等待 Broker ACK。
- 只有 Broker 确认接收，才把 `event_published` 更新为 `1`。
- 超时、失败或中断都会保留未发布状态，由发布轮询任务重试。
- 默认由 XXL-JOB 调度 `outboxEventPublishJob`（full Compose 已内置 Admin，默认 `XXL_JOB_ENABLED=true`）。
- 仅在没有 Admin 时，才设 `PAY_OUTBOX_LOCAL_SCHEDULER_ENABLED=true`，用同一发布器的 Spring `@Scheduled` 兜底；二者不要同时开启，避免双调度。
- 交付语义为至少一次；member 权益消费必须保持幂等。直购 `DEAL_DONE` 已在支付成功事务内完成，不再靠 Kafka 自循环改订单状态。

相关迁移：

- `docs/dev-ops/mysql/sql/V3_benefit_event.sql`
- `docs/dev-ops/mysql/sql/V5_transactional_outbox.sql`
- `docs/dev-ops/mysql/sql/V6_pay_order_idempotency.sql`

一键启动脚本会幂等执行这些迁移。`V5` 里给历史直购补过的 `ORDER_PAY_SUCCESS` 行已不再被消费（该 topic 已删除）；当前只认 `GROUP_BUY_COMPLETED` / `GROUP_BUY_REVOKED`。等待成团的拼团 `PAY_SUCCESS` 不会被提前发额度。

## 本地配置

- pay 服务默认端口：`8070`
- member-service 默认地址：`http://127.0.0.1:18082`
- Kafka 发布 ACK 超时：`KAFKA_ACK_TIMEOUT_MS`，默认 `5000`
- outbox 扫描：优先 XXL-JOB `outboxEventPublishJob`（Admin：`http://localhost:18081/xxl-job-admin`）。
  无 Admin 时再用 `PAY_OUTBOX_LOCAL_SCHEDULER_ENABLED=true`；本地定时间隔由
  `PAY_OUTBOX_PUBLISH_INTERVAL_MS` 控制，默认 `1000` 毫秒。

## 测试

```powershell
$ErrorActionPreference = 'Stop'
mvn -pl pay-service-app -am test -DskipTests=false
```

单元测试使用 Mockito 模拟 KafkaTemplate，不依赖真实 Broker；数据库迁移仍应在目标 MySQL 环境幂等执行并核验。
