export type InputModeKey = 'quick' | 'think' | 'research';

/**
 * 推理模式由输出格式与 deepThink 共同编码：
 * - chat + false：快速对话
 * - chat + true 或结构化输出 + false：深度思考
 * - 结构化输出 + true：深度研究（Plan-Solve）
 *
 * 这样“深度思考”既可以保留聊天输出，也可以选择网页/文档等交付格式，
 * 不会因为选择了 Web 输出就被 UI 错标成“深度研究”。
 */
export function resolveInputMode(productType?: string, deepThink = false): InputModeKey {
  if (productType === 'chat') {
    return deepThink ? 'think' : 'quick';
  }
  return deepThink ? 'research' : 'think';
}

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
    : params.visibleMode === 'quick'
      ? 'chat'
      : params.visibleOutputProduct.type;

  return {
    message: params.question.trim(),
    outputStyle,
    // 深度研究才让结构化输出进入 Plan-Solve；聊天输出的 think 标志
    // 仍会原样透传，不能在 Home 状态层被静默重置。
    deepThink:
      !params.isDataAgent &&
      (params.visibleMode === 'research' ||
        (params.visibleMode === 'think' && outputStyle === 'chat')),
    files: params.uploadedFiles.length > 0 ? params.uploadedFiles : undefined,
    aiAgentId: outputStyle === 'chat' ? params.chatRole?.agentId : undefined,
    // 数据分析模式独立引擎，不接受模型覆盖
    modelId: params.isDataAgent ? undefined : params.modelId,
  };
}
