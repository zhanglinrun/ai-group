import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { ActionViewItemEnum } from '@/utils';
import { getStableTaskIdentity } from '@/utils/chat';
import Dialogue from '@/components/Dialogue';
import GeneralInput from '@/components/GeneralInput';
import ActionView from '@/components/ActionView';
import { productList, defaultProduct } from '@/utils/constants';
import { useMemoizedFn } from 'ahooks';
import classNames from 'classnames';
import { Modal } from 'antd';
import {
  Conversation,
  ConversationContent,
  ConversationScrollButton,
} from '@/components/ai-elements/conversation';
import { Maximize2, PanelLeftClose, PanelRightClose } from 'lucide-react';
import {
  canRegenerateChat,
  isAgentRunBlockingInput,
  useConversationStream,
} from './useConversationStream';
import { useWorkspacePanels } from './useWorkspacePanels';

type Props = {
  inputInfo: CHAT.TInputInfo;
  product?: CHAT.Product;
  conversation: CHAT.ConversationHistory;
  chatRoles: CHAT.FixRole[];
  models?: import('@/services/models').ModelItem[];
  selectedModelId?: string;
  onConversationChange: (
    conversationId: string,
    nextConversation: CHAT.ConversationHistory,
  ) => void;
  onRoleSelect: (role: CHAT.FixRole) => void;
  onSelectionChange?: (selection: {
    product: CHAT.Product;
    executionMode: CHAT.ExecutionMode;
  }) => void;
  onInputConsumed?: () => void;
  onSelectModel?: (modelId: string) => void;
  onRunSettled?: (sessionId: string) => void;
};

const getProductByType = (type?: string) => {
  return productList.find((item) => item.type === type) ?? defaultProduct;
};

const getTaskStableKey = (task?: CHAT.Task) => {
  return getStableTaskIdentity(task);
};

