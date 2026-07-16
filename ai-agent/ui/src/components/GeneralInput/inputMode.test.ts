import { describe, expect, it } from 'vitest';

import { buildSubmitPayload, resolveInputMode } from './inputMode';

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

  it('网页输出的深度思考应保持 think，不能串成深度研究', () => {
    const payload = buildSubmitPayload({
      question: '仔细分析并生成网页',
      visibleMode: 'think',
      isDataAgent: false,
      visibleOutputProduct: { type: 'html' } as CHAT.Product,
      uploadedFiles: [],
      chatRole: null,
    });

    expect(payload).toMatchObject({ outputStyle: 'html', deepThink: false });
    expect(resolveInputMode(payload.outputStyle, payload.deepThink)).toBe('think');
  });

  it('普通对话可以独立开启深度思考', () => {
    expect(
      buildSubmitPayload({
        question: '仔细分析这个问题',
        visibleMode: 'think',
        isDataAgent: false,
        visibleOutputProduct: { type: 'chat' } as CHAT.Product,
        uploadedFiles: [],
        chatRole: { agentId: 'role-1' } as CHAT.ConversationRole,
      }),
    ).toMatchObject({ outputStyle: 'chat', deepThink: true, aiAgentId: 'role-1' });
  });

  it('应从 product/deepThink 稳定恢复三种可见模式', () => {
    expect(resolveInputMode('chat', false)).toBe('quick');
    expect(resolveInputMode('chat', true)).toBe('think');
    expect(resolveInputMode('html', false)).toBe('think');
    expect(resolveInputMode('html', true)).toBe('research');
  });
});
