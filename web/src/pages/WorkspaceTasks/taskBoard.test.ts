import { describe, expect, it } from 'vitest';

import type { WorkTaskItem, WorkTaskStatus } from '@/services/workspaces';

import { taskColumnOf } from './taskBoard';

function task(status: WorkTaskStatus, blockedBy: string[] = []): WorkTaskItem {
  return {
    id: `task-${status}`,
    workspaceId: 'workspace-1',
    subject: status,
    status,
    blocks: [],
    blockedBy,
    metadata: {},
    createdAt: '2026-07-17T00:00:00Z',
    updatedAt: '2026-07-17T00:00:00Z',
  };
}

describe('taskColumnOf', () => {
  it('keeps pending dependency-free tasks ready and dependency tasks blocked', () => {
    expect(taskColumnOf(task('PENDING'))).toBe('ready');
    expect(taskColumnOf(task('PENDING', ['blocker-1']))).toBe('blocked');
  });

  it('keeps failed and cancelled tasks out of the ready column', () => {
    expect(taskColumnOf(task('FAILED'))).toBe('closed');
    expect(taskColumnOf(task('CANCELLED'))).toBe('closed');
  });
});
