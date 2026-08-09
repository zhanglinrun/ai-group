import {
  AlertTriangle,
  CheckCircle2,
  ChevronLeft,
  Compass,
  FileText,
  Loader2,
  Microscope,
  PenLine,
  Pin,
  Plus,
  Sparkles,
  X,
} from "lucide-react";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useNavigate, useParams } from "react-router-dom";

import { useConfirmRunPlan, useRunDetail } from "@/api/hooks";
import type { PlanTask, PlanTaskStage, PlanTree, RunIntakeDraft } from "@/api/types";
import { CancelRunButton } from "@/components/CancelRunButton";
import { RunBreadcrumb } from "@/components/RunBreadcrumb";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { pushToast } from "@/components/ui/toaster";
import { groupCompetitorRoles } from "@/lib/competitorRoles";
import { cn } from "@/lib/utils";

const STAGE_META: Record<PlanTaskStage, { label: string; icon: typeof Compass; description: string }> = {
  discover: {
    label: "发现",
    icon: Compass,
    description: "Agent 先去找出赛道上的头部竞品。",
  },
  research: {
    label: "调研",
    icon: Microscope,
    description: "按维度采集每家竞品的事实证据。",
  },
  analyze: {
    label: "分析",
    icon: Sparkles,
    description: "跨竞品对比、找出差异化与机会点。",
  },
  write: {
    label: "撰写",
    icon: PenLine,
    description: "把证据 + 洞察整理成可分发的报告。",
  },
};

const STAGE_ORDER: PlanTaskStage[] = ["discover", "research", "analyze", "write"];

// Phase β: stages a user is allowed to inject. Mirrors the backend's
// `_USER_ALLOWED_STAGES`; discover is reserved for the discovery node.
const USER_ALLOWED_STAGES: ReadonlyArray<"research" | "analyze" | "write"> = [
  "research",
  "analyze",
  "write",
];
// Phase β: backend caps `additional_tasks` at this number; we enforce locally
// so the user gets immediate feedback instead of waiting for a 500-equivalent
// background failure.
const MAX_ADDITIONAL_TASKS = 5;
const USER_TASK_TITLE_MAX = 60;

const POST_CONFIRM_REDIRECT_MS = 800;

const TERMINAL_STATUSES = new Set(["completed", "degraded", "failed", "cancelled"]);

