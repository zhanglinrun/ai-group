import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Alert, message } from 'antd';

import WorkspaceToolSwitcher from '@/components/WorkspaceToolSwitcher';
import { ROUTES } from '@/router/routes';
import {
  appendResearchEvent,
  loadResearchRun,
  researchEventEvidence,
  researchWorkspaceApi,
  saveResearchRun,
  subscribeResearchRunEvents,
  type ResearchRunSnapshot,
  type RunDiagnostics,
} from '@/services/researchWorkspace';

function runPath(template: string, runId: string): string {
  return template.replace(':runId', encodeURIComponent(runId));
}

function readableStatus(status: ResearchRunSnapshot['status']): string {
  if (status === 'COMPLETED') return '已完成';
  if (status === 'FAILED') return '失败';
  return status === 'CANCEL_REQUESTED' ? '取消请求已提交' : '进行中';
}

function displayNumber(value?: number): string {
  return typeof value === 'number' ? value.toLocaleString() : '—';
}

export default function ResearchRun() {
  const { runId = '' } = useParams();
  const [snapshot, setSnapshot] = useState<ResearchRunSnapshot | null>(() => loadResearchRun(runId));
  const [diagnostics, setDiagnostics] = useState<RunDiagnostics | null>(null);
  const [recoveryError, setRecoveryError] = useState('');
  const [reconnectNotice, setReconnectNotice] = useState('');
  const [recoveryEpoch, setRecoveryEpoch] = useState(0);
  const [canceling, setCanceling] = useState(false);

  useEffect(() => {
    const restored = loadResearchRun(runId);
    setSnapshot(restored);
    setDiagnostics(null);
    setRecoveryError('');
    setReconnectNotice('');
    if (!restored) return;

    let active = true;
    const refreshSnapshot = () => void Promise.all([
      researchWorkspaceApi.diagnostics(runId),
      researchWorkspaceApi.listAttachments(restored.sessionId),
    ]).then(([nextDiagnostics, attachments]) => {
      if (!active) return;
      setDiagnostics(nextDiagnostics);
      setSnapshot((current) => {
        if (!current) return current;
        const next = { ...current, attachments: attachments || current.attachments };
        saveResearchRun(next);
        return next;
      });
    }).catch((error: unknown) => {
      if (!active) return;
      setRecoveryError(error instanceof Error ? error.message : '无法加载最新 Run 状态');
    });
    refreshSnapshot();

    const unsubscribe = subscribeResearchRunEvents(
      runId,
      restored.cursor,
      {
        onEvent: (event, nextCursor) => {
          if (!active) return;
          setReconnectNotice('');
          setSnapshot((current) => {
            if (!current) return current;
            const next = appendResearchEvent(current, event, nextCursor);
            saveResearchRun(next);
            return next;
          });
        },
        onError: (error) => {
          if (active) setRecoveryError(error.message || 'Run 事件恢复失败');
        },
        onReconnect: (attempt, delayMs) => {
          if (active) setReconnectNotice(`连接中断，${delayMs / 1000} 秒后进行第 ${attempt} 次恢复（使用 Last-Event-ID）。`);
        },
        onGap: (gap) => {
          if (!active) return;
          setReconnectNotice('检测到事件保留窗口缺口，正在重新拉取 Run 快照并从可用游标恢复。');
          setSnapshot((current) => {
            if (!current) return current;
            const next = { ...current, events: [], cursor: Math.max(0, gap.earliestRetained - 1) };
            saveResearchRun(next);
            return next;
          });
          refreshSnapshot();
          setRecoveryEpoch((current) => current + 1);
        },
      },
    );
    return () => {
      active = false;
      unsubscribe();
    };
  }, [runId, recoveryEpoch]);

  const requestCancellation = async () => {
    if (!snapshot || snapshot.status !== 'RUNNING' || canceling) return;
    setCanceling(true);
    try {
      const result = await researchWorkspaceApi.cancel(runId);
      if (result.accepted) {
        message.success('取消请求已记录；已完成的外部副作用不会回滚。');
        setSnapshot((current) => {
          if (!current) return current;
          const next = { ...current, status: 'CANCEL_REQUESTED' as const };
          saveResearchRun(next);
          return next;
        });
      } else {
        message.info('Run 已处于终态或取消请求已经存在。');
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '取消请求失败');
    } finally {
      setCanceling(false);
    }
  };

  if (!snapshot) {
    return <main className="mx-auto w-full max-w-3xl px-5 py-8 sm:px-8">
      <Alert type="warning" showIcon message="此浏览器会话中没有该 Run 的恢复快照" description="请从 Research Workspace 重新进入，或在原浏览器会话打开此页面。" />
      <Link className="mt-4 inline-block font-semibold text-sky-700" to={ROUTES.WORKSPACE_RESEARCH}>返回调研工作区 →</Link>
    </main>;
  }

  const recentEvents = snapshot.events.slice(-10).reverse();
  return <main className="mx-auto min-h-full w-full max-w-6xl px-5 py-7 sm:px-8">
    <div className="flex flex-wrap items-start justify-between gap-4"><div><p className="text-xs font-semibold uppercase tracking-[0.22em] text-sky-600">ResearchPilot · Run</p><h1 className="mt-2 text-3xl font-bold text-slate-950">调研进度与恢复</h1><p className="mt-2 max-w-3xl text-sm text-slate-500">{snapshot.query}</p></div><WorkspaceToolSwitcher /></div>
    {recoveryError && <Alert className="mt-5" type="warning" showIcon message="实时恢复暂不可用" description={recoveryError} closable onClose={() => setRecoveryError('')} />}
    {reconnectNotice && <Alert className="mt-5" type="info" showIcon message="断线恢复" description={reconnectNotice} />}
    <section className="mt-5 grid gap-4 md:grid-cols-5">
      <Metric label="状态" value={readableStatus(snapshot.status)} />
      <Metric label="模式" value={snapshot.executionMode} />
      <Metric label="附件" value={`${snapshot.attachments.length} 个`} />
      <Metric label="事件游标" value={String(snapshot.cursor)} />
      <Metric label="预估额度上限" value={snapshot.estimatedMicrocredits?.toLocaleString() || '—'} />
    </section>
    <section className="mt-5 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm"><div className="flex flex-wrap items-center justify-between gap-3"><div><h2 className="font-bold text-slate-900">阶段进度</h2><p className="mt-1 text-xs text-slate-500">重新连接会从已确认的事件游标继续；仅展示安全的事件摘要。{snapshot.quotaEstimateBasis ? ` ${snapshot.quotaEstimateBasis}。` : ''}</p></div><div className="flex flex-wrap gap-3"><button type="button" disabled={snapshot.status !== 'RUNNING' || canceling} onClick={() => void requestCancellation()} className="rounded-xl border border-rose-200 px-3 py-2 text-sm font-semibold text-rose-700 disabled:opacity-40">{canceling ? '正在提交取消…' : '取消 Run'}</button><Link className="rounded-xl border border-slate-200 px-3 py-2 text-sm font-semibold text-slate-700" to={runPath(ROUTES.WORKSPACE_RESEARCH_EVIDENCE, runId)}>证据链</Link><Link className="rounded-xl bg-slate-950 px-3 py-2 text-sm font-semibold text-white" to={runPath(ROUTES.WORKSPACE_RESEARCH_REPORT, runId)}>报告与 Trace</Link></div></div>
      <div className="mt-4 space-y-2">{recentEvents.length === 0 ? <p className="rounded-xl bg-slate-50 p-4 text-sm text-slate-500">等待第一个持久化进度事件…</p> : recentEvents.map((event, index) => { const evidence = researchEventEvidence(event); return <article key={`${evidence.kind}-${index}`} className="flex flex-wrap items-center justify-between gap-2 rounded-2xl bg-slate-50 px-4 py-3"><span className="font-mono text-xs font-semibold text-sky-700">{evidence.kind}</span><span className="text-xs text-slate-500">{[evidence.fileName, evidence.strategy, evidence.degraded ? '已降级' : undefined].filter(Boolean).join(' · ') || '运行事件'}</span></article>; })}</div>
    </section>
    <section className="mt-5 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm"><h2 className="font-bold text-slate-900">额度与执行摘要</h2><div className="mt-4 grid gap-4 sm:grid-cols-3"><Metric label="总 Tokens" value={displayNumber(diagnostics?.totalTokens)} /><Metric label="耗时" value={diagnostics?.durationMs === undefined ? '—' : `${diagnostics.durationMs} ms`} /><Metric label="工具调用" value={String(diagnostics?.toolInvocations.length ?? 0)} /></div>{diagnostics?.errorCode && <p className="mt-3 text-xs font-semibold text-rose-600">错误代码：{diagnostics.errorCode}</p>}</section>
    <button type="button" className="mt-5 text-sm text-slate-500 underline" onClick={() => message.info('此 Run 的恢复快照仅保存在当前浏览器会话；完成后可通过报告页查看安全 Trace 摘要。')}>恢复机制说明</button>
  </main>;
}

function Metric({ label, value }: { label: string; value: string }) {
  return <article className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm"><p className="text-xs text-slate-500">{label}</p><p className="mt-2 text-lg font-bold text-slate-900">{value}</p></article>;
}
