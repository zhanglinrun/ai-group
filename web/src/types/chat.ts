declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace CHAT {
    export type ChatItem = ReactorType.Merge<
      Pick<MESSAGE.Question, 'sessionId' | 'query' | 'requestId'>,
      {
        files: TFile[];
        generatedFiles?: TFile[];
        forceStop: boolean;
        tip?: string;
        multiAgent: MESSAGE.MultiAgent;
        conclusion?: Task;
        responseType?: string;
        loading: boolean;
        tasks: Task[][];
        response?: string;
        taskStatus?: MESSAGE.MsgItem['taskStatus'];
        timeline?: TimelineEntry[];
        agentRun?: AgentLoopViewState;
        metrics?: {
          event_count?: number;
          status?: string;
          /** 本轮实际使用的模型名（用户可选模型 / 默认模型解析结果）。 */
          modelName?: string;
          /** 本轮总 token 用量（流式下可能缺失）。 */
          totalTokens?: number;
          /** 本轮耗时（毫秒）。 */
          durationMs?: number;
        };
        startedAt?: string;
        finishedAt?: string;
      }
    >;

    export type ExecutionMode = 'AUTO' | 'STANDARD' | 'DEEP';
    export type AgentRunPhase =
      | 'ANALYZING'
      | 'PLANNING'
      | 'EXECUTING'
      | 'VERIFYING'
      | 'FINALIZING';
    export type AgentRunStatus =
      | 'RUNNING'
      | 'SUCCESS'
      | 'FAILED'
      | 'STOPPED'
      | 'TIMEOUT';
    export type TodoStatus = 'pending' | 'in_progress' | 'completed' | 'blocked' | 'failed';
    export type TodoEvidencePolicy = 'NONE' | 'TOOL' | 'LEGACY';
    export type TodoItem = {
      id: string;
      title: string;
      detail?: string;
      status: TodoStatus;
      evidencePolicy?: TodoEvidencePolicy;
      evidenceRefs?: string[];
    };
    export type VerificationState = {
      status: 'running' | 'passed' | 'failed';
      summary?: string;
      missingRequirements?: string[];
      requiredActions?: string[];
      attempt?: number;
    };
    export type AgentLoopViewState = {
      runId?: string;
      phase?: AgentRunPhase;
      status: AgentRunStatus;
      todoTitle?: string;
      todos: TodoItem[];
      verification?: VerificationState;
      /** 是否已收到权威 run_finished；result 本身不得替代该终态事件。 */
      terminalEventSeen?: boolean;
      completionGatePassed?: boolean;
      stopReason?: string;
    };

    export type TimelineEntry = {
      seq: number;
      type: string;
      subType?: string;
      area: string;
      title: string;
      content?: string;
      taskId?: string;
      taskOrder?: number;
      messageIdExt?: string;
      isFinal: boolean;
      status?: string;
      payload?: Record<string, unknown>;
    };

    export type TFile = {
      name: string;
      url: string;
      type: string;
      size: number;
      previewUrl?: string;
      downloadUrl?: string;
      missing?: boolean;
      missingReason?: string;
      resourceKey?: string;
      mimeType?: string | null;
      originFileName?: string;
    };

    export type TInputInfo = {
      files?: TFile[];
      message: string;
      outputStyle?: string;
      executionMode: ExecutionMode;
      /** 是否允许本轮 Agent 使用联网搜索工具。 */
      online?: boolean;
      aiAgentId?: string;
      /** 用户在输入框选择的模型 ID；为空则由后端走默认模型逻辑。 */
      modelId?: string;
    };

    export type TAbortController = {
      signal: AbortSignal;
      abort(reason?: unknown): void;
    };

    export type FetchEventSourceInit = {
      onopen: (event: Event) => void;
      onmessage: (event: unknown) => void;
      onerror: (event?: Event) => void;
      onclose: (event?: Event) => void;
      headers?: Record<string, string>;
      body?: string;
    };

    export type Task = ReactorType.Merge<
      MESSAGE.Task,
      {
        resultMap: ReactorType.Merge<
          MESSAGE.ResultMap,
          {
            searchResult?: ReactorType.Merge<
              MESSAGE.SearchResult,
              {
                docs: MESSAGE.Doc[];
              }
            >;
            code?: string;
          }
        >;
        id: string;
        children?: Task[];
      }
    >;

    export type DataChatChartItem = Record<string, unknown>;

    export type DataChatItem = {
      query: string;
      loading: boolean;
      think: string;
      chartData?: DataChatChartItem[];
      error: string;
    };

    export type DataChatEvent =
      | {
          eventType: 'THINK';
          data: string;
        }
      | {
          eventType: 'CHART_DATA';
          data: DataChatChartItem[];
        }
      | {
          eventType: 'ERROR';
          data: string;
        }
      | {
          eventType: 'READY';
          data?: unknown;
        };

    export type FileList = MESSAGE.FileInfo;

    export type Product = {
      name: string;
      img: string;
      type: string;
      placeholder: string;
      color: string;
    };

    export type ConversationHistory = {
      id: string;
      sessionId: string;
      title: string;
      productType: string;
      executionMode: ExecutionMode;
      role?: ConversationRole | null;
      createdAt: number;
      updatedAt: number;
      chatTitle: string;
      chatList: ChatItem[];
      dataChatList: DataChatItem[];
    };

    export type ModelInfo = {
      modelName: string;
      modelCode: string;
      schemaList: {
        columnComment: string;
        columnName: string;
        dataType: string;
        columnId: string;
      }[];
    };

    export type ConversationRole = {
      agentId: string;
      agentName: string;
      available: boolean;
      defaultRole: boolean;
    };

    export type FixRole = {
      agentId: string;
      agentName: string;
      description?: string;
      defaultRole: boolean;
    };

    export type ConversationSessionItem =
      import('@/services/agentConversation').ConversationSessionItem;
    export type ConversationHistoryDetail =
      import('@/services/agentConversation').ConversationHistoryDetail;
    export type ConversationHistoryRunDetail =
      import('@/services/agentConversation').ConversationHistoryRunDetail;
    export type ConversationReplayFrame =
      import('@/services/agentConversation').ConversationReplayFrame;
  }
}
