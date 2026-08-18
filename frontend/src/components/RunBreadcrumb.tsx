import { ChevronRight } from "lucide-react";
import { Link } from "react-router-dom";

import { formatRunTitle } from "@/lib/format";
import { cn } from "@/lib/utils";

interface RunLike {
  run_id: string;
  user_query: string;
  title?: string | null;
}

interface RunBreadcrumbProps {
  /** Run detail; null while loading. Falls back to "..." for the title slot. */
  run: RunLike | null | undefined;
  /**
   * Optional leaf segment for sub-pages (live / plan / trace / evidence).
   * When provided, the run title becomes a clickable middle segment that
   * routes back to RunView; the leaf is rendered as plain text.
   */
  current?: string;
  className?: string;
}

/**
 * Anchors run-detail pages to the "我的调研" sidebar item so users always
 * know where they are. Used at the top of RunView / LiveRun / PlanConfirm /
 * RunTrace / RunEvidence headers.
 */
export function RunBreadcrumb({ run, current, className }: RunBreadcrumbProps): JSX.Element {
  const title = run ? formatRunTitle(run, { max: 40 }) : "…";
  const detailHref = run ? `/app/runs/${run.run_id}` : "/app";
  return (
    <nav
      aria-label="breadcrumb"
      className={cn(
        "flex flex-wrap items-center gap-1.5 text-caption text-foreground-muted",
        className,
      )}
    >
      <Link className="transition-colors hover:text-foreground" to="/app">
        我的调研
      </Link>
      <ChevronRight className="h-3.5 w-3.5 shrink-0 text-foreground-subtle" />
      {current ? (
        <>
          <Link
            className="max-w-[280px] truncate transition-colors hover:text-foreground"
            title={run?.user_query}
            to={detailHref}
          >
            {title}
          </Link>
          <ChevronRight className="h-3.5 w-3.5 shrink-0 text-foreground-subtle" />
          <span className="text-foreground">{current}</span>
        </>
      ) : (
        <span
          className="max-w-[400px] truncate text-foreground"
          title={run?.user_query}
        >
          {title}
        </span>
      )}
    </nav>
  );
}
