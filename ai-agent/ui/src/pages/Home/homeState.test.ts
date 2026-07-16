import { describe, expect, it } from 'vitest';

import {
  deriveConversationMetaFromInput,
  resolveNewConversationMode,
  shouldHydrateConversationHistory,
} from './homeState';

describe('homeState', () => {
  it('切到 dataAgent 时应清空角色并关闭 deepThink', () => {
    expect(
      deriveConversationMetaFromInput(
        {
          outputStyle: 'dataAgent',
          deepThink: true,
        },
        {
          productType: 'html',
          currentRole: {
            agentId: 'agent-1',
            agentName: '默认角色',
            available: true,
            defaultRole: true,
          },
        },
      ),
    ).toMatchObject({
      productType: 'dataAgent',
      deepThink: false,
      role: null,
    });
  });

  it('聊天输出应保留用户选择的深度思考标志', () => {
    const role = {
      agentId: 'agent-1',
      agentName: '默认角色',
      available: true,
      defaultRole: true,
    };

    expect(
      deriveConversationMetaFromInput(
        { outputStyle: 'chat', deepThink: true },
        { productType: 'chat', currentRole: role },
      ),
    ).toEqual({ productType: 'chat', deepThink: true, role });
  });

  it('新聊天默认普通聊天，不应回落到网页报告模式', () => {
    expect(resolveNewConversationMode()).toEqual({ productType: 'chat', deepThink: false });
    expect(resolveNewConversationMode({ productType: 'html', deepThink: true })).toEqual({
      productType: 'html',
      deepThink: true,
    });
  });

  it('仅在未 hydrate 且没有内容时才恢复历史', () => {
    expect(
      shouldHydrateConversationHistory({
        conversation: {
          sessionId: 'session-1',
          chatList: [],
          dataChatList: [],
        } as unknown as CHAT.ConversationHistory,
        hydratedSessionIds: new Set<string>(),
      }),
    ).toBe(true);

    expect(
      shouldHydrateConversationHistory({
        conversation: {
          sessionId: 'session-1',
          chatList: [{} as CHAT.ChatItem],
          dataChatList: [],
        } as unknown as CHAT.ConversationHistory,
        hydratedSessionIds: new Set<string>(),
      }),
    ).toBe(false);
  });
});
