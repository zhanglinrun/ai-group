import type { FC } from 'react';

import { Message, MessageContent } from '@/components/ai-elements/message';

/**
 * 统一复用 Agent 执行时的初始 Thinking 占位。
 */
const PHASE_LABELS: Record<CHAT.AgentRunPhase, string> = {
  ANALYZING: '正在分析',
  PLANNING: '正在更新任务',
  EXECUTING: '正在执行',
  VERIFYING: '正在验证结果',
  FINALIZING: '正在整理结果',
};

const ThinkingMessage: FC<{ phase?: CHAT.AgentRunPhase }> = ({ phase }) => (
  <div className="mt-6 flex w-full justify-start">
    <Message from="assistant" className="w-full max-w-full">
      <MessageContent>
        <div className="flex items-center text-[15px] font-medium text-muted-foreground">
          <span className="thinking-shimmer text-[15px] font-medium tracking-[0.02em]">
            {phase ? PHASE_LABELS[phase] : '正在思考'}
          </span>
        </div>
      </MessageContent>
    </Message>
  </div>
);

export default ThinkingMessage;
