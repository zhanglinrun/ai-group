from __future__ import annotations

import re
from dataclasses import dataclass

from models.evidence import EvidenceRecord

NUMERIC_CLAIM_LIMIT = 12
EVIDENCE_PREVIEW_LIMIT = 3
QUOTE_PREVIEW_CHARS = 220
CLAIM_TEXT_CHARS = 360

_NUMERIC_PATTERN = re.compile(
    r"("
    r"\d+(?:\.\d+)?\s?%"
    r"|"
    r"\d+(?:\.\d+)?\s?(?:小时|分钟|秒|h|hr|hrs|hour|hours|min|mins|minute|minutes)"
    r"|"
    r"(?:[$￥¥]\s?\d+(?:\.\d+)?|\d+(?:\.\d+)?\s?(?:万美元|万元|美元|美金|万|元|k|K|m|M|million|billion))"
    r"(?:\s?(?:-|~|–|—|到|至)\s?"
    r"(?:[$￥¥]?\s?\d+(?:\.\d+)?\s?(?:万美元|万元|美元|美金|万|元|k|K|m|M|million|billion)?))?"
    r")",
    re.IGNORECASE,
)
_CLAIM_SPLIT_PATTERN = re.compile(r"(?<=[。！？!?;；])\s+|\n+")


@dataclass(frozen=True)
class NumericClaimCandidate:
    section_id: str
    claim: str
    numbers: list[str]
    evidence_ids: list[str]
    evidence_quotes: list[dict[str, str]]

    def to_prompt_dict(self) -> dict[str, object]:
        return {
            "section_id": self.section_id,
            "claim": self.claim,
            "numbers": list(self.numbers),
            "evidence_ids": list(self.evidence_ids),
            "evidence_quotes": [dict(item) for item in self.evidence_quotes],
        }


def _section_id(section: dict[str, object]) -> str | None:
    section_id_raw = section.get("section_id")
    return section_id_raw if isinstance(section_id_raw, str) and section_id_raw else None


def _section_markdown(section: dict[str, object]) -> str:
    value = section.get("content_markdown")
    return value if isinstance(value, str) else ""


def _section_evidence_refs(section: dict[str, object]) -> list[str]:
    refs_raw = section.get("evidence_refs")
    if not isinstance(refs_raw, list):
        return []
    refs: list[str] = []
    seen: set[str] = set()
    for item in refs_raw:
        if not isinstance(item, str) or not item or item in seen:
            continue
        seen.add(item)
        refs.append(item)
    return refs


def _iter_claim_fragments(markdown: str) -> list[str]:
    fragments: list[str] = []
    for raw_line in _CLAIM_SPLIT_PATTERN.split(markdown):
        line = raw_line.strip(" \t-*")
        if not line:
            continue
        fragments.append(line[:CLAIM_TEXT_CHARS].strip())
    return fragments


def _evidence_quotes(
    *,
    evidence_ids: list[str],
    evidence_by_id: dict[str, EvidenceRecord],
) -> list[dict[str, str]]:
    quotes: list[dict[str, str]] = []
    for evidence_id in evidence_ids[:EVIDENCE_PREVIEW_LIMIT]:
        evidence = evidence_by_id.get(evidence_id)
        if evidence is None:
            continue
        quote = evidence.sanitized_text.strip()
        if not quote:
            quote = evidence.quote.strip()
        if not quote:
            continue
        quotes.append(
            {
                "evidence_id": evidence_id,
                "quote_preview": quote[:QUOTE_PREVIEW_CHARS],
            }
        )
    return quotes


def extract_numeric_claim_candidates(
    *,
    report_json: dict[str, object],
    evidence_items: list[EvidenceRecord],
    limit: int = NUMERIC_CLAIM_LIMIT,
) -> list[NumericClaimCandidate]:
    sections_raw = report_json.get("sections")
    if not isinstance(sections_raw, list):
        return []
    evidence_by_id = {item.id: item for item in evidence_items}
    candidates: list[NumericClaimCandidate] = []
    seen_claims: set[tuple[str, str]] = set()
    for section_raw in sections_raw:
        if not isinstance(section_raw, dict):
            continue
        section_id = _section_id(section_raw)
        if section_id is None:
            continue
        evidence_ids = _section_evidence_refs(section_raw)
        evidence_quotes = _evidence_quotes(evidence_ids=evidence_ids, evidence_by_id=evidence_by_id)
        for fragment in _iter_claim_fragments(_section_markdown(section_raw)):
            numbers = [match.group(0).strip() for match in _NUMERIC_PATTERN.finditer(fragment)]
            if not numbers:
                continue
            claim_key = (section_id, fragment)
            if claim_key in seen_claims:
                continue
            seen_claims.add(claim_key)
            candidates.append(
                NumericClaimCandidate(
                    section_id=section_id,
                    claim=fragment,
                    numbers=numbers,
                    evidence_ids=evidence_ids,
                    evidence_quotes=evidence_quotes,
                )
            )
            if len(candidates) >= limit:
                return candidates
    return candidates
