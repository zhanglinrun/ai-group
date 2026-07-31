import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { message } from 'antd';

import WorkspaceToolSwitcher from '@/components/WorkspaceToolSwitcher';
import { agentFileApi, type UploadedConversationFile } from '@/services/agentFile';
import { estimateResearchQuota, newResearchId, saveResearchRun, type ResearchRunEvent } from '@/services/researchWorkspace';
import { ROUTES } from '@/router/routes';
import querySSE from '@/utils/querySSE';

export default function ResearchWorkspace() {
  const navigate = useNavigate();
  const [sessionId] = useState(() => newResearchId('research-session'));
  const [query, setQuery] = useState('');
  const [executionMode, setExecutionMode] = useState<'STANDARD' | 'DEEP'>('DEEP');
  const [attachments, setAttachments] = useState<UploadedConversationFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [starting, setStarting] = useState(false);
  const quotaEstimate = estimateResearchQuota(executionMode, query.trim().length, attachments.length);

  useEffect(() => { sessionStorage.setItem('researchpilot:draft-session', sessionId); }, [sessionId]);

  const upload = async (files: FileList | null) => {
    if (!files?.length || uploading) return;
    setUploading(true);
    try {
      const uploaded = await Promise.all(Array.from(files).map((file) => agentFileApi.uploadConversationFile(sessionId, file)));
      setAttachments((current) => [...current, ...uploaded]);
      message.success(`已添加 ${uploaded.length} 个附件`);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '附件上传失败');
    } finally { setUploading(false); }
  };

  const start = () => {
    const normalizedQuery = query.trim();
    if (!normalizedQuery || starting) return;
    const requestId = newResearchId('research-run');
    const initial = {
      requestId,
      sessionId,
      query: normalizedQuery,
      executionMode,
      attachments,
      events: [] as ResearchRunEvent[],
      cursor: 0,
      status: 'RUNNING' as const,
      estimatedMicrocredits: quotaEstimate.microcredits,
      quotaEstimateBasis: quotaEstimate.basis,
    };
    saveResearchRun(initial);
    setStarting(true);
    querySSE<ResearchRunEvent>({
      body: {
        sessionId, requestId, query: normalizedQuery, executionMode, online: true, outputStyle: 'markdown',
        sessionFiles: attachments.map((file) => ({ fileName: file.name, resourceKey: file.resourceKey, mimeType: file.mimeType, fileSize: file.size, domainUrl: file.accessUrl || file.previewUrl, ossUrl: file.accessUrl || file.downloadUrl, originFileName: file.originFileName })),
      },
      handleMessage: (event) => {
        initial.events.push(event);
        const status = event.type === 'error' ? 'FAILED' : event.type === 'complete' ? 'COMPLETED' : 'RUNNING';
        saveResearchRun({ ...initial, status });
      },
      handleError: () => undefined,
      handleClose: () => setStarting(false),
    });
    navigate(ROUTES.WORKSPACE_RESEARCH_RUN.replace(':runId', requestId));
  };

  return <main className="mx-auto min-h-full w-full max-w-5xl px-5 py-7 sm:px-8">
    <div className="flex flex-wrap items-start justify-between gap-4"><div><p className="text-xs font-semibold uppercase tracking-[0.22em] text-sky-600">ResearchPilot</p><h1 className="mt-2 text-3xl font-bold text-slate-950">新建专业调研</h1><p className="mt-2 text-sm text-slate-500">提交问题、附件和研究模式；Agent 会产出可追溯证据、冲突与正式报告。</p></div><WorkspaceToolSwitcher /></div>
    <section className="mt-7 space-y-5 rounded-3xl border border-slate-200 bg-white p-5 shadow-sm">
      <label className="block text-sm font-semibold text-slate-800">研究问题与目标<textarea className="mt-2 min-h-32 w-full rounded-2xl border border-slate-200 p-3" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="例如：比较 2026 年中国 AI Agent 开发平台的技术路线、证据与风险。" /></label>
      <div className="flex flex-wrap gap-3">{(['STANDARD', 'DEEP'] as const).map((mode) => <button key={mode} type="button" onClick={() => setExecutionMode(mode)} className={`rounded-xl px-4 py-2 text-sm font-semibold ${executionMode === mode ? 'bg-slate-950 text-white' : 'border border-slate-200 text-slate-700'}`}>{mode === 'DEEP' ? 'DEEP 深度调研' : 'STANDARD 快速调研'}</button>)}</div>
      <aside className="rounded-2xl border border-sky-100 bg-sky-50/70 p-4 text-sm text-slate-700">
        <div className="flex flex-wrap items-center justify-between gap-2"><span className="font-semibold text-slate-900">额度预估上限</span><span className="font-mono text-base font-bold text-sky-700">{quotaEstimate.label}</span></div>
        <p className="mt-1 text-xs text-slate-500">{quotaEstimate.basis}；最终预占与结算严格以服务端固定的价格版本及实际用量为准。</p>
      </aside>
      <label className="block rounded-2xl border border-dashed border-slate-300 p-4 text-sm text-slate-600"><span className="font-semibold text-slate-800">附件：PDF、Markdown、文本、数据或图片（最大 25 MiB）</span><input className="mt-3 block" type="file" multiple disabled={uploading} onChange={(event) => void upload(event.target.files)} /></label>
      {attachments.length > 0 && <ul className="space-y-2">{attachments.map((file) => <li key={file.resourceKey || file.name} className="flex items-center justify-between rounded-xl bg-slate-50 px-3 py-2 text-sm"><span>{file.name}</span><span className="text-xs text-slate-500">{file.artifactHash?.slice(0, 12) || 'pending'} · {file.size} B</span></li>)}</ul>}
      <button disabled={!query.trim() || starting} onClick={start} className="rounded-xl bg-sky-600 px-5 py-3 text-sm font-bold text-white disabled:opacity-40">{starting ? '正在创建 Run…' : '启动调研'}</button>
    </section>
    <p className="mt-4 text-xs text-slate-400">已启动的 Run 可断线恢复；附件状态和事件游标保存在当前浏览器会话中。</p>
    <Link className="mt-3 inline-block text-sm font-semibold text-sky-700" to={ROUTES.WORKSPACE_TASKS}>查看旧项目任务图 →</Link>
  </main>;
}
