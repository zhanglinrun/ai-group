from __future__ import annotations

import re


_TOKEN_PATTERN = re.compile(r"[^a-z0-9]+")


def _normalize_tag(raw: str) -> str:
    normalized = _TOKEN_PATTERN.sub("_", raw.strip().lower()).strip("_")
    return normalized[:48]


def infer_candidate_tags(
    *,
    domain_hint: str | None,
    evidence_source_counts: dict[str, int],
    qa_rejection_count: int,
) -> list[str]:
    tags: set[str] = {"generic"}
    if domain_hint is not None and domain_hint.strip():
        domain_tag = _normalize_tag(domain_hint)
        if domain_tag:
            tags.add(domain_tag)

    for source_type, count in evidence_source_counts.items():
        if count <= 0:
            continue
        normalized_source = _normalize_tag(source_type)
        if not normalized_source:
            continue
        tags.add(f"source_{normalized_source}")

    if qa_rejection_count > 0:
        tags.add("qa_rejection")

    return sorted(tags)
