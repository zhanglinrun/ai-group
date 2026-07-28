import { useEffect, useMemo, useRef, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import { useMemoizedFn } from 'ahooks';
import { getUniqId } from '@/utils';
import { buildAgentStreamRequest } from '@/utils/agentRequest';
import {
  buildConversationTaskData,
  buildTaskFromEventData,
  combineData,
  extractRunMetrics,
  handleTaskData,
  normalizeEventData,
} from '@/utils/chat';
import querySSE from '@/utils/querySSE';
import {
  canonicalEventData,
  isAgentStreamEventMessage,
  parseAgentStreamMessage,
  stageOutputEventData,
  type AgentStreamEvent,
} from '@/utils/sseParsers';
import { isAgentLoopEvent } from '@/utils/agentEvents';
import type {
  ActiveRunState,
  ConversationDraftController,
  ConversationListKey,
  ThrottledStreamController,
} from './chatView.types';
import {
  cloneWorkspaceTask,
  getLatestRenderableTask,
  resolveActionPanelVisibility,
  resolveLatestRunState,
  shouldRefreshWorkspaceTask,
} from './streamState';

type UseConversationStreamOptions = {
  conversation: CHAT.ConversationHistory;
  selectedModelId?: string;
  onConversationChange: (
    conversationId: string,
    nextConversation: CHAT.ConversationHistory,
  ) => void;
  onPrepareStreamingWorkspace?: () => void;
  onTokenUseUp?: () => void;
  onRunSettled?: (sessionId: string) => void;
};

type UseConversationStreamResult = {
  taskList: CHAT.Task[];
  workspaceStreamTask?: CHAT.Task;
  activeRunState?: ActiveRunState;
  setActiveRunState: Dispatch<SetStateAction<ActiveRunState | undefined>>;
  showAction: boolean;
  changeActionStatus: (status: boolean) => void;
  loading: boolean;
  sendMessage: (inputInfo: CHAT.TInputInfo) => void;
  stopCurrentRun: () => void;
  regenerateLastMessage: () => void;
};

function useRafThrottle<TValue>(
  initialValue: TValue,
  interval: number,
  onFlush: (value: TValue) => void,
): ThrottledStreamController<TValue> {
  const frameRef = useRef<number | null>(null);
  const pendingRef = useRef(initialValue);
  const lastFlushAtRef = useRef(0);

  const cancel = useMemoizedFn(() => {
    if (frameRef.current !== null) {
      cancelAnimationFrame(frameRef.current);
      frameRef.current = null;
    }
  });

  const flush = useMemoizedFn((force = false) => {
    const now = performance.now();
    if (!force && now - lastFlushAtRef.current < interval) {
      return;
    }
    lastFlushAtRef.current = now;
    const nextValue = pendingRef.current;
    pendingRef.current = initialValue;
    onFlush(nextValue);
  });

  const schedule = useMemoizedFn(
    (updater: TValue | ((current: TValue) => TValue), force = false) => {
      pendingRef.current =
        typeof updater === 'function'
          ? (updater as (current: TValue) => TValue)(pendingRef.current)
          : updater;

      if (force) {
        cancel();
        flush(true);
        return;
      }

      if (frameRef.current !== null) {
        return;
      }

      const requestNextFrame = () => {
        frameRef.current = requestAnimationFrame(() => {
          frameRef.current = null;
          const now = performance.now();
          if (now - lastFlushAtRef.current < interval) {
            requestNextFrame();
            return;
          }
          flush(true);
        });
      };

      requestNextFrame();
    },
  );

  const reset = useMemoizedFn((value: TValue) => {
    cancel();
    pendingRef.current = value;
    lastFlushAtRef.current = 0;
  });

  return useMemo(
    () => ({
      pendingRef,
      cancel,
      flush,
      schedule,
      reset,
    }),
    [cancel, flush, reset, schedule],
  );
}

function replaceConversationListLastItem<TItem>(
  conversation: CHAT.ConversationHistory,
  key: ConversationListKey,
  item: TItem,
) {
  const nextList = [...(conversation[key] as TItem[])];
  nextList.splice(nextList.length - 1, 1, item);
  return {
    ...conversation,
    [key]: nextList,
  } as CHAT.ConversationHistory;
}

export function createConversationDraftController<TItem>(
  conversationId: string,
  initialConversation: CHAT.ConversationHistory,
  listKey: ConversationListKey,
  commit: (conversationId: string, nextConversation: CHAT.ConversationHistory) => void,
): ConversationDraftController<TItem> {
  let snapshot = initialConversation;

  return {
    conversationId,
    getSnapshot: () => snapshot,
    replaceLastItem: (item) => {
      snapshot = replaceConversationListLastItem(snapshot, listKey, item);
      return snapshot;
    },
    commit: (nextConversation) => {
      snapshot = nextConversation;
      commit(conversationId, snapshot);
    },
  };
}

export function createDraftConversation(
  baseConversation: CHAT.ConversationHistory,
  overrides: Partial<CHAT.ConversationHistory>,
) {
  return {
    ...baseConversation,
    chatTitle: baseConversation.chatTitle || overrides.chatTitle || '',
    title:
      baseConversation.title === '新对话' && overrides.chatTitle
        ? overrides.chatTitle.slice(0, 30)
        : baseConversation.title,
    ...overrides,
  };
}

function createRunningChat(
  inputInfo: CHAT.TInputInfo,
  sessionId: string,
  requestId: string,
): CHAT.ChatItem {
  return {
    query: inputInfo.message!,
    files: inputInfo.files!,
    responseType: 'txt',
    sessionId,
    requestId,
    loading: true,
    forceStop: false,
    tasks: [],
    response: '',
    taskStatus: 0,
    tip: '',
    multiAgent: { tasks: [] },
    agentRun: { status: 'RUNNING', phase: 'ANALYZING', todos: [] },
    metrics: { status: 'RUNNING' },
  };
}

function createDeepResearchWorkspaceTask(requestId: string): MESSAGE.Task {
  const messageTime = String(Date.now());
  const resultMap = {
    messageType: 'deep_research_progress',
    requestId,
    nodeId: 'research_planner',
    role: 'planner',
    status: 'pending',
    progress: 0,
    evidenceCount: 0,
    completedSections: [],
  } as MESSAGE.ResultMap;

  return {
    taskId: requestId,
    messageId: `${requestId}-deep-research`,
    messageTime,
    requestId,
    messageType: 'deep_research_progress',
    resultMap,
    finish: false,
    isFinal: false,
    id: `${requestId}-deep-research`,
  };
}

/**
 * guard error 没有结构化 eventData 时，前端需要补一条失败总结，
 * 否则多智能体对话会停留在 loading 态，看不到明确的失败结论。
 */
export function applyGuardError(currentChat: CHAT.ChatItem, errorText: string): CHAT.ChatItem {
  return applyTerminalRunError(currentChat, 'FAILED', errorText);
}

export type TerminalRunStatus = 'SUCCESS' | 'FAILED' | 'STOPPED' | 'TIMEOUT' | 'RUNNING';

export const MAX_RUN_RECOVERY_ATTEMPTS = 12;
export const MAX_TRANSPORT_RECOVERY_ATTEMPTS = 3;
export const RUN_RECOVERY_DEADLINE_MILLIS = 120_000;
export const TRANSPORT_RECOVERY_DEADLINE_MILLIS = 15_000;
const MIN_RECOVERY_DELAY_MILLIS = 250;

export type RunRecoveryDirective = {
  retry: boolean;
  exhausted: boolean;
  retryable: boolean;
  delayMillis: number;
};

type RecoveryKind = 'run' | 'transport';

export function createRecoveryBudgetTracker(now: () => number = () => Date.now()) {
  const startedAt: Record<RecoveryKind, number | null> = { run: null, transport: null };
  const attempts: Record<RecoveryKind, number> = { run: 0, transport: 0 };

  return {
    completedAttempts: (kind: RecoveryKind) => attempts[kind],
    elapsedMillis: (kind: RecoveryKind) =>
      startedAt[kind] === null ? 0 : Math.max(0, now() - startedAt[kind]!),
    recordAttempt: (kind: RecoveryKind) => {
      if (startedAt[kind] === null) {
        startedAt[kind] = now();
      }
      attempts[kind] += 1;
    },
  };
}

export function createStreamLifecycleController() {
  let generation = 0;
  let settled = false;
  let cancelActiveTransport: (() => void) | null = null;

  const invalidateActiveTransport = () => {
    generation += 1;
    const cancel = cancelActiveTransport;
    cancelActiveTransport = null;
    cancel?.();
  };

  return {
    begin: (): number | null => {
      if (settled) {
        return null;
      }
      invalidateActiveTransport();
      return generation;
    },
    attach: (targetGeneration: number, cancel: () => void) => {
      if (settled || targetGeneration !== generation) {
        cancel();
        return false;
      }
      cancelActiveTransport = cancel;
      return true;
    },
    accepts: (targetGeneration: number) => !settled && targetGeneration === generation,
    cancel: invalidateActiveTransport,
    settle: () => {
      if (settled) {
        return false;
      }
      settled = true;
      invalidateActiveTransport();
      return true;
    },
    isSettled: () => settled,
  };
}

export function createRecoveryTimerController() {
  let timer: ReturnType<typeof setTimeout> | null = null;

  return {
    schedule: (delayMillis: number, callback: () => void) => {
      if (timer !== null) {
        return false;
      }
      timer = setTimeout(() => {
        timer = null;
        callback();
      }, delayMillis);
      return true;
    },
    clear: () => {
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
    },
    hasPending: () => timer !== null,
  };
}

export function createReplayEventDeduper(maxEntries = 2_048) {
  const seen = new Set<string>();
  const insertionOrder: string[] = [];

  const remember = (key: string) => {
    seen.add(key);
    insertionOrder.push(key);
    while (insertionOrder.length > maxEntries) {
      const oldest = insertionOrder.shift();
      if (oldest) {
        seen.delete(oldest);
      }
    }
  };

  return {
    accept: (eventData: MESSAGE.EventData) => {
      const keys: string[] = [];
      const messageId = String(eventData.messageId || '').trim();
      if (messageId) {
        keys.push(`message:${messageId}`);
      }
      if (Number.isFinite(eventData.messageOrder) && eventData.messageOrder > 0) {
        const logicalMessageType =
          String(eventData.resultMap?.messageType || '').trim() || eventData.messageType;
        keys.push(
          `order:${eventData.taskId || 'run'}:${logicalMessageType}:${eventData.messageOrder}`,
        );
      }
      if (keys.some((key) => seen.has(key))) {
        return false;
      }
      keys.forEach(remember);
      return true;
    },
    reset: () => {
      seen.clear();
      insertionOrder.splice(0, insertionOrder.length);
    },
  };
}

export function canRegenerateChat(chat: CHAT.ChatItem | undefined) {
  if (!chat || chat.loading) {
    return false;
  }
  return !isAgentRunBlockingInput(chat.agentRun?.status);
}

export function isAgentRunBlockingInput(status: unknown) {
  return String(status || '').trim().toUpperCase() === 'RUNNING';
}

export function createDurableRecoveryRequest<TBody extends { requestId: string }>(body: TBody) {
  return Object.freeze({ ...body }) as Readonly<TBody>;
}

export function shouldResetForAuthoritativeReplay(
  recoveringCurrentRun: boolean,
  eventData: MESSAGE.EventData | undefined,
  runRecovery: RunRecoveryDirective | null,
) {
  return recoveringCurrentRun && Boolean(eventData) && runRecovery === null;
}

type TerminalRunState = {
  status: TerminalRunStatus;
  message: string;
};

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function firstNonBlankString(...values: unknown[]) {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) {
      return value.trim();
    }
  }
  return '';
}

