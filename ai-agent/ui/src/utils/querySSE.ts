import { fetchEventSource, EventSourceMessage, EventStreamContentType } from '@microsoft/fetch-event-source';

import { getDeviceId } from '@/services/agentConversation';
import { getAccessToken } from '@/auth/token';
import { resolveServiceBaseUrl } from './origin';

const customHost = resolveServiceBaseUrl(SERVICE_BASE_URL);
/**
 * 历史会话接口已下线，主聊天统一回到当前仍然保留的 Reactor SSE 入口。
 */
const DEFAULT_SSE_URL = `${customHost}/web/api/v1/gpt/queryAgentStreamIncr`;

const SSE_HEADERS: Record<string, string> = {
  'Content-Type': 'application/json',
  'Cache-Control': 'no-cache',
  'Connection': 'keep-alive',
  'Accept': 'text/event-stream',
  'X-Device-Id': getDeviceId(),
};

const buildSseHeaders = (): Record<string, string> => {
  const headers: Record<string, string> = { ...SSE_HEADERS };
  const token = getAccessToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
};

class FatalSseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'FatalSseError';
  }
}

interface SSEConfig<TMessage = unknown> {
  body: unknown;
  parser?: (raw: unknown) => TMessage;
  handleMessage: (data: TMessage) => void;
  handleError: (error: Error) => void;
  handleClose: () => void;
  /** Optional abort signal so callers can cancel the stream (unmount / conversation switch). */
  signal?: AbortSignal;
}

/**
 * 创建服务器发送事件（SSE）连接
 * @param config SSE 配置
 * @param url 可选的自定义 URL
 */
export default <TMessage = unknown>(
  config: SSEConfig<TMessage>,
  url: string = DEFAULT_SSE_URL
): void => {
  const { body = null, parser, handleMessage, handleError, handleClose, signal } = config;
  let errorHandled = false;

  // Already aborted before start: do nothing.
  if (signal?.aborted) {
    handleClose();
    return;
  }

  fetchEventSource(url, {
    method: 'POST',
    credentials: 'include',
    headers: buildSseHeaders(),
    body: JSON.stringify(body),
    openWhenHidden: true,
    signal,
    async onopen(response) {
      const contentType = response.headers.get('content-type') || '';
      if (response.ok && contentType.startsWith(EventStreamContentType)) {
        return;
      }

      let errorText = `SSE request failed with status ${response.status}`;
      try {
        const bodyText = await response.text();
        if (bodyText) {
          errorText = bodyText;
        }
      } catch {
        // Keep the status-based fallback when the response body cannot be read.
      }
      throw new FatalSseError(errorText);
    },
    onmessage(event: EventSourceMessage) {
      if (!event.data) {
        return;
      }
      const trimmed = event.data.trim();
      // 忽略保活/注释类非结构化数据帧（如纯文本 "heartbeat"），只解析 JSON 结构帧，
      // 避免心跳/保活内容触发 JSON.parse 失败而中断整条对话流。
      if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
        return;
      }
      try {
        const parsedData = JSON.parse(trimmed);
        handleMessage(parser ? parser(parsedData) : (parsedData as TMessage));
      } catch (error) {
        console.error('Error parsing SSE message:', error);
        throw new FatalSseError('Failed to parse SSE message');
      }
    },
    onerror(error: Error) {
      console.error('SSE error:', error);
      errorHandled = true;
      handleError(error);
      throw error;
    },
    onclose() {
      console.log('SSE connection closed');
      handleClose();
    }
  }).catch((error: Error) => {
    // Aborted streams (unmount / conversation switch) are expected; treat as a normal close.
    if (error?.name === 'AbortError' || signal?.aborted) {
      handleClose();
      return;
    }
    if (!errorHandled) {
      handleError(error);
    }
  });
};
