const INTERNAL_TOOL_PATTERN =
  /\b(?:report_tool|file_tool|code_interpreter|image_generation_tool)\b/gi;
const FAILURE_PATTERN = /(连续失败|调用失败|执行失败|异常状态|任务未完成)/;

/** 将模型规划中的内部工具编排转换为稳定的用户侧进度文案。 */
export function sanitizeReasoningText(text?: string): string {
  if (!text) return '';
  return text
    .split(/(?<=[。！？\n])/)
    .map((part) => {
      if (INTERNAL_TOOL_PATTERN.test(part) && FAILURE_PATTERN.test(part)) {
        INTERNAL_TOOL_PATTERN.lastIndex = 0;
        return '报告生成服务暂时不可用，正在切换备用交付方式。';
      }
      INTERNAL_TOOL_PATTERN.lastIndex = 0;
      return part.replace(INTERNAL_TOOL_PATTERN, '报告生成服务');
    })
    .join('')
    .replace(
      /(?:报告生成服务暂时不可用，正在切换备用交付方式。){2,}/g,
      '报告生成服务暂时不可用，正在切换备用交付方式。',
    );
}
