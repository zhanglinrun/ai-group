"""Per-call Member hold: authorize one LLM round-trip, then confirm actual tokens."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import uuid4

from sqlalchemy import func, update

from db.engine import get_session_factory
from models.llm_billing_attempt import LLMBillingAttempt
from models.run import Run
from service.billing import (
    BillingUnavailableError,
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
    attempt_id: str | None = None


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
        attempt_id=None,
    )


async def _persist_reserved_attempt(
    *,
    run_id: str,
    reservation: Reservation,
    estimated_micro_points: int,
) -> str:
    attempt_id = uuid4().hex
    session_factory = get_session_factory()
    async with session_factory() as session:
        session.add(
            LLMBillingAttempt(
                attempt_id=attempt_id,
                run_id=run_id,
                reservation_id=reservation.reservation_id,
                request_id=reservation.request_id,
                owner_user_id=reservation.user_id,
                estimated_micro_points=estimated_micro_points,
                status="RESERVED",
            )
        )
        await session.commit()
    return attempt_id


async def _update_attempt(
    *,
    attempt_id: str,
    status: str,
    actual_micro_points: int | None = None,
    error: str | None = None,
    increment_retry: bool = False,
) -> None:
    session_factory = get_session_factory()
    async with session_factory() as session:
        attempt = await session.get(LLMBillingAttempt, attempt_id)
        if attempt is None:
            return
        attempt.status = status
        if actual_micro_points is not None:
            attempt.actual_micro_points = max(0, int(actual_micro_points))
        attempt.last_error = error[:2000] if error else None
        if increment_retry:
            attempt.retry_count = int(attempt.retry_count or 0) + 1
        if status in {"CONFIRMED", "RELEASED"}:
            attempt.settled_at = datetime.now(timezone.utc)
        await session.commit()


async def _load_run_owner(run_id: str) -> int | None:
    try:
        session_factory = get_session_factory()
    except RuntimeError as exc:
        raise BillingUnavailableError("Agent billing database is not initialized") from exc
    try:
        async with session_factory() as session:
            run = await session.get(Run, run_id)
            if run is None:
                raise BillingUnavailableError(f"Run {run_id} does not exist")
            return int(run.owner_user_id or 0)
    except Exception as exc:
        log.warning("billing.meter.load_owner_failed", run_id=run_id, error=str(exc))
        if isinstance(exc, BillingUnavailableError):
            raise
        raise BillingUnavailableError("Agent billing owner lookup is unavailable") from exc


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
        raise BillingUnavailableError("Agent billing owner is unavailable")
    if owner_user_id == 0:
        return _unmetered_hold(run_id=run_id, estimated=estimated, owner_user_id=owner_user_id)

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
        raise BillingUnavailableError("Member quota authorization is unavailable") from exc

    try:
        attempt_id = await _persist_reserved_attempt(
            run_id=run_id,
            reservation=reservation,
            estimated_micro_points=estimated,
        )
    except Exception as exc:
        log.error("billing.meter.persist_reservation_failed", run_id=run_id, error=str(exc))
        try:
            await quota_client.release(reservation, trace_id=run_id)
        except Exception as release_exc:
            log.error("billing.meter.release_after_persist_failure_failed", run_id=run_id, error=str(release_exc))
        raise BillingUnavailableError("Billing reservation could not be persisted") from exc

    return LLMCallHold(
        metered=True,
        run_id=run_id,
        estimated_micro_points=reservation.amount_micro_points,
        reservation=reservation,
        owner_user_id=owner_user_id,
        attempt_id=attempt_id,
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
        if hold.attempt_id:
            await _update_attempt(
                attempt_id=hold.attempt_id,
                status="RELEASING" if actual <= 0 else "CONFIRMING",
                actual_micro_points=actual,
            )
        if actual <= 0:
            await quota_client.release(reservation, trace_id=hold.run_id or reservation.request_id)
            if hold.attempt_id:
                await _update_attempt(attempt_id=hold.attempt_id, status="RELEASED", actual_micro_points=0)
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
        if hold.attempt_id:
            await _update_attempt(
                attempt_id=hold.attempt_id,
                status="CONFIRMED",
                actual_micro_points=confirm_amount,
            )
    except Exception as exc:
        log.warning(
            "billing.meter.settle_failed",
            run_id=hold.run_id,
            freeze_id=reservation.reservation_id,
            error=str(exc),
        )
        if hold.attempt_id:
            try:
                await _update_attempt(
                    attempt_id=hold.attempt_id,
                    status="PENDING_RECONCILIATION",
                    actual_micro_points=actual,
                    error=str(exc),
                    increment_retry=True,
                )
            except Exception as persist_exc:
                log.error("billing.meter.persist_settlement_failure_failed", run_id=hold.run_id, error=str(persist_exc))
