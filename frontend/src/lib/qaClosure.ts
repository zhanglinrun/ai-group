import type {
  RunTraceResponse,
  StepTraceResponse,
  SupervisorDecisionTraceResponse,
} from "@/api/types";

export interface QaMetricDelta {
  label: string;
  before: number | null;
  after: number | null;
}

export interface QaClosureRound {
  round: number;
  qaStep: StepTraceResponse;
  rejectTo: string | null;
  failedRuleIds: string[];
  failedRuleCountDelta: QaMetricDelta;
  semanticFindings: string[];
  qaReasons: string[];
  decision: SupervisorDecisionTraceResponse | null;
  redoStep: StepTraceResponse | null;
  deltas: QaMetricDelta[];
}

export interface QaClosureSummary {
  qaStepCount: number;
  rejectedStepCount: number;
  rounds: QaClosureRound[];
}

function toTimestamp(value: string): number {
  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp) ? 0 : timestamp;
}

function asString(value: unknown): string | null {
  return typeof value === "string" && value ? value : null;
}

function asStringList(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((item): item is string => typeof item === "string" && item.length > 0);
}

function getNestedRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function getRejectionReason(step: StepTraceResponse): Record<string, unknown> {
  return step.rejection_reason ?? {};
}

function extractSemanticFindings(step: StepTraceResponse): string[] {
  const reason = getRejectionReason(step);
  const raw = reason.semantic_findings;
  if (!Array.isArray(raw)) {
    return [];
  }
  return raw
    .map((item) => {
      if (typeof item === "string") {
        return item;
      }
      const record = getNestedRecord(item);
      if (record === null) {
        return null;
      }
      return asString(record.message) ?? asString(record.finding) ?? JSON.stringify(record);
    })
    .filter((item): item is string => item !== null && item.length > 0);
}

function extractRejectTo(step: StepTraceResponse): string | null {
  return (
    asString(step.payload.qa_reject_to) ??
    asString(step.payload.reject_to) ??
    asString(getRejectionReason(step).reject_to)
  );
}

function extractFailedRuleIds(step: StepTraceResponse): string[] {
  return asStringList(step.payload.failed_rule_ids).concat(
    asStringList(getRejectionReason(step).failed_rule_ids),
  );
}

function isRejectedQaStep(step: StepTraceResponse): boolean {
  if (step.agent_name !== "qa") {
    return false;
  }
  const outcome = asString(step.payload.qa_outcome)?.toLowerCase();
  return (
    step.rejection_reason !== null ||
    step.status.toLowerCase().includes("reject") ||
    outcome === "rejected" ||
    outcome === "force_degraded"
  );
}

function mapToolToAgent(chosenTool: string): string | null {
  const normalized = chosenTool.trim().toLowerCase();
  if (normalized === "conductresearch" || normalized === "conductresearchbatch") {
    return "researcher";
  }
  if (normalized === "analyze") {
    return "analyst";
  }
  if (normalized === "write") {
    return "writer";
  }
  return null;
}

function rejectTargetToAgent(rejectTo: string | null): string | null {
  if (rejectTo === "researcher" || rejectTo === "analyst" || rejectTo === "writer") {
    return rejectTo;
  }
  return null;
}

