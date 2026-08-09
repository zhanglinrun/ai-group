import { Link } from "react-router-dom";

import { useRunsList } from "@/api/hooks";
import { StatusBadge } from "@/components/StatusBadge";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDateTime, formatRunTitle } from "@/lib/format";

export function ExamplesPage(): JSX.Element {
  // The case library is the authenticated user's completed analyses. Public
  // sharing remains available through the dedicated /share/:runId route.
  const runsQuery = useRunsList({ status: "completed", limit: 12, offset: 0 });

  return (
    <section className="space-y-6 py-8">
      <header>
        <h1 className="text-h1 text-foreground">案例库</h1>
        <p className="mt-1 text-caption text-foreground-muted">浏览已完成的竞品分析报告。</p>
      </header>

      {runsQuery.isLoading && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-28 w-full" />)}
        </div>
      )}

      {runsQuery.isError && (
        <div className="rounded-lg border border-danger/30 bg-danger/5 p-4 text-caption text-danger">
          {runsQuery.error.message}
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {(runsQuery.data?.items ?? []).map((run) => (
          <Link
            key={run.run_id}
            to={`/share/${run.run_id}`}
            className="rounded-lg border border-white/[0.06] bg-surface p-5 transition-colors hover:border-white/[0.12]"
          >
            <p
              className="line-clamp-2 text-caption font-medium text-foreground"
              title={run.user_query}
            >
              {formatRunTitle(run)}
            </p>
            <div className="mt-3 flex items-center gap-2">
              <StatusBadge status={run.status} />
            </div>
            <p className="mt-2 text-micro text-foreground-subtle">
              {run.finished_at ? formatDateTime(run.finished_at) : "处理中"}
            </p>
          </Link>
        ))}
      </div>

      {!runsQuery.isLoading && !runsQuery.isError && (runsQuery.data?.items ?? []).length === 0 ? (
        <div className="rounded-lg border border-dashed border-border p-8 text-center text-caption text-foreground-muted">
          还没有已完成的竞品分析。<Link className="ml-1 text-primary hover:underline" to="/app/runs/new">新建一次分析</Link>
        </div>
      ) : null}
    </section>
  );
}
