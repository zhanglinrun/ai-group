# `group-service`（拼团交易服务）

ai-group 的拼团交易服务：活动展示、优惠试算、锁单占位、支付后结算、退款回补、通知补偿。`bff-service` 调它的营销配置接口拼装拼团价；Pay 在锁单和成团回调上与它协作。

主线是：

`进入活动页` → `试算`（算价格和资格）→ `锁单`（先占住名额）→ `支付成功` → `结算`（推进拼团状态）→ `退款或补偿`（处理超时、失败、逆向场景）

这里最容易被忽略、但最关键的一步是`锁单`。团购不是“谁付款成功谁算数”那么简单，如果不先占位，并发下很容易出现重复参团、名额超卖、支付后才发现团满、事后大量退款这些问题。所以系统要在支付之前先把资格和名额占住。

口径：活动查询和锁单的 `userId` 以网关 JWT `sub` 为准，不以 body 为准；结算/退款是回调和 Job，只认内部令牌 + 订单里的 userId。加入者占座走 Redis `INCR+1` + recovery + `SET NX`，然后请求线程同步写 MySQL `lock_count`；开团不占 Redis。落库失败或未成团退款给 recovery +1。限流是拼团侧 Redis 固定窗口，不是网关 `RequestRateLimiter`。Kafka 监听器手动 ack，失败有限重试后进 `{topic}.DLT`。

---

## 工程结构

这是一个 `Maven`（构建工具）多模块工程，核心是 6 个模块：

| 模块 | 职责 |
| --- | --- |
| `group-service-app` | 启动应用、加载配置、注册线程池和日志等运行时能力 |
| `group-service-trigger` | 系统入口，接 `HTTP`（接口请求）、定时任务、消息监听 |
| `group-service-domain` | 核心业务规则，内部再分 `activity` / `trade` / `tag` 三个域 |
| `group-service-infrastructure` | 把领域需要的能力落到技术实现：数据库、`Redis`（缓存）、消息、外部回调 |
| `group-service-api` | 对外约定：服务接口、`DTO`（数据传输对象）、统一返回对象 |
| `group-service-types` | 公共类型：枚举、异常、常量、事件基类 |

一句话记住分工：`app` 把系统装起来，`trigger` 接外部请求，`domain` 处理业务，`infrastructure` 查库发消息，`api` 定约定，`types` 提供通用表达。

---

## 三类系统入口

这个项目的入口不只有接口，从 `trigger`（入口层）能看到三类。

### 1. HTTP 接口

| 接口路径 | 作用 |
| --- | --- |
| `/api/v1/gbm/index/query_group_buy_market_config` | 查活动页营销配置，同时做试算 |
| `/api/v1/gbm/trade/lock_market_pay_order` | 发起锁单，占住交易资格 |
| `/api/v1/gbm/trade/settlement_market_pay_order` | 支付成功后结算 |
| `/api/v1/gbm/trade/refund_market_pay_order` | 发起退单或逆向处理 |
| `/api/v1/gbm/dcc/update_config` | 更新动态配置（开关、切量、限流） |

### 2. 定时任务

- `GroupBuyNotifyJob`（拼团回调通知任务）：扫描待通知任务，把回调结果继续往外推。
- `TimeoutRefundJob`（超时未支付退单任务）：扫描超时未支付订单，触发退单和回补。

### 3. 消息监听

- `RefundSuccessTopicListener`（退单成功监听）：退单成功后恢复锁单量，走最终一致性。成团消息由 Pay 消费，Group 不再监听 `group.team_success`。

---

## 领域划分

`domain`（领域层）里最重要的是三个业务域。

- `activity`（活动域）：负责活动页查询和优惠试算。用根节点、标签节点、开关节点、市场节点、结束节点这类编排，把试算流程拆开。主要回答：用户能不能看到这个活动、能不能参加、按什么规则算价。
- `trade`（交易域）：交易主线的核心，包含锁单、结算、退款、通知任务、锁单量回补。只想看一条最重要的链路，就看这里。
- `tag`（标签域）：处理人群标签。活动的可见性和可参与性，不只看活动本身，也看用户是否落在人群范围里。

