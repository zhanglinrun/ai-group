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
import { parseAgentAnswer } from '@/utils/sseParsers';
import { applyCheckpointControlEvent, parseCheckpointControlEvent } from '@/utils/checkpoint';
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
  plan?: CHAT.Plan;
  showAction: boolean;
  changeActionStatus: (status: boolean) => void;
  loading: boolean;
  streamingThoughtMap: Record<string, string>;
  sendMessage: (inputInfo: CHAT.TInputInfo) => void;
  resumeFromCheckpoint: (
    sourceChat: CHAT.ChatItem,
    decision: CHAT.CheckpointResumeDecision,
  ) => void;
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
  outputStyle?: string,
  deepThink?: boolean,
): CHAT.ChatItem {
  return {
    query: inputInfo.message!,
    files: inputInfo.files!,
    responseType: 'txt',
    sessionId,
    requestId,
    agentType: outputStyle === 'chat' ? 0 : deepThink ? 1 : 2,
    loading: true,
    forceStop: false,
    tasks: [],
    thought: '',
    response: '',
    taskStatus: 0,
    tip: '',
    multiAgent: { tasks: [] },
    metrics: { status: 'RUNNING' },
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
  if (
    normalized.includes('STOP') ||
    normalized.includes('CANCEL') ||
    normalized === 'ABORTED'
  ) {
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
    (answer.errorMsg ? 'FAILED' : answer.finished ? 'SUCCESS' : 'RUNNING');

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
  const [plan, setPlan] = useState<CHAT.Plan>();
  const [showAction, setShowAction] = useState(false);
  const [loading, setLoading] = useState(false);
  const [streamingThoughtMap, setStreamingThoughtMap] = useState<Record<string, string>>({});
  const conversationRef = useRef(conversation);
  // 持有当前 SSE 流的中断句柄，用于切换会话/卸载时取消旧流。
  const streamAbortRef = useRef<AbortController | null>(null);

  const workspaceTaskThrottle = useRafThrottle<CHAT.Task | undefined>(undefined, 32, (task) =>
    setWorkspaceStreamTask(task),
  );
  const thoughtThrottle = useRafThrottle<Record<string, string>>({}, 48, (pendingThoughtMap) => {
    const pendingEntries = Object.entries(pendingThoughtMap);
    if (!pendingEntries.length) {
      return;
    }

    setStreamingThoughtMap((previous) => {
      let changed = false;
      const next = { ...previous };

      pendingEntries.forEach(([requestId, thought]) => {
        if (next[requestId] !== thought) {
          next[requestId] = thought;
          changed = true;
        }
      });

      return changed ? next : previous;
    });
  });

  const commitConversation = useMemoizedFn(
    (conversationId: string, nextConversation: CHAT.ConversationHistory) => {
      onConversationChange(conversationId, {
        ...nextConversation,
        updatedAt: Date.now(),
      });
    },
  );

  const scheduleStreamingThought = useMemoizedFn(
    (requestId: string, thought: string, force = false) => {
      thoughtThrottle.schedule(
        (current) => ({
          ...current,
          [requestId]: thought,
        }),
        force,
      );
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
    thoughtThrottle.reset({});
    setTaskList([]);
    setWorkspaceStreamTask(undefined);
    setActiveRunState(undefined);
    setPlan(undefined);
    setShowAction(false);
    setLoading(false);
    setStreamingThoughtMap({});
  }, [conversation.id, thoughtThrottle, workspaceTaskThrottle]);

  useEffect(() => {
    if (!conversation.chatList.length || loading) {
      return;
    }

    const latestChatSnapshot = [...conversation.chatList]
      .reverse()
      .find(
        (chat) =>
          (chat.multiAgent?.tasks?.length || 0) > 0 ||
          !!chat.multiAgent?.plan ||
          !!chat.timeline?.length,
      );

    if (!latestChatSnapshot) {
      return;
    }

    const conversationTaskData = buildConversationTaskData(
      latestChatSnapshot,
      conversation.deepThink,
    );
    const latestTask = getLatestRenderableTask(conversationTaskData.currentChat);

    setTaskList(conversationTaskData.taskList);
    setPlan(conversationTaskData.plan);
    setWorkspaceStreamTask(latestTask ? cloneWorkspaceTask(latestTask) : undefined);
    setActiveRunState(resolveLatestRunState(latestChatSnapshot));
    setShowAction(
      resolveActionPanelVisibility({
        plan: conversationTaskData.plan,
        taskList: conversationTaskData.taskList,
      }),
    );
  }, [conversation.chatList, conversation.deepThink, conversation.id, loading]);

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
      thoughtThrottle.cancel();
      // 组件卸载 / 路由离开时中断仍在进行的 SSE 流，避免泄漏与跨会话数据污染。
      streamAbortRef.current?.abort();
      streamAbortRef.current = null;
    };
  }, [thoughtThrottle, workspaceTaskThrottle]);

  const sendMessage = useMemoizedFn((inputInfo: CHAT.TInputInfo) => {
    const baseConversation = conversationRef.current;
    const conversationId = baseConversation.id;
    const { message, deepThink, outputStyle } = inputInfo;
    const currentOutputStyle = outputStyle || baseConversation.productType;
    const isChatMode = currentOutputStyle === 'chat';
    const normalizedDeepThink = Boolean(deepThink);
    const replaceLast = Boolean(
      (inputInfo as CHAT.TInputInfo & { replaceLast?: boolean }).replaceLast,
    );
    const requestId = getUniqId();
    let currentChat = createRunningChat(
      inputInfo,
      baseConversation.sessionId,
      requestId,
      currentOutputStyle,
      normalizedDeepThink,
    );
    let runSettled = false;
    const notifyRunSettled = () => {
      if (runSettled) {
        return;
      }
      runSettled = true;
      onRunSettled?.(baseConversation.sessionId);
    };

    if (!isChatMode && normalizedDeepThink) {
      setStreamingThoughtMap((previous) => ({
        ...previous,
        [requestId]: '',
      }));
    }

    const initialConversation = createDraftConversation(baseConversation, {
      chatTitle: message || '',
      productType: currentOutputStyle,
      deepThink: normalizedDeepThink,
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

    const params = buildAgentStreamRequest({
      sessionId: baseConversation.sessionId,
      requestId,
      message,
      deepThink: normalizedDeepThink,
      outputStyle: currentOutputStyle,
      files: inputInfo.files,
      aiAgentId: inputInfo.aiAgentId,
      fallbackRoleAgentId: baseConversation.role?.agentId,
      modelId: inputInfo.modelId,
      resumeCheckpointId: inputInfo.resumeCheckpointId,
      resumeDecision: inputInfo.resumeDecision,
    });
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
        pendingTaskData = handleTaskData(currentChat, normalizedDeepThink, currentChat.multiAgent);
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
        setPlan(pendingTaskData.plan);
        setShowAction(
          resolveActionPanelVisibility({
            plan: pendingTaskData.plan,
            taskList: pendingTaskData.taskList,
          }),
        );
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

    const handleMessage = (data: MESSAGE.Answer) => {
      const { finished, resultMap, packageType, status } = data;
      const envelopeEventData = normalizeEventData(resultMap?.eventData);
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

        if (isChatMode) {
          currentChat = {
            ...currentChat,
            loading: false,
            forceStop: terminalStatus === 'STOPPED',
            response: currentChat.response || errorText,
            tip: errorText,
            metrics: {
              ...(currentChat.metrics || {}),
              status: terminalStatus,
            },
          };
          syncRunningConversation();
          notifyRunSettled();
          return;
        }

        currentChat = applyTerminalRunError(currentChat, terminalStatus, errorText);
        const taskData = handleTaskData(currentChat, normalizedDeepThink, currentChat.multiAgent);
        setTaskList(taskData.taskList);
        draftController.commit(draftController.replaceLastItem({ ...currentChat }));
        notifyRunSettled();
        return;
      }

      if (['roleUnavailable', 'roleSwitchRejected', 'noAvailableChatRole'].includes(status)) {
        currentChat = {
          ...currentChat,
          response: data.errorMsg || '当前角色暂不可用',
          loading: false,
          metrics: {
            ...(currentChat.metrics || {}),
            status: 'FAILED',
          },
        };
        setLoading(false);
        syncRunningConversation();
        notifyRunSettled();
        return;
      }

      if (status === 'tokenUseUp') {
        onTokenUseUp?.();
        const errorText = data.errorMsg || '对话额度不足，请前往额度中心查看或购买额度包';
        currentChat = isChatMode
          ? {
              ...currentChat,
              loading: false,
              response: currentChat.response || errorText,
              tip: errorText,
              metrics: { ...(currentChat.metrics || {}), status: 'FAILED' },
            }
          : applyTerminalRunError(currentChat, 'FAILED', errorText);
        const taskData = handleTaskData(currentChat, normalizedDeepThink, currentChat.multiAgent);
        setLoading(false);
        setTaskList(taskData.taskList);
        draftController.commit(draftController.replaceLastItem({ ...currentChat }));
        notifyRunSettled();
        return;
      }

      if (packageType === 'heartbeat') {
        return;
      }

      if (isChatMode) {
        const eventData = envelopeEventData;
        const inner = eventData?.resultMap;
        const innerType = inner?.messageType;
        if (innerType === 'agent_stream') {
          const text = inner?.result || '';
          if (text) {
            currentChat.response = `${currentChat.response || ''}${text}`;
          }
        } else if (innerType === 'result' && !currentChat.response) {
          currentChat.response = inner?.result || '';
        }

        if (innerType) {
          syncRunningConversation();
        }

        if (innerType && (inner?.finish || finished)) {
          const terminalState = resolveTerminalRunState(data, eventData);
          const terminalStatus =
            terminalState.status === 'RUNNING' ? 'SUCCESS' : terminalState.status;
          currentChat.loading = false;
          currentChat.forceStop = terminalStatus === 'STOPPED';
          if (terminalStatus !== 'SUCCESS') {
            currentChat.tip = terminalState.message;
            currentChat.response = currentChat.response || terminalState.message;
          }
          currentChat.metrics = {
            ...(currentChat.metrics || {}),
            status: terminalStatus,
            ...(extractRunMetrics(inner) || {}),
          };
          setLoading(false);
          syncRunningConversation();
          notifyRunSettled();
        }
        return;
      }

      const eventData = envelopeEventData;
      if (!eventData) {
        return;
      }

      const checkpointControlEvent = parseCheckpointControlEvent(eventData);
      if (checkpointControlEvent) {
        currentChat = applyCheckpointControlEvent(currentChat, checkpointControlEvent);
        pendingConversation = draftController.replaceLastItem({ ...currentChat });
        scheduleNonChatFlush(true);
        return;
      }

      const isPlanThoughtEvent = eventData.messageType === 'plan_thought';
      const isEvaluationEvent = eventData.resultMap?.messageType === 'evaluation';
      const isPlanThoughtFinal = Boolean(eventData.resultMap?.isFinal || finished);
      currentChat = combineData(eventData, currentChat);
      // 实时收到最终 result 时，优先用结构化结果覆盖掉临时 agent_stream 结论，
      // 避免界面在当前会话里一直停留在“答案$$$文件名”的原始协议文本。
      if (eventData.resultMap?.messageType === 'result') {
        currentChat.conclusion = buildTaskFromEventData(eventData) as CHAT.Task;
      }
      if (shouldRefreshWorkspaceTask(eventData)) {
        scheduleWorkspaceStreamTask(currentChat, finished);
      }
      if (normalizedDeepThink && isPlanThoughtEvent) {
        const latestThought = currentChat.thought || currentChat.multiAgent.plan_thought || '';
        scheduleStreamingThought(currentChat.requestId, latestThought, isPlanThoughtFinal);
      }
      if (!isPlanThoughtEvent && !isEvaluationEvent) {
        taskDataDirty = true;
      }
      if (finished) {
        const terminalState = resolveTerminalRunState(data, eventData);
        const terminalStatus = terminalState.status === 'RUNNING' ? 'SUCCESS' : terminalState.status;
        currentChat =
          terminalStatus === 'SUCCESS'
            ? { ...currentChat, loading: false }
            : applyTerminalRunError(currentChat, terminalStatus, terminalState.message);
        currentChat.metrics = {
          ...(currentChat.metrics || {}),
          status: terminalStatus,
          ...(extractRunMetrics(eventData.resultMap) || {}),
        };
        setLoading(false);
        if (normalizedDeepThink) {
          const finalThought = currentChat.thought || currentChat.multiAgent.plan_thought || '';
          scheduleStreamingThought(currentChat.requestId, finalThought, true);
        }
        notifyRunSettled();
      }

      draftController.replaceLastItem({ ...currentChat });
      if (!isPlanThoughtEvent || isPlanThoughtFinal) {
        pendingConversation = draftController.getSnapshot();
        scheduleNonChatFlush(finished);
      }
    };

    const handleError = (error: unknown) => {
      console.error('SSE stream error', error);
      const errorText = resolveStreamErrorMessage(error);
      setLoading(false);

      if (isChatMode) {
        currentChat = {
          ...currentChat,
          loading: false,
          response: errorText,
          metrics: {
            ...(currentChat.metrics || {}),
            status: 'FAILED',
          },
        };
        syncRunningConversation();
        notifyRunSettled();
        return;
      }

      currentChat = applyGuardError(currentChat, errorText);
      const taskData = handleTaskData(currentChat, normalizedDeepThink, currentChat.multiAgent);
      setTaskList(taskData.taskList);
      draftController.commit(draftController.replaceLastItem({ ...currentChat }));
      notifyRunSettled();
    };

    const handleClose = () => {
      if (currentChat.loading && !abortController.signal.aborted) {
        const errorText = '对话连接已中断，请重试。';
        setLoading(false);
        if (isChatMode) {
          currentChat = {
            ...currentChat,
            loading: false,
            response: currentChat.response || errorText,
            tip: errorText,
            metrics: { ...(currentChat.metrics || {}), status: 'FAILED' },
          };
          syncRunningConversation();
        } else {
          currentChat = applyTerminalRunError(currentChat, 'FAILED', errorText);
          draftController.commit(draftController.replaceLastItem({ ...currentChat }));
        }
        notifyRunSettled();
      }
      scheduleNonChatFlush(true);
    };

    // 中断上一条仍在进行的流，并为本次请求建立可取消的信号。
    streamAbortRef.current?.abort();
    const abortController = new AbortController();
    streamAbortRef.current = abortController;

    querySSE({
      body: params,
      parser: parseAgentAnswer,
      handleMessage,
      handleError,
      handleClose,
      signal: abortController.signal,
    });
  });

  const resumeFromCheckpoint = useMemoizedFn(
    (sourceChat: CHAT.ChatItem, decision: CHAT.CheckpointResumeDecision) => {
      const checkpoint = sourceChat.checkpoint;
      if (!checkpoint || !checkpoint.resumable || checkpoint.status !== 'AVAILABLE' || loading) {
        return;
      }

      const baseConversation = conversationRef.current;
      sendMessage({
        message: sourceChat.query || '从检查点恢复任务',
        files: sourceChat.files || [],
        outputStyle: baseConversation.productType,
        // 后端只允许 Plan-Solve 消费 checkpoint，恢复动作必须显式进入深度模式。
        deepThink: true,
        aiAgentId: baseConversation.role?.agentId,
        modelId: selectedModelId,
        resumeCheckpointId: checkpoint.checkpointId,
        resumeDecision: decision,
      });
    },
  );

  const regenerateLastMessage = useMemoizedFn(() => {
    const last = conversation.chatList[conversation.chatList.length - 1];
    if (!last || loading) {
      return;
    }

    sendMessage({
      message: last.query,
      outputStyle: conversation.productType,
      deepThink: conversation.deepThink,
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
    plan,
    showAction,
    changeActionStatus,
    loading,
    streamingThoughtMap,
    sendMessage,
    resumeFromCheckpoint,
    regenerateLastMessage,
  };
}
