# Agent 最小评测集（evals）

一个轻量、可复现的 Agent 效果评测：把「Demo 能跑」推向「可度量」。经 Gateway 跑真实 SSE 对话，
对每条用例做关键词命中断言，并从执行账本 `agent_db.dialogue_run` 读取步数与 token 消耗。

## 组成

- `cases.jsonl`：20 条确定性用例，每条含 `id / mode(html=ReAct, chat) / query / expect(关键词，任一命中即通过)`。
  用例均为模型知识内可直接作答的问题，规避工具调用带来的不确定性，保证评测可复现。
- `run-evals.ps1`：注册登录 → 逐条经 `/web/api/v1/gpt/queryAgentStreamIncr` 跑 SSE →
  汇总最终文本做关键词断言 → 按 `session_id` 回读 `dialogue_run` 的 `llm_call_count / total_tokens_total` →
  输出通过率、平均 LLM 调用数、平均 token，并落 `last-report.json`。

## 运行

```powershell
# 需先启动全栈（docs/dev-ops/start-full-stack.ps1）并配置可用 LLM Key
pwsh docs/evals/run-evals.ps1            # 全量 20 条
pwsh docs/evals/run-evals.ps1 -Limit 3   # 快速冒烟
```

## 指标口径

- 通过率：关键词命中的用例占比（正确性代理指标）。
- 平均步数（LLM 调用）/平均 token：来自 ReAct 执行账本，反映成本与推理链长度。
- chat 模式走固定流策略，不产生 `dialogue_run` 账本，故成本指标以 ReAct 用例为准。

## 可扩展方向（面试可讲）

- 断言从关键词升级为 LLM-as-judge 或语义相似度；
- 增加工具调用类用例并断言工具轨迹；
- 记录 p50/p95 时延与失败原因分类，接入 CI 做回归门禁。
