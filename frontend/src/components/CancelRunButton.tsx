import { CircleSlash, Loader2 } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { usePatchRun } from "@/api/hooks";
import { queryClient } from "@/api/queryClient";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { pushToast } from "@/components/ui/toaster";

interface CancelRunButtonProps {
  runId: string;
  // Show / hide the entry point based on the page's terminal-state logic. We
  // never render a stop button on completed/failed/cancelled runs.
  disabled?: boolean;
  // Compact button (used inside chat header / intake chrome) vs. full-width
  // (used in the Live Run header). Both share the same confirm dialog.
  size?: "sm" | "default";
  // Label override — "停止调研" by default. Intake mode uses "放弃此次调研"
  // because there's nothing to "stop" yet, the run is just paused on a
  // clarification turn.
  label?: string;
  // Where to navigate after a successful cancel. Default goes to the dashboard
  // so users don't sit on a frozen page; LiveRunPage leaves this empty so
  // its own terminal-state effect can run the redirect with the usual delay.
  redirectTo?: string | null;
}

export function CancelRunButton({
  runId,
  disabled = false,
  size = "sm",
  label = "停止调研",
  redirectTo = "/app",
}: CancelRunButtonProps): JSX.Element {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const mutation = usePatchRun();

  const handleConfirm = async (): Promise<void> => {
    if (mutation.isPending) {
      return;
    }
    try {
      await mutation.mutateAsync({
        runId,
        payload: { status: "cancelled", cancel_reason: "用户主动停止" },
      });
      // Invalidate so any page that hasn't yet received the SSE run.finish
      // picks up the new terminal status on its next render pass.
      await queryClient.invalidateQueries({ queryKey: ["run-detail", runId] });
      await queryClient.invalidateQueries({ queryKey: ["runs"] });
      pushToast({
        title: "已停止此次调研",
        description: "已采集的证据保留在历史中，可重新发起新的调研。",
        variant: "default",
      });
      setOpen(false);
      if (redirectTo !== null) {
        navigate(redirectTo);
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : "未知错误";
      pushToast({
        title: "停止失败",
        description: message,
        variant: "danger",
      });
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <Button
        type="button"
        variant="ghost"
        size={size}
        disabled={disabled}
        onClick={() => setOpen(true)}
        className="text-foreground-muted hover:text-danger hover:bg-danger/10"
      >
        <CircleSlash className="mr-1.5 h-3.5 w-3.5" />
        {label}
      </Button>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{label}？</DialogTitle>
          <DialogDescription>
            Agent 后台仍在运行中，停止后会取消未完成的调研任务。已经采集到的证据将保留在历史里。
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="ghost" size="sm" disabled={mutation.isPending}>
              再等等
            </Button>
          </DialogClose>
          <Button
            type="button"
            variant="danger"
            size="sm"
            onClick={handleConfirm}
            disabled={mutation.isPending}
          >
            {mutation.isPending ? (
              <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
            ) : (
              <CircleSlash className="mr-1.5 h-3.5 w-3.5" />
            )}
            确认停止
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
