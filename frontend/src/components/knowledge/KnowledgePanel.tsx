import {
  Boxes,
  Building2,
  ChevronDown,
  ExternalLink,
  Layers,
  MessageSquareQuote,
  Tags,
  UserRound,
} from "lucide-react";
import { useMemo, useState } from "react";

import type {
  KnowledgeFeedback,
  KnowledgeFeature,
  KnowledgePersona,
  KnowledgePricing,
  RunKnowledgeResponse,
} from "@/api/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  candidateRoleLabel,
  competitorMetaFromKnowledge,
  isCoreCandidateRole,
  UNGROUPED_SEGMENT_LABEL,
  type CompetitorMeta,
} from "@/lib/competitorRoles";
import { cn } from "@/lib/utils";

export interface KnowledgePanelProps {
  knowledge: RunKnowledgeResponse | null;
  isLoading?: boolean;
  errorMessage?: string | null;
  onEvidenceClick: (evidenceIds: string[]) => void;
  compact?: boolean;
  onFocusCompetitor?: (competitorId: string) => void;
  onAddWatchlist?: (competitorId: string, sourceRole?: string) => void;
}

const MATURITY_LABELS: Record<NonNullable<KnowledgeFeature["maturity"]>, string> = {
  unknown: "未知",
  basic: "基础",
  advanced: "成熟",
  leading: "领先",
};

function unique(values: string[]): string[] {
  return Array.from(new Set(values.filter((value) => value.trim().length > 0)));
}

function normalizeLabel(value: string): string {
  return value.trim().toLocaleLowerCase();
}

function isProductContainerFeature(feature: KnowledgeFeature, competitorId: string): boolean {
  return normalizeLabel(feature.name) === normalizeLabel(competitorId);
}

function getCompetitorIds(knowledge: RunKnowledgeResponse | null): string[] {
  if (knowledge === null) {
    return [];
  }
  return unique([
    // Keep products visible even when a run has only a competitor profile and
    // no extracted feature/pricing/persona rows yet. Those rows are useful for
    // honest "暂无证据" states and should not disappear from the matrix.
    ...knowledge.competitors.map((item) => item.competitor_id),
    ...knowledge.features.map((item) => item.competitor_id),
    ...knowledge.pricings.map((item) => item.competitor_id),
    ...knowledge.personas.map((item) => item.competitor_id),
    ...knowledge.feedback.map((item) => item.competitor_id),
    ...Object.keys(knowledge.coverage),
  ]);
}

interface SegmentGroup {
  segment: string;
  competitors: string[];
}

/**
 * Group products by their sub-track (赛道). Products without a segment fall into
 * a trailing "未归类赛道" bucket. Within a group, core competitors come first.
 * Returns `{ groups, grouped }` where `grouped=false` signals legacy runs that
 * carry no segment metadata, so the caller can render a flat list instead.
 */
function groupCompetitorsBySegment(
  competitorIds: string[],
  meta: Record<string, CompetitorMeta>,
): { groups: SegmentGroup[]; grouped: boolean } {
  const bySegment = new Map<string, string[]>();
  let realSegmentCount = 0;
  for (const competitorId of competitorIds) {
    const segment = meta[competitorId]?.segment ?? null;
    const key = segment ?? UNGROUPED_SEGMENT_LABEL;
    const bucket = bySegment.get(key);
    if (bucket) {
      bucket.push(competitorId);
    } else {
      bySegment.set(key, [competitorId]);
      if (segment !== null) {
        realSegmentCount += 1;
      }
    }
  }
  const sortWithinSegment = (left: string, right: string): number => {
    const leftCore = isCoreCandidateRole(meta[left]?.role);
    const rightCore = isCoreCandidateRole(meta[right]?.role);
    if (leftCore !== rightCore) {
      return leftCore ? -1 : 1;
    }
    return left.localeCompare(right);
  };
  const groups: SegmentGroup[] = Array.from(bySegment.entries())
    .sort((a, b) => {
      const aUngrouped = a[0] === UNGROUPED_SEGMENT_LABEL;
      const bUngrouped = b[0] === UNGROUPED_SEGMENT_LABEL;
      if (aUngrouped !== bUngrouped) {
        return aUngrouped ? 1 : -1;
      }
      if (a[1].length !== b[1].length) {
        return b[1].length - a[1].length;
      }
      return a[0].localeCompare(b[0]);
    })
    .map(([segment, competitors]) => ({
      segment,
      competitors: [...competitors].sort(sortWithinSegment),
    }));
  return { groups, grouped: realSegmentCount > 0 };
}

