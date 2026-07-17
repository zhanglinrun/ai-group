export function deriveConversationMetaFromInput(
  info: Pick<CHAT.TInputInfo, 'outputStyle' | 'executionMode'>,
  params: {
    productType: string;
    currentRole: CHAT.ConversationRole | null;
  },
) {
  const outputStyle = info.outputStyle || params.productType;
  const isChatMode = outputStyle === 'chat';

  return {
    productType: outputStyle,
    executionMode: outputStyle === 'dataAgent' ? ('STANDARD' as const) : info.executionMode,
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
  override?: Pick<Partial<CHAT.ConversationHistory>, 'productType' | 'executionMode'>,
) {
  const productType = override?.productType || 'chat';
  return {
    productType,
    executionMode:
      productType === 'dataAgent' ? ('STANDARD' as const) : override?.executionMode || 'STANDARD',
  };
}
