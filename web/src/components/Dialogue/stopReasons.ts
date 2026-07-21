const STOP_REASON_LABELS: Record<string, string> = {
  MAX_TURNS: '达到最大模型轮次',
  REPEATED_TURN: '检测到重复循环',
  DOWNSTREAM_ABORTED: '连接已中断',
  TOOL_CALL_BUDGET: '达到工具调用上限',
  TIME_BUDGET: '达到运行时限',
  TOKEN_BUDGET: '达到 Token 上限',
  CREDIT_BUDGET: '达到本轮费用上限',
  COMPLETION_ATTEMPT_BUDGET: '完成验收连续未通过',
  REQUIRED_CAPABILITY_UNAVAILABLE: '缺少用户要求的工具能力',
  MODEL_ERROR: '模型调用失败',
  EXECUTION_ERROR: '执行过程失败',
  RUN_ALREADY_IN_PROGRESS: '该请求正在执行，请稍后查看或重试',
  RUN_OWNER_MISMATCH: '请求不属于当前账户，已拒绝访问',
  RUN_REQUEST_MISMATCH: '请求标识与既有运行不一致，已拒绝执行',
};

export const resolveStopReasonLabel = (
  status: CHAT.AgentRunStatus | undefined,
  stopReason: string | undefined,
): string | null => {
  if (!stopReason || !status || status === 'SUCCESS' || status === 'RUNNING') return null;
  return STOP_REASON_LABELS[stopReason] || stopReason;
};
