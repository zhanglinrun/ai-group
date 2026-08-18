import { useEffect } from "react";

import { API_BASE_URL } from "@/api/client";
import { queryClient } from "@/api/queryClient";

const RUN_RECONNECT_HINT_MS = 15_000;
const RUN_FALLBACK_POLL_MS = 10_000;

export function getRunFallbackPollMs(): number {
  return RUN_FALLBACK_POLL_MS;
}

export interface IntakeClarifyEventPayload {
  turn: number;
  question: string;
  field_targets: string[];
  suggested_options: string[];
  suggested_answer: string | null;
  draft_complete: boolean;
  // Live draft snapshot the FE checklist consumes for immediate state-driven
  // updates. Backend started sending this after the "checklist never updates"
  // race condition; older runs may omit it (treated as undefined).
  draft?: Record<string, unknown>;
}

export interface IntakeUserReplyPayload {
  turn: number;
  reply_text: string;
  reply_options: string[];
  // Post-merge draft snapshot emitted by intake_wait_node, so FE can paint the
  // checklist the instant the user sends, without waiting for the next turn.
  draft?: Record<string, unknown>;
}

export interface IntakeCompletePayload {
  turn: number;
  draft: Record<string, unknown>;
}

export interface PlanPublishedPayload {
  plan_id: string;
  version: number;
  task_count: number;
}

export type PlanReconciledPayload = PlanPublishedPayload;
export type PlanRevisedPayload = PlanPublishedPayload;

export interface PlanConfirmedPayload {
  plan_id: string;
  version: number;
  kept_task_count: number;
  disabled_task_ids: string[];
  confirmed_at: string | null;
}

export interface ToolEventPayload {
  tool: string;
  competitor_id: string | null;
  dimension: string | null;
  turn?: number;
  args_summary?: Record<string, unknown>;
}

export interface ToolFinishEventPayload extends ToolEventPayload {
  success: boolean;
  snippet_count: number;
  snippet_preview: string | null;
  source_type_distribution: Record<string, number>;
  latency_ms: number;
  error: string | null;
}

export interface EvidenceCollectedPayload {
  evidence_id: string;
  competitor_id: string | null;
  dimension: string | null;
  source_type: string | null;
  source_title: string | null;
  source_url: string | null;
  desensitized: boolean;
}

export interface SupervisorDecisionEventPayload {
  iteration: number;
  chosen_tool: string;
  triggered_by: string;
  outcome: string;
  plan_task_ids: string[];
  // Phase 4: ids the supervisor consumed in this turn. FE uses this to drop
  // matching entries from the local "pending instructions" chip list.
  consumed_follow_up_ids?: string[];
}

export interface FollowUpReceivedPayload {
  follow_up_id: string;
  text: string;
  applies_to_stage: string | null;
  received_at: string;
}

export interface RunFinishPayload {
  run_id: string;
  // Backend's terminal RUN_FINISH payload. error_type / error_message are only
  // present on failed / cancelled outcomes; consumers use them to drive the
  // top-of-page Alert banner instead of leaving the user staring at a stale
  // "running" UI.
  status: "completed" | "degraded" | "failed" | "cancelled" | string;
  error_type?: string;
  error_message?: string;
  status_reason?: string;
}

export interface StepFinishEventPayload {
  agent_name: string;
  status?: string;
  competitor_id?: string | null;
  evidence_count?: number;
  discovered_competitors?: string[];
}

