import { defaultUrlTransform } from "react-markdown";

const CITATION_REGEX = /\[(ev_[a-zA-Z0-9_]+)\](?!\()/g;
const EVIDENCE_URL_PREFIX = "evidence://";

function asNonEmptyString(value: unknown): string | null {
  if (typeof value !== "string") {
    return null;
  }
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

function asStringList(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => asNonEmptyString(item))
    .filter((item): item is string => item !== null);
}

function buildEvidenceLink(runId: string, evidenceId: string): string {
  return `/app/runs/${runId}/evidence?evidence_id=${encodeURIComponent(evidenceId)}`;
}

export function toCitationLinkMarkdown(markdown: string): string {
  const citationIndexByEvidenceId = new Map<string, number>();
  let nextIndex = 1;
  return markdown.replace(
    CITATION_REGEX,
    (_match, evidenceId: string) => {
      const knownIndex = citationIndexByEvidenceId.get(evidenceId);
      if (knownIndex !== undefined) {
        return `[E${knownIndex}](${EVIDENCE_URL_PREFIX}${evidenceId})`;
      }
      const currentIndex = nextIndex;
      citationIndexByEvidenceId.set(evidenceId, currentIndex);
      nextIndex += 1;
      return `[E${currentIndex}](${EVIDENCE_URL_PREFIX}${evidenceId})`;
    },
  );
}

export function transformEvidenceMarkdownUrl(url: string): string {
  if (url.startsWith(EVIDENCE_URL_PREFIX)) {
    return url;
  }
  return defaultUrlTransform(url);
}

function buildCompetitorLink(runId: string, competitorId: string): string {
  return `/app/runs/${runId}/evidence?competitor_id=${encodeURIComponent(competitorId)}`;
}

export function buildEvidenceLinkFromToolArgs(
  runId: string,
  toolArgs: Record<string, unknown>,
): string | null {
  const directEvidenceIds = asStringList(toolArgs.evidence_ids);
  if (directEvidenceIds.length > 0) {
    return buildEvidenceLink(runId, directEvidenceIds[0]);
  }

  const directCompetitorId = asNonEmptyString(toolArgs.competitor_id);
  if (directCompetitorId !== null) {
    return buildCompetitorLink(runId, directCompetitorId);
  }

  const topicRecords = Array.isArray(toolArgs.topics) ? toolArgs.topics : [];
  for (const topic of topicRecords) {
    if (typeof topic !== "object" || topic === null) {
      continue;
    }
    const record = topic as Record<string, unknown>;
    const competitorId = asNonEmptyString(record.competitor_id);
    if (competitorId !== null) {
      return buildCompetitorLink(runId, competitorId);
    }
  }

  return null;
}

export function buildEvidenceLinkFromPayload(
  runId: string,
  payload: Record<string, unknown>,
): string | null {
  const evidenceIds = [
    ...asStringList(payload.evidence_ids),
    ...asStringList(payload.supporting_evidence_ids),
    ...asStringList(payload.cited_evidence_ids),
  ];
  if (evidenceIds.length > 0) {
    return buildEvidenceLink(runId, evidenceIds[0]);
  }

  const competitorKeys = ["competitor_id", "focus_competitor_id", "target_competitor_id"];
  for (const key of competitorKeys) {
    const competitorId = asNonEmptyString(payload[key]);
    if (competitorId !== null) {
      return buildCompetitorLink(runId, competitorId);
    }
  }

  return buildEvidenceLinkFromToolArgs(runId, payload);
}
