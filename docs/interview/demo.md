# 10–15 分钟现场演示脚本

## 准备

- 使用全新演示账号（现场注册，不在仓库保存密码）。
- 运行 `pwsh docs/dev-ops/start-full-stack.ps1`；无恢复 JAR 时使用 `-DemoLite`。
- 准备 `docs/acceptance/p170-final-e2e-20260731-213253.json`、`trace-demo.json`、P120 eval 报告和离线 DEEP 查询。

## 演示

1. **1 分钟：业务边界**：登录后展示 Member 免费额度；说明 Group/Pay 只负责额度发放，Agent 只消费额度。
2. **2 分钟：STANDARD**：发送 `直接回答，不调用工具：AGENT_PRODUCT_STANDARD_OK`，说明 Harness、quota 和 SSE 是同一条链路。
3. **3 分钟：DEEP**：离线请求生成表格，展示 Planner/Researcher/Evidence/ReportSpec 的进度与 CSV 下载。
4. **2 分钟：可靠性**：断线后带 `Last-Event-ID` 重连；打开 event ledger 解释持久回放。
5. **2 分钟：worker**：执行 durable worker recovery 演示；说明 safe retry 与 UNKNOWN 的区别。
6. **2 分钟：质量与安全**：展示 Trace 白名单、P120 Golden Eval、Citation Gate 和一条安全拒绝证据。
7. **1 分钟：收尾**：展示 P170 PASSED、冻结 Manifest 和已知限制。

## 离线兜底命令

```powershell
pwsh .\docs\acceptance\agent-product-e2e.ps1 -TimeoutSeconds 420 -RequireDiagnostics
```

推荐查询：`生成一个表格产物，列出 STANDARD、DEEP、工具、诊断四项及其最小验收内容。`

## 三句收尾

1. Graph 只编排，Java Harness 集中生产语义。
2. 模型、工具、额度和事件都有 durable ledger，断线不等于取消。
3. 质量由 Evidence Ledger、Trace 和 Eval 证明，而不是靠一次演示成功。
