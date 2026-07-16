type RecordValue = Record<string, unknown>;

export type CheckpointControlEvent = {
  kind: 'checkpoint' | 'resume';
  checkpoint: CHAT.AgentCheckpoint;
};

const isRecord = (value: unknown): value is RecordValue =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const optionalNumber = (value: unknown) =>
  typeof value === 'number' && Number.isFinite(value) ? value : undefined;

/**
 * 解析 AgentSessionPrinter 的真实控制事件结构。
 * AgentResponse 适配层会把后端 resultMap 放在 task.resultMap 内，因此同时兼容嵌套和扁平形态。
 */
export function parseCheckpointControlEvent(
  eventData: MESSAGE.EventData,
): CheckpointControlEvent | undefined {
  const task = eventData.resultMap;
  const kind = task?.messageType;
  if (kind !== 'checkpoint' && kind !== 'resume') {
    return undefined;
  }

  const payload: RecordValue = isRecord(task.resultMap)
    ? task.resultMap
    : (task as unknown as RecordValue);
  const checkpointId = typeof payload.checkpointId === 'string' ? payload.checkpointId.trim() : '';
  const phase = typeof payload.phase === 'string' ? payload.phase.trim() : '';
  if (!checkpointId || !phase) {
    return undefined;
  }

  const resumeDecision =
    payload.resumeDecision === 'SAFE_ONLY' || payload.resumeDecision === 'RESTART_FROM_CHECKPOINT'
      ? payload.resumeDecision
      : undefined;

  return {
    kind,
    checkpoint: {
      checkpointId,
      phase,
      sequence: optionalNumber(payload.sequence),
      nextStepIndex: optionalNumber(payload.nextStepIndex),
      resumable: kind === 'checkpoint' && payload.resumable === true,
      sourceRequestId:
        typeof payload.sourceRequestId === 'string' ? payload.sourceRequestId : undefined,
      resumeDecision,
      status: kind === 'resume' ? 'RESUMED' : 'AVAILABLE',
    },
  };
}

export function applyCheckpointControlEvent(
  chat: CHAT.ChatItem,
  controlEvent: CheckpointControlEvent,
): CHAT.ChatItem {
  return {
    ...chat,
    checkpoint: controlEvent.checkpoint,
  };
}

export function requiresExplicitRestartConfirmation(decision: CHAT.CheckpointResumeDecision) {
  return decision === 'RESTART_FROM_CHECKPOINT';
}
