import { CheckIcon, CircleIcon, LoaderCircleIcon, XCircleIcon } from 'lucide-react';

const PHASES: Array<{ key: CHAT.AgentRunPhase; label: string }> = [
  { key: 'ANALYZING', label: '分析任务' },
  { key: 'PLANNING', label: '建立计划' },
  { key: 'EXECUTING', label: '执行工具' },
  { key: 'VERIFYING', label: '验收结果' },
  { key: 'FINALIZING', label: '整理交付' },
];

const phaseIndex = (phase?: CHAT.AgentRunPhase) => {
  const index = PHASES.findIndex((item) => item.key === phase);
  return index < 0 ? 0 : index;
};
const statusCopy: Record<CHAT.AgentRunStatus, string> = {
  RUNNING: '任务执行中',
  SUCCESS: '任务已完成',
  FAILED: '任务未完成',
  STOPPED: '任务已停止',
  TIMEOUT: '任务已超时',
};

type Props = {
  run?: CHAT.AgentLoopViewState;
};

/**
 * 面向用户的 Agent 阶段进度，不暴露内部 chain-of-thought。
 * 阶段来源于后端 canonical phase_changed/run_finished 事实。
 */
export const AgentRunProgress = ({ run }: Props) => {
  if (!run) return null;

  const terminal = run.status !== 'RUNNING';
  const failed = run.status === 'FAILED' || run.status === 'TIMEOUT' || run.status === 'STOPPED';
  const activeIndex = terminal && run.status === 'SUCCESS' ? PHASES.length - 1 : phaseIndex(run.phase);

  return (
    <section
      aria-label="agent-run-progress"
      className="mt-4 overflow-hidden rounded-2xl border border-[var(--chat-border)]/18 bg-[var(--chat-surface-soft)]/55 px-4 py-3"
    >
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          {failed ? (
            <XCircleIcon className="size-4 shrink-0 text-rose-500" />
          ) : terminal ? (
            <CheckIcon className="size-4 shrink-0 text-emerald-500" />
          ) : (
            <LoaderCircleIcon className="size-4 shrink-0 animate-spin text-[var(--primary)]" />
          )}
          <span className="truncate text-[13px] font-medium text-[var(--chat-text)]">
            {statusCopy[run.status]}
          </span>
        </div>
        {run.phase ? (
          <span className="shrink-0 text-[11px] text-[var(--chat-text-soft)]">{PHASES[activeIndex]?.label}</span>
        ) : null}
      </div>

      <div className="mt-3 grid grid-cols-5 gap-1.5">
        {PHASES.map((phase, index) => {
          const complete = terminal ? run.status === 'SUCCESS' || index < activeIndex : index < activeIndex;
          const active = !terminal && index === activeIndex;
          return (
            <div key={phase.key} className="min-w-0">
              <div className="flex items-center gap-1.5">
                <div
                  className={[
                    'flex size-5 shrink-0 items-center justify-center rounded-full border',
                    complete
                      ? 'border-emerald-500 bg-emerald-500 text-white'
                      : active
                        ? 'border-[var(--primary)] bg-[var(--primary)]/10 text-[var(--primary)]'
                        : 'border-[var(--chat-border)]/70 text-[var(--chat-text-soft)]',
                  ].join(' ')}
                >
                  {complete ? <CheckIcon className="size-3" /> : active ? <LoaderCircleIcon className="size-3 animate-spin" /> : <CircleIcon className="size-2" />}
                </div>
                <div
                  className={[
                    'h-px min-w-0 flex-1',
                    index === PHASES.length - 1
                      ? 'bg-transparent'
                      : complete
                        ? 'bg-emerald-500/60'
                        : 'bg-[var(--chat-border)]/45',
                  ].join(' ')}
                />
              </div>
              <div className="mt-1 truncate text-[10px] text-[var(--chat-text-soft)]">{phase.label}</div>
            </div>
          );
        })}
      </div>
    </section>
  );
};
