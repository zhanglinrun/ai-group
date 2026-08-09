from __future__ import annotations

from datetime import datetime, timedelta, timezone
import sys
from uuid import uuid4

import pytest
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app_main import _sweep_orphan_running_runs
from core.config import settings
from models.run import Run

pytestmark = pytest.mark.skipif(
    sys.platform == "win32",
    reason="psycopg async engine is incompatible with Proactor loop on Windows CI.",
)


@pytest.mark.asyncio
async def test_orphan_sweep_respects_grace_period(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "ORPHAN_RUN_SWEEP_GRACE_SECONDS", 300)
    grace_seconds = settings.ORPHAN_RUN_SWEEP_GRACE_SECONDS
    now = datetime.now(timezone.utc)
    stale_run_id = f"run_orphan_stale_{uuid4().hex[:8]}"
    fresh_run_id = f"run_orphan_fresh_{uuid4().hex[:8]}"
    engine = create_async_engine(settings.DATABASE_URL, pool_pre_ping=True)
    session_factory = async_sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)

    emitted: list[str] = []

    async def _capture_emit(*, run_id: str, event_type: object, **kwargs: object) -> None:
        del event_type, kwargs
        emitted.append(run_id)

    monkeypatch.setattr("app_main.emit_run_event", _capture_emit)
    monkeypatch.setattr("app_main.get_session_factory", lambda: session_factory)

    try:
        async with session_factory() as session:
            session.add(
                Run(
                    run_id=stale_run_id,
                    user_query="stale orphan sweep test",
                    domain_hint=None,
                    reference_urls=[],
                    status="running",
                    target_roles=["pm"],
                    competitors=["comp_a"],
                    started_at=now - timedelta(seconds=grace_seconds + 60),
                )
            )
            session.add(
                Run(
                    run_id=fresh_run_id,
                    user_query="fresh orphan sweep test",
                    domain_hint=None,
                    reference_urls=[],
                    status="running",
                    target_roles=["pm"],
                    competitors=["comp_b"],
                    started_at=now - timedelta(seconds=grace_seconds - 30),
                )
            )
            await session.commit()

        await _sweep_orphan_running_runs()

        async with session_factory() as session:
            stale_status = (
                await session.execute(select(Run.status).where(Run.run_id == stale_run_id))
            ).scalar_one()
            fresh_status = (
                await session.execute(select(Run.status).where(Run.run_id == fresh_run_id))
            ).scalar_one()

        assert stale_status == "failed"
        assert fresh_status == "running"
        assert stale_run_id in emitted
        assert fresh_run_id not in emitted
    finally:
        async with session_factory() as session:
            await session.execute(delete(Run).where(Run.run_id.in_([stale_run_id, fresh_run_id])))
            await session.commit()
        await engine.dispose()
