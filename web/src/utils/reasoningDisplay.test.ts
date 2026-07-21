import { describe, expect, it } from 'vitest';
import { sanitizeReasoningText } from './reasoningDisplay';

describe('sanitizeReasoningText', () => {
  it('隐藏内部工具名并合并连续失败细节', () => {
    expect(
      sanitizeReasoningText('report_tool已连续失败，任务未完成；改用file_tool生成Markdown交付物。'),
    ).toBe('报告生成服务暂时不可用，正在切换备用交付方式。');
  });
});
