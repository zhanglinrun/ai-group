import { Handle, Position, type NodeProps } from "@xyflow/react";

import type { DecisionAction, DecisionDagNode } from "@/lib/dagBuilder";
import { cn } from "@/lib/utils";

const ACTION_STYLES: Record<DecisionAction, string> = {
  approved: "border-emerald-400/70 bg-emerald-500/15 text-emerald-100",
  rejected: "border-red-400/70 bg-red-500/15 text-red-100",
  regenerate: "border-amber-400/70 bg-amber-500/15 text-amber-100",
  fallback: "border-violet-400/70 bg-violet-500/15 text-violet-100",
};

const ACTION_LABELS: Record<DecisionAction, string> = {
  approved: "approved",
  rejected: "rejected",
  regenerate: "regen",
  fallback: "fallback",
};

export function DecisionNode({ data, selected }: NodeProps<DecisionDagNode>): JSX.Element {
  return (
    <div className="relative flex h-full w-full items-center justify-center">
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

      <div
        className={cn(
          "flex h-16 w-16 -rotate-45 items-center justify-center rounded-md border text-center shadow-sm",
          ACTION_STYLES[data.action],
          selected && "ring-2 ring-primary/60 ring-offset-1 ring-offset-background",
        )}
      >
        <div className="rotate-45 px-1">
          <p className="line-clamp-1 text-[11px] font-semibold">{data.chosenTool}</p>
          <p className="text-[10px] uppercase tracking-wide text-current/85">
            {ACTION_LABELS[data.action]}
          </p>
        </div>
      </div>
    </div>
  );
}
