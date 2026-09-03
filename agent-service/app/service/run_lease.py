"""Database-backed fencing lease for LangGraph Run execution."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

from sqlalchemy import or_, update

from core.config import settings
from db.engine import get_session_factory
from models.run import Run
from utils.logger import get_logger

log = get_logger("service.run_lease")


def _lease_seconds() -> int:
    value = int(getattr(settings, "RUN_EXECUTION_LEASE_SECONDS", 600))
    return max(60, value)


async def claim_run_execution(*, run_id: str, owner_token: str) -> bool:
    """Atomically claim a running Run, fencing expired/stale workers."""
    now = datetime.now(timezone.utc)
    lease_until = now + timedelta(seconds=_lease_seconds())
    session_factory = get_session_factory()
    async with session_factory() as session:
        result = await session.execute(
            update(Run)
            .where(
                Run.run_id == run_id,
                Run.status == "running",
                or_(
                    Run.execution_owner_token.is_(None),
                    Run.execution_lease_until.is_(None),
                    Run.execution_lease_until < now,
                    Run.execution_owner_token == owner_token,
                ),
            )
            .values(
                execution_owner_token=owner_token,
                execution_lease_until=lease_until,
            )
            .execution_options(synchronize_session=False)
        )
        await session.commit()
        claimed = int(result.rowcount or 0) == 1
    if not claimed:
        log.info("run.lease.claim_rejected", run_id=run_id)
    return claimed


async def renew_run_execution(*, run_id: str, owner_token: str) -> bool:
    """Extend a lease only when the caller still owns its fencing token."""
    now = datetime.now(timezone.utc)
    lease_until = now + timedelta(seconds=_lease_seconds())
    session_factory = get_session_factory()
    async with session_factory() as session:
        result = await session.execute(
            update(Run)
            .where(
                Run.run_id == run_id,
                Run.status == "running",
                Run.execution_owner_token == owner_token,
            )
            .values(execution_lease_until=lease_until)
            .execution_options(synchronize_session=False)
        )
        await session.commit()
        return int(result.rowcount or 0) == 1


async def release_run_execution(*, run_id: str, owner_token: str | None) -> bool:
    """Clear a lease without touching a newer worker's token."""
    if not owner_token:
        return False
    session_factory = get_session_factory()
    async with session_factory() as session:
        result = await session.execute(
            update(Run)
            .where(Run.run_id == run_id, Run.execution_owner_token == owner_token)
            .values(execution_owner_token=None, execution_lease_until=None)
            .execution_options(synchronize_session=False)
        )
        await session.commit()
        return int(result.rowcount or 0) == 1
