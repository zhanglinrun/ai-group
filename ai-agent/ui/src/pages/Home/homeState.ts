export function deriveConversationMetaFromInput(
  info: Pick<CHAT.TInputInfo, 'outputStyle' | 'deepThink'>,
  params: {
    productType: string;
    currentRole: CHAT.ConversationRole | null;
  },
) {
  const outputStyle = info.outputStyle || params.productType;
  const isChatMode = outputStyle === 'chat';
  // dataAgent 使用独立执行引擎，不接受 deepThink；聊天模式则必须保留用户选择，
  // 否则点击“深度思考”后会在 Home 状态层被静默重置为快速模式。
  const deepThink = outputStyle === 'dataAgent' ? false : Boolean(info.deepThink);

  return {
    productType: outputStyle,
    deepThink,
    role: isChatMode ? params.currentRole : null,
  };
}

export function shouldHydrateConversationHistory(params: {
  conversation: CHAT.ConversationHistory;
  hydratedSessionIds: Set<string>;
}) {
  return Boolean(
    params.conversation.sessionId &&
    params.conversation.chatList.length === 0 &&
    params.conversation.dataChatList.length === 0 &&
    !params.hydratedSessionIds.has(params.conversation.sessionId),
  );
}

export function resolveNewConversationMode(
  override?: Pick<Partial<CHAT.ConversationHistory>, 'productType' | 'deepThink'>,
) {
  const productType = override?.productType || 'chat';
  return {
    productType,
    deepThink: productType === 'dataAgent' ? false : Boolean(override?.deepThink),
  };
}
