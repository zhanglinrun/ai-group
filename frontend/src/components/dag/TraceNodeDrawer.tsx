import { useMemo } from "react";
import { Link } from "react-router-dom";

import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import type { DagNodeData } from "@/lib/dagBuilder";
import { formatDateTime } from "@/lib/format";

export interface TraceNodeDrawerProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  nodeData: DagNodeData | null;
}

function renderJson(value: Record<string, unknown>): string {
  return JSON.stringify(value, null, 2);
}

export function TraceNodeDrawer({
  open,
  onOpenChange,
  nodeData,
}: TraceNodeDrawerProps): JSX.Element {
  const payloadText = useMemo(() => {
    if (nodeData?.kind !== "agent") {
      return null;
    }
    return renderJson(nodeData.latestPayload);
  }, [nodeData]);

  const toolArgsText = useMemo(() => {
    if (nodeData?.kind !== "decision") {
      return null;
    }
    return renderJson(nodeData.toolArgs);
  }, [nodeData]);

  return (
    <Sheet onOpenChange={onOpenChange} open={open}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-xl">
        <SheetHeader>
          <SheetTitle>
            {nodeData?.kind === "agent"
              ? `Agent · ${nodeData.label}`
              : nodeData?.kind === "decision"
                ? `Decision · #${nodeData.iteration.toString(10)}`
                : "节点详情"}
          </SheetTitle>
          <SheetDescription>
            {nodeData === null
              ? "未选中节点"
              : nodeData.kind === "startEnd"
                ? nodeData.label
                : nodeData.kind === "agent"
                  ? `${nodeData.agentName} · ${nodeData.rawStatus}`
                  : `${nodeData.chosenTool} · action=${nodeData.action}`}
          </SheetDescription>
        </SheetHeader>

        {nodeData?.kind === "agent" ? (
          <div className="mt-4 space-y-4">
            <div className="rounded-md border border-border bg-card p-3 text-sm">
              <p>latest_step: {nodeData.latestStepId}</p>
              <p className="text-muted-foreground">
                created_at: {formatDateTime(nodeData.latestCreatedAt)}
              </p>
              <p className="text-muted-foreground">execution_count: {nodeData.executionCount}</p>
            </div>

            {nodeData.evidenceLink ? (
              <Button asChild size="sm" variant="outline">
                <Link onClick={() => onOpenChange(false)} to={nodeData.evidenceLink}>
                  查看相关证据
                </Link>
              </Button>
            ) : null}

            <div className="space-y-2">
              <p className="text-xs text-muted-foreground">latest payload</p>
              <pre className="max-h-[50vh] overflow-auto rounded-md border border-border bg-black/30 p-3 text-xs leading-5">
                {payloadText}
              </pre>
            </div>
          </div>
        ) : null}

        {nodeData?.kind === "decision" ? (
          <div className="mt-4 space-y-4">
            <div className="rounded-md border border-border bg-card p-3 text-sm">
              <p>decision_id: {nodeData.decisionId}</p>
              <p>chosen_tool: {nodeData.chosenTool}</p>
              <p>action: {nodeData.action}</p>
              <p>outcome: {nodeData.outcome ?? "-"}</p>
              <p className="text-muted-foreground">
                created_at: {formatDateTime(nodeData.createdAt)}
              </p>
            </div>

            <div className="space-y-2">
              <p className="text-xs text-muted-foreground">reasoning_summary</p>
              <p className="whitespace-pre-wrap rounded-md border border-border bg-card p-3 text-sm leading-6">
                {nodeData.reasoningSummary}
              </p>
            </div>

            {nodeData.evidenceLink ? (
              <Button asChild size="sm" variant="outline">
                <Link onClick={() => onOpenChange(false)} to={nodeData.evidenceLink}>
                  查看相关证据
                </Link>
              </Button>
            ) : null}

            <div className="space-y-2">
              <p className="text-xs text-muted-foreground">tool_args</p>
              <pre className="max-h-[42vh] overflow-auto rounded-md border border-border bg-black/30 p-3 text-xs leading-5">
                {toolArgsText}
              </pre>
            </div>
          </div>
        ) : null}

        {nodeData?.kind === "startEnd" ? (
          <div className="mt-4 rounded-md border border-border bg-card p-3 text-sm text-muted-foreground">
            {nodeData.stage === "start"
              ? "这是 run 的开始节点。"
              : "这是 run 的结束节点。"}
          </div>
        ) : null}
      </SheetContent>
    </Sheet>
  );
}