function numberFromPayload(step: StepTraceResponse | null, key: string): number | null {
  if (step === null) {
    return null;
  }
  const value = step.payload[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function failedRuleCountFromQaStep(step: StepTraceResponse | null): number | null {
  if (step === null) {
    return null;
  }
  const direct = numberFromPayload(step, "failed_rule_count");
  if (direct !== null) {
    return direct;
  }
  const reason = getRejectionReason(step);
  const value = reason.failed_rule_count;
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function evidenceCountFromStep(step: StepTraceResponse | null): number | null {
  if (step === null) {
    return null;
  }
  const direct = numberFromPayload(step, "evidence_count");
  if (direct !== null) {
    return direct;
  }
  const evidenceIds = step.payload.evidence_ids;
  return Array.isArray(evidenceIds) ? evidenceIds.length : null;
}

function buildDeltas(
  agentName: string | null,
  beforeStep: StepTraceResponse | null,
  afterStep: StepTraceResponse | null,
): QaMetricDelta[] {
  if (agentName === "writer") {
    return [
      {
        label: "章节数",
        before: numberFromPayload(beforeStep, "section_count"),
        after: numberFromPayload(afterStep, "section_count"),
      },
      {
        label: "证据引用数",
        before: numberFromPayload(beforeStep, "evidence_ref_count"),
        after: numberFromPayload(afterStep, "evidence_ref_count"),
      },
    ];
  }
  if (agentName === "analyst") {
    return [
      {
        label: "insight 数",
        before: numberFromPayload(beforeStep, "insight_count"),
        after: numberFromPayload(afterStep, "insight_count"),
      },
      {
        label: "落库结论数",
        before: numberFromPayload(beforeStep, "conclusions_persisted_count"),
        after: numberFromPayload(afterStep, "conclusions_persisted_count"),
      },
    ];
  }
  if (agentName === "researcher") {
    return [
      {
        label: "证据数",
        before: evidenceCountFromStep(beforeStep),
        after: evidenceCountFromStep(afterStep),
      },
    ];
  }
  return [];
}

function findPriorStep(
  steps: StepTraceResponse[],
  agentName: string | null,
  beforeTimestamp: number,
  targetStepId: string | null,
): StepTraceResponse | null {
  if (targetStepId !== null) {
    const target = steps.find((step) => step.step_id === targetStepId);
    if (target !== undefined) {
      return target;
    }
  }
  if (agentName === null) {
    return null;
  }
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    const step = steps[index];
    if (step.agent_name === agentName && toTimestamp(step.created_at) < beforeTimestamp) {
      return step;
    }
  }
  return null;
}

function findQaDecision(
  decisions: SupervisorDecisionTraceResponse[],
  qaTimestamp: number,
): SupervisorDecisionTraceResponse | null {
  return (
    decisions.find(
      (decision) =>
        decision.triggered_by === "qa_rejection" &&
        toTimestamp(decision.created_at) >= qaTimestamp,
    ) ?? null
  );
}

function findRedoStep(
  steps: StepTraceResponse[],
  decision: SupervisorDecisionTraceResponse | null,
  fallbackAgentName: string | null,
): StepTraceResponse | null {
  const agentName = decision !== null ? mapToolToAgent(decision.chosen_tool) ?? fallbackAgentName : fallbackAgentName;
  if (agentName === null) {
    return null;
  }
  const afterTimestamp = decision !== null ? toTimestamp(decision.created_at) : 0;
  return (
    steps.find(
      (step) => step.agent_name === agentName && toTimestamp(step.created_at) >= afterTimestamp,
    ) ?? null
  );
}

export function buildQaClosure(trace: RunTraceResponse): QaClosureSummary {
  const steps = [...trace.steps].sort((left, right) => toTimestamp(left.created_at) - toTimestamp(right.created_at));
  const decisions = [...trace.supervisor_decisions].sort(
    (left, right) => toTimestamp(left.created_at) - toTimestamp(right.created_at),
  );
  const qaSteps = steps.filter((step) => step.agent_name === "qa");
  const rejectedQaSteps = qaSteps.filter(isRejectedQaStep);

  const rounds = rejectedQaSteps.map<QaClosureRound>((qaStep, index) => {
    const qaTimestamp = toTimestamp(qaStep.created_at);
    const rejectTo = extractRejectTo(qaStep);
    const fallbackAgentName = rejectTargetToAgent(rejectTo);
    const decision = findQaDecision(decisions, qaTimestamp);
    const redoStep = findRedoStep(steps, decision, fallbackAgentName);
    const redoAgentName = redoStep?.agent_name ?? fallbackAgentName;
    const targetStepId = redoAgentName === "writer" ? asString(qaStep.payload.target_step_id) : null;
    const beforeStep = findPriorStep(steps, redoAgentName, qaTimestamp, targetStepId);
    const qaReasons = asStringList(decision?.tool_args.qa_reasons);
    const nextQaStep = qaSteps.find((step) => toTimestamp(step.created_at) > qaTimestamp) ?? null;
    const nextFailedRuleCount = failedRuleCountFromQaStep(nextQaStep);

    return {
      round: index + 1,
      qaStep,
      rejectTo,
      failedRuleIds: Array.from(new Set(extractFailedRuleIds(qaStep))),
      failedRuleCountDelta: {
        label: "失败规则数",
        before: failedRuleCountFromQaStep(qaStep),
        after: nextQaStep === null ? null : nextFailedRuleCount ?? 0,
      },
      semanticFindings: extractSemanticFindings(qaStep),
      qaReasons,
      decision,
      redoStep,
      deltas: buildDeltas(redoAgentName, beforeStep, redoStep),
    };
  });

  return {
    qaStepCount: qaSteps.length,
    rejectedStepCount: rejectedQaSteps.length,
    rounds,
  };
}
