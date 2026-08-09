import { useMemo, useSyncExternalStore } from "react";

import type {
  EvidenceCollectedPayload,
  FollowUpReceivedPayload,
  RunFinishPayload,
  StepFinishEventPayload,
  SupervisorDecisionEventPayload,
  ToolEventPayload,
  ToolFinishEventPayload,
} from "@/api/sse";
import type { PlanTaskStage, PlanTree, StepTraceResponse } from "@/api/types";

type PlanTaskRuntimeStatus = "queued" | "running" | "completed";
type ToolRuntimeStatus = "running" | "done" | "error" | "skipped";

// Task tiles only move forward; both live SSE and persisted backfill upgrade
// monotonically so a poll snapshot can never roll a tile back to queued.
const STATUS_RANK: Record<PlanTaskRuntimeStatus, number> = {
  queued: 0,
  running: 1,
  completed: 2,
};

export interface ToolActivityEntry {
  key: string;
  tool: string;
  competitorId: string | null;
  dimension: string | null;
  argsSummary: Record<string, unknown> | undefined;
  status: ToolRuntimeStatus;
  startedAt: number;
  latencyMs: number | null;
  snippetCount: number | null;
  error: string | null;
}

export interface LiveEvidenceFeedEntry extends EvidenceCollectedPayload {
  status: "candidate" | "persisted";
  snippetCount?: number;
}

export interface LiveRunProgressState {
  planTaskStatus: Record<string, PlanTaskRuntimeStatus>;
  toolActivity: ToolActivityEntry[];
  evidenceFeed: LiveEvidenceFeedEntry[];
  pendingFollowUps: FollowUpReceivedPayload[];
  finishPayload: RunFinishPayload | null;
  lastActivityAt: number;
}

const AGENT_NAME_TO_STAGE: Record<string, PlanTaskStage> = {
  discovery: "discover",
  researcher: "research",
  analyst: "analyze",
  writer: "write",
};

const MAX_TOOL_ENTRIES = 12;
const MAX_EVIDENCE_ENTRIES = 30;

const storeByRunId = new Map<string, LiveRunProgressState>();
const listenersByRunId = new Map<string, Set<() => void>>();

function createInitialState(): LiveRunProgressState {
  return {
    planTaskStatus: {},
    toolActivity: [],
    evidenceFeed: [],
    pendingFollowUps: [],
    finishPayload: null,
    lastActivityAt: Date.now(),
  };
}

function ensureState(runId: string): LiveRunProgressState {
  const existing = storeByRunId.get(runId);
  if (existing !== undefined) {
    return existing;
  }
  const initial = createInitialState();
  storeByRunId.set(runId, initial);
  return initial;
}

function emit(runId: string): void {
  const listeners = listenersByRunId.get(runId);
  if (listeners === undefined) {
    return;
  }
  for (const listener of listeners) {
    listener();
  }
}

function update(runId: string, updater: (prev: LiveRunProgressState) => LiveRunProgressState): void {
  const prev = ensureState(runId);
  const next = updater(prev);
  storeByRunId.set(runId, next);
  emit(runId);
}

function touchActivity(state: LiveRunProgressState): LiveRunProgressState {
  return {
    ...state,
    lastActivityAt: Date.now(),
  };
}

function buildToolKey(payload: ToolEventPayload): string {
  return `${payload.tool}|${payload.competitor_id ?? "-"}|${payload.dimension ?? "-"}|${payload.turn ?? 0}`;
}

function cloneTaskStatusMap(
  prev: Record<string, PlanTaskRuntimeStatus>,
): Record<string, PlanTaskRuntimeStatus> {
  return { ...prev };
}

export function ensureRunTaskStatuses(runId: string, planTree: PlanTree | null): void {
  update(runId, (prev) => {
    if (planTree === null) {
      return prev;
    }
    const nextTaskStatus: Record<string, PlanTaskRuntimeStatus> = {};
    for (const task of planTree.tasks) {
      nextTaskStatus[task.task_id] = prev.planTaskStatus[task.task_id] ?? "queued";
    }
    return {
      ...prev,
      planTaskStatus: nextTaskStatus,
    };
  });
}

export function recordSupervisorDecision(
  runId: string,
  payload: SupervisorDecisionEventPayload,
): void {
  update(runId, (prev) => {
    const next = touchActivity(prev);
    if (payload.plan_task_ids.length > 0) {
      const planTaskStatus = cloneTaskStatusMap(next.planTaskStatus);
      for (const taskId of payload.plan_task_ids) {
        if (planTaskStatus[taskId] !== "completed") {
          planTaskStatus[taskId] = "running";
        }
      }
      next.planTaskStatus = planTaskStatus;
    }
    const consumed = payload.consumed_follow_up_ids;
    if (consumed !== undefined && consumed.length > 0) {
      const consumedSet = new Set(consumed);
      next.pendingFollowUps = next.pendingFollowUps.filter(
        (entry) => !consumedSet.has(entry.follow_up_id),
      );
    }
    return next;
  });
}

