import { shouldRenderDeepSearchWorkspace } from '@/utils/deepSearch';
import { isAgentLoopEvent } from '@/utils/agentEvents';
import type { ActiveRunState } from './chatView.types';

const WORKSPACE_HIDDEN_MESSAGE_TYPES = new Set(['task_summary', 'result', 'tool_thought']);

export function isWorkspaceRenderableTask(task?: Partial<CHAT.Task> | Partial<MESSAGE.Task>) {
  if (!task) {
    return false;
  }

  if (WORKSPACE_HIDDEN_MESSAGE_TYPES.has(task.messageType || '')) {
    return false;
  }

  if (task.messageType === 'deep_search') {
    return shouldRenderDeepSearchWorkspace(task.resultMap?.messageType);
  }

  return true;
}

export function shouldRefreshWorkspaceTask(eventData?: MESSAGE.EventData) {
  if (!eventData) {
    return false;
  }

  // Harness 状态事件属于对话运行态，不是右侧工具工作区内容。
  if (isAgentLoopEvent(eventData)) {
    return false;
  }

  if (
    eventData.messageType === 'agent_event' &&
    ['agent_stream', 'tool_thought'].includes(eventData.resultMap?.messageType || '')
  ) {
    return false;
  }

  return true;
}

export function getLatestRenderableTask(chat: CHAT.ChatItem): CHAT.Task | undefined {
  const groups = chat.multiAgent?.tasks || [];
  for (let groupIndex = groups.length - 1; groupIndex >= 0; groupIndex -= 1) {
    const group = groups[groupIndex] || [];
    for (let taskIndex = group.length - 1; taskIndex >= 0; taskIndex -= 1) {
      const task = group[taskIndex] as CHAT.Task | undefined;
      // 工作区只跟随真正属于右侧详情面的任务。
      if (!isWorkspaceRenderableTask(task)) {
        continue;
      }
      return task;
    }
  }
  return undefined;
}

export function cloneWorkspaceTask(task: CHAT.Task): CHAT.Task {
  return {
    ...task,
    resultMap: task.resultMap ? { ...task.resultMap } : task.resultMap,
  } as CHAT.Task;
}

export function resolveActionPanelVisibility(params: { taskList: CHAT.Task[] }) {
  return params.taskList.some((task) => isWorkspaceRenderableTask(task));
}

export function resolveLatestRunState(
  chat?: Pick<CHAT.ChatItem, 'metrics' | 'finishedAt'>,
): ActiveRunState | undefined {
  if (!chat) {
    return undefined;
  }

  return {
    status: chat.metrics?.status,
    finishedAt: chat.finishedAt,
  };
}
