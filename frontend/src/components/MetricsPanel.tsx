import { useMemo } from "react";

import { useRunMetrics } from "@/api/hooks";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

export interface MetricsPanelProps {
  runId: string;
  isRunActive: boolean;
}

interface MetricItem {
  key: string;
  label: string;
  value: string;
  hint: string;
}

function formatPercent(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}

function formatInteger(value: number): string {
  return value.toLocaleString();
}

function formatWallClock(value: number | null): string {
  if (value === null) {
    return "-";
  }
  if (value < 60) {
    return `${value}s`;
  }
  const minutes = Math.floor(value / 60);
  const seconds = value % 60;
  return `${minutes}m ${seconds}s`;
}

function formatDistribution(distribution: Record<string, number>): string {
  const entries = Object.entries(distribution);
  if (entries.length === 0) {
    return "-";
  }
  return entries.map(([key, count]) => `${key}: ${count}`).join(" · ");
}

export function MetricsPanel({ runId, isRunActive }: MetricsPanelProps): JSX.Element {
  const metricsQuery = useRunMetrics(runId, {
    enabled: Boolean(runId),
    refetchInterval: isRunActive ? 2_000 : false,
  });

  const metricsItems = useMemo<MetricItem[]>(() => {
    const metrics = metricsQuery.data;
    if (!metrics) {
      return [];
    }
    const authorityTotal = Object.values(metrics.source_authority_distribution).reduce(
      (sum, count) => sum + count,
      0,
    );
    const officialSourceCount = metrics.source_authority_distribution.official ?? 0;
    return [
      {
        key: "coverage",
        label: "覆盖率",
        value: formatPercent(metrics.coverage_rate),
        hint: "有 evidence 的竞品数 / run 竞品总数",
      },
      {
        key: "dimension_coverage",
        label: "结构维度覆盖",
        value: formatPercent(metrics.dimension_coverage_rate),
        hint: "分析/报告已覆盖的目标维度比例",
      },
      {
        key: "evidence_dimension_coverage",
        label: "证据维度覆盖",
        value: formatPercent(metrics.evidence_dimension_coverage_rate),
        hint: "研究任务目标维度中有同维度 evidence 的比例",
      },
      {
        key: "knowledge_schema_coverage",
        label: "功能/定价/口碑覆盖率",
        value: formatPercent(metrics.knowledge_schema_coverage_rate),
        hint: "run_knowledge coverage 中 complete/partial 的占比",
      },
      {
        key: "knowledge_feature_count",
        label: "Feature 条目",
        value: formatInteger(metrics.knowledge_feature_count),
        hint: "最新 run_knowledge.features 条目数",
      },
      {
        key: "knowledge_pricing_count",
        label: "Pricing 条目",
        value: formatInteger(metrics.knowledge_pricing_count),
        hint: "最新 run_knowledge.pricings 条目数",
      },
      {
        key: "knowledge_persona_count",
        label: "Persona 条目",
        value: formatInteger(metrics.knowledge_persona_count),
        hint: "最新 run_knowledge.personas 条目数",
      },
      {
        key: "qa_rejection",
        label: "QA 打回率",
        value: formatPercent(metrics.qa_rejection_rate),
        hint: "rejection 的 QA step 数 / QA step 总数",
      },
      {
        key: "manual_review",
        label: "人工修正率*",
        value: formatPercent(metrics.manual_review_rate),
        hint: "代理指标：reviewed skill candidate / run 相关 candidate 总数",
      },
      {
        key: "desensitization",
        label: "脱敏覆盖率",
        value: formatPercent(metrics.desensitization_coverage),
        hint: "desensitized=true 的 evidence 比例",
      },
      {
        key: "official_source",
        label: "官方来源占比",
        value: formatPercent(authorityTotal === 0 ? 0 : officialSourceCount / authorityTotal),
        hint: "source_authority=official 的 evidence 比例",
      },
      {
        key: "locale_match",
        label: "地域匹配度",
        value: formatPercent(metrics.locale_match_rate),
        hint: "source_url 地域/文本语言与目标市场语言匹配的 evidence 比例",
      },
      {
        key: "evidence",
        label: "证据总量",
        value: formatInteger(metrics.evidence_count_total),
        hint: "当前 run 写入 evidence 表的记录数",
      },
      {
        key: "token",
        label: "Token 总量",
        value: formatInteger(metrics.llm_token_total),
        hint: "prompt_tokens + completion_tokens 总和",
      },
      {
        key: "llm_calls",
        label: "LLM 调用数",
        value: formatInteger(metrics.llm_call_count),
        hint: "当前 run 的 llm_calls 行数",
      },
      {
        key: "llm_p50",
        label: "LLM 延迟 P50",
        value: metrics.llm_latency_p50_ms === null ? "-" : `${metrics.llm_latency_p50_ms}ms`,
        hint: "llm_calls.latency_ms 中位数",
      },
      {
        key: "llm_retry_total",
        label: "LLM 重试",
        value: formatInteger(metrics.llm_retry_total),
        hint: "llm_calls.retry_count 总和",
      },
      {
        key: "llm_provider_error",
        label: "Provider 错误",
        value: formatInteger(metrics.llm_provider_error_count),
        hint: "llm_calls.error 非空的调用数",
      },
      {
        key: "supervisor",
        label: "Supervisor 迭代",
        value: formatInteger(metrics.supervisor_iterations),
        hint: "supervisor_decisions.iteration 的最大值",
      },
      {
        key: "wall_clock",
        label: "Run 用时",
        value: formatWallClock(metrics.run_wall_clock_seconds),
        hint: "finished_at - started_at（若未完成则为空）",
      },
      {
        key: "qa_total",
        label: "QA 执行次数",
        value: formatInteger(metrics.qa_total_steps),
        hint: "agent_name=qa 的 step 总数",
      },
      {
        key: "qa_rejected_steps",
        label: "QA 打回次数",
        value: formatInteger(metrics.qa_rejected_steps),
        hint: "rejection_reason 非空或状态为 rejected 的 QA step 数",
      },
    ];
  }, [metricsQuery.data]);

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">业务闭环指标</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {metricsQuery.isLoading ? (
          <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
            {Array.from({ length: 8 }).map((_, index) => (
              <Skeleton className="h-16 w-full" key={`metrics-skeleton-${index}`} />
            ))}
          </div>
        ) : null}

        {metricsQuery.isError ? (
          <div className="rounded-md border border-red-400/40 bg-red-500/10 p-3 text-sm text-red-200">
            指标读取失败：{metricsQuery.error.message}
          </div>
        ) : null}

        {metricsItems.length > 0 ? (
          <>
            <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
              {metricsItems.map((item) => (
                <div className="rounded-md border border-border p-3" key={item.key}>
                  <p className="text-xs text-muted-foreground" title={item.hint}>
                    {item.label}
                  </p>
                  <p className="mt-1 text-lg font-semibold">{item.value}</p>
                </div>
              ))}
            </div>

            <div className="grid gap-3 text-xs text-muted-foreground sm:grid-cols-2">
              <div className="rounded-md border border-border p-3">
                <p className="mb-1 font-medium text-foreground">按竞品证据分布</p>
                {Object.entries(metricsQuery.data!.evidence_count_by_competitor).map(([competitorId, count]) => (
                  <p key={competitorId}>
                    {competitorId}: {count}
                  </p>
                ))}
              </div>
              <div className="rounded-md border border-border p-3">
                <p className="mb-1 font-medium text-foreground">按 source_type 分布</p>
                <p>{formatDistribution(metricsQuery.data!.source_type_distribution)}</p>
              </div>
              <div className="rounded-md border border-border p-3">
                <p className="mb-1 font-medium text-foreground">按 source_authority 分布</p>
                <p>{formatDistribution(metricsQuery.data!.source_authority_distribution)}</p>
              </div>
              <div className="rounded-md border border-border p-3">
                <p className="mb-1 font-medium text-foreground">按地域/语言分布</p>
                <p>{formatDistribution(metricsQuery.data!.locale_distribution)}</p>
              </div>
            </div>

            <p className="text-xs text-muted-foreground">
              * 人工修正率是代理指标，基于与本 run 相关的 skill candidate 审核状态计算。
            </p>
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
