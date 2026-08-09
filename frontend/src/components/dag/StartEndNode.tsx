import { Handle, Position, type NodeProps } from "@xyflow/react";

import type { StartEndDagNode } from "@/lib/dagBuilder";
import { cn } from "@/lib/utils";

export function StartEndNode({ data, selected }: NodeProps<StartEndDagNode>): JSX.Element {
  const isStart = data.stage === "start";
  return (
    <div
      className={cn(
        "relative flex h-full w-full items-center justify-center rounded-full border px-3 py-2 text-xs font-semibold uppercase tracking-wider shadow-sm",
        isStart
          ? "border-primary/70 bg-primary/15 text-primary-foreground"
          : "border-slate-400/60 bg-slate-700/30 text-slate-100",
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
      {data.label}
    </div>
  );
}
