from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from models.comparison import ComparisonCellRecord
from schemas.ids import make_id
from service.comparison.mapper import comparisons_to_cells
from service.run_steps import latest_completed_step_id


async def persist_comparisons_for_step(
    *,
    session: AsyncSession,
    run_id: str,
    step_id: str,
    comparisons: list[dict[str, object]],
    evidence_lookup: dict[str, object],
    competitors: list[str],
    competitors_with_evidence: set[str] | None = None,
) -> list[ComparisonCellRecord]:
    mapped_items = comparisons_to_cells(
        run_id=run_id,
        step_id=step_id,
        comparisons=comparisons,
        evidence_lookup=evidence_lookup,
        competitors=competitors,
        competitors_with_evidence=competitors_with_evidence,
    )
    records: list[ComparisonCellRecord] = []
    for mapped in mapped_items:
        record = ComparisonCellRecord(
            cell_id=make_id("cmp_"),
            run_id=run_id,
            step_id=step_id,
            dimension=mapped["dimension"],
            competitor_id=mapped["competitor_id"],
            stance=mapped["stance"],
            summary=mapped["summary"],
            evidence_ids=mapped["evidence_ids"],
        )
        session.add(record)
        records.append(record)
    return records


async def load_comparisons_for_run(
    *,
    session: AsyncSession,
    run_id: str,
) -> list[dict[str, object]]:
    latest_analyst_step_id = await latest_completed_step_id(
        session=session,
        run_id=run_id,
        agent_name="analyst",
    )
    query = (
        select(ComparisonCellRecord)
        .where(ComparisonCellRecord.run_id == run_id)
        .order_by(
            ComparisonCellRecord.dimension.asc(),
            ComparisonCellRecord.competitor_id.asc(),
            ComparisonCellRecord.created_at.asc(),
        )
    )
    if latest_analyst_step_id is not None:
        query = query.where(ComparisonCellRecord.step_id == latest_analyst_step_id)
    cell_rows = (await session.execute(query)).scalars().all()
    grouped: dict[str, list[dict[str, object]]] = {}
    for row in cell_rows:
        grouped.setdefault(row.dimension, []).append(
            {
                "cell_id": row.cell_id,
                "run_id": row.run_id,
                "step_id": row.step_id,
                "dimension": row.dimension,
                "competitor_id": row.competitor_id,
                "stance": row.stance,
                "summary": row.summary,
                "evidence_ids": list(row.evidence_ids),
                "created_at": row.created_at.isoformat(),
            }
        )

    return [
        {
            "dimension": dimension,
            "cells": cells,
        }
        for dimension, cells in grouped.items()
    ]