export function PlanConfirmPage(): JSX.Element {
  const { runId: rawRunId } = useParams<{ runId: string }>();
  const runId = rawRunId ?? "";
  const navigate = useNavigate();
  const runDetail = useRunDetail(runId);
  const confirmPlan = useConfirmRunPlan();

  const [disabledIds, setDisabledIds] = useState<Set<string>>(() => new Set());
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  // Phase β: user-injected tasks held locally until /plan/confirm submits.
  // Server regenerates task_id; the local id is only used as a React key.
  const [additionalTasks, setAdditionalTasks] = useState<PlanTask[]>([]);

  const planTree = runDetail.data?.plan_tree ?? null;
  const runStatus = runDetail.data?.status ?? null;
  const phase = runDetail.data?.phase ?? null;
  const planConfirmed = Boolean(planTree?.confirmed_at);

  // Initialize the disabled set from the plan exactly once (when the plan
  // first arrives). Keeps the checkbox state stable across SSE re-fetches
  // — otherwise React Query invalidations would reset user choices.
  useEffect(() => {
    if (planTree === null || touched) {
      return;
    }
    setDisabledIds(new Set());
  }, [planTree, touched]);

  // Auto-redirect if the run is already past the planning gate.
  useEffect(() => {
    if (!runId) {
      return;
    }
    if (runStatus && TERMINAL_STATUSES.has(runStatus)) {
      navigate(`/app/runs/${runId}`, { replace: true });
      return;
    }
    if (planConfirmed && phase === "executing") {
      navigate(`/app/runs/${runId}/live`, { replace: true });
    }
  }, [runId, runStatus, planConfirmed, phase, navigate]);

  const tasksByStage = useMemo(() => {
    if (planTree === null) {
      return null;
    }
    const grouped: Record<PlanTaskStage, PlanTask[]> = {
      discover: [],
      research: [],
      analyze: [],
      write: [],
    };
    for (const task of planTree.tasks) {
      grouped[task.stage].push(task);
    }
    return grouped;
  }, [planTree]);

  const totalEnabled = useMemo(() => {
    if (planTree === null) {
      return 0;
    }
    const enabledFromPlan = planTree.tasks.filter(
      (task) => !disabledIds.has(task.task_id),
    ).length;
    return enabledFromPlan + additionalTasks.length;
  }, [planTree, disabledIds, additionalTasks]);

  const removeAdditionalTask = (taskId: string): void => {
    setAdditionalTasks((prev) => prev.filter((task) => task.task_id !== taskId));
  };

  const addAdditionalTask = (task: PlanTask): void => {
    setAdditionalTasks((prev) => [...prev, task]);
  };

  const toggleTask = (taskId: string): void => {
    setTouched(true);
    setDisabledIds((prev) => {
      const next = new Set(prev);
      if (next.has(taskId)) {
        next.delete(taskId);
      } else {
        next.add(taskId);
      }
      return next;
    });
  };

  async function handleConfirm(): Promise<void> {
    if (planTree === null || submitting) {
      return;
    }
    if (totalEnabled === 0) {
      pushToast({
        title: "至少需要保留一个任务",
        description: "全部任务被禁用，无法启动分析。",
        variant: "danger",
      });
      return;
    }
    setSubmitting(true);
    try {
      await confirmPlan.mutateAsync({
        runId,
        payload: {
          disabled_task_ids: Array.from(disabledIds),
          additional_tasks: additionalTasks,
        },
      });
      pushToast({
        title: "计划已确认",
        description: "Agent 正在按计划执行，跳转到实时进度…",
        variant: "success",
      });
      window.setTimeout(() => {
        navigate(`/app/runs/${runId}/live`);
      }, POST_CONFIRM_REDIRECT_MS);
    } catch (error) {
      const status = (error as { response?: { status?: number } }).response?.status;
      const errorCode = (error as { response?: { data?: { error_code?: string } } }).response?.data
        ?.error_code;
      if (status === 409 && errorCode === "PLAN_NOT_AWAITING_CONFIRM") {
        // The graph already moved past planner_wait — most often because the
        // user confirmed in another tab. Send them to the live page instead of
        // surfacing the 409 as an error.
        pushToast({
          title: "计划已被确认",
          description: "Agent 已开始执行，正在跳转…",
          variant: "default",
        });
        navigate(`/app/runs/${runId}/live`, { replace: true });
        return;
      }
      const message = error instanceof Error ? error.message : "未知错误";
      pushToast({
        title: "确认失败",
        description: message,
        variant: "danger",
      });
    } finally {
      setSubmitting(false);
    }
  }

  if (!runId) {
    return (
      <section className="space-y-4">
        <header className="space-y-2">
          <h1 className="text-h1 text-foreground">计划确认</h1>
          <p className="text-caption text-foreground-muted">缺少 run_id 参数，请从对话页重新进入。</p>
        </header>
      </section>
    );
  }

  return (
    <section className="space-y-5">
      <header className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <RunBreadcrumb run={runDetail.data} current="计划确认" />
          <Button
            asChild
            className="inline-flex items-center gap-1 text-caption text-foreground-muted hover:text-foreground"
            size="sm"
            variant="ghost"
          >
            <a href="/app/runs/new" onClick={(event) => {
              event.preventDefault();
              navigate("/app/runs/new");
            }}>
              <ChevronLeft className="h-3.5 w-3.5" />
              返回对话
            </a>
          </Button>
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-h1 text-foreground">确认分析计划</h1>
          <div className="flex items-center gap-2">
            <Badge variant="secondary" className="font-mono text-xs">{runId}</Badge>
            <CancelRunButton runId={runId} label="放弃此次分析" redirectTo="/app" />
          </div>
        </div>
        <p className="text-caption text-foreground-muted">
          Agent 已根据需求拟定了一份分步执行计划。你可以取消不需要的任务，确认后会立即开始抓取证据。
        </p>
      </header>

      <div className="grid min-h-0 gap-4 lg:grid-cols-3 lg:items-stretch">
        <div className="space-y-3 lg:col-span-2">
          {planTree === null ? (
            <PlanLoadingCard />
          ) : (
            <div className="space-y-3">
              {planTree.rationale ? (
                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="inline-flex items-center gap-2 text-base">
                      <Sparkles className="h-4 w-4 text-primary" />
                      Agent 的整体思路
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="pt-0 text-sm leading-relaxed text-foreground">
                    {planTree.rationale}
                  </CardContent>
                </Card>
              ) : null}

              <div className="space-y-3">
                {STAGE_ORDER.map((stage) => {
                  const tasks = tasksByStage?.[stage] ?? [];
                  if (tasks.length === 0) {
                    return null;
                  }
                  return (
                    <StageBlock
                      key={stage}
                      stage={stage}
                      tasks={tasks}
                      disabledIds={disabledIds}
                      onToggle={toggleTask}
                    />
                  );
                })}
              </div>

              <AdditionalTasksCard
                tasks={additionalTasks}
                onAdd={addAdditionalTask}
                onRemove={removeAdditionalTask}
              />

              <Card className="border-primary/30">
                <CardContent className="flex flex-wrap items-center justify-between gap-3 p-4">
                  <div className="space-y-0.5 text-sm">
                    <p className="font-medium text-foreground">
                      共 {planTree.tasks.length} 个任务，已选 {totalEnabled} 个
                    </p>
                    <p className="text-caption text-foreground-muted">
                      取消勾选的任务不会被执行；确认后无法再编辑。
                    </p>
                  </div>
                  <Button
                    disabled={submitting || totalEnabled === 0}
                    size="sm"
                    type="button"
                    onClick={() => {
                      void handleConfirm();
                    }}
                  >
                    {submitting ? (
                      <>
                        <Loader2 className="h-3.5 w-3.5 animate-spin" />
                        启动中
                      </>
                    ) : (
                      "确认并启动分析"
                    )}
                  </Button>
                </CardContent>
              </Card>
            </div>
          )}
        </div>

        <aside className="flex min-h-0 flex-col gap-3 lg:h-full">
          <IntakeSummaryCard
            draft={runDetail.data?.intake_draft ?? null}
            planTree={planTree}
            className="min-h-0 flex-1"
          />
          <PlanMetadataCard planTree={planTree} className="shrink-0" />
        </aside>
      </div>
    </section>
  );
}

