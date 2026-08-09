import dagre from "@dagrejs/dagre";
import { MarkerType, type Edge, type Node } from "@xyflow/react";

import type {
  RunTraceResponse,
  StepTraceResponse,
  SupervisorDecisionTraceResponse,
} from "@/api/types";
import { buildEvidenceLinkFromPayload, buildEvidenceLinkFromToolArgs } from "@/lib/evidenceLinks";

const AGENT_NODE_WIDTH = 180;
const AGENT_NODE_HEIGHT = 72;
const DECISION_NODE_WIDTH = 152;
const DECISION_NODE_HEIGHT = 92;
const START_END_NODE_WIDTH = 112;
const START_END_NODE_HEIGHT = 44;

const SUPERVISOR_AGENT_NAME = "supervisor";

export type DagNodeStatus = "success" | "running" | "failed" | "skipped" | "pending";
export type DecisionAction = "approved" | "rejected" | "regenerate" | "fallback";

export interface AgentNodeData extends Record<string, unknown> {
  kind: "agent";
  label: string;
  agentName: string;
  status: DagNodeStatus;
  rawStatus: string;
  executionCount: number;
  latestStepId: string;
  latestCreatedAt: string;
  latestPayload: Record<string, unknown>;
  evidenceLink: string | null;
}

export interface DecisionNodeData extends Record<string, unknown> {
  kind: "decision";
  label: string;
  decisionId: string;
  chosenTool: string;
  reasoningSummary: string;
  action: DecisionAction;
  iteration: number;
  outcome: string | null;
  createdAt: string;
  toolArgs: Record<string, unknown>;
  evidenceLink: string | null;
}

export interface StartEndNodeData extends Record<string, unknown> {
  kind: "startEnd";
  stage: "start" | "end";
  label: string;
}

export type DagNodeData = AgentNodeData | DecisionNodeData | StartEndNodeData;

export type AgentDagNode = Node<AgentNodeData, "agent">;
export type DecisionDagNode = Node<DecisionNodeData, "decision">;
export type StartEndDagNode = Node<StartEndNodeData, "startEnd">;

export type DagNode = AgentDagNode | DecisionDagNode | StartEndDagNode;
export type DagEdge = Edge;

export interface DagBuildResult {
  nodes: DagNode[];
  edges: DagEdge[];
}

interface AgentSummary {
  agentName: string;
  steps: StepTraceResponse[];
}

interface AddEdgeOptions {
  sourceStatus?: DagNodeStatus;
  dashed?: boolean;
  label?: string;
}

function toTimestamp(value: string): number {
  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp) ? 0 : timestamp;
}

function formatAgentLabel(agentName: string): string {
  const overrides: Record<string, string> = {
    qa: "QA",
    llm: "LLM",
    skill_curator: "Skill Curator",
  };
  if (overrides[agentName] !== undefined) {
    return overrides[agentName];
  }
  return agentName
    .split("_")
    .map((chunk) => chunk.charAt(0).toUpperCase() + chunk.slice(1))
    .join(" ");
}

function normalizeStepStatus(rawStatus: string): DagNodeStatus {
  const value = rawStatus.trim().toLowerCase();
  if (value.includes("complete") || value.includes("success") || value.includes("succeeded")) {
    return "success";
  }
  if (value.includes("running") || value.includes("progress")) {
    return "running";
  }
  if (value.includes("reject") || value.includes("fail") || value.includes("error")) {
    return "failed";
  }
  if (value.includes("skip")) {
    return "skipped";
  }
  return "pending";
}

function normalizeDecisionAction(decision: SupervisorDecisionTraceResponse): DecisionAction {
  const outcome = decision.outcome?.toLowerCase() ?? "";
  const triggeredBy = decision.triggered_by?.toLowerCase() ?? "";
  const reasoning = decision.reasoning_summary.toLowerCase();
  const argsText = JSON.stringify(decision.tool_args).toLowerCase();

  if (outcome.includes("rejected")) {
    return "rejected";
  }
  if (reasoning.includes("fallback") || argsText.includes("fallback")) {
    return "fallback";
  }
  if (triggeredBy.includes("qa") || outcome.includes("failed") || reasoning.includes("retry")) {
    return "regenerate";
  }
  return "approved";
}

