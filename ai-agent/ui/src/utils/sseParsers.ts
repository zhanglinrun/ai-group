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

const dataChatEventSchema = z.discriminatedUnion('eventType', [
  z.object({
    eventType: z.literal('THINK'),
    data: z.string(),
  }),
  z.object({
    eventType: z.literal('CHART_DATA'),
    data: z.array(z.record(z.string(), z.unknown())),
  }),
  z.object({
    eventType: z.literal('ERROR'),
    data: z.string(),
  }),
  z.object({
    eventType: z.literal('READY'),
    data: z.unknown().optional(),
  }),
]);

function isRecord(value: unknown): value is RecordValue {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
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

  if (raw.digitalEmployee) {
    payload.digitalEmployee = raw.digitalEmployee;
  }

  switch (messageType) {
    case 'tool_thought':
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
    case 'plan_thought':
      payload.planThought = raw.planThought;
      break;
    case 'plan':
      payload.title = isRecord(raw.plan) ? raw.plan.title : undefined;
      payload.stages = isRecord(raw.plan) ? raw.plan.stages : undefined;
      payload.steps = isRecord(raw.plan) ? raw.plan.steps : undefined;
      payload.stepStatus = isRecord(raw.plan) ? raw.plan.stepStatus : undefined;
      payload.notes = isRecord(raw.plan) ? raw.plan.notes : undefined;
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
      break;
    default:
      if (Object.keys(resultMap).length) {
        payload.resultMap = resultMap;
      }
      break;
  }

  if (isRecord(raw.resultMap) && typeof raw.resultMap.plannerRoundId === 'string') {
    payload.plannerRoundId = raw.resultMap.plannerRoundId;
  }

  return payload;
}

function normalizeAgentResponseFrame(raw: unknown): unknown {
  if (!isRecord(raw) || !('messageType' in raw) || 'packageType' in raw) {
    return raw;
  }

  const messageType = String(raw.messageType || '');
  const finished = Boolean(raw.finish);
  const eventData = {
    messageOrder: 1,
    messageType: messageType === 'plan' || messageType === 'plan_thought' ? messageType : 'task',
    messageId: String(raw.messageId || `${raw.requestId || 'message'}-${messageType}`),
    taskId: String(raw.requestId || raw.messageId || 'agent-task'),
    taskOrder: 1,
    resultMap: buildAgentResponsePayload(raw),
  };

  return {
    status: finished ? 'success' : 'running',
    packageType: 'result',
    finished,
    errorMsg: '',
    resultMap: {
      agentType: isRecord(raw.resultMap) ? raw.resultMap.agentType : undefined,
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

export function parseDataChatEvent(raw: unknown): CHAT.DataChatEvent {
  return dataChatEventSchema.parse(raw);
}