interface StageBlockProps {
  stage: PlanTaskStage;
  tasks: PlanTask[];
  disabledIds: Set<string>;
  onToggle: (taskId: string) => void;
}

function StageBlock({ stage, tasks, disabledIds, onToggle }: StageBlockProps): JSX.Element {
  const meta = STAGE_META[stage];
  const Icon = meta.icon;
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="inline-flex items-center gap-2 text-base">
          <Icon className="h-4 w-4 text-primary" />
          {meta.label}
          <span className="text-caption font-normal text-foreground-muted">· {tasks.length}</span>
        </CardTitle>
        <p className="text-caption text-foreground-muted">{meta.description}</p>
      </CardHeader>
      <CardContent className="space-y-2 pt-0">
        {tasks.map((task) => (
          <TaskRow
            key={task.task_id}
            task={task}
            disabled={disabledIds.has(task.task_id)}
            onToggle={() => onToggle(task.task_id)}
          />
        ))}
      </CardContent>
    </Card>
  );
}

interface TaskRowProps {
  task: PlanTask;
  disabled: boolean;
  onToggle: () => void;
}

function TaskRow({ task, disabled, onToggle }: TaskRowProps): JSX.Element {
  const checkboxId = `plan-task-${task.task_id}`;
  return (
    <label
      htmlFor={checkboxId}
      className={cn(
        "flex cursor-pointer items-start gap-3 rounded-md border p-3 transition-colors",
        disabled
          ? "border-white/[0.06] bg-white/[0.02] opacity-60"
          : "border-white/[0.08] bg-white/[0.04] hover:bg-white/[0.06]",
      )}
    >
      <input
        checked={!disabled}
        className="mt-1 h-3.5 w-3.5 cursor-pointer rounded border-white/20 bg-white/[0.05]"
        id={checkboxId}
        onChange={onToggle}
        type="checkbox"
      />
      <div className="flex-1 space-y-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className={cn("text-sm font-medium", disabled ? "text-foreground-muted line-through" : "text-foreground")}>
            {task.title}
          </span>
          {task.competitor_id ? (
            <Badge variant="secondary" className="text-xs">{task.competitor_id}</Badge>
          ) : null}
          {task.priority === "user_pinned" ? (
            <Badge variant="default" className="text-xs">补充优先</Badge>
          ) : null}
        </div>
        {task.description ? (
          <p className="text-caption text-foreground-muted">{task.description}</p>
        ) : null}
        {task.focus_dimensions.length > 0 ? (
          <div className="flex flex-wrap gap-1">
            {task.focus_dimensions.map((dim) => (
              <Badge key={dim} variant="outline" className="text-[10px]">{dim}</Badge>
            ))}
          </div>
        ) : null}
      </div>
    </label>
  );
}