const ChatView: ReactorType.FC<Props> = (props) => {
  const {
    inputInfo: inputInfoProp,
    product,
    conversation,
    chatRoles,
    models = [],
    selectedModelId,
    onConversationChange,
    onRoleSelect,
    onSelectionChange,
    onInputConsumed,
    onSelectModel,
    onRunSettled,
  } = props;

  const [activeTask, setActiveTask] = useState<CHAT.Task>();
  const {
    leftPanelWidth,
    isDragging,
    isLeftCollapsed,
    isRightCollapsed,
    isFocusMode,
    containerRef,
    handleDragStart,
    setIsRightCollapsed,
    setIsFocusMode,
    toggleLeftPanel,
    toggleRightPanel: toggleWorkspaceRightPanel,
    toggleFocusMode,
  } = useWorkspacePanels();
  const actionViewRef = ActionView.useActionView();
  const [modal, contextHolder] = Modal.useModal();
  const conversationRef = useRef(conversation);
  const [isConversationSwitching, setIsConversationSwitching] = useState(false);
  const {
    taskList,
    workspaceStreamTask,
    activeRunState,
    setActiveRunState,
    showAction,
    changeActionStatus,
    loading: streamLoading,
    sendMessage,
    stopCurrentRun,
    regenerateLastMessage,
  } = useConversationStream({
    conversation,
    selectedModelId,
    onConversationChange,
    onRunSettled,
    onPrepareStreamingWorkspace: () => {
      // 新一轮请求开始后，工作区恢复自动跟随，避免仍停留在上一轮手动点开的旧任务上。
      setActiveTask(undefined);
      actionViewRef.current?.changeActionView(ActionViewItemEnum.follow);
    },
    onTokenUseUp: () => {
      modal.info({
        title: '您的试用次数已用尽',
        content: '如需额外申请，请联系 liyang.1236@jd.com',
      });
    },
  });
  const agentRunBlocksInput = isAgentRunBlockingInput(activeRunState?.status);

  useEffect(() => {
    conversationRef.current = conversation;
  }, [conversation]);

  useEffect(() => {
    setActiveTask((prevActiveTask) => {
      if (!prevActiveTask) {
        return prevActiveTask;
      }

      const activeTaskKey = getTaskStableKey(prevActiveTask);
      if (!activeTaskKey) {
        return prevActiveTask;
      }

      const matchedTask = taskList.find((task) => getTaskStableKey(task) === activeTaskKey);
      if (matchedTask) {
        return matchedTask;
      }

      if (getTaskStableKey(workspaceStreamTask) === activeTaskKey && workspaceStreamTask) {
        return workspaceStreamTask;
      }

      return prevActiveTask;
    });
  }, [taskList, workspaceStreamTask]);

  // Ensure fade-in starts before the browser paints after conversation switch.
  useLayoutEffect(() => {
    setIsConversationSwitching(true);
    const timer = setTimeout(() => setIsConversationSwitching(false), 220);
    return () => clearTimeout(timer);
  }, [conversation.id]);

  const changeTask = (task: CHAT.Task, chat?: CHAT.ChatItem) => {
    setIsRightCollapsed(false);
    actionViewRef.current?.changeActionView(ActionViewItemEnum.follow);
    changeActionStatus(true);
    setActiveTask(task);
    setActiveRunState({
      status: chat?.metrics?.status,
      finishedAt: chat?.finishedAt,
    });
  };

  const changeFile = (file: CHAT.TFile, chat?: CHAT.ChatItem) => {
    setIsRightCollapsed(false);
    changeActionStatus(true);
    setActiveRunState({
      status: chat?.metrics?.status,
      finishedAt: chat?.finishedAt,
    });
    actionViewRef.current?.setFilePreview(file);
  };

  const toggleRightPanel = useMemoizedFn(() => {
    changeActionStatus(isRightCollapsed);
    toggleWorkspaceRightPanel();
  });

  useEffect(() => {
    if (inputInfoProp.message?.length !== 0) {
      sendMessage(
        inputInfoProp.outputStyle === 'dataAgent'
          ? { ...inputInfoProp, outputStyle: 'chat', executionMode: 'STANDARD' }
          : inputInfoProp,
      );
      onInputConsumed?.();
    }
  }, [inputInfoProp, onInputConsumed, sendMessage]);

  const handleRegenerate = useMemoizedFn(() => {
    regenerateLastMessage();
  });

  const loading = streamLoading;

  const currentProduct = useMemo(() => {
    const nextProduct = getProductByType(conversation.productType || product?.type);
    return nextProduct.type === 'dataAgent' ? defaultProduct : nextProduct;
  }, [conversation.productType, product?.type]);

  const headerTitle = conversation.chatTitle || conversation.title;

  const renderChatDialogues = () => {
    if (isConversationSwitching) {
      return (
        <motion.div
          key={`switch-${conversation.id}`}
          initial={{
            opacity: 0.9,
            y: 6,
          }}
          animate={{
            opacity: 1,
            y: 0,
          }}
          transition={{
            duration: 0.14,
            ease: [0.25, 0.46, 0.45, 0.94],
          }}
        >
          {conversation.chatList.map((chat, index) => (
            <Dialogue
              key={chat.requestId}
              chat={chat}
              changeTask={changeTask}
              changeFile={changeFile}
              onRegenerate={
                index === conversation.chatList.length - 1 && canRegenerateChat(chat)
                  ? handleRegenerate
                  : undefined
              }
            />
          ))}
        </motion.div>
      );
    }

    return (
      <AnimatePresence mode="popLayout" initial={false}>
        {conversation.chatList.map((chat, index) => (
          <motion.div
            key={chat.requestId}
            initial={{
              opacity: 0.9,
              y: 6,
            }}
            animate={{
              opacity: 1,
              y: 0,
            }}
            exit={{
              opacity: 0.85,
              y: -4,
            }}
            transition={{
              duration: 0.14,
              ease: [0.25, 0.46, 0.45, 0.94],
            }}
          >
            <Dialogue
              chat={chat}
              changeTask={changeTask}
              changeFile={changeFile}
              onRegenerate={
                index === conversation.chatList.length - 1 && canRegenerateChat(chat)
                  ? handleRegenerate
                  : undefined
              }
            />
          </motion.div>
        ))}
      </AnimatePresence>
    );
  };

  const renderMultAgent = () => {
    // 如果没有工作空间内容，显示单面板
    if (!showAction) {
      return (
        <div className="flex h-full w-full justify-center overflow-hidden px-4 pt-4 md:px-6">
          <div
            className="flex h-full min-h-0 w-full max-w-[980px] flex-col overflow-hidden"
            id="chat-view"
          >
            <div className="mb-3 flex min-h-[36px] items-center justify-between px-1">
              <div className="flex min-w-0 items-center gap-3">
                <h2 className="truncate text-[16px] font-semibold tracking-tight text-[var(--chat-text)]">
                  {headerTitle}
                </h2>
                {conversation.executionMode !== 'STANDARD' && (
                  <div className="flex shrink-0 items-center gap-1.5 rounded-full bg-[var(--chat-surface-muted)] px-3 py-1 text-[12px] font-medium text-[var(--chat-text-soft)]">
                    <i className="font_family icon-shendusikao text-[11px]"></i>
                    <span>{conversation.executionMode === 'DEEP' ? '深度' : '自动'}</span>
                  </div>
                )}
              </div>
            </div>

            <Conversation className="chat-fade-bottom min-h-0 flex-1 overflow-hidden">
              <ConversationContent className="mx-auto w-full max-w-[860px] px-1 pb-6">
                {renderChatDialogues()}
              </ConversationContent>
              <ConversationScrollButton />
            </Conversation>

            <div className="shrink-0 bg-gradient-to-t from-[var(--page-gradient)] via-[var(--page-gradient)]/95 to-transparent pb-5 pt-4">
              <div className="mx-auto w-full max-w-[860px]">
                <GeneralInput
                  key={`input-${conversation.sessionId}-single`}
                  sessionId={conversation.sessionId}
                  placeholder={
                    conversation.role?.available === false
                      ? '当前角色已失效，请新建对话后重新选择角色'
                      : loading
                        ? '任务进行中...'
                        : agentRunBlocksInput
                          ? '当前任务仍在后台运行，请稍后重新打开会话查看结果'
                        : '希望熊博士agent为你做哪些任务呢？'
                  }
                  showBtn={true}
                  size="medium"
                  disabled={
                    loading || agentRunBlocksInput || conversation.role?.available === false
                  }
                  product={currentProduct}
                  executionMode={conversation.executionMode}
                  displayOutput={currentProduct}
                  chatRole={conversation.role}
                  chatRoles={chatRoles}
                  models={models}
                  selectedModelId={selectedModelId}
                  showModelSelector={true}
                  onSelectionChange={onSelectionChange}
                  onRoleSelect={onRoleSelect}
                  onSelectModel={onSelectModel}
                  send={(info) =>
                    sendMessage({
                      ...info,
                      aiAgentId:
                        info.outputStyle === 'chat' ? conversation.role?.agentId : undefined,
                    })
                  }
                />
              </div>
            </div>
          </div>
        </div>
      );
    }

    // 38/62 双面板布局；专注模式隐藏对话区，把工作区拉满
    return (
      <div ref={containerRef} className="flex h-full w-full gap-0.5 p-2 max-md:p-0">
        {/* Left Panel - Chat Area */}
        {!isFocusMode && (
          <div
            className={classNames(
              'flex min-h-0 flex-col overflow-hidden rounded-[24px] bg-white/90 transition-all duration-300',
              isLeftCollapsed && 'w-14 min-w-14',
              !isLeftCollapsed &&
                'flex-1 max-md:!w-full max-md:!min-w-0 max-md:!flex-[0_0_100%] max-md:rounded-none',
            )}
            style={!isLeftCollapsed ? { flex: `0 0 ${leftPanelWidth}%` } : undefined}
          >
            {isLeftCollapsed ? (
              // 折叠状态
              <div className="flex h-full flex-col items-center py-4">
                <button
                  onClick={toggleLeftPanel}
                  className="flex h-10 w-10 items-center justify-center rounded-full text-[#86868b] transition-colors hover:bg-[#f5f5f7] hover:text-[#1d1d1f]"
                  title="展开聊天区"
                >
                  <PanelRightClose className="h-5 w-5" />
                </button>
              </div>
            ) : (
              // 展开状态
              <>
                {/* Header */}
                <div className="flex items-center justify-between px-5 py-4">
                  <div className="flex min-w-0 items-center gap-3">
                    <h2 className="truncate text-[17px] font-semibold tracking-tight text-[#1d1d1f]">
                      {headerTitle}
                    </h2>
                    {conversation.executionMode !== 'STANDARD' && (
                      <div className="flex shrink-0 items-center gap-1.5 rounded-full bg-[#1d1d1f] px-3 py-1 text-[12px] font-medium text-white">
                        <i className="font_family icon-shendusikao text-[11px]"></i>
                        <span>{conversation.executionMode === 'DEEP' ? '深度' : '自动'}</span>
                      </div>
                    )}
                  </div>
                  <button
                    onClick={() => setIsFocusMode(true)}
                    className="flex h-8 w-8 items-center justify-center rounded-full text-[#86868b] transition-colors hover:bg-[#f5f5f7] hover:text-[#1d1d1f] md:hidden"
                    title="打开智能体工作区"
                  >
                    <Maximize2 className="h-4 w-4" />
                  </button>
                  <button
                    onClick={toggleLeftPanel}
                    className="hidden h-8 w-8 items-center justify-center rounded-full text-[#86868b] transition-colors hover:bg-[#f5f5f7] hover:text-[#1d1d1f] md:flex"
                    title="收起聊天区"
                  >
                    <PanelLeftClose className="h-4 w-4" />
                  </button>
                </div>

                {/* Messages */}
                <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
                  <Conversation className="chat-fade-bottom min-h-0 flex-1 overflow-hidden px-5 pt-5">
                    <ConversationContent>{renderChatDialogues()}</ConversationContent>
                    <ConversationScrollButton />
                  </Conversation>

                  {/* Input */}
                  <div className="shrink-0 bg-gradient-to-t from-white via-white/95 to-transparent px-4 pb-4 pt-3">
                    <GeneralInput
                      key={`input-${conversation.sessionId}-left`}
                      sessionId={conversation.sessionId}
                      placeholder={
                        conversation.role?.available === false
                          ? '当前角色已失效，请新建对话后重新选择角色'
                          : loading
                            ? '任务进行中...'
                            : agentRunBlocksInput
                              ? '当前任务仍在后台运行，请稍后重新打开会话查看结果'
                            : '希望熊博士agent为你做哪些任务呢？'
                      }
                      showBtn={true}
                      loading={streamLoading}
                      onStop={stopCurrentRun}
                      size="medium"
                      disabled={
                        loading || agentRunBlocksInput || conversation.role?.available === false
                      }
                      product={currentProduct}
                      executionMode={conversation.executionMode}
                      displayOutput={currentProduct}
                      chatRole={conversation.role}
                      chatRoles={chatRoles}
                      models={models}
                      selectedModelId={selectedModelId}
                      showModelSelector={true}
                      onSelectionChange={onSelectionChange}
                      onRoleSelect={onRoleSelect}
                      onSelectModel={onSelectModel}
                      send={(info) =>
                        sendMessage({
                          ...info,
                          aiAgentId:
                            info.outputStyle === 'chat' ? conversation.role?.agentId : undefined,
                        })
                      }
                    />
                  </div>
                </div>
              </>
            )}
          </div>
        )}

        {/* Drag Handle */}
        {!isFocusMode && !isLeftCollapsed && !isRightCollapsed && (
          <div
            onMouseDown={handleDragStart}
            className={classNames(
              'group relative flex w-3 shrink-0 cursor-col-resize items-center justify-center transition-colors',
              'max-md:hidden',
              'hover:bg-[#0071e3]/8',
              isDragging && 'bg-[#0071e3]/16',
            )}
            title="拖拽调整左右区域宽度"
          >
            {/* Wider hit area with slim visual indicator */}
            <div
              className={classNames(
                'h-10 w-0.5 rounded-full transition-all duration-200',
                isDragging ? 'bg-[#0071e3]' : 'bg-[#d2d2d7] group-hover:bg-[#86868b]',
              )}
            />
          </div>
        )}

        {/* Right Panel - Action/Workspace Area */}
        <div
          className={classNames(
            'flex min-h-0 flex-col overflow-hidden rounded-[24px] bg-white/90 transition-all duration-300',
            isRightCollapsed && 'w-14 min-w-14',
            !isRightCollapsed && 'flex-1 max-md:!w-full max-md:!min-w-0 max-md:!flex-[0_0_100%] max-md:rounded-none',
            !isFocusMode && 'max-md:hidden',
          )}
          style={
            !isRightCollapsed && !isFocusMode
              ? { flex: `0 0 ${100 - leftPanelWidth - (isLeftCollapsed ? 0 : 0)}%` }
              : undefined
          }
        >
          {isRightCollapsed ? (
            // 折叠状态
            <div className="flex h-full flex-col items-center py-4">
              <button
                onClick={toggleRightPanel}
                className="flex h-10 w-10 items-center justify-center rounded-full text-[#86868b] transition-colors hover:bg-[#f5f5f7] hover:text-[#1d1d1f]"
                title="展开智能体工作区"
              >
                <PanelLeftClose className="h-5 w-5" />
              </button>
            </div>
          ) : (
            // 展开状态 - 工作空间
            <ActionView
              activeTask={activeTask}
              streamTask={workspaceStreamTask}
              taskList={taskList}
              runState={activeRunState}
              isFocusMode={isFocusMode}
              onToggleFocusMode={toggleFocusMode}
              ref={actionViewRef}
              onClose={() => {
                if (isFocusMode) {
                  setIsFocusMode(false);
                } else {
                  changeActionStatus(false);
                  setIsRightCollapsed(true);
                }
              }}
            />
          )}
        </div>

        {contextHolder}
      </div>
    );
  };

  return (
    <div className="flex h-full w-full justify-center">
      {renderMultAgent()}
    </div>
  );
};

export default ChatView;
