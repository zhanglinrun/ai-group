import { useMemo } from "react";
import { CheckCircle2, GitBranch, RotateCcw, ShieldCheck, TriangleAlert } from "lucide-react";

import type { RunTraceResponse } from "@/api/types";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { buildQaClosure, type QaMetricDelta } from "@/lib/qaClosure";
import { formatDateTime } from "@/lib/format";

export interface QaClosureSectionProps {
  trace: RunTraceResponse;
}

function formatMetricValue(value: number | null): string {
  return value === null ? "-" : value.toLocaleString();
}

function formatDelta(delta: QaMetricDelta): string {
  if (delta.before === null || delta.after === null) {
    return "-";
  }
  const diff = delta.after - delta.before;
  if (diff === 0) {
    return "0";
  }
  return diff > 0 ? `+${diff.toLocaleString()}` : diff.toLocaleString();
}

function renderList(items: string[], emptyText: string): JSX.Element {
  if (items.length === 0) {
    return <p className="text-xs text-muted-foreground">{emptyText}</p>;
  }
  return (
    <ul className="space-y-1 text-xs text-muted-foreground">
      {items.slice(0, 5).map((item, index) => (
        <li key={`${item}-${index.toString(10)}`}>- {item}</li>
      ))}
    </ul>
  );
}

export function QaClosureSection({ trace }: QaClosureSectionProps): JSX.Element {
  const summary = useMemo(() => buildQaClosure(trace), [trace]);

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle className="flex items-center gap-2 text-base">
            <ShieldCheck className="h-4 w-4 text-primary" />
            QA 反馈闭环
          </CardTitle>
          <div className="flex flex-wrap gap-1.5">
            <Badge variant="secondary">QA {summary.qaStepCount}</Badge>
            <Badge variant={summary.rejectedStepCount > 0 ? "warning" : "success"}>
              打回 {summary.rejectedStepCount}
            </Badge>
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {summary.qaStepCount === 0 ? (
          <div className="rounded-lg border border-border bg-muted/20 p-4 text-sm text-muted-foreground">
            当前 run 尚无 QA step。
          </div>
        ) : null}

        {summary.qaStepCount > 0 && summary.rounds.length === 0 ? (
          <div className="flex items-start gap-3 rounded-lg border border-success/25 bg-success/10 p-4">
            <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-success" />
            <div>
              <p className="text-sm font-medium text-foreground">QA 规则全部通过</p>
              <p className="mt-1 text-xs text-muted-foreground">
                本次运行有 QA 校验记录，但没有打回轮次。
              </p>
            </div>
          </div>
        ) : null}

        {summary.rounds.map((round) => (
          <article className="rounded-lg border border-warning/25 bg-warning/5 p-4" key={round.qaStep.step_id}>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="flex items-center gap-2 text-sm font-medium text-foreground">
                  <TriangleAlert className="h-4 w-4 text-warning" />
                  第 {round.round} 轮 QA 打回
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  {formatDateTime(round.qaStep.created_at)} · reject_to={round.rejectTo ?? "-"}
                </p>
              </div>
              <div className="flex flex-wrap gap-1.5">
                {round.failedRuleIds.map((ruleId) => (
                  <Badge key={ruleId} variant="warning">
                    {ruleId}
                  </Badge>
                ))}
              </div>
            </div>

            <div className="mt-4 grid gap-3 lg:grid-cols-3">
              <div className="rounded-md border border-border bg-background/60 p-3">
                <p className="mb-2 text-xs font-medium text-foreground">打回原因</p>
                {renderList(
                  round.semanticFindings.length > 0 ? round.semanticFindings : round.qaReasons,
                  "未返回结构化语义原因。",
                )}
              </div>
              <div className="rounded-md border border-border bg-background/60 p-3">
                <p className="mb-2 flex items-center gap-1.5 text-xs font-medium text-foreground">
                  <GitBranch className="h-3.5 w-3.5" />
                  Supervisor 调度
                </p>
                {round.decision === null ? (
                  <p className="text-xs text-muted-foreground">未匹配到 qa_rejection 决策。</p>
                ) : (
                  <div className="space-y-1 text-xs text-muted-foreground">
                    <p>tool: {round.decision.chosen_tool}</p>
                    <p>iter: {round.decision.iteration}</p>
                    <p>{round.decision.reasoning_summary}</p>
                  </div>
                )}
              </div>
              <div className="rounded-md border border-border bg-background/60 p-3">
                <p className="mb-2 flex items-center gap-1.5 text-xs font-medium text-foreground">
                  <RotateCcw className="h-3.5 w-3.5" />
                  重做后变化
                </p>
                {round.redoStep === null ? (
                  <p className="text-xs text-muted-foreground">未匹配到重做 step。</p>
                ) : (
                  <div className="space-y-2 text-xs text-muted-foreground">
                    <p>
                      {round.redoStep.agent_name} · {round.redoStep.step_id}
                    </p>
                    <div className="flex items-center justify-between gap-2">
                      <span>{round.failedRuleCountDelta.label}</span>
                      <span className="font-mono">
                        {formatMetricValue(round.failedRuleCountDelta.before)} →{" "}
                        {formatMetricValue(round.failedRuleCountDelta.after)}
                        <span className="ml-1 text-foreground">
                          ({formatDelta(round.failedRuleCountDelta)})
                        </span>
                      </span>
                    </div>
                    {round.deltas.length === 0 ? (
                      <p>该 Agent 暂无可比较计数字段。</p>
                    ) : (
                      round.deltas.map((delta) => (
                        <div className="flex items-center justify-between gap-2" key={delta.label}>
                          <span>{delta.label}</span>
                          <span className="font-mono">
                            {formatMetricValue(delta.before)} → {formatMetricValue(delta.after)}
                            <span className="ml-1 text-foreground">({formatDelta(delta)})</span>
                          </span>
                        </div>
                      ))
                    )}
                  </div>
                )}
              </div>
            </div>
          </article>
        ))}
      </CardContent>
    </Card>
  );
}
