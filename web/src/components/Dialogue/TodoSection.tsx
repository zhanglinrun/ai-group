import { FC, memo } from 'react';
import { motion } from 'motion/react';
import { Layers } from 'lucide-react';

const resolveTodoTone = (status: CHAT.TodoStatus) => {
  switch (status) {
    case 'completed':
      return {
        badgeClass: 'bg-[#0071e3]/10 text-[#0071e3]',
        dotClass: 'bg-[#0071e3]',
        label: '已完成',
      };
    case 'in_progress':
      return {
        badgeClass: 'bg-amber-500/10 text-amber-600',
        dotClass: 'bg-amber-500',
        label: '进行中',
      };
    case 'blocked':
      return {
        badgeClass: 'bg-orange-500/10 text-orange-700',
        dotClass: 'bg-orange-500',
        label: '已阻塞',
      };
    case 'failed':
      return {
        badgeClass: 'bg-rose-500/10 text-rose-700',
        dotClass: 'bg-rose-500',
        label: '失败',
      };
    default:
      return {
        badgeClass: 'bg-[var(--chat-surface-muted)] text-[var(--chat-text-muted)]',
        dotClass: 'bg-[var(--chat-text-muted)]',
        label: '未开始',
      };
  }
};

export const TodoSection: FC<{ title?: string; todos: CHAT.TodoItem[] }> = memo(
  ({ title, todos }) => {
    if (!todos.length) return null;

    const completedCount = todos.filter((todo) => todo.status === 'completed').length;

    return (
      <motion.section
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.24, ease: [0.25, 0.46, 0.45, 0.94] }}
        className="overflow-hidden rounded-2xl bg-[var(--chat-surface-soft)]/90 px-4 py-4 shadow-[var(--shadow-sm)]"
        aria-label="agent-todos"
      >
        <div className="mb-4 flex items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-[var(--chat-surface)]/95 text-[var(--chat-text-soft)] shadow-[var(--shadow-xs)]">
              <Layers className="h-5 w-5" strokeWidth={1.75} />
            </div>
            <div className="min-w-0">
              <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-[var(--chat-text-muted)]">
                Todo
              </p>
              <p className="text-[15px] font-semibold leading-snug tracking-[-0.02em] text-[var(--chat-text)]">
                {title || '任务进度'}
              </p>
            </div>
          </div>
          <div className="shrink-0 rounded-full bg-[var(--chat-surface)] px-3 py-1 text-[12px] font-medium text-[var(--chat-text-soft)]">
            {completedCount}/{todos.length}
          </div>
        </div>

        <div className="space-y-2.5">
          {todos.map((todo, index) => {
            const tone = resolveTodoTone(todo.status);
            return (
              <motion.div
                key={todo.id}
                initial={{ opacity: 0, x: -6 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{
                  delay: Math.min(index * 0.06, 0.36),
                  duration: 0.22,
                  ease: [0.25, 0.46, 0.45, 0.94],
                }}
                className="rounded-xl bg-[var(--chat-surface)]/75 px-3 py-3 shadow-[var(--shadow-xs)]"
              >
                <div className="flex items-start gap-3">
                  <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-[var(--chat-surface-muted)] text-[12px] font-semibold tabular-nums text-[var(--chat-text-soft)]">
                    {index + 1}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-[14px] font-medium leading-snug tracking-[-0.01em] text-[var(--chat-text)]">
                        {todo.title}
                      </span>
                      <span
                        className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ${tone.badgeClass}`}
                      >
                        <span className={`h-1.5 w-1.5 rounded-full ${tone.dotClass}`} />
                        {tone.label}
                      </span>
                      {todo.evidencePolicy === 'TOOL' ? (
                        <span className="rounded-full bg-indigo-500/10 px-2 py-0.5 text-[11px] font-medium text-indigo-600">
                          工具证据
                        </span>
                      ) : null}
                      {todo.evidencePolicy === 'NONE' ? (
                        <span className="rounded-full bg-slate-500/10 px-2 py-0.5 text-[11px] font-medium text-slate-600">
                          过程步骤
                        </span>
                      ) : null}
                    </div>
                    {todo.detail ? (
                      <p className="mt-2 text-[13px] leading-relaxed text-[var(--chat-text-soft)]">
                        {todo.detail}
                      </p>
                    ) : null}
                  </div>
                </div>
              </motion.div>
            );
          })}
        </div>
      </motion.section>
    );
  },
);

TodoSection.displayName = 'TodoSection';
