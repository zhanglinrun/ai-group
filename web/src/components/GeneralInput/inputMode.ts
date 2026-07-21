export type InputModeKey = 'auto' | 'standard' | 'deep';
export type ExecutionMode = 'AUTO' | 'STANDARD' | 'DEEP';

export function resolveInputMode(executionMode: ExecutionMode = 'STANDARD'): InputModeKey {
  if (executionMode === 'AUTO') return 'auto';
  if (executionMode === 'DEEP') return 'deep';
  return 'standard';
}

export function resolveExecutionMode(mode: InputModeKey): ExecutionMode {
  if (mode === 'auto') return 'AUTO';
  if (mode === 'deep') return 'DEEP';
  return 'STANDARD';
}

export function buildSubmitPayload(params: {
  question: string;
  visibleMode: InputModeKey;
  isDataAgent: boolean;
  visibleOutputProduct: CHAT.Product;
  uploadedFiles: CHAT.TFile[];
  modelId?: string;
  online?: boolean;
}) {
  const outputStyle = params.isDataAgent ? 'dataAgent' : params.visibleOutputProduct.type;

  return {
    message: params.question.trim(),
    outputStyle,
    executionMode: resolveExecutionMode(params.visibleMode),
    files: params.uploadedFiles.length > 0 ? params.uploadedFiles : undefined,
    // 数据分析模式独立引擎，不接受模型覆盖
    modelId: params.isDataAgent ? undefined : params.modelId,
    online: !params.isDataAgent && Boolean(params.online),
  };
}
