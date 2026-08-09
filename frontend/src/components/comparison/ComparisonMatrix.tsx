import { FileText } from "lucide-react";

import type { DimensionComparisonResponse } from "@/api/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface ComparisonMatrixProps {
  comparisons: DimensionComparisonResponse[];
  onEvidenceClick: (evidenceIds: string[]) => void;
}

const STANCE_VARIANT: Record<string, "success" | "warning" | "danger" | "secondary"> = {
  leader: "success",
  competitive: "warning",
  laggard: "danger",
  unknown: "secondary",
};

const STANCE_LABEL: Record<string, string> = {
  leader: "相对领先",
  competitive: "可竞争",
  laggard: "落后",
  unknown: "未知",
};

const DIMENSION_LABEL: Record<string, string> = {
  feature: "功能",
  pricing: "定价",
  user_feedback: "用户反馈",
  positioning: "产品定位",
  tech_stack: "技术栈",
  go_to_market: "市场策略",
  market_differences: "市场差异",
  enterprise_capabilities: "企业能力",
  strategic_recommendations: "战略建议",
  hiring_signals: "招聘动态",
  recent_news: "近期动态",
  product_changelog: "产品更新",
};

function formatDimension(value: string): string {
  return DIMENSION_LABEL[value] ?? value.replace(/_/g, " ");
}

export function ComparisonMatrix({
  comparisons,
  onEvidenceClick,
}: ComparisonMatrixProps): JSX.Element | null {
  if (comparisons.length === 0) {
    return null;
  }

  const competitors = Array.from(
    new Set(comparisons.flatMap((comparison) => comparison.cells.map((cell) => cell.competitor_id))),
  ).sort();
  const allCells = comparisons.flatMap((comparison) => comparison.cells);
  const qualifiedCells = allCells.filter((cell) => cell.stance !== "unknown");
  const leaderCellCount = qualifiedCells.filter((cell) => cell.stance === "leader").length;
  const leaderDominant =
    qualifiedCells.length > 0 && leaderCellCount / qualifiedCells.length >= 0.8;

  if (competitors.length === 0) {
    return null;
  }

  return (
    <section className="overflow-hidden rounded-lg border border-white/[0.06] bg-surface">
      <div className="border-b border-white/[0.04] px-4 py-3">
        <h2 className="text-caption font-semibold text-foreground">跨竞品对比矩阵</h2>
        <p className="mt-0.5 text-micro text-foreground-subtle">
          {comparisons.length} 个维度 · {competitors.length} 个竞品
        </p>
        <p className="mt-1 text-micro text-foreground-subtle">
          标签含义：相对领先=该维度证据更完整或优势更明确；可竞争=差距不显著；落后=存在明显短板；未知=证据不足。
        </p>
        {leaderDominant ? (
          <p className="mt-1 text-micro text-amber-300">
            当前矩阵「相对领先」占比偏高，区分度不足；建议结合完整报告与证据细节判读。
          </p>
        ) : null}
      </div>
      <div className="relative">
        <div className="pointer-events-none absolute inset-y-0 right-0 z-10 w-10 bg-gradient-to-l from-surface to-transparent" />
        <div className="scrollbar-prominent-x overflow-x-auto pb-3">
          <table className="min-w-full table-fixed border-collapse">
          <thead>
            <tr className="border-b border-white/[0.04] text-left text-micro uppercase text-foreground-subtle">
              <th className="w-36 px-4 py-3 font-medium">维度</th>
              {competitors.map((competitor) => (
                <th key={competitor} className="min-w-56 px-4 py-3 font-medium">
                  {competitor}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {comparisons.map((comparison) => {
              const cellsByCompetitor = new Map(
                comparison.cells.map((cell) => [cell.competitor_id, cell]),
              );
              return (
                <tr key={comparison.dimension} className="border-b border-white/[0.04] last:border-b-0">
                  <th className="align-top px-4 py-4 text-left text-caption font-medium capitalize text-foreground">
                    {formatDimension(comparison.dimension)}
                  </th>
                  {competitors.map((competitor) => {
                    const cell = cellsByCompetitor.get(competitor);
                    if (cell === undefined) {
                      return (
                        <td key={competitor} className="px-4 py-4 align-top">
                          <span className="text-micro text-foreground-subtle">-</span>
                        </td>
                      );
                    }
                    const variant = STANCE_VARIANT[cell.stance] ?? "secondary";
                    const label = STANCE_LABEL[cell.stance] ?? cell.stance;
                    return (
                      <td key={cell.cell_id} className="px-4 py-4 align-top">
                        <div className="space-y-2">
                          <Badge variant={variant}>{label}</Badge>
                          <p className="text-caption leading-6 text-foreground-muted">
                            {cell.summary}
                          </p>
                          {cell.evidence_ids.length > 0 ? (
                            <Button
                              className={cn("h-7 gap-1.5 px-2 text-micro")}
                              onClick={() => onEvidenceClick(cell.evidence_ids)}
                              size="sm"
                              variant="ghost"
                            >
                              <FileText className="h-3.5 w-3.5" />
                              {cell.evidence_ids.length} 条证据
                            </Button>
                          ) : null}
                        </div>
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
        </div>
      </div>
    </section>
  );
}