interface AdditionalTasksCardProps {
  tasks: PlanTask[];
  onAdd: (task: PlanTask) => void;
  onRemove: (taskId: string) => void;
}

function AdditionalTasksCard({ tasks, onAdd, onRemove }: AdditionalTasksCardProps): JSX.Element {
  const [formOpen, setFormOpen] = useState(false);
  const [stage, setStage] = useState<"research" | "analyze" | "write">("research");
  const [title, setTitle] = useState("");
  const [competitorId, setCompetitorId] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);

  const atCapacity = tasks.length >= MAX_ADDITIONAL_TASKS;
  const titleTrimmed = title.trim();
  const titleTooLong = titleTrimmed.length > USER_TASK_TITLE_MAX;
  const needsCompetitor = stage === "research";
  const competitorTrimmed = competitorId.trim();
  const canSubmit =
    titleTrimmed.length > 0 &&
    !titleTooLong &&
    (!needsCompetitor || competitorTrimmed.length > 0);

  const resetForm = (): void => {
    setStage("research");
    setTitle("");
    setCompetitorId("");
    setDescription("");
    setError(null);
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (!canSubmit) {
      if (!titleTrimmed) {
        setError("请填写任务标题");
      } else if (titleTooLong) {
        setError(`标题不能超过 ${USER_TASK_TITLE_MAX} 字符`);
      } else if (needsCompetitor && !competitorTrimmed) {
        setError("调研任务需要指定竞品");
      }
      return;
    }
    const localTaskId =
      typeof globalThis.crypto !== "undefined" &&
      typeof globalThis.crypto.randomUUID === "function"
        ? globalThis.crypto.randomUUID()
        : `${Date.now()}_${Math.random().toString(16).slice(2)}`;
    const newTask: PlanTask = {
      // Local-only id; the backend regenerates a ptask_ prefix before merging.
      task_id: `client_${localTaskId}`,
      stage,
      title: titleTrimmed,
      description: description.trim(),
      competitor_id: needsCompetitor ? competitorTrimmed : null,
      focus_dimensions: [],
      source: "user",
      enabled: true,
      priority: "user_pinned",
    };
    onAdd(newTask);
    pushToast({
      title: "补充任务已加入待确认队列",
      description: "点击“确认并启动分析”后才会生效。",
      variant: "success",
    });
    resetForm();
    setFormOpen(false);
  };

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="inline-flex items-center gap-2 text-base">
          <Pin className="h-4 w-4 text-warning" />
          补充任务（优先）
          <span className="text-caption font-normal text-foreground-muted">
            · {tasks.length}/{MAX_ADDITIONAL_TASKS}
          </span>
        </CardTitle>
        <p className="text-caption text-foreground-muted">
          这里新增的是待确认补充任务，点击“确认并启动分析”后才会提交并按高优先级执行。
        </p>
      </CardHeader>
      <CardContent className="space-y-2 pt-0">
        {tasks.length === 0 && !formOpen ? (
          <p className="text-caption text-foreground-muted">
            还没有添加补充任务。
          </p>
        ) : null}
        {tasks.map((task) => (
          <div
            key={task.task_id}
            className="flex items-start gap-3 rounded-md border border-warning/40 bg-warning/[0.04] p-3"
          >
            <Pin className="mt-1 h-3.5 w-3.5 text-warning" />
            <div className="flex-1 space-y-1.5">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-medium text-foreground">{task.title}</span>
                <Badge variant="outline" className="text-xs">
                  {STAGE_META[task.stage].label}
                </Badge>
                {task.competitor_id ? (
                  <Badge variant="secondary" className="text-xs">{task.competitor_id}</Badge>
                ) : null}
              </div>
              {task.description ? (
                <p className="text-caption text-foreground-muted">{task.description}</p>
              ) : null}
            </div>
            <Button
              size="sm"
              variant="ghost"
              type="button"
              onClick={() => onRemove(task.task_id)}
              aria-label="移除任务"
            >
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>
        ))}

        {formOpen ? (
          <form
            onSubmit={handleSubmit}
            className="space-y-2 rounded-md border border-white/[0.08] bg-white/[0.02] p-3"
          >
            <div className="grid gap-2 sm:grid-cols-2">
              <label className="space-y-1 text-caption text-foreground-muted">
                阶段
                <NativeSelect
                  value={stage}
                  onChange={(event) =>
                    setStage(event.target.value as "research" | "analyze" | "write")
                  }
                >
                  {USER_ALLOWED_STAGES.map((s) => (
                    <option key={s} value={s}>
                      {STAGE_META[s].label}
                    </option>
                  ))}
                </NativeSelect>
              </label>
              {needsCompetitor ? (
                <label className="space-y-1 text-caption text-foreground-muted">
                  竞品名称
                  <input
                    type="text"
                    value={competitorId}
                    onChange={(event) => setCompetitorId(event.target.value)}
                    placeholder="如 GitHub Copilot"
                    className="block w-full rounded-md border border-white/[0.08] bg-transparent px-2 py-1.5 text-sm text-foreground placeholder:text-foreground-muted/60 focus:border-primary/40 focus:outline-none"
                  />
                </label>
              ) : null}
            </div>
            <label className="block space-y-1 text-caption text-foreground-muted">
              任务标题（≤ {USER_TASK_TITLE_MAX} 字符）
              <input
                type="text"
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                placeholder="如 调研 GitHub Copilot 的定价"
                className={cn(
                  "block w-full rounded-md border bg-transparent px-2 py-1.5 text-sm text-foreground placeholder:text-foreground-muted/60 focus:outline-none",
                  titleTooLong
                    ? "border-danger/60 focus:border-danger/60"
                    : "border-white/[0.08] focus:border-primary/40",
                )}
              />
            </label>
            <label className="block space-y-1 text-caption text-foreground-muted">
              描述（可选）
              <textarea
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                rows={2}
                placeholder="给 Agent 一句话上下文。"
                className="block w-full resize-none rounded-md border border-white/[0.08] bg-transparent px-2 py-1.5 text-sm text-foreground placeholder:text-foreground-muted/60 focus:border-primary/40 focus:outline-none"
              />
            </label>
            {error ? (
              <p className="text-caption text-danger">{error}</p>
            ) : null}
            <div className="flex justify-end gap-2">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => {
                  resetForm();
                  setFormOpen(false);
                }}
              >
                取消
              </Button>
              <Button type="submit" size="sm" disabled={!canSubmit}>
                添加补充任务
              </Button>
            </div>
          </form>
        ) : (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => setFormOpen(true)}
            disabled={atCapacity}
            className="w-full justify-center border border-dashed border-white/[0.08]"
          >
            <Plus className="h-3.5 w-3.5" />
            {atCapacity ? `已达上限 (${MAX_ADDITIONAL_TASKS})` : "添加补充任务"}
          </Button>
        )}
      </CardContent>
    </Card>
  );
}

