import type { CompetitorDiffResponse, DiffChangeType } from "@/api/types";
import { Badge } from "@/components/ui/badge";

const CHANGE_TYPE_LABEL: Record<DiffChangeType, string> = {
  stance_changed: "阵营变化",
  new_dimension: "新增维度",
  lost_dimension: "丢失维度",
  summary_changed: "描述更新",
};

const CHANGE_TYPE_SYMBOL: Record<DiffChangeType, string> = {
  stance_changed: "↑↓",
  new_dimension: "+",
  lost_dimension: "−",
  summary_changed: "~",
};

function changeTypeBadgeVariant(
  type: DiffChangeType,
  significance: string,
): "danger" | "success" | "warning" | "secondary" | "outline" {
  if (type === "stance_changed") {
    if (significance === "high") return "danger";
    if (significance === "medium") return "warning";
    return "secondary";
  }
  if (type === "new_dimension") return "secondary";
  if (type === "lost_dimension") return "warning";
  return "secondary";
}

interface DiffRowProps {
  diff: CompetitorDiffResponse;
}

function DiffRow({ diff }: DiffRowProps): JSX.Element {
  const variant = changeTypeBadgeVariant(diff.change_type, diff.significance);
  return (
    <div className="flex flex-wrap items-start gap-x-2 gap-y-1 py-1.5">
      <span className="w-36 shrink-0 font-mono text-micro text-foreground-subtle">
        {diff.dimension.replace(/_/g, " ")}
      </span>
      <Badge variant={variant}>
        {CHANGE_TYPE_SYMBOL[diff.change_type]} {CHANGE_TYPE_LABEL[diff.change_type]}
      </Badge>
      {diff.change_type === "stance_changed" &&
      diff.old_value?.stance &&
      diff.new_value?.stance ? (
        <span className="text-micro text-foreground-muted">
          {diff.old_value.stance} → {diff.new_value.stance}
        </span>
      ) : null}
      <Badge
        variant={
          diff.significance === "high"
            ? "danger"
            : diff.significance === "medium"
              ? "warning"
              : "secondary"
        }
      >
        {diff.significance}
      </Badge>
    </div>
  );
}

interface CompetitorDiffCardProps {
  diffs: CompetitorDiffResponse[];
}

export function CompetitorDiffCard({ diffs }: CompetitorDiffCardProps): JSX.Element | null {
  if (diffs.length === 0) return null;

  const byCompetitor = new Map<string, CompetitorDiffResponse[]>();
  for (const diff of diffs) {
    const existing = byCompetitor.get(diff.competitor_id) ?? [];
    existing.push(diff);
    byCompetitor.set(diff.competitor_id, existing);
  }

  return (
    <section className="overflow-hidden rounded-lg border border-white/[0.06] bg-surface">
      <div className="border-b border-white/[0.04] px-4 py-3">
        <h2 className="text-caption font-semibold text-foreground">与上次分析相比</h2>
        <p className="mt-0.5 text-micro text-foreground-subtle">
          {diffs.length} 项维度发生变化 · {byCompetitor.size} 个竞品
        </p>
      </div>
      <div className="divide-y divide-white/[0.04]">
        {Array.from(byCompetitor.entries()).map(([competitorId, competitorDiffs]) => (
          <div key={competitorId} className="px-4 py-3">
            <p className="mb-1 text-micro font-semibold text-foreground">{competitorId}</p>
            {competitorDiffs.map((diff) => (
              <DiffRow key={diff.diff_id} diff={diff} />
            ))}
          </div>
        ))}
      </div>
    </section>
  );
}
