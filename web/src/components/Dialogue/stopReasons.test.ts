import { describe, expect, it } from 'vitest';

import { resolveStopReasonLabel } from './stopReasons';

describe('resolveStopReasonLabel', () => {
  it.each([
    ['STOPPED', 'RUN_ALREADY_IN_PROGRESS', '该请求正在执行，请稍后查看或重试'],
    ['FAILED', 'RUN_OWNER_MISMATCH', '请求不属于当前账户，已拒绝访问'],
    ['FAILED', 'RUN_REQUEST_MISMATCH', '请求标识与既有运行不一致，已拒绝执行'],
    ['TIMEOUT', 'TIME_BUDGET', '达到运行时限'],
  ] as const)('shows %s terminal reason %s', (status, reason, expected) => {
    expect(resolveStopReasonLabel(status, reason)).toBe(expected);
  });

  it('does not show a stop reason for successful or still-running runs', () => {
    expect(resolveStopReasonLabel('SUCCESS', 'COMPLETED')).toBeNull();
    expect(resolveStopReasonLabel('RUNNING', 'RUN_ALREADY_IN_PROGRESS')).toBeNull();
  });
});
