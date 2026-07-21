import api from './index';

const DEFAULT_DEVICE_ID = 'device-default';

let runtimeDeviceId: string | null = DEFAULT_DEVICE_ID;

/**
 * 当前前端已经移除历史对话持久化，只保留一个轻量设备标识，
 * 兼容仍然需要该请求头的上传和流式接口。
 */
export function getDeviceId(): string {
  if (!runtimeDeviceId) {
    runtimeDeviceId = DEFAULT_DEVICE_ID;
  }
  return runtimeDeviceId;
}

export function getDeviceHeaders(): Record<string, string> {
  return { 'X-Device-Id': getDeviceId() };
}

export interface FixRoleItem {
  agentId: string;
  agentName: string;
  description?: string;
  defaultRole: boolean;
}

export interface ConversationSessionItem {
  sessionId: string;
  title: string;
  status: string;
  latestQueryText: string;
  runCount: number;
  finishedRunCount: number;
  failedRunCount: number;
  startedAt: string;
  lastActiveAt: string;
}

export interface ConversationRole {
  agentId: string;
  agentName: string;
  available: boolean;
  defaultRole: boolean;
}

export interface ConversationReplayFrame {
  reqId: string;
  status: string;
  finished: boolean;
  resultMap: {
    multiAgent?: Record<string, unknown>;
    eventData?: MESSAGE.EventData;
  };
}

export interface ConversationHistoryRunDetail {
  requestId: string;
  status: string;
  queryText: string;
  finalSummaryText?: string;
  startedAt?: string;
  finishedAt?: string;
  /** 本轮实际使用的模型名（账本回放）。 */
  modelName?: string;
  /** 本轮总 token 用量（账本聚合）。 */
  totalTokens?: number;
  /** 本轮耗时（毫秒，账本聚合）。 */
  durationMs?: number;
  replayFrames: ConversationReplayFrame[];
}

export interface ConversationHistoryDetail {
  sessionId: string;
  title: string;
  status: string;
  outputStyle: string;
  executionMode?: CHAT.ExecutionMode;
  role: ConversationRole | null;
  runCount: number;
  finishedRunCount: number;
  failedRunCount: number;
  startedAt?: string;
  lastActiveAt?: string;
  runs: ConversationHistoryRunDetail[];
}

export const roleLibraryApi = {
  list: () =>
    api.get<FixRoleItem[]>(`/api/agent/role-library/list`) as unknown as Promise<FixRoleItem[]>,
};

export const conversationHistoryApi = {
  listSessions: (limit = 20) =>
    api.get<ConversationSessionItem[]>(
      `/api/agent/conversation/sessions?limit=${limit}`,
    ) as unknown as Promise<ConversationSessionItem[]>,
  getSessionDetail: (sessionId: string) =>
    api.get<ConversationHistoryDetail>(
      `/api/agent/conversation/sessions/${sessionId}`,
    ) as unknown as Promise<ConversationHistoryDetail>,
  deleteSession: (sessionId: string) =>
    api.delete<boolean>(
      `/api/agent/conversation/sessions/${sessionId}`,
    ) as unknown as Promise<boolean>,
};