function groupFeatures(features: KnowledgeFeature[]): Map<string, KnowledgeFeature[]> {
  const grouped = new Map<string, KnowledgeFeature[]>();
  for (const feature of features) {
    const items = grouped.get(feature.competitor_id) ?? [];
    items.push(feature);
    grouped.set(feature.competitor_id, items);
  }
  return grouped;
}

function groupPricings(pricings: KnowledgePricing[]): Map<string, KnowledgePricing[]> {
  const grouped = new Map<string, KnowledgePricing[]>();
  for (const pricing of pricings) {
    const items = grouped.get(pricing.competitor_id) ?? [];
    items.push(pricing);
    grouped.set(pricing.competitor_id, items);
  }
  return grouped;
}

function groupFeedback(items: KnowledgeFeedback[]): Map<string, KnowledgeFeedback[]> {
  const grouped = new Map<string, KnowledgeFeedback[]>();
  for (const item of items) {
    const rows = grouped.get(item.competitor_id) ?? [];
    rows.push(item);
    grouped.set(item.competitor_id, rows);
  }
  return grouped;
}

function groupPersonas(items: KnowledgePersona[]): Map<string, KnowledgePersona[]> {
  const grouped = new Map<string, KnowledgePersona[]>();
  for (const item of items) {
    const rows = grouped.get(item.competitor_id) ?? [];
    rows.push(item);
    grouped.set(item.competitor_id, rows);
  }
  return grouped;
}

function pricingEmptyText(knowledge: RunKnowledgeResponse | null): string {
  if (knowledge === null) {
    return "暂无定价模型条目：可能是价格未公开、证据不足，或抽取仍在处理中。";
  }
  const pricingStatuses = Object.values(knowledge.coverage)
    .map((item) => item.pricing)
    .filter((value): value is string => typeof value === "string");
  const pricingMissingReasons = Object.values(knowledge.missing_reasons).flat();
  const pricingNotApplicable =
    knowledge.analysis_archetype === "landscape" &&
    (pricingStatuses.includes("not_applicable_for_archetype") ||
      pricingMissingReasons.includes("pricing:not_applicable_for_archetype"));
  if (pricingNotApplicable) {
    return "本次为趋势/全景分析，未检索到可验证套餐或价格证据；定价模型不作为本场景的强制字段。";
  }
  return "暂无定价模型条目：可能是价格未公开、证据不足，或抽取仍在处理中。";
}


function schemaEmptyText(
  knowledge: RunKnowledgeResponse | null,
  options: {
    bucket: "feature" | "feedback" | "persona";
    defaultText: string;
    landscapeText: string;
  },
): string {
  const { bucket, defaultText, landscapeText } = options;
  if (knowledge === null || knowledge.analysis_archetype !== "landscape") {
    return defaultText;
  }
  const statuses = Object.values(knowledge.coverage)
    .map((item) => item[bucket])
    .filter((value): value is string => typeof value === "string");
  const missingReasons = Object.values(knowledge.missing_reasons).flat();
  const notApplicable =
    statuses.includes("not_applicable_for_archetype") ||
    missingReasons.includes(`${bucket}:not_applicable_for_archetype`);
  return notApplicable ? landscapeText : defaultText;
}

function EvidenceButton({
  evidenceIds,
  onEvidenceClick,
}: {
  evidenceIds: string[];
  onEvidenceClick: (evidenceIds: string[]) => void;
}): JSX.Element | null {
  if (evidenceIds.length === 0) {
    return null;
  }
  return (
    <Button
      className="h-7 px-2 text-micro"
      onClick={() => onEvidenceClick(evidenceIds)}
      size="sm"
      type="button"
      variant="outline"
    >
      <ExternalLink className="h-3.5 w-3.5" />
      {evidenceIds.length} 条证据
    </Button>
  );
}

function EmptyBlock({ text }: { text: string }): JSX.Element {
  return (
    <div className="rounded-md border border-border bg-muted/20 p-3 text-xs text-muted-foreground">
      {text}
    </div>
  );
}