export function recordStepFinish(
  runId: string,
  payload: StepFinishEventPayload,
  planTree: PlanTree | null,
): void {
  if (planTree === null) {
    return;
  }
  const targetStage = AGENT_NAME_TO_STAGE[payload.agent_name];
  if (targetStage === undefined) {
    return;
  }
  update(runId, (prev) => {
    const next = touchActivity(prev);
    const planTaskStatus = cloneTaskStatusMap(next.planTaskStatus);
    for (const task of planTree.tasks) {
      if (task.stage !== targetStage) {
        continue;
      }
      if (targetStage === "research") {
        if (payload.competitor_id !== null && task.competitor_id === payload.competitor_id) {
          planTaskStatus[task.task_id] = "completed";
        }
      } else {
        planTaskStatus[task.task_id] = "completed";
      }
    }
    next.planTaskStatus = planTaskStatus;
    return next;
  });
}

function persistedStepRuntimeStatus(status: string): PlanTaskRuntimeStatus | null {
  if (status === "running") {
    return "running";
  }
  // Terminal step states settle the tile. The runtime enum has no failed/skipped
  // tile, and recordStepFinish already collapses them to completed, so match it.
  if (status === "completed" || status === "failed" || status === "degraded" || status === "skipped") {
    return "completed";
  }
  return null;
}

function stepCompetitorId(step: StepTraceResponse): string | null {
  const raw = step.payload?.competitor_id;
  return typeof raw === "string" && raw.length > 0 ? raw : null;
}

/**
 * Reconcile plan-task tiles from persisted steps.
 *
 * The SSE stream has no historical replay, and a deep run reconnects many times
 * over its lifetime, so the live `supervisor.decision` flips that paint a tile
 * "running" are lost on every reconnect/refresh. The polled run trace is the
 * durable source of truth; fold it in (upgrade-only) so an in-flight task shows
 * running even when its decision event arrived before this client connected.
 */
export function backfillTaskStatusesFromSteps(
  runId: string,
  steps: readonly StepTraceResponse[],
  planTree: PlanTree | null,
): void {
  if (planTree === null || steps.length === 0) {
    return;
  }
  update(runId, (prev) => {
    const planTaskStatus = cloneTaskStatusMap(prev.planTaskStatus);
    let changed = false;
    for (const step of steps) {
      const stage = AGENT_NAME_TO_STAGE[step.agent_name];
      if (stage === undefined) {
        continue;
      }
      const derived = persistedStepRuntimeStatus(step.status);
      if (derived === null) {
        continue;
      }
      const competitorId = stage === "research" ? stepCompetitorId(step) : null;
      for (const task of planTree.tasks) {
        if (task.stage !== stage) {
          continue;
        }
        if (stage === "research" && (competitorId === null || task.competitor_id !== competitorId)) {
          continue;
        }
        const current = planTaskStatus[task.task_id] ?? "queued";
        if (STATUS_RANK[derived] > STATUS_RANK[current]) {
          planTaskStatus[task.task_id] = derived;
          changed = true;
        }
      }
    }
    if (!changed) {
      return prev;
    }
    return { ...prev, planTaskStatus };
  });
}

export function recordToolStart(runId: string, payload: ToolEventPayload): void {
  update(runId, (prev) => {
    const next = touchActivity(prev);
    const key = buildToolKey(payload);
    const entry: ToolActivityEntry = {
      key,
      tool: payload.tool,
      competitorId: payload.competitor_id,
      dimension: payload.dimension,
      argsSummary: payload.args_summary,
      status: "running",
      startedAt: Date.now(),
      latencyMs: null,
      snippetCount: null,
      error: null,
    };
    const without = next.toolActivity.filter((item) => item.key !== key);
    next.toolActivity = [entry, ...without].slice(0, MAX_TOOL_ENTRIES);
    return next;
  });
}