function firstBoolean(...values: unknown[]): boolean | undefined {
  return values.find((value): value is boolean => typeof value === 'boolean');
}

function firstFiniteNumber(...values: unknown[]): number | undefined {
  for (const value of values) {
    const parsed = typeof value === 'number' ? value : Number(value);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return undefined;
}

/**
 * Detects the durable-claim response that means another transport still owns this request.
 * Retrying is read-only: the same requestId can only observe RUNNING or replay FINISHED.
 */
export function resolveRunRecoveryDirective(
  answer: Pick<MESSAGE.Answer, 'resultMap' | 'errorMsg'>,
  eventData: MESSAGE.EventData | undefined,
  completedAttempts: number,
  elapsedMillis = 0,
): RunRecoveryDirective | null {
  const answerMap = asRecord(answer.resultMap);
  const eventPayload = asRecord(eventData?.resultMap);
  const nestedPayload = asRecord(eventPayload?.resultMap);
  const answerEvent = asRecord(answerMap?.eventData);
  const answerEventPayload = asRecord(answerEvent?.resultMap);
  const sources = [eventPayload, nestedPayload, answerEventPayload, answerMap].filter(
    (source): source is Record<string, unknown> => Boolean(source),
  );

  const stopReason = firstNonBlankString(
    ...sources.map((source) => source.stopReason),
    ...sources.map((source) => source.errorCode),
  );
  if (stopReason !== 'RUN_ALREADY_IN_PROGRESS') {
    return null;
  }

  const retryable = firstBoolean(...sources.map((source) => source.retryable)) !== false;
  const requestedDelay = firstFiniteNumber(
    ...sources.map((source) => source.retryAfterMillis),
  );
  if (!retryable) {
    return {
      retry: false,
      exhausted: false,
      retryable: false,
      delayMillis: 0,
    };
  }

  const remainingMillis = Math.max(0, RUN_RECOVERY_DEADLINE_MILLIS - elapsedMillis);
  const exhausted =
    completedAttempts >= MAX_RUN_RECOVERY_ATTEMPTS ||
    remainingMillis < MIN_RECOVERY_DELAY_MILLIS;
  const serverDelay = Math.min(
    5_000,
    Math.max(MIN_RECOVERY_DELAY_MILLIS, requestedDelay || 1_000),
  );
  const backoffDelay = Math.min(10_000, 1_000 * 2 ** Math.min(completedAttempts, 4));
  const delayMillis = exhausted ? 0 : Math.min(Math.max(serverDelay, backoffDelay), remainingMillis);

  return {
    retry: !exhausted,
    exhausted,
    retryable: true,
    delayMillis,
  };
}

/** Returns a bounded delay for transport recovery, or null for fatal/exhausted errors. */
export function isFatalTransportRecoveryError(error: unknown) {
  const raw = error instanceof Error ? `${error.name} ${error.message}` : String(error || '');
  const normalized = raw.toLowerCase();
  return (
    normalized.includes('abort') ||
    normalized.includes('parse') ||
    normalized.includes('unauthorized') ||
    normalized.includes('forbidden') ||
    normalized.includes('401') ||
    normalized.includes('403') ||
    normalized.includes('quota') ||
    raw.includes('登录') ||
    raw.includes('额度') ||
    raw.includes('配额')
  );
}

export function isTransportRecoveryBudgetExhausted(
  completedAttempts: number,
  elapsedMillis: number,
) {
  return (
    completedAttempts >= MAX_TRANSPORT_RECOVERY_ATTEMPTS ||
    TRANSPORT_RECOVERY_DEADLINE_MILLIS - elapsedMillis < MIN_RECOVERY_DELAY_MILLIS
  );
}

export function resolveTransportRecoveryDelay(
  error: unknown,
  completedAttempts: number,
  elapsedMillis = 0,
): number | null {
  if (isTransportRecoveryBudgetExhausted(completedAttempts, elapsedMillis)) {
    return null;
  }
  if (isFatalTransportRecoveryError(error)) {
    return null;
  }
  const delayMillis = Math.min(2_000, 400 * 2 ** completedAttempts);
  const remainingMillis = TRANSPORT_RECOVERY_DEADLINE_MILLIS - elapsedMillis;
  return remainingMillis < MIN_RECOVERY_DELAY_MILLIS
    ? null
    : Math.min(delayMillis, remainingMillis);
}

function normalizeTerminalRunStatus(value: unknown): TerminalRunStatus | undefined {
  const normalized = String(value || '')
    .trim()
    .toUpperCase();
  if (!normalized) {
    return undefined;
  }
  if (normalized.includes('FAIL') || normalized === 'ERROR') {
    return 'FAILED';
  }
  if (normalized.includes('STOP') || normalized.includes('CANCEL') || normalized === 'ABORTED') {
    return 'STOPPED';
  }
  if (normalized.includes('TIMEOUT') || normalized.includes('TIMED_OUT')) {
    return 'TIMEOUT';
  }
  if (normalized.includes('SUCCESS') || normalized === 'COMPLETED' || normalized === 'DONE') {
    return 'SUCCESS';
  }
  if (normalized.includes('RUNNING') || normalized === 'PROCESSING') {
    return 'RUNNING';
  }
  return undefined;
}

/**
 * 最终状态优先读取事件内的 runStatus/status，再回退 SSE 顶层 status。
 * 旧后端会把所有 finish 帧的顶层 status 写成 success，因此内层明确终态必须优先。
 */
export function resolveTerminalRunState(
  answer: Pick<MESSAGE.Answer, 'status' | 'finished' | 'errorMsg'>,
  eventData?: MESSAGE.EventData,
): TerminalRunState {
  const inner = asRecord(eventData?.resultMap);
  const nested = asRecord(inner?.resultMap);
  const metrics = asRecord(inner?.metrics);
  const status =
    normalizeTerminalRunStatus(inner?.runStatus) ||
    normalizeTerminalRunStatus(nested?.runStatus) ||
    normalizeTerminalRunStatus(inner?.status) ||
    normalizeTerminalRunStatus(nested?.status) ||
    normalizeTerminalRunStatus(metrics?.status) ||
    normalizeTerminalRunStatus(answer.status) ||
    (answer.errorMsg ? 'FAILED' : 'RUNNING');

  const defaultMessage =
    status === 'STOPPED'
      ? '任务已停止，已保留停止前的可见内容。'
      : status === 'TIMEOUT'
        ? '任务执行超时，请稍后重试。'
        : status === 'FAILED'
          ? '任务执行失败，请重试。'
          : '';
  const message = firstNonBlankString(
    answer.errorMsg,
    inner?.errorMsg,
    nested?.errorMsg,
    inner?.errorMessage,
    nested?.errorMessage,
    inner?.message,
    nested?.message,
    status !== 'SUCCESS' ? inner?.taskSummary : undefined,
    status !== 'SUCCESS' ? inner?.result : undefined,
    defaultMessage,
  );

  return { status, message };
}

/**
 * result 只承载最终内容，不能把运行升级为成功。成功必须已经由
 * run_finished(status=SUCCESS, completionGatePassed=true) 建立。
 */
export function resolveAuthoritativeResultStatus(
  currentChat: Pick<CHAT.ChatItem, 'agentRun'>,
  resultStatus: Exclude<TerminalRunStatus, 'RUNNING'>,
): Exclude<TerminalRunStatus, 'RUNNING'> {
  if (resultStatus !== 'SUCCESS') {
    return resultStatus;
  }
  const run = currentChat.agentRun;
  return run?.terminalEventSeen === true &&
    run.status === 'SUCCESS' &&
    run.completionGatePassed === true
    ? 'SUCCESS'
    : 'FAILED';
}

export function applyRunRecoveryPending(
  currentChat: CHAT.ChatItem,
  message: string,
  stopReason = 'RUN_ALREADY_IN_PROGRESS',
): CHAT.ChatItem {
  return {
    ...currentChat,
    loading: false,
    forceStop: false,
    tip: message,
    metrics: {
      ...(currentChat.metrics || {}),
      status: 'RUNNING',
    },
    agentRun: {
      ...(currentChat.agentRun || { todos: [] }),
      status: 'RUNNING',
      stopReason,
    },
  };
}

export function resetChatForAuthoritativeReplay(currentChat: CHAT.ChatItem): CHAT.ChatItem {
  return {
    ...currentChat,
    loading: true,
    forceStop: false,
    tip: '',
    response: '',
    taskStatus: 0,
    tasks: [],
    multiAgent: { tasks: [] },
    conclusion: undefined,
    timeline: [],
    generatedFiles: undefined,
    startedAt: undefined,
    finishedAt: undefined,
    metrics: { status: 'RUNNING' },
    agentRun: {
      runId: currentChat.agentRun?.runId,
      status: 'RUNNING',
      phase: 'ANALYZING',
      todos: [],
    },
  };
}

export function applyTerminalRunError(
  currentChat: CHAT.ChatItem,
  status: Exclude<TerminalRunStatus, 'SUCCESS' | 'RUNNING'>,
  errorText: string,
): CHAT.ChatItem {
  const nextErrorText =
    errorText ||
    (status === 'STOPPED'
      ? '任务已停止，已保留停止前的可见内容。'
      : status === 'TIMEOUT'
        ? '任务执行超时，请稍后重试。'
        : '当前请求处理失败，请稍后重试');
  const conclusion = asRecord(currentChat.conclusion);
  const conclusionResultMap = asRecord(conclusion?.resultMap);
  const hasVisibleConclusion = Boolean(
    firstNonBlankString(
      conclusion?.taskSummary,
      conclusion?.result,
      conclusionResultMap?.taskSummary,
      conclusionResultMap?.result,
    ),
  );
  const fallbackConclusion = {
    id: `${currentChat.requestId}-terminal-${status.toLowerCase()}`,
    messageId: `${currentChat.requestId}-terminal-${status.toLowerCase()}`,
    requestId: currentChat.requestId,
    messageTime: String(Date.now()),
    messageType: 'result',
    finish: true,
    isFinal: true,
    result: nextErrorText,
    resultMap: {
      taskSummary: nextErrorText,
      fileList: [],
      isFinal: true,
      status,
    },
  } as CHAT.Task;

  return {
    ...currentChat,
    loading: false,
    forceStop: status === 'STOPPED',
    tip: nextErrorText,
    metrics: {
      ...(currentChat.metrics || {}),
      status,
    },
    agentRun: {
      ...(currentChat.agentRun || { todos: [] }),
      status,
    },
    conclusion: hasVisibleConclusion ? currentChat.conclusion : fallbackConclusion,
  };
}

function resolveStreamErrorMessage(error: unknown): string {
  const raw =
    error instanceof Error && error.message
      ? error.message
      : typeof error === 'string' && error.trim()
        ? error.trim()
        : '';
  const lower = raw.toLowerCase();

  // 并发限流（后端 per-user 并发上限或线程池繁忙）
  if (
    raw.includes('并发') ||
    raw.includes('上限') ||
    lower.includes('busy') ||
    lower.includes('too many')
  ) {
    return '当前对话请求较多，请稍后重试';
  }
  // 配额不足
  if (raw.includes('配额') || raw.includes('额度') || lower.includes('quota')) {
    return '对话额度不足，请前往额度中心查看或购买额度包';
  }
  // 登录态失效
  if (
    raw.includes('登录') ||
    lower.includes('unauthorized') ||
    lower.includes('401') ||
    lower.includes('token')
  ) {
    return '登录状态已失效，请重新登录后重试';
  }
  // 响应解析失败
  if (lower.includes('parse') || raw.includes('解析')) {
    return '响应解析失败，请稍后重试';
  }
  // 其它：优先展示后端可读中文提示，否则回退通用文案（避免把英文技术栈信息直接抛给用户）
  if (raw && /[\u4e00-\u9fa5]/.test(raw)) {
    return raw;
  }
  return '当前请求处理失败，请稍后重试';
}

export function useConversationStream(
  options: UseConversationStreamOptions,
): UseConversationStreamResult {
  const {
    conversation,
    selectedModelId,
    onConversationChange,
    onPrepareStreamingWorkspace,
    onTokenUseUp,
    onRunSettled,
  } = options;

  const [taskList, setTaskList] = useState<CHAT.Task[]>([]);
  const [workspaceStreamTask, setWorkspaceStreamTask] = useState<CHAT.Task>();
  const [activeRunState, setActiveRunState] = useState<ActiveRunState>();
  const [showAction, setShowAction] = useState(false);
  const [loading, setLoading] = useState(false);
  const conversationRef = useRef(conversation);
  // 持有当前 SSE 流的中断句柄，用于切换会话/卸载时取消旧流。
  const streamAbortRef = useRef<AbortController | null>(null);

  const workspaceTaskThrottle = useRafThrottle<CHAT.Task | undefined>(undefined, 32, (task) =>
    setWorkspaceStreamTask(task),
  );

  const commitConversation = useMemoizedFn(
    (conversationId: string, nextConversation: CHAT.ConversationHistory) => {
      onConversationChange(conversationId, {
        ...nextConversation,
        updatedAt: Date.now(),
      });
    },
  );

  const scheduleWorkspaceStreamTask = useMemoizedFn((chat: CHAT.ChatItem, force = false) => {
    const latestTask = getLatestRenderableTask(chat);
    if (!latestTask) {
      return;
    }

    workspaceTaskThrottle.schedule(cloneWorkspaceTask(latestTask), force);
  });

  const changeActionStatus = useMemoizedFn((status: boolean) => {
    setShowAction(status);
  });

  useEffect(() => {
    conversationRef.current = conversation;
  }, [conversation]);

  useEffect(() => {
    // 切换会话时中断上一会话仍在进行的 SSE 流，避免旧流事件污染新会话。
    streamAbortRef.current?.abort();
    streamAbortRef.current = null;
    workspaceTaskThrottle.reset(undefined);
    setTaskList([]);
    setWorkspaceStreamTask(undefined);
    setActiveRunState(undefined);
    setShowAction(false);
    setLoading(false);
  }, [conversation.id, workspaceTaskThrottle]);

  useEffect(() => {
    if (!conversation.chatList.length || loading) {
      return;
    }

    const latestChatSnapshot = [...conversation.chatList]
      .reverse()
      .find(
        (chat) =>
          (chat.multiAgent?.tasks?.length || 0) > 0 ||
          (chat.agentRun?.todos?.length || 0) > 0 ||
          !!chat.timeline?.length,
      );

    if (!latestChatSnapshot) {
      return;
    }

    const conversationTaskData = buildConversationTaskData(latestChatSnapshot);
    const latestTask = getLatestRenderableTask(conversationTaskData.currentChat);

    setTaskList(conversationTaskData.taskList);
    setWorkspaceStreamTask(latestTask ? cloneWorkspaceTask(latestTask) : undefined);
    setActiveRunState(resolveLatestRunState(latestChatSnapshot));
    setShowAction(resolveActionPanelVisibility({ taskList: conversationTaskData.taskList }));
  }, [conversation.chatList, conversation.id, loading]);

  useEffect(() => {
    const referenceChat = conversation.chatList[conversation.chatList.length - 1];
    if (!referenceChat) {
      setActiveRunState(undefined);
      return;
    }

    setActiveRunState(resolveLatestRunState(referenceChat));
  }, [conversation.chatList]);

  useEffect(() => {
    return () => {
      workspaceTaskThrottle.cancel();
      // 组件卸载 / 路由离开时中断仍在进行的 SSE 流，避免泄漏与跨会话数据污染。
      streamAbortRef.current?.abort();
      streamAbortRef.current = null;
    };
  }, [workspaceTaskThrottle]);

  const sendMessage = useMemoizedFn((inputInfo: CHAT.TInputInfo) => {
    const baseConversation = conversationRef.current;
    const conversationId = baseConversation.id;
    const { message, executionMode, online, outputStyle } = inputInfo;
    const currentOutputStyle = outputStyle || baseConversation.productType;
    const replaceLast = Boolean(
      (inputInfo as CHAT.TInputInfo & { replaceLast?: boolean }).replaceLast,
    );
    // Every explicit send (including "regenerate") is a new execution. Only the
    // transport recovery loop below is allowed to reuse this durable requestId.
    const requestId = getUniqId();
    let currentChat = createRunningChat(inputInfo, baseConversation.sessionId, requestId);
    let initialDeepResearchTaskList: CHAT.Task[] = [];
    if (executionMode === 'DEEP') {
      currentChat.multiAgent.tasks.push([createDeepResearchWorkspaceTask(requestId)]);
      initialDeepResearchTaskList = handleTaskData(currentChat, currentChat.multiAgent).taskList;
    }
    let recoveringCurrentRun = false;
    const streamLifecycle = createStreamLifecycleController();
    const recoveryTimer = createRecoveryTimerController();
    const recoveryBudget = createRecoveryBudgetTracker();
    const replayDeduper = createReplayEventDeduper();

    // 中断上一条仍在进行的流，并为本次请求及其同 requestId 恢复尝试建立统一取消信号。
    streamAbortRef.current?.abort();
    const abortController = new AbortController();
    streamAbortRef.current = abortController;

    const notifyRunSettled = () => {
      if (!streamLifecycle.settle()) {
        return;
      }
      recoveryTimer.clear();
      if (streamAbortRef.current === abortController) {
        streamAbortRef.current = null;
      }
      onRunSettled?.(baseConversation.sessionId);
    };
    abortController.signal.addEventListener(
      'abort',
      () => {
        recoveryTimer.clear();
        streamLifecycle.cancel();
      },
      { once: true },
    );

    const initialConversation = createDraftConversation(baseConversation, {
      chatTitle: message || '',
      productType: currentOutputStyle,
      executionMode,
      chatList: [
        ...(replaceLast ? baseConversation.chatList.slice(0, -1) : baseConversation.chatList),
        { ...currentChat },
      ],
    });
    const draftController = createConversationDraftController<CHAT.ChatItem>(
      conversationId,
      initialConversation,
      'chatList',
      commitConversation,
    );

    draftController.commit(initialConversation);
    setLoading(true);
    onPrepareStreamingWorkspace?.();
    if (initialDeepResearchTaskList.length) {
      setTaskList(initialDeepResearchTaskList);
      setWorkspaceStreamTask(cloneWorkspaceTask(initialDeepResearchTaskList[0]));
      setShowAction(true);
    }

    const syncRunningConversation = () => {
      draftController.commit(draftController.replaceLastItem({ ...currentChat }));
    };

    /**
     * 流式任务会先把原始事件累积在 multiAgent.tasks，再由 handleTaskData 派生出左侧时间线需要的 chat.tasks。
     * 这里在节流刷新任务视图时，把派生后的 chat 一并回写到会话快照，避免左侧对话区一直停留在旧数据。
     */
    const syncDerivedConversationSnapshot = (nextChat: CHAT.ChatItem) => {
      pendingConversation = draftController.replaceLastItem({ ...nextChat });
    };

    const params = createDurableRecoveryRequest(
      buildAgentStreamRequest({
        sessionId: baseConversation.sessionId,
        requestId,
        message,
        executionMode,
        online,
        outputStyle: currentOutputStyle,
        files: inputInfo.files,
        aiAgentId: inputInfo.aiAgentId,
        fallbackRoleAgentId: baseConversation.role?.agentId,
        modelId: inputInfo.modelId,
      }),
    );
    let pendingConversation: CHAT.ConversationHistory | null = null;
    let pendingTaskData: ReturnType<typeof handleTaskData> | null = null;
    let taskDataDirty = false;
    let pendingFlushFrame: number | null = null;
    let lastConversationFlushAt = 0;
    let lastTaskFlushAt = 0;
    const CONVERSATION_FLUSH_INTERVAL = 16;
    const TASK_FLUSH_INTERVAL = 96;

    const flushNonChatUpdates = (force = false) => {
      if (!pendingConversation && !pendingTaskData && !taskDataDirty) {
        return;
      }

      const now = performance.now();
      if (taskDataDirty && (force || now - lastTaskFlushAt >= TASK_FLUSH_INTERVAL)) {
        pendingTaskData = handleTaskData(currentChat, currentChat.multiAgent);
        syncDerivedConversationSnapshot(pendingTaskData.currentChat);
        taskDataDirty = false;
      }

      const shouldFlushConversation =
        !!pendingConversation &&
        (force || now - lastConversationFlushAt >= CONVERSATION_FLUSH_INTERVAL);
      const shouldFlushTask =
        !!pendingTaskData && (force || now - lastTaskFlushAt >= TASK_FLUSH_INTERVAL);

      if (shouldFlushTask && pendingTaskData) {
        setTaskList(pendingTaskData.taskList);
        setShowAction(resolveActionPanelVisibility({ taskList: pendingTaskData.taskList }));
        pendingTaskData = null;
        lastTaskFlushAt = now;
      }

      if (shouldFlushConversation && pendingConversation) {
        commitConversation(conversationId, pendingConversation);
        pendingConversation = null;
        lastConversationFlushAt = now;
      }
    };

    const scheduleNonChatFlush = (force = false) => {
      if (force) {
        if (pendingFlushFrame) {
          cancelAnimationFrame(pendingFlushFrame);
          pendingFlushFrame = null;
        }
        flushNonChatUpdates(true);
        return;
      }

      if (pendingFlushFrame) {
        return;
      }

      pendingFlushFrame = requestAnimationFrame(() => {
        pendingFlushFrame = null;
        flushNonChatUpdates(false);
        if (pendingConversation || pendingTaskData || taskDataDirty) {
          scheduleNonChatFlush(false);
        }
      });
    };

    const scheduleRecovery = (delayMillis: number, tip: string) => {
      if (
        recoveryTimer.hasPending() ||
        abortController.signal.aborted ||
        streamLifecycle.isSettled() ||
        !currentChat.loading
      ) {
        return false;
      }
      streamLifecycle.cancel();
      recoveringCurrentRun = true;
      currentChat = {
        ...currentChat,
        loading: true,
        forceStop: false,
        tip,
        metrics: { ...(currentChat.metrics || {}), status: 'RUNNING' },
        agentRun: {
          ...(currentChat.agentRun || { todos: [] }),
          status: 'RUNNING',
          stopReason: undefined,
        },
      };
      setLoading(true);
      syncRunningConversation();
      recoveryTimer.schedule(delayMillis, () => {
        if (
          !abortController.signal.aborted &&
          !streamLifecycle.isSettled() &&
          currentChat.loading
        ) {
          startStream();
        }
      });
      return true;
    };

    const suspendAutomaticRecovery = (message: string, stopReason: string) => {
      recoveryTimer.clear();
      streamLifecycle.cancel();
      recoveringCurrentRun = false;
      setLoading(false);
      currentChat = applyRunRecoveryPending(currentChat, message, stopReason);
      const taskData = handleTaskData(currentChat, currentChat.multiAgent);
      setTaskList(taskData.taskList);
      draftController.commit(draftController.replaceLastItem({ ...currentChat }));
      setActiveRunState({ status: 'RUNNING', errorMsg: message });
      if (streamAbortRef.current === abortController) {
        streamAbortRef.current = null;
      }
    };

    const resetProjectionForAuthoritativeReplay = () => {
      if (pendingFlushFrame !== null) {
        cancelAnimationFrame(pendingFlushFrame);
        pendingFlushFrame = null;
      }
      pendingConversation = null;
      pendingTaskData = null;
      taskDataDirty = false;
      replayDeduper.reset();
      recoveringCurrentRun = false;
      currentChat = resetChatForAuthoritativeReplay(currentChat);
      workspaceTaskThrottle.reset(undefined);
      setWorkspaceStreamTask(undefined);
      setTaskList([]);
      setShowAction(false);
      setActiveRunState({ status: 'RUNNING' });
      setLoading(true);
      draftController.commit(draftController.replaceLastItem({ ...currentChat }));
    };

    let canonicalMessageOrder = 0;
    const handleCanonicalMessage = (event: AgentStreamEvent) => {
      if (abortController.signal.aborted || streamLifecycle.isSettled()) return;
      if (recoveringCurrentRun && event.type === 'agent_start') {
        resetProjectionForAuthoritativeReplay();
      }
      const eventData = canonicalEventData(event, ++canonicalMessageOrder);
      if (!replayDeduper.accept(eventData)) return;
      currentChat = combineData(eventData, currentChat);

      if (event.type === 'stage_output') {
        const artifactEvent = stageOutputEventData(event, ++canonicalMessageOrder);
        currentChat = combineData(artifactEvent, currentChat);
        taskDataDirty = true;
        scheduleWorkspaceStreamTask(currentChat, event.isFinal);
      }

      const terminal = event.type === 'complete' || event.type === 'error';
      if (event.type === 'complete') {
        if (!currentChat.response && event.summary) currentChat.response = event.summary;
        currentChat.loading = false;
        currentChat.metrics = { ...(currentChat.metrics || {}), status: 'SUCCESS' };
        setLoading(false);
        notifyRunSettled();
      } else if (event.type === 'error') {
        currentChat = applyTerminalRunError(currentChat, 'FAILED', event.message);
        setLoading(false);
        notifyRunSettled();
      }

      draftController.replaceLastItem({ ...currentChat });
      pendingConversation = draftController.getSnapshot();
      scheduleNonChatFlush(terminal);
    };

    const handleMessage = (data: MESSAGE.Answer | AgentStreamEvent) => {
      if (abortController.signal.aborted || streamLifecycle.isSettled()) {
        return;
      }
      if (isAgentStreamEventMessage(data)) {
        handleCanonicalMessage(data);
        return;
      }
      const { finished, resultMap, packageType, status } = data;
      const envelopeEventData = normalizeEventData(resultMap?.eventData);
      const runRecovery = resolveRunRecoveryDirective(
        data,
        envelopeEventData,
        recoveryBudget.completedAttempts('run'),
        recoveryBudget.elapsedMillis('run'),
      );
      if (runRecovery?.retry) {
        if (
          scheduleRecovery(
            runRecovery.delayMillis,
            '当前运行仍在执行，正在使用原请求恢复结果…',
          )
        ) {
          recoveryBudget.recordAttempt('run');
        }
        return;
      }

      if (runRecovery?.exhausted) {
        const recoveryExhaustedText =
          '当前运行仍在后台执行，自动恢复窗口已结束。请稍后重新打开该会话查看结果；为避免重复执行和扣费，当前请求不能重新生成。';
        suspendAutomaticRecovery(recoveryExhaustedText, 'RUN_ALREADY_IN_PROGRESS');
        return;
      }

      if (
        shouldResetForAuthoritativeReplay(
          recoveringCurrentRun,
          envelopeEventData,
          runRecovery,
        )
      ) {
        resetProjectionForAuthoritativeReplay();
      }
      if (envelopeEventData && !replayDeduper.accept(envelopeEventData)) {
        return;
      }
      const envelopeTerminalState = resolveTerminalRunState(data, envelopeEventData);
      const isTerminalEnvelopeError =
        Boolean(finished) &&
        packageType === 'result' &&
        !envelopeEventData &&
        (Boolean(data.errorMsg) ||
          ['FAILED', 'STOPPED', 'TIMEOUT'].includes(envelopeTerminalState.status));

      if (isTerminalEnvelopeError) {
        const terminalStatus =
          envelopeTerminalState.status === 'SUCCESS' || envelopeTerminalState.status === 'RUNNING'
            ? 'FAILED'
            : envelopeTerminalState.status;
        const errorText = envelopeTerminalState.message || '当前请求处理失败，请稍后重试';
        setLoading(false);
        currentChat = applyTerminalRunError(currentChat, terminalStatus, errorText);
        const taskData = handleTaskData(currentChat, currentChat.multiAgent);
        setTaskList(taskData.taskList);
        draftController.commit(draftController.replaceLastItem({ ...currentChat }));
        notifyRunSettled();
        return;
      }

      if (['roleUnavailable', 'roleSwitchRejected', 'noAvailableChatRole'].includes(status)) {
        currentChat = applyTerminalRunError(
          currentChat,
          'FAILED',
          data.errorMsg || '当前角色暂不可用',
        );
        setLoading(false);
        syncRunningConversation();
        notifyRunSettled();
        return;
      }

      if (status === 'tokenUseUp') {
        onTokenUseUp?.();
        const errorText = data.errorMsg || '对话额度不足，请前往额度中心查看或购买额度包';
        currentChat = applyTerminalRunError(currentChat, 'FAILED', errorText);
        const taskData = handleTaskData(currentChat, currentChat.multiAgent);
        setLoading(false);
        setTaskList(taskData.taskList);
        draftController.commit(draftController.replaceLastItem({ ...currentChat }));
        notifyRunSettled();
        return;
      }

      if (packageType === 'heartbeat') {
        return;
      }

      const eventData = envelopeEventData;
      if (!eventData) {
        return;
      }

      currentChat = combineData(eventData, currentChat);
      // 实时收到最终 result 时，优先用结构化结果覆盖掉临时 agent_stream 结论，
      // 避免界面在当前会话里一直停留在“答案$$$文件名”的原始协议文本。
      if (eventData.resultMap?.messageType === 'result') {
        currentChat.conclusion = buildTaskFromEventData(eventData) as CHAT.Task;
      }
      if (shouldRefreshWorkspaceTask(eventData)) {
        scheduleWorkspaceStreamTask(currentChat, finished);
      }
      if (!isAgentLoopEvent(eventData)) {
        taskDataDirty = true;
      }
      if (finished) {
        const terminalState = resolveTerminalRunState(data, eventData);
        const resultStatus = terminalState.status === 'RUNNING' ? 'FAILED' : terminalState.status;
        const terminalStatus = resolveAuthoritativeResultStatus(currentChat, resultStatus);
        currentChat =
          terminalStatus === 'SUCCESS'
            ? { ...currentChat, loading: false }
            : applyTerminalRunError(currentChat, terminalStatus, terminalState.message);
        currentChat.metrics = {
          ...(currentChat.metrics || {}),
          status: terminalStatus,
          ...(extractRunMetrics(eventData.resultMap) || {}),
        };
        if (currentChat.agentRun) {
          currentChat.agentRun.status = terminalStatus;
        }
        setLoading(false);
        notifyRunSettled();
      }

      draftController.replaceLastItem({ ...currentChat });
      pendingConversation = draftController.getSnapshot();
      scheduleNonChatFlush(finished);
    };

    const handleError = (error: unknown) => {
      console.error('SSE stream error', error);
      if (
        abortController.signal.aborted ||
        streamLifecycle.isSettled() ||
        !currentChat.loading
      ) {
        return;
      }
      const completedTransportAttempts = recoveryBudget.completedAttempts('transport');
      const recoveryElapsedMillis = recoveryBudget.elapsedMillis('transport');
      const recoveryDelay = resolveTransportRecoveryDelay(
        error,
        completedTransportAttempts,
        recoveryElapsedMillis,
      );
      if (
        recoveryDelay !== null &&
        scheduleRecovery(recoveryDelay, '连接短暂中断，正在使用原请求恢复当前运行…')
      ) {
        recoveryBudget.recordAttempt('transport');
        return;
      }
      if (
        !isFatalTransportRecoveryError(error) &&
        isTransportRecoveryBudgetExhausted(
          completedTransportAttempts,
          recoveryElapsedMillis,
        )
      ) {
        suspendAutomaticRecovery(
          '连接恢复窗口已结束，但服务端任务可能仍在运行。请稍后重新打开该会话查看结果；为避免重复执行和扣费，当前请求不能重新生成。',
          'TRANSPORT_RECOVERY_EXHAUSTED',
        );
        return;
      }
      const errorText = resolveStreamErrorMessage(error);
      setLoading(false);

      currentChat = applyGuardError(currentChat, errorText);
      const taskData = handleTaskData(currentChat, currentChat.multiAgent);
      setTaskList(taskData.taskList);
      draftController.commit(draftController.replaceLastItem({ ...currentChat }));
      notifyRunSettled();
    };

    const handleClose = () => {
      if (streamLifecycle.isSettled()) {
        return;
      }
      if (abortController.signal.aborted) {
        scheduleNonChatFlush(true);
        return;
      }
      if (recoveryTimer.hasPending()) {
        scheduleNonChatFlush(true);
        return;
      }
      if (currentChat.loading) {
        const completedTransportAttempts = recoveryBudget.completedAttempts('transport');
        const recoveryElapsedMillis = recoveryBudget.elapsedMillis('transport');
        const recoveryDelay = resolveTransportRecoveryDelay(
          new Error('SSE connection closed before a terminal event'),
          completedTransportAttempts,
          recoveryElapsedMillis,
        );
        if (
          recoveryDelay !== null &&
          scheduleRecovery(recoveryDelay, '连接已关闭，正在使用原请求恢复当前运行…')
        ) {
          recoveryBudget.recordAttempt('transport');
          scheduleNonChatFlush(true);
          return;
        }
        suspendAutomaticRecovery(
          '连接恢复窗口已结束，但服务端任务可能仍在运行。请稍后重新打开该会话查看结果；为避免重复执行和扣费，当前请求不能重新生成。',
          'TRANSPORT_RECOVERY_EXHAUSTED',
        );
        return;
      }
      if (streamAbortRef.current === abortController && !recoveryTimer.hasPending()) {
        streamAbortRef.current = null;
      }
      scheduleNonChatFlush(true);
    };

    function startStream() {
      if (abortController.signal.aborted || streamLifecycle.isSettled()) {
        return;
      }
      const generation = streamLifecycle.begin();
      if (generation === null) {
        return;
      }
      const cancelTransport = querySSE({
        body: params,
        parser: parseAgentStreamMessage,
        handleMessage: (data) => {
          if (streamLifecycle.accepts(generation)) {
            handleMessage(data);
          }
        },
        handleError: (error) => {
          if (streamLifecycle.accepts(generation)) {
            handleError(error);
          }
        },
        handleClose: () => {
          if (streamLifecycle.accepts(generation)) {
            handleClose();
          }
        },
        signal: abortController.signal,
      });
      streamLifecycle.attach(generation, cancelTransport);
    }

    startStream();
  });

  const stopCurrentRun = useMemoizedFn(() => {
    const activeStream = streamAbortRef.current;
    if (!activeStream) return;

    // 先断开 SSE，让服务端的 downstream-abort probe 取消模型和工具 Future，
    // 再把停止事实投影到当前对话，避免网页端一直停在 loading。
    activeStream.abort();
    const currentConversation = conversationRef.current;
    const lastChat = currentConversation.chatList[currentConversation.chatList.length - 1];
    if (!lastChat || !lastChat.loading) return;

    const stoppedChat = applyTerminalRunError(
      lastChat,
      'STOPPED',
      '任务已停止，已保留停止前的可见内容。',
    );
    onConversationChange(
      currentConversation.id,
      replaceConversationListLastItem(currentConversation, 'chatList', stoppedChat),
    );
    setLoading(false);
    setActiveRunState({ status: 'STOPPED', errorMsg: '任务已停止' });
    onRunSettled?.(currentConversation.sessionId);
  });

  const regenerateLastMessage = useMemoizedFn(() => {
    const last = conversation.chatList[conversation.chatList.length - 1];
    if (loading || !canRegenerateChat(last)) {
      return;
    }

    sendMessage({
      message: last.query,
      outputStyle: conversation.productType,
      executionMode: conversation.executionMode,
      aiAgentId: conversation.role?.agentId,
      modelId: selectedModelId,
      replaceLast: true,
    } as CHAT.TInputInfo & { replaceLast: boolean });
  });

  return {
    taskList,
    workspaceStreamTask,
    activeRunState,
    setActiveRunState,
    showAction,
    changeActionStatus,
    loading,
    sendMessage,
    stopCurrentRun,
    regenerateLastMessage,
  };
}
