import { describe, expect, it } from 'vitest';

import { buildSubmitPayload, resolveInputMode } from './inputMode';

describe('inputMode', () => {
  it('普通问题模式应保留输出格式', () => {
    const payload = buildSubmitPayload({
      question: '调研三家主流 Agent 产品',
      visibleMode: 'standard',
      visibleOutputProduct: { type: 'docs' } as CHAT.Product,
      uploadedFiles: [],
    });

    expect(payload).toMatchObject({
      outputStyle: 'docs',
      executionMode: 'STANDARD',
    });
    expect(payload).not.toHaveProperty('deepThink');
    expect(payload).not.toHaveProperty('autoRoute');
    expect(resolveInputMode(payload.executionMode)).toBe('standard');
  });

  it('深度调研模式应保留用户选择的交付格式', () => {
    expect(
      buildSubmitPayload({
        question: '帮我调研竞品',
        visibleMode: 'deep',
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
      visibleOutputProduct: { type: 'html' } as CHAT.Product,
      uploadedFiles: [],
    });

    expect(payload).toMatchObject({ outputStyle: 'html', executionMode: 'STANDARD' });
    expect(resolveInputMode(payload.executionMode)).toBe('standard');
  });

  it('应从 executionMode 稳定恢复可见模式', () => {
    expect(resolveInputMode('AUTO')).toBe('standard');
    expect(resolveInputMode('STANDARD')).toBe('standard');
    expect(resolveInputMode('DEEP')).toBe('deep');
  });
});
