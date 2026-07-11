type InputModeKey = 'quick' | 'think' | 'research';

export function buildSubmitPayload(params: {
  question: string;
  visibleMode: InputModeKey;
  isDataAgent: boolean;
  visibleOutputProduct: CHAT.Product;
  uploadedFiles: CHAT.TFile[];
  chatRole: CHAT.ConversationRole | null;
  modelId?: string;
}) {
  const outputStyle = params.isDataAgent
    ? 'dataAgent'
    : params.visibleOutputProduct.type;

  return {
    message: params.question.trim(),
    outputStyle,
    // 推理强度与交付格式是两个维度。普通对话同样可以开启深度思考。
    deepThink: !params.isDataAgent && params.visibleMode !== 'quick',
    files: params.uploadedFiles.length > 0 ? params.uploadedFiles : undefined,
    aiAgentId: outputStyle === 'chat' ? params.chatRole?.agentId : undefined,
    // 数据分析模式独立引擎，不接受模型覆盖
    modelId: params.isDataAgent ? undefined : params.modelId,
  };
}
