import {
  Bot,
  CheckCircle2,
  Circle,
  CircleDot,
  Loader2,
  Sparkles,
  User as UserIcon,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import {
  fetchRunIntakeSession,
  useRunDetail,
  useCreateRunIntake,
  useReplyRunIntake,
  type CreateRunIntakeVariables,
} from "@/api/hooks";
import {
  useRunEvents,
  type IntakeClarifyEventPayload,
  type IntakeCompletePayload,
  type IntakeUserReplyPayload,
} from "@/api/sse";
import type {
  IntakeClarifyRequest,
  IntakeCreateRequest,
  IntakeUserReply,
  ReportDepth,
  RunIntakeDraft,
  UserRole,
} from "@/api/types";
import { CancelRunButton } from "@/components/CancelRunButton";
import { IntakeModeSwitcher } from "@/components/intake/IntakeModeSwitcher";
import { ReportDepthSelector } from "@/components/intake/ReportDepthSelector";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { NativeSelect } from "@/components/ui/native-select";
import { track } from "@/lib/analytics";
import { cn } from "@/lib/utils";
import { pushToast } from "@/components/ui/toaster";

// --- Message model --------------------------------------------------------

type ChatMessage =
  | { id: string; kind: "assistant.welcome"; text: string }
  | {
      id: string;
      kind: "assistant.clarify";
      question: string;
      fieldTargets: string[];
      suggestedOptions: string[];
      suggestedAnswer: string | null;
    }
  | { id: string; kind: "assistant.complete"; text: string }
  | { id: string; kind: "assistant.error"; text: string }
  | { id: string; kind: "user"; text: string; selectedOptions: string[] };

type ChatStatus =
  | "idle"
  | "creating"
  | "awaiting_user"
  | "awaiting_profile"
  | "replying"
  | "resuming"
  | "complete"
  | "error";

const WELCOME_TEXT =
  "你好，我是熊博士。告诉我你想做的竞品分析，我会先帮你对齐角色、意图和竞品范围，再开始抓取证据。";

const POST_COMPLETE_DELAY_MS = 1500;

// run_id is the session id. We pin it in sessionStorage (not the URL, since the
// chat lives at the static /runs/new route) so a refresh can restore the current
// pending question from the server instead of losing the thread.
const INTAKE_SESSION_KEY = "xiongdoctor.intake.run_id";
type ReportLanguage = "zh" | "en";

function normalizeReportDepth(raw: unknown): ReportDepth {
  if (raw === "deep" || raw === "quick") {
    return raw;
  }
  if (raw === "debug" && import.meta.env.DEV) {
    return "debug";
  }
  return "quick";
}

function normalizeReportLanguage(raw: unknown): ReportLanguage {
  return raw === "en" ? "en" : "zh";
}

function reportLanguageLabel(language: ReportLanguage): string {
  return language === "en" ? "English" : "中文";
}

function reportDepthLabel(depth: ReportDepth): string {
  if (depth === "deep") {
    return "深度报告";
  }
  if (depth === "debug") {
    return "Debug（仅调试）";
  }
  return "速览";
}

// Raw schema keys (market_scope, domain_hint, …) are debug-grade. Map them to
// user-facing labels for the clarify bubble; unknown keys are hidden, not shown raw.
const FIELD_TARGET_LABELS: Record<string, string> = {
  user_role: "用户身份",
  analysis_intent: "分析意图",
  competitors_explicit: "竞品范围",
  competitors_discovery_mode: "竞品范围",
  domain_hint: "行业领域",
  focus_dimensions: "关注维度",
  report_depth: "报告深度",
  reference_urls: "参考链接",
  self_product: "我方产品",
  market_scope: "市场范围",
  time_context: "时效",
};

function buildIdempotencyKey(): string {
  if (
    typeof globalThis.crypto !== "undefined" &&
    typeof globalThis.crypto.randomUUID === "function"
  ) {
    return globalThis.crypto.randomUUID();
  }
  return `intake_${Date.now()}_${Math.random().toString(16).slice(2)}`;
}

// Curated example prompts the user can one-click into the composer. Three
// scenarios chosen to be mutually orthogonal on (industry × user role ×
// requirement clarity), so a stranger to the system sees the full coverage:
//   - trae_full       → TC-I7  PM     × AI Coding IDE × discovery + dense spec
//   - hr_saas_ai      → TC-I1  PM     × HR SaaS       × discovery-first（竞品范围未知）
//   - ai_resume_track → TC-I2  Founder× HR Tech       × 半模糊（创业者给投资人讲故事）
interface ExamplePrompt {
  id: string;
  title: string;
  subtitle: string;
  recommended?: boolean;
  query: string;
}

// Product-level pipeline only — never scenario-specific. Concrete plan tasks
// and report sections are produced by Planner / Writer after the user sends.
const ANALYSIS_PIPELINE_STEPS: Array<{ title: string; text: string }> = [
  {
    title: "澄清需求",
    text: "Intake Agent 根据你的描述追问，补齐角色、意图与竞品范围（左侧清单同步更新）",
  },
  {
    title: "确认计划",
    text: "Planner Agent 生成任务树，你在计划页勾选、跳过或追加关注点",
  },
  {
    title: "执行调研",
    text: "多 Agent 采集公开证据，Live 页可查看工具调用与溯源过程",
  },
  {
    title: "产出报告",
    text: "生成 Battlecard 与可分享报告，结论均可追溯到 evidence",
  },
];

const EXAMPLE_PROMPTS: ExamplePrompt[] = [
  {
    id: "trae_full",
    title: "TRAE 对标全球 AI 编程 IDE",
    subtitle: "产品战略 · 发现未知竞品 · 投入方向建议",
    recommended: true,
    query:
      "我们是字节 AIGC 创新孵化团队，TRAE 已在集团内部上线，下个月要给高层做汇报。目前我知道的外部对手有 Cursor、GitHub Copilot、Windsurf，国内有通义灵码、文心 Comate，但这个赛道迭代太快，我不确定还漏了哪些值得关注的竞品或厂商，请你主动发现补全。重点对比：产品定位与目标开发者画像、定价与商业化模型（个人版 vs 企业版）、AI Agent 化程度、代码库级上下文能力，以及中国 vs 海外的市场策略差异。最终结论要能直接回答：TRAE 下一步在哪几个方向有差异化机会，哪几个战场已经过于拥挤。",
  },
  {
    id: "hr_saas_ai",
    title: "企业 HR SaaS 的 AI 能力竞调",
    subtitle: "产品经理 · 竞品范围未知 · 让 Agent 主动摸底",
    query:
      "我们公司做企业 HR SaaS，现有简历筛选和入职管理两个模块，已在 200 多家中小企业上线。CTO 上周说竞品都在上 AI，让我做一次全面竞调，看看行业里 AI 都接了哪些功能、哪家做得最深。我对这块不太了解，国内只听说过北森、Moka，国外知道 Workday、SAP SuccessFactors，但 AI 能力这块完全不清楚谁是真的在做还是在堆概念。麻烦帮我把这个赛道的 AI 能力格局摸清楚，不要只分析我提到的这几家，如果有我不知道的新兴竞品或垂直厂商也一起带进来。",
  },
  {
    id: "ai_resume_track",
    title: "AI 求职工具的赛道全图",
    subtitle: "创业者 · 早期对标 · 给种子轮投资人讲故事",
    query:
      "我们三人团队在做「AI 简历优化 + 求职进度追踪」工具，目标用户是国内应届生和 1-3 年经验的年轻人，MVP 刚在小红书内测，反馈还不错。下个月要见种子轮投资人，需要把赛道讲清楚。海外我知道有 Teal、Rezi 做简历，LinkedIn 在推 AI 求职助手；国内感觉很乱，不知道谁是真正的竞品、谁只是个单点功能。请帮我把这个赛道的主要产品和公司找出来，梳理各自的用户定位、核心功能和商业模式，然后帮我判断「简历优化 + 求职追踪」的组合切入点还有没有空间。",
  },
];

// --- Helpers --------------------------------------------------------------

function newMessageId(): string {
  return `msg_${Date.now().toString(36)}_${Math.random().toString(16).slice(2, 8)}`;
}

function emptyDraft(userQuery: string): RunIntakeDraft {
  return {
    user_query: userQuery,
    user_role: null,
    analysis_intent: null,
    competitors_explicit: [],
    competitors_discovery_mode: false,
    domain_hint: null,
    focus_dimensions: [],
    report_depth: "quick",
    reference_urls: [],
    self_product: null,
    market_scope: null,
    time_context: null,
    response_language: null,
    analysis_archetype: "comparison",
    is_complete: false,
  };
}

function normalizeIntakeDraft(raw: unknown, fallbackQuery: string): RunIntakeDraft {
  const source = raw && typeof raw === "object" ? (raw as Partial<RunIntakeDraft>) : {};
  const base = emptyDraft(typeof source.user_query === "string" ? source.user_query : fallbackQuery);
  const list = (value: unknown): string[] =>
    Array.isArray(value)
      ? value.filter((item): item is string => typeof item === "string" && item.trim().length > 0)
      : [];
  return {
    ...base,
    user_query: typeof source.user_query === "string" ? source.user_query : base.user_query,
    user_role: source.user_role ?? null,
    analysis_intent: typeof source.analysis_intent === "string" ? source.analysis_intent : null,
    competitors_explicit: list(source.competitors_explicit),
    competitors_discovery_mode: source.competitors_discovery_mode === true,
    domain_hint: typeof source.domain_hint === "string" ? source.domain_hint : null,
    focus_dimensions: list(source.focus_dimensions),
    report_depth: normalizeReportDepth(source.report_depth),
    reference_urls: list(source.reference_urls),
    self_product: typeof source.self_product === "string" ? source.self_product : null,
    market_scope: typeof source.market_scope === "string" ? source.market_scope : null,
    time_context: typeof source.time_context === "string" ? source.time_context : null,
    response_language: source.response_language === "en" || source.response_language === "zh"
      ? source.response_language
      : null,
    analysis_archetype: typeof source.analysis_archetype === "string"
      ? source.analysis_archetype
      : "comparison",
    is_complete: source.is_complete === true,
  };
}

function clarifyMessageFromPayload(
  payload: Pick<IntakeClarifyRequest, "question" | "field_targets"> & {
    suggested_options: string[] | null;
    suggested_answer: string | null;
  },
): ChatMessage {
  const fieldTargets = [...(payload.field_targets ?? [])];
  const suggestedOptions = payload.suggested_options ? [...payload.suggested_options] : [];
  if (import.meta.env.DEV && fieldTargets.includes("report_depth")) {
    const hasDebugOption = suggestedOptions.some(
      (option) => option.toLowerCase().includes("debug") || option.includes("调试"),
    );
    if (!hasDebugOption) {
      suggestedOptions.push("调试 (debug)");
    }
  }
  return {
    id: newMessageId(),
    kind: "assistant.clarify",
    question: payload.question,
    fieldTargets,
    suggestedOptions,
    suggestedAnswer: payload.suggested_answer,
  };
}

interface ChecklistRow {
  id: string;
  label: string;
  hint: string;
  satisfied: boolean;
  active: boolean;
}

// Map a chat-side clarify event's field_targets to the three checklist rows
// the right panel renders. This is what gives the "in_progress" item its
// amber color: the row whose target field the Agent is currently asking about.
const CHECKLIST_FIELD_MAP: Record<string, string> = {
  user_role: "identity",
  analysis_intent: "intent",
  competitors_explicit: "competitors",
  competitors_discovery_mode: "competitors",
};

function deriveChecklistRows(
  draft: RunIntakeDraft | null,
  activeRowIds: Set<string>,
): ChecklistRow[] {
  const identitySatisfied = draft?.user_role !== null && draft?.user_role !== undefined;
  const intentSatisfied = Boolean(draft?.analysis_intent);
  const competitorsSatisfied = competitorPathSatisfied(draft);
  return [
    {
      id: "identity",
      label: "用户身份",
      hint:
        identitySatisfied && draft
          ? roleLabel(draft.user_role as UserRole)
          : "PM / 创业者 / 销售 / 投资人",
      satisfied: identitySatisfied,
      active: !identitySatisfied && activeRowIds.has("identity"),
    },
    {
      id: "intent",
      label: "分析意图",
      hint: intentSatisfied && draft?.analysis_intent
        ? draft.analysis_intent
        : "你最希望解决的问题或决策",
      satisfied: intentSatisfied,
      active: !intentSatisfied && activeRowIds.has("intent"),
    },
    {
      id: "competitors",
      label: "竞品范围",
      hint: competitorHint(draft),
      satisfied: competitorsSatisfied,
      active: !competitorsSatisfied && activeRowIds.has("competitors"),
    },
  ];
}

function activeRowIdsFromFieldTargets(fieldTargets: string[]): Set<string> {
  const ids = new Set<string>();
  for (const target of fieldTargets) {
    const mapped = CHECKLIST_FIELD_MAP[target];
    if (mapped) {
      ids.add(mapped);
    }
  }
  return ids;
}

function roleLabel(role: UserRole): string {
  switch (role) {
    case "pm":
      return "产品经理";
    case "founder":
      return "创业者";
    case "sales":
      return "销售";
    case "investor":
      return "投资人";
    default:
      return role;
  }
}

function competitorHint(draft: RunIntakeDraft | null): string {
  if (!draft) {
    return "指定竞品或让 Agent 自动发现";
  }
  if (draft.competitors_explicit.length > 0) {
    return draft.competitors_explicit.join("、");
  }
  if (draft.competitors_discovery_mode) {
    return "由 Agent 自动发现赛道头部";
  }
  return "指定竞品或让 Agent 自动发现";
}

function competitorPathSatisfied(draft: RunIntakeDraft | null): boolean {
  if (!draft) {
    return false;
  }
  return draft.competitors_explicit.length > 0 || draft.competitors_discovery_mode;
}

type ChecklistPhase = "pending" | "in_progress" | "complete";

function deriveChecklistPhase(
  rows: Array<{ satisfied: boolean }>,
  draft: RunIntakeDraft | null,
): ChecklistPhase {
  if (draft?.is_complete) {
    return "complete";
  }
  const satisfiedCount = rows.filter((row) => row.satisfied).length;
  if (satisfiedCount === 0) {
    return "pending";
  }
  if (satisfiedCount >= rows.length) {
    return "complete";
  }
  return "in_progress";
}

function checklistPhaseLabel(phase: ChecklistPhase): string {
  switch (phase) {
    case "pending":
      return "待开始";
    case "in_progress":
      return "进行中";
    case "complete":
      return "已就绪";
  }
}

// --- Page ------------------------------------------------------------------

export function NewRunChatPage(): JSX.Element {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const createIntake = useCreateRunIntake();
  const replyIntake = useReplyRunIntake();
  const fromRunId = searchParams.get("from")?.trim() || null;
  const seedCompetitorsFromQuery = useMemo(() => {
    const raw = searchParams.get("seed");
    if (!raw) {
      return [];
    }
    return Array.from(
      new Set(
        raw
          .split(",")
          .map((item) => item.trim())
          .filter((item) => item.length > 0),
      ),
    );
  }, [searchParams]);
  const sourceRunQuery = useRunDetail(fromRunId ?? "");

  const [runId, setRunId] = useState<string | null>(null);
  const [draft, setDraft] = useState<RunIntakeDraft | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>(() => [
    { id: "welcome", kind: "assistant.welcome", text: WELCOME_TEXT },
  ]);
  const [status, setStatus] = useState<ChatStatus>("idle");
  const [composerText, setComposerText] = useState("");
  const [composerOptions, setComposerOptions] = useState<string[]>([]);
  const [reportDepth, setReportDepth] = useState<ReportDepth>("quick");
  const [reportLanguage, setReportLanguage] = useState<ReportLanguage>("zh");
  const [selectedExampleId, setSelectedExampleId] = useState<string | null>(null);

  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const composerTextareaRef = useRef<HTMLTextAreaElement | null>(null);
  const ghostShownMessageIdsRef = useRef<Set<string>>(new Set());
  const createIdempotencyKeyRef = useRef<string | null>(null);
  const createIdempotencyQueryRef = useRef<string | null>(null);
  const freshStartToken = searchParams.get("fresh");

  const resetIntakeSession = useCallback(() => {
    sessionStorage.removeItem(INTAKE_SESSION_KEY);
    setRunId(null);
    setDraft(null);
    setMessages([{ id: "welcome", kind: "assistant.welcome", text: WELCOME_TEXT }]);
    setStatus("idle");
    setComposerText("");
    setComposerOptions([]);
    setReportDepth("quick");
    setReportLanguage("zh");
    setSelectedExampleId(null);
    ghostShownMessageIdsRef.current.clear();
    createIdempotencyKeyRef.current = null;
    createIdempotencyQueryRef.current = null;
  }, []);

  useEffect(() => {
    if (freshStartToken === null) {
      return;
    }
    resetIntakeSession();
    navigate("/app/runs/new", { replace: true });
  }, [freshStartToken, navigate, resetIntakeSession]);

  // Auto-scroll the chat thread to the latest message.
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages.length]);

  // Session restore: the server is the source of truth. On (re)mount, if a run is
  // pinned in sessionStorage and still awaiting the user mid-intake, rebuild the
  // thread from history + pending_clarify so a refresh resumes the current question.
  const restoredRef = useRef(false);
  useEffect(() => {
    if (freshStartToken !== null) {
      sessionStorage.removeItem(INTAKE_SESSION_KEY);
      return;
    }
    if (restoredRef.current) {
      return;
    }
    restoredRef.current = true;
    const savedRunId = sessionStorage.getItem(INTAKE_SESSION_KEY);
    if (savedRunId === null || savedRunId.length === 0) {
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const session = await fetchRunIntakeSession(savedRunId);
        if (cancelled) {
          return;
        }
        if (
          !session.awaiting_user ||
          (session.phase !== "intake" && session.phase !== "planning")
        ) {
          sessionStorage.removeItem(INTAKE_SESSION_KEY);
          return;
        }
        const rebuilt: ChatMessage[] = [
          { id: "welcome", kind: "assistant.welcome", text: WELCOME_TEXT },
        ];
        if (session.intake_draft?.user_query) {
          rebuilt.push({
            id: newMessageId(),
            kind: "user",
            text: session.intake_draft.user_query,
            selectedOptions: [],
          });
        }
        for (const exchange of session.history) {
          rebuilt.push(clarifyMessageFromPayload(exchange.clarify));
          rebuilt.push({
            id: newMessageId(),
            kind: "user",
            text: exchange.reply.text,
            selectedOptions: [...exchange.reply.selected_options],
          });
        }
        if (session.pending_clarify !== null) {
          rebuilt.push(clarifyMessageFromPayload(session.pending_clarify));
        }
        setMessages(rebuilt);
        const restoredDraft = normalizeIntakeDraft(session.intake_draft, "");
        setDraft(restoredDraft);
        setReportDepth(normalizeReportDepth(restoredDraft.report_depth));
        setReportLanguage(normalizeReportLanguage(restoredDraft.response_language));
        setRunId(session.run_id);
        setStatus(session.phase === "planning" ? "awaiting_profile" : "awaiting_user");
      } catch {
        // Stale / deleted run: drop the pointer and let the user start fresh.
        sessionStorage.removeItem(INTAKE_SESSION_KEY);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [freshStartToken]);

  const inheritedSeedCompetitors = useMemo(() => {
    if (seedCompetitorsFromQuery.length > 0) {
      return seedCompetitorsFromQuery;
    }
    const fromDetail = sourceRunQuery.data;
    if (!fromDetail) {
      return [];
    }
    if (fromDetail.seed_competitor_ids && fromDetail.seed_competitor_ids.length > 0) {
      return fromDetail.seed_competitor_ids;
    }
    return fromDetail.competitors;
  }, [seedCompetitorsFromQuery, sourceRunQuery.data]);

  useEffect(() => {
    if (runId !== null) {
      return;
    }
    const inheritedReportLanguage = sourceRunQuery.data?.intake_draft?.response_language;
    if (inheritedReportLanguage === "zh" || inheritedReportLanguage === "en") {
      setReportLanguage(inheritedReportLanguage);
    }
  }, [runId, sourceRunQuery.data?.intake_draft?.response_language]);

  useEffect(() => {
    if (fromRunId === null || runId !== null || status !== "idle") {
      return;
    }
    if (composerText.trim().length > 0) {
      return;
    }
    if (messages.length > 1) {
      return;
    }
    if (sourceRunQuery.isLoading) {
      return;
    }
    const focusTargets = inheritedSeedCompetitors.slice(0, 5);
    if (focusTargets.length > 0) {
      setComposerText(
        `基于上次结果，聚焦分析 ${focusTargets.join("、")}，输出功能、定价与用户反馈对比。`,
      );
      return;
    }
    setComposerText("基于上次结果做一轮更聚焦的竞品对比，输出功能、定价与用户反馈分析。");
  }, [
    composerText,
    fromRunId,
    inheritedSeedCompetitors,
    messages.length,
    runId,
    sourceRunQuery.isLoading,
    status,
  ]);

  const currentClarify = useMemo<ChatMessage | null>(() => {
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      const msg = messages[i];
      if (msg.kind === "assistant.clarify") {
        return msg;
      }
    }
    return null;
  }, [messages]);
  const activeGhostSuggestion = useMemo(() => {
    if (
      status !== "awaiting_user" ||
      currentClarify === null ||
      currentClarify.kind !== "assistant.clarify"
    ) {
      return null;
    }
    if (currentClarify.suggestedOptions.length > 0) {
      return null;
    }
    return currentClarify.suggestedAnswer;
  }, [currentClarify, status]);

  // --- SSE: clarify_request / user_reply / complete -------------------------

  // Merge a partial draft snapshot (from any SSE event) into local state.
  // Backend is the source of truth — we replace fields it sent rather than
  // patching, so a field that backend explicitly cleared can land in FE state.
  const applyDraftSnapshot = useCallback(
    (snapshot: Record<string, unknown> | undefined) => {
      if (!snapshot) {
        return;
      }
      const responseLanguageRaw = snapshot.response_language;
      if (responseLanguageRaw === "zh" || responseLanguageRaw === "en") {
        setReportLanguage(responseLanguageRaw);
      }
      setDraft((prev) => {
        const base = prev ?? emptyDraft(typeof snapshot.user_query === "string" ? snapshot.user_query : "");
        const next = normalizeIntakeDraft({ ...base, ...snapshot }, base.user_query);
        // is_complete is computed server-side; recompute locally as a guard so
        // the checklist badge cannot drift if backend forgets to send it.
        next.is_complete =
          next.user_role !== null &&
          next.user_role !== undefined &&
          Boolean(next.analysis_intent && next.analysis_intent.length > 0) &&
          (next.competitors_explicit.length > 0 || next.competitors_discovery_mode === true);
        return next;
      });
    },
    [],
  );

  const handleIntakeClarify = useCallback(
    (payload: IntakeClarifyEventPayload) => {
      applyDraftSnapshot(payload.draft);
      setMessages((prev) => [
        ...prev,
        clarifyMessageFromPayload({
          question: payload.question,
          field_targets: payload.field_targets,
          suggested_options: payload.suggested_options,
          suggested_answer: payload.suggested_answer,
        }),
      ]);
      setStatus("awaiting_user");
      setComposerOptions([]);
    },
    [applyDraftSnapshot],
  );

  const handleIntakeUserReply = useCallback(
    (payload: IntakeUserReplyPayload) => {
      // Post-merge draft from the wait node — paints checklist before the next
      // clarify_request arrives. This is what makes the right-side requirement
      // checklist feel instantaneous after the user clicks Send.
      applyDraftSnapshot(payload.draft);
    },
    [applyDraftSnapshot],
  );

  const handleIntakeComplete = useCallback(
    (payload: IntakeCompletePayload) => {
      const draftFromEvent = payload.draft as Partial<RunIntakeDraft> | undefined;
      if (draftFromEvent) {
        setDraft({
          ...emptyDraft(draftFromEvent.user_query ?? ""),
          ...draftFromEvent,
          is_complete: true,
        });
      }
      setMessages((prev) => [
        ...prev,
        {
          id: newMessageId(),
          kind: "assistant.complete",
          text: "需求确认完成。请先选择分析档位，再生成计划。",
        },
      ]);
      if (draftFromEvent?.report_depth !== undefined) {
        setReportDepth(normalizeReportDepth(draftFromEvent.report_depth));
      }
      if (draftFromEvent?.response_language === "zh" || draftFromEvent?.response_language === "en") {
        setReportLanguage(draftFromEvent.response_language);
      }
      setStatus("awaiting_profile");
      pushToast({
        title: "需求确认完成",
        description: "请选择分析档位后继续。",
        variant: "success",
      });
    },
    [],
  );

  useRunEvents(runId ?? "", {
    onIntakeClarify: handleIntakeClarify,
    onIntakeUserReply: handleIntakeUserReply,
    onIntakeComplete: handleIntakeComplete,
  });

  // --- Send handlers --------------------------------------------------------

  const canSend = useMemo(() => {
    if (
      status === "creating" ||
      status === "replying" ||
      status === "resuming" ||
      status === "complete" ||
      status === "awaiting_profile"
    ) {
      return false;
    }
    if (runId === null) {
      // Initial create: require non-empty query.
      return composerText.trim().length > 0;
    }
    // Reply: require at least non-empty text OR a selected option.
    return composerText.trim().length > 0 || composerOptions.length > 0;
  }, [status, runId, composerText, composerOptions]);

  async function handleSend(): Promise<void> {
    if (!canSend) {
      return;
    }
    if (runId === null) {
      await startIntake(composerText.trim());
      return;
    }
    await sendReply(composerText.trim(), composerOptions);
  }

  async function startIntake(userQuery: string): Promise<void> {
    setStatus("creating");
    const userMessage: ChatMessage = {
      id: newMessageId(),
      kind: "user",
      text: userQuery,
      selectedOptions: [],
    };
    setMessages((prev) => [...prev, userMessage]);
    setComposerText("");
    try {
      const payload: IntakeCreateRequest = {
        user_query: userQuery,
        response_language: reportLanguage,
      };
      if (fromRunId !== null) {
        payload.from_run_id = fromRunId;
        payload.seed_competitor_ids = inheritedSeedCompetitors;
      }
      const idempotencyKey =
        createIdempotencyKeyRef.current !== null && createIdempotencyQueryRef.current === userQuery
          ? createIdempotencyKeyRef.current
          : buildIdempotencyKey();
      createIdempotencyKeyRef.current = idempotencyKey;
      createIdempotencyQueryRef.current = userQuery;
      const variables: CreateRunIntakeVariables = {
        payload,
        idempotencyKey,
      };
      const response = await createIntake.mutateAsync(variables);
      createIdempotencyKeyRef.current = null;
      createIdempotencyQueryRef.current = null;
      setRunId(response.run_id);
      sessionStorage.setItem(INTAKE_SESSION_KEY, response.run_id);
      const responseDraft = normalizeIntakeDraft(response.intake_draft, userQuery);
      setDraft(responseDraft);
      setReportDepth(normalizeReportDepth(responseDraft.report_depth));
      setReportLanguage(normalizeReportLanguage(responseDraft.response_language));
      if (response.phase === "done") {
        sessionStorage.removeItem(INTAKE_SESSION_KEY);
        setStatus("complete");
        pushToast({
          title: "已开始分析",
          description: "任务已在后台完成，正在跳转结果页。",
          variant: "success",
        });
        const targetRunId = response.run_id;
        window.setTimeout(() => navigate(`/app/runs/${targetRunId}`), POST_COMPLETE_DELAY_MS);
        return;
      }
      if (response.phase === "planning") {
        setStatus("awaiting_profile");
        return;
      }
      const clarify = response.first_clarify_request;
      if (clarify !== undefined && clarify !== null) {
        setMessages((prev) => [
          ...prev,
          clarifyMessageFromPayload({
            question: clarify.question,
            field_targets: clarify.field_targets,
            suggested_options: clarify.suggested_options,
            suggested_answer: clarify.suggested_answer,
          }),
        ]);
        setStatus("awaiting_user");
        return;
      }
      // Async create contract: first clarify now arrives over SSE.
      setStatus("resuming");
    } catch (error) {
      const message = error instanceof Error ? error.message : "未知错误";
      setMessages((prev) => [
        ...prev,
        {
          id: newMessageId(),
          kind: "assistant.error",
          text: `创建任务失败：${message}`,
        },
      ]);
      setStatus("error");
      pushToast({
        title: "创建任务失败",
        description: message,
        variant: "danger",
      });
    }
  }

  async function sendReply(text: string, selectedOptions: string[]): Promise<void> {
    if (runId === null) {
      return;
    }
    if (text.length === 0 && selectedOptions.length === 0) {
      return;
    }
    setStatus("replying");
    const userMessage: ChatMessage = {
      id: newMessageId(),
      kind: "user",
      text,
      selectedOptions: [...selectedOptions],
    };
    setMessages((prev) => [...prev, userMessage]);
    setComposerText("");
    setComposerOptions([]);
    const isProfileSelectionStep =
      currentClarify !== null &&
      currentClarify.kind === "assistant.clarify" &&
      currentClarify.fieldTargets.includes("report_depth");
    try {
      const reply: IntakeUserReply = { text, selected_options: selectedOptions };
      await replyIntake.mutateAsync({ runId, reply });
      if (isProfileSelectionStep) {
        sessionStorage.removeItem(INTAKE_SESSION_KEY);
        setStatus("complete");
        pushToast({
          title: "档位已确认",
          description: "正在生成计划并跳转到计划确认页…",
          variant: "success",
        });
        const targetRunId = runId;
        window.setTimeout(() => navigate(`/app/runs/${targetRunId}/plan`), POST_COMPLETE_DELAY_MS);
        return;
      }
      setStatus("resuming");
    } catch (error) {
      const message = error instanceof Error ? error.message : "未知错误";
      setMessages((prev) => [
        ...prev,
        {
          id: newMessageId(),
          kind: "assistant.error",
          text: `回复失败：${message}`,
        },
      ]);
      setStatus("awaiting_user");
      pushToast({
        title: "回复失败",
        description: message,
        variant: "danger",
      });
    }
  }

  async function submitProfileSelection(): Promise<void> {
    if (runId === null || status !== "awaiting_profile") {
      return;
    }
    setStatus("replying");
    setMessages((prev) => [
      ...prev,
      {
        id: newMessageId(),
        kind: "user",
        text: `选择档位：${reportDepthLabel(reportDepth)}`,
        selectedOptions: [reportDepth],
      },
    ]);
    try {
      const reply: IntakeUserReply = {
        text: "",
        selected_options: [reportDepth],
      };
      await replyIntake.mutateAsync({ runId, reply });
      sessionStorage.removeItem(INTAKE_SESSION_KEY);
      setStatus("complete");
      pushToast({
        title: "档位已确认",
        description: "正在生成计划并跳转到计划确认页…",
        variant: "success",
      });
      const targetRunId = runId;
      window.setTimeout(() => navigate(`/app/runs/${targetRunId}/plan`), POST_COMPLETE_DELAY_MS);
    } catch (error) {
      const message = error instanceof Error ? error.message : "未知错误";
      setMessages((prev) => [
        ...prev,
        {
          id: newMessageId(),
          kind: "assistant.error",
          text: `确认档位失败：${message}`,
        },
      ]);
      setStatus("awaiting_profile");
      pushToast({
        title: "确认档位失败",
        description: message,
        variant: "danger",
      });
    }
  }

  function toggleOption(option: string): void {
    setComposerOptions((prev) => {
      if (prev.includes(option)) {
        return prev.filter((item) => item !== option);
      }
      return [...prev, option];
    });
  }

  function handleExamplePick(prompt: ExamplePrompt): void {
    if (selectedExampleId === prompt.id) {
      setSelectedExampleId(null);
      setComposerText("");
      return;
    }
    setSelectedExampleId(prompt.id);
    setComposerText(prompt.query);
  }

  function handleComposerChange(nextText: string): void {
    setComposerText(nextText);
    if (selectedExampleId === null) {
      return;
    }
    const selected = EXAMPLE_PROMPTS.find((prompt) => prompt.id === selectedExampleId);
    if (selected !== undefined && nextText !== selected.query) {
      setSelectedExampleId(null);
    }
  }

  function acceptGhostSuggestion(source: "keyboard" | "click" | "button"): void {
    if (activeGhostSuggestion === null) {
      return;
    }
    if (composerText.length > 0) {
      return;
    }
    const accepted = activeGhostSuggestion;
    setComposerText(accepted);
    track("intake.ghost.accepted", {
      run_id: runId,
      source,
      field_targets:
        currentClarify !== null && currentClarify.kind === "assistant.clarify"
          ? currentClarify.fieldTargets
          : [],
    });
    // Restore focus + park caret at end so the user can keep typing or send.
    // requestAnimationFrame waits for React to flush the controlled value;
    // otherwise setSelectionRange races with the value update on Chromium.
    requestAnimationFrame(() => {
      const el = composerTextareaRef.current;
      if (el === null) {
        return;
      }
      el.focus();
      el.setSelectionRange(accepted.length, accepted.length);
    });
  }

  function handleComposerKeyDown(event: KeyboardEvent<HTMLTextAreaElement>): void {
    const canAcceptGhost =
      activeGhostSuggestion !== null &&
      composerText.length === 0 &&
      status !== "creating" &&
      status !== "replying" &&
      status !== "resuming" &&
      status !== "complete";
    if (event.key === "Tab" && canAcceptGhost) {
      event.preventDefault();
      acceptGhostSuggestion("keyboard");
      return;
    }
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void handleSend();
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (status === "awaiting_profile") {
      await submitProfileSelection();
      return;
    }
    await handleSend();
  }

  const activeRowIds = useMemo<Set<string>>(() => {
    if (
      status !== "awaiting_user" ||
      currentClarify === null ||
      currentClarify.kind !== "assistant.clarify"
    ) {
      return new Set();
    }
    return activeRowIdsFromFieldTargets(currentClarify.fieldTargets);
  }, [currentClarify, status]);
  const checklistRows = useMemo(
    () => deriveChecklistRows(draft, activeRowIds),
    [draft, activeRowIds],
  );
  const checklistPhase = useMemo(
    () => deriveChecklistPhase(checklistRows, draft),
    [checklistRows, draft],
  );
  const checklistSatisfiedCount = useMemo(
    () => checklistRows.filter((row) => row.satisfied).length,
    [checklistRows],
  );
  const hasClarify = useMemo(
    () => messages.some((message) => message.kind === "assistant.clarify"),
    [messages],
  );
  const isBusy =
    status === "creating" || status === "replying" || status === "resuming";
  const composerDisabled =
    isBusy || status === "complete" || status === "awaiting_profile";
  const shouldShowGhostSuggestion =
    activeGhostSuggestion !== null &&
    composerText.length === 0 &&
    !composerDisabled;
  const composerPlaceholder =
    runId === null
      ? "描述你想做的竞品分析（角色、要解决的问题、可选竞品名单）"
      : status === "awaiting_profile"
        ? "请先选择分析档位后继续"
        : "回答澄清问题，或补充更多上下文…";

  useEffect(() => {
    if (runId === null) {
      return;
    }
    if (currentClarify === null || currentClarify.kind !== "assistant.clarify") {
      return;
    }
    if (currentClarify.suggestedOptions.length > 0 || currentClarify.suggestedAnswer === null) {
      return;
    }
    if (ghostShownMessageIdsRef.current.has(currentClarify.id)) {
      return;
    }
    ghostShownMessageIdsRef.current.add(currentClarify.id);
    track("intake.ghost.shown", {
      run_id: runId,
      field_targets: currentClarify.fieldTargets,
    });
  }, [currentClarify, runId]);

  return (
    // Why h-full + flex column: chat page must lock to viewport height so the
    // message list scrolls inside the Card. Without this, growing messages
    // would inflate the page and surface the outer main scrollbar, breaking
    // the fixed composer feel that mature chat products (ChatGPT/Claude/Cursor)
    // all rely on.
    <section className="flex h-full min-h-0 flex-col gap-5">
      <header className="shrink-0 space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-h1 text-foreground">新建竞品分析</h1>
          <div className="flex items-center gap-2">
            {runId !== null && status !== "complete" && (
              <CancelRunButton
                runId={runId}
                label="放弃此次分析"
                redirectTo="/app"
                size="sm"
              />
            )}
            <IntakeModeSwitcher active="chat" />
          </div>
        </div>
        <p className="text-caption text-foreground-muted">
          告诉 Agent 你想分析什么，我会用对话帮你确认身份、意图和竞品范围，再开始抓取证据。
          想跳过澄清直接填表单，可以切到「专家表单」。
        </p>
        {fromRunId !== null ? (
          <p className="text-xs text-primary">
            当前为聚焦模式：继承 run {fromRunId} 的上下文，默认会按 comparison 生成关键维度分析。
          </p>
        ) : null}
      </header>

      <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-3 lg:items-stretch">
        <div className="flex min-h-0 flex-col gap-3 lg:col-span-2">
          <Card className="flex h-0 min-h-[18rem] flex-1 flex-col lg:min-h-0">
            <CardHeader className="pb-2">
              <CardTitle className="inline-flex items-center gap-2 text-lg">
                <Sparkles className="h-4 w-4 text-primary" />
                Agent 对话
              </CardTitle>
            </CardHeader>
            <CardContent className="flex min-h-0 flex-1 flex-col gap-2 overflow-hidden pt-0">
              <div className="min-h-0 flex-1 space-y-3 overflow-y-auto pr-2">
                {messages.map((message) => (
                  <MessageBubble
                    key={message.id}
                    message={message}
                    onOptionToggle={toggleOption}
                    selectedOptions={composerOptions}
                    isCurrentClarify={
                      status === "awaiting_user" && currentClarify?.id === message.id
                    }
                  />
                ))}
                {isBusy && <ThinkingBubble status={status} hasClarify={hasClarify} />}
                <div ref={messagesEndRef} />
              </div>
            </CardContent>
          </Card>

          <form className="shrink-0" onSubmit={handleSubmit}>
            <Card className="border-primary/30">
              <CardContent className="space-y-2 p-4">
                <div className="relative rounded-md border border-white/[0.08] bg-white/[0.03] transition focus-within:border-primary/40 focus-within:ring-2 focus-within:ring-ring/40">
                  {shouldShowGhostSuggestion && (
                    <button
                      aria-label={`采纳 Agent 建议：${activeGhostSuggestion ?? ""}`}
                      className="absolute left-3 right-3 top-2 z-20 cursor-pointer rounded text-left text-caption text-foreground-subtle/55 transition hover:text-foreground-subtle/90"
                      onMouseDown={(event) => {
                        // mouseDown.preventDefault 阻止 textarea 失焦，焦点保留在输入框
                        event.preventDefault();
                        acceptGhostSuggestion("click");
                      }}
                      tabIndex={-1}
                      title="点击或按 Tab 采纳建议"
                      type="button"
                    >
                      {activeGhostSuggestion}
                    </button>
                  )}
                  <textarea
                    aria-label="向 Agent 输入"
                    autoComplete="off"
                    className="relative z-10 min-h-20 w-full resize-none bg-transparent px-3 py-2 text-caption text-foreground outline-none"
                    disabled={composerDisabled}
                    name="intake-user-reply"
                    onChange={(event) => handleComposerChange(event.target.value)}
                    onKeyDown={handleComposerKeyDown}
                    placeholder={shouldShowGhostSuggestion ? "" : composerPlaceholder}
                    ref={composerTextareaRef}
                    value={composerText}
                  />
                </div>
                {runId === null && (
                  <label className="flex items-center gap-2 text-xs text-foreground-muted">
                    <span className="shrink-0">报告语言</span>
                    <NativeSelect
                      className="h-8 w-40"
                      disabled={composerDisabled}
                      onChange={(event) => setReportLanguage(normalizeReportLanguage(event.target.value))}
                      value={reportLanguage}
                    >
                      <option value="zh">中文（默认）</option>
                      <option value="en">English</option>
                    </NativeSelect>
                  </label>
                )}
                {composerOptions.length > 0 && (
                  <div className="flex flex-wrap gap-1.5">
                    {composerOptions.map((option) => (
                      <Badge key={option} variant="secondary" className="text-xs">
                        已选：{option}
                      </Badge>
                    ))}
                  </div>
                )}
                {status === "awaiting_profile" && runId !== null && (
                  <div className="rounded-md border border-primary/30 bg-primary/[0.06] p-3">
                    <p className="text-xs font-medium text-foreground">
                      需求已确认，生成计划前请选择分析档位
                    </p>
                    <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
                      <ReportDepthSelector value={reportDepth} onChange={setReportDepth} />
                      <Button
                        className="shrink-0"
                        disabled={isBusy}
                        onClick={() => {
                          void submitProfileSelection();
                        }}
                        size="sm"
                        type="button"
                      >
                        继续生成计划
                      </Button>
                    </div>
                  </div>
                )}
                <div className="flex items-center gap-2">
                  <div className="flex min-w-0 flex-1 flex-wrap items-center gap-1.5">
                    {runId === null && (
                      <>
                        <span className="shrink-0 text-xs text-foreground-subtle">试试</span>
                        {EXAMPLE_PROMPTS.map((prompt) => {
                          const isSelected = selectedExampleId === prompt.id;
                          return (
                            <button
                              aria-pressed={isSelected}
                              className={cn(
                                "inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs transition",
                                isSelected
                                  ? "border-primary/50 bg-primary/15 text-foreground ring-1 ring-primary/30"
                                  : "border-white/[0.1] bg-white/[0.03] text-foreground-muted hover:border-primary/40 hover:text-foreground",
                                composerDisabled && "cursor-not-allowed opacity-50",
                              )}
                              disabled={composerDisabled}
                              key={prompt.id}
                              onClick={() => handleExamplePick(prompt)}
                              title={prompt.subtitle}
                              type="button"
                            >
                              {prompt.recommended === true && !isSelected && (
                                <Sparkles className="h-3 w-3 text-primary/70" aria-hidden />
                              )}
                              {prompt.title}
                            </button>
                          );
                        })}
                      </>
                    )}
                    {runId !== null && shouldShowGhostSuggestion && (
                      <span className="text-xs text-foreground-subtle">
                        Tab 或点击灰字采纳建议 · Enter 发送
                      </span>
                    )}
                  </div>
                  {shouldShowGhostSuggestion && (
                    <Button
                      className="shrink-0"
                      onClick={() => acceptGhostSuggestion("button")}
                      size="sm"
                      type="button"
                      variant="secondary"
                    >
                      <Sparkles className="h-3.5 w-3.5" aria-hidden />
                      采纳建议
                    </Button>
                  )}
                  <Button className="shrink-0" disabled={!canSend} size="sm" type="submit">
                    {isBusy ? (
                      <>
                        <Loader2 className="h-3.5 w-3.5 animate-spin" />
                        发送中
                      </>
                    ) : (
                      "发送"
                    )}
                  </Button>
                </div>
              </CardContent>
            </Card>
          </form>
        </div>

        <aside className="flex min-h-0 flex-col gap-3 pr-1 lg:h-full">
          <Card className="flex min-h-0 flex-1 flex-col">
            <CardHeader className="shrink-0 space-y-3 pb-3">
              <div className="flex items-center justify-between gap-2">
                <CardTitle className="text-base">需求清单</CardTitle>
                <span className="inline-flex items-center gap-1.5 text-xs text-foreground-muted">
                  <StatusDot phase={checklistPhase} />
                  <span>{checklistPhaseLabel(checklistPhase)}</span>
                  <span className="tabular-nums text-foreground-subtle">
                    {checklistSatisfiedCount}/{checklistRows.length}
                  </span>
                </span>
              </div>
              <ChecklistProgressBar
                phase={checklistPhase}
                satisfied={checklistSatisfiedCount}
                total={checklistRows.length}
              />
            </CardHeader>
            <CardContent className="min-h-0 flex-1 space-y-3 overflow-y-auto pt-0">
              {checklistRows.map((row) => (
                <ChecklistItem
                  key={row.id}
                  label={row.label}
                  hint={row.hint}
                  satisfied={row.satisfied}
                  active={row.active}
                />
              ))}
              {draft && (
                <div className="space-y-1 rounded-md border border-white/[0.06] bg-white/[0.02] p-3 text-xs text-foreground-muted">
                  {draft.domain_hint && (
                    <p>
                      <span className="text-foreground-subtle">领域：</span>
                      {draft.domain_hint}
                    </p>
                  )}
                  {draft.focus_dimensions.length > 0 && (
                    <p>
                      <span className="text-foreground-subtle">关注维度：</span>
                      {draft.focus_dimensions.join("、")}
                    </p>
                  )}
                  <p>
                    <span className="text-foreground-subtle">报告深度：</span>
                    {status === "awaiting_profile"
                      ? reportDepthLabel(reportDepth)
                      : reportDepthLabel(normalizeReportDepth(draft.report_depth))}
                  </p>
                  <p>
                    <span className="text-foreground-subtle">报告语言：</span>
                    {status === "awaiting_profile"
                      ? reportLanguageLabel(reportLanguage)
                      : reportLanguageLabel(normalizeReportLanguage(draft.response_language))}
                  </p>
                </div>
              )}
            </CardContent>
          </Card>

          <Card className="shrink-0">
            <CardHeader className="pb-3">
              <CardTitle className="text-base">为什么要对话？</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3 pt-0 text-xs text-foreground-muted">
              <p className="leading-relaxed text-foreground-muted">
                Agent 会先与你对齐角色、意图和竞品范围，再开始抓取证据——这样可以避免「报告生成了但方向错」的浪费。
              </p>
              <div className="space-y-2.5 border-t border-white/[0.06] pt-3">
                <p className="text-[11px] uppercase tracking-wider text-foreground-subtle">
                  完整链路
                </p>
                {ANALYSIS_PIPELINE_STEPS.map((step, index) => (
                  <PipelineStep
                    key={step.title}
                    n={index + 1}
                    text={step.text}
                    title={step.title}
                  />
                ))}
              </div>
              <p className="border-t border-white/[0.06] pt-3 text-foreground-subtle">
                如果你已经清楚自己要什么，可以切到右上「专家表单」一次性填完。
              </p>
            </CardContent>
          </Card>
        </aside>
      </div>
    </section>
  );
}