export interface RunEventsOptions {
  // Phase 1b: surface intake event payloads to callers that drive the chat
  // page (NewRunChatPage). Generic runs that do not care about intake leave
  // these undefined and continue with cache invalidation only.
  onIntakeClarify?: (payload: IntakeClarifyEventPayload) => void;
  onIntakeUserReply?: (payload: IntakeUserReplyPayload) => void;
  onIntakeComplete?: (payload: IntakeCompletePayload) => void;
  // Phase 2: PlanConfirmPage subscribes to plan.published to render the plan
  // the moment it appears (instead of polling Run.plan_tree). plan.confirmed
  // is forwarded so the page can navigate to the run view once the executor
  // takes over.
  onPlanPublished?: (payload: PlanPublishedPayload) => void;
  onPlanConfirmed?: (payload: PlanConfirmedPayload) => void;
  onPlanRevised?: (payload: PlanRevisedPayload) => void;
  // Phase 3: LiveRunPage subscribes to tool / evidence / supervisor.decision /
  // step.finish to drive the right-side evidence feed, the left-side plan
  // tree progress, and the top status bar. Listeners are additive — pages
  // that don't pass callbacks just rely on cache invalidation as before.
  onToolStart?: (payload: ToolEventPayload) => void;
  onToolFinish?: (payload: ToolFinishEventPayload) => void;
  onEvidenceCollected?: (payload: EvidenceCollectedPayload) => void;
  onSupervisorDecision?: (payload: SupervisorDecisionEventPayload) => void;
  onStepFinish?: (payload: StepFinishEventPayload) => void;
  // Phase 4: LiveRunPage shows a toast / pending-instruction chip when a new
  // follow-up is accepted (either by the local user or another tab).
  onFollowUpReceived?: (payload: FollowUpReceivedPayload) => void;
  // Terminal-state hook (failed / cancelled / completed). LiveRunPage uses it
  // to render a reason banner; pages that only need cache invalidation can
  // omit it and rely on the default queryClient.invalidate call.
  onRunFinish?: (payload: RunFinishPayload) => void;
}

interface RunEventEnvelope {
  payload?: unknown;
}

interface RunEventsSubscriber {
  options: RunEventsOptions;
}

interface SharedRunEventsChannel {
  runId: string;
  eventSource: EventSource;
  refCount: number;
  teardownTimerId: number | null;
  subscribers: Set<RunEventsSubscriber>;
}

const RUN_EVENTS_CHANNELS = new Map<string, SharedRunEventsChannel>();
const RUN_STREAM_TEARDOWN_DELAY_MS = 15_000;

function parseEventPayload(rawData: string): unknown {
  try {
    const envelope = JSON.parse(rawData) as RunEventEnvelope;
    return envelope.payload ?? null;
  } catch {
    return null;
  }
}

function coerceDraftRecord(value: unknown): Record<string, unknown> | undefined {
  if (value === null || typeof value !== "object") {
    return undefined;
  }
  return value as Record<string, unknown>;
}

