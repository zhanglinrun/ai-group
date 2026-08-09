import { useMemo, useState } from "react";
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  type NodeTypes,
} from "@xyflow/react";

import type { RunTraceResponse } from "@/api/types";
import { AgentNode } from "@/components/dag/AgentNode";
import { DecisionNode } from "@/components/dag/DecisionNode";
import { StartEndNode } from "@/components/dag/StartEndNode";
import { TraceNodeDrawer } from "@/components/dag/TraceNodeDrawer";
import { buildRunTraceDag, type DagNodeData } from "@/lib/dagBuilder";

export interface RunTraceDagProps {
  trace: RunTraceResponse;
}

const NODE_TYPES: NodeTypes = {
  agent: AgentNode,
  decision: DecisionNode,
  startEnd: StartEndNode,
};

export function RunTraceDag({ trace }: RunTraceDagProps): JSX.Element {
  const [selectedNodeData, setSelectedNodeData] = useState<DagNodeData | null>(null);
  const dag = useMemo(() => buildRunTraceDag(trace), [trace]);
  const isEmpty =
    (Array.isArray(trace.steps) ? trace.steps.length : 0) === 0 &&
    (Array.isArray(trace.supervisor_decisions) ? trace.supervisor_decisions.length : 0) === 0;

  return (
    <ReactFlowProvider>
      <div className="space-y-3">
        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          <span className="rounded-md border border-emerald-400/40 bg-emerald-500/10 px-2 py-1">
            success
          </span>
          <span className="rounded-md border border-blue-400/40 bg-blue-500/10 px-2 py-1">
            running
          </span>
          <span className="rounded-md border border-red-400/40 bg-red-500/10 px-2 py-1">
            failed/rejected
          </span>
          <span className="rounded-md border border-amber-400/40 bg-amber-500/10 px-2 py-1">
            regenerate
          </span>
          <span className="rounded-md border border-violet-400/40 bg-violet-500/10 px-2 py-1">
            fallback
          </span>
        </div>

        <div className="h-[62vh] min-h-[420px] w-full overflow-hidden rounded-lg border border-border bg-black/30">
          <ReactFlow
            edges={dag.edges}
            fitView
            maxZoom={1.8}
            minZoom={0.35}
            nodeTypes={NODE_TYPES}
            nodes={dag.nodes}
            nodesConnectable={false}
            nodesDraggable={false}
            onNodeClick={(_, node) => setSelectedNodeData(node.data as unknown as DagNodeData)}
            onPaneClick={() => setSelectedNodeData(null)}
            proOptions={{ hideAttribution: true }}
          >
            <Background color="#475569" gap={20} size={1} />
            <MiniMap
              className="!border !border-border !bg-black/60"
              nodeColor="#64748b"
              pannable
              zoomable
            />
            <Controls position="top-right" showInteractive={false} />
          </ReactFlow>
        </div>

        {isEmpty ? (
          <p className="text-xs text-muted-foreground">
            当前 run 还没有可回放的 steps/decisions，DAG 会在执行过程中自动补全。
          </p>
        ) : null}
      </div>

      <TraceNodeDrawer
        nodeData={selectedNodeData}
        onOpenChange={(open) => {
          if (!open) {
            setSelectedNodeData(null);
          }
        }}
        open={selectedNodeData !== null}
      />
    </ReactFlowProvider>
  );
}
