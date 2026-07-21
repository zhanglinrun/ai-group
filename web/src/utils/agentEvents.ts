type RecordValue = Record<string, unknown>;

const AGENT_LOOP_EVENT_TYPES = new Set([
  'run_started',
  'phase_changed',
  'todo_snapshot',
  'verification_started',
  'verification_result',
  'completion_blocked',
  'run_finished',
]);

export type UiAgentEvent =
  | { type: 'run_started'; runId?: string; phase?: CHAT.AgentRunPhase }
  | { type: 'phase_changed'; phase: CHAT.AgentRunPhase }
  | { type: 'todo_snapshot'; title?: string; todos: CHAT.TodoItem[] }
  | { type: 'verification_started'; attempt?: number }
  | { type: 'verification_result'; verification: CHAT.VerificationState }
  | { type: 'completion_blocked'; verification: CHAT.VerificationState }
  | {
      type: 'run_finished';
      status: CHAT.AgentRunStatus;
      completionGatePassed?: boolean;
      stopReason?: string;
    }
  | { type: 'final_result' }
  | { type: 'activity' };

const isRecord = (value: unknown): value is RecordValue =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const firstText = (...values: unknown[]) => {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return '';
};

const stringList = (value: unknown): string[] | undefined => {
  if (!Array.isArray(value)) return undefined;
  const values = value.filter((item): item is string => typeof item === 'string' && !!item.trim());
  return values.length ? values : undefined;
};

export function normalizeTodoStatus(value: unknown): CHAT.TodoStatus {
  switch (
    String(value || '')
      .trim()
      .toLowerCase()
  ) {
    case 'completed':
      return 'completed';
    case 'in_progress':
      return 'in_progress';
    case 'blocked':
      return 'blocked';
    case 'failed':
      return 'failed';
    case 'not_started':
    case 'pending':
    default:
      return 'pending';
  }
}

const normalizeRunStatus = (value: unknown): CHAT.AgentRunStatus | undefined => {
  switch (
    String(value || '')
      .trim()
      .toUpperCase()
  ) {
    case 'SUCCESS':
      return 'SUCCESS';
    case 'FAILED':
      return 'FAILED';
    case 'STOPPED':
      return 'STOPPED';
    case 'TIMEOUT':
      return 'TIMEOUT';
    case 'RUNNING':
      return 'RUNNING';
    default:
      return undefined;
  }
};

const normalizePhase = (value: unknown): CHAT.AgentRunPhase | undefined => {
  const phase = String(value || '')
    .trim()
    .toUpperCase();
  return [
    'ANALYZING',
    'PLANNING',
    'EXECUTING',
    'VERIFYING',
    'FINALIZING',
  ].includes(phase)
    ? (phase as CHAT.AgentRunPhase)
    : undefined;
};

const normalizeTodo = (raw: unknown, fallbackId: string): CHAT.TodoItem | undefined => {
  if (!isRecord(raw)) return undefined;
  const title = firstText(raw.title);
  if (!title) return undefined;
  const evidencePolicy = firstText(raw.evidencePolicy).toUpperCase();
  return {
    id: firstText(raw.id, fallbackId),
    title,
    detail: firstText(raw.detail) || undefined,
    status: normalizeTodoStatus(raw.status),
    evidencePolicy: ['NONE', 'TOOL', 'LEGACY'].includes(evidencePolicy)
      ? (evidencePolicy as CHAT.TodoEvidencePolicy)
      : undefined,
    evidenceRefs: stringList(raw.evidenceRefs),
  };
};

function readEventEnvelope(eventData: MESSAGE.EventData) {
  const envelope = eventData.resultMap as unknown as RecordValue;
  const eventType = firstText(envelope.messageType, eventData.messageType).toLowerCase();
  const payload = isRecord(envelope.resultMap) ? envelope.resultMap : envelope;
  return { eventType, payload };
}

export function isAgentLoopEvent(eventData: MESSAGE.EventData): boolean {
  return AGENT_LOOP_EVENT_TYPES.has(readEventEnvelope(eventData).eventType);
}

/**
 * 实时协议只接受统一 Agent Loop 事件。旧版 plan/task/evaluation
 * 不再在这里兼容，避免旧状态机重新渗入前端运行态。
 */
