import { z } from 'zod';

type RecordValue = Record<string, unknown>;

const answerEnvelopeSchema = z
  .object({
    status: z.string(),
    packageType: z.string(),
    finished: z.boolean(),
    errorMsg: z
      .string()
      .nullable()
      .optional()
      .transform((value) => value ?? ''),
    resultMap: z.object({ eventData: z.unknown().optional() }).passthrough().optional().default({}),
  })
  .passthrough();

const eventDataSchema = z
  .object({
    messageOrder: z.number(),
    messageType: z.string(),
    messageId: z.string(),
    taskId: z.string(),
    taskOrder: z.number(),
    resultMap: z.object({ messageType: z.string().optional() }).passthrough(),
    artifactRefs: z.array(z.object({}).passthrough()).optional(),
  })
  .passthrough();

const baseStreamEventSchema = z.object({ runId: z.string().nullable().optional() });
const artifactRefSchema = z.object({}).passthrough();
export const agentStreamEventSchema = z.discriminatedUnion('type', [
  baseStreamEventSchema.extend({
    type: z.literal('agent_start'),
    ownerId: z.string().nullable().optional(),
    conversationId: z.string().nullable().optional(),
    agentName: z.string().nullable().optional(),
    modelId: z.string().nullable().optional(),
  }),
  baseStreamEventSchema.extend({ type: z.literal('thinking'), content: z.string() }),
  baseStreamEventSchema.extend({ type: z.literal('text'), delta: z.string() }),
  baseStreamEventSchema.extend({
    type: z.literal('tool_start'),
    toolCallId: z.string(),
    toolName: z.string().nullable().optional(),
    argumentsPreview: z.unknown().optional(),
  }),
  baseStreamEventSchema.extend({
    type: z.literal('tool_end'),
    toolCallId: z.string(),
    toolName: z.string().nullable().optional(),
    resultPreview: z.unknown().optional(),
    success: z.boolean(),
    durationMillis: z.number().nonnegative(),
  }),
  baseStreamEventSchema.extend({
    type: z.literal('todo_progress'),
    items: z.array(z.object({}).passthrough()),
  }),
  baseStreamEventSchema.extend({
    type: z.literal('paused'),
    approvalId: z.string(),
    toolCallId: z.string(),
    toolName: z.string(),
    argumentsPreview: z.unknown().optional(),
    estimatedMicrocredits: z.number().nonnegative(),
    expiresAt: z.string(),
  }),
  baseStreamEventSchema.extend({
    type: z.literal('resume_start'),
    approvalId: z.string(),
    toolCallId: z.string(),
    decision: z.string(),
  }),
  baseStreamEventSchema.extend({
    type: z.literal('stage_output'),
    toolCallId: z.string().nullable().optional(),
    outputType: z.string(),
    payload: z.unknown(),
    artifactRefs: z.array(artifactRefSchema),
    isFinal: z.boolean(),
  }),
  baseStreamEventSchema.extend({
    type: z.literal('error'),
    code: z.string(),
    message: z.string(),
  }),
  baseStreamEventSchema.extend({
    type: z.literal('complete'),
    summary: z.string().nullable().optional(),
    totalDurationMillis: z.number().nonnegative(),
    microcreditsConsumed: z.number().nonnegative(),
  }),
]);

export type AgentStreamEvent = z.infer<typeof agentStreamEventSchema>;

export function isAgentStreamEventMessage(value: unknown): value is AgentStreamEvent {
  return agentStreamEventSchema.safeParse(value).success;
}

export function parseAgentStreamMessage(
  raw: unknown,
  eventName?: string,
): AgentStreamEvent | MESSAGE.Answer {
  const canonical = agentStreamEventSchema.safeParse(raw);
  if (canonical.success) {
    if (eventName && canonical.data.type !== eventName) {
      throw new Error(`SSE event name ${eventName} does not match data type ${canonical.data.type}`);
    }
    return canonical.data;
  }
  if (eventName) {
    throw canonical.error;
  }
  return parseAgentAnswer(raw);
}

export function canonicalEventData(
  event: AgentStreamEvent,
  messageOrder: number,
): MESSAGE.EventData {
  const toolCallId = 'toolCallId' in event ? event.toolCallId : undefined;
  return {
    messageOrder,
    messageType: 'agent_event',
    messageId: toolCallId || `${event.runId || 'run'}-${event.type}-${messageOrder}`,
    taskId: event.runId || 'agent-run',
    taskOrder: messageOrder,
    resultMap: { messageType: event.type, resultMap: event },
    ...('artifactRefs' in event && event.artifactRefs.length
      ? { artifactRefs: event.artifactRefs }
      : {}),
  } as unknown as MESSAGE.EventData;
}

export function stageOutputEventData(
  event: Extract<AgentStreamEvent, { type: 'stage_output' }>,
  messageOrder: number,
): MESSAGE.EventData {
  const payload = isRecord(event.payload) ? event.payload : { result: event.payload };
  return {
    messageOrder,
    messageType: 'agent_event',
    messageId: event.toolCallId || `${event.runId || 'run'}-${event.outputType}-${messageOrder}`,
    taskId: event.runId || 'agent-run',
    taskOrder: messageOrder,
    artifactRefs: event.artifactRefs,
    resultMap: {
      ...payload,
      requestId: event.runId,
      messageId: event.toolCallId,
      messageType: event.outputType,
      isFinal: event.isFinal,
    },
  } as unknown as MESSAGE.EventData;
}

