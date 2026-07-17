import api from './index';

export type WorkTaskStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface WorkspaceItem {
  id: string;
  name: string;
  instructions: string;
  toolPolicy: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface WorkTaskItem {
  id: string;
  workspaceId: string;
  subject: string;
  description?: string;
  activeForm?: string;
  status: WorkTaskStatus;
  taskOwner?: string;
  blocks: string[];
  blockedBy: string[];
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
  version?: number;
}

export interface TaskGraphEventItem {
  eventUid: string;
  workspaceId: string;
  taskId?: string;
  eventType: string;
  actorId: string;
  payload: Record<string, unknown>;
  createdAt: string;
}

export const workspaceApi = {
  list: () => api.get<WorkspaceItem[]>('/api/agent/work/workspaces') as unknown as Promise<WorkspaceItem[]>,
  create: (input: Pick<WorkspaceItem, 'name' | 'instructions'>) =>
    api.post<WorkspaceItem>('/api/agent/work/workspaces', input) as unknown as Promise<WorkspaceItem>,
  listTasks: (workspaceId: string) =>
    api.get<WorkTaskItem[]>(`/api/agent/work/workspaces/${workspaceId}/tasks`) as unknown as Promise<WorkTaskItem[]>,
  createTask: (workspaceId: string, input: { subject: string; description?: string; activeForm?: string; blockedBy?: string[] }) =>
    api.post<WorkTaskItem>(`/api/agent/work/workspaces/${workspaceId}/tasks`, input) as unknown as Promise<WorkTaskItem>,
  claim: (workspaceId: string, taskId: string) =>
    api.post<WorkTaskItem>(`/api/agent/work/workspaces/${workspaceId}/tasks/${taskId}/claim`, {}) as unknown as Promise<WorkTaskItem>,
  updateStatus: (workspaceId: string, taskId: string, status: WorkTaskStatus) =>
    api.patch<WorkTaskItem>(`/api/agent/work/workspaces/${workspaceId}/tasks/${taskId}`, { status }) as unknown as Promise<WorkTaskItem>,
  events: (workspaceId: string, afterEventUid?: string) =>
    api.get<TaskGraphEventItem[]>(`/api/agent/work/workspaces/${workspaceId}/events`, afterEventUid ? { afterEventUid } : undefined) as unknown as Promise<TaskGraphEventItem[]>,
};
