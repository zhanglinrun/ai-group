# Agent 最小评测集（evals）

一个轻量、可复现的 Agent 效果评测：把「Demo 能跑」推向「可度量」。经 Gateway 跑真实 SSE 对话，
对每条用例做关键词命中断言，并从执行账本 `agent_db.dialogue_run` 读取步数与 token 消耗。

## 组成

- `cases.jsonl`：20 条确定性用例，每条含 `id / mode(html=ReAct, chat) / query / expect(关键词，任一命中即通过)`。
  用例均为模型知识内可直接作答的问题，规避工具调用带来的不确定性，保证评测可复现。
- `cases-tools.jsonl`：工具轨迹 / 规划类用例，额外支持 `deepThink(1=Plan-Execute)`、
  `expectTools(期望使用的工具名，任一命中即算轨迹正确)`、`minToolCalls(最少工具调用数)`。
  这类用例依赖 reactor-tool 与联网工具，结果有一定不确定性，单独成集、按需运行。
- `run-evals.ps1`：注册登录 → 逐条经 `/web/api/v1/gpt/queryAgentStreamIncr` 跑 SSE →
  关键词/工具轨迹断言 → 按 `session_id` 回读 `dialogue_run`（步数/token/终态/耗时）与 `tool_invocation`（工具轨迹）→
  输出通过率、任务成功率、平均步数/token、p50/p95 时延、工具使用率、失败原因分类，并落 `last-report.json`。

## 运行

```powershell
# 需先启动全栈（docs/dev-ops/start-full-stack.ps1）并配置可用 LLM Key
pwsh docs/evals/run-evals.ps1                         # 确定性核心集（20 条）
pwsh docs/evals/run-evals.ps1 -Limit 3                # 快速冒烟
pwsh docs/evals/run-evals.ps1 -Judge                  # 额外启用 LLM-as-judge 打分
pwsh docs/evals/run-evals.ps1 -CasesFile cases-tools.jsonl   # 工具轨迹 / 规划集
```

## 指标口径

- 关键词通过率：关键词命中的用例占比（正确性代理指标）。
- 任务成功率：执行账本 `dialogue_run.status` 终态为成功的占比（区别于"命中关键词"，反映链路是否正常收敛）。
- 平均步数（LLM 调用）/平均 token：来自 ReAct 执行账本，反映成本与推理链长度。
- p50/p95 时延：每条用例 SSE 端到端墙钟耗时的分位数，反映延迟分布。
- 工具使用率：产生工具调用的 run 占比；工具轨迹断言校验实际用到的工具是否符合预期。
- LLM-as-judge（可选 `-Judge`）：让模型对答复相对参考要点做 0-5 忠实度/相关性打分，汇总均分。
- 失败原因分类：`keyword-miss / tool-trajectory-miss / tool-count-miss / empty-answer / error`。
- chat 模式走固定流策略，不产生 `dialogue_run` 账本，故成本类指标以 ReAct 用例为准。

## 面试可讲

- 从"能跑"推进到"可度量"：通过率 + 任务成功率 + p95 时延 + 平均 token + 工具轨迹 + LLM-as-judge 的多维口径；
- 失败原因分类支撑归因；工具轨迹断言校验 Function Calling 是否按预期编排；
- 可接入 CI 做回归门禁（`exit 1` 已在未全绿时返回非零）。
