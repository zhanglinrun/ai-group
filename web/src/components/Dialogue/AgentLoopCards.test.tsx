import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { TodoSection } from './TodoSection';
import VerificationCard from './VerificationCard';
import { AgentRunProgress } from './AgentRunProgress';

describe('Agent Loop cards', () => {
  it('renders canonical todo statuses without a legacy plan model', () => {
    const html = renderToStaticMarkup(
      <TodoSection
        title="竞品调研"
        todos={[
          {
            id: 'todo-1',
            title: '核对输入参数',
            status: 'completed',
            evidencePolicy: 'NONE',
          },
          {
            id: 'todo-2',
            title: '调用额度估算工具',
            status: 'in_progress',
            evidencePolicy: 'TOOL',
          },
          {
            id: 'todo-3',
            title: '生成报告',
            status: 'pending',
            evidencePolicy: 'NONE',
          },
        ]}
      />,
    );

    expect(html).toContain('aria-label="agent-todos"');
    expect(html).toContain('竞品调研');
    expect(html).toContain('1/3');
    expect(html).toContain('进行中');
    expect(html).toContain('过程步骤');
    expect(html).toContain('工具证据');
  });

  it('renders completion feedback and corrective actions', () => {
    const html = renderToStaticMarkup(
      <VerificationCard
        verification={{
          status: 'failed',
          summary: '价格信息仍不完整',
          missingRequirements: ['Cursor 官方价格'],
          requiredActions: ['补充官方来源'],
          attempt: 2,
        }}
      />,
    );

    expect(html).toContain('aria-label="verification-card"');
    expect(html).toContain('第 2 轮');
    expect(html).toContain('Cursor 官方价格');
    expect(html).toContain('补充官方来源');
  });

  it('renders the user-facing execution phases without exposing chain of thought', () => {
    const html = renderToStaticMarkup(
      <AgentRunProgress
        run={{
          status: 'RUNNING',
          phase: 'EXECUTING',
          todos: [],
        }}
      />,
    );

    expect(html).toContain('aria-label="agent-run-progress"');
    expect(html).toContain('任务执行中');
    expect(html).toContain('分析任务');
    expect(html).toContain('执行工具');
    expect(html).not.toContain('chain-of-thought');
  });
});