function FeatureTree({
  competitorId,
  features,
  onEvidenceClick,
}: {
  competitorId: string;
  features: KnowledgeFeature[];
  onEvidenceClick: (evidenceIds: string[]) => void;
}): JSX.Element {
  const byParent = new Map<string, KnowledgeFeature[]>();
  const ids = new Set(features.map((feature) => feature.id));
  for (const feature of features) {
    const parentKey = feature.parent_id && ids.has(feature.parent_id) ? feature.parent_id : "root";
    const items = byParent.get(parentKey) ?? [];
    items.push(feature);
    byParent.set(parentKey, items);
  }
  const roots = byParent.get("root") ?? [];
  const displayRoots =
    roots.length === 1 && isProductContainerFeature(roots[0], competitorId)
      ? byParent.get(roots[0].id) ?? roots
      : roots;

  return (
    <section className="rounded-lg border border-white/[0.06] bg-background/40 p-3">
      <div className="space-y-2">
        {displayRoots.map((feature) => (
          <FeatureNode
            childrenByParent={byParent}
            feature={feature}
            key={feature.id}
            level={0}
            onEvidenceClick={onEvidenceClick}
          />
        ))}
      </div>
    </section>
  );
}

function FeatureNode({
  childrenByParent,
  feature,
  level,
  onEvidenceClick,
}: {
  childrenByParent: Map<string, KnowledgeFeature[]>;
  feature: KnowledgeFeature;
  level: number;
  onEvidenceClick: (evidenceIds: string[]) => void;
}): JSX.Element {
  const children = childrenByParent.get(feature.id) ?? [];
  const maturity = feature.maturity ? MATURITY_LABELS[feature.maturity] : null;
  return (
    <div className={cn("rounded-lg border border-white/[0.06] bg-surface/55 p-3", level > 0 && "ml-4")}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-sm font-medium text-foreground">{feature.name}</p>
            {maturity ? <Badge variant="secondary">{maturity}</Badge> : null}
          </div>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">{feature.description}</p>
        </div>
        <EvidenceButton evidenceIds={feature.evidence_ids} onEvidenceClick={onEvidenceClick} />
      </div>
      {children.length > 0 ? (
        <div className="mt-2 space-y-2">
          {children.map((child) => (
            <FeatureNode
              childrenByParent={childrenByParent}
              feature={child}
              key={child.id}
              level={Math.min(level + 1, 2)}
              onEvidenceClick={onEvidenceClick}
            />
          ))}
        </div>
      ) : null}
    </div>
  );
}

function PricingBlock({
  pricing,
  onEvidenceClick,
}: {
  pricing: KnowledgePricing;
  onEvidenceClick: (evidenceIds: string[]) => void;
}): JSX.Element {
  const tiers = Array.isArray(pricing.tiers) ? pricing.tiers : [];
  return (
    <article className="rounded-md border border-white/[0.06] bg-surface/70 p-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="text-sm font-medium text-foreground">{pricing.model || "unknown"}</p>
          <div className="mt-1 flex flex-wrap gap-1.5">
            <Badge variant={pricing.free_plan ? "success" : "secondary"}>
              {pricing.free_plan ? "有免费版" : "免费版未知/无"}
            </Badge>
            <Badge variant={pricing.enterprise_plan ? "success" : "secondary"}>
              {pricing.enterprise_plan ? "企业版" : "企业版未知/无"}
            </Badge>
          </div>
        </div>
        <EvidenceButton evidenceIds={pricing.evidence_ids} onEvidenceClick={onEvidenceClick} />
      </div>
      {tiers.length > 0 ? (
        <div className="mt-3 grid gap-2 md:grid-cols-2">
          {tiers.map((tier, index) => {
            const tierName = typeof tier.name === "string" && tier.name.trim() ? tier.name : "未命名套餐";
            const limits = Array.isArray(tier.limits) ? tier.limits : [];
            return (
            <div className="rounded border border-border bg-background/50 p-2" key={`${pricing.id}-${tierName}-${index.toString(10)}`}>
              <p className="text-xs font-medium text-foreground">{tierName}</p>
              <p className="mt-1 text-xs text-muted-foreground">
                {tier.price ?? "价格未知"} {tier.unit ?? ""}
              </p>
              {limits.length > 0 ? (
                <p className="mt-1 text-xs text-muted-foreground">{limits.join(" · ")}</p>
              ) : null}
            </div>
            );
          })}
        </div>
      ) : (
        <p className="mt-3 text-xs text-muted-foreground">未提取到分层价格。</p>
      )}
    </article>
  );
}