function PlanLoadingCard(): JSX.Element {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="inline-flex items-center gap-2 text-base">
          <Loader2 className="h-4 w-4 animate-spin text-primary" />
          Agent 正在拟定分析计划…
        </CardTitle>
        <p className="text-caption text-foreground-muted">
          Planner 通常需要 5-15 秒，期间你可以稍等或先阅读右侧需求摘要。
        </p>
      </CardHeader>
      <CardContent className="space-y-3 pt-0">
        {[0, 1, 2].map((idx) => (
          <div className="space-y-2" key={idx}>
            <Skeleton className="h-4 w-1/3" />
            <Skeleton className="h-12 w-full" />
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

function IntakeSummaryCard({
  draft,
  planTree,
  className,
}: {
  draft: RunIntakeDraft | null;
  planTree: PlanTree | null;
  className?: string;
}): JSX.Element {
  const roleGroups = useMemo(() => groupCompetitorRoles(planTree), [planTree]);
  return (
    <Card className={cn("flex min-h-0 flex-col", className)}>
      <CardHeader className="shrink-0 pb-3">
        <CardTitle className="inline-flex items-center gap-2 text-base">
          <FileText className="h-4 w-4 text-primary" />
          需求摘要
        </CardTitle>
      </CardHeader>
      <CardContent className="min-h-0 flex-1 space-y-2 overflow-y-auto pt-0 text-xs text-foreground-muted">
        {draft === null ? (
          <p>暂无需求快照。</p>
        ) : (
          <>
            <SummaryRow label="原始诉求" value={draft.user_query} />
            <SummaryRow label="用户角色" value={draft.user_role ?? "—"} />
            <SummaryRow label="分析意图" value={draft.analysis_intent ?? "—"} />
            <SummaryRow
              label="分析形态"
              value={
                draft.analysis_archetype === "landscape"
                  ? "landscape（赛道分层）"
                  : "comparison（同类对比）"
              }
            />
            <SummaryRow
              label="竞品"
              value={
                draft.competitors_explicit.length > 0
                  ? draft.competitors_explicit.join("、")
                  : draft.competitors_discovery_mode
                    ? "Agent 自动发现"
                    : "—"
              }
            />
            {draft.focus_dimensions.length > 0 ? (
              <SummaryRow label="关注维度" value={draft.focus_dimensions.join("、")} />
            ) : null}
            {roleGroups.length > 0 ? (
              <SummaryRow
                label="角色分层"
                value={roleGroups.map((group) => `${group.label}(${group.competitors.length})`).join(" · ")}
              />
            ) : null}
          </>
        )}
      </CardContent>
    </Card>
  );
}

function PlanMetadataCard({
  planTree,
  className,
}: {
  planTree: PlanTree | null;
  className?: string;
}): JSX.Element {
  return (
    <Card className={className}>
      <CardHeader className="pb-3">
        <CardTitle className="inline-flex items-center gap-2 text-base">
          <CheckCircle2 className="h-4 w-4 text-primary" />
          计划信息
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 pt-0 text-xs text-foreground-muted">
        {planTree === null ? (
          <div className="inline-flex items-center gap-2 text-foreground-muted">
            <Loader2 className="h-3 w-3 animate-spin" />
            等待计划发布…
          </div>
        ) : (
          <>
            <SummaryRow label="plan_id" value={<span className="font-mono">{planTree.plan_id}</span>} />
            <SummaryRow label="版本" value={`v${planTree.version}`} />
            <SummaryRow label="任务数" value={String(planTree.tasks.length)} />
            <SummaryRow
              label="状态"
              value={planTree.confirmed_at ? "已确认" : "待确认"}
            />
            {planTree.confirmed_at ? (
              <div className="inline-flex items-center gap-1.5 text-foreground-muted">
                <AlertTriangle className="h-3 w-3" />
                此计划已确认，正在执行。
              </div>
            ) : null}
          </>
        )}
      </CardContent>
    </Card>
  );
}

function SummaryRow({ label, value }: { label: string; value: React.ReactNode }): JSX.Element {
  return (
    <div className="flex items-start justify-between gap-3">
      <span className="text-foreground-muted/80">{label}</span>
      <span className="max-w-[12rem] truncate text-right text-foreground">{value}</span>
    </div>
  );
}
