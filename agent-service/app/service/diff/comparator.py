from __future__ import annotations

import json

from sqlalchemy import cast, select
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.asyncio import AsyncSession

from models.comparison import ComparisonCellRecord
from models.competitor_diff import CompetitorDiff
from models.run import Run
from schemas.ids import make_id
from utils.logger import get_logger

log = get_logger("service.diff.comparator")

_STANCE_SEVERITY: dict[str, int] = {
    "leader": 3,
    "competitive": 2,
    "laggard": 1,
    "unknown": 0,
}


def _judge_stance_change_significance(old_stance: str, new_stance: str) -> str:
    old_score = _STANCE_SEVERITY.get(old_stance, 0)
    new_score = _STANCE_SEVERITY.get(new_stance, 0)
    delta = abs(old_score - new_score)
    if delta >= 2:
        return "high"
    if delta == 1:
        return "medium"
    return "low"


def _summary_changed_materially(old_summary: str, new_summary: str) -> bool:
    old_words = set(old_summary.lower().split())
    new_words = set(new_summary.lower().split())
    if not old_words and not new_words:
        return False
    union = old_words | new_words
    intersection = old_words & new_words
    jaccard = len(intersection) / len(union) if union else 1.0
    return jaccard < 0.7


async def compute_diff(
    *,
    run_id_new: str,
    competitor_id: str,
    session: AsyncSession,
) -> list[CompetitorDiff]:
    """Compare comparison cells for competitor_id between run_id_new and most recent prior run.

    Returns an empty list if there is no prior run to compare against or
    if the new run has no comparison cells for this competitor.
    """
    new_cells_rows = (
        await session.execute(
            select(ComparisonCellRecord).where(
                ComparisonCellRecord.run_id == run_id_new,
                ComparisonCellRecord.competitor_id == competitor_id,
            )
        )
    ).scalars().all()

    if not new_cells_rows:
        return []

    run_new = await session.get(Run, run_id_new)
    if run_new is None or run_new.finished_at is None:
        return []

    # Find the most recent prior completed run that includes this competitor.
    # Run.competitors is a JSONB array — use PostgreSQL @> (contains) operator.
    prev_run = (
        await session.execute(
            select(Run)
            .where(
                Run.run_id != run_id_new,
                Run.status.in_(["completed", "degraded"]),
                Run.finished_at < run_new.finished_at,
                Run.competitors.op("@>")(cast(json.dumps([competitor_id]), JSONB)),
            )
            .order_by(Run.finished_at.desc())
            .limit(1)
        )
    ).scalars().first()

    if prev_run is None:
        return []

    old_cells_rows = (
        await session.execute(
            select(ComparisonCellRecord).where(
                ComparisonCellRecord.run_id == prev_run.run_id,
                ComparisonCellRecord.competitor_id == competitor_id,
            )
        )
    ).scalars().all()

    cells_new = {row.dimension: row for row in new_cells_rows}
    cells_old = {row.dimension: row for row in old_cells_rows}
    all_dimensions = set(cells_new) | set(cells_old)

    diffs: list[CompetitorDiff] = []
    for dimension in sorted(all_dimensions):
        new_cell = cells_new.get(dimension)
        old_cell = cells_old.get(dimension)

        if new_cell is None and old_cell is not None:
            diffs.append(
                CompetitorDiff(
                    diff_id=make_id("diff_"),
                    competitor_id=competitor_id,
                    run_id_new=run_id_new,
                    run_id_old=prev_run.run_id,
                    dimension=dimension,
                    change_type="lost_dimension",
                    old_value={"stance": old_cell.stance, "summary": old_cell.summary},
                    new_value=None,
                    significance="low",
                )
            )
        elif old_cell is None and new_cell is not None:
            diffs.append(
                CompetitorDiff(
                    diff_id=make_id("diff_"),
                    competitor_id=competitor_id,
                    run_id_new=run_id_new,
                    run_id_old=prev_run.run_id,
                    dimension=dimension,
                    change_type="new_dimension",
                    old_value=None,
                    new_value={"stance": new_cell.stance, "summary": new_cell.summary},
                    significance="medium",
                )
            )
        elif old_cell is not None and new_cell is not None:
            if old_cell.stance != new_cell.stance:
                significance = _judge_stance_change_significance(old_cell.stance, new_cell.stance)
                diffs.append(
                    CompetitorDiff(
                        diff_id=make_id("diff_"),
                        competitor_id=competitor_id,
                        run_id_new=run_id_new,
                        run_id_old=prev_run.run_id,
                        dimension=dimension,
                        change_type="stance_changed",
                        old_value={"stance": old_cell.stance, "summary": old_cell.summary},
                        new_value={"stance": new_cell.stance, "summary": new_cell.summary},
                        significance=significance,
                    )
                )
            elif _summary_changed_materially(old_cell.summary, new_cell.summary):
                diffs.append(
                    CompetitorDiff(
                        diff_id=make_id("diff_"),
                        competitor_id=competitor_id,
                        run_id_new=run_id_new,
                        run_id_old=prev_run.run_id,
                        dimension=dimension,
                        change_type="summary_changed",
                        old_value={"stance": old_cell.stance, "summary": old_cell.summary},
                        new_value={"stance": new_cell.stance, "summary": new_cell.summary},
                        significance="low",
                    )
                )

    log.info(
        "diff.compute.done",
        run_id_new=run_id_new,
        run_id_old=prev_run.run_id,
        competitor_id=competitor_id,
        diff_count=len(diffs),
    )
    return diffs
