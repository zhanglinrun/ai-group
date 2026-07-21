import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  CheckCircle2,
  CircleDashed,
  Loader2,
  LockKeyhole,
  Plus,
  RefreshCw,
  ShieldAlert,
  Workflow,
} from 'lucide-react';
import { message } from 'antd';

import WorkspaceToolSwitcher from '@/components/WorkspaceToolSwitcher';
import {
  workspaceApi,
  type TaskGraphEventItem,
  type WorkTaskItem,
  type WorkspaceItem,
} from '@/services/workspaces';
import { taskColumnOf, type TaskColumnKey } from './taskBoard';

const columns: Array<{ key: TaskColumnKey; title: string; icon: typeof Workflow }> = [
  { key: 'ready', title: 'Ready', icon: CircleDashed },
  { key: 'running', title: 'In progress', icon: Workflow },
  { key: 'blocked', title: 'Blocked', icon: LockKeyhole },
  { key: 'completed', title: 'Completed', icon: CheckCircle2 },
  { key: 'closed', title: 'Failed / cancelled', icon: ShieldAlert },
];

function actionErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export default function WorkspaceTasks() {
  const [workspaces, setWorkspaces] = useState<WorkspaceItem[]>([]);
  const [workspaceId, setWorkspaceId] = useState('');
  const [tasks, setTasks] = useState<WorkTaskItem[]>([]);
  const [events, setEvents] = useState<TaskGraphEventItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [workspaceName, setWorkspaceName] = useState('Agent 项目工作区');
  const [taskSubject, setTaskSubject] = useState('');
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const selectedWorkspaceRef = useRef('');
  const refreshSequenceRef = useRef(0);
  const actionRef = useRef<string | null>(null);

  selectedWorkspaceRef.current = workspaceId;

  const beginAction = (key: string) => {
    if (actionRef.current) return false;
    actionRef.current = key;
    setPendingAction(key);
    return true;
  };

  const finishAction = (key: string) => {
    if (actionRef.current === key) actionRef.current = null;
    setPendingAction((current) => (current === key ? null : current));
  };

  const refreshWorkspaces = useCallback(async (notifyOnError = true) => {
    try {
      const list = (await workspaceApi.list()) || [];
      setWorkspaces(list);
      setWorkspaceId((current) =>
        current && list.some((workspace) => workspace.id === current) ? current : list[0]?.id || '',
      );
    } catch (error) {
      console.error('工作区加载失败', error);
      if (notifyOnError) message.error(actionErrorMessage(error, '工作区加载失败'));
    }
  }, []);

  const refreshTasks = useCallback(
    async (notifyOnError = true) => {
      const requestedWorkspaceId = workspaceId;
      const requestSequence = ++refreshSequenceRef.current;
      if (!requestedWorkspaceId) {
        setTasks([]);
        setEvents([]);
        setLoading(false);
        return;
      }

      setLoading(true);
      try {
        const [nextTasks, nextEvents] = await Promise.all([
          workspaceApi.listTasks(requestedWorkspaceId),
          workspaceApi.events(requestedWorkspaceId),
        ]);
        if (
          refreshSequenceRef.current !== requestSequence ||
          selectedWorkspaceRef.current !== requestedWorkspaceId
        ) {
          return;
        }
        setTasks(nextTasks || []);
        setEvents(nextEvents || []);
      } catch (error) {
        console.error('任务图加载失败', error);
        if (notifyOnError) message.error(actionErrorMessage(error, '任务图加载失败'));
      } finally {
        if (
          refreshSequenceRef.current === requestSequence &&
          selectedWorkspaceRef.current === requestedWorkspaceId
        ) {
          setLoading(false);
        }
      }
    },
    [workspaceId],
  );

  useEffect(() => {
    void refreshWorkspaces();
  }, [refreshWorkspaces]);
  useEffect(() => {
    setTasks([]);
    setEvents([]);
    void refreshTasks();
  }, [refreshTasks]);
  useEffect(() => {
    if (!workspaceId) return;
    const timer = window.setInterval(() => void refreshTasks(false), 4000);
    return () => window.clearInterval(timer);
  }, [refreshTasks, workspaceId]);

  const grouped = useMemo(
    () =>
      Object.fromEntries(
        columns.map(({ key }) => [key, tasks.filter((task) => taskColumnOf(task) === key)]),
      ) as Record<TaskColumnKey, WorkTaskItem[]>,
    [tasks],
  );

  const createWorkspace = async () => {
    const name = workspaceName.trim();
    const actionKey = 'workspace:create';
    if (!name || !beginAction(actionKey)) return;

    setLoading(true);
    try {
      const created = await workspaceApi.create({
        name,
        instructions: '在此项目内共享上下文、任务与工具策略。',
      });
      setWorkspaces((previous) => [created, ...previous.filter((item) => item.id !== created.id)]);
      setWorkspaceId(created.id);
      setWorkspaceName('');
      message.success('工作区已创建');
    } catch (error) {
      console.error('创建工作区失败', error);
      message.error(actionErrorMessage(error, '创建工作区失败'));
    } finally {
      setLoading(false);
      finishAction(actionKey);
    }
  };

  const createTask = async () => {
    const subject = taskSubject.trim();
    const requestedWorkspaceId = workspaceId;
    const actionKey = 'task:create';
    if (!requestedWorkspaceId || !subject || !beginAction(actionKey)) return;

    try {
      const created = await workspaceApi.createTask(requestedWorkspaceId, {
        subject,
        activeForm: `正在${subject}`,
      });
      if (selectedWorkspaceRef.current === requestedWorkspaceId) {
        setTasks((previous) => [created, ...previous.filter((item) => item.id !== created.id)]);
        setTaskSubject('');
        await refreshTasks(false);
      }
      message.success('任务已创建');
    } catch (error) {
      console.error('创建任务失败', error);
      message.error(actionErrorMessage(error, '创建任务失败'));
    } finally {
      finishAction(actionKey);
    }
  };

  const advance = async (task: WorkTaskItem) => {
    const requestedWorkspaceId = workspaceId;
    const actionKey = `task:${task.id}`;
    if (!requestedWorkspaceId || !beginAction(actionKey)) return;

    try {
      const updated =
        taskColumnOf(task) === 'ready'
          ? await workspaceApi.claim(requestedWorkspaceId, task.id)
          : task.status === 'IN_PROGRESS'
            ? await workspaceApi.updateStatus(requestedWorkspaceId, task.id, 'COMPLETED')
            : null;
      if (updated && selectedWorkspaceRef.current === requestedWorkspaceId) {
        setTasks((previous) =>
          previous.map((candidate) => (candidate.id === updated.id ? updated : candidate)),
        );
        await refreshTasks(false);
      }
    } catch (error) {
      console.error('任务状态更新失败', error);
      message.error(actionErrorMessage(error, '任务状态更新失败，请检查依赖或认领状态'));
    } finally {
      finishAction(actionKey);
    }
  };

  return (
    <main className="mx-auto min-h-full w-full max-w-[1480px] px-5 py-7 sm:px-8">
      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.22em] text-sky-600">
            Agent Work
          </p>
          <h1 className="mt-2 text-3xl font-bold text-slate-950">项目工作区与任务图</h1>
          <p className="mt-2 text-sm text-slate-500">
            Todo 服务单次运行；这里管理跨会话、可认领、有依赖并持久化到 MySQL 的工作项。
          </p>
        </div>
        <WorkspaceToolSwitcher />
      </div>

      <section className="mb-6 grid gap-3 rounded-3xl border border-slate-200 bg-white p-4 shadow-sm lg:grid-cols-[260px_1fr_auto]">
        <select
          className="rounded-xl border border-slate-200 px-3 py-2"
          value={workspaceId}
          disabled={pendingAction !== null}
          onChange={(event) => setWorkspaceId(event.target.value)}
        >
          <option value="">选择工作区</option>
          {workspaces.map((item) => (
            <option key={item.id} value={item.id}>
              {item.name}
            </option>
          ))}
        </select>
        <input
          className="rounded-xl border border-slate-200 px-3 py-2"
          value={workspaceName}
          onChange={(event) => setWorkspaceName(event.target.value)}
          placeholder="新工作区名称"
        />
        <button
          disabled={!workspaceName.trim() || pendingAction !== null}
          className="inline-flex items-center justify-center gap-2 rounded-xl bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:opacity-40"
          onClick={() => void createWorkspace()}
        >
          {pendingAction === 'workspace:create' ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <Plus className="h-4 w-4" />
          )}
          创建工作区
        </button>
      </section>

      <section className="mb-5 flex gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-3">
        <input
          className="min-w-0 flex-1 rounded-xl border border-slate-200 bg-white px-3 py-2"
          value={taskSubject}
          onChange={(event) => setTaskSubject(event.target.value)}
          placeholder="创建一个工作项，例如：完成工具权限策略验收"
        />
        <button
          disabled={!workspaceId || !taskSubject.trim() || pendingAction !== null}
          className="inline-flex items-center gap-2 rounded-xl bg-sky-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-40"
          onClick={() => void createTask()}
        >
          {pendingAction === 'task:create' ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
          添加任务
        </button>
        <button
          type="button"
          aria-label="刷新任务"
          disabled={loading || pendingAction !== null}
          className="rounded-xl border border-slate-200 bg-white px-3 disabled:opacity-40"
          onClick={() => void refreshTasks()}
        >
          <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
        </button>
      </section>

      <div className="grid gap-4 xl:grid-cols-5">
        {columns.map(({ key, title, icon: Icon }) => (
          <section
            key={key}
            className="min-h-[420px] rounded-3xl border border-slate-200 bg-slate-50/80 p-3"
          >
            <header className="mb-3 flex items-center justify-between px-1">
              <span className="flex items-center gap-2 text-sm font-bold text-slate-700">
                <Icon className="h-4 w-4" />
                {title}
              </span>
              <span className="rounded-full bg-white px-2 py-0.5 text-xs text-slate-500">
                {grouped[key].length}
              </span>
            </header>
            <div className="space-y-3">
              {grouped[key].map((task) => (
                <article
                  key={task.id}
                  className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm"
                >
                  <h3 className="font-semibold text-slate-900">{task.subject}</h3>
                  {task.activeForm && (
                    <p className="mt-1 text-xs text-slate-500">{task.activeForm}</p>
                  )}
                  {task.blockedBy.length > 0 && (
                    <p className="mt-3 text-xs text-amber-600">
                      等待 {task.blockedBy.length} 个前置任务
                    </p>
                  )}
                  {task.taskOwner && (
                    <p className="mt-3 text-xs text-slate-400">执行者：{task.taskOwner}</p>
                  )}
                  {key === 'closed' && (
                    <p className="mt-3 text-xs font-semibold text-rose-600">终态：{task.status}</p>
                  )}
                  {(key === 'ready' || key === 'running') && (
                    <button
                      disabled={pendingAction !== null}
                      className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-xl border border-sky-200 bg-sky-50 px-3 py-2 text-xs font-semibold text-sky-700 disabled:opacity-50"
                      onClick={() => void advance(task)}
                    >
                      {pendingAction === `task:${task.id}` ? (
                        <Loader2 className="h-3.5 w-3.5 animate-spin" />
                      ) : null}
                      {key === 'ready' ? '认领并开始' : '标记完成'}
                    </button>
                  )}
                </article>
              ))}
            </div>
          </section>
        ))}
      </div>
      <section className="mt-5 rounded-3xl border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-bold text-slate-800">任务事件</h2>
          <span className="text-xs text-slate-400">{events.length} 条审计记录 · 每 4 秒刷新</span>
        </div>
        <div className="mt-3 flex flex-wrap gap-2">
          {events.slice(-8).map((event) => (
            <span
              key={event.eventUid}
              className="rounded-full bg-slate-100 px-3 py-1 text-xs text-slate-600"
            >
              {event.eventType}
            </span>
          ))}
        </div>
      </section>
    </main>
  );
}
