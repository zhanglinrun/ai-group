import { FC, memo, useCallback, useMemo } from 'react';
import AttachmentList from '@/components/AttachmentList';
import { getTaskFiles } from '@/utils/taskArtifacts';
import { Message, MessageContent } from '@/components/ai-elements/message';
import MarkdownRenderer from '@/components/ActionPanel/MarkdownRenderer';
import ThinkingMessage from './ThinkingMessage';
import RunStatus from '@/components/ActionView/RunStatus';
import { TodoSection } from './TodoSection';
import { Timeline } from './Timeline';
import { MessageToolbar } from './MessageToolbar';
import MessageMeta from './MessageMeta';
import { resolveConversationConclusionText } from './contentHelpers';
import VerificationCard from './VerificationCard';
import { resolveStopReasonLabel } from './stopReasons';
import { AgentRunProgress } from './AgentRunProgress';

type Props = {
  chat: CHAT.ChatItem;
  changeTask?: (task: CHAT.Task, chat?: CHAT.ChatItem) => void;
  changeFile?: (file: CHAT.TFile, chat?: CHAT.ChatItem) => void;
  onRegenerate?: () => void;
};

const ConclusionSection: FC<{
  chat: CHAT.ChatItem;
  changeFile?: (file: CHAT.TFile, chat?: CHAT.ChatItem) => void;
}> = ({ chat, changeFile }) => {
  const summary = resolveConversationConclusionText(chat);
  const summaryStreaming = !!chat.loading && chat.conclusion?.messageType === 'agent_stream';
  const attachmentFiles = useMemo(() => getTaskFiles(chat.conclusion), [chat.conclusion]);
  return (
    <div className="mb-[8px]">
      <div className="mb-[8px] rounded-2xl bg-white/72 px-1 py-1">
        <MarkdownRenderer
          markDownContent={summary}
          isStreaming={summaryStreaming}
          normalizationScope="structured_summary"
          className="conclusion-markdown text-[15px] leading-8"
        />
      </div>
      <AttachmentList
        files={attachmentFiles}
        preview={true}
        review={(file) => changeFile?.(file, chat)}
      />
    </div>
  );
};

const DialogueComponent: FC<Props> = ({ chat, changeTask, changeFile, onRegenerate }) => {
  const todos = chat.agentRun?.todos || [];
  const verification = chat.agentRun?.verification;
  // 仅有历史兼容层补出的 { status: 'passed' } 没有可读信息，
  // 不应在普通聊天里渲染成一整条“验证通过”横幅。
  const showVerification = Boolean(
    verification &&
      (verification.status !== 'passed' ||
        verification.summary ||
        verification.attempt ||
        verification.missingRequirements?.length ||
        verification.requiredActions?.length),
  );
  const stopReason = chat.agentRun?.stopReason;
  const stopReasonLabel = resolveStopReasonLabel(chat.agentRun?.status, stopReason);
  const hasAssistantPayload =
    !!chat.response ||
    !!chat.tip ||
    todos.length > 0 ||
    !!verification ||
    !!stopReason ||
    !!chat.tasks.length ||
    !!chat.conclusion;
  // 仅用于旧历史普通回答：实时 Agent Loop 的回答统一写入 conclusion。
  const showStandaloneResponse = !!chat.response && !chat.conclusion;

  const changeActiveChat = useCallback(
    (task: CHAT.Task, targetChat: CHAT.ChatItem) => {
      changeTask?.(task, targetChat);
    },
    [changeTask],
  );

  return (
    <div className="flex h-full flex-col text-[15px] font-normal text-[#111827]">
      {(chat.files || []).length ? (
        <div className="mt-6 flex w-full justify-end">
          <AttachmentList files={chat.files} preview={false} />
        </div>
      ) : null}

      {chat.query ? (
        <div className="mt-6 flex w-full justify-end">
          <Message from="user" className="max-w-[82%]">
            <MessageContent>{chat.query}</MessageContent>
          </Message>
        </div>
      ) : null}

      {chat.tip ? (
        <div className="mt-5 w-full text-[15px] text-muted-foreground">{chat.tip}</div>
      ) : null}

      <div className="mt-5 w-full">
        <RunStatus status={chat.metrics?.status} errorMsg={chat.tip} finishedAt={chat.finishedAt} />
      </div>

      {chat.agentRun ? <AgentRunProgress run={chat.agentRun} /> : null}

      {showStandaloneResponse ? (
        <div className="mt-6 flex w-full justify-start">
          <Message from="assistant" className="w-full max-w-full">
            <MessageContent>
              <MarkdownRenderer
                markDownContent={chat.response}
                isStreaming={chat.loading}
                normalizationScope="default"
              />
            </MessageContent>
            {!chat.loading ? (
              <>
                <MessageToolbar response={chat.response} onRegenerate={onRegenerate} />
                <MessageMeta metrics={chat.metrics} className="mt-1 px-1" />
              </>
            ) : null}
          </Message>
        </div>
      ) : null}

      {chat.loading && !hasAssistantPayload ? (
        <ThinkingMessage phase={chat.agentRun?.phase} />
      ) : null}

      {todos.length ? (
        <div className="mt-6 w-full">
          <TodoSection title={chat.agentRun?.todoTitle} todos={todos} />
        </div>
      ) : null}

      {showVerification && verification ? <VerificationCard verification={verification} /> : null}

      {stopReasonLabel ? (
        <div
          className="mt-4 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
          aria-label="agent-stop-reason"
        >
          {stopReasonLabel}
        </div>
      ) : null}

      {chat.tasks.length ? (
        <div className="mt-6 w-full">
          <Timeline chat={chat} changeActiveChat={changeActiveChat} changeFile={changeFile} />
        </div>
      ) : null}

      {chat.conclusion ? (
        <div className="w-full">
          <ConclusionSection chat={chat} changeFile={changeFile} />
          {!chat.loading ? <MessageMeta metrics={chat.metrics} className="mt-2" /> : null}
        </div>
      ) : null}
    </div>
  );
};

const Dialogue = memo(
  DialogueComponent,
  (prev, next) =>
    prev.chat === next.chat &&
    prev.changeTask === next.changeTask &&
    prev.changeFile === next.changeFile &&
    prev.onRegenerate === next.onRegenerate,
);

export default Dialogue;