---

## 几个关键业务概念

- **锁单是先占位，不是下单完成。** 锁单会先校验活动是否生效、用户是否超出参与次数、团队是否还有名额、外部单号是否重复，然后占住资格和团状态，但这还不算最终成交。
- **结算是在推动团状态变化。** 支付成功后不只是把订单改成“已支付”，还要判断这单属于哪个团、团里完成了多少、是否刚好成团、要不要生成回调任务。本质是在推动“拼团是否成功”。
- **退款不止一种。** 至少有三种场景：下单没支付超时退、已支付但没成团、已支付且已成团后又退款。不同场景恢复的数据和触发的动作都不一样，所以用了不同退款策略。
- **主流程结束不等于业务结束。** 后面往往还有一串尾巴：回调外部系统、本地消息补发、定时任务重试、`MQ`（消息队列）失败重投、锁单量恢复。这也是项目里会有 `notify_task`（通知任务表）和多个任务、监听器的原因。
- **本地通知任务不能把"调用了发送 API"当作成功。** 成团通知只走 Kafka；`ConfirmedKafkaPublisher` 等到 Broker ACK 才把 `notify_task` 标成成功，超时、失败或中断都会保留任务供后续补偿重试。

---

## 核心数据表

结合数据库看代码会清楚很多，先认识这几张表：

| 表 | 存什么 |
| --- | --- |
| `group_buy_activity` | 活动的时间、目标人数、限购次数、标签范围、状态 |
| `group_buy_discount` | 优惠方式和优惠表达式（直减、满减、`N` 元购等） |
| `group_buy_order` | 某个团队的拼团进度、目标人数、锁单量、完成量、有效期 |
| `group_buy_order_list` | 某个用户在某个团里的交易明细（外部单号、价格、状态） |
| `notify_task` | 后续需要回调或重试的任务 |
| `crowd_tags` / `crowd_tags_detail` | 人群标签本身，以及用户与标签的对应关系 |

---

## 技术栈

- `Java 21`（运行环境）
- `Spring Boot 3.5.16`（应用框架）
- `Maven`（构建工具）、`MyBatis`（持久层框架）
- `MySQL`（数据库）、`Redis`（缓存）、`Redisson`（分布式锁）
- `Kafka`（领域事件与 Outbox 投递）
- `Nacos`（服务发现）、`XXL-JOB`（补偿任务）
- 动态配置与限流组件的配置键仍是库前缀 `xfg.wrench`（不是网关 `RequestRateLimiter`）

观测栈在仓库 `dev-ops/observability`，不是本服务启动依赖。

---

## 本地运行

### 环境要求

- `JDK 21`
- `Maven 3.6+`
- `MySQL 8`、`Redis 6`、`Kafka 3.8`

### 启动步骤

1. **起中间件。** 在仓库根目录：

```powershell
docker compose --env-file .env -f dev-ops/compose/docker-compose.dev.yml up
```

全栈也可用 `dev-ops/compose/docker-compose.full.yml`。库表初始化走 Compose 挂载的 `group-service/docs/dev-ops/mysql/sql/2-29-group_buy_market.sql`。

2. **改开发配置。** 检查 `group-service-app/src/main/resources/application.yml`：数据源、Kafka、Redis，以及动态配置注册键 `xfg.wrench.config.register`（组件库前缀，不要改成别的名字）。

3. **构建并启动。** 在仓库根目录：

```bash
mvn clean install -DskipTests
mvn -pl group-service/group-service-app -am spring-boot:run
```

默认端口 `8091`。主启动类 `com.aigroup.groupbuy.Application`。

### 启动后最小验证

按 `活动页查询 → 锁单 → 结算 → 退款` 走一遍；身份口径见上文：用户 API 验 JWT，回调/Job 只认内部令牌。
