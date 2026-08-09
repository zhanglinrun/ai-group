import type { RunPhase, RunStatus } from "@/api/types";

interface RouteableRun {
  run_id: string;
  status?: RunStatus | null;
  phase?: RunPhase | null;
}

export function runPhaseRoute(run: RouteableRun): string {
  if (run.status !== "running") {
    return `/app/runs/${run.run_id}`;
  }

  switch (run.phase) {
    case "planning":
      return `/app/runs/${run.run_id}/plan`;
    case "executing":
      return `/app/runs/${run.run_id}/live`;
    case "done":
    case "intake":
    default:
      return `/app/runs/${run.run_id}`;
  }
}
