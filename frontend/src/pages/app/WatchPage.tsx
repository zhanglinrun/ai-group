import { ArrowUpRight, CalendarClock, FileText, Plus, RefreshCw, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import {
  useCompetitorSeeds,
  useCreateWatchlistItem,
  useDeleteWatchlistItem,
  useManualRefreshWatchlist,
  usePatchWatchlistItem,
  useWatchlistDigest,
} from "@/api/hooks";
import type {
  CompetitorDiffResponse,
  WatchInsightItemResponse,
  WatchlistDigestItemResponse,
} from "@/api/types";
import { EvidenceDrawer } from "@/components/EvidenceDrawer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { pushToast } from "@/components/ui/toaster";
import { RefreshScheduleDialog } from "@/components/watchlist/RefreshScheduleDialog";
import { formatDateTime, formatRelativeTime } from "@/lib/format";

function toConfidenceVariant(confidence: string): "success" | "warning" | "danger" | "secondary" {
  const normalized = confidence.trim().toLowerCase();
  if (normalized === "high") return "success";
  if (normalized === "medium") return "warning";
  if (normalized === "low") return "danger";
  return "secondary";
}

function significanceLabel(significance: string): string {
  if (significance === "high") return "高影响";
  if (significance === "medium") return "中影响";
  if (significance === "low") return "低影响";
  return significance;
}

function refreshFrequencyLabel(hours: number | null): string {
  if (hours === null) return "手动";
  if (hours <= 24) return "每日";
  if (hours <= 24 * 7) return "每周";
  return "每两周";
}

function focusRunLink(latestRunId: string | null, competitorId: string): string {
  const params = new URLSearchParams();
  if (latestRunId) {
    params.set("from", latestRunId);
  }
  params.set("seed", competitorId);
  return `/app/runs/new?${params.toString()}`;
}

const CHANGE_TYPE_LABEL: Record<string, string> = {
  stance_changed: "阵营变化",
  new_dimension: "新增维度",
  lost_dimension: "丢失维度",
  summary_changed: "描述更新",
};

const SIGNIFICANCE_VARIANT: Record<string, "danger" | "warning" | "secondary"> = {
  high: "danger",
  medium: "warning",
  low: "secondary",
};

function RecentChangeItem({ diff }: { diff: CompetitorDiffResponse }): JSX.Element {
  return (
    <div className="rounded-lg border border-white/[0.06] bg-black/15 p-3">
      <div className="flex flex-wrap items-center gap-1.5 text-micro">
        <Badge variant="outline">{diff.dimension}</Badge>
        <Badge variant="secondary">{CHANGE_TYPE_LABEL[diff.change_type] ?? diff.change_type}</Badge>
        {diff.change_type === "stance_changed" && diff.old_value?.stance && diff.new_value?.stance ? (
          <span className="text-foreground-subtle">
            {diff.old_value.stance} → {diff.new_value.stance}
          </span>
        ) : null}
        <Badge variant={SIGNIFICANCE_VARIANT[diff.significance] ?? "secondary"}>
          {significanceLabel(diff.significance)}
        </Badge>
      </div>
      {diff.new_value?.summary ? (
        <p className="mt-2 line-clamp-2 text-caption text-foreground-muted">{diff.new_value.summary}</p>
      ) : null}
      <Link
        className="mt-2 inline-flex items-center gap-1 text-micro text-primary hover:underline"
        to={`/app/runs/${diff.run_id_new}`}
      >
        查看变化来源
        <ArrowUpRight className="h-3 w-3" />
      </Link>
    </div>
  );
}

function InsightItem({
  insight,
  onOpenEvidence,
}: {
  insight: WatchInsightItemResponse;
  onOpenEvidence: (runId: string, evidenceIds: string[]) => void;
}): JSX.Element {
  return (
    <li className="rounded-lg border border-white/[0.05] bg-black/15 p-3">
      <div className="flex flex-wrap items-center gap-1.5 text-micro">
        <Badge variant="outline" className="capitalize">
          {insight.section}
        </Badge>
        <Badge variant={toConfidenceVariant(insight.confidence)}>{insight.confidence}</Badge>
        <span className="text-foreground-subtle">{formatRelativeTime(insight.created_at)}</span>
      </div>
      <p className="mt-2 line-clamp-3 text-caption text-foreground-muted">{insight.claim}</p>
      <div className="mt-2 flex flex-wrap items-center gap-3 text-micro">
        <Link
          className="inline-flex items-center gap-1 text-primary hover:underline"
          to={`/app/runs/${insight.run_id}`}
        >
          来源：{insight.run_title}
          <ArrowUpRight className="h-3.5 w-3.5" />
        </Link>
        {insight.evidence_ids.length > 0 ? (
          <button
            className="inline-flex items-center gap-1 text-foreground-subtle hover:text-foreground"
            type="button"
            onClick={() => onOpenEvidence(insight.run_id, insight.evidence_ids)}
          >
            <FileText className="h-3.5 w-3.5" />
            {insight.evidence_ids.length} 条证据
          </button>
        ) : null}
      </div>
    </li>
  );
}

function StatPill({ label, value }: { label: string; value: string }): JSX.Element {
  return (
    <div className="rounded-full border border-white/[0.08] bg-white/[0.03] px-3 py-1">
      <span className="text-micro text-foreground-subtle">{label}</span>
      <span className="ml-1.5 text-micro font-medium text-foreground">{value}</span>
    </div>
  );
}

function WatchCard({
  item,
  isRefreshing,
  onRefresh,
  onDelete,
  onEditNote,
  onOpenEvidence,
}: {
  item: WatchlistDigestItemResponse;
  isRefreshing: boolean;
  onRefresh: (watchId: string) => void;
  onDelete: (watchId: string, competitorId: string) => void;
  onEditNote: (item: WatchlistDigestItemResponse) => void;
  onOpenEvidence: (runId: string, evidenceIds: string[]) => void;
}): JSX.Element {
  const profile = item.profile;
  const displayName = profile?.competitor_id ?? item.competitor_id;
  const introduction = profile?.introduction?.trim();
  const latestRunId = item.last_run_id ?? item.latest_run_id;

  return (
    <article className="overflow-hidden rounded-2xl border border-white/[0.07] bg-surface shadow-sm">
      <div className="border-b border-white/[0.05] bg-white/[0.02] px-5 py-4">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-lg font-semibold text-foreground">{displayName}</h2>
              {profile?.vendor ? <Badge variant="secondary">{profile.vendor}</Badge> : null}
              {profile?.segment ? <Badge variant="outline">{profile.segment}</Badge> : null}
              {profile?.role ? <Badge variant="outline">{profile.role}</Badge> : null}
            </div>
            <p className="mt-2 max-w-3xl text-caption leading-6 text-foreground-muted">
              {introduction || "暂无稳定竞品简介；下一次从报告加入追踪或完成刷新后会优先补齐产品档案。"}
            </p>
            <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-micro text-foreground-subtle">
              <span>追踪自 {formatDateTime(item.created_at)}</span>
              {item.added_from_run_id ? (
                <Link className="inline-flex items-center gap-1 text-primary hover:underline" to={`/app/runs/${item.added_from_run_id}`}>
                  来源 run
                  <ArrowUpRight className="h-3 w-3" />
                </Link>
              ) : null}
              {item.note ? <span>备注：{item.note}</span> : null}
            </div>
          </div>

          <div className="flex flex-wrap gap-2 lg:justify-end">
            <StatPill label="洞察" value={`${item.insight_count}`} />
            <StatPill label="分析" value={`${item.run_count}`} />
            <StatPill
              label="最近"
              value={item.last_updated_at ? formatRelativeTime(item.last_updated_at) : "-"}
            />
            <StatPill label="频率" value={refreshFrequencyLabel(item.refresh_interval_hours)} />
          </div>
        </div>
      </div>

      <div className="grid gap-4 p-5 xl:grid-cols-[minmax(0,0.95fr)_minmax(0,1.05fr)]">
        <section className="rounded-xl border border-white/[0.06] bg-black/10 p-4">
          <div className="mb-3 flex items-center justify-between gap-3">
            <div>
              <h3 className="text-caption font-semibold text-foreground">最新变化</h3>
              <p className="mt-0.5 text-micro text-foreground-subtle">结构化 diff，优先看高影响变化。</p>
            </div>
            {latestRunId ? (
              <Button asChild size="sm" variant="ghost">
                <Link to={`/app/runs/${latestRunId}`}>最近报告</Link>
              </Button>
            ) : null}
          </div>

          {item.recent_changes.length > 0 ? (
            <ul className="space-y-2">
              {item.recent_changes.slice(0, 3).map((diff) => (
                <li key={diff.diff_id}>
                  <RecentChangeItem diff={diff} />
                </li>
              ))}
            </ul>
          ) : (
            <div className="rounded-lg border border-dashed border-white/[0.1] bg-black/10 p-4 text-caption text-foreground-muted">
              暂无结构化变化。完成至少两次同一竞品分析后，这里会显示功能、定价、定位等维度的变化。
            </div>
          )}

          {item.delta && (item.delta.added_claims.length > 0 || item.delta.removed_claims.length > 0) ? (
            <div className="mt-3 rounded-lg border border-primary/20 bg-primary/5 p-3 text-micro text-foreground-subtle">
              <p className="font-medium text-foreground">结论变化摘要</p>
              {item.delta.added_claims.length > 0 ? (
                <p className="mt-1">新增：{item.delta.added_claims.join("；")}</p>
              ) : null}
              {item.delta.removed_claims.length > 0 ? (
                <p className="mt-1">减少：{item.delta.removed_claims.join("；")}</p>
              ) : null}
            </div>
          ) : null}
        </section>

        <section className="rounded-xl border border-white/[0.06] bg-black/10 p-4">
          <div className="mb-3">
            <h3 className="text-caption font-semibold text-foreground">关键洞察</h3>
            <p className="mt-0.5 text-micro text-foreground-subtle">保留来源报告和证据入口，避免只看孤立结论。</p>
          </div>
          {item.items.length === 0 ? (
            <div className="rounded-lg border border-dashed border-white/[0.1] bg-black/10 p-4 text-caption text-foreground-muted">
              暂无分析洞察。点击“刷新一次”会启动 quick 追踪分析，完成后自动沉淀到这里。
            </div>
          ) : (
            <ul className="space-y-2">
              {item.items.slice(0, 5).map((insight) => (
                <InsightItem
                  key={insight.conclusion_id}
                  insight={insight}
                  onOpenEvidence={onOpenEvidence}
                />
              ))}
            </ul>
          )}
        </section>
      </div>

      <div className="flex flex-col gap-3 border-t border-white/[0.05] px-5 py-4 md:flex-row md:items-center md:justify-between">
        <div className="text-micro text-foreground-subtle">
          {item.last_refreshed_at ? `上次刷新 ${formatRelativeTime(item.last_refreshed_at)}` : "尚未刷新"}
          {item.next_refresh_at ? ` · 下次 ${formatRelativeTime(item.next_refresh_at)}` : " · 当前为手动追踪"}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            variant="secondary"
            onClick={() => onRefresh(item.watch_id)}
            disabled={isRefreshing}
          >
            <RefreshCw className={`h-3.5 w-3.5 ${isRefreshing ? "animate-spin" : ""}`} />
            刷新一次
          </Button>
          <RefreshScheduleDialog item={item}>
            <Button size="sm" variant="outline">
              <CalendarClock className="h-3.5 w-3.5" />
              设置频率
            </Button>
          </RefreshScheduleDialog>
          <Button size="sm" variant="outline" onClick={() => onEditNote(item)}>
            编辑备注
          </Button>
          <Button asChild size="sm" variant="ghost">
            <Link to={focusRunLink(item.latest_run_id, item.competitor_id)}>新建专题分析</Link>
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => onDelete(item.watch_id, displayName)}
          >
            <Trash2 className="h-3.5 w-3.5" />
            移除
          </Button>
        </div>
      </div>
    </article>
  );
}