function decisionActionToStatus(action: DecisionAction): DagNodeStatus {
  switch (action) {
    case "approved":
      return "success";
    case "rejected":
      return "failed";
    case "regenerate":
      return "running";
    case "fallback":
      return "pending";
    default:
      return "pending";
  }
}

function statusToStrokeColor(status: DagNodeStatus): string {
  switch (status) {
    case "success":
      return "#10b981";
    case "running":
      return "#3b82f6";
    case "failed":
      return "#ef4444";
    case "skipped":
      return "#64748b";
    case "pending":
      return "#94a3b8";
    default:
      return "#94a3b8";
  }
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
  if (normalized === "finalize") {
    return "skill_curator";
  }
  return null;
}

function buildAgentSummaries(sortedSteps: StepTraceResponse[]): AgentSummary[] {
  const order: string[] = [];
  const byAgent = new Map<string, StepTraceResponse[]>();
  for (const step of sortedSteps) {
    if (!byAgent.has(step.agent_name)) {
      byAgent.set(step.agent_name, []);
      order.push(step.agent_name);
    }
    byAgent.get(step.agent_name)?.push(step);
  }
  return order.map((agentName) => ({
    agentName,
    steps: byAgent.get(agentName) ?? [],
  }));
}

function findPreviousAgentName(steps: StepTraceResponse[], decisionTimestamp: number): string | null {
  let fallback: string | null = null;
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    const step = steps[index];
    if (toTimestamp(step.created_at) > decisionTimestamp) {
      continue;
    }
    if (fallback === null) {
      fallback = step.agent_name;
    }
    if (step.agent_name !== SUPERVISOR_AGENT_NAME) {
      return step.agent_name;
    }
  }
  return fallback;
}

function findNextAgentName(steps: StepTraceResponse[], decisionTimestamp: number): string | null {
  let fallback: string | null = null;
  for (const step of steps) {
    if (toTimestamp(step.created_at) < decisionTimestamp) {
      continue;
    }
    if (fallback === null) {
      fallback = step.agent_name;
    }
    if (step.agent_name !== SUPERVISOR_AGENT_NAME) {
      return step.agent_name;
    }
  }
  return fallback;
}

function resolveNodeSize(node: DagNode): { width: number; height: number } {
  if (node.type === "agent") {
    return { width: AGENT_NODE_WIDTH, height: AGENT_NODE_HEIGHT };
  }
  if (node.type === "decision") {
    return { width: DECISION_NODE_WIDTH, height: DECISION_NODE_HEIGHT };
  }
  return { width: START_END_NODE_WIDTH, height: START_END_NODE_HEIGHT };
}

function applyDagreLayout(nodes: DagNode[], edges: DagEdge[]): DagNode[] {
  const graph = new dagre.graphlib.Graph().setDefaultEdgeLabel(() => ({}));
  graph.setGraph({
    rankdir: "LR",
    nodesep: 40,
    ranksep: 80,
    marginx: 24,
    marginy: 24,
  });

  for (const node of nodes) {
    const { width, height } = resolveNodeSize(node);
    graph.setNode(node.id, { width, height });
  }

  for (const edge of edges) {
    graph.setEdge(edge.source, edge.target);
  }

  dagre.layout(graph);

  return nodes.map((node) => {
    const { width, height } = resolveNodeSize(node);
    const positionedNode = graph.node(node.id) as { x: number; y: number };
    return {
      ...node,
      position: {
        x: positionedNode.x - width / 2,
        y: positionedNode.y - height / 2,
      },
    };
  });
}

/**
 * Build DAG elements for the RunTrace page.
 *
 * ID convention:
 * - agent node: agent:<agent_name>
 * - decision node: decision:<decision_id>
 * - start/end node: start / end
 */
