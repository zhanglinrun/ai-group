/**
 * 时间线任务总结和最终结论共用同一套文本回退顺序，
 * 这样可以避免不同展示区各自兜底时出现文案不一致。
 */
export function resolveTaskSummaryText(task?: CHAT.Task) {
  if (!task) {
    return '';
  }

  const taskRecord = task as unknown as Record<string, unknown>;
  const resultMapRecord = (task.resultMap || {}) as Record<string, unknown>;

  return (
    (typeof resultMapRecord.taskSummary === 'string' ? resultMapRecord.taskSummary : '') ||
    (typeof taskRecord.taskSummary === 'string' ? taskRecord.taskSummary : '') ||
    task.result ||
    (typeof resultMapRecord.result === 'string' ? resultMapRecord.result : '') ||
    ''
  );
}

/**
 * 失败/停止终态不能复用成功兜底文案。即使后端发来一个空 result，
 * 页面也必须如实展示终态，而不是显示“任务已完成”。
 */
export function resolveConversationConclusionText(chat: CHAT.ChatItem) {
  const summary = resolveTaskSummaryText(chat.conclusion);
  if (summary) {
    return summary;
  }

  const status = String(chat.metrics?.status || '')
    .trim()
    .toUpperCase();
  if (status === 'FAILED') {
    return chat.tip || '任务执行失败，请重试。';
  }
  if (status === 'STOPPED') {
    return chat.tip || '任务已停止，已保留停止前的可见内容。';
  }
  if (status === 'TIMEOUT') {
    return chat.tip || '任务执行超时，请稍后重试。';
  }
  return '任务已完成';
}
