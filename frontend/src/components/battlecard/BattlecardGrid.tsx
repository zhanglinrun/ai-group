import { useState } from "react";

import type { ConclusionItemResponse } from "@/api/types";
import { EvidenceDrawer } from "@/components/EvidenceDrawer";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

export interface BattlecardGridProps {
  runId: string;
  conclusions: ConclusionItemResponse[];
}

const CONFIDENCE_VARIANT: Record<string, "success" | "warning" | "danger" | "secondary"> = {
  high: "success",
  medium: "warning",
  low: "danger",
};

const SECTION_ORDER = ["功能", "定价", "用户反馈", "SWOT", "Risk"];

function getSectionOrder(section: string): number {
  const idx = SECTION_ORDER.findIndex((s) => section.toLowerCase().includes(s.toLowerCase()));
  return idx >= 0 ? idx : SECTION_ORDER.length;
}

export function BattlecardGrid({ runId, conclusions }: BattlecardGridProps): JSX.Element {
  const [drawerState, setDrawerState] = useState<{ open: boolean; evidenceIds: string[] }>({
    open: false,
    evidenceIds: [],
  });

  const competitorMap = new Map<string, ConclusionItemResponse[]>();
  for (const item of conclusions) {
    for (const cid of item.competitor_ids) {
      const existing = competitorMap.get(cid) ?? [];
      existing.push(item);
      competitorMap.set(cid, existing);
    }
  }

  const competitors = Array.from(competitorMap.keys()).sort();

  if (competitors.length === 0) {
    return (
      <div className="rounded-lg border border-white/[0.06] bg-surface p-8 text-center text-caption text-foreground-muted">
        暂无结构化结论数据
      </div>
    );
  }

  return (
    <>
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        {competitors.map((competitorId) => {
          const items = competitorMap.get(competitorId) ?? [];
          const sections = new Map<string, ConclusionItemResponse[]>();
          for (const item of items) {
            const existing = sections.get(item.section) ?? [];
            existing.push(item);
            sections.set(item.section, existing);
          }
          const sortedSections = Array.from(sections.entries()).sort(
            ([a], [b]) => getSectionOrder(a) - getSectionOrder(b),
          );

          return (
            <div
              key={competitorId}
              className="flex flex-col rounded-lg border border-white/[0.06] bg-surface"
            >
              <div className="border-b border-white/[0.04] px-4 py-3">
                <h3 className="text-caption font-semibold text-foreground">{competitorId}</h3>
                <p className="text-micro text-foreground-subtle">{items.length} 条结论</p>
              </div>
              <div className="flex-1 space-y-3 p-4">
                {sortedSections.map(([section, sectionItems]) => (
                  <div key={section}>
                    <p className="mb-1.5 text-micro font-medium uppercase tracking-wider text-foreground-subtle">
                      {section}
                    </p>
                    <div className="space-y-2">
                      {sectionItems.map((item) => (
                        <ClaimRow
                          key={item.conclusion_id}
                          item={item}
                          onEvidenceClick={(ids) => setDrawerState({ open: true, evidenceIds: ids })}
                        />
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </div>

      <EvidenceDrawer
        open={drawerState.open}
        onOpenChange={(open) => setDrawerState((s) => ({ ...s, open }))}
        runId={runId}
        evidenceIds={drawerState.evidenceIds}
      />
    </>
  );
}

interface ClaimRowProps {
  item: ConclusionItemResponse;
  onEvidenceClick: (ids: string[]) => void;
}

function ClaimRow({ item, onEvidenceClick }: ClaimRowProps): JSX.Element {
  const variant = CONFIDENCE_VARIANT[item.confidence] ?? "secondary";
  return (
    <div className="group rounded-md bg-white/[0.02] p-2.5 ring-1 ring-inset ring-white/[0.04] transition-colors hover:ring-white/[0.08]">
      <p className="text-micro leading-relaxed text-foreground-muted">{item.claim}</p>
      <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
        <Badge variant={variant} className="text-[10px]">
          {item.confidence}
        </Badge>
        {item.risk_flags.map((flag) => (
          <Badge key={flag} variant="danger" className="text-[10px]">
            {flag}
          </Badge>
        ))}
        {item.evidence_ids.length > 0 && (
          <button
            type="button"
            onClick={() => onEvidenceClick(item.evidence_ids)}
            className={cn(
              "text-[10px] text-primary underline-offset-2 hover:underline",
              "focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring rounded",
            )}
          >
            {item.evidence_ids.length} 条证据
          </button>
        )}
      </div>
    </div>
  );
}
