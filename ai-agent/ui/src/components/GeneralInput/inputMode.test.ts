import { describe, expect, it } from 'vitest';

import { buildSubmitPayload, resolveInputMode } from './inputMode';

describe('inputMode', () => {
  it('自动模式应保留输出格式并交给后端路由', () => {
    const payload = buildSubmitPayload({
      question: '调研三家主流 Agent 产品',
      visibleMode: 'auto',
      isDataAgent: false,
      visibleOutputProduct: { type: 'docs' } as CHAT.Product,
      uploadedFiles: [],
    });

    expect(payload).toMatchObject({
      outputStyle: 'docs',
      executionMode: 'AUTO',
    });
    expect(payload).not.toHaveProperty('deepThink');
    expect(payload).not.toHaveProperty('autoRoute');
    expect(resolveInputMode(payload.executionMode)).toBe('auto');
  });

  it('深度模式应只通过 executionMode 表达', () => {
    expect(
      buildSubmitPayload({
        question: '帮我调研竞品',
        visibleMode: 'deep',
        isDataAgent: false,
        visibleOutputProduct: { type: 'html' } as CHAT.Product,
        uploadedFiles: [],
      }),
    ).toMatchObject({
      outputStyle: 'html',
      executionMode: 'DEEP',
    });
  });

  it('标准模式应保留输出格式且不发送旧模式字段', () => {
    const payload = buildSubmitPayload({
      question: '你好',
      visibleMode: 'standard',
      isDataAgent: false,
      visibleOutputProduct: { type: 'chat' } as CHAT.Product,
      uploadedFiles: [],
    });

    expect(payload).toMatchObject({
      outputStyle: 'chat',
      executionMode: 'STANDARD',
    });
    expect(payload).not.toHaveProperty('aiAgentId');
    expect(payload).not.toHaveProperty('deepThink');
    expect(payload).not.toHaveProperty('autoRoute');
  });

  it('快速模式可以保留结构化输出', () => {
    const payload = buildSubmitPayload({
      question: '仔细分析并生成网页',
      visibleMode: 'standard',
      isDataAgent: false,
      visibleOutputProduct: { type: 'html' } as CHAT.Product,
      uploadedFiles: [],
    });

    expect(payload).toMatchObject({ outputStyle: 'html', executionMode: 'STANDARD' });
    expect(resolveInputMode(payload.executionMode)).toBe('standard');
  });

  it('应从 executionMode 稳定恢复可见模式', () => {
    expect(resolveInputMode('AUTO')).toBe('auto');
    expect(resolveInputMode('STANDARD')).toBe('standard');
    expect(resolveInputMode('DEEP')).toBe('deep');
  });
});