export function recordToolFinish(runId: string, payload: ToolFinishEventPayload): void {
  update(runId, (prev) => {
    const next = touchActivity(prev);
    const key = buildToolKey(payload);
    const robotsSkipped = isRobotsBlockedError(payload.error);
    const status: ToolRuntimeStatus = payload.success ? "done" : robotsSkipped ? "skipped" : "error";
    const error = robotsSkipped ? "站点 robots.txt 禁止抓取，已合规跳过" : payload.error;
    const candidateEvidence = buildCandidateEvidenceEntry(key, payload);
    const existingIndex = next.toolActivity.findIndex((entry) => entry.key === key);
    if (existingIndex === -1) {
      const synthesized: ToolActivityEntry = {
        key,
        tool: payload.tool,
        competitorId: payload.competitor_id,
        dimension: payload.dimension,
        argsSummary: payload.args_summary,
        status,
        startedAt: Date.now() - payload.latency_ms,
        latencyMs: payload.latency_ms,
        snippetCount: payload.snippet_count,
        error,
      };
      next.toolActivity = [synthesized, ...next.toolActivity].slice(0, MAX_TOOL_ENTRIES);
      next.evidenceFeed = upsertCandidateEvidence(next.evidenceFeed, candidateEvidence);
      return next;
    }
    const entries = [...next.toolActivity];
    entries[existingIndex] = {
      ...entries[existingIndex],
      status,
      latencyMs: payload.latency_ms,
      snippetCount: payload.snippet_count,
      error,
    };
    next.toolActivity = entries;
    next.evidenceFeed = upsertCandidateEvidence(next.evidenceFeed, candidateEvidence);
    return next;
  });
}

function isRobotsBlockedError(error: string | null): boolean {
  if (error === null) {
    return false;
  }
  const lowered = error.toLowerCase();
  return lowered.includes("blocked_by_robots") || lowered.includes("robots denied");
}

function stringFromArgs(args: Record<string, unknown> | undefined, key: string): string | null {
  const value = args?.[key];
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : null;
}

function firstSourceType(distribution: Record<string, number>): string | null {
  const [first] = Object.entries(distribution)
    .filter(([, count]) => count > 0)
    .sort((left, right) => right[1] - left[1]);
  return first?.[0] ?? null;
}

function buildCandidateEvidenceEntry(
  key: string,
  payload: ToolFinishEventPayload,
): LiveEvidenceFeedEntry | null {
  if (!payload.success || payload.snippet_count <= 0) {
    return null;
  }
  const sourceUrl = stringFromArgs(payload.args_summary, "url");
  const query = stringFromArgs(payload.args_summary, "query");
  return {
    evidence_id: `candidate:${key}`,
    competitor_id: payload.competitor_id,
    dimension: payload.dimension,
    source_type: firstSourceType(payload.source_type_distribution) ?? payload.tool,
    source_title: payload.snippet_preview ?? query ?? sourceUrl ?? "候选证据片段正在筛选",
    source_url: sourceUrl,
    desensitized: false,
    status: "candidate",
    snippetCount: payload.snippet_count,
  };
}

function upsertCandidateEvidence(
  entries: LiveEvidenceFeedEntry[],
  candidate: LiveEvidenceFeedEntry | null,
): LiveEvidenceFeedEntry[] {
  if (candidate === null) {
    return entries;
  }
  const without = entries.filter((entry) => entry.evidence_id !== candidate.evidence_id);
  return [candidate, ...without].slice(0, MAX_EVIDENCE_ENTRIES);
}

export function recordEvidenceCollected(runId: string, payload: EvidenceCollectedPayload): void {
  update(runId, (prev) => {
    const next = touchActivity(prev);
    if (next.evidenceFeed.some((entry) => entry.evidence_id === payload.evidence_id)) {
      return next;
    }
    const persisted: LiveEvidenceFeedEntry = {
      ...payload,
      status: "persisted",
    };
    const withoutStaleCandidates = next.evidenceFeed.filter((entry) => {
      if (entry.evidence_id === payload.evidence_id) {
        return false;
      }
      return !(entry.status === "candidate" && payload.source_url !== null && entry.source_url === payload.source_url);
    });
    next.evidenceFeed = [persisted, ...withoutStaleCandidates].slice(0, MAX_EVIDENCE_ENTRIES);
    return next;
  });
}

export function recordFollowUpReceived(runId: string, payload: FollowUpReceivedPayload): void {
  update(runId, (prev) => {
    const next = touchActivity(prev);
    if (next.pendingFollowUps.some((entry) => entry.follow_up_id === payload.follow_up_id)) {
      return next;
    }
    next.pendingFollowUps = [...next.pendingFollowUps, payload];
    return next;
  });
}

export function recordRunFinish(runId: string, payload: RunFinishPayload): void {
  update(runId, (prev) => ({
    ...prev,
    finishPayload: payload,
  }));
}

export function useLiveRunProgress(runId: string): LiveRunProgressState {
  const subscribe = useMemo(
    () => (listener: () => void): (() => void) => {
      const listeners = listenersByRunId.get(runId) ?? new Set<() => void>();
      listeners.add(listener);
      listenersByRunId.set(runId, listeners);
      return () => {
        const current = listenersByRunId.get(runId);
        if (current === undefined) {
          return;
        }
        current.delete(listener);
        if (current.size === 0) {
          listenersByRunId.delete(runId);
        }
      };
    },
    [runId],
  );
  const getSnapshot = useMemo(
    () => (): LiveRunProgressState => ensureState(runId),
    [runId],
  );
  const getServerSnapshot = useMemo(() => createInitialState, []);
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}

