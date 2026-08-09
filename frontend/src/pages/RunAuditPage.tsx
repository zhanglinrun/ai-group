import { BarChart3, FileText, GitBranch, ShieldCheck } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";

import {
  useRunComparisons,
  useRunConclusions,
  useRunDetail,
  useRunKnowledge,
  useRunMetrics,
  useRunTrace,
} from "@/api/hooks";
import { useRunEvents } from "@/api/sse";
import { BattlecardGrid } from "@/components/battlecard";
import { ComparisonMatrix } from "@/components/comparison/ComparisonMatrix";
import { EvidenceDrawer } from "@/components/EvidenceDrawer";
import { KnowledgePanel } from "@/components/knowledge/KnowledgePanel";
import { MetricsPanel } from "@/components/MetricsPanel";
import { QaClosureSection } from "@/components/audit/QaClosureSection";
import { RunTraceDag } from "@/components/dag/RunTraceDag";
import { RunBreadcrumb } from "@/components/RunBreadcrumb";
import { StatusBadge } from "@/components/StatusBadge";
import { LlmCallsTable } from "@/components/trace/LlmCallsTable";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDateTime, formatDuration, formatRunTitle } from "@/lib/format";

function formatPercent(value: number | undefined): string {
  return value === undefined ? "-" : `${(value * 100).toFixed(1)}%`;
}

function formatDistribution(distribution: Record<string, number> | undefined): string {
  const entries = Object.entries(distribution ?? {});
  if (entries.length === 0) {
    return "-";
  }
  return entries.map(([key, count]) => `${key}: ${count}`).join(" · ");
}

function AuditKpiCard({
  label,
  value,
  hint,
}: {
  label: string;
  value: string;
  hint: string;
}): JSX.Element {
  return (
    <div className="rounded-lg border border-white/[0.06] bg-surface px-4 py-3">
      <p className="text-micro text-foreground-subtle">{label}</p>
      <p className="mt-0.5 text-h3 font-semibold text-foreground">{value}</p>
      <p className="mt-1 text-micro text-foreground-subtle">{hint}</p>
    </div>
  );
}

