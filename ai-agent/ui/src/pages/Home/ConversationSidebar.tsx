import { memo, useState, useCallback } from 'react';
import { NavLink } from 'react-router-dom';
import { motion, AnimatePresence } from 'motion/react';
import classNames from 'classnames';
import { ROUTES } from '@/router/routes';
import {
  SquarePen,
  Search,
  MoreHorizontal,
  DatabaseZap,
  MessagesSquare,
  WandSparkles,
  X,
  Pin,
  Trash2,
  Moon,
  Sun,
} from 'lucide-react';
import type { ConversationSessionItem } from '@/services/agentConversation';
import { useTheme } from '@/theme';

type SidebarView = 'chat' | 'mrag' | 'image-generation';

type NavItem = {
  key: SidebarView;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
};

const platformNavItems = [
  { to: ROUTES.PRICING, label: '定价' },
  { to: ROUTES.ORDERS, label: '订单' },
  { to: ROUTES.ACCOUNT, label: '额度中心' },
];

const navItems: NavItem[] = [
  {
    key: 'chat',
    label: '对话',
    icon: MessagesSquare,
  },
  {
    key: 'mrag',
    label: '知识库',
    icon: DatabaseZap,
  },
  {
    key: 'image-generation',
    label: '生图',
    icon: WandSparkles,
  },
];

type ConversationSidebarProps = {
  activeView: SidebarView;
  recentSessions: ConversationSessionItem[];
  recentSessionsLoading: boolean;
  selectedSessionId?: string;
  onNewChat: () => void;
  onSelectSession: (session: ConversationSessionItem) => void;
  onChangeView: (view: SidebarView) => void;
};

