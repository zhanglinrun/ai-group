from __future__ import annotations

from datetime import datetime, timezone
import sys
from uuid import uuid4

import pytest
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from core.config import settings
from models.conclusion import ConclusionEvidenceLink, ConclusionRecord
from models.evidence import EvidenceRecord
from models.run import Run
from models.step import Step
from service.conclusion.persistence import (
    load_conclusions_for_run,
    persist_comparison_conclusions_for_step,
    persist_conclusions_for_step,
)

pytestmark = pytest.mark.skipif(
    sys.platform == "win32",
    reason="psycopg async engine is incompatible with Proactor loop on Windows CI.",
)


@pytest.mark.asyncio
async def test_conclusions_persistence_and_cascade() -> None:
    run_id = f"run_conclusions_{uuid4().hex[:8]}"
    step_id = f"step_conclusions_{uuid4().hex[:8]}"
    engine = create_async_engine(settings.DATABASE_URL, pool_pre_ping=True)
    session_factory = async_sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    now = datetime.now(timezone.utc)

    try:
        async with session_factory() as session:
            run = Run(
                run_id=run_id,
                user_query="conclusion persistence test",
                domain_hint="ai_coding_tools",
                reference_urls=[],
                status="running",
                target_roles=["pm"],
                competitors=["comp_cursor", "comp_windsurf"],
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
                    id=f"ev_test_{uuid4().hex[:8]}",
                    run_id=run_id,
                    source_type="article",
                    source_url="https://example.com/cursor-feature",
                    source_title="cursor feature article",
                    quote="Cursor keeps repository context stronger.",
                    sanitized_text="Cursor keeps repository context stronger.",
                    span={"dimension": "feature", "competitor_id": "comp_cursor"},
                    collected_by=step_id,
                    collected_at=now,
                    desensitized=True,
                ),
                EvidenceRecord(
                    id=f"ev_test_{uuid4().hex[:8]}",
                    run_id=run_id,
                    source_type="article",
                    source_url="https://example.com/windsurf-pricing",
                    source_title="windsurf pricing article",
                    quote="Windsurf has lower starter pricing.",
                    sanitized_text="Windsurf has lower starter pricing.",
                    span={"dimension": "pricing", "competitor_id": "comp_windsurf"},
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

            persisted = await persist_conclusions_for_step(
                session=session,
                run_id=run_id,
                step_id=step_id,
                insights=[
                    {
                        "dimension": "feature",
                        "finding": "Cursor repository context is more stable.",
                        "confidence": "high",
                        "evidence_ids": [evidence_rows[0].id],
                    },
                    {
                        "dimension": "pricing",
                        "finding": "Windsurf starter tier lowers adoption friction.",
                        "confidence": "medium",
                        "evidence_ids": [evidence_rows[1].id],
                    },
                ],
                evidence_lookup={row.id: row for row in evidence_rows},
                risk_flags=["feature_gap", "pricing_volatility"],
            )
            await session.flush()

            conclusion_count = int(
                (
                    await session.execute(
                        select(func.count()).select_from(ConclusionRecord).where(ConclusionRecord.run_id == run_id)
                    )
                ).scalar_one()
            )
            link_count = int(
                (
                    await session.execute(
                        select(func.count())
                        .select_from(ConclusionEvidenceLink)
                        .join(ConclusionRecord, ConclusionRecord.conclusion_id == ConclusionEvidenceLink.conclusion_id)
                        .where(ConclusionRecord.run_id == run_id)
                    )
                ).scalar_one()
            )

            assert len(persisted) == 2
            assert conclusion_count == 2
            assert link_count == 2

            loaded = await load_conclusions_for_run(session=session, run_id=run_id)
            assert len(loaded) == 2
            assert all(item["evidence_ids"] for item in loaded)

            first_conclusion_id = persisted[0].conclusion_id
            await session.delete(persisted[0])
            await session.flush()
            first_link_count = int(
                (
                    await session.execute(
                        select(func.count())
                        .select_from(ConclusionEvidenceLink)
                        .where(ConclusionEvidenceLink.conclusion_id == first_conclusion_id)
                    )
                ).scalar_one()
            )
            assert first_link_count == 0

            await session.execute(delete(Run).where(Run.run_id == run_id))
            await session.commit()
    finally:
        await engine.dispose()


@pytest.mark.asyncio
async def test_comparison_conclusion_backfill_persistence() -> None:
    run_id = f"run_conclusion_backfill_{uuid4().hex[:8]}"
    step_id = f"step_conclusion_backfill_{uuid4().hex[:8]}"
    engine = create_async_engine(settings.DATABASE_URL, pool_pre_ping=True)
    session_factory = async_sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    now = datetime.now(timezone.utc)

    try:
        async with session_factory() as session:
            run = Run(
                run_id=run_id,
                user_query="comparison conclusion backfill test",
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
                    id=f"ev_test_{uuid4().hex[:8]}",
                    run_id=run_id,
                    source_type="article",
                    source_url="https://example.com/cursor-pricing",
                    source_title="cursor pricing article",
                    quote="Cursor has stronger enterprise packaging.",
                    sanitized_text="Cursor has stronger enterprise packaging.",
                    span={"dimension": "pricing", "competitor_id": "Cursor"},
                    collected_by=step_id,
                    collected_at=now,
                    desensitized=True,
                ),
                EvidenceRecord(
                    id=f"ev_test_{uuid4().hex[:8]}",
                    run_id=run_id,
                    source_type="article",
                    source_url="https://example.com/windsurf-pricing",
                    source_title="windsurf pricing article",
                    quote="Windsurf has less mature enterprise pricing evidence.",
                    sanitized_text="Windsurf has less mature enterprise pricing evidence.",
                    span={"dimension": "pricing", "competitor_id": "Windsurf"},
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

            persisted = await persist_comparison_conclusions_for_step(
                session=session,
                run_id=run_id,
                step_id=step_id,
                comparisons=[
                    {
                        "dimension": "pricing",
                        "cells": [
                            {
                                "competitor_id": "Cursor",
                                "stance": "leader",
                                "summary": "Cursor has stronger enterprise packaging.",
                                "evidence_ids": [evidence_rows[0].id],
                            },
                            {
                                "competitor_id": "Windsurf",
                                "stance": "laggard",
                                "summary": "Windsurf has less mature enterprise pricing evidence.",
                                "evidence_ids": [evidence_rows[1].id],
                            },
                        ],
                    }
                ],
                evidence_lookup={row.id: row for row in evidence_rows},
                competitors=["Cursor", "Windsurf"],
                covered_sections=set(),
                risk_flags=["pricing_uncertainty"],
            )
            await session.flush()

            assert len(persisted) == 1
            assert persisted[0].section == "pricing"
            loaded = await load_conclusions_for_run(session=session, run_id=run_id)
            assert len(loaded) == 1
            assert loaded[0]["evidence_ids"] == [row.id for row in evidence_rows]

            skipped = await persist_comparison_conclusions_for_step(
                session=session,
                run_id=run_id,
                step_id=step_id,
                comparisons=[
                    {
                        "dimension": "pricing",
                        "cells": [
                            {
                                "competitor_id": "Cursor",
                                "stance": "leader",
                                "summary": "Covered section should not duplicate.",
                                "evidence_ids": [evidence_rows[0].id],
                            },
                            {
                                "competitor_id": "Windsurf",
                                "stance": "competitive",
                                "summary": "Covered section should not duplicate.",
                                "evidence_ids": [evidence_rows[1].id],
                            },
                        ],
                    }
                ],
                evidence_lookup={row.id: row for row in evidence_rows},
                competitors=["Cursor", "Windsurf"],
                covered_sections={"pricing"},
                risk_flags=[],
            )
            assert skipped == []

            await session.execute(delete(Run).where(Run.run_id == run_id))
            await session.commit()
    finally:
        await engine.dispose()
