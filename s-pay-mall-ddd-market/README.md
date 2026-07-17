# 支付与拼团结算服务

该工程负责额度商品下单、支付宝支付、拼团结算、退款，以及支付结果到会员额度发放之间的可靠事件交付。

## 模块

- `s-pay-mall-ddd-app`：Spring Boot 启动、配置、MyBatis 映射与测试。
- `s-pay-mall-ddd-domain`：订单、商品履约、权益 outbox 与补偿逻辑。
- `s-pay-mall-ddd-infrastructure`：MySQL、RabbitMQ、支付宝、拼团服务和 member-service 适配。
- `s-pay-mall-ddd-trigger`：HTTP 接口、MQ 消费者和定时补偿任务。
- `s-pay-mall-ddd-api` / `s-pay-mall-ddd-types`：跨层 DTO、事件和公共类型。

## 主要链路

### 下单幂等边界

- 客户端每次显式购买生成一个稳定 `requestId`；服务端以 `(user_id, client_request_id)` 唯一键决定唯一订单 owner。
- 指纹基于服务端规范化后的 `userId/productId/productCode/marketType/activityId/teamId` 计算。相同键同载荷回放原订单，不同载荷返回 `409` 冲突。
- 只有唯一键插入成功者可以调用 group 锁单与支付宝预下单；并发插入输家查询并回放赢家，不重复外部副作用。
- 直购、开团、参团以及不同活动/队伍具有不同指纹，不能共用一个 `requestId`。
- 订单列表使用 `id < lastId ORDER BY id DESC` 的倒序 keyset 分页，新订单稳定出现在首屏。

### 直接购买

1. 支付结果把订单从 `CREATE/PAY_WAIT` 更新为 `PAY_SUCCESS`。
2. 同一个本地事务向 `benefit_event` 写入两条 outbox：
   - `ORDER_PAY_SUCCESS`：驱动模拟履约，最终把订单更新为 `DEAL_DONE`。
   - `GROUP_BUY_COMPLETED`：沿用 member 侧既有协议，发放基础额度。
3. 事务提交后，`OutboxEventPublishJob` 才能扫描并发送消息。

### 拼团购买

1. 支付成功后通知 group 服务登记成员已支付；通知丢失由结算补偿任务重试。
2. 成团回调只结算真正处于 `PAY_SUCCESS` 的订单。
3. 订单更新为 `MARKET` 与履约/权益 outbox 写入处于同一个事务；阶梯加赠额度随权益事件发送。
4. 过期、已完成、满员等终态拒绝会进入幂等支付宝退款，不把订单永久留在 `PAY_SUCCESS`。

### 退款与权益撤销

支付宝退款成功后，本地关单与 `GROUP_BUY_REVOKED` outbox 原子提交。若同一订单已有完成事件，发布器会先确保完成事件到达 Broker，再发送撤销；member 同时保留乱序 tombstone 防线，使先到的撤销能阻止后到完成事件误发额度。若额度早已发放，member 记录人工审核状态，不自动扣成负余额。

## Outbox 与 RabbitMQ 门禁

现有 `benefit_event` 表作为统一交易 outbox 使用，表名为历史命名。`order_id + event_type` 唯一键保证重复回调只生成一条同类事件。

- 业务事务只写 outbox，不直接发送 MQ。
- 发布器采用 persistent message、mandatory routing 和 correlated publisher confirm。
- 只有 broker ACK 且消息未被 returned，才把 `event_published` 更新为 `1`。
- NACK、不可路由、超时或中断都会保留未发布状态，由轮询任务重试。
- 交付语义为至少一次；member 权益消费和订单履约更新必须保持幂等。

相关迁移：

- `docs/dev-ops/mysql/sql/V3_benefit_event.sql`
- `docs/dev-ops/mysql/sql/V5_transactional_outbox.sql`
- `docs/dev-ops/mysql/sql/V6_pay_order_idempotency.sql`

一键启动脚本会幂等执行这些迁移，并为历史直购 `PAY_SUCCESS`、已成团 `MARKET` 订单补建缺失的履约 outbox；历史直购还会补建可能缺失的额度事件。等待成团的拼团 `PAY_SUCCESS` 不会被提前履约。

## 本地配置

- pay 服务默认端口：`8070`
- member-service 默认地址：`http://127.0.0.1:18082`
- Rabbit confirm 超时：`RABBITMQ_PUBLISH_CONFIRM_TIMEOUT_MS`，默认 `5000`
- outbox 扫描间隔：`PAY_OUTBOX_PUBLISH_DELAY_MS`，默认 `1000`

## 测试

```powershell
$ErrorActionPreference = 'Stop'
mvn -pl s-pay-mall-ddd-app -am test -DskipTests=false
```

2026-07-17 本地快照为 `78/78` 通过。单元测试使用 Mockito 模拟 Rabbit confirm/return，不依赖真实 RabbitMQ；数据库迁移仍应在目标 MySQL 环境幂等执行并核验。