const ConversationSidebar = memo((props: ConversationSidebarProps) => {
  const {
    activeView,
    recentSessions,
    recentSessionsLoading,
    selectedSessionId,
    onNewChat,
    onSelectSession,
    onChangeView,
  } = props;

  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [hoveredSessionId, setHoveredSessionId] = useState<string | null>(null);
  const [expandedSessionId, setExpandedSessionId] = useState<string | null>(null);
  const { resolvedMode, setMode } = useTheme();

  const filteredSessions = searchQuery.trim()
    ? recentSessions.filter((s) =>
        (s.title || '未命名会话').toLowerCase().includes(searchQuery.toLowerCase()),
      )
    : recentSessions;

  const handleSearchToggle = useCallback(() => {
    setSearchOpen((prev) => !prev);
    if (searchOpen) {
      setSearchQuery('');
    }
  }, [searchOpen]);

  const handleMoreClick = useCallback((e: React.MouseEvent, sessionId: string) => {
    e.stopPropagation();
    setExpandedSessionId((prev) => (prev === sessionId ? null : sessionId));
  }, []);

  return (
    <div className="agent-sidebar hidden lg:flex">
      {/* 顶部操作区 */}
      <div className="shrink-0 pt-2 pb-2">
        <div className="mb-4 flex items-center gap-2 px-2">
          <img src="/bear-doctor-logo.png" alt="熊博士agent Logo" className="h-8 w-8 rounded-lg" />
          <span className="font-semibold text-[15px]">熊博士agent</span>
        </div>
        <button
          type="button"
          onClick={onNewChat}
          className="flex w-full items-center justify-center gap-2.5 rounded-xl border border-[var(--chat-border)] bg-[var(--chat-surface)] px-3.5 py-2.5 text-[13px] font-medium text-[var(--chat-text)] transition-all hover:bg-[var(--chat-surface-soft)]"
        >
          <SquarePen className="h-4 w-4 text-[var(--chat-text-soft)]" />
          <span>新聊天</span>
        </button>

        <button
          type="button"
          onClick={handleSearchToggle}
          className={classNames(
            'mt-2 flex w-full items-center gap-2.5 rounded-xl px-3.5 py-2.5 text-[13px] transition-colors',
            searchOpen
              ? 'bg-[var(--chat-surface-muted)] text-[var(--chat-text)]'
              : 'text-[var(--chat-text-soft)] hover:bg-[var(--chat-surface-muted)]',
          )}
        >
          <Search className="h-4 w-4" />
          <span>搜索聊天</span>
        </button>

        <AnimatePresence>
          {searchOpen && (
            <motion.div
              initial={{
                height: 0,
                opacity: 0,
              }}
              animate={{
                height: 'auto',
                opacity: 1,
              }}
              exit={{
                height: 0,
                opacity: 0,
              }}
              transition={{
                duration: 0.2,
                ease: [0.16, 1, 0.3, 1],
              }}
              className="overflow-hidden"
            >
              <div className="relative mt-2">
                <input
                  type="text"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder="搜索会话..."
                  autoFocus
                  className="w-full rounded-lg border border-[var(--chat-border)] bg-[var(--chat-surface)] px-3 py-2 pr-8 text-[13px] text-[var(--chat-text)] outline-none transition-colors placeholder:text-[var(--chat-text-muted)] focus:border-[var(--chat-border-strong)]"
                />
                {searchQuery && (
                  <button
                    type="button"
                    onClick={() => setSearchQuery('')}
                    className="absolute right-2 top-1/2 -translate-y-1/2 text-[var(--chat-text-muted)] hover:text-[var(--chat-text)]"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* 导航区 */}
      <div className="shrink-0 py-2">
        <div className="px-3 py-1 text-[11px] font-medium uppercase tracking-wider text-[var(--chat-text-muted)]">
          工作台
        </div>
        {navItems.map((item) => {
          const Icon = item.icon;
          const isActive = activeView === item.key;
          return (
            <button
              key={item.key}
              type="button"
              onClick={() => onChangeView(item.key)}
              className={classNames(
                'flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-[13px] transition-colors',
                isActive
                  ? 'bg-[var(--chat-surface-muted)] font-medium text-[var(--chat-text)]'
                  : 'text-[var(--chat-text-soft)] hover:bg-[var(--chat-surface-muted)] hover:text-[var(--chat-text)]',
              )}
            >
              <Icon className="h-4 w-4" />
              <span>{item.label}</span>
            </button>
          );
        })}
      </div>

      {/* 最近会话 */}
      <div className="flex min-h-0 flex-1 flex-col pt-2">
        <div className="mb-1.5 flex items-center justify-between px-3">
          <span className="text-[11px] font-medium uppercase tracking-wider text-[var(--chat-text-muted)]">
            最近
          </span>
          {recentSessionsLoading && (
            <span className="text-[11px] text-[var(--chat-text-muted)]">加载中...</span>
          )}
        </div>

        <div className="flex-1 overflow-y-auto scrollbar-hover">
          {filteredSessions.length === 0 ? (
            <div className="px-3 py-4 text-center text-[12px] text-[var(--chat-text-muted)]">
              {searchQuery.trim() ? '未找到匹配的会话' : '暂无会话'}
            </div>
          ) : (
            <div className="flex flex-col gap-0.5">
              {filteredSessions.map((session) => {
                const isActive = session.sessionId === selectedSessionId;
                const isHovered = session.sessionId === hoveredSessionId;
                const isExpanded = session.sessionId === expandedSessionId;

                return (
                  <div
                    key={session.sessionId}
                    className="relative"
                    onMouseEnter={() => setHoveredSessionId(session.sessionId)}
                    onMouseLeave={() => setHoveredSessionId(null)}
                  >
                    <button
                      type="button"
                      onClick={() => onSelectSession(session)}
                      className={classNames(
                        'group flex w-full items-center gap-2 rounded-lg py-2 pl-2.5 pr-9 text-left transition-colors',
                        isActive
                          ? 'bg-[var(--chat-surface-muted)] text-[var(--chat-text)]'
                          : 'text-[var(--chat-text-soft)] hover:bg-[var(--chat-surface-muted)] hover:text-[var(--chat-text)]',
                      )}
                    >
                      <span className="min-w-0 flex-1 truncate text-[13px]">
                        {session.title || '未命名会话'}
                      </span>
                    </button>
                    <button
                      type="button"
                      aria-label={`更多会话操作：${session.title || '未命名会话'}`}
                      aria-expanded={isExpanded}
                      onClick={(event) => handleMoreClick(event, session.sessionId)}
                      className={classNames(
                        'absolute right-2 top-1/2 -translate-y-1/2 rounded p-0.5 text-[var(--chat-text-muted)] transition-all hover:bg-[var(--chat-surface-muted)] hover:text-[var(--chat-text)]',
                        isHovered || isExpanded ? 'opacity-100' : 'opacity-0',
                      )}
                    >
                      <MoreHorizontal className="h-3.5 w-3.5" />
                    </button>

                    {/* 更多操作下拉 */}
                    {isExpanded && (
                      <div className="absolute right-2 top-full z-10 mt-1 w-32 rounded-lg border border-[var(--chat-border)] bg-[var(--chat-surface)] py-1 shadow-[var(--shadow-md)]">
                        <button
                          type="button"
                          disabled
                          title="置顶功能暂未开放"
                          className="flex w-full cursor-not-allowed items-center gap-2 px-3 py-2 text-[12px] text-[var(--chat-text-muted)] opacity-65"
                        >
                          <Pin className="h-3.5 w-3.5" />
                          <span>置顶（暂未开放）</span>
                        </button>
                        <button
                          type="button"
                          disabled
                          title="删除功能暂未开放"
                          className="flex w-full cursor-not-allowed items-center gap-2 px-3 py-2 text-[12px] text-[var(--chat-text-muted)] opacity-65"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                          <span>删除（暂未开放）</span>
                        </button>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      <div className="shrink-0 border-t border-[var(--chat-border)] py-3">
        <div className="flex flex-col gap-1">
          {platformNavItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                classNames(
                  'rounded-lg px-3 py-2 text-[12px] transition-colors',
                  isActive
                    ? 'bg-[var(--chat-surface-muted)] text-[var(--chat-text)]'
                    : 'text-[var(--chat-text-soft)] hover:bg-[var(--chat-surface-muted)] hover:text-[var(--chat-text)]',
                )
              }
            >
              {item.label}
            </NavLink>
          ))}
          <button
            type="button"
            onClick={() => setMode(resolvedMode === 'dark' ? 'light' : 'dark')}
            aria-label={resolvedMode === 'dark' ? '切换到浅色模式' : '切换到深色模式'}
            className="flex items-center gap-2.5 rounded-lg px-3 py-2 text-[12px] text-[var(--chat-text-soft)] transition-colors hover:bg-[var(--chat-surface-muted)] hover:text-[var(--chat-text)]"
          >
            {resolvedMode === 'dark' ? (
              <Sun className="h-3.5 w-3.5" />
            ) : (
              <Moon className="h-3.5 w-3.5" />
            )}
            <span>{resolvedMode === 'dark' ? '浅色模式' : '深色模式'}</span>
          </button>
        </div>
      </div>
    </div>
  );
});

ConversationSidebar.displayName = 'ConversationSidebar';

export default ConversationSidebar;
