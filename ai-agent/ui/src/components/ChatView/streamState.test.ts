import { describe, expect, it } from 'vitest';

import { shouldRefreshWorkspaceTask } from './streamState';

describe('shouldRefreshWorkspaceTask', () => {
  it('keeps verification telemetry out of the task workspace', () => {
    const event = {
      messageType: 'agent_event',
      resultMap: { messageType: 'verification_result', resultMap: { accepted: true } },
    } as unknown as MESSAGE.EventData;

    expect(shouldRefreshWorkspaceTask(event)).toBe(false);
  });
});
