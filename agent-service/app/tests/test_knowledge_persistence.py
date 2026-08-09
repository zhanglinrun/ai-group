from __future__ import annotations

import sys
from uuid import uuid4

import pytest
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from core.config import settings
from models.run import Run
from models.step import Step
from service.knowledge import load_knowledge_for_run, persist_knowledge_for_step

pytestmark = pytest.mark.skipif(
    sys.platform == "win32",
    reason="psycopg async engine is incompatible with Proactor loop on Windows CI.",
)


@pytest.mark.asyncio
async def test_knowledge_persistence_round_trip_and_latest_row() -> None:
    run_id = f"run_knowledge_{uuid4().hex[:8]}"
    first_step_id = f"step_knowledge_{uuid4().hex[:8]}"
    second_step_id = f"step_knowledge_{uuid4().hex[:8]}"
    engine = create_async_engine(settings.DATABASE_URL, pool_pre_ping=True)
    session_factory = async_sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)

    try:
        async with session_factory() as session:
            run = Run(
                run_id=run_id,
                user_query="knowledge persistence test",
                domain_hint="ai_coding_tools",
                reference_urls=[],
                status="running",
                target_roles=["pm"],
                competitors=["Cursor"],
            )
            first_step = Step(
                step_id=first_step_id,
                run_id=run_id,
                agent_name="analyst",
                status="completed",
                retry_count=0,
                payload={},
            )
            second_step = Step(
                step_id=second_step_id,
                run_id=run_id,
                agent_name="analyst",
                status="completed",
                retry_count=0,
                payload={},
            )
            session.add(run)
            session.add(first_step)
            session.add(second_step)
            await session.flush()

            await persist_knowledge_for_step(
                session=session,
                run_id=run_id,
                step_id=first_step_id,
                schema_version="schema_v0.2",
                features=[
                    {
                        "id": "feat_old",
                        "competitor_id": "Cursor",
                        "name": "Old feature",
                        "evidence_ids": ["ev_old"],
                    }
                ],
                pricings=[],
                personas=[],
                feedback=[],
                missing_reasons={"Cursor": ["feature:coverage_partial"]},
                coverage={"Cursor": {"feature": "partial"}},
            )
            await session.flush()
            await persist_knowledge_for_step(
                session=session,
                run_id=run_id,
                step_id=second_step_id,
                schema_version="schema_v0.2",
                features=[
                    {
                        "id": "feat_new",
                        "competitor_id": "Cursor",
                        "name": "New feature",
                        "evidence_ids": ["ev_new"],
                    }
                ],
                pricings=[
                    {
                        "id": "price_new",
                        "competitor_id": "Cursor",
                        "model": "unknown",
                        "evidence_ids": ["ev_price"],
                    }
                ],
                personas=[],
                feedback=[
                    {
                        "id": "fb_1",
                        "competitor_id": "Cursor",
                        "sentiment": "neutral",
                        "topic": "onboarding",
                        "summary": "Users request clearer onboarding docs.",
                        "evidence_ids": ["ev_fb"],
                    }
                ],
                missing_reasons={"Cursor": ["pricing:tier_details_missing"]},
                coverage={"Cursor": {"feature": "complete", "pricing": "partial"}},
            )
            await session.flush()

            loaded = await load_knowledge_for_run(session=session, run_id=run_id)

            assert loaded["schema_version"] == "schema_v0.2"
            assert loaded["features"][0]["id"] == "feat_new"
            assert loaded["pricings"][0]["model"] == "unknown"
            assert loaded["feedback"][0]["topic"] == "onboarding"
            assert loaded["missing_reasons"] == {"Cursor": ["pricing:tier_details_missing"]}
            assert loaded["coverage"] == {"Cursor": {"feature": "complete", "pricing": "partial"}}

            await session.execute(delete(Run).where(Run.run_id == run_id))
            await session.commit()
    finally:
        await engine.dispose()


@pytest.mark.asyncio
async def test_load_knowledge_for_run_returns_empty_payload_without_rows() -> None:
    engine = create_async_engine(settings.DATABASE_URL, pool_pre_ping=True)
    session_factory = async_sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)

    try:
        async with session_factory() as session:
            loaded = await load_knowledge_for_run(
                session=session,
                run_id=f"run_missing_{uuid4().hex[:8]}",
            )

            assert loaded == {
                "schema_version": "schema_v0.2",
                "features": [],
                "pricings": [],
                "personas": [],
                "feedback": [],
                "missing_reasons": {},
                "coverage": {},
            }
    finally:
        await engine.dispose()
