const PHASE_LABELS: Record<string, string> = {
  READY_FOR_STEP: '步骤执行前',
  BEFORE_SUMMARY: '生成总结前',
};

export const getCheckpointPhaseLabel = (phase: string) => PHASE_LABELS[phase] || phase;
