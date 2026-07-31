import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Alert } from 'antd';

import WorkspaceToolSwitcher from '@/components/WorkspaceToolSwitcher';
import { ROUTES } from '@/router/routes';
import { loadResearchRun, researchWorkspaceApi, type ResearchRunSnapshot, type RunDiagnostics } from '@/services/researchWorkspace';

function runPath(template: string, runId: string): string {
  return template.replace(':runId', encodeURIComponent(runId));
}

function isReportArtifact(role: string): boolean {
  return /REPORT|MARKDOWN|HTML/i.test(role);
}

export default function ResearchReport() {
  const { runId = '' } = useParams();
  const [snapshot, setSnapshot] = useState<ResearchRunSnapshot | null>(() => loadResearchRun(runId));
  const [diagnostics, setDiagnostics] = useState<RunDiagnostics | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    setSnapshot(loadResearchRun(runId));
    setDiagnostics(null);
    setError('');
    void researchWorkspaceApi.diagnostics(runId).then(setDiagnostics).catch((reason: unknown) => {
      setError(reason instanceof Error ? reason.message : '报告摘要暂不可用');
    });
  }, [runId]);

  const reportArtifacts = useMemo(() => diagnostics?.artifacts.filter((artifact) => isReportArtifact(artifact.artifactRole)) || [], [diagnostics]);
  if (!snapshot) return <main className="mx-auto w-full max-w-3xl px-5 py-8 sm:px-8"><Alert type="warning" showIcon message="未找到 Run 快照" /><Link className="mt-4 inline-block font-semibold text-sky-700" to={ROUTES.WORKSPACE_RESEARCH}>返回调研工作区 →</Link></main>;

  return <main className="mx-auto min-h-full w-full max-w-6xl px-5 py-7 sm:px-8"><div className="flex flex-wrap items-start justify-between gap-4"><div><p className="text-xs font-semibold uppercase tracking-[0.22em] text-sky-600">ResearchPilot · Report</p><h1 className="mt-2 text-3xl font-bold text-slate-950">报告与 Trace 摘要</h1><p className="mt-2 text-sm text-slate-500">报告工件经运行账本追踪；此页不暴露对象存储地址、原始 Prompt 或模型隐藏推理。</p></div><WorkspaceToolSwitcher /></div>{error && <Alert className="mt-5" type="warning" showIcon message="部分诊断数据未加载" description={error} />}
    <section className="mt-5 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm"><h2 className="font-bold text-slate-900">报告工件</h2><div className="mt-3 space-y-2">{reportArtifacts.length === 0 ? <p className="text-sm text-slate-500">报告尚未生成或没有可展示的报告工件。Run 完成后可在这里核验工件元数据。</p> : reportArtifacts.map((artifact, index) => <article key={`${artifact.fileName}-${index}`} className="flex flex-wrap items-center justify-between gap-2 rounded-2xl bg-slate-50 px-4 py-3"><span className="font-medium text-slate-800">{artifact.fileName}</span><span className="text-xs text-slate-500">{artifact.artifactRole} · {artifact.mimeType || 'unknown'} · {artifact.fileSize ?? 0} B</span></article>)}</div></section>
    <section className="mt-5 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm"><h2 className="font-bold text-slate-900">Trace 摘要</h2><div className="mt-4 grid gap-3 sm:grid-cols-4"><TraceMetric label="入口 Agent" value={diagnostics?.entryAgent || '—'} /><TraceMetric label="模型调用" value={String(diagnostics?.modelInvocations.length ?? 0)} /><TraceMetric label="总 Tokens" value={diagnostics?.totalTokens?.toLocaleString() || '—'} /><TraceMetric label="耗时" value={diagnostics?.durationMs === undefined ? '—' : `${diagnostics.durationMs} ms`} /></div><div className="mt-4 space-y-2">{diagnostics?.modelInvocations.map((model, index) => <article key={`${model.callKind}-${model.modelName}-${index}`} className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-slate-50 px-4 py-3 text-xs"><span className="font-semibold text-slate-800">{model.callKind} · {model.modelName}</span><span className="text-slate-500">status {model.status} · tokens {model.totalTokens ?? '—'}{model.errorCode ? ` · ${model.errorCode}` : ''}</span></article>)}</div></section>
    <div className="mt-5 flex gap-3"><Link className="rounded-xl border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-700" to={runPath(ROUTES.WORKSPACE_RESEARCH_EVIDENCE, runId)}>证据链</Link><Link className="rounded-xl bg-slate-950 px-4 py-2 text-sm font-semibold text-white" to={runPath(ROUTES.WORKSPACE_RESEARCH_RUN, runId)}>返回进度</Link></div>
  </main>;
}

function TraceMetric({ label, value }: { label: string; value: string }) {
  return <article className="rounded-2xl bg-slate-50 p-4"><p className="text-xs text-slate-500">{label}</p><p className="mt-2 font-semibold text-slate-900">{value}</p></article>;
}
