import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import ConversationSidebar from './ConversationSidebar';

vi.mock('motion/react', () => ({
  motion: {
    div: ({ children, ...props }: React.HTMLAttributes<HTMLDivElement>) => (
      <div {...props}>{children}</div>
    ),
  },
  AnimatePresence: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('@/theme', () => ({
  useTheme: () => ({ resolvedMode: 'light', setMode: vi.fn() }),
}));

describe('ConversationSidebar', () => {
  it('会话选择与更多操作应是同级 button，不能产生嵌套 button', () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <ConversationSidebar
          activeView="chat"
          recentSessions={[
            {
              sessionId: 'session-1',
              title: '项目风险分析',
              status: 'SUCCESS',
              latestQueryText: '继续分析',
              runCount: 1,
              finishedRunCount: 1,
              failedRunCount: 0,
              startedAt: '2026-07-15T10:00:00',
              lastActiveAt: '2026-07-15T10:01:00',
            },
          ]}
          recentSessionsLoading={false}
          selectedSessionId="session-1"
          onNewChat={() => {}}
          onSelectSession={() => {}}
          onDeleteSession={() => {}}
          onChangeView={() => {}}
        />
      </MemoryRouter>,
    );

    const titleIndex = html.indexOf('项目风险分析');
    const sessionButtonCloseIndex = html.indexOf('</button>', titleIndex);
    const moreButtonIndex = html.indexOf('更多会话操作：项目风险分析');

    expect(titleIndex).toBeGreaterThan(-1);
    expect(sessionButtonCloseIndex).toBeGreaterThan(titleIndex);
    expect(moreButtonIndex).toBeGreaterThan(sessionButtonCloseIndex);
  });

  it('只展示已开放的删除操作，不展示知识库和置顶占位', () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <ConversationSidebar
          activeView="chat"
          recentSessions={[]}
          recentSessionsLoading={false}
          onNewChat={() => {}}
          onSelectSession={() => {}}
          onDeleteSession={() => {}}
          onChangeView={() => {}}
        />
      </MemoryRouter>,
    );

    expect(html).not.toContain('知识库');
    expect(html).not.toContain('暂未开放');
  });
});
