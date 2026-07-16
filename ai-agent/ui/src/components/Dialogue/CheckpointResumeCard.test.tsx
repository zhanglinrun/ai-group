import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import CheckpointResumeCard from './CheckpointResumeCard';
import { getCheckpointPhaseLabel } from './checkpointPresentation';

describe('CheckpointResumeCard', () => {
  it('展示真实 checkpoint 字段、风险提示和两种恢复入口', () => {
    const html = renderToStaticMarkup(
      <CheckpointResumeCard
        checkpoint={{
          checkpointId: 'checkpoint-001',
          phase: 'READY_FOR_STEP',
          sequence: 4,
          nextStepIndex: 2,
          resumable: true,
          status: 'AVAILABLE',
        }}
        runStatus="FAILED"
        runLoading={false}
        onResume={vi.fn()}
      />,
    );

    expect(html).toContain('checkpoint-001');
    expect(html).toContain('当前连接收到的最近恢复点');
    expect(html).toContain('步骤执行前');
    expect(html).toContain('SAFE_ONLY');
    expect(html).toContain('强制重启');
    expect(html).toContain('未知副作用');
    expect(html).toContain('刷新页面或打开历史回放时暂不还原');
  });

  it('保留未知 phase 的后端原值', () => {
    expect(getCheckpointPhaseLabel('CUSTOM_PHASE')).toBe('CUSTOM_PHASE');
  });
});