function isDuplicateWatchlistError(error: unknown): boolean {
  return error instanceof Error && error.message.includes("WATCHLIST_ALREADY_EXISTS");
}

interface ActiveEvidence {
  runId: string;
  evidenceIds: string[];
}

export function WatchPage(): JSX.Element {
  const [newCompetitor, setNewCompetitor] = useState("");
  const [refreshingIds, setRefreshingIds] = useState<Set<string>>(new Set());
  const [activeEvidence, setActiveEvidence] = useState<ActiveEvidence | null>(null);
  const navigate = useNavigate();
  const competitorSeedsQuery = useCompetitorSeeds();
  const watchlistDigestQuery = useWatchlistDigest();
  const createMutation = useCreateWatchlistItem();
  const deleteMutation = useDeleteWatchlistItem();
  const refreshMutation = useManualRefreshWatchlist();
  const patchMutation = usePatchWatchlistItem();

  async function handleAdd(): Promise<void> {
    const id = newCompetitor.trim();
    if (!id) return;
    try {
      await createMutation.mutateAsync({ competitor_id: id });
      setNewCompetitor("");
      pushToast({ title: `已添加 ${id}`, variant: "success" });
    } catch (error) {
      if (isDuplicateWatchlistError(error)) {
        pushToast({ title: `${id} 已在追踪列表`, variant: "default" });
        return;
      }
      if (error instanceof Error)
        pushToast({ title: "添加失败", description: error.message, variant: "danger" });
    }
  }

  async function handleDelete(watchId: string, competitorId: string): Promise<void> {
    if (!window.confirm(`确认移除 ${competitorId} 的追踪？历史分析不会被删除。`)) {
      return;
    }
    try {
      await deleteMutation.mutateAsync(watchId);
      pushToast({ title: "已移除", variant: "success" });
    } catch (error) {
      if (error instanceof Error)
        pushToast({ title: "移除失败", description: error.message, variant: "danger" });
    }
  }

  async function handleEditNote(item: WatchlistDigestItemResponse): Promise<void> {
    const nextNote = window.prompt("更新追踪备注", item.note ?? "");
    if (nextNote === null) return;
    try {
      await patchMutation.mutateAsync({
        watchId: item.watch_id,
        payload: { note: nextNote },
      });
      pushToast({ title: "备注已更新", variant: "success" });
    } catch (error) {
      if (error instanceof Error)
        pushToast({ title: "更新备注失败", description: error.message, variant: "danger" });
    }
  }

  async function handleRefresh(watchId: string): Promise<void> {
    setRefreshingIds((prev) => new Set(prev).add(watchId));
    try {
      const result = await refreshMutation.mutateAsync(watchId);
      pushToast({ title: "刷新任务已启动", variant: "success" });
      navigate(`/app/runs/${result.run_id}/live`);
    } catch (error) {
      if (error instanceof Error)
        pushToast({ title: "触发刷新失败", description: error.message, variant: "danger" });
    } finally {
      setRefreshingIds((prev) => {
        const next = new Set(prev);
        next.delete(watchId);
        return next;
      });
    }
  }

  function openEvidenceDrawer(runId: string, evidenceIds: string[]): void {
    if (evidenceIds.length === 0) return;
    setActiveEvidence({ runId, evidenceIds });
  }

  const watchItems = watchlistDigestQuery.data ?? [];
  const seedOptions = competitorSeedsQuery.data ?? [];

  return (
    <section className="space-y-6">
      <header>
        <h1 className="text-h1 text-foreground">竞品追踪</h1>
        <p className="mt-1 max-w-2xl text-caption text-foreground-muted">
          持续维护竞品档案、变化信号、证据来源和刷新节奏。这里关注“发生了什么变化”，不是简单重跑一份报告。
        </p>
      </header>

      <div className="rounded-2xl border border-white/[0.07] bg-surface p-4">
        <div className="flex flex-col gap-2 md:flex-row">
          <Input
            list="watch-competitor-seeds"
            placeholder="输入产品名，例如 Ray-Ban Meta..."
            value={newCompetitor}
            onChange={(e) => setNewCompetitor(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") void handleAdd();
            }}
          />
          <datalist id="watch-competitor-seeds">
            {seedOptions.map((seed) => (
              <option key={seed.id} value={seed.display_name}>
                {seed.category ?? seed.id}
              </option>
            ))}
          </datalist>
          <Button onClick={() => void handleAdd()} disabled={createMutation.isPending}>
            <Plus className="h-4 w-4" />
            添加追踪
          </Button>
        </div>
        <p className="mt-2 text-micro text-foreground-subtle">
          建议追踪具体产品，而不是公司名。已有分析中的同名或别名竞品会自动聚合历史洞察。
        </p>
      </div>

      {watchlistDigestQuery.isLoading && (
        <div className="space-y-3">
          <Skeleton className="h-56 w-full rounded-2xl" />
          <Skeleton className="h-56 w-full rounded-2xl" />
        </div>
      )}

      {watchlistDigestQuery.isError ? (
        <div className="rounded-lg border border-red-400/40 bg-red-500/10 p-4 text-caption text-red-200">
          <p className="font-medium">竞品追踪加载失败</p>
          <p className="mt-1">{watchlistDigestQuery.error.message}</p>
          <Button className="mt-3" size="sm" variant="outline" onClick={() => void watchlistDigestQuery.refetch()}>
            重试
          </Button>
        </div>
      ) : null}

      {!watchlistDigestQuery.isLoading && !watchlistDigestQuery.isError && watchItems.length === 0 ? (
        <div className="rounded-lg border border-white/[0.06] bg-surface p-8 text-center text-caption text-foreground-muted">
          追踪列表为空。先添加一个具体产品，后续分析和刷新会沉淀竞品档案、变化和证据。
        </div>
      ) : null}

      <div className="space-y-4">
        {watchItems.map((item) => (
          <WatchCard
            key={item.watch_id}
            item={item}
            isRefreshing={refreshingIds.has(item.watch_id)}
            onRefresh={(watchId) => void handleRefresh(watchId)}
            onDelete={(watchId, competitorId) => void handleDelete(watchId, competitorId)}
            onEditNote={(watchItem) => void handleEditNote(watchItem)}
            onOpenEvidence={openEvidenceDrawer}
          />
        ))}
      </div>

      <EvidenceDrawer
        open={activeEvidence !== null}
        onOpenChange={(open) => {
          if (!open) setActiveEvidence(null);
        }}
        runId={activeEvidence?.runId ?? ""}
        evidenceIds={activeEvidence?.evidenceIds ?? []}
      />
    </section>
  );
}
