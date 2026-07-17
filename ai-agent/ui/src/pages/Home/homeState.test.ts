import { describe, expect, it } from 'vitest';

import {
  deriveConversationMetaFromInput,
  resolveNewConversationMode,
  shouldHydrateConversationHistory,
} from './homeState';

describe('homeState', () => {
  it('切到 dataAgent 时应清空角色并回到标准执行模式', () => {
    expect(
      deriveConversationMetaFromInput(
        {
          outputStyle: 'dataAgent',
          executionMode: 'DEEP',
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
      executionMode: 'STANDARD',
      role: null,
    });
  });

  it('聊天输出应保留用户选择的执行模式', () => {
    const role = {
      agentId: 'agent-1',
      agentName: '默认角色',
      available: true,
      defaultRole: true,
    };

    expect(
      deriveConversationMetaFromInput(
        { outputStyle: 'chat', executionMode: 'DEEP' },
        { productType: 'chat', currentRole: role },
      ),
    ).toEqual({ productType: 'chat', executionMode: 'DEEP', role });
  });

  it('新聊天默认普通聊天，不应回落到网页报告模式', () => {
    expect(resolveNewConversationMode()).toEqual({
      productType: 'chat',
      executionMode: 'STANDARD',
    });
    expect(resolveNewConversationMode({ productType: 'html', executionMode: 'DEEP' })).toEqual({
      productType: 'html',
      executionMode: 'DEEP',
    });
    expect(resolveNewConversationMode({ productType: 'docs', executionMode: 'AUTO' })).toEqual({
      productType: 'docs',
      executionMode: 'AUTO',
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
