from __future__ import annotations

from typing import TypedDict

from schemas.contracts import validate_section_id
from service.comparison.mapper import MappedComparisonCell, comparisons_to_cells


ALLOWED_CONFIDENCE = {"low", "medium", "high"}


class MappedConclusion(TypedDict):
    section: str
    claim: str
    confidence: str
    evidence_ids: list[str]
    competitor_ids: list[str]
    risk_flags: list[str]


def _stable_unique(items: list[str]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for item in items:
        if item in seen:
            continue
        seen.add(item)
        ordered.append(item)
    return ordered


def _extract_competitor_id(lookup_item: object) -> str | None:
    if isinstance(lookup_item, dict):
        direct_competitor_id = lookup_item.get("competitor_id")
        if isinstance(direct_competitor_id, str) and direct_competitor_id.strip():
            return direct_competitor_id.strip()
        span = lookup_item.get("span")
        if isinstance(span, dict):
            nested_competitor_id = span.get("competitor_id")
            if isinstance(nested_competitor_id, str) and nested_competitor_id.strip():
                return nested_competitor_id.strip()

    span = getattr(lookup_item, "span", None)
    if isinstance(span, dict):
        competitor_id = span.get("competitor_id")
        if isinstance(competitor_id, str) and competitor_id.strip():
            return competitor_id.strip()
    return None


def _risk_flags_for_dimension(dimension: str, risk_flags: list[str]) -> list[str]:
    normalized_dimension = dimension.strip().lower()
    prefixes = (
        f"{normalized_dimension}_",
        f"{normalized_dimension}:",
        f"{normalized_dimension}-",
        f"uncovered_dimension:{normalized_dimension}",
        f"uncovered_section:{normalized_dimension}",
    )
    matched = []
    for item in risk_flags:
        lowered = item.strip().lower()
        if lowered == normalized_dimension or lowered.startswith(prefixes):
            matched.append(item)
    return _stable_unique(matched)


def _confidence_from_comparison_cells(cells: list[MappedComparisonCell]) -> str:
    evidence_count = len(_stable_unique([evidence_id for cell in cells for evidence_id in cell["evidence_ids"]]))
    grounded_competitor_count = len(_stable_unique([cell["competitor_id"] for cell in cells]))
    if grounded_competitor_count >= 3 and evidence_count >= 4:
        return "high"
    if grounded_competitor_count >= 2 and evidence_count >= 2:
        return "medium"
    return "low"


def _comparison_claim(*, dimension: str, cells: list[MappedComparisonCell]) -> str:
    fragments = [
        f'{cell["competitor_id"]} is {cell["stance"]}: {cell["summary"]}'
        for cell in cells[:4]
    ]
    return f"{dimension} comparison: " + "; ".join(fragments)


def insights_to_conclusions(
    *,
    run_id: str,
    step_id: str,
    insights: list[dict[str, object]],
    evidence_lookup: dict[str, object],
    risk_flags: list[str],
) -> list[MappedConclusion]:
    del run_id, step_id
    mapped: list[MappedConclusion] = []
    for insight in insights:
        dimension_raw = insight.get("dimension")
        claim_raw = insight.get("finding")
        confidence_raw = insight.get("confidence")
        evidence_ids_raw = insight.get("evidence_ids")

        if not isinstance(dimension_raw, str) or not isinstance(claim_raw, str) or not claim_raw.strip():
            continue
        try:
            normalized_dimension = validate_section_id(dimension_raw)
        except ValueError:
            continue
        if not isinstance(evidence_ids_raw, list):
            continue

        evidence_ids = [
            item
            for item in evidence_ids_raw
            if isinstance(item, str) and item in evidence_lookup
        ]
        evidence_ids = _stable_unique(evidence_ids)
        if not evidence_ids:
            continue

        confidence = (
            confidence_raw
            if isinstance(confidence_raw, str) and confidence_raw in ALLOWED_CONFIDENCE
            else "medium"
        )
        competitor_ids = _stable_unique(
            [
                competitor_id
                for competitor_id in (
                    _extract_competitor_id(evidence_lookup[evidence_id])
                    for evidence_id in evidence_ids
                )
                if competitor_id is not None
            ]
        )

        mapped.append(
            {
                "section": normalized_dimension,
                "claim": claim_raw.strip(),
                "confidence": confidence,
                "evidence_ids": evidence_ids,
                "competitor_ids": competitor_ids,
                "risk_flags": _risk_flags_for_dimension(normalized_dimension, risk_flags),
            }
        )
    return mapped


def comparisons_to_conclusions(
    *,
    run_id: str,
    step_id: str,
    comparisons: list[dict[str, object]],
    evidence_lookup: dict[str, object],
    competitors: list[str],
    covered_sections: set[str] | None = None,
    risk_flags: list[str] | None = None,
) -> list[MappedConclusion]:
    covered = covered_sections or set()
    risk_flag_rows = risk_flags or []
    mapped_cells = comparisons_to_cells(
        run_id=run_id,
        step_id=step_id,
        comparisons=comparisons,
        evidence_lookup=evidence_lookup,
        competitors=competitors,
    )
    cells_by_dimension: dict[str, list[MappedComparisonCell]] = {}
    for cell in mapped_cells:
        cells_by_dimension.setdefault(cell["dimension"], []).append(cell)

    mapped: list[MappedConclusion] = []
    for dimension, cells in cells_by_dimension.items():
        if dimension in covered:
            continue
        grounded_cells = [
            cell
            for cell in cells
            if cell["stance"] != "unknown" and cell["evidence_ids"]
        ]
        if not grounded_cells:
            continue
        evidence_ids = _stable_unique(
            [evidence_id for cell in grounded_cells for evidence_id in cell["evidence_ids"]]
        )
        if not evidence_ids:
            continue
        competitor_ids = _stable_unique([cell["competitor_id"] for cell in grounded_cells])
        mapped.append(
            {
                "section": dimension,
                "claim": _comparison_claim(dimension=dimension, cells=grounded_cells),
                "confidence": _confidence_from_comparison_cells(grounded_cells),
                "evidence_ids": evidence_ids,
                "competitor_ids": competitor_ids,
                "risk_flags": _risk_flags_for_dimension(dimension, risk_flag_rows),
            }
        )
    return mapped
