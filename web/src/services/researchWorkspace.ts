import {
  fetchEventSource,
  EventStreamContentType,
  type EventSourceMessage,
} from '@microsoft/fetch-event-source';

import { getAccessToken } from '@/auth/token';
import type { UploadedConversationFile } from '@/services/agentFile';
import api from '@/services/index';
import { resolveServiceBaseUrl } from '@/utils/origin';

const serviceBaseUrl = resolveServiceBaseUrl(
  import.meta.env.VITE_API_BASE_URL || import.meta.env.VITE_API_TARGET || '',
);

export type ResearchRunEvent = Record<string, unknown> & {
  type?: string;
  runId?: string;
  messageType?: string;
  outputType?: string;
};

export type ResearchRunSnapshot = {
  requestId: string;
  sessionId: string;
  query: string;
  executionMode: 'STANDARD' | 'DEEP';
  attachments: UploadedConversationFile[];
  events: ResearchRunEvent[];
  cursor: number;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCEL_REQUESTED';
  estimatedMicrocredits?: number;
  quotaEstimateBasis?: string;
};

export type ResearchQuotaEstimate = {
  microcredits: number;
  label: string;
  basis: string;
};

export type RunDiagnostics = {
  requestId: string;
  sessionId: string;
  entryAgent?: string;
  status: number;
  errorCode?: string;
  durationMs?: number;
  totalTokens?: number;
  modelInvocations: Array<{
    callKind: string;
    modelName: string;
    configHash?: string;
    totalTokens?: number;
    status: number;
    errorCode?: string;
    durationMs?: number;
  }>;
  toolInvocations: Array<{
    toolName: string;
    status: number;
    artifactCount: number;
    errorCode?: string;
  }>;
  artifacts: Array<{
    fileName: string;
    artifactRole: string;
    mimeType?: string;
    fileSize?: number;
    sourceType?: string;
    createdAt?: string;
  }>;
};

export type ResearchEventEvidence = {
  kind: string;
  fileName?: string;
  strategy?: string;
  uncertainty?: string;
  degraded?: boolean;
  artifactReference?: string;
};

/**
 * Conservative browser-side preflight budget. The server-side price snapshot and actual provider
 * usage remain the only billing authority; this value is never sent as a billing instruction.
 */
export function estimateResearchQuota(
  executionMode: ResearchRunSnapshot['executionMode'],
  queryLength: number,
  attachmentCount: number,
): ResearchQuotaEstimate {
  const base = executionMode === 'DEEP' ? 750_000 : 250_000;
  const queryAllowance = Math.min(250_000, Math.ceil(Math.max(0, queryLength) / 200) * 10_000);
  const attachmentAllowance = Math.min(400_000, Math.max(0, attachmentCount) * 100_000);
  const microcredits = base + queryAllowance + attachmentAllowance;
  return {
    microcredits,
    label: `${microcredits.toLocaleString()} microcredits`,
    basis:
      executionMode === 'DEEP'
        ? 'DEEP 预留模型、工具与附件的保守上限'
        : 'STANDARD 预留模型与附件的保守上限',
  };
}

const storageKey = (requestId: string) => `researchpilot:run:${requestId}`;

export function saveResearchRun(snapshot: ResearchRunSnapshot): void {
  sessionStorage.setItem(
    storageKey(snapshot.requestId),
    JSON.stringify({ ...snapshot, events: snapshot.events.slice(-120) }),
  );
}

export function loadResearchRun(requestId: string): ResearchRunSnapshot | null {
  try {
    const raw = sessionStorage.getItem(storageKey(requestId));
    return raw ? (JSON.parse(raw) as ResearchRunSnapshot) : null;
  } catch {
    return null;
  }
}

/**
 * Merges durable replay events without displaying unbounded event payloads.  The raw event is
 * still retained only in the current browser session so an interrupted workspace can resume.
 */
export function appendResearchEvent(
  snapshot: ResearchRunSnapshot,
  event: ResearchRunEvent,
  nextCursor: number,
): ResearchRunSnapshot {
  const fingerprint = researchEventFingerprint(event);
  const alreadyPresent = snapshot.events.some(
    (candidate) => researchEventFingerprint(candidate) === fingerprint,
  );
  const kind = researchEventKind(event);
  return {
    ...snapshot,
    events: alreadyPresent ? snapshot.events : [...snapshot.events, event].slice(-120),
    cursor:
      Number.isFinite(nextCursor) && nextCursor > 0
        ? Math.max(snapshot.cursor, nextCursor)
        : snapshot.cursor,
    status: kind === 'error' ? 'FAILED' : kind === 'complete' ? 'COMPLETED' : snapshot.status,
  };
}

/** Returns only the whitelisted evidence fields suitable for the workspace UI. */
export function researchEventEvidence(event: ResearchRunEvent): ResearchEventEvidence {
  const payload = eventPayload(event);
  return {
    kind: researchEventKind(event),
    fileName: stringValue(payload.fileName),
    strategy: stringValue(payload.strategy),
    uncertainty: stringValue(payload.uncertainty),
    degraded: booleanValue(payload.degraded),
    artifactReference: stringValue(payload.artifactReference),
  };
}

export function isFileAnalysisEvent(event: ResearchRunEvent): boolean {
  return researchEventKind(event).startsWith('FILE_ANALYSIS_');
}

