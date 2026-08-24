"""Per-call Member hold: authorize one LLM round-trip, then confirm actual tokens."""

from __future__ import annotations

from dataclasses import dataclass
from uuid import uuid4

from sqlalchemy import func, update

from db.engine import get_session_factory
from models.run import Run
from service.billing import (
    QuotaExhaustedError,
    Reservation,
    charge_micro_points,
    quota_client,
)
from utils.logger import get_logger

log = get_logger("service.billing_meter")

_MIN_CALL_HOLD_MICRO_POINTS = 1


def current_run_id() -> str | None:
    try:
        from structlog.contextvars import get_contextvars

        value = get_contextvars().get("run_id")
    except Exception:
        return None
    return value if isinstance(value, str) and value.strip() else None


@dataclass(frozen=True, slots=True)
class LLMCallHold:
    metered: bool
    run_id: str | None
    estimated_micro_points: int
    reservation: Reservation | None
    owner_user_id: int


def estimate_call_hold_micro_points(
    *,
    prompt_tokens: int,
    output_tokens: int,
) -> int:
    # Cap the pre-call authorization so one oversized prompt cannot occupy an
    # unbounded slice of the user's wallet for a single provider round-trip.
    capped_prompt = min(max(0, prompt_tokens), 24_000)
    capped_output = min(max(0, output_tokens), 8_192)
    return max(_MIN_CALL_HOLD_MICRO_POINTS, charge_micro_points(capped_prompt, capped_output))


def _unmetered_hold(*, run_id: str | None, estimated: int, owner_user_id: int = 0) -> LLMCallHold:
    return LLMCallHold(
        metered=False,
        run_id=run_id,
        estimated_micro_points=estimated,
        reservation=None,
        owner_user_id=owner_user_id,
    )


async def _load_run_owner(run_id: str) -> int | None:
    try:
        session_factory = get_session_factory()
    except RuntimeError:
        return None
    try:
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                return None
            return int(run.owner_user_id or 0)
    except Exception as exc:
        log.warning("billing.meter.load_owner_failed", run_id=run_id, error=str(exc))
        return None


async def acquire_llm_call_hold(*, estimated_micro_points: int) -> LLMCallHold:
    """Authorize one model call against Member, or raise QuotaExhaustedError.

    Member has no one-shot debit API, so this hold lasts only for the current
    provider round-trip. Anonymous / local-dev runs (user 0 or no internal
    token) skip Member and still proceed.
    """
    run_id = current_run_id()
    estimated = max(_MIN_CALL_HOLD_MICRO_POINTS, int(estimated_micro_points))
    if not run_id:
        return _unmetered_hold(run_id=None, estimated=estimated)

    owner_user_id = await _load_run_owner(run_id)
    if owner_user_id is None:
        return _unmetered_hold(run_id=run_id, estimated=estimated)

    try:
        reservation = await quota_client.reserve(
            user_id=owner_user_id,
            amount_micro_points=estimated,
            run_id=run_id,
            trace_id=run_id,
            request_id=f"agent:{run_id}:call:{uuid4().hex}",
        )
    except QuotaExhaustedError:
        log.info(
            "billing.meter.quota_exhausted",
            run_id=run_id,
            estimated_micro_points=estimated,
            owner_user_id=owner_user_id,
        )
        raise
    except Exception as exc:
        log.warning("billing.meter.acquire_failed", run_id=run_id, error=str(exc))
        return _unmetered_hold(run_id=run_id, estimated=estimated, owner_user_id=owner_user_id)

    return LLMCallHold(
        metered=True,
        run_id=run_id,
        estimated_micro_points=reservation.amount_micro_points,
        reservation=reservation,
        owner_user_id=owner_user_id,
    )


async def _add_consumed_micro_points(*, run_id: str, actual_micro_points: int) -> None:
    actual = max(0, int(actual_micro_points))
    if actual <= 0:
        return
    try:
        session_factory = get_session_factory()
        async with session_factory() as session:
            await session.execute(
                update(Run)
                .where(Run.run_id == run_id)
                .values(
                    consumed_micro_points=func.greatest(0, Run.consumed_micro_points + actual)
                )
            )
            await session.commit()
    except Exception as exc:
        log.warning("billing.meter.consumed_update_failed", run_id=run_id, error=str(exc))


async def settle_llm_call_hold(hold: LLMCallHold, *, actual_micro_points: int) -> None:
    actual = max(0, int(actual_micro_points))
    reservation = hold.reservation
    if hold.run_id:
        await _add_consumed_micro_points(run_id=hold.run_id, actual_micro_points=actual)
    if not hold.metered or reservation is None:
        return
    try:
        if actual <= 0:
            await quota_client.release(reservation, trace_id=hold.run_id or reservation.request_id)
            return
        confirm_amount = min(actual, max(0, reservation.amount_micro_points))
        if confirm_amount < actual:
            log.warning(
                "billing.meter.confirm_clipped",
                run_id=hold.run_id,
                actual_micro_points=actual,
                confirm_micro_points=confirm_amount,
            )
        await quota_client.confirm(
            reservation,
            actual_micro_points=confirm_amount,
            trace_id=hold.run_id or reservation.request_id,
        )
    except Exception as exc:
        log.warning(
            "billing.meter.settle_failed",
            run_id=hold.run_id,
            freeze_id=reservation.reservation_id,
            error=str(exc),
        )