function isRecord(value: unknown): value is RecordValue {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
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

function stablePositiveHash(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) | 0;
  }
  return (hash >>> 0) || 1;
}

function buildAgentResponsePayload(raw: RecordValue): RecordValue {
  const messageType = String(raw.messageType || '');
  const resultMap = isRecord(raw.resultMap) ? raw.resultMap : {};
  const payload: RecordValue = {
    requestId: raw.requestId,
    messageId: raw.messageId,
    messageType,
    messageTime: raw.messageTime || String(Date.now()),
    isFinal: Boolean(raw.isFinal),
    finish: Boolean(raw.finish),
  };

  // 兼容直接下发 AgentResponse 的入口：终态字段不能在 envelope 转换时丢失，
  // 否则 FAILED/STOPPED 会再次被 finish=true 误写成 success。
  const passthroughFields = [
    'runStatus',
    'status',
    'stopReason',
    'completionGatePassed',
    'errorCode',
    'errorMessage',
    'errorMsg',
    'retryable',
    'retryAfterMillis',
    'existingRunId',
  ];
  passthroughFields.forEach((field) => {
    const value = resultMap[field] ?? raw[field];
    if (value !== undefined && value !== null && value !== '') {
      payload[field] = value;
    }
  });

  if (raw.digitalEmployee) {
    payload.digitalEmployee = raw.digitalEmployee;
  }

  switch (messageType) {
    case 'tool_thought':
    case 'thinking':
      payload.toolThought = raw.toolThought;
      break;
    case 'task':
      payload.task = raw.task;
      break;
    case 'task_summary':
      payload.taskSummary = raw.taskSummary;
      if (Object.keys(resultMap).length) {
        payload.resultMap = resultMap;
      }
      break;
    case 'tool_result':
      payload.toolResult = raw.toolResult;
      break;
    case 'agent_stream':
      payload.result = raw.result;
      break;
    case 'result':
      payload.result = raw.result;
      if ('taskSummary' in resultMap) {
        payload.taskSummary = resultMap.taskSummary;
      }
      if ('fileList' in resultMap) {
        payload.fileList = resultMap.fileList;
      }
      // 透传展示级 run 元数据（模型 / tokens / 耗时），否则会被此处重建逻辑丢弃
      if ('metrics' in resultMap) {
        payload.metrics = resultMap.metrics;
      }
      break;
    default:
      if (Object.keys(resultMap).length) {
        payload.resultMap = resultMap;
      }
      break;
  }

  return payload;
}

function normalizeAgentResponseFrame(raw: unknown): unknown {
  if (!isRecord(raw) || !('messageType' in raw) || 'packageType' in raw) {
    return raw;
  }

  const messageType = String(raw.messageType || '');
  const finished = Boolean(raw.finish);
  const rawResultMap = isRecord(raw.resultMap) ? raw.resultMap : {};
  const messageId = String(
    raw.messageId ||
      `${raw.requestId || 'message'}-${messageType}-${raw.messageTime || Date.now()}`,
  );
  const explicitMessageOrder = firstFiniteNumber(
    raw.messageOrder,
    rawResultMap.messageOrder,
    raw.sequence,
    rawResultMap.sequence,
  );
  const messageOrder = explicitMessageOrder ?? stablePositiveHash(messageId);
  const taskOrder = firstFiniteNumber(
    raw.taskOrder,
    rawResultMap.taskOrder,
    rawResultMap.dispatchIndex,
    messageOrder,
  ) ?? 0;
  const eventData = {
    messageOrder,
    messageType: 'agent_event',
    messageId,
    taskId: String(raw.requestId || raw.messageId || 'agent-task'),
    taskOrder,
    resultMap: buildAgentResponsePayload(raw),
  };

  return {
    status:
      typeof raw.status === 'string' && raw.status.trim()
        ? raw.status
        : finished
          ? 'success'
          : 'running',
    packageType: 'result',
    finished,
    errorMsg:
      typeof raw.errorMsg === 'string'
        ? raw.errorMsg
        : typeof raw.errorMessage === 'string'
          ? raw.errorMessage
          : '',
    resultMap: {
      eventData,
    },
  };
}

/**
 * 这里只校验 SSE 顶层协议骨架，内部 payload 继续交给业务解析层消费，
 * 避免前端跟后端富结构结果做过紧耦合。
 */
export function parseAgentAnswer(raw: unknown): MESSAGE.Answer {
  return answerEnvelopeSchema.parse(normalizeAgentResponseFrame(raw)) as unknown as MESSAGE.Answer;
}

/**
 * SSE eventData 入口统一做一次骨架校验，确保 message/task 主键存在，
 * 后续 combineData 只处理结构合法的事件。
 */
export function parseEventData(raw: unknown): MESSAGE.EventData {
  return eventDataSchema.parse(raw) as unknown as MESSAGE.EventData;
}