// --- Subcomponents --------------------------------------------------------

interface MessageBubbleProps {
  message: ChatMessage;
  selectedOptions: string[];
  onOptionToggle: (option: string) => void;
  isCurrentClarify: boolean;
}

function MessageBubble({
  message,
  selectedOptions,
  onOptionToggle,
  isCurrentClarify,
}: MessageBubbleProps): JSX.Element {
  if (message.kind === "user") {
    return (
      <div className="flex items-start gap-2 justify-end">
        <div className="max-w-[80%] rounded-lg rounded-tr-sm bg-primary/15 px-3 py-2 text-sm text-foreground">
          <p className="whitespace-pre-wrap break-words">{message.text}</p>
          {message.selectedOptions.length > 0 && (
            <p className="mt-1 text-xs text-foreground-muted">
              已选项：{message.selectedOptions.join("、")}
            </p>
          )}
        </div>
        <div className="mt-0.5 inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/15 text-primary">
          <UserIcon className="h-3.5 w-3.5" />
        </div>
      </div>
    );
  }

  const Icon = message.kind === "assistant.error" ? Sparkles : Bot;
  const bubbleColor =
    message.kind === "assistant.error"
      ? "bg-danger/10 text-danger"
      : message.kind === "assistant.complete"
        ? "bg-success/10 text-success"
        : "bg-white/[0.04] text-foreground";

  return (
    <div className="flex items-start gap-2">
      <div className="mt-0.5 inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-white/[0.05] text-primary">
        <Icon className="h-3.5 w-3.5" />
      </div>
      <div className={cn("max-w-[80%] rounded-lg rounded-tl-sm px-3 py-2 text-sm", bubbleColor)}>
        {message.kind === "assistant.clarify" ? (
          <>
            <p className="whitespace-pre-wrap break-words">{message.question}</p>
            {(() => {
              const labels = Array.from(
                new Set(
                  message.fieldTargets
                    .map((target) => FIELD_TARGET_LABELS[target])
                    .filter((label): label is string => Boolean(label)),
                ),
              );
              return labels.length > 0 ? (
                <p className="mt-1 text-xs text-foreground-muted">
                  关于：{labels.join("、")}
                </p>
              ) : null;
            })()}
            {message.suggestedOptions.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {message.suggestedOptions.map((option) => {
                  const isSelected =
                    isCurrentClarify && selectedOptions.includes(option);
                  return (
                    <button
                      aria-pressed={isSelected}
                      className={cn(
                        "rounded-full border px-3 py-1 text-xs transition",
                        isSelected
                          ? "border-primary/60 bg-primary/15 text-foreground"
                          : "border-white/[0.1] bg-white/[0.03] text-foreground-muted hover:border-primary/40 hover:text-foreground",
                        !isCurrentClarify && "opacity-60",
                      )}
                      disabled={!isCurrentClarify}
                      key={option}
                      onClick={() => onOptionToggle(option)}
                      type="button"
                    >
                      {option}
                    </button>
                  );
                })}
              </div>
            )}
          </>
        ) : (
          <p className="whitespace-pre-wrap break-words">{message.text}</p>
        )}
      </div>
    </div>
  );
}