export function normalizeAgentEvent(eventData: MESSAGE.EventData): UiAgentEvent | undefined {
  const { eventType, payload } = readEventEnvelope(eventData);

  if (eventType === 'run_started') {
    return {
      type: 'run_started',
      runId: firstText(payload.runId) || undefined,
      phase: normalizePhase(payload.phase),
    };
  }
  if (eventType === 'phase_changed') {
    const phase = normalizePhase(payload.phase);
    return phase ? { type: 'phase_changed', phase } : undefined;
  }
  if (eventType === 'todo_snapshot') {
    const rawTodos = Array.isArray(payload.todos) ? payload.todos : [];
    return {
      type: 'todo_snapshot',
      title: firstText(payload.title) || undefined,
      todos: rawTodos
        .map((todo, index) => normalizeTodo(todo, `${eventData.taskId}-todo-${index}`))
        .filter((todo): todo is CHAT.TodoItem => Boolean(todo)),
    };
  }
  if (eventType === 'verification_started') {
    return {
      type: 'verification_started',
      attempt: typeof payload.attempt === 'number' ? payload.attempt : undefined,
    };
  }
  if (eventType === 'verification_result') {
    const accepted = payload.accepted === true || payload.canStop === true;
    const status = firstText(payload.status).toLowerCase();
    return {
      type: 'verification_result',
      verification: {
        status: accepted || status === 'passed' ? 'passed' : 'failed',
        summary: firstText(payload.summary) || undefined,
        missingRequirements: stringList(payload.missingRequirements ?? payload.failureReasons),
        requiredActions: stringList(payload.requiredActions),
        attempt: typeof payload.attempt === 'number' ? payload.attempt : undefined,
      },
    };
  }
  if (eventType === 'completion_blocked') {
    return {
      type: 'completion_blocked',
      verification: {
        status: 'failed',
        missingRequirements: stringList(payload.reasons ?? payload.failureReasons),
        requiredActions: stringList(payload.requiredActions),
        attempt: typeof payload.attempt === 'number' ? payload.attempt : undefined,
      },
    };
  }
  if (eventType === 'run_finished') {
    const status = normalizeRunStatus(payload.status ?? payload.runStatus);
    if (!status) return undefined;
    return {
      type: 'run_finished',
      status,
      completionGatePassed:
        typeof payload.completionGatePassed === 'boolean'
          ? payload.completionGatePassed
          : undefined,
      stopReason: firstText(payload.stopReason) || undefined,
    };
  }
  if (eventType === 'result') return { type: 'final_result' };
  if (eventType.startsWith('tool_') || eventType === 'agent_stream') return { type: 'activity' };
  return undefined;
}

function ensureRun(chat: CHAT.ChatItem): CHAT.AgentLoopViewState {
  if (!chat.agentRun) {
    chat.agentRun = { status: 'RUNNING', todos: [] };
  }
  return chat.agentRun;
}

export function applyAgentEvent(chat: CHAT.ChatItem, event: UiAgentEvent): CHAT.ChatItem {
  const run = ensureRun(chat);
  switch (event.type) {
    case 'run_started':
      run.runId = event.runId || run.runId;
      run.phase = event.phase || 'ANALYZING';
      run.status = 'RUNNING';
      break;
    case 'phase_changed':
      run.phase = event.phase;
      break;
    case 'todo_snapshot':
      run.todoTitle = event.title || run.todoTitle;
      run.todos = event.todos;
      run.phase = 'PLANNING';
      break;
    case 'verification_started':
      run.phase = 'VERIFYING';
      run.verification = { status: 'running', attempt: event.attempt };
      break;
    case 'verification_result':
      run.phase = event.verification.status === 'passed' ? 'FINALIZING' : 'VERIFYING';
      run.verification = event.verification;
      break;
    case 'completion_blocked':
      run.phase = 'VERIFYING';
      run.verification = event.verification;
      break;
    case 'run_finished':
      run.terminalEventSeen = true;
      run.status =
        event.status === 'SUCCESS' && event.completionGatePassed !== true ? 'FAILED' : event.status;
      run.completionGatePassed = event.completionGatePassed;
      run.stopReason = event.stopReason;
      if (run.status === 'SUCCESS') {
        run.phase = 'FINALIZING';
        // 历史账本可稳定恢复 run_finished，但旧记录不一定单独保存
        // verification_result。终态完成门已经通过时补齐最小验证视图，
        // 同时保留实时 verification_result 中更丰富的轮次与摘要。
        run.verification ||= { status: 'passed' };
      }
      break;
    case 'final_result':
      run.phase = 'FINALIZING';
      break;
    case 'activity':
      if (run.phase !== 'VERIFYING') run.phase = 'EXECUTING';
      break;
  }
  return chat;
}
