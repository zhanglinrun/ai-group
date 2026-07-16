import { useEffect, useState } from 'react';
import { RotateCcw, ShieldCheck, TriangleAlert } from 'lucide-react';

import { requiresExplicitRestartConfirmation } from '@/utils/checkpoint';
import { getCheckpointPhaseLabel } from './checkpointPresentation';

type Props = {
  checkpoint: CHAT.AgentCheckpoint;
  runStatus?: string;
  runLoading: boolean;
  disabled?: boolean;
  onResume?: (decision: CHAT.CheckpointResumeDecision) => void;
};

const CheckpointResumeCard = ({ checkpoint, runStatus, runLoading, disabled, onResume }: Props) => {
  const [confirmingRestart, setConfirmingRestart] = useState(false);
  const normalizedRunStatus = String(runStatus || '').toUpperCase();
  const sourceRunCompleted = normalizedRunStatus === 'SUCCESS';
  const canResume =
    checkpoint.status === 'AVAILABLE' &&
    checkpoint.resumable &&
    !runLoading &&
    !sourceRunCompleted &&
    !disabled;

  useEffect(() => {
    setConfirmingRestart(false);
  }, [checkpoint.checkpointId]);

  const requestResume = (decision: CHAT.CheckpointResumeDecision) => {
    if (!canResume) {
      return;
    }
    if (requiresExplicitRestartConfirmation(decision)) {
      setConfirmingRestart(true);
      return;
    }
    onResume?.(decision);
  };

  const confirmRestart = () => {
    if (!canResume) {
      return;
    }
    setConfirmingRestart(false);
    onResume?.('RESTART_FROM_CHECKPOINT');
  };

  const availabilityText =
    checkpoint.status === 'RESUMED'
      ? `已按 ${checkpoint.resumeDecision || 'SAFE_ONLY'} 恢复`
      : runLoading
        ? '当前运行仍在继续，结束或中断后可恢复'
        : sourceRunCompleted
          ? '源运行已完成，检查点已关闭'
          : checkpoint.resumable
            ? '可用于失败或中断后的节点级恢复'
            : '该检查点不可恢复';

  return (
    <section className="mt-3 rounded-2xl border border-sky-200/80 bg-sky-50/65 px-4 py-3 text-slate-700 shadow-[var(--shadow-xs)]">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex items-start gap-2.5">
          <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-white/80 text-sky-700">
            <RotateCcw className="h-4 w-4" />
          </div>
          <div className="min-w-0">
            <div className="text-[13px] font-semibold text-slate-800">当前连接收到的最近恢复点</div>
            <div className="mt-0.5 text-[11px] text-slate-500">{availabilityText}</div>
          </div>
        </div>
        <span className="rounded-full border border-sky-200 bg-white/75 px-2 py-1 text-[11px] font-medium text-sky-700">
          {getCheckpointPhaseLabel(checkpoint.phase)}
        </span>
      </div>

      <dl className="mt-3 grid gap-1.5 text-[11px] sm:grid-cols-2">
        <div className="min-w-0">
          <dt className="text-slate-400">Checkpoint ID</dt>
          <dd className="break-all font-mono text-slate-600" title={checkpoint.checkpointId}>
            {checkpoint.checkpointId}
          </dd>
        </div>
        <div>
          <dt className="text-slate-400">进度</dt>
          <dd className="text-slate-600">
            序号 {checkpoint.sequence ?? '-'} · 下一步 {checkpoint.nextStepIndex ?? '-'}
          </dd>
        </div>
      </dl>

      <p className="mt-2 text-[10px] leading-relaxed text-slate-400">
        仅展示本次 SSE 连接收到的状态；刷新页面或打开历史回放时暂不还原此卡片。
      </p>

      {checkpoint.status === 'AVAILABLE' ? (
        <div className="mt-3 rounded-xl border border-amber-200/80 bg-amber-50/80 px-3 py-2 text-[11px] leading-relaxed text-amber-800">
          <div className="flex items-start gap-2">
            <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            <span>
              SAFE_ONLY 会先校验工具重放风险并默认拒绝未知副作用；强制重启可能重复调用外部写操作。
            </span>
          </div>
        </div>
      ) : null}

      {checkpoint.status === 'AVAILABLE' && !confirmingRestart ? (
        <div className="mt-3 flex flex-wrap gap-2">
          <button
            type="button"
            disabled={!canResume}
            onClick={() => requestResume('SAFE_ONLY')}
            className="inline-flex items-center gap-1.5 rounded-lg bg-sky-700 px-3 py-1.5 text-[11px] font-medium text-white transition-colors hover:bg-sky-800 disabled:cursor-not-allowed disabled:opacity-45"
          >
            <ShieldCheck className="h-3.5 w-3.5" />
            安全恢复（SAFE_ONLY）
          </button>
          <button
            type="button"
            disabled={!canResume}
            onClick={() => requestResume('RESTART_FROM_CHECKPOINT')}
            className="rounded-lg border border-rose-200 bg-white/80 px-3 py-1.5 text-[11px] font-medium text-rose-700 transition-colors hover:bg-rose-50 disabled:cursor-not-allowed disabled:opacity-45"
          >
            强制重启
          </button>
        </div>
      ) : null}

      {checkpoint.status === 'AVAILABLE' && confirmingRestart ? (
        <div
          role="alert"
          className="mt-3 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-[11px] text-rose-800"
        >
          <p className="font-medium">确认承担工具重复执行风险？</p>
          <p className="mt-1 leading-relaxed">仅在你已核对外部写操作、支付、发信等副作用后继续。</p>
          <div className="mt-2 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={confirmRestart}
              className="rounded-lg bg-rose-700 px-3 py-1.5 font-medium text-white hover:bg-rose-800"
            >
              确认 RESTART_FROM_CHECKPOINT
            </button>
            <button
              type="button"
              onClick={() => setConfirmingRestart(false)}
              className="rounded-lg border border-rose-200 bg-white px-3 py-1.5 font-medium text-rose-700"
            >
              取消
            </button>
          </div>
        </div>
      ) : null}
    </section>
  );
};

export default CheckpointResumeCard;
