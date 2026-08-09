from __future__ import annotations

from datetime import datetime, timedelta, timezone
import sys
from uuid import uuid4

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from core.config import settings
from models.comparison import ComparisonCellRecord
from models.evidence import EvidenceRecord
from models.run import Run
from models.step import Step
from service.comparison.persistence import load_comparisons_for_run, persist_comparisons_for_step

pytestmark = pytest.mark.skipif(
    sys.platform == "win32",
    reason="psycopg async engine is incompatible with Proactor loop on Windows CI.",
)


@pytest.mark.asyncio
async def test_comparison_persistence_and_load_grouping() -> None:
    run_id = f"run_comparisons_{uuid4().hex[:8]}"
    step_id = f"step_comparisons_{uuid4().hex[:8]}"
    engine = create_async_engine(settings.DATABASE_URL, pool_pre_ping=True)
    session_factory = async_sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    now = datetime.now(timezone.utc)

    try:
        async with session_factory() as session:
            run = Run(
                run_id=run_id,
                user_query="comparison persistence test",
                domain_hint="ai_coding_tools",
                reference_urls=[],
                status="running",
                target_roles=["pm"],
                competitors=["Cursor", "Windsurf"],
            )
            step = Step(
                step_id=step_id,
                run_id=run_id,
                agent_name="analyst",
                status="completed",
                retry_count=0,
                payload={"analysis_payload": {}},
            )
            evidence_rows = [
                EvidenceRecord(
                    id=f"ev_cmp_{uuid4().hex[:8]}",
                    run_id=run_id,
                    source_type="article",
                    source_url="https://example.com/cursor-feature",
                    source_title="cursor feature article",
                    quote="Cursor keeps repository context stronger.",
                    sanitized_text="Cursor keeps repository context stronger.",
                    span={"dimension": "feature", "competitor_id": "Cursor"},
                    collected_by=step_id,
                    collected_at=now,
                    desensitized=True,
                ),
                EvidenceRecord(
                    id=f"ev_cmp_{uuid4().hex[:8]}",
                    run_id=run_id,
                    source_type="article",
                    source_url="https://example.com/windsurf-feature",
                    source_title="windsurf feature article",
                    quote="Windsurf is competitive on workflow automation.",
                    sanitized_text="Windsurf is competitive on workflow automation.",
                    span={"dimension": "feature", "competitor_id": "Windsurf"},
                    collected_by=step_id,
                    collected_at=now,
                    desensitized=True,
                ),
            ]
            session.add(run)
            session.add(step)
            await session.flush()
            for row in evidence_rows:
                session.add(row)
            await session.flush()

            persisted = await persist_comparisons_for_step(
                session=session,
                run_id=run_id,
                step_id=step_id,
                comparisons=[
                    {
                        "dimension": "feature",
                        "cells": [
                            {
                                "competitor_id": "Cursor",
                                "stance": "leader",
                                "summary": "Cursor leads on repo context.",
                                "evidence_ids": [evidence_rows[0].id],
                            },
                            {
                                "competitor_id": "Windsurf",
                                "stance": "competitive",
                                "summary": "Windsurf is competitive.",
                                "evidence_ids": [evidence_rows[1].id],
                            },
                        ],
                    }
                ],
                evidence_lookup={row.id: row for row in evidence_rows},
                competitors=["Cursor", "Windsurf"],
            )
            await session.flush()

            cell_count = int(
                (
                    await session.execute(
                        select(func.count()).select_from(ComparisonCellRecord).where(ComparisonCellRecord.run_id == run_id)
                    )
                ).scalar_one()
            )
            assert len(persisted) == 2
            assert cell_count == 2

            loaded = await load_comparisons_for_run(session=session, run_id=run_id)
            assert loaded == [
                {
                    "dimension": "feature",
                    "cells": [
                        {
                            "cell_id": persisted[0].cell_id,
                            "run_id": run_id,
                            "step_id": step_id,
                            "dimension": "feature",
                            "competitor_id": "Cursor",
                            "stance": "leader",
                            "summary": "Cursor leads on repo context.",
                            "evidence_ids": [evidence_rows[0].id],
                            "created_at": persisted[0].created_at.isoformat(),
                        },
                        {
                            "cell_id": persisted[1].cell_id,
                            "run_id": run_id,
                            "step_id": step_id,
                            "dimension": "feature",
                            "competitor_id": "Windsurf",
                            "stance": "competitive",
                            "summary": "Windsurf is competitive.",
                            "evidence_ids": [evidence_rows[1].id],
                            "created_at": persisted[1].created_at.isoformat(),
                        },
                    ],
                }
            ]

            await session.execute(delete(Run).where(Run.run_id == run_id))
            await session.commit()
    finally:
        await engine.dispose()