function intakeThinkingLabel(status: ChatStatus, hasClarify: boolean): string {
  if (status === "creating") {
    return "正在启动会话…";
  }
  if (status === "replying") {
    return "发送中…";
  }
  return hasClarify ? "正在核对需求信息…" : "正在理解你的分析需求…";
}

function ThinkingBubble({
  status,
  hasClarify,
}: {
  status: ChatStatus;
  hasClarify: boolean;
}): JSX.Element {
  const label = intakeThinkingLabel(status, hasClarify);
  return (
    <div className="flex items-start gap-2">
      <div className="mt-0.5 inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-white/[0.05] text-primary">
        <Bot className="h-3.5 w-3.5" />
      </div>
      <div className="inline-flex items-center gap-2 rounded-lg rounded-tl-sm bg-white/[0.04] px-3 py-2 text-sm text-foreground-muted">
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
        {label}
      </div>
    </div>
  );
}

interface ChecklistItemProps {
  label: string;
  hint: string;
  satisfied: boolean;
  active: boolean;
}

// Tiny status dot used in the checklist header. Pairs with the textual status
// label ("待开始 / 进行中 / 已就绪") so the user gets both a glyph and a word —
// matches the Linear / Vercel "ambient state indicator" pattern.
function StatusDot({ phase }: { phase: ChecklistPhase }): JSX.Element {
  const dotColor =
    phase === "complete"
      ? "bg-success"
      : phase === "in_progress"
        ? "bg-warning"
        : "bg-foreground-subtle";
  return (
    <span
      aria-hidden
      className={cn("inline-block h-1.5 w-1.5 shrink-0 rounded-full", dotColor)}
    />
  );
}

