from __future__ import annotations

from typing import TypedDict

from schemas.contracts import validate_dimension

ALLOWED_STANCES = {"leader", "competitive", "laggard", "unknown"}


class MappedComparisonCell(TypedDict):
    dimension: str
    competitor_id: str
    stance: str
    summary: str
    evidence_ids: list[str]


def _stable_unique(items: list[str]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for item in items:
        if item in seen:
            continue
        seen.add(item)
        ordered.append(item)
    return ordered


def comparisons_to_cells(
    *,
    run_id: str,
    step_id: str,
    comparisons: list[dict[str, object]],
    evidence_lookup: dict[str, object],
    competitors: list[str],
    competitors_with_evidence: set[str] | None = None,
) -> list[MappedComparisonCell]:
    del run_id, step_id
    allowed_competitors = {item.strip() for item in competitors if item.strip()}
    mapped: list[MappedComparisonCell] = []
    for comparison in comparisons:
        dimension_raw = comparison.get("dimension")
        cells_raw = comparison.get("cells")
        if not isinstance(dimension_raw, str) or not isinstance(cells_raw, list):
            continue
        try:
            dimension = validate_dimension(dimension_raw)
        except ValueError:
            continue

        dimension_cells: list[MappedComparisonCell] = []
        seen_competitors: set[str] = set()
        for cell in cells_raw:
            if not isinstance(cell, dict):
                continue
            competitor_raw = cell.get("competitor_id")
            summary_raw = cell.get("summary")
            evidence_ids_raw = cell.get("evidence_ids")
            if not isinstance(competitor_raw, str) or not competitor_raw.strip():
                continue
            competitor_id = competitor_raw.strip()
            if competitor_id in seen_competitors:
                continue
            if allowed_competitors and competitor_id not in allowed_competitors:
                continue
            # Research-yield gate: a competitor that produced zero evidence across the
            # whole run is a discovery false positive (article anecdote, not a researchable
            # product). Keep it out of the comparison matrix instead of emitting an empty
            # "unknown" cell with an ungrounded LLM summary.
            if (
                competitors_with_evidence is not None
                and competitor_id not in competitors_with_evidence
            ):
                continue
            if not isinstance(summary_raw, str) or not summary_raw.strip():
                continue
            stance_raw = cell.get("stance")
            stance = stance_raw if isinstance(stance_raw, str) and stance_raw in ALLOWED_STANCES else "unknown"
            evidence_ids = (
                [
                    evidence_id
                    for evidence_id in evidence_ids_raw
                    if isinstance(evidence_id, str) and evidence_id in evidence_lookup
                ]
                if isinstance(evidence_ids_raw, list)
                else []
            )
            if stance != "unknown" and not evidence_ids:
                stance = "unknown"
            seen_competitors.add(competitor_id)
            dimension_cells.append(
                {
                    "dimension": dimension,
                    "competitor_id": competitor_id,
                    "stance": stance,
                    "summary": summary_raw.strip(),
                    "evidence_ids": _stable_unique(evidence_ids),
                }
            )
        if len(dimension_cells) >= 2:
            mapped.extend(dimension_cells)
    return mapped
