import type { KnowledgeCompetitor, PlanTree } from "@/api/types";

const ROLE_ORDER = [
  "direct_competitor",
  "adjacent_competitor",
  "substitute",
  "upstream_supplier",
  "trend_reference",
  "unknown",
] as const;

const CORE_ROLES = new Set<string>(["direct_competitor", "adjacent_competitor", "substitute"]);

const ROLE_LABELS: Record<string, string> = {
  direct_competitor: "核心竞品",
  adjacent_competitor: "相邻产品",
  substitute: "替代方案",
  upstream_supplier: "上游生态",
  trend_reference: "趋势样本",
  unknown: "未分类",
};

export interface CompetitorRoleGroup {
  role: string;
  label: string;
  competitors: string[];
  isCore: boolean;
}

export interface CompetitorMeta {
  role: string;
  segment: string | null;
  introduction: string | null;
  vendor: string | null;
}

export const UNGROUPED_SEGMENT_LABEL = "未归类赛道";

export function normalizeCandidateRole(role: string | null | undefined): string {
  const cleaned = typeof role === "string" ? role.trim() : "";
  return cleaned.length > 0 ? cleaned : "unknown";
}

export function candidateRoleLabel(role: string | null | undefined): string {
  const normalized = normalizeCandidateRole(role);
  return ROLE_LABELS[normalized] ?? normalized;
}

export function isCoreCandidateRole(role: string | null | undefined): boolean {
  return CORE_ROLES.has(normalizeCandidateRole(role));
}

export function roleByCompetitor(
  planTree: PlanTree | null | undefined,
): Record<string, string> {
  const sources = planTree?.competitor_sources;
  if (!sources) {
    return {};
  }
  const mapping: Record<string, string> = {};
  for (const [competitorId, payload] of Object.entries(sources)) {
    mapping[competitorId] = normalizeCandidateRole(payload?.candidate_role ?? null);
  }
  return mapping;
}

function cleanText(value: string | null | undefined): string | null {
  const trimmed = typeof value === "string" ? value.trim() : "";
  return trimmed.length > 0 ? trimmed : null;
}

/**
 * Single source of truth for competitor profiles: the `/knowledge` endpoint
 * already resolves role/segment/vendor/introduction server-side (plan_tree
 * mirror with discovery-step fallback), so the UI just maps it by competitor_id.
 */
export function competitorMetaFromKnowledge(
  competitors: KnowledgeCompetitor[] | null | undefined,
): Record<string, CompetitorMeta> {
  if (!competitors) {
    return {};
  }
  const mapping: Record<string, CompetitorMeta> = {};
  for (const competitor of competitors) {
    mapping[competitor.competitor_id] = {
      role: normalizeCandidateRole(competitor.role),
      segment: cleanText(competitor.segment),
      introduction: cleanText(competitor.introduction),
      vendor: cleanText(competitor.vendor),
    };
  }
  return mapping;
}

export function groupCompetitorRoles(
  planTree: PlanTree | null | undefined,
): CompetitorRoleGroup[] {
  const byRole = new Map<string, string[]>();
  const mapping = roleByCompetitor(planTree);
  for (const [competitorId, role] of Object.entries(mapping)) {
    const competitors = byRole.get(role) ?? [];
    competitors.push(competitorId);
    byRole.set(role, competitors);
  }
  if (byRole.size === 0) {
    return [];
  }
  return Array.from(byRole.entries())
    .sort((a, b) => {
      const orderA = ROLE_ORDER.indexOf(a[0] as (typeof ROLE_ORDER)[number]);
      const orderB = ROLE_ORDER.indexOf(b[0] as (typeof ROLE_ORDER)[number]);
      const normalizedA = orderA === -1 ? Number.MAX_SAFE_INTEGER : orderA;
      const normalizedB = orderB === -1 ? Number.MAX_SAFE_INTEGER : orderB;
      if (normalizedA !== normalizedB) {
        return normalizedA - normalizedB;
      }
      return a[0].localeCompare(b[0]);
    })
    .map(([role, competitors]) => ({
      role,
      label: candidateRoleLabel(role),
      competitors: [...competitors].sort((left, right) => left.localeCompare(right)),
      isCore: isCoreCandidateRole(role),
    }));
}