function coerceIntakeClarifyPayload(value: unknown): IntakeClarifyEventPayload | null {
  if (value === null || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const question = record.question;
  if (typeof question !== "string" || question.length === 0) {
    return null;
  }
  const fieldTargetsRaw = record.field_targets;
  const fieldTargets = Array.isArray(fieldTargetsRaw)
    ? fieldTargetsRaw.filter((item): item is string => typeof item === "string")
    : [];
  const suggestedRaw = record.suggested_options;
  const suggestedOptions = Array.isArray(suggestedRaw)
    ? suggestedRaw.filter((item): item is string => typeof item === "string")
    : [];
  const suggestedAnswerRaw = record.suggested_answer;
  const suggestedAnswer = typeof suggestedAnswerRaw === "string" ? suggestedAnswerRaw : null;
  const turnRaw = record.turn;
  const turn = typeof turnRaw === "number" ? turnRaw : 0;
  const draftComplete = record.draft_complete === true;
  return {
    turn,
    question,
    field_targets: fieldTargets,
    suggested_options: suggestedOptions,
    suggested_answer: suggestedAnswer,
    draft_complete: draftComplete,
    draft: coerceDraftRecord(record.draft),
  };
}

function coerceIntakeUserReplyPayload(value: unknown): IntakeUserReplyPayload | null {
  if (value === null || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const turnRaw = record.turn;
  const turn = typeof turnRaw === "number" ? turnRaw : 0;
  const replyTextRaw = record.reply_text;
  const replyText = typeof replyTextRaw === "string" ? replyTextRaw : "";
  const replyOptionsRaw = record.reply_options;
  const replyOptions = Array.isArray(replyOptionsRaw)
    ? replyOptionsRaw.filter((item): item is string => typeof item === "string")
    : [];
  return {
    turn,
    reply_text: replyText,
    reply_options: replyOptions,
    draft: coerceDraftRecord(record.draft),
  };
}

function coerceRunFinishPayload(value: unknown): RunFinishPayload | null {
  if (value === null || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const runIdRaw = record.run_id;
  const statusRaw = record.status;
  if (typeof runIdRaw !== "string" || typeof statusRaw !== "string") {
    return null;
  }
  const errorTypeRaw = record.error_type;
  const errorMessageRaw = record.error_message;
  const statusReasonRaw = record.status_reason;
  return {
    run_id: runIdRaw,
    status: statusRaw,
    error_type: typeof errorTypeRaw === "string" ? errorTypeRaw : undefined,
    error_message: typeof errorMessageRaw === "string" ? errorMessageRaw : undefined,
    status_reason: typeof statusReasonRaw === "string" ? statusReasonRaw : undefined,
  };
}

function coerceIntakeCompletePayload(value: unknown): IntakeCompletePayload | null {
  if (value === null || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const turnRaw = record.turn;
  const turn = typeof turnRaw === "number" ? turnRaw : 0;
  const draftRaw = record.draft;
  const draft = draftRaw !== null && typeof draftRaw === "object" ? (draftRaw as Record<string, unknown>) : {};
  return { turn, draft };
}

function coercePlanPublishedPayload(value: unknown): PlanPublishedPayload | null {
  if (value === null || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const planIdRaw = record.plan_id;
  if (typeof planIdRaw !== "string" || planIdRaw.length === 0) {
    return null;
  }
  const versionRaw = record.version;
  const taskCountRaw = record.task_count;
  return {
    plan_id: planIdRaw,
    version: typeof versionRaw === "number" ? versionRaw : 1,
    task_count: typeof taskCountRaw === "number" ? taskCountRaw : 0,
  };
}

function coerceToolStartPayload(value: unknown): ToolEventPayload | null {
  if (value === null || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const toolRaw = record.tool;
  if (typeof toolRaw !== "string" || toolRaw.length === 0) {
    return null;
  }
  const competitorRaw = record.competitor_id;
  const dimensionRaw = record.dimension;
  const turnRaw = record.turn;
  const argsRaw = record.args_summary;
  return {
    tool: toolRaw,
    competitor_id: typeof competitorRaw === "string" ? competitorRaw : null,
    dimension: typeof dimensionRaw === "string" ? dimensionRaw : null,
    turn: typeof turnRaw === "number" ? turnRaw : undefined,
    args_summary:
      argsRaw !== null && typeof argsRaw === "object" ? (argsRaw as Record<string, unknown>) : undefined,
  };
}

function coerceToolFinishPayload(value: unknown): ToolFinishEventPayload | null {
  const base = coerceToolStartPayload(value);
  if (base === null) {
    return null;
  }
  const record = value as Record<string, unknown>;
  const successRaw = record.success;
  const snippetCountRaw = record.snippet_count;
  const snippetPreviewRaw = record.snippet_preview;
  const sourceTypeDistributionRaw = record.source_type_distribution;
  const latencyRaw = record.latency_ms;
  const errorRaw = record.error;
  return {
    ...base,
    success: successRaw === true,
    snippet_count: typeof snippetCountRaw === "number" ? snippetCountRaw : 0,
    snippet_preview: typeof snippetPreviewRaw === "string" ? snippetPreviewRaw : null,
    source_type_distribution: coerceSourceTypeDistribution(sourceTypeDistributionRaw),
    latency_ms: typeof latencyRaw === "number" ? latencyRaw : 0,
    error: typeof errorRaw === "string" ? errorRaw : null,
  };
}

function coerceSourceTypeDistribution(value: unknown): Record<string, number> {
  if (value === null || typeof value !== "object") {
    return {};
  }
  const output: Record<string, number> = {};
  for (const [key, count] of Object.entries(value as Record<string, unknown>)) {
    if (typeof count === "number") {
      output[key] = count;
    }
  }
  return output;
}

function coerceEvidenceCollectedPayload(value: unknown): EvidenceCollectedPayload | null {
  if (value === null || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const evidenceIdRaw = record.evidence_id;
  if (typeof evidenceIdRaw !== "string" || evidenceIdRaw.length === 0) {
    return null;
  }
  const competitorRaw = record.competitor_id;
  const dimensionRaw = record.dimension;
  const sourceTypeRaw = record.source_type;
  const sourceTitleRaw = record.source_title;
  const sourceUrlRaw = record.source_url;
  return {
    evidence_id: evidenceIdRaw,
    competitor_id: typeof competitorRaw === "string" ? competitorRaw : null,
    dimension: typeof dimensionRaw === "string" ? dimensionRaw : null,
    source_type: typeof sourceTypeRaw === "string" ? sourceTypeRaw : null,
    source_title: typeof sourceTitleRaw === "string" ? sourceTitleRaw : null,
    source_url: typeof sourceUrlRaw === "string" ? sourceUrlRaw : null,
    desensitized: record.desensitized === true,
  };
}

function coerceSupervisorDecisionPayload(value: unknown): SupervisorDecisionEventPayload | null {
  if (value === null || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const chosenToolRaw = record.chosen_tool;
  if (typeof chosenToolRaw !== "string" || chosenToolRaw.length === 0) {
    return null;
  }
  const iterationRaw = record.iteration;
  const triggeredByRaw = record.triggered_by;
  const outcomeRaw = record.outcome;
  const planTaskIdsRaw = record.plan_task_ids;
  const planTaskIds = Array.isArray(planTaskIdsRaw)
    ? planTaskIdsRaw.filter((item): item is string => typeof item === "string")
    : [];
  const consumedRaw = record.consumed_follow_up_ids;
  const consumedFollowUpIds = Array.isArray(consumedRaw)
    ? consumedRaw.filter((item): item is string => typeof item === "string")
    : undefined;
  return {
    iteration: typeof iterationRaw === "number" ? iterationRaw : 0,
    chosen_tool: chosenToolRaw,
    triggered_by: typeof triggeredByRaw === "string" ? triggeredByRaw : "unknown",
    outcome: typeof outcomeRaw === "string" ? outcomeRaw : "unknown",
    plan_task_ids: planTaskIds,
    consumed_follow_up_ids: consumedFollowUpIds,
  };
}

function coerceFollowUpReceivedPayload(value: unknown): FollowUpReceivedPayload | null {
  if (value === null || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const followUpIdRaw = record.follow_up_id;
  if (typeof followUpIdRaw !== "string" || followUpIdRaw.length === 0) {
    return null;
  }
  const textRaw = record.text;
  if (typeof textRaw !== "string") {
    return null;
  }
  const receivedAtRaw = record.received_at;
  const stageRaw = record.applies_to_stage;
  return {
    follow_up_id: followUpIdRaw,
    text: textRaw,
    applies_to_stage: typeof stageRaw === "string" ? stageRaw : null,
    received_at: typeof receivedAtRaw === "string" ? receivedAtRaw : "",
  };
}

function coerceStepFinishPayload(value: unknown): StepFinishEventPayload | null {
  if (value === null || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const agentNameRaw = record.agent_name;
  if (typeof agentNameRaw !== "string" || agentNameRaw.length === 0) {
    return null;
  }
  const competitorRaw = record.competitor_id;
  const statusRaw = record.status;
  const evidenceCountRaw = record.evidence_count;
  const discoveredRaw = record.discovered_competitors;
  return {
    agent_name: agentNameRaw,
    status: typeof statusRaw === "string" ? statusRaw : undefined,
    competitor_id: typeof competitorRaw === "string" ? competitorRaw : null,
    evidence_count: typeof evidenceCountRaw === "number" ? evidenceCountRaw : undefined,
    discovered_competitors: Array.isArray(discoveredRaw)
      ? discoveredRaw.filter((item): item is string => typeof item === "string")
      : undefined,
  };
}

function coercePlanConfirmedPayload(value: unknown): PlanConfirmedPayload | null {
  if (value === null || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const planIdRaw = record.plan_id;
  if (typeof planIdRaw !== "string" || planIdRaw.length === 0) {
    return null;
  }
  const versionRaw = record.version;
  const keptRaw = record.kept_task_count;
  const disabledRaw = record.disabled_task_ids;
  const disabledTaskIds = Array.isArray(disabledRaw)
    ? disabledRaw.filter((item): item is string => typeof item === "string")
    : [];
  const confirmedAtRaw = record.confirmed_at;
  return {
    plan_id: planIdRaw,
    version: typeof versionRaw === "number" ? versionRaw : 2,
    kept_task_count: typeof keptRaw === "number" ? keptRaw : 0,
    disabled_task_ids: disabledTaskIds,
    confirmed_at: typeof confirmedAtRaw === "string" ? confirmedAtRaw : null,
  };
}

function dispatchToSubscribers(
  channel: SharedRunEventsChannel,
  invoke: (options: RunEventsOptions) => void,
): void {
  for (const subscriber of channel.subscribers) {
    invoke(subscriber.options);
  }
}

function createRunEventsChannel(runId: string): SharedRunEventsChannel {
  const eventsUrl = `${API_BASE_URL}/api/bff/agent/runs/${runId}/events`;
  const eventSource = new EventSource(eventsUrl);
  const channel: SharedRunEventsChannel = {
    runId,
    eventSource,
    refCount: 0,
    teardownTimerId: null,
    subscribers: new Set<RunEventsSubscriber>(),
  };

  const invalidateRunDetail = (): void => {
    void queryClient.invalidateQueries({ queryKey: ["run-detail", runId] });
  };
  const invalidateRunTrace = (): void => {
    void queryClient.invalidateQueries({ queryKey: ["run-trace", runId] });
  };
  const invalidateRunMetrics = (): void => {
    void queryClient.invalidateQueries({ queryKey: ["run-metrics", runId] });
  };
  const invalidateRunReport = (): void => {
    void queryClient.invalidateQueries({ queryKey: ["run-report", runId] });
    void queryClient.invalidateQueries({ queryKey: ["run-conclusions", runId] });
    void queryClient.invalidateQueries({ queryKey: ["run-comparisons", runId] });
  };
  const invalidateRunEvidence = (): void => {
    void queryClient.invalidateQueries({ queryKey: ["run-evidence", runId] });
  };
  const onFallbackMessage = (): void => {
    invalidateRunDetail();
    invalidateRunTrace();
  };

  eventSource.onmessage = onFallbackMessage;
  eventSource.addEventListener("step.start", () => {
    invalidateRunDetail();
    invalidateRunTrace();
  });
  eventSource.addEventListener("step.finish", (event: MessageEvent<string>) => {
    invalidateRunDetail();
    invalidateRunTrace();
    invalidateRunMetrics();
    const payload = coerceStepFinishPayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onStepFinish?.(payload);
    });
  });
  eventSource.addEventListener("qa.outcome", () => {
    invalidateRunDetail();
    invalidateRunTrace();
    invalidateRunMetrics();
  });
  eventSource.addEventListener("supervisor.decision", (event: MessageEvent<string>) => {
    invalidateRunTrace();
    const payload = coerceSupervisorDecisionPayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onSupervisorDecision?.(payload);
    });
  });
  eventSource.addEventListener("followup.received", (event: MessageEvent<string>) => {
    const payload = coerceFollowUpReceivedPayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onFollowUpReceived?.(payload);
    });
  });
  eventSource.addEventListener("curator.finish", () => {
    void queryClient.invalidateQueries({ queryKey: ["skill-candidates"] });
  });
  eventSource.addEventListener("run.finish", (event: MessageEvent<string>) => {
    invalidateRunDetail();
    invalidateRunMetrics();
    invalidateRunReport();
    const payload = coerceRunFinishPayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onRunFinish?.(payload);
    });
  });
  eventSource.addEventListener("intake.clarify_request", (event: MessageEvent<string>) => {
    invalidateRunDetail();
    const payload = coerceIntakeClarifyPayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onIntakeClarify?.(payload);
    });
  });
  eventSource.addEventListener("intake.user_reply", (event: MessageEvent<string>) => {
    const payload = coerceIntakeUserReplyPayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onIntakeUserReply?.(payload);
    });
  });
  eventSource.addEventListener("intake.complete", (event: MessageEvent<string>) => {
    invalidateRunDetail();
    const payload = coerceIntakeCompletePayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onIntakeComplete?.(payload);
    });
  });
  eventSource.addEventListener("plan.published", (event: MessageEvent<string>) => {
    invalidateRunDetail();
    invalidateRunTrace();
    const payload = coercePlanPublishedPayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onPlanPublished?.(payload);
    });
  });
  eventSource.addEventListener("plan.confirmed", (event: MessageEvent<string>) => {
    invalidateRunDetail();
    invalidateRunTrace();
    const payload = coercePlanConfirmedPayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onPlanConfirmed?.(payload);
    });
  });
  eventSource.addEventListener("plan.reconciled", () => {
    invalidateRunDetail();
    invalidateRunTrace();
  });
  eventSource.addEventListener("plan.revised", (event: MessageEvent<string>) => {
    invalidateRunDetail();
    invalidateRunTrace();
    const payload = coercePlanPublishedPayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onPlanRevised?.(payload);
    });
  });
  eventSource.addEventListener("tool.start", (event: MessageEvent<string>) => {
    const payload = coerceToolStartPayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onToolStart?.(payload);
    });
  });
  eventSource.addEventListener("tool.finish", (event: MessageEvent<string>) => {
    const payload = coerceToolFinishPayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onToolFinish?.(payload);
    });
  });
  eventSource.addEventListener("evidence.collected", (event: MessageEvent<string>) => {
    invalidateRunEvidence();
    const payload = coerceEvidenceCollectedPayload(parseEventPayload(event.data));
    if (payload === null) {
      return;
    }
    dispatchToSubscribers(channel, (options) => {
      options.onEvidenceCollected?.(payload);
    });
  });
  eventSource.addEventListener("error", () => {
    // Browser-side EventSource handles retry; backend hints 15s.
    const _retryHintMs = RUN_RECONNECT_HINT_MS;
    void _retryHintMs;
  });

  return channel;
}

