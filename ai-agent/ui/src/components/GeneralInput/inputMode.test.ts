import { describe, expect, it } from 'vitest';

import { buildSubmitPayload } from './inputMode';

describe('inputMode', () => {
  it('深度研究模式应保留结构化输出类型并打开 deepThink', () => {
    expect(
      buildSubmitPayload({
        question: '帮我调研竞品',
        visibleMode: 'research',
        isDataAgent: false,
        visibleOutputProduct: { type: 'html' } as CHAT.Product,
        uploadedFiles: [],
        chatRole: null,
      }),
    ).toMatchObject({
      outputStyle: 'html',
      deepThink: true,
    });
  });

  it('快速模式应走普通聊天(chat)且关闭 deepThink，并带上角色 agentId', () => {
    expect(
      buildSubmitPayload({
        question: '你好',
        visibleMode: 'quick',
        isDataAgent: false,
        visibleOutputProduct: { type: 'html' } as CHAT.Product,
        uploadedFiles: [],
        chatRole: { agentId: 'role-1' } as CHAT.ConversationRole,
      }),
    ).toMatchObject({
      outputStyle: 'chat',
      deepThink: false,
      aiAgentId: 'role-1',
    });
  });
});
