import {
  Activity,
  CircleSlash,
  Download,
  FileDown,
  RotateCcw,
  Share2,
  ShieldCheck,
  XCircle,
} from "lucide-react";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import {
  useCreateWatchlistItem,
  useRunComparisons,
  useRunDetail,
  useRunDiff,
  useRunKnowledge,
  useRunMetrics,
  useRunReport,
  useRunTrace,
} from "@/api/hooks";
import { useRunEvents } from "@/api/sse";
import { ComparisonMatrix } from "@/components/comparison/ComparisonMatrix";
import { CompetitorDiffCard } from "@/components/comparison/CompetitorDiffCard";
import { RunTraceDag } from "@/components/dag/RunTraceDag";
import { EvidenceDrawer } from "@/components/EvidenceDrawer";
import { KnowledgePanel } from "@/components/knowledge/KnowledgePanel";
import { ReportArticle } from "@/components/report/ReportArticle";
import { RunBreadcrumb } from "@/components/RunBreadcrumb";
import { StatusBadge } from "@/components/StatusBadge";
import { StatusReasonBanner } from "@/components/StatusReasonBanner";
import { LlmCallsTable } from "@/components/trace/LlmCallsTable";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { pushToast } from "@/components/ui/toaster";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { buildEvidenceLinkFromToolArgs } from "@/lib/evidenceLinks";
import { formatDateTime, formatDuration, formatRunTitle } from "@/lib/format";
import { SHOW_DEBUG_PANELS } from "@/lib/debugFlags";
import { runPhaseRoute } from "@/lib/runRoute";
import { track } from "@/lib/analytics";
import { cn } from "@/lib/utils";

type RunViewTab = "knowledge" | "report" | "trace";

function isRunViewTab(value: string): value is RunViewTab {
  return value === "knowledge" || value === "report" || value === "trace";
}

