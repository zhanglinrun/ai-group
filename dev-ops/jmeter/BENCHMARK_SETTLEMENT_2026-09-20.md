# 成团结算幂等与吞吐压测（2026-09-20）

## 范围

- 环境：`ai-group-bench`，`ALIPAY_ENABLED=false`
- 路径：向 `group.team_success` 投递成团通知 → Pay `PAY_SUCCESS→MARKET` + 本地消息表 → Kafka 权益事件 → Member 幂等入账
- **不是**支付宝沙箱真实收款吞吐，也不是 Gateway 全链路

脚本：`dev-ops/jmeter/plans/bench_settlement_load.py`  
入口：`dev-ops/jmeter/run-settlement-bench.ps1`（Compose 网络内 kafka-python 发压，不再走 `kafka-console-producer`）

## 与 campus-dash ≈191 TPS 的口径差

| | campus-dash SettleConcurrencyIT | 本项目 E2E | 本项目 Pay 切片 |
| --- | --- | --- | --- |
| 热路径 | 进程内单库结算（MQ 关闭） | 2×Kafka + Pay/Member 两事务 | 进程内 `changeOrderMarketSettlement`（写 MARKET + outbox） |
| 发压 | 64 工作线程 | 网络内 64 worker produce | 64 工作线程 |
| 公式 | `round(N * 1000 / elapsed_ms)` | 同左 | 同左 |

完整 E2E **不会**对齐到 191；简历成团→入账数字用 E2E，Pay 单库切片另报。

## 结果（优化后复测）

报告：`reports/settlement-bench/20260920T080114987553Z-eb356890/summary.json`

### 1. 500 并发零重复入账

- 预置 1 笔 `PAY_SUCCESS`，首次成团入账后，64 worker 再投递 **500** 次相同 `team_success`
- `benefit_grant_event=1`、`benefit_event=1`、`paid_quota_balance` 仍为单次额度 → **零重复入账**

### 2. E2E 结算吞吐（成团通知 → 权益入账）

- 500 笔互不相关订单，网络内并行 produce（`produceMs=236`）
- 总耗时 **7123 ms**，**e2eTps ≈ 70**
- MARKET / SENT outbox / 授予事件 / GRANT 流水均为 **500**

相对优化前（console-producer 发压、约 26 TPS / 200 单）主增益来自发压对齐 + listener 并发 / 分区 / Hikari / outbox 线程池（仅 `-p ai-group-bench`）。

### 3. Pay MARKET 切片（与 campus-dash 同构）

| 度量 | 结果 |
| --- | --- |
| `MarketSettlementConcurrencyIT`（进程内 OrderService，500 单 / 64 线程） | **≈354 TPS**（`elapsedMs=1413`，MARKET=outbox=500） |
| 脚本 `payMarketSlice`（纯 MySQL CAS+outbox insert） | **≈438 TPS** |

切片不含 Member；不可冒充全链路。

## 复现

```powershell
docker compose -p ai-group-bench --env-file .env `
  -f dev-ops/compose/docker-compose.full.yml `
  -f dev-ops/jmeter/bench-compose.override.yml up -d --build mysql redis kafka nacos member-service pay-service group-service

# 若刚改过 consumer groupId，先停服务、删空 group，再起（bench 覆盖已设 auto-offset-reset=latest）
powershell -ExecutionPolicy Bypass -File dev-ops/jmeter/run-settlement-bench.ps1 `
  -Concurrency 500 -Orders 500 -Workers 64

# Pay 进程内切片 IT（需 MYSQL_ROOT_PASSWORD，bench MySQL :13306）
$env:MYSQL_ROOT_PASSWORD = (Get-Content .env | ? { $_ -match '^MYSQL_ROOT_PASSWORD=' })
mvn -pl pay-service/pay-service-app "-Dtest=MarketSettlementConcurrencyIT" test
```

## Bench-only 调参（`bench-compose.override.yml`）

- `SPRING_KAFKA_LISTENER_CONCURRENCY=16`，topic 分区 16（`AI_GROUP_KAFKA_TOPIC_PARTITIONS`）
- Hikari `maximum-pool-size=40`
- Pay outbox 线程池 core/max 32/64，`AbortPolicy`（避免 CallerRuns 堵死 listener）
- Pay/Member listener 使用独立 consumer group（不再共用一个 `pay-service` / `member-service` group 抢分区）

## 边界

- 吞吐数字描述本机 Docker「成团通知→权益入账」路径，不可外推生产或支付宝打款
- 不把 console-producer / 发压工具耗时写进简历吞吐
- 不关闭 Outbox/Kafka 正确性来刷数字
