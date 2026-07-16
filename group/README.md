# `group-buy-market`（团购交易项目）

这是一个围绕团购活动的营销交易项目。它管理的是一场团购从开始到结束的完整过程：活动展示、优惠试算、锁单占位、支付结算、退款回补、通知补偿。

它现在也是 `ai-group`（会员平台）下的一个子服务，`bff-service`（前端聚合层）会调它的营销配置接口来拼装拼团价格。但抛开平台不谈，它本身就是一个可以独立运行、独立学习的团购交易系统。

如果你在学 `Java`（编程语言）业务项目，这个仓库适合用来练几件事：

- 看懂一条真实的团购交易主线是怎么走的。
- 理解 `DDD`（领域驱动设计）在工程里怎么分层落地。
- 明白规则链、策略、仓储、消息、定时补偿这些写法为什么会出现在业务系统里。

---

## 一句话理解业务

它在管理“用户参加团购活动”这件事的全过程。这条主线大致是：

`进入活动页` → `试算`（算价格和资格）→ `锁单`（先占住名额）→ `支付成功` → `结算`（推进拼团状态）→ `退款或补偿`（处理超时、失败、逆向场景）

这里最容易被忽略、但最关键的一步是`锁单`。团购不是“谁付款成功谁算数”那么简单，如果不先占位，并发下很容易出现重复参团、名额超卖、支付后才发现团满、事后大量退款这些问题。所以系统要在支付之前先把资格和名额占住。

---

## 工程结构

这是一个 `Maven`（构建工具）多模块工程，核心是 6 个模块：

| 模块 | 职责 |
| --- | --- |
| `group-buy-market-app` | 启动应用、加载配置、注册线程池和日志等运行时能力 |
| `group-buy-market-trigger` | 系统入口，接 `HTTP`（接口请求）、定时任务、消息监听 |
| `group-buy-market-domain` | 核心业务规则，内部再分 `activity` / `trade` / `tag` 三个域 |
| `group-buy-market-infrastructure` | 把领域需要的能力落到技术实现：数据库、`Redis`（缓存）、消息、外部回调 |
| `group-buy-market-api` | 对外约定：服务接口、`DTO`（数据传输对象）、统一返回对象 |
| `group-buy-market-types` | 公共类型：枚举、异常、常量、事件基类 |

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

- `TeamSuccessTopicListener`（组队成功监听）：接收拼团完结相关消息。
- `RefundSuccessTopicListener`（退单成功监听）：退单成功后恢复锁单量，走最终一致性。

---

## 领域划分

`domain`（领域层）里最重要的是三个业务域。

- `activity`（活动域）：负责活动页查询和优惠试算。用根节点、标签节点、开关节点、市场节点、结束节点这类编排，把试算流程拆开。主要回答：用户能不能看到这个活动、能不能参加、按什么规则算价。
- `trade`（交易域）：交易主线的核心，包含锁单、结算、退款、通知任务、锁单量回补。只想看一条最重要的链路，就看这里。
- `tag`（标签域）：处理人群标签。活动的可见性和可参与性，不只看活动本身，也看用户是否落在人群范围里。

---

## 几个关键业务概念

这几点是新手最容易看漏、但最能帮你建立业务感觉的地方。

- **锁单是先占位，不是下单完成。** 锁单会先校验活动是否生效、用户是否超出参与次数、团队是否还有名额、外部单号是否重复，然后占住资格和团状态，但这还不算最终成交。
- **结算是在推动团状态变化。** 支付成功后不只是把订单改成“已支付”，还要判断这单属于哪个团、团里完成了多少、是否刚好成团、要不要生成回调任务。本质是在推动“拼团是否成功”。
- **退款不止一种。** 至少有三种场景：下单没支付超时退、已支付但没成团、已支付且已成团后又退款。不同场景恢复的数据和触发的动作都不一样，所以用了不同退款策略。
- **主流程结束不等于业务结束。** 后面往往还有一串尾巴：回调外部系统、本地消息补发、定时任务重试、`MQ`（消息队列）失败重投、锁单量恢复。这也是项目里会有 `notify_task`（通知任务表）和多个任务、监听器的原因。

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
- `RabbitMQ`（消息队列）
- `Prometheus`（监控采集）+ `Grafana`（监控展示）+ `Logstash`（日志采集）

它不是纯演示项目，把监控、日志、任务、消息这些接近真实业务系统的东西也带上了。

---

## 本地运行

### 环境要求

- `JDK 21`
- `Maven 3.6+`
- `MySQL 8`、`Redis 6`、`RabbitMQ 3`

### 启动步骤

1. **起中间件。** 参考 `docs/dev-ops/docker-compose-environment.yml`（本地中间件编排文件）准备 MySQL、Redis、RabbitMQ：

```bash
docker-compose -f docs/dev-ops/docker-compose-environment.yml up -d
```

2. **初始化数据库。** 用一套完整脚本导入，不要混用不同版本：

```
docs/tag/v3.0/mysql/sql/group_buy_market.sql
```

3. **改开发配置。** 检查 `group-buy-market-app/src/main/resources/application-dev.yml`，确认这几类能连通本地：`spring.datasource`（数据库）、`spring.rabbitmq`（消息队列）、`redis.sdk.config`（缓存）、`xfg.wrench.config.register`（动态配置注册）。注意 RabbitMQ 的业务连接端口和管理后台端口不是一回事，别填错。

4. **构建。** 在仓库根目录执行：

```bash
mvn clean install -DskipTests
```

5. **启动。** 二选一：

```bash
mvn -pl group-buy-market-app -am spring-boot:run
```

或在 `IDEA`（开发工具）里直接运行主启动类 `com.aigroup.groupbuy.Application`。默认端口 `8091`。

### 启动后最小验证

按 `活动页查询 → 锁单 → 结算 → 退款` 的顺序各调一次接口，确认主线和逆向流程都能走通。不想手写请求，可以先看 `docs/ui/html`（静态示例页）。

---

## 建议的阅读顺序

第一次看这个项目，不建议从头硬啃所有类，按下面顺序更稳：

1. 先读本 README 的“一句话理解业务”和“几个关键业务概念”，把主线装进脑子。
2. 看 `trigger`（入口层），知道请求从哪进来。
3. 看 `domain`（领域层），重点跟 `trade`（交易域）的锁单、结算、退款。
4. 再看 `infrastructure`（基础设施层），了解具体怎么查库、占库存、发消息。
5. 最后回头看 `app`（启动配置），补线程池、配置加载这些工程细节。

一个实用建议：测试类往往比主链路更短，很适合当阅读入口。想快速上手可以先看这几个：

- 入口层：`MarketIndexControllerTest`、`MarketTradeControllerTest`、`DCCControllerTest`
- 领域层：`IIndexGroupBuyMarketServiceTest`（试算）、`ITradeLockOrderServiceTest`（锁单）、`TradeSettlementOrderServiceTest`（结算）、`ITradeRefundOrderServiceTest`（退款）
- 基础设施：`GroupBuyActivityDaoTest`、`GroupBuyDiscountDaoTest`、`GroupBuyNotifyServiceTest`
- 整链路：`Link01Test`、`Link02Test`

---

## 目录速查

| 目录 | 用途 |
| --- | --- |
| `docs/dev-ops` | 本地环境、部署、监控相关文件（Docker 编排、Grafana、Prometheus、Logstash 等配置） |
| `docs/tag` | 不同版本的演示资源和数据库脚本 |
| `docs/ui` | 界面示例 |

想先把项目跑起来，优先看 `docs/dev-ops`。
