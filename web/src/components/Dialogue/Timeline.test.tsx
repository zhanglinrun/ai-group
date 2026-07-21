import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { Timeline } from './Timeline';

function createChat(overrides?: Partial<CHAT.ChatItem>): CHAT.ChatItem {
  return {
    sessionId: 'timeline-session',
    requestId: 'timeline-request',
    query: '测试时间线',
    files: [],
    forceStop: false,
    loading: false,
    tasks: [],
    timeline: [],
    multiAgent: { tasks: [] },
    ...overrides,
  } as CHAT.ChatItem;
}

describe('Timeline component', () => {
  it('最后一组任务在 loading=false 且全部完成时显示完成图标', () => {
    const html = renderToStaticMarkup(
      <Timeline
        chat={createChat({
          loading: false,
          tasks: [
            [
              {
                task: '收集资料',
                children: [{ finish: true, isFinal: true }],
              } as unknown as CHAT.Task,
            ],
          ],
        })}
        changeActiveChat={vi.fn()}
      />,
    );

    expect(html).toContain('aria-label="timeline-completed"');
  });

  it('最后一组仍在 loading 时显示加载态而不是完成图标', () => {
    const html = renderToStaticMarkup(
      <Timeline
        chat={createChat({
          loading: true,
          tasks: [
            [
              {
                task: '收集资料',
                children: [{ finish: true, isFinal: true }],
              } as unknown as CHAT.Task,
            ],
          ],
        })}
        changeActiveChat={vi.fn()}
      />,
    );

    expect(html).toContain('aria-label="timeline-loading"');
    expect(html).not.toContain('aria-label="timeline-completed"');
  });

  it('deep search 检索阶段渲染预览卡片而不是普通工具行', () => {
    const html = renderToStaticMarkup(
      <Timeline
        chat={createChat({
          tasks: [
            [
              {
                task: '深度搜索',
                children: [
                  {
                    messageType: 'deep_search',
                    resultMap: {
                      messageType: 'search',
                      searchResult: {
                        query: ['子问题一'],
                        docs: [
                          [
                            {
                              title: '来源A',
                              link: 'https://example.com/a',
                              content: '摘要A',
                            },
                          ],
                        ],
                      },
                    },
                  } as unknown as CHAT.Task,
                ],
              } as unknown as CHAT.Task,
            ],
          ],
        })}
        changeActiveChat={vi.fn()}
      />,
    );

    expect(html).toContain('子问题一');
    expect(html).toContain('搜索完成');
  });

  it('实时与历史共用的 tool_result 任务渲染平台业务卡片和后端 CTA', () => {
    const html = renderToStaticMarkup(
      <Timeline
        chat={createChat({
          tasks: [
            [
              {
                children: [
                  {
                    messageType: 'tool_result',
                    toolResult: {
                      toolName: 'platform_context',
                      toolResult: JSON.stringify({
                        operation: 'orders',
                        status: 'DEGRADED',
                        complete: false,
                        degraded: true,
                        authoritativeEmpty: false,
                        data: { items: [] },
                        message: '订单服务暂时不可用。',
                        cta: { label: '查看订单', path: '/orders' },
                      }),
                    },
                  } as unknown as CHAT.Task,
                ],
              } as unknown as CHAT.Task,
            ],
          ],
        })}
        changeActiveChat={vi.fn()}
      />,
    );

    expect(html).toContain('platform-context-orders');
    expect(html).toContain('数据可能不完整');
    expect(html).toContain('订单服务暂时不可用。');
    expect(html).toContain('href="/orders"');
  });
});
