import { Handle, Position, type NodeProps } from "@xyflow/react";

import type { AgentDagNode, DagNodeStatus } from "@/lib/dagBuilder";
import { cn } from "@/lib/utils";

const STATUS_LABELS: Record<DagNodeStatus, string> = {
  success: "success",
  running: "running",
  failed: "failed",
  skipped: "skipped",
  pending: "pending",
};

const STATUS_STYLES: Record<DagNodeStatus, string> = {
  success: "border-emerald-400/60 bg-emerald-500/15 text-emerald-100",
  running: "border-blue-400/60 bg-blue-500/15 text-blue-100",
  failed: "border-red-400/60 bg-red-500/15 text-red-100",
  skipped: "border-slate-500/60 bg-slate-600/20 text-slate-200",
  pending: "border-border bg-card text-foreground",
};

export function AgentNode({ data, selected }: NodeProps<AgentDagNode>): JSX.Element {
  return (
    <div
      className={cn(
        "relative flex h-full w-full flex-col justify-between rounded-lg border px-3 py-2 text-left shadow-sm",
        STATUS_STYLES[data.status],
        selected && "ring-2 ring-primary/60 ring-offset-1 ring-offset-background",
      )}
    >
      <Handle
        className="!h-2.5 !w-2.5 !border-0 !bg-muted-foreground"
        position={Position.Left}
        type="target"
      />
      <Handle
        className="!h-2.5 !w-2.5 !border-0 !bg-muted-foreground"
        position={Position.Right}
        type="source"
      />

      <div className="flex items-center justify-between gap-2">
        <p className="truncate text-sm font-semibold">{data.label}</p>
        <span className="rounded-full border border-current/30 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide">
          {STATUS_LABELS[data.status]}
        </span>
      </div>

      <div className="flex items-center justify-between gap-2 text-[11px] text-current/85">
        <span className="truncate">step: {data.latestStepId}</span>
        <span className="rounded-md border border-current/30 px-1.5 py-0.5 font-semibold">
          x{data.executionCount}
        </span>
      </div>
    </div>
  );
}