function PersonaBlock({
  persona,
  onEvidenceClick,
}: {
  persona: KnowledgePersona;
  onEvidenceClick: (evidenceIds: string[]) => void;
}): JSX.Element {
  return (
    <article className="rounded-md border border-white/[0.06] bg-surface/70 p-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="text-sm font-medium text-foreground">{persona.name}</p>
          <p className="mt-1 text-xs text-muted-foreground">{persona.role}</p>
        </div>
        <EvidenceButton evidenceIds={persona.evidence_ids} onEvidenceClick={onEvidenceClick} />
      </div>
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <ListBlock items={persona.pain_points} title="痛点" />
        <ListBlock items={persona.jobs_to_be_done} title="任务" />
      </div>
    </article>
  );
}

function FeedbackBlock({
  feedback,
  onEvidenceClick,
}: {
  feedback: KnowledgeFeedback;
  onEvidenceClick: (evidenceIds: string[]) => void;
}): JSX.Element {
  return (
    <article className="rounded-md border border-white/[0.06] bg-surface/70 p-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="text-sm font-medium text-foreground">{feedback.topic}</p>
          <p className="mt-1 text-xs text-muted-foreground">{feedback.sentiment}</p>
        </div>
        <EvidenceButton evidenceIds={feedback.evidence_ids} onEvidenceClick={onEvidenceClick} />
      </div>
      <p className="mt-2 text-xs leading-5 text-muted-foreground">{feedback.summary}</p>
    </article>
  );
}

