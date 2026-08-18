import { ArrowRight, MoreHorizontal, Pencil, Plus, Search, Trash2 } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type MouseEvent } from "react";
import { Link, useNavigate } from "react-router-dom";

import { useBatchDeleteRuns, useClearRuns, useDeleteRun, usePatchRun, useResumeRun, useRunsList, useWatchlist } from "@/api/hooks";
import { queryClient } from "@/api/queryClient";
import { StatusBadge } from "@/components/StatusBadge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { pushToast } from "@/components/ui/toaster";
import { formatDateTime, formatRelativeTime, formatRunTitle } from "@/lib/format";
import { runPhaseRoute } from "@/lib/runRoute";
import { track } from "@/lib/analytics";

const PAGE_SIZE = 10;
const CLEAR_ALL_CONFIRM_TEXT = "清空全部历史";
type StatusFilter = "all" | "running" | "completed" | "degraded" | "failed";

function clearSkipDescription(result: {
  skipped_running_count: number;
  skipped_unsettled_count?: number;
}): string | undefined {
  const parts: string[] = [];
  if (result.skipped_running_count > 0) {
    parts.push(`已跳过 ${result.skipped_running_count} 条进行中任务`);
  }
  if ((result.skipped_unsettled_count ?? 0) > 0) {
    parts.push(`已跳过 ${result.skipped_unsettled_count} 条未结清额度`);
  }
  return parts.length > 0 ? parts.join("；") : undefined;
}

