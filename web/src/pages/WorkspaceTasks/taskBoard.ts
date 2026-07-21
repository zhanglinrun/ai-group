import type { WorkTaskItem } from '@/services/workspaces';

export type TaskColumnKey = 'ready' | 'running' | 'blocked' | 'completed' | 'closed';

export function taskColumnOf(task: WorkTaskItem): TaskColumnKey {
  if (task.status === 'COMPLETED') return 'completed';
  if (task.status === 'FAILED' || task.status === 'CANCELLED') return 'closed';
  if (task.status === 'IN_PROGRESS') return 'running';
  if (task.blockedBy.length > 0) return 'blocked';
  return 'ready';
}
