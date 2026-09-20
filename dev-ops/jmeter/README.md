# JMeter 压测

Gateway / Group / Member 的可重复 HTTP 压测入口。报告写入本目录 `reports/`（已 gitignore），不进入业务镜像。

## 前置

1. 安装 [Apache JMeter 5.6+](https://jmeter.apache.org/download_jmeter.cgi)，设置 `JMETER_HOME` 或把 `jmeter` 放进 `PATH`
2. 本机有 `python`（生成 `.jmx` 与汇总 `summary.json`）
3. 业务栈已启动（默认 Gateway `8080`，Group 指标口 `8091`）

## 场景

| 脚本 | 目标 | 说明 |
|------|------|------|
| `run-login-group-order.ps1` | Gateway `8080` | 注册 → 登录 → 创建拼团订单（含 Group 锁单）。**必须** `ALIPAY_ENABLED=false` |
| `run-group-lock.ps1` | Group `8091` | 直打锁单接口（带内部身份头），测 Group 锁争用 |
| `run-group-lock-fixed-team.ps1` | Group `8091` | Explicit existing team; 2000 signed users each send one lock request, synchronized spike |
| `run-quota-ledger.ps1` | Member（默认 `18082`） | 配额账本吞吐；需自行暴露 Member 端口 |

## 运行

```powershell
# 全链路下单冒烟压测（默认 20 用户 × 1 轮）
powershell -ExecutionPolicy Bypass -File dev-ops/jmeter/run-login-group-order.ps1

# 提高并发
$env:JMETER_THREADS = "50"
$env:JMETER_LOOPS = "3"
$env:JMETER_RAMPUP = "15"
powershell -ExecutionPolicy Bypass -File dev-ops/jmeter/run-login-group-order.ps1

# Group 锁单专项
powershell -ExecutionPolicy Bypass -File dev-ops/jmeter/run-group-lock.ps1 -Threads 20 -Duration 60
```

### 隔离 Group 测试栈

仅在本机 Docker 的测试数据上使用；`ai-group-bench` 是固定项目名，已有同名卷时会复用其数据，先确认归属。覆盖文件隔离网络/卷，并仅对该测试 Group 开启本地 MySQL 8.4 的公钥检索。

```powershell
docker compose -p ai-group-bench --env-file .env `
  -f dev-ops/compose/docker-compose.full.yml `
  -f dev-ops/jmeter/bench-compose.override.yml up -d --build group-service
```

该命令会启动 Group 及其 MySQL、Redis、Kafka、Nacos、XXL-JOB 依赖。初次建卷才运行数据库初始化 SQL；不要在已有业务卷上运行尖峰。实测和未覆盖范围见 [压测记录](BENCHMARK_GROUP_LOCK_2026-09-20.md)。

### Fixed-team one-shot spike (opt-in)

Use only the local `ai-group-bench` Compose project above, with an existing dedicated
team (no other lock/settlement/refund traffic). Independently inspect the team's
`target_count - lock_count` and pass that exact positive number as
`-ExpectedFreeSlots`; do not seed, delete, or update rows to prepare a team. The
runner checks this count again in a read-only preflight before sending traffic.
The Group service must be published on the supplied local port and configured for
the bench MySQL database. Non-local targets are rejected. The default new-team
`run-group-lock.ps1` is separate and unchanged.

For a read-only candidate list, open a shell in the bench MySQL container and
query the existing rows (the same shell uses the container's own DB credential):

```text
docker exec -it ai-group-bench-mysql-1 sh
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -D group_buy_market -e "SELECT team_id,activity_id,source,channel,status,valid_end_time,target_count,lock_count,target_count-lock_count AS free_slots FROM group_buy_order WHERE status=0 AND valid_end_time>NOW() AND target_count>lock_count AND source='s01' AND channel='c01' LIMIT 20"
exit
```

Confirm the selected goods mapping in `sc_sku_activity`; start with a small
`-Threads` value for tool validation and reserve the 2000-thread run for an
isolated, adequately provisioned environment.

```powershell
# Set these to the existing bench service values and verified team identifiers.
# Prompt privately; do not put values in shell history or JMeter arguments.
$env:AI_GROUP_IDENTITY_SIGNING_SECRET = Read-Host 'Bench signing secret' -MaskInput
$env:AI_GROUP_INTERNAL_TOKEN = Read-Host 'Bench internal token' -MaskInput
$teamId = '<existing team ID>'
$activityId = [long](Read-Host 'Existing activity ID')
$goodsId = '<existing goods ID>'
$freeSlots = [int](Read-Host 'Preflight free-slot count (target_count - lock_count)')
pwsh -File dev-ops/jmeter/run-group-lock-fixed-team.ps1 `
  -TeamId $teamId -ActivityId $activityId -GoodsId $goodsId -ExpectedFreeSlots $freeSlots `
  -Threads 2000 -RampUp 0 -HostName '127.0.0.1' -Port 8091
```

The runner generates the gitignored fixed-team JMX and sends one POST per thread,
with a distinct signed user and `jmeter-spike-<17-digit runId>-<32-hex UUID>`
outTradeNo per request (63 characters). No warmup is included. `RampUp=0` is
required; the synchronization barrier waits at most 120 s and JTL start times
must be within 5 s. The load generator must support the requested JVM threads
and sockets.

Each `reports/group-lock-fixed-team/<runId>/` contains `results.jtl`,
`summary.json`, `db-pre.json`, `db-post.json`, and `verification.json` (the post
snapshot is absent if preflight fails). The CSV saves only `businessCode` and
`outTradeNo` as sample variables, not JWTs/tokens. Summary latency includes
overall, accepted-lock and expected-rejection averages/P95/P99 separately.
HTTP 200 `E0006` (full), `E0005` (DB guard) and `E0008` (Redis guard) are
rejections, never accepted locks. Passing requires exactly the preflighted free
slots accepted, one sample per thread, unique run-scoped keys, no other response
or assertion errors, and a synchronized start.

The DB gate verifies Docker Compose project/service labels, the Group published
port and configured datasource, the database name and server identity. Each
snapshot is a consistent, read-only transaction in `ai-group-bench-mysql-1`.
It verifies unexpired in-progress team, activity/goods/source/channel mapping,
team counters and details, exact successful JTL outTradeNo set against new
run-scoped rows, absence of duplicates/misplaced rows and unchanged outbox.
It fails closed on any missing DB/JTL or mismatch. SQL parameters are restricted
to ASCII IDs and a numeric activity/run ID before interpolation; the MySQL root
password is consumed inside the container without appearing in logs/arguments.
The snapshots contain order identifiers and should be treated as local test data.
Do not infer pass/fail from `summary.json` alone; check `verification.json.passed`
and the runner's exit code. Inspect failed snapshots privately before a new run.
Earlier JTLs do not include outTradeNo and cannot be retroactively passed by this
gate; historical results in the benchmark record remain historical.

Local checks (no Group/MySQL access or load):

```powershell
python dev-ops/jmeter/plans/generate_group_lock_jmx.py --fixed-team
python -m unittest discover -s dev-ops/jmeter/plans -p test_group_lock_fixed_team.py
```

The fixed-team runner prints `GROUP_LOCK_FIXED_TEAM_OUTPUT=...` after load; it
writes CSV and JSON only, without an HTML dashboard. Other runners may generate HTML reports.

## 与观测栈配合

压测时建议同时打开：

- Grafana（JVM / QPS）：http://localhost:3000
- SkyWalking UI（全链路）：http://localhost:8088  
  需先按 [observability/README.md](../observability/README.md) 启用 Java Agent

## 文件

```text
dev-ops/jmeter/
├── plans/                 # .jmx 生成器与 JTL 汇总脚本
├── reports/               # 本地运行产物（gitignore）
├── run-login-group-order.ps1
├── run-group-lock.ps1
├── run-group-lock-fixed-team.ps1
└── run-quota-ledger.ps1
```