export function newResearchId(prefix: string): string {
  const random = globalThis.crypto?.randomUUID?.() || Math.random().toString(36).slice(2);
  return `${prefix}-${random}`;
}

function researchEventKind(event: ResearchRunEvent): string {
  const payload = eventPayload(event);
  return (
    stringValue(payload.event) ||
    stringValue(payload.type) ||
    stringValue(event.outputType) ||
    stringValue(event.type) ||
    stringValue(event.messageType) ||
    'event'
  );
}

function researchEventFingerprint(event: ResearchRunEvent): string {
  const identity = stringValue(event.messageId) || stringValue(event.eventUid);
  return identity ? `id:${identity}` : JSON.stringify(event);
}

function eventPayload(event: ResearchRunEvent): Record<string, unknown> {
  const result = recordValue(event.resultMap);
  return recordValue(event.payload) || recordValue(result?.resultMap) || result || event;
}

function recordValue(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function booleanValue(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined;
}

export const researchWorkspaceApi = {
  listAttachments: (sessionId: string) =>
    api.get<UploadedConversationFile[]>(
      `/api/agent/file/sessions/${encodeURIComponent(sessionId)}`,
    ) as unknown as Promise<UploadedConversationFile[]>,
  deleteAttachment: (sessionId: string, resourceKey: string) =>
    api.delete<boolean>(`/api/agent/file/sessions/${encodeURIComponent(sessionId)}`, {
      resourceKey,
    }) as unknown as Promise<boolean>,
  diagnostics: (requestId: string) =>
    api.get<RunDiagnostics>(
      `/api/agent/runs/${encodeURIComponent(requestId)}/diagnostics`,
    ) as unknown as Promise<RunDiagnostics>,
  cancel: (requestId: string) =>
    api.post<{ requestId: string; status: string; accepted: boolean }>(
      `/api/agent/runs/by-request/${encodeURIComponent(requestId)}/cancel`,
    ) as unknown as Promise<{ requestId: string; status: string; accepted: boolean }>,
};

export type ResearchRunGap = {
  requestedAfter: number;
  earliestRetained: number;
};

export type ResearchRunSubscriptionHandlers = {
  onEvent: (event: ResearchRunEvent, nextCursor: number) => void;
  onError: (error: Error) => void;
  onGap?: (gap: ResearchRunGap) => void;
  onReconnect?: (attempt: number, delayMs: number) => void;
};

export function researchSseHeaders(
  token: string | null | undefined,
  cursor: number,
): Record<string, string> {
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (cursor > 0) headers['Last-Event-ID'] = String(cursor);
  return headers;
}

export function subscribeResearchRunEvents(
  requestId: string,
  cursor: number,
  handlers: ResearchRunSubscriptionHandlers,
): () => void {
  const controller = new AbortController();
  const token = getAccessToken();
  const url = `${serviceBaseUrl}/api/agent/runs/by-request/${encodeURIComponent(requestId)}/events?cursor=${Math.max(0, cursor)}`;
  let lastCursor = Math.max(0, cursor);
  let reconnectAttempts = 0;
  let terminalReceived = false;
  let gapDetected = false;
  const headers = researchSseHeaders(token, lastCursor);
  void fetchEventSource(url, {
    method: 'GET',
    credentials: 'include',
    signal: controller.signal,
    headers,
    async onopen(response) {
      if (
        response.ok &&
        (response.headers.get('content-type') || '').startsWith(EventStreamContentType)
      )
        return;
      throw new Error(`无法恢复 Run 事件：${response.status}`);
    },
    onmessage(message: EventSourceMessage) {
      if (message.id) {
        const nextCursor = Number(message.id);
        if (Number.isFinite(nextCursor) && nextCursor > lastCursor) {
          lastCursor = nextCursor;
          headers['Last-Event-ID'] = String(lastCursor);
        }
      }
      if (message.event === 'gap') {
        gapDetected = true;
        try {
          const payload = JSON.parse(message.data || '{}') as Partial<ResearchRunGap>;
          handlers.onGap?.({
            requestedAfter: Number(payload.requestedAfter || lastCursor),
            earliestRetained: Number(payload.earliestRetained || lastCursor + 1),
          });
        } catch (error) {
          handlers.onError(error instanceof Error ? error : new Error('Run gap 事件解析失败'));
        }
        controller.abort();
        return;
      }
      if (!message.data) return;
      try {
        const event = JSON.parse(message.data) as ResearchRunEvent;
        terminalReceived = event.type === 'complete' || event.type === 'error';
        handlers.onEvent(event, lastCursor);
      } catch (error) {
        handlers.onError(error instanceof Error ? error : new Error('Run 事件解析失败'));
      }
    },
    onclose() {
      if (!controller.signal.aborted && !terminalReceived && !gapDetected) {
        throw new Error('Run 事件流在终态前断开');
      }
    },
    onerror(error) {
      if (controller.signal.aborted) return;
      if (reconnectAttempts >= 4) {
        handlers.onError(error instanceof Error ? error : new Error('Run 事件恢复失败'));
        throw error;
      }
      reconnectAttempts += 1;
      const delayMs = Math.min(1_000 * 2 ** (reconnectAttempts - 1), 8_000);
      handlers.onReconnect?.(reconnectAttempts, delayMs);
      return delayMs;
    },
  }).catch(() => undefined);
  return () => controller.abort();
}
