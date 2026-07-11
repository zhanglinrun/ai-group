import type { ConversationSessionItem } from '@/services/agentConversation';

/**
 * 优先恢复本机最后一次会话；只有记录不存在时才显示欢迎态。
 */
export function resolveInitialSessionId(params: {
  recentSessions: ConversationSessionItem[];
  storedSessionId?: string | null;
}) {
  const storedSessionId = params.storedSessionId?.trim();
  if (!storedSessionId) return null;
  return params.recentSessions.some((item) => item.sessionId === storedSessionId)
    ? storedSessionId
    : null;
}