export function RunAuditPage(): JSX.Element {
  const { runId: runIdFromParams } = useParams<{ runId: string }>();
  const runId = runIdFromParams ?? "";
  const [isEvidenceDrawerOpen, setIsEvidenceDrawerOpen] = useState(false);
  const [activeEvidenceIds, setActiveEvidenceIds] = useState<string[]>([]);
  useRunEvents(runId);

  const detailQuery = useRunDetail(runId);
  const traceQuery = useRunTrace(runId);
  const metricsQuery = useRunMetrics(runId, { enabled: Boolean(runId) });
  const conclusionsQuery = useRunConclusions(runId, { enabled: Boolean(runId) });
  const comparisonsQuery = useRunComparisons(runId, { enabled: Boolean(runId) });
  const knowledgeQuery = useRunKnowledge(runId, { enabled: Boolean(runId) });

  const detail = detailQuery.data;
  const metrics = metricsQuery.data;
  const trace = traceQuery.data;
  const conclusions = conclusionsQuery.data?.items ?? [];
  const comparisons = comparisonsQuery.data?.items ?? [];
  const isRunActive = detail?.status === "running";

  function openEvidenceDrawer(evidenceIds: string[]): void {
    if (evidenceIds.length === 0) {
      return;
    }
    setActiveEvidenceIds(evidenceIds);
    setIsEvidenceDrawerOpen(true);
  }

  return (
    <section className="space-y-6">
      <header className="space-y-3">
        <RunBreadcrumb run={detail} current="运行诊断" />
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <p className="inline-flex items-center gap-2 text-xs text-primary">
              <ShieldCheck className="h-3.5 w-3.5" />
              运行诊断 · 内部可观测
            </p>
            <h1 className="mt-1 text-h1 text-foreground" title={detail?.user_query ?? undefined}>
              {detail ? formatRunTitle(detail, { max: 64 }) : "加载中..."}
            </h1>
            <p className="mt-1 text-micro text-foreground-subtle">
              run_id: {runId}
              {detail ? ` · ${formatDateTime(detail.started_at)}` : ""}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {detail ? <StatusBadge status={detail.status} /> : null}
            <Button asChild size="sm" variant="outline">
              <Link to={`/app/runs/${runId}`}>返回报告</Link>
            </Button>
          </div>
        </div>
      </header>

      {detailQuery.isLoading || traceQuery.isLoading ? (
        <div className="space-y-3">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-72 w-full" />
        </div>
      ) : null}

      {detailQuery.isError || traceQuery.isError ? (
        <Card className="border-danger/30 bg-danger/5">
          <CardContent className="pt-6 text-sm text-danger">
            诊断数据读取失败：
            {detailQuery.error?.message ?? traceQuery.error?.message ?? "unknown error"}
          </CardContent>
        </Card>
      ) : null}

      {detail && trace ? (
        <>
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="flex items-center gap-2 text-base">
                <BarChart3 className="h-4 w-4 text-primary" />
                Run 概览
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
                <AuditKpiCard
                  hint="竞品维度覆盖"
                  label="覆盖率"
                  value={formatPercent(metrics?.coverage_rate)}
                />
                <AuditKpiCard
                  hint="evidence 行数"
                  label="证据数"
                  value={metrics?.evidence_count_total.toLocaleString() ?? "-"}
                />
                <AuditKpiCard
                  hint="LLM 调用行数"
                  label="LLM 调用"
                  value={metrics?.llm_call_count.toLocaleString() ?? "-"}
                />
                <AuditKpiCard
                  hint="finished_at - started_at"
                  label="耗时"
                  value={formatDuration(detail.started_at, detail.finished_at)}
                />
              </div>
            </CardContent>
          </Card>

          <MetricsPanel isRunActive={isRunActive} runId={runId} />

          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="flex items-center gap-2 text-base">
                <GitBranch className="h-4 w-4 text-primary" />
                多 Agent DAG
              </CardTitle>
            </CardHeader>
            <CardContent>
              <RunTraceDag trace={trace} />
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <CardTitle className="text-base">LLM 调用与可观测性</CardTitle>
                <Badge variant="secondary">{trace.llm_calls.length} calls</Badge>
              </div>
            </CardHeader>
            <CardContent>
              <LlmCallsTable calls={trace.llm_calls} steps={trace.steps} />
            </CardContent>
          </Card>

          <QaClosureSection trace={trace} />

          <Card>
            <CardHeader className="pb-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <CardTitle className="flex items-center gap-2 text-base">
                  <FileText className="h-4 w-4 text-primary" />
                  溯源证据与合规
                </CardTitle>
                <Button asChild size="sm" variant="outline">
                  <Link to={`/app/runs/${runId}/evidence`}>打开证据库</Link>
                </Button>
              </div>
            </CardHeader>
            <CardContent className="grid gap-3 md:grid-cols-3">
              <div className="rounded-md border border-border p-3">
                <p className="text-xs font-medium text-foreground">source_type 分布</p>
                <p className="mt-2 text-xs text-muted-foreground">
                  {formatDistribution(metrics?.source_type_distribution)}
                </p>
              </div>
              <div className="rounded-md border border-border p-3">
                <p className="text-xs font-medium text-foreground">source_authority 分布</p>
                <p className="mt-2 text-xs text-muted-foreground">
                  {formatDistribution(metrics?.source_authority_distribution)}
                </p>
              </div>
              <div className="rounded-md border border-border p-3">
                <p className="text-xs font-medium text-foreground">脱敏覆盖率</p>
                <p className="mt-2 text-lg font-semibold text-foreground">
                  {formatPercent(metrics?.desensitization_coverage)}
                </p>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">业务产物</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {conclusionsQuery.isLoading || comparisonsQuery.isLoading ? (
                <Skeleton className="h-40 w-full" />
              ) : null}
              <BattlecardGrid runId={runId} conclusions={conclusions} />
              <ComparisonMatrix comparisons={comparisons} onEvidenceClick={openEvidenceDrawer} />
              <KnowledgePanel
                compact
                errorMessage={knowledgeQuery.error?.message ?? null}
                isLoading={knowledgeQuery.isLoading}
                knowledge={knowledgeQuery.data ?? null}
                onEvidenceClick={openEvidenceDrawer}
              />
            </CardContent>
          </Card>
        </>
      ) : null}

      <EvidenceDrawer
        evidenceIds={activeEvidenceIds}
        onOpenChange={setIsEvidenceDrawerOpen}
        open={isEvidenceDrawerOpen}
        runId={runId}
      />
    </section>
  );
}
