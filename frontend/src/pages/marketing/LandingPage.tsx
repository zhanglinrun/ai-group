import { ArrowRight, FileSearch, Layers, Share2 } from "lucide-react";
import { useEffect } from "react";
import { Link } from "react-router-dom";

import { useRunsList } from "@/api/hooks";
import { StatusBadge } from "@/components/StatusBadge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDateTime, formatRunTitle } from "@/lib/format";

export function LandingPage(): JSX.Element {
  // The marketing page is public, while /api/runs is scoped to the signed-in
  // user's workspace. Do not turn the expected guest 401 into a visible
  // "network error" on the landing page.
  const completedRunsQuery = useRunsList(
    { status: "completed", limit: 3, offset: 0 },
    { enabled: false },
  );
  const previewRun = completedRunsQuery.data?.items[0] ?? null;

  useEffect(() => {
    document.title = "熊博士 — AI 深度调研与拼团商城";
  }, []);

  return (
    <div className="space-y-20 pb-16">
      {/* Hero */}
      <section className="relative pt-16 text-center">
        <div className="absolute inset-0 -z-10 overflow-hidden">
          <div className="absolute left-1/2 top-0 h-[500px] w-[800px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-primary/[0.06] blur-[120px]" />
        </div>
        <p className="mb-4 inline-flex items-center gap-2 rounded-full bg-primary/10 px-3 py-1 text-micro font-medium text-primary ring-1 ring-inset ring-primary/20">
          拼团购买积分 · 按 Token 透明计费
        </p>
        <h1 className="mx-auto max-w-3xl font-display text-display text-foreground">
          让熊博士帮你完成
          <br />
          <span className="text-primary">可溯源的深度调研</span>
        </h1>
        <p className="mx-auto mt-5 max-w-2xl text-body text-foreground-muted">
          竞品对比、赛道摸底、领域论文综述都可以。通过拼团获得更划算的 AI 积分，多智能体并行检索公开资料并写作；每次运行都展示 Token 消耗、积分冻结和最终结算。
        </p>
        <div className="mt-8 flex items-center justify-center gap-3">
          <Button asChild size="lg">
            <Link to="/app/runs/new">
              开始调研
              <ArrowRight className="h-4 w-4" />
            </Link>
          </Button>
          <Button asChild size="lg" variant="secondary">
            <Link to="/app">进入工作区</Link>
          </Button>
        </div>
      </section>

      {/* Product preview */}
      <section className="relative mx-auto max-w-4xl">
        <div className="rounded-xl border border-white/[0.06] bg-surface p-6 shadow-raised">
          <div className="mb-4 flex items-center gap-3">
            <div className="h-2 w-2 rounded-full bg-success" />
            <span className="text-caption text-foreground-muted">最近完成的深度调研</span>
          </div>
          {completedRunsQuery.isLoading ? (
            <Skeleton className="h-28 w-full" />
          ) : null}
          {completedRunsQuery.isError ? (
            <div className="rounded-lg border border-danger/30 bg-danger/5 p-4 text-caption text-danger">
              {completedRunsQuery.error.message}
            </div>
          ) : null}
          {!completedRunsQuery.isLoading && !completedRunsQuery.isError ? (
            previewRun ? (
              <Link
                className="block rounded-lg border border-white/[0.06] bg-page p-5 transition-colors hover:border-white/[0.12]"
                to={`/share/${previewRun.run_id}`}
              >
                <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
                  <div className="min-w-0">
                    <p
                      className="line-clamp-2 text-h3 font-semibold text-foreground"
                      title={previewRun.user_query}
                    >
                      {formatRunTitle(previewRun)}
                    </p>
                    <p className="mt-2 text-caption text-foreground-muted">
                      {previewRun.domain_hint ?? "通用场景"}
                    </p>
                  </div>
                  <StatusBadge status={previewRun.status} />
                </div>
                <div className="mt-5 grid gap-3 sm:grid-cols-3">
                  <PreviewMetric label="证据" value={`${previewRun.evidence_count}`} />
                  <PreviewMetric label="步骤" value={`${previewRun.step_count}`} />
                  <PreviewMetric
                    label="完成时间"
                    value={
                      previewRun.finished_at
                        ? formatDateTime(previewRun.finished_at)
                        : "-"
                    }
                  />
                </div>
              </Link>
            ) : (
              <div className="rounded-lg border border-dashed border-white/[0.08] bg-page p-5">
                <p className="text-caption font-medium text-foreground">暂无真实报告预览</p>
                <p className="mt-2 text-caption text-foreground-muted">
                  完成一次深度调研后，这里会展示最新报告的标题、证据量和执行步骤。
                </p>
              </div>
            )
          ) : null}
        </div>
        <div className="absolute -inset-px -z-10 rounded-xl bg-gradient-to-b from-primary/20 to-transparent opacity-40 blur-xl" />
      </section>

      {/* Value props */}
      <section className="grid gap-6 md:grid-cols-3">
        <div className="space-y-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 ring-1 ring-inset ring-primary/20">
            <FileSearch className="h-5 w-5 text-primary" />
          </div>
          <h3 className="text-h3 text-foreground">自动调研</h3>
          <p className="text-caption text-foreground-muted">
            可以抓取竞品官网、定价与用户反馈，也可以检索论文、技术报告和公开资料，形成可追踪证据链。每条结论都可溯源到原文。
          </p>
        </div>
        <div className="space-y-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent/10 ring-1 ring-inset ring-accent/20">
            <Layers className="h-5 w-5 text-accent" />
          </div>
          <h3 className="text-h3 text-foreground">结构化结论</h3>
          <p className="text-caption text-foreground-muted">
            按主题整理：竞品对比会输出 Battlecard，领域调研会梳理方法演进与关键结论，并标注置信度。
          </p>
        </div>
        <div className="space-y-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-success/10 ring-1 ring-inset ring-success/20">
            <Share2 className="h-5 w-5 text-success" />
          </div>
          <h3 className="text-h3 text-foreground">一键分享</h3>
          <p className="text-caption text-foreground-muted">
            导出 Markdown 或生成公开链接，让团队快速对齐决策结论，无需重复沟通。
          </p>
        </div>
      </section>

      {/* Showcase wall */}
      <section className="space-y-4">
        <div className="flex items-end justify-between">
          <h2 className="text-h2 text-foreground">最近完成的调研</h2>
          <Link className="text-caption text-primary hover:underline" to="/examples">
            查看全部
          </Link>
        </div>

        {completedRunsQuery.isLoading && (
          <div className="grid gap-4 md:grid-cols-3">
            <Skeleton className="h-28 w-full" />
            <Skeleton className="h-28 w-full" />
            <Skeleton className="h-28 w-full" />
          </div>
        )}

        {completedRunsQuery.isError && (
          <Card>
            <CardContent className="py-6 text-caption text-danger">
              {completedRunsQuery.error.message}
            </CardContent>
          </Card>
        )}

        <div className="grid gap-4 md:grid-cols-3">
          {(completedRunsQuery.data?.items ?? []).map((run) => (
            <Link key={run.run_id} to={`/share/${run.run_id}`}>
              <Card className="h-full transition-colors hover:border-white/[0.12]">
                <CardContent className="space-y-2 p-5">
                  <p
                    className="line-clamp-2 text-caption font-medium text-foreground"
                    title={run.user_query}
                  >
                    {formatRunTitle(run)}
                  </p>
                  <div className="flex items-center gap-2">
                    <StatusBadge status={run.status} />
                  </div>
                  <p className="text-micro text-foreground-subtle">
                    {run.finished_at ? formatDateTime(run.finished_at) : "处理中"}
                  </p>
                </CardContent>
              </Card>
            </Link>
          ))}
        </div>
      </section>

      {/* Footer */}
      <footer className="border-t border-white/[0.04] pt-6 text-micro text-foreground-subtle">
        <div className="flex items-center justify-between">
          <p>数据来源以公开信息为主，每条结论可追溯到证据原文。</p>
          <div className="flex gap-4">
            <Link className="hover:text-foreground" to="/examples">案例</Link>
            <Link className="hover:text-foreground" to="/pricing">定价</Link>
            <Link className="hover:text-foreground" to="/app">工作区</Link>
          </div>
        </div>
      </footer>
    </div>
  );
}

function PreviewMetric({ label, value }: { label: string; value: string }): JSX.Element {
  return (
    <div className="rounded-md border border-white/[0.06] bg-white/[0.02] px-3 py-2">
      <p className="text-micro text-foreground-subtle">{label}</p>
      <p className="mt-1 truncate text-caption font-medium text-foreground" title={value}>
        {value}
      </p>
    </div>
  );
}
