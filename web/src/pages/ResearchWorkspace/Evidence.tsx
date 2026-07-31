import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Alert } from 'antd';

import WorkspaceToolSwitcher from '@/components/WorkspaceToolSwitcher';
import { ROUTES } from '@/router/routes';
import {
  isFileAnalysisEvent,
  loadResearchRun,
  researchEventEvidence,
  researchWorkspaceApi,
  type ResearchRunSnapshot,
  type RunDiagnostics,
} from '@/services/researchWorkspace';

function runPath(template: string, runId: string): string {
  return template.replace(':runId', encodeURIComponent(runId));
}

export default function ResearchEvidence() {
  const { runId = '' } = useParams();
  const [snapshot, setSnapshot] = useState<ResearchRunSnapshot | null>(() => loadResearchRun(runId));
  const [diagnostics, setDiagnostics] = useState<RunDiagnostics | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    setSnapshot(loadResearchRun(runId));
    setDiagnostics(null);
    setError('');
    void researchWorkspaceApi.diagnostics(runId).then(setDiagnostics).catch((reason: unknown) => {
      setError(reason instanceof Error ? reason.message : '证据摘要暂不可用');
    });
  }, [runId]);

  if (!snapshot) return <main className="mx-auto w-full max-w-3xl px-5 py-8 sm:px-8"><Alert type="warning" showIcon message="未找到 Run 快照" /><Link className="mt-4 inline-block font-semibold text-sky-700" to={ROUTES.WORKSPACE_RESEARCH}>返回调研工作区 →</Link></main>;

  const fileEvents = snapshot.events.filter(isFileAnalysisEvent).map(researchEventEvidence).reverse();
  return <main className="mx-auto min-h-full w-full max-w-6xl px-5 py-7 sm:px-8"><div className="flex flex-wrap items-start justify-between gap-4"><div><p className="text-xs font-semibold uppercase tracking-[0.22em] text-sky-600">ResearchPilot · Evidence</p><h1 className="mt-2 text-3xl font-bold text-slate-950">证据与附件处理链</h1><p className="mt-2 text-sm text-slate-500">只显示工具、文件及 artifact 的安全元数据；不会展示原始提示词、Base64 或私有下载地址。</p></div><WorkspaceToolSwitcher /></div>{error && <Alert className="mt-5" type="warning" showIcon message="部分诊断数据未加载" description={error} />}
    <section className="mt-5 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm"><h2 className="font-bold text-slate-900">附件</h2><div className="mt-3 space-y-2">{snapshot.attachments.length === 0 ? <p className="text-sm text-slate-500">本次调研没有附件。</p> : snapshot.attachments.map((file) => <article key={file.resourceKey || file.name} className="flex flex-wrap items-center justify-between gap-2 rounded-2xl bg-slate-50 px-4 py-3"><span className="font-medium text-slate-800">{file.name}</span><span className="font-mono text-xs text-slate-500">{file.artifactHash ? `sha256:${file.artifactHash.slice(0, 16)}…` : 'hash pending'} · {file.size} B</span></article>)}</div></section>
    <section className="mt-5 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm"><h2 className="font-bold text-slate-900">文件分析生命周期</h2><div className="mt-3 space-y-2">{fileEvents.length === 0 ? <p className="text-sm text-slate-500">尚未收到文件分析事件。</p> : fileEvents.map((event, index) => <article key={`${event.kind}-${index}`} className="rounded-2xl bg-slate-50 p-4"><p className="font-mono text-xs font-semibold text-sky-700">{event.kind}</p><p className="mt-2 text-sm text-slate-700">{[event.fileName, event.strategy, event.uncertainty, event.degraded ? 'VLM/解析降级已标记' : undefined].filter(Boolean).join(' · ')}</p>{event.artifactReference && <p className="mt-1 font-mono text-xs text-slate-500">artifact: {event.artifactReference}</p>}</article>)}</div></section>
    <section className="mt-5 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm"><h2 className="font-bold text-slate-900">工具证据</h2><div className="mt-3 grid gap-3 md:grid-cols-2">{diagnostics?.toolInvocations.length ? diagnostics.toolInvocations.map((tool, index) => <article key={`${tool.toolName}-${index}`} className="rounded-2xl bg-slate-50 p-4"><p className="font-semibold text-slate-800">{tool.toolName}</p><p className="mt-2 text-xs text-slate-500">status {tool.status} · artifacts {tool.artifactCount}{tool.errorCode ? ` · ${tool.errorCode}` : ''}</p></article>) : <p className="text-sm text-slate-500">尚无可显示的工具调用账本。</p>}</div></section>
    <div className="mt-5 flex gap-3"><Link className="rounded-xl border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-700" to={runPath(ROUTES.WORKSPACE_RESEARCH_RUN, runId)}>返回进度</Link><Link className="rounded-xl bg-slate-950 px-4 py-2 text-sm font-semibold text-white" to={runPath(ROUTES.WORKSPACE_RESEARCH_REPORT, runId)}>查看报告 →</Link></div>
  </main>;
}