function ListBlock({ items, title }: { items: string[]; title: string }): JSX.Element {
  return (
    <div>
      <p className="text-xs font-medium text-foreground">{title}</p>
      {items.length === 0 ? (
        <p className="mt-1 text-xs text-muted-foreground">暂无</p>
      ) : (
        <ul className="mt-1 space-y-1 text-xs leading-5 text-muted-foreground">
          {items.map((item, index) => (
            <li key={`${item}-${index.toString(10)}`}>- {item}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

interface CompetitorCardEmptyTexts {
  feature: string;
  pricing: string;
  persona: string;
  feedback: string;
}

function StatPill({ count, label }: { count: number; label: string }): JSX.Element {
  const hasData = count > 0;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-full border px-2 py-1 text-[11px]",
        hasData
          ? "border-primary/25 bg-primary/[0.08] text-foreground"
          : "border-white/[0.06] bg-surface/60 text-foreground-subtle",
      )}
    >
      <span className="font-semibold">{count}</span>
      {label}
    </span>
  );
}

function ProductMetaItem({ label, value }: { label: string; value: string | null }): JSX.Element {
  return (
    <div className="rounded-md border border-white/[0.06] bg-background/40 px-3 py-2">
      <p className="text-[11px] uppercase tracking-wide text-foreground-subtle">{label}</p>
      <p className="mt-1 truncate text-xs font-medium text-foreground">{value ?? "—"}</p>
    </div>
  );
}

function CompetitorCard({
  competitorId,
  meta,
  features,
  pricings,
  personas,
  feedback,
  isExpanded,
  isLandscape,
  onToggle,
  onEvidenceClick,
  onFocusCompetitor,
  onAddWatchlist,
  emptyTexts,
}: {
  competitorId: string;
  meta?: CompetitorMeta;
  features: KnowledgeFeature[];
  pricings: KnowledgePricing[];
  personas: KnowledgePersona[];
  feedback: KnowledgeFeedback[];
  isExpanded: boolean;
  isLandscape: boolean;
  onToggle: (competitorId: string) => void;
  onEvidenceClick: (evidenceIds: string[]) => void;
  onFocusCompetitor?: (competitorId: string) => void;
  onAddWatchlist?: (competitorId: string, sourceRole?: string) => void;
  emptyTexts: CompetitorCardEmptyTexts;
}): JSX.Element {
  const role = meta?.role;
  const vendor = meta?.vendor ?? null;
  const segment = meta?.segment ?? null;
  const introduction = meta?.introduction ?? null;
  const roleLabel = role ? candidateRoleLabel(role) : null;
  return (
    <article className="overflow-hidden rounded-xl border border-border bg-gradient-to-br from-surface/90 to-background/70 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-3 p-4">
        <button
          aria-expanded={isExpanded}
          className="flex min-w-0 flex-1 items-start gap-3 text-left"
          onClick={() => onToggle(competitorId)}
          type="button"
        >
          <ChevronDown
            className={cn(
              "mt-1 h-4 w-4 shrink-0 text-foreground-subtle transition-transform",
              isExpanded && "rotate-180",
            )}
          />
          <div className="min-w-0 flex-1 space-y-2">
            <div className="flex flex-wrap items-center gap-2">
              <h4 className="truncate text-base font-semibold text-foreground">{competitorId}</h4>
              {vendor ? (
                <span className="inline-flex items-center gap-1 text-xs text-foreground-subtle">
                  <Building2 className="h-3 w-3" />
                  {vendor}
                </span>
              ) : null}
              {roleLabel ? (
                <Badge variant={isLandscape ? "secondary" : "outline"} title="该产品在本赛道中的角色">
                  {roleLabel}
                </Badge>
              ) : null}
            </div>
            {introduction ? (
              <p className="line-clamp-2 text-xs leading-5 text-muted-foreground">{introduction}</p>
            ) : null}
            <div className="flex flex-wrap gap-1.5">
              <StatPill count={features.length} label="功能" />
              <StatPill count={pricings.length} label="定价" />
              <StatPill count={personas.length} label="画像" />
              <StatPill count={feedback.length} label="反馈" />
            </div>
          </div>
        </button>
        <div className="flex items-center gap-2">
          {onFocusCompetitor ? (
            <Button onClick={() => onFocusCompetitor(competitorId)} size="sm" type="button" variant="outline">
              聚焦分析
            </Button>
          ) : null}
          {onAddWatchlist ? (
            <Button
              onClick={() => onAddWatchlist(competitorId, role)}
              size="sm"
              type="button"
              variant="secondary"
            >
              加入追踪
            </Button>
          ) : null}
        </div>
      </div>
      {isExpanded ? (
        <div className="space-y-3 border-t border-border/60 p-4">
          <section className="rounded-lg border border-white/[0.08] bg-surface/55 p-3">
            <div className="grid gap-2 sm:grid-cols-3">
              <ProductMetaItem label="厂商 / 品牌" value={vendor} />
              <ProductMetaItem label="所属赛道" value={segment} />
              <ProductMetaItem label="竞争角色" value={roleLabel} />
            </div>
            {introduction ? (
              <p className="mt-3 text-xs leading-5 text-muted-foreground">{introduction}</p>
            ) : null}
          </section>
          <div className="grid gap-3 xl:grid-cols-2">
            <section className="space-y-2 rounded-lg border border-white/[0.08] bg-surface/55 p-3">
              <p className="inline-flex items-center gap-1 text-xs font-medium text-foreground">
                <Boxes className="h-3.5 w-3.5 text-primary" />
                功能树
              </p>
              {features.length > 0 ? (
                <FeatureTree competitorId={competitorId} features={features} onEvidenceClick={onEvidenceClick} />
              ) : (
                <EmptyBlock text={emptyTexts.feature} />
              )}
            </section>
            <section className="space-y-2 rounded-lg border border-white/[0.08] bg-surface/55 p-3">
              <p className="inline-flex items-center gap-1 text-xs font-medium text-foreground">
                <Tags className="h-3.5 w-3.5 text-primary" />
                定价模型
              </p>
              {pricings.length > 0 ? (
                <div className="space-y-2">
                  {pricings.map((pricing) => (
                    <PricingBlock key={pricing.id} onEvidenceClick={onEvidenceClick} pricing={pricing} />
                  ))}
                </div>
              ) : (
                <EmptyBlock text={emptyTexts.pricing} />
              )}
            </section>
            <section className="space-y-2 rounded-lg border border-white/[0.08] bg-surface/55 p-3">
              <p className="inline-flex items-center gap-1 text-xs font-medium text-foreground">
                <UserRound className="h-3.5 w-3.5 text-primary" />
                用户画像
              </p>
              {personas.length > 0 ? (
                <div className="space-y-2">
                  {personas.map((persona) => (
                    <PersonaBlock key={persona.id} onEvidenceClick={onEvidenceClick} persona={persona} />
                  ))}
                </div>
              ) : (
                <EmptyBlock text={emptyTexts.persona} />
              )}
            </section>
            <section className="space-y-2 rounded-lg border border-white/[0.08] bg-surface/55 p-3">
              <p className="inline-flex items-center gap-1 text-xs font-medium text-foreground">
                <MessageSquareQuote className="h-3.5 w-3.5 text-primary" />
                用户反馈
              </p>
              {feedback.length > 0 ? (
                <div className="space-y-2">
                  {feedback.map((item) => (
                    <FeedbackBlock feedback={item} key={item.id} onEvidenceClick={onEvidenceClick} />
                  ))}
                </div>
              ) : (
                <EmptyBlock text={emptyTexts.feedback} />
              )}
            </section>
          </div>
        </div>
      ) : null}
    </article>
  );
}

export function KnowledgePanel({
  knowledge,
  isLoading = false,
  errorMessage = null,
  onEvidenceClick,
  compact = false,
  onFocusCompetitor,
  onAddWatchlist,
}: KnowledgePanelProps): JSX.Element {
  const featureGroups = useMemo(() => groupFeatures(knowledge?.features ?? []), [knowledge?.features]);
  const pricingGroups = useMemo(() => groupPricings(knowledge?.pricings ?? []), [knowledge?.pricings]);
  const feedbackGroups = useMemo(() => groupFeedback(knowledge?.feedback ?? []), [knowledge?.feedback]);
  const personaGroups = useMemo(() => groupPersonas(knowledge?.personas ?? []), [knowledge?.personas]);
  const competitorMeta = useMemo(
    () => competitorMetaFromKnowledge(knowledge?.competitors),
    [knowledge?.competitors],
  );
  const competitorIds = useMemo(() => getCompetitorIds(knowledge), [knowledge]);
  const { groups: segmentGroups, grouped: isSegmented } = useMemo(
    () => groupCompetitorsBySegment(competitorIds, competitorMeta),
    [competitorIds, competitorMeta],
  );
  const [expandedCompetitors, setExpandedCompetitors] = useState<Set<string>>(new Set());
  function toggleCompetitor(competitorId: string): void {
    setExpandedCompetitors((prev) => {
      const next = new Set(prev);
      if (next.has(competitorId)) {
        next.delete(competitorId);
      } else {
        next.add(competitorId);
      }
      return next;
    });
  }
  const isLandscape = knowledge?.analysis_archetype === "landscape";
  const hasKnowledge =
    (knowledge?.competitors.length ?? 0) +
      (knowledge?.features.length ?? 0) +
      (knowledge?.pricings.length ?? 0) +
      (knowledge?.personas.length ?? 0) +
      (knowledge?.feedback.length ?? 0) >
    0;
  const emptyTexts: CompetitorCardEmptyTexts = {
    feature: schemaEmptyText(knowledge ?? null, {
      bucket: "feature",
      defaultText: "暂无功能树条目：可能是公开证据不足、产品未公开，或抽取仍在处理中。",
      landscapeText: "当前是趋势/全景模式，外围角色可允许不强制功能树。",
    }),
    pricing: pricingEmptyText(knowledge ?? null),
    persona: schemaEmptyText(knowledge ?? null, {
      bucket: "persona",
      defaultText: "暂无用户画像条目：可能是公开资料未覆盖目标用户，或抽取仍在处理中。",
      landscapeText: "当前是趋势/全景模式，外围角色可允许不强制用户画像。",
    }),
    feedback: schemaEmptyText(knowledge ?? null, {
      bucket: "feedback",
      defaultText: "暂无用户反馈条目：可能是公开评论证据不足，或抽取仍在处理中。",
      landscapeText: "当前是趋势/全景模式，外围角色可允许不强制用户反馈。",
    }),
  };

  if (isLoading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  if (errorMessage !== null) {
    return (
      <div className="rounded-lg border border-danger/30 bg-danger/5 p-4 text-sm text-danger">
        竞品知识读取失败：{errorMessage}
      </div>
    );
  }

  function renderCard(competitorId: string): JSX.Element {
    return (
      <CompetitorCard
        competitorId={competitorId}
        emptyTexts={emptyTexts}
        feedback={feedbackGroups.get(competitorId) ?? []}
        features={featureGroups.get(competitorId) ?? []}
        isExpanded={expandedCompetitors.has(competitorId)}
        isLandscape={isLandscape}
        key={`card-${competitorId}`}
        meta={competitorMeta[competitorId]}
        onAddWatchlist={onAddWatchlist}
        onEvidenceClick={onEvidenceClick}
        onFocusCompetitor={onFocusCompetitor}
        personas={personaGroups.get(competitorId) ?? []}
        pricings={pricingGroups.get(competitorId) ?? []}
        onToggle={toggleCompetitor}
      />
    );
  }

  return (
    <div className={cn("space-y-5", compact && "space-y-4")}>
      {!hasKnowledge ? (
        <EmptyBlock text="当前暂无可展示的竞品知识。通常是公开证据不足、目标信息未公开，或抽取仍在处理中。" />
      ) : (
        <>
          <section className="space-y-3">
            <h4 className="flex items-center gap-2 text-sm font-semibold text-foreground">
              <Layers className="h-4 w-4 text-primary" />
              {isSegmented ? "赛道 → 产品" : "竞品列表"}
            </h4>
            {isSegmented ? (
              <div className="space-y-5">
                {segmentGroups.map((group) => (
                  <div className="space-y-3" key={`segment-${group.segment}`}>
                    <div className="flex items-center gap-2 border-l-2 border-primary/50 pl-2.5">
                      <span className="text-sm font-medium text-foreground">{group.segment}</span>
                      <Badge variant="secondary">{group.competitors.length} 款产品</Badge>
                    </div>
                    <div className="space-y-4 pl-1">{group.competitors.map(renderCard)}</div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="space-y-4">{competitorIds.map(renderCard)}</div>
            )}
          </section>

          <section className="space-y-3">
            <h4 className="flex items-center gap-2 text-sm font-semibold text-foreground">
              <Tags className="h-4 w-4 text-primary" />
              关键维度对比矩阵
            </h4>
            <div className="overflow-hidden rounded-lg border border-border">
              <div className="overflow-x-auto">
                <table className="min-w-[820px] w-full text-xs">
                  <thead className="bg-surface/80 text-left text-foreground-muted">
                    <tr>
                      <th className="px-3 py-2">产品</th>
                      <th className="px-3 py-2">赛道</th>
                      <th className="px-3 py-2">厂商</th>
                      <th className="px-3 py-2">关键功能</th>
                      <th className="px-3 py-2">定价模型</th>
                      <th className="px-3 py-2">画像</th>
                      <th className="px-3 py-2">反馈</th>
                    </tr>
                  </thead>
                  <tbody>
                    {competitorIds.map((competitorId) => {
                      const competitorFeatures = featureGroups.get(competitorId) ?? [];
                      const competitorPricings = pricingGroups.get(competitorId) ?? [];
                      const competitorPersonas = personaGroups.get(competitorId) ?? [];
                      const competitorFeedback = feedbackGroups.get(competitorId) ?? [];
                      const cardMeta = competitorMeta[competitorId];
                      return (
                        <tr className="border-t border-border/80" key={`matrix-${competitorId}`}>
                          <td className="px-3 py-2 font-medium text-foreground">{competitorId}</td>
                          <td className="px-3 py-2 text-muted-foreground">{cardMeta?.segment ?? "—"}</td>
                          <td className="px-3 py-2 text-muted-foreground">{cardMeta?.vendor ?? "—"}</td>
                          <td className="px-3 py-2 text-muted-foreground">
                            {competitorFeatures.slice(0, 2).map((item) => item.name).join(" / ") || "—"}
                          </td>
                          <td className="px-3 py-2 text-muted-foreground">
                            {competitorPricings.slice(0, 1).map((item) => item.model).join(" / ") || "—"}
                          </td>
                          <td className="px-3 py-2 text-muted-foreground">
                            {competitorPersonas.length > 0 ? `${competitorPersonas.length} 条` : "—"}
                          </td>
                          <td className="px-3 py-2 text-muted-foreground">
                            {competitorFeedback.length > 0 ? `${competitorFeedback.length} 条` : "—"}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          </section>
        </>
      )}
    </div>
  );
}
