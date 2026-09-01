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

成功后终端会打印 `JMETER_HTML=...` 与 `*_REPORT=...`。HTML 报告在对应 `reports/<scenario>/<runId>/html/`。

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
└── run-quota-ledger.ps1
```