// 3-segment progress bar (one segment per required field). Keeps the visual
// weight low (h-1) and shows discrete progress instead of a continuous bar —
// users immediately see "1 of 3 done" without reading the number.
function ChecklistProgressBar({
  phase,
  satisfied,
  total,
}: {
  phase: ChecklistPhase;
  satisfied: number;
  total: number;
}): JSX.Element {
  const fillColor =
    phase === "complete"
      ? "bg-success"
      : phase === "in_progress"
        ? "bg-warning"
        : "bg-foreground-subtle/40";
  return (
    <div className="flex items-center gap-1" aria-hidden>
      {Array.from({ length: Math.max(total, 1) }).map((_, index) => (
        <div
          key={index}
          className={cn(
            "h-1 flex-1 rounded-full transition-colors duration-200",
            index < satisfied ? fillColor : "bg-white/[0.06]",
          )}
        />
      ))}
    </div>
  );
}

// Static product pipeline step — not tied to example selection or Agent output.
function PipelineStep({
  n,
  title,
  text,
}: {
  n: number;
  title: string;
  text: string;
}): JSX.Element {
  return (
    <div className="flex items-start gap-2.5">
      <span className="mt-0.5 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-white/[0.06] text-[11px] font-medium tabular-nums text-foreground-subtle">
        {n}
      </span>
      <div className="min-w-0 space-y-0.5">
        <p className="text-sm font-medium text-foreground-muted">{title}</p>
        <p className="text-xs leading-relaxed text-foreground-subtle">{text}</p>
      </div>
    </div>
  );
}

function ChecklistItem({ label, hint, satisfied, active }: ChecklistItemProps): JSX.Element {
  const StateIcon = satisfied ? CheckCircle2 : active ? CircleDot : Circle;
  const iconColor = satisfied
    ? "text-success"
    : active
      ? "text-warning"
      : "text-foreground-subtle";
  const labelColor = satisfied
    ? "text-foreground"
    : active
      ? "text-foreground"
      : "text-foreground-muted";
  return (
    <div
      className={cn(
        "flex items-start gap-2 rounded-md px-2 py-1.5 transition-colors",
        active && !satisfied ? "bg-warning/[0.06]" : "bg-transparent",
      )}
    >
      <StateIcon className={cn("mt-0.5 h-4 w-4 shrink-0", iconColor)} aria-hidden />
      <div className="min-w-0">
        <p className={cn("text-sm font-medium", labelColor)}>{label}</p>
        <p className="text-xs text-foreground-muted">{hint}</p>
      </div>
    </div>
  );
}
