from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from models.step import Step


async def latest_completed_step_id(
    *,
    session: AsyncSession,
    run_id: str,
    agent_name: str,
) -> str | None:
    """Return the most recent completed step_id for an agent in a run.

    On QA reject the same agent re-runs and writes a fresh step; derived artifacts
    (comparison cells, conclusions, knowledge) accumulate across passes. Reads that
    must reflect only the current pass scope to this step_id, matching the latest-wins
    semantics already used for reports and run_knowledge.
    """
    return (
        await session.execute(
            select(Step.step_id)
            .where(
                Step.run_id == run_id,
                Step.agent_name == agent_name,
                Step.status == "completed",
            )
            .order_by(Step.created_at.desc())
            .limit(1)
        )
    ).scalars().first()
