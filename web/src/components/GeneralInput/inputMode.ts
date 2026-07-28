export type InputModeKey = 'standard' | 'deep';
export type ExecutionMode = 'AUTO' | 'STANDARD' | 'DEEP';

export function resolveInputMode(executionMode: ExecutionMode = 'STANDARD'): InputModeKey {
  if (executionMode === 'DEEP') return 'deep';
  return 'standard';
}

export function resolveExecutionMode(mode: InputModeKey): ExecutionMode {
  if (mode === 'deep') return 'DEEP';
  return 'STANDARD';
}

export function buildSubmitPayload(params: {
  question: string;
  visibleMode: InputModeKey;
  visibleOutputProduct: CHAT.Product;
  uploadedFiles: CHAT.TFile[];
  modelId?: string;
  online?: boolean;
}) {
  const outputStyle = params.visibleMode === 'deep'
    ? 'markdown'
    : params.visibleOutputProduct.type;

  return {
    message: params.question.trim(),
    outputStyle,
    executionMode: resolveExecutionMode(params.visibleMode),
    files: params.uploadedFiles.length > 0 ? params.uploadedFiles : undefined,
    modelId: params.modelId,
    online: Boolean(params.online),
  };
}