export function DashboardPage(): JSX.Element {
  const navigate = useNavigate();
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [offset, setOffset] = useState(0);
  const [searchKeyword, setSearchKeyword] = useState("");

  const runsQuery = useRunsList({
    status: statusFilter === "all" ? undefined : statusFilter,
    limit: PAGE_SIZE,
    offset,
  });
  const latestCompletedQuery = useRunsList({ status: "completed", limit: 4, offset: 0 });
  const latestRunsQuery = useRunsList({ limit: 20, offset: 0 });
  const watchlistQuery = useWatchlist();
  const resumeMutation = useResumeRun();
  const deleteMutation = useDeleteRun();
  const patchMutation = usePatchRun();
  const batchDeleteMutation = useBatchDeleteRuns();
  const clearRunsMutation = useClearRuns();
  const [selectedRuns, setSelectedRuns] = useState<Set<string>>(new Set());
  const [editingRunId, setEditingRunId] = useState<string | null>(null);
  const [editingTitle, setEditingTitle] = useState("");
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null);
  const [isClearAllDialogOpen, setIsClearAllDialogOpen] = useState(false);
  const [clearAllConfirmText, setClearAllConfirmText] = useState("");
  const menuRef = useRef<HTMLDivElement>(null);
  const selectionAnchorIndexRef = useRef<number | null>(null);

  useEffect(() => {
    selectionAnchorIndexRef.current = null;
  }, [statusFilter, offset, searchKeyword]);

  const currentItems = runsQuery.data?.items ?? [];
  const filteredItems = useMemo(() => {
    const kw = searchKeyword.trim().toLowerCase();
    return kw ? currentItems.filter((i) => i.user_query.toLowerCase().includes(kw)) : currentItems;
  }, [currentItems, searchKeyword]);

  const latestRuns = latestRunsQuery.data?.items ?? [];
  const continueRun = latestRuns.find((i) => i.status === "running" || i.status === "failed");
  const hasAnyHistoryTasks = (runsQuery.data?.total ?? 0) > 0;
  const currentPage = Math.floor(offset / PAGE_SIZE) + 1;
  const totalPages = Math.max(1, Math.ceil((runsQuery.data?.total ?? 0) / PAGE_SIZE));

  async function handleResumeRun(runId: string): Promise<void> {
    try {
      await resumeMutation.mutateAsync(runId);
      track("dashboard.resume_run", { run_id: runId });
      pushToast({ title: "任务已恢复", variant: "success" });
      navigate(`/app/runs/${runId}`);
    } catch (error) {
      if (error instanceof Error) {
        pushToast({ title: "恢复失败", description: error.message, variant: "danger" });
      }
    }
  }

  async function handleDeleteRun(runId: string): Promise<void> {
    try {
      await deleteMutation.mutateAsync(runId);
      track("dashboard.delete_run", { run_id: runId });
      pushToast({ title: "已删除", variant: "success" });
      void queryClient.invalidateQueries({ queryKey: ["runs"] });
    } catch (error) {
      if (error instanceof Error) {
        pushToast({ title: "删除失败", description: error.message, variant: "danger" });
      }
    }
    setMenuOpenId(null);
  }

  async function handleRename(runId: string): Promise<void> {
    const trimmed = editingTitle.trim();
    if (!trimmed) { setEditingRunId(null); return; }
    try {
      // user_query is the immutable original prompt; rename should only
      // mutate the display label so we don't corrupt downstream prompts
      // (intake history snapshots, planner inputs, etc.).
      await patchMutation.mutateAsync({ runId, payload: { title: trimmed } });
      pushToast({ title: "已重命名", variant: "success" });
      void queryClient.invalidateQueries({ queryKey: ["runs"] });
    } catch (error) {
      if (error instanceof Error) {
        pushToast({ title: "重命名失败", description: error.message, variant: "danger" });
      }
    }
    setEditingRunId(null);
  }

  function handleHistoryCheckboxClick(index: number, event: MouseEvent<HTMLInputElement>): void {
    event.stopPropagation();
    const run = filteredItems[index];
    if (run === undefined) {
      return;
    }

    if (event.shiftKey && selectionAnchorIndexRef.current !== null) {
      // Convention (GitHub/Gmail/Linear): shift+click always extends selection.
      // Toggling on shift would invert anchor state and surprise the user when
      // the anchor row is already selected (the common case right after a click).
      const anchor = selectionAnchorIndexRef.current;
      const lo = Math.min(anchor, index);
      const hi = Math.max(anchor, index);
      setSelectedRuns((prev) => {
        const next = new Set(prev);
        for (let i = lo; i <= hi; i++) {
          const row = filteredItems[i];
          if (row !== undefined) {
            next.add(row.run_id);
          }
        }
        return next;
      });
      return;
    }

    setSelectedRuns((prev) => {
      const next = new Set(prev);
      if (next.has(run.run_id)) {
        next.delete(run.run_id);
      } else {
        next.add(run.run_id);
      }
      return next;
    });
    selectionAnchorIndexRef.current = index;
  }

  async function handleBatchDelete(): Promise<void> {
    if (selectedRuns.size === 0) return;
    try {
      const result = await batchDeleteMutation.mutateAsync([...selectedRuns]);
      pushToast({
        title: `已删除 ${result.deleted_count} 项`,
        description:
          (result.skipped_unsettled_count ?? 0) > 0
            ? `已跳过 ${result.skipped_unsettled_count} 条未结清额度`
            : undefined,
        variant: "success",
      });
      setSelectedRuns(new Set());
      void queryClient.invalidateQueries({ queryKey: ["runs"] });
    } catch (error) {
      if (error instanceof Error) {
        pushToast({ title: "批量删除失败", description: error.message, variant: "danger" });
      }
    }
  }

  async function handleClearCurrentFilter(): Promise<void> {
    if (!hasAnyHistoryTasks) {
      pushToast({ title: "暂无可清空任务", variant: "warning" });
      return;
    }
    if (!window.confirm("将清空当前筛选下的历史任务（运行中任务不会删除）。此操作不可恢复，确定继续吗？")) {
      return;
    }
    try {
      const result = await clearRunsMutation.mutateAsync({
        status: statusFilter,
        keyword: searchKeyword.trim() || undefined,
        include_running: false,
      });
      setSelectedRuns(new Set());
      selectionAnchorIndexRef.current = null;
      void queryClient.invalidateQueries({ queryKey: ["runs"] });
      pushToast({
        title: `已清空 ${result.deleted_count} 条`,
        description: clearSkipDescription(result),
        variant: "success",
      });
    } catch (error) {
      if (error instanceof Error) {
        pushToast({ title: "清空失败", description: error.message, variant: "danger" });
      }
    }
  }

  async function handleClearAllHistory(): Promise<void> {
    if (clearAllConfirmText !== CLEAR_ALL_CONFIRM_TEXT) {
      return;
    }
    try {
      const result = await clearRunsMutation.mutateAsync({
        status: "all",
        include_running: false,
      });
      setSelectedRuns(new Set());
      selectionAnchorIndexRef.current = null;
      void queryClient.invalidateQueries({ queryKey: ["runs"] });
      pushToast({
        title: `已清空 ${result.deleted_count} 条`,
        description: clearSkipDescription(result),
        variant: "success",
      });
      setClearAllConfirmText("");
      setIsClearAllDialogOpen(false);
    } catch (error) {
      if (error instanceof Error) {
        pushToast({ title: "清空失败", description: error.message, variant: "danger" });
      }
    }
  }

  return (
    <section className="space-y-8">
      {/* Header */}
      <div className="flex items-end justify-between">
        <div>
          <h1 className="text-h1 text-foreground">我的调研</h1>
          <p className="mt-1 text-caption text-foreground-muted">继续上次任务、复盘历史报告、快速启动新的深度调研。</p>
        </div>
        <Button asChild>
          <Link to="/app/runs/new">
            <Plus className="h-4 w-4" />
            新建调研
          </Link>
        </Button>
      </div>

      {/* Top cards */}
      <div className="grid gap-4 md:grid-cols-2">
        {/* Continue card */}
        <div className="rounded-lg border border-white/[0.06] bg-surface p-5">
          <p className="text-micro font-medium uppercase tracking-wider text-foreground-subtle">继续上次</p>
          {continueRun ? (
            <div className="mt-3 space-y-2">
              <p className="text-caption font-medium text-foreground">{continueRun.user_query}</p>
              <div className="flex items-center gap-2">
                <StatusBadge status={continueRun.status} reason={continueRun.status_reason} />
                <span className="text-micro text-foreground-subtle">{formatRelativeTime(continueRun.started_at)}</span>
              </div>
              <Button
                size="sm"
                onClick={() => {
                  if (continueRun.status === "running") {
                    navigate(runPhaseRoute(continueRun));
                    return;
                  }
                  void handleResumeRun(continueRun.run_id);
                }}
              >
                继续处理
              </Button>
            </div>
          ) : (
            <p className="mt-3 text-caption text-foreground-muted">暂无进行中任务</p>
          )}
        </div>

        {/* Watchlist card */}
        <div className="rounded-lg border border-white/[0.06] bg-surface p-5">
          <p className="text-micro font-medium uppercase tracking-wider text-foreground-subtle">调研追踪</p>
          <p className="mt-3 text-h2 font-semibold text-foreground">{watchlistQuery.data?.length ?? 0}</p>
          <p className="text-micro text-foreground-muted">个对象正在追踪</p>
          <Button asChild size="sm" variant="secondary" className="mt-3">
            <Link to="/app/watch">管理追踪列表</Link>
          </Button>
        </div>
      </div>

      {/* Latest reports */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="text-h3 text-foreground">最新报告</h2>
          <Link className="text-micro text-primary hover:underline" to="/examples">
            查看全部 <ArrowRight className="inline h-3 w-3" />
          </Link>
        </div>
        {latestCompletedQuery.isLoading ? (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-24 w-full" />)}
          </div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {(latestCompletedQuery.data?.items ?? []).map((run) => (
              <Link
                key={run.run_id}
                to={`/app/runs/${run.run_id}`}
                className="rounded-lg border border-white/[0.06] bg-surface p-4 transition-colors hover:border-white/[0.12]"
              >
                <p
                  className="line-clamp-2 text-caption font-medium text-foreground"
                  title={run.user_query}
                >
                  {formatRunTitle(run)}
                </p>
                <p className="mt-2 text-micro text-foreground-subtle">
                  {run.finished_at ? formatDateTime(run.finished_at) : "处理中"}
                </p>
              </Link>
            ))}
          </div>
        )}
      </div>

      {/* History */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="text-h3 text-foreground">历史任务</h2>
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              variant="ghost"
              disabled={clearRunsMutation.isPending || !hasAnyHistoryTasks}
              onClick={() => void handleClearCurrentFilter()}
            >
              清空当前筛选
            </Button>
            {selectedRuns.size > 0 && (
              <Button size="sm" variant="danger" onClick={() => void handleBatchDelete()}>
                <Trash2 className="h-3.5 w-3.5" />
                删除 {selectedRuns.size} 项
              </Button>
            )}
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-foreground-subtle" />
            <Input className="pl-9" onChange={(e) => setSearchKeyword(e.target.value)} placeholder="搜索..." value={searchKeyword} />
          </div>
          <NativeSelect
            className="w-auto shrink-0 text-caption"
            onChange={(e) => { setStatusFilter(e.target.value as StatusFilter); setOffset(0); }}
            value={statusFilter}
          >
            <option value="all">全部</option>
            <option value="running">进行中</option>
            <option value="completed">已完成</option>
            <option value="degraded">降级</option>
            <option value="failed">失败</option>
          </NativeSelect>
        </div>
        <details className="rounded-lg border border-danger/20 bg-danger/[0.03] px-3 py-2">
          <summary className="cursor-pointer text-micro font-medium text-danger">危险操作</summary>
          <div className="mt-3 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <p className="text-micro text-foreground-muted">
              清空全部历史会删除所有非运行中任务。这个操作不可恢复。
            </p>
            <Button
              size="sm"
              variant="danger"
              disabled={clearRunsMutation.isPending}
              onClick={() => setIsClearAllDialogOpen(true)}
            >
              清空全部历史
            </Button>
          </div>
        </details>

        {runsQuery.isLoading && <Skeleton className="h-32 w-full" />}
        {runsQuery.isError && (
          <div className="rounded-lg border border-danger/30 bg-danger/5 p-3 text-caption text-danger">{runsQuery.error.message}</div>
        )}

        <div className="space-y-1">
          {filteredItems.map((run, index) => (
            <div
              key={run.run_id}
              className="group relative flex w-full items-center gap-2 rounded-md px-3 py-2.5 transition-colors hover:bg-white/[0.03]"
            >
              <input
                type="checkbox"
                readOnly
                className="h-3.5 w-3.5 shrink-0 cursor-pointer rounded border-white/20 bg-transparent accent-primary"
                checked={selectedRuns.has(run.run_id)}
                onMouseDown={(e) => {
                  // Suppress browser's native shift+click text-range selection
                  // which otherwise highlights row text and breaks the gesture.
                  if (e.shiftKey) e.preventDefault();
                }}
                onClick={(e) => handleHistoryCheckboxClick(index, e)}
              />
              <button
                type="button"
                onClick={() => navigate(runPhaseRoute(run))}
                className="min-w-0 flex-1 text-left focus-visible:outline-none"
              >
                {editingRunId === run.run_id ? (
                  <Input
                    autoFocus
                    className="h-7 text-caption"
                    value={editingTitle}
                    onChange={(e) => setEditingTitle(e.target.value)}
                    onBlur={() => void handleRename(run.run_id)}
                    onKeyDown={(e) => { if (e.key === "Enter") void handleRename(run.run_id); if (e.key === "Escape") setEditingRunId(null); }}
                    onClick={(e) => e.stopPropagation()}
                  />
                ) : (
                  <>
                    <p
                      className="truncate text-caption font-medium text-foreground"
                      title={run.user_query}
                    >
                      {formatRunTitle(run)}
                    </p>
                    <p className="text-micro text-foreground-subtle">
                      {run.domain_hint ?? "通用"} · {run.evidence_count} 证据 · {run.step_count} 步骤
                    </p>
                    {run.status_reason ? (
                      <p className="truncate text-micro text-warning" title={run.status_reason}>
                        {run.status_reason}
                      </p>
                    ) : null}
                  </>
                )}
              </button>
              <StatusBadge status={run.status} reason={run.status_reason} />
              <div className="relative" ref={menuOpenId === run.run_id ? menuRef : undefined}>
                <button
                  type="button"
                  className="rounded p-1 text-foreground-subtle opacity-0 transition-opacity hover:bg-white/[0.06] group-hover:opacity-100"
                  onClick={(e) => { e.stopPropagation(); setMenuOpenId(menuOpenId === run.run_id ? null : run.run_id); }}
                >
                  <MoreHorizontal className="h-4 w-4" />
                </button>
                {menuOpenId === run.run_id && (
                  <div className="absolute right-0 top-8 z-50 w-32 rounded-md border border-white/[0.08] bg-raised py-1 shadow-raised">
                    <button
                      type="button"
                      className="flex w-full items-center gap-2 px-3 py-1.5 text-caption text-foreground hover:bg-white/[0.06]"
                      onClick={(e) => {
                        e.stopPropagation();
                        setEditingRunId(run.run_id);
                        setEditingTitle(formatRunTitle(run));
                        setMenuOpenId(null);
                      }}
                    >
                      <Pencil className="h-3.5 w-3.5" /> 重命名
                    </button>
                    <button
                      type="button"
                      className="flex w-full items-center gap-2 px-3 py-1.5 text-caption text-danger hover:bg-white/[0.06]"
                      onClick={(e) => { e.stopPropagation(); void handleDeleteRun(run.run_id); }}
                    >
                      <Trash2 className="h-3.5 w-3.5" /> 删除
                    </button>
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>

        {/* Pagination */}
        <div className="flex items-center justify-between text-micro text-foreground-subtle">
          <span>{currentPage} / {totalPages}</span>
          <div className="flex gap-1">
            <Button size="sm" variant="ghost" disabled={offset === 0} onClick={() => setOffset((p) => Math.max(0, p - PAGE_SIZE))}>上一页</Button>
            <Button size="sm" variant="ghost" disabled={offset + PAGE_SIZE >= (runsQuery.data?.total ?? 0)} onClick={() => setOffset((p) => p + PAGE_SIZE)}>下一页</Button>
          </div>
        </div>
      </div>
      <Dialog
        open={isClearAllDialogOpen}
        onOpenChange={(open) => {
          setIsClearAllDialogOpen(open);
          if (!open) setClearAllConfirmText("");
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>确认清空全部历史</DialogTitle>
            <DialogDescription>
              将删除所有非运行中的历史任务，运行中任务会被跳过。此操作不可恢复。
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <label htmlFor="clear-all-confirm" className="text-caption text-foreground-muted">
              输入“{CLEAR_ALL_CONFIRM_TEXT}”以继续
            </label>
            <Input
              id="clear-all-confirm"
              value={clearAllConfirmText}
              onChange={(event) => setClearAllConfirmText(event.target.value)}
              placeholder={CLEAR_ALL_CONFIRM_TEXT}
            />
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="ghost"
              onClick={() => setIsClearAllDialogOpen(false)}
            >
              取消
            </Button>
            <Button
              type="button"
              variant="danger"
              disabled={clearAllConfirmText !== CLEAR_ALL_CONFIRM_TEXT || clearRunsMutation.isPending}
              onClick={() => void handleClearAllHistory()}
            >
              确认清空
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