function getOrCreateRunEventsChannel(runId: string): SharedRunEventsChannel {
  const existing = RUN_EVENTS_CHANNELS.get(runId);
  if (existing !== undefined) {
    return existing;
  }
  const created = createRunEventsChannel(runId);
  RUN_EVENTS_CHANNELS.set(runId, created);
  return created;
}

function subscribeRunEvents(runId: string, options: RunEventsOptions): () => void {
  const channel = getOrCreateRunEventsChannel(runId);
  if (channel.teardownTimerId !== null) {
    window.clearTimeout(channel.teardownTimerId);
    channel.teardownTimerId = null;
  }
  const subscriber: RunEventsSubscriber = { options };
  channel.subscribers.add(subscriber);
  channel.refCount += 1;

  return () => {
    channel.subscribers.delete(subscriber);
    channel.refCount = Math.max(0, channel.refCount - 1);
    if (channel.refCount > 0) {
      return;
    }
    channel.teardownTimerId = window.setTimeout(() => {
      if (channel.refCount > 0) {
        return;
      }
      channel.eventSource.close();
      RUN_EVENTS_CHANNELS.delete(channel.runId);
      channel.teardownTimerId = null;
    }, RUN_STREAM_TEARDOWN_DELAY_MS);
  };
}

export function useRunEvents(runId: string, options: RunEventsOptions = {}): void {
  const {
    onIntakeClarify,
    onIntakeUserReply,
    onIntakeComplete,
    onPlanPublished,
    onPlanConfirmed,
    onPlanRevised,
    onToolStart,
    onToolFinish,
    onEvidenceCollected,
    onSupervisorDecision,
    onStepFinish,
    onFollowUpReceived,
    onRunFinish,
  } = options;
  useEffect(() => {
    if (!runId) {
      return;
    }
    return subscribeRunEvents(runId, options);
  }, [
    runId,
    onIntakeClarify,
    onIntakeUserReply,
    onIntakeComplete,
    onPlanPublished,
    onPlanConfirmed,
    onPlanRevised,
    onToolStart,
    onToolFinish,
    onEvidenceCollected,
    onSupervisorDecision,
    onStepFinish,
    onFollowUpReceived,
    onRunFinish,
  ]);
}