export function RunViewPage(): JSX.Element {
  const { runId: runIdFromParams } = useParams<{ runId: string }>();
  const navigate = useNavigate();
  const runId = runIdFromParams ?? "";
  const [isEvidenceDrawerOpen, setIsEvidenceDrawerOpen] = useState(false);
  const [activeEvidenceIds, setActiveEvidenceIds] = useState<string[]>([]);
  const [activeTab, setActiveTab] = useState<RunViewTab>("report");
  const [activeDimensions, setActiveDimensions] = useState<Set<string> | null>(null);
  const createWatchlistMutation = useCreateWatchlistItem();

  const detailQuery = useRunDetail(runId, { events: false });
  const traceQuery = useRunTrace(runId, {
    events: false,
    enabled: Boolean(runId) && detailQuery.isSuccess,
  });
  // Only subscribe after the ownership/detail request succeeds.  A direct
  // link to another user's run should render a normal error card, not keep
  // reconnecting an SSE request that can never be authorized.
  useRunEvents(detailQuery.data && !detailQuery.isError ? runId : "");

  const runStatus = detailQuery.data?.status ?? "running";
  const isRunActive = runStatus === "running";
  const isReportReady = runStatus === "completed" || runStatus === "degraded";
  // failed/cancelled are *terminal-without-output* — KPI cards and Tabs are
  // dead weight (they all collapse to "-" or "生成中" placeholders).
  // We collapse them into a single outcome card with the actions that matter.
  const isTerminalFailure = runStatus === "failed" || runStatus === "cancelled";
  const reportQuery = useRunReport(runId, { enabled: isReportReady });
  const comparisonsQuery = useRunComparisons(runId, {
    enabled: isReportReady,
    refetchInterval: isRunActive ? 2_000 : false,
  });
  const knowledgeQuery = useRunKnowledge(runId, {
    enabled: isReportReady,
    refetchInterval: isRunActive ? 2_000 : false,
  });
  const metricsQuery = useRunMetrics(runId, {
    enabled: isReportReady,
    refetchInterval: isRunActive ? 2_000 : false,
  });
  const diffQuery = useRunDiff(isReportReady ? runId : null);

  const detailErrorMessage = detailQuery.error?.message === "run not found"
    ? "报告不存在，或当前账号无权访问。"
    : detailQuery.error?.message;

  const reportMarkdown = reportQuery.data?.content_markdown ?? "";
  const comparisons = comparisonsQuery.data?.items ?? [];
  const diffs = diffQuery.data ?? [];
  const filteredComparisons =
    activeDimensions === null
      ? comparisons
      : comparisons.filter((c) => activeDimensions.has(c.dimension));
  const activeRunRoute = detailQuery.data ? runPhaseRoute(detailQuery.data) : null;
  function openEvidenceDrawer(evidenceIds: string[]): void {
    if (evidenceIds.length === 0) return;
    setActiveEvidenceIds(evidenceIds);
    setIsEvidenceDrawerOpen(true);
  }

  function toggleDimension(dimension: string): void {
    setActiveDimensions((prev) => {
      const allDimensions = new Set(comparisons.map((c) => c.dimension));
      const current = prev ?? allDimensions;
      const next = new Set(current);
      if (next.has(dimension)) {
        next.delete(dimension);
      } else {
        next.add(dimension);
      }
      if (next.size === allDimensions.size) return null;
      return next;
    });
  }

  function handleTabChange(value: string): void {
    if (isRunViewTab(value)) {
      setActiveTab(value);
    }
  }

  function navigateToFocusedRun(seedCompetitorIds: string[]): void {
    const params = new URLSearchParams();
    params.set("from", runId);
    if (seedCompetitorIds.length > 0) {
      params.set("seed", seedCompetitorIds.join(","));
    }
    navigate(`/app/runs/new?${params.toString()}`);
  }

  async function handleAddWatchlist(competitorId: string, sourceRole?: string): Promise<void> {
    try {
      await createWatchlistMutation.mutateAsync({
        competitor_id: competitorId,
        added_from_run_id: runId,
        source_role: sourceRole ?? null,
      });
      pushToast({
        title: `已加入追踪：${competitorId}`,
        variant: "success",
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : "未知错误";
      if (message.includes("WATCHLIST_ALREADY_EXISTS")) {
        pushToast({ title: `${competitorId} 已在追踪列表`, variant: "default" });
        return;
      }
      pushToast({ title: "加入追踪失败", description: message, variant: "danger" });
    }
  }

  useEffect(() => {
    setActiveTab("report");
    setActiveDimensions(null);
  }, [runId]);

  function handleExportMarkdown(): void {
    if (!reportMarkdown) {
      pushToast({ title: "暂无报告内容", variant: "warning" });
      return;
    }
    const blob = new Blob([reportMarkdown], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `xiongdoctor_report_${runId}.md`;
    a.click();
    URL.revokeObjectURL(url);
    track("run_view.export_markdown", { run_id: runId });
  }

  function handleExportPdf(): void {
    const sharedUrl = `${window.location.origin}/share/${runId}?print=1`;
    window.open(sharedUrl, "_blank", "noopener,noreferrer");
    track("run_view.export_pdf", { run_id: runId });
  }

  async function handleCopyShareLink(): Promise<void> {
    const sharedUrl = `${window.location.origin}/share/${runId}`;
    try {
      await navigator.clipboard.writeText(sharedUrl);
      pushToast({ title: "分享链接已复制", description: sharedUrl, variant: "success" });
      track("run_view.copy_share_link", { run_id: runId });
    } catch {
      pushToast({ title: "复制失败", variant: "danger" });
    }
  }

  return (
    <section className="space-y-6">
      {/* Header */}
      <header className="space-y-2">
        <RunBreadcrumb run={detailQuery.data} />
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0 flex-1">
            <h1
              className="text-h1 text-foreground"
              title={detailQuery.data?.user_query ?? undefined}
            >
              {detailQuery.isError
                ? "报告不可用"
                : detailQuery.data
                  ? formatRunTitle(detailQuery.data, { max: 60 })
                  : "加载中..."}
            </h1>
            <p className="mt-1 text-micro text-foreground-subtle">
              {detailQuery.data ? formatDateTime(detailQuery.data.started_at) : ""} · {runId}
            </p>
            {detailQuery.data?.user_query ? (
              <p
                className="mt-2 line-clamp-2 max-w-3xl text-caption text-foreground-subtle"
                title={detailQuery.data.user_query}
              >
                {detailQuery.data.user_query}
              </p>
            ) : null}
          </div>
          <StatusBadge
            status={detailQuery.isError ? "failed" : runStatus}
            reason={detailQuery.data?.status_reason}
          />
        </div>
        {detailQuery.data && !isTerminalFailure ? (
          <StatusReasonBanner status={runStatus} reason={detailQuery.data.status_reason} />
        ) : null}
      </header>

      {detailQuery.isLoading && (
        <div className="space-y-3">
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-40 w-full" />
        </div>
      )}

      {detailQuery.isError && (
        <div className="rounded-lg border border-danger/30 bg-danger/5 p-4 text-caption text-danger">
          {detailErrorMessage ?? "报告数据加载失败，请稍后重试。"}
        </div>
      )}

      {detailQuery.data && isTerminalFailure && (
        <RunOutcomeCard
          runId={runId}
          status={runStatus as "failed" | "cancelled"}
          reason={detailQuery.data.status_reason}
          startedAt={detailQuery.data.started_at}
          finishedAt={detailQuery.data.finished_at}
          onReanalyze={() => navigateToFocusedRun([])}
        />
      )}

      {detailQuery.data && !isTerminalFailure && (
        <>
          {/* Running hint */}
          {isRunActive && activeRunRoute !== null && activeRunRoute !== `/app/runs/${runId}` ? (
            <div className="flex flex-col gap-3 rounded-lg border border-primary/25 bg-primary/[0.06] p-4 text-caption text-foreground-muted sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-center gap-2">
                <Activity className="h-4 w-4 text-primary" />
                <span>调研仍在进行中，建议查看实时进度，避免在报告生成前看到空结果。</span>
              </div>
              <Button asChild size="sm" variant="secondary">
                <Link to={activeRunRoute}>前往实时进度</Link>
              </Button>
            </div>
          ) : null}

          {/* KPI bar */}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <KpiCard label="覆盖率" value={metricsQuery.data ? `${(metricsQuery.data.coverage_rate * 100).toFixed(0)}%` : "-"} />
            <KpiCard label="QA 通过" value={metricsQuery.data ? `${((1 - metricsQuery.data.qa_rejection_rate) * 100).toFixed(0)}%` : "-"} />
            <KpiCard label="证据数" value={metricsQuery.data?.evidence_count_total.toLocaleString() ?? "-"} />
            <KpiCard
              label="耗时"
              value={
                detailQuery.data.finished_at
                  ? formatDuration(detailQuery.data.started_at, detailQuery.data.finished_at)
                  : "进行中"
              }
            />
          </div>

          {/* Tabs */}
          <Tabs value={activeTab} onValueChange={handleTabChange}>
            <div className="flex items-center justify-between gap-3">
              <TabsList>
                <TabsTrigger value="report">完整报告</TabsTrigger>
                <TabsTrigger value="knowledge">竞品知识</TabsTrigger>
                <TabsTrigger value="trace">执行回放</TabsTrigger>
              </TabsList>
              {/* Toolbar */}
              <div className="flex items-center gap-1.5">
                <Button size="sm" variant="ghost" onClick={() => void handleCopyShareLink()} aria-label="复制分享链接">
                  <Share2 className="h-3.5 w-3.5" />
                </Button>
                <Button size="sm" variant="ghost" onClick={handleExportMarkdown} aria-label="导出 Markdown">
                  <Download className="h-3.5 w-3.5" />
                </Button>
                <Button size="sm" variant="ghost" onClick={handleExportPdf} aria-label="导出 PDF">
                  <FileDown className="h-3.5 w-3.5" />
                </Button>
                <Button size="sm" variant="ghost" onClick={() => navigateToFocusedRun([])} aria-label="再调研一版">
                  <RotateCcw className="h-3.5 w-3.5" />
                </Button>
                {SHOW_DEBUG_PANELS && isReportReady ? (
                  <Button size="sm" variant="ghost" onClick={() => navigate(`/app/runs/${runId}/audit`)} aria-label="运行诊断">
                    <ShieldCheck className="h-3.5 w-3.5" />
                  </Button>
                ) : null}
              </div>
            </div>

            {/* Knowledge tab */}
            <TabsContent value="knowledge" className="space-y-4">
              {!isReportReady && (
                <div className="flex items-center gap-2 rounded-lg border border-white/[0.06] bg-surface p-4 text-caption text-foreground-muted">
                  <Activity className="h-4 w-4 text-primary" />
                  报告生成后将展示结构化功能树、定价模型和用户画像。
                </div>
              )}
              {isReportReady ? (
                <>
                  <KnowledgePanel
                    errorMessage={knowledgeQuery.error?.message ?? null}
                    isLoading={knowledgeQuery.isLoading}
                    knowledge={knowledgeQuery.data ?? null}
                    onEvidenceClick={openEvidenceDrawer}
                    onFocusCompetitor={(competitorId) => navigateToFocusedRun([competitorId])}
                    onAddWatchlist={(competitorId, sourceRole) =>
                      void handleAddWatchlist(competitorId, sourceRole)
                    }
                  />
                </>
              ) : null}
            </TabsContent>

            {/* Full report tab */}
            <TabsContent value="report" className="space-y-4">
              {!isReportReady && (
                <div className="flex items-center gap-2 rounded-lg border border-white/[0.06] bg-surface p-4 text-caption text-foreground-muted">
                  <Activity className="h-4 w-4 text-primary" />
                  报告仍在生成中。
                </div>
              )}
              {reportQuery.isLoading && <Skeleton className="h-60 w-full" />}
              {reportQuery.isError && (
                <p className="text-caption text-danger">报告读取失败：{reportQuery.error.message}</p>
              )}
              {isReportReady && !reportQuery.isLoading && !reportQuery.isError && (
                <>
                  <ReportArticle
                    markdown={reportMarkdown}
                    onEvidenceClick={openEvidenceDrawer}
                  />
                  {diffs.length > 0 ? (
                    <CompetitorDiffCard diffs={diffs} />
                  ) : null}
                  {comparisons.length > 0 ? (
                    <div className="flex flex-wrap gap-1.5">
                      {comparisons.map((c) => {
                        const isActive = activeDimensions === null || activeDimensions.has(c.dimension);
                        return (
                          <button
                            key={c.dimension}
                            onClick={() => toggleDimension(c.dimension)}
                            className={cn(
                              "rounded-full border px-2.5 py-0.5 text-micro transition-colors",
                              isActive
                                ? "border-primary/40 bg-primary/10 text-primary"
                                : "border-white/[0.08] bg-transparent text-foreground-subtle hover:border-white/20",
                            )}
                          >
                            {c.dimension.replace(/_/g, " ")}
                          </button>
                        );
                      })}
                      {activeDimensions !== null ? (
                        <button
                          onClick={() => setActiveDimensions(null)}
                          className="rounded-full border border-white/[0.08] px-2.5 py-0.5 text-micro text-foreground-subtle hover:border-white/20"
                        >
                          重置
                        </button>
                      ) : null}
                    </div>
                  ) : null}
                  <ComparisonMatrix comparisons={filteredComparisons} onEvidenceClick={openEvidenceDrawer} />
                </>
              )}
            </TabsContent>

            {/* Trace tab */}
            <TabsContent value="trace" className="space-y-4">
              {traceQuery.isLoading ? (
                <div className="space-y-3">
                  <Skeleton className="h-20 w-full" />
                  <Skeleton className="h-60 w-full" />
                </div>
              ) : null}

              {traceQuery.isError ? (
                <div className="rounded-lg border border-danger/30 bg-danger/5 p-4 text-caption text-danger">
                  执行回放读取失败：{traceQuery.error.message}
                </div>
              ) : null}

              {traceQuery.data ? (
                <div className="rounded-lg border border-white/[0.06] bg-surface p-4">
                  <Tabs defaultValue="dag">
                    <TabsList>
                      <TabsTrigger value="dag">DAG</TabsTrigger>
                      <TabsTrigger value="steps">执行步骤</TabsTrigger>
                      <TabsTrigger value="decisions">调度决策</TabsTrigger>
                      <TabsTrigger value="llm">LLM 调用</TabsTrigger>
                    </TabsList>

                    <TabsContent value="dag" className="mt-3">
                      <RunTraceDag trace={traceQuery.data} />
                    </TabsContent>

                    <TabsContent value="steps" className="mt-3 space-y-2">
                      {traceQuery.data.steps.length === 0 ? (
                        <p className="text-caption text-foreground-muted">暂无执行步骤。</p>
                      ) : (
                        traceQuery.data.steps.map((step) => (
                          <div key={step.step_id} className="rounded-lg border border-white/[0.06] bg-background/40 p-3">
                            <p className="text-caption font-medium text-foreground">
                              {step.agent_name} · {step.status}
                            </p>
                            <p className="mt-1 text-micro text-foreground-subtle">
                              {formatDateTime(step.created_at)} · {step.step_id}
                            </p>
                          </div>
                        ))
                      )}
                    </TabsContent>

                    <TabsContent value="decisions" className="mt-3 space-y-2">
                      {traceQuery.data.supervisor_decisions.length === 0 ? (
                        <p className="text-caption text-foreground-muted">暂无调度决策记录。</p>
                      ) : (
                        traceQuery.data.supervisor_decisions.map((decision) => {
                          const evidenceLink = buildEvidenceLinkFromToolArgs(runId, decision.tool_args);
                          return (
                            <div key={decision.id} className="rounded-lg border border-white/[0.06] bg-background/40 p-3">
                              <p className="text-caption font-medium text-foreground">
                                iter {decision.iteration} · {decision.chosen_tool}
                              </p>
                              <p className="mt-1 text-micro text-foreground-subtle">
                                {formatDateTime(decision.created_at)}
                              </p>
                              <p className="mt-2 text-caption text-foreground-muted">
                                {decision.reasoning_summary}
                              </p>
                              {evidenceLink !== null ? (
                                <Button asChild className="mt-2" size="sm" variant="outline">
                                  <Link to={evidenceLink}>查看相关证据</Link>
                                </Button>
                              ) : null}
                            </div>
                          );
                        })
                      )}
                    </TabsContent>

                    <TabsContent value="llm" className="mt-3">
                      <LlmCallsTable
                        calls={traceQuery.data.llm_calls}
                        steps={traceQuery.data.steps}
                      />
                    </TabsContent>
                  </Tabs>
                </div>
              ) : null}
            </TabsContent>
          </Tabs>
        </>
      )}

      <EvidenceDrawer
        evidenceIds={activeEvidenceIds}
        onOpenChange={setIsEvidenceDrawerOpen}
        open={isEvidenceDrawerOpen}
        runId={runId}
      />
    </section>
  );
}

function KpiCard({ label, value }: { label: string; value: string }): JSX.Element {
  return (
    <div className="rounded-lg border border-white/[0.06] bg-surface px-4 py-3">
      <p className="text-micro text-foreground-subtle">{label}</p>
      <p className="mt-0.5 text-h3 font-semibold text-foreground">{value}</p>
    </div>
  );
}

interface RunOutcomeCardProps {
  runId: string;
  status: "failed" | "cancelled";
  reason?: string | null;
  startedAt: string;
  finishedAt: string | null;
  onReanalyze: () => void;
}

/**
 * Replaces the KPI + Tabs region when the run ended without producing a
 * report. Pattern follows Vercel's "Deployment failed" page and GitHub
 * Actions failed-run summary: large icon + status headline + brief
 * timeline + the two actions that actually matter (re-run, see logs).
 *
 * We deliberately avoid showing KPI placeholders ("-") or "报告生成中" hints
 * here — they're noise once the run is terminal-without-output.
 */
function RunOutcomeCard({
  runId,
  status,
  reason,
  startedAt,
  finishedAt,
  onReanalyze,
}: RunOutcomeCardProps): JSX.Element {
  const isFailed = status === "failed";
  const Icon = isFailed ? XCircle : CircleSlash;
  return (
    <div
      className={cn(
        "rounded-xl border p-6",
        isFailed
          ? "border-danger/25 bg-danger/[0.04]"
          : "border-white/[0.08] bg-white/[0.02]",
      )}
    >
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start">
        <div
          className={cn(
            "flex h-12 w-12 shrink-0 items-center justify-center rounded-full",
            isFailed
              ? "bg-danger/10 text-danger"
              : "bg-white/[0.06] text-foreground-muted",
          )}
        >
          <Icon className="h-6 w-6" />
        </div>
        <div className="min-w-0 flex-1 space-y-3">
          <div>
            <h2 className="text-h3 font-semibold text-foreground">
              {isFailed ? "调研未能完成" : "调研已停止"}
            </h2>
            <p className="mt-1 text-caption text-foreground-muted">
              {reason?.trim()
                ? reason
                : isFailed
                  ? "运行过程中发生错误，可在「执行回放」查看 Agent 最后操作以定位原因，或直接基于此重新发起一次。"
                  : "你在调研进行中点击了停止；可以基于同一需求重新发起一次。"}
            </p>
          </div>
          <dl className="grid grid-cols-3 gap-x-4 gap-y-1.5 border-t border-white/[0.04] pt-3 text-caption">
            <div className="space-y-0.5">
              <dt className="text-micro text-foreground-subtle">开始时间</dt>
              <dd className="font-medium text-foreground">{formatDateTime(startedAt)}</dd>
            </div>
            <div className="space-y-0.5">
              <dt className="text-micro text-foreground-subtle">结束时间</dt>
              <dd className="font-medium text-foreground">
                {finishedAt ? formatDateTime(finishedAt) : "-"}
              </dd>
            </div>
            <div className="space-y-0.5">
              <dt className="text-micro text-foreground-subtle">耗时</dt>
              <dd className="font-medium text-foreground">
                {formatDuration(startedAt, finishedAt)}
              </dd>
            </div>
          </dl>
          <div className="flex flex-wrap gap-2 pt-1">
            <Button onClick={onReanalyze} size="sm">
              <RotateCcw className="h-3.5 w-3.5" />
              基于此重新调研
            </Button>
            <Button asChild size="sm" variant="outline">
              <Link to={`/app/runs/${runId}/trace`}>查看执行回放</Link>
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