@pytest.mark.asyncio
async def test_load_comparisons_returns_only_latest_analyst_step() -> None:
    run_id = f"run_retry_cmp_{uuid4().hex[:8]}"
    old_step_id = f"step_old_{uuid4().hex[:8]}"
    new_step_id = f"step_new_{uuid4().hex[:8]}"
    engine = create_async_engine(settings.DATABASE_URL, pool_pre_ping=True)
    session_factory = async_sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    now = datetime.now(timezone.utc)

    try:
        async with session_factory() as session:
            session.add(
                Run(
                    run_id=run_id,
                    user_query="retry comparison scoping test",
                    domain_hint="ai_coding_tools",
                    reference_urls=[],
                    status="running",
                    target_roles=["pm"],
                    competitors=["Cursor", "Windsurf"],
                )
            )
            # Rejected analyst pass (older) and the retry pass (newer).
            session.add(
                Step(
                    step_id=old_step_id,
                    run_id=run_id,
                    agent_name="analyst",
                    status="completed",
                    retry_count=0,
                    payload={"analysis_payload": {}},
                    created_at=now - timedelta(minutes=5),
                )
            )
            session.add(
                Step(
                    step_id=new_step_id,
                    run_id=run_id,
                    agent_name="analyst",
                    status="completed",
                    retry_count=1,
                    payload={"analysis_payload": {}},
                    created_at=now,
                )
            )
            evidence_rows = [
                EvidenceRecord(
                    id=f"ev_retry_{uuid4().hex[:8]}",
                    run_id=run_id,
                    source_type="article",
                    source_url="https://example.com/a",
                    source_title="a",
                    quote="Cursor leads.",
                    sanitized_text="Cursor leads.",
                    span={"dimension": "feature", "competitor_id": "Cursor"},
                    collected_by=new_step_id,
                    collected_at=now,
                    desensitized=True,
                ),
                EvidenceRecord(
                    id=f"ev_retry_{uuid4().hex[:8]}",
                    run_id=run_id,
                    source_type="article",
                    source_url="https://example.com/b",
                    source_title="b",
                    quote="Windsurf competitive.",
                    sanitized_text="Windsurf competitive.",
                    span={"dimension": "feature", "competitor_id": "Windsurf"},
                    collected_by=new_step_id,
                    collected_at=now,
                    desensitized=True,
                ),
            ]
            await session.flush()
            for row in evidence_rows:
                session.add(row)
            await session.flush()

            evidence_lookup = {row.id: row for row in evidence_rows}
            comparisons = [
                {
                    "dimension": "feature",
                    "cells": [
                        {
                            "competitor_id": "Cursor",
                            "stance": "leader",
                            "summary": "Cursor leads on repo context.",
                            "evidence_ids": [evidence_rows[0].id],
                        },
                        {
                            "competitor_id": "Windsurf",
                            "stance": "competitive",
                            "summary": "Windsurf is competitive.",
                            "evidence_ids": [evidence_rows[1].id],
                        },
                    ],
                }
            ]
            await persist_comparisons_for_step(
                session=session,
                run_id=run_id,
                step_id=old_step_id,
                comparisons=comparisons,
                evidence_lookup=evidence_lookup,
                competitors=["Cursor", "Windsurf"],
            )
            await persist_comparisons_for_step(
                session=session,
                run_id=run_id,
                step_id=new_step_id,
                comparisons=comparisons,
                evidence_lookup=evidence_lookup,
                competitors=["Cursor", "Windsurf"],
            )
            await session.flush()

            total_cells = int(
                (
                    await session.execute(
                        select(func.count())
                        .select_from(ComparisonCellRecord)
                        .where(ComparisonCellRecord.run_id == run_id)
                    )
                ).scalar_one()
            )
            assert total_cells == 4

            loaded = await load_comparisons_for_run(session=session, run_id=run_id)
            loaded_cells = [cell for group in loaded for cell in group["cells"]]
            assert len(loaded_cells) == 2
            assert {cell["step_id"] for cell in loaded_cells} == {new_step_id}

            await session.execute(delete(Run).where(Run.run_id == run_id))
            await session.commit()
    finally:
        await engine.dispose()