export function buildRunTraceDag(trace: RunTraceResponse): DagBuildResult {
  // Keep the visualization usable when an older Agent/BFF response is
  // missing one of the trace collections. The API hook normalizes this too,
  // but the builder is intentionally defensive for cached/manual payloads.
  const rawTrace = trace as Partial<RunTraceResponse>;
  const steps = Array.isArray(rawTrace.steps) ? rawTrace.steps : [];
  const decisions = Array.isArray(rawTrace.supervisor_decisions)
    ? rawTrace.supervisor_decisions
    : [];
  const sortedSteps = [...steps].sort(
    (left, right) => toTimestamp(left.created_at) - toTimestamp(right.created_at),
  );
  const sortedDecisions = [...decisions].sort(
    (left, right) => toTimestamp(left.created_at) - toTimestamp(right.created_at),
  );

  const runId = trace.run?.run_id ?? "";
  const agentSummaries = buildAgentSummaries(sortedSteps);
  const nodes: DagNode[] = [];

  const startNode: StartEndDagNode = {
    id: "start",
    type: "startEnd",
    position: { x: 0, y: 0 },
    data: {
      kind: "startEnd",
      stage: "start",
      label: "Run Start",
    },
    style: {
      width: START_END_NODE_WIDTH,
      height: START_END_NODE_HEIGHT,
    },
  };
  const endNode: StartEndDagNode = {
    id: "end",
    type: "startEnd",
    position: { x: 0, y: 0 },
    data: {
      kind: "startEnd",
      stage: "end",
      label: "Run End",
    },
    style: {
      width: START_END_NODE_WIDTH,
      height: START_END_NODE_HEIGHT,
    },
  };

  nodes.push(startNode);

  for (const summary of agentSummaries) {
    const latestStep = summary.steps[summary.steps.length - 1];
    if (latestStep === undefined) {
      continue;
    }
    const agentNode: AgentDagNode = {
      id: `agent:${summary.agentName}`,
      type: "agent",
      position: { x: 0, y: 0 },
      data: {
        kind: "agent",
        label: formatAgentLabel(summary.agentName),
        agentName: summary.agentName,
        status: normalizeStepStatus(latestStep.status),
        rawStatus: latestStep.status,
        executionCount: summary.steps.length,
        latestStepId: latestStep.step_id,
        latestCreatedAt: latestStep.created_at,
        latestPayload: latestStep.payload,
        evidenceLink: buildEvidenceLinkFromPayload(runId, latestStep.payload),
      },
      style: {
        width: AGENT_NODE_WIDTH,
        height: AGENT_NODE_HEIGHT,
      },
    };
    nodes.push(agentNode);
  }

  for (const decision of sortedDecisions) {
    const action = normalizeDecisionAction(decision);
    const decisionNode: DecisionDagNode = {
      id: `decision:${decision.id}`,
      type: "decision",
      position: { x: 0, y: 0 },
      data: {
        kind: "decision",
        label: decision.chosen_tool,
        decisionId: decision.id,
        chosenTool: decision.chosen_tool,
        reasoningSummary: decision.reasoning_summary,
        action,
        iteration: decision.iteration,
        outcome: decision.outcome,
        createdAt: decision.created_at,
        toolArgs: decision.tool_args,
        evidenceLink: buildEvidenceLinkFromToolArgs(runId, decision.tool_args),
      },
      style: {
        width: DECISION_NODE_WIDTH,
        height: DECISION_NODE_HEIGHT,
      },
    };
    nodes.push(decisionNode);
  }

  nodes.push(endNode);

  const knownNodeIds = new Set(nodes.map((node) => node.id));
  const nodeStatusById = new Map<string, DagNodeStatus>();
  for (const node of nodes) {
    if (node.type === "agent") {
      nodeStatusById.set(node.id, node.data.status);
      continue;
    }
    if (node.type === "decision") {
      nodeStatusById.set(node.id, decisionActionToStatus(node.data.action));
    }
  }

  const edges: DagEdge[] = [];
  const seenEdges = new Set<string>();
  let edgeIndex = 0;
  const addEdge = (source: string, target: string, options: AddEdgeOptions = {}): void => {
    if (!knownNodeIds.has(source) || !knownNodeIds.has(target) || source === target) {
      return;
    }
    const dedupeKey = `${source}->${target}`;
    if (seenEdges.has(dedupeKey)) {
      return;
    }
    seenEdges.add(dedupeKey);
    const sourceStatus = options.sourceStatus ?? nodeStatusById.get(source) ?? "pending";
    const strokeColor = statusToStrokeColor(sourceStatus);
    edges.push({
      id: `edge:${edgeIndex.toString(10)}`,
      source,
      target,
      type: "smoothstep",
      label: options.label,
      animated: sourceStatus === "running",
      markerEnd: {
        type: MarkerType.ArrowClosed,
        color: strokeColor,
      },
      style: {
        stroke: strokeColor,
        strokeWidth: 2,
        strokeDasharray: options.dashed === true ? "6 4" : undefined,
      },
      labelStyle:
        options.label === undefined
          ? undefined
          : {
              fill: "#94a3b8",
              fontSize: 11,
            },
    });
    edgeIndex += 1;
  };

  // Supervisor owns every routing decision; when it has run we anchor all
  // decision diamonds to it so the graph reads as a hub (worker -> supervisor ->
  // decision -> worker) instead of two competing paths for one transition.
  const supervisorNodeId = knownNodeIds.has(`agent:${SUPERVISOR_AGENT_NAME}`)
    ? `agent:${SUPERVISOR_AGENT_NAME}`
    : null;

  const decisionRoutes = sortedDecisions.map((decision) => {
    const decisionTimestamp = toTimestamp(decision.created_at);
    const fallbackTargetAgentName = mapToolToAgent(decision.chosen_tool);
    const nextAgentName =
      findNextAgentName(sortedSteps, decisionTimestamp) ?? fallbackTargetAgentName;
    const previousAgentName = findPreviousAgentName(sortedSteps, decisionTimestamp);
    const sourceNodeId =
      supervisorNodeId ??
      (previousAgentName !== null && knownNodeIds.has(`agent:${previousAgentName}`)
        ? `agent:${previousAgentName}`
        : "start");
    const targetNodeId =
      nextAgentName !== null && knownNodeIds.has(`agent:${nextAgentName}`)
        ? `agent:${nextAgentName}`
        : "end";
    return {
      decision,
      decisionNodeId: `decision:${decision.id}`,
      sourceNodeId,
      targetNodeId,
      action: normalizeDecisionAction(decision),
    };
  });

  // Directed agent pairs already bridged by a decision diamond; the raw
  // step-order edge between them would duplicate that routing.
  const decisionCoveredPairs = new Set<string>();
  for (const route of decisionRoutes) {
    decisionCoveredPairs.add(`${route.sourceNodeId}->${route.targetNodeId}`);
  }

  const firstStep = sortedSteps[0];
  const lastStep = sortedSteps[sortedSteps.length - 1];

  if (firstStep !== undefined) {
    addEdge("start", `agent:${firstStep.agent_name}`);
    for (let index = 1; index < sortedSteps.length; index += 1) {
      const previous = sortedSteps[index - 1];
      const current = sortedSteps[index];
      const source = `agent:${previous.agent_name}`;
      const target = `agent:${current.agent_name}`;
      if (source === target || decisionCoveredPairs.has(`${source}->${target}`)) {
        continue;
      }
      addEdge(source, target);
    }
    if (lastStep !== undefined) {
      addEdge(`agent:${lastStep.agent_name}`, "end");
    }
  } else {
    addEdge("start", "end");
  }

  for (const route of decisionRoutes) {
    const isDashed =
      route.action === "regenerate" || route.action === "fallback" || route.action === "rejected";

    addEdge(route.sourceNodeId, route.decisionNodeId, {
      label: `iter ${route.decision.iteration.toString(10)}`,
    });
    addEdge(route.decisionNodeId, route.targetNodeId, {
      sourceStatus: decisionActionToStatus(route.action),
      dashed: isDashed,
      label: route.decision.chosen_tool,
    });
  }

  return {
    nodes: applyDagreLayout(nodes, edges),
    edges,
  };
}
