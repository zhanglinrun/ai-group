import { describe, expect, it } from 'vitest';

import { shouldRefreshWorkspaceTask } from './streamState';

describe('shouldRefreshWorkspaceTask', () => {
  it('keeps evaluator telemetry out of the task workspace', () => {
    const event = {
      messageType: 'task',
      resultMap: { messageType: 'evaluation' },
    } as MESSAGE.EventData;

    expect(shouldRefreshWorkspaceTask(event)).toBe(false);
  });
});
