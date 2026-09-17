"""Authorize one LLM round-trip with a balance gate, then debit actual tokens."""

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
    charge_micro_points,
    quota_client,
)
from utils.logger import get_logger

log = get_logger("service.billing_meter")

_MIN_CALL_HOLD_MICRO_POINTS = 1
STATUS_OPEN = "OPEN"
STATUS_DEBITING = "DEBITING"
STATUS_DEBITED = "DEBITED"
STATUS_SKIPPED = "SKIPPED"
STATUS_PENDING = "PENDING_RECONCILIATION"


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
    request_id: str | None
    owner_user_id: int
    attempt_id: str | None = None
    reservation: object | None = None


def estimate_call_hold_micro_points(
    *,
    prompt_tokens: int,
    output_tokens: int,
) -> int:
    # Cap the pre-call gate so one oversized prompt cannot block a truncated call.
    # Actual settlement uses provider usage, not this cap.
    capped_prompt = min(max(0, prompt_tokens), 24_000)
    capped_output = min(max(0, output_tokens), 8_192)
    return max(_MIN_CALL_HOLD_MICRO_POINTS, charge_micro_points(capped_prompt, capped_output))


def _unmetered_hold(*, run_id: str | None, estimated: int, owner_user_id: int = 0) -> LLMCallHold:
    return LLMCallHold(
        metered=False,
        run_id=run_id,
        estimated_micro_points=estimated,
        request_id=None,
        owner_user_id=owner_user_id,
        attempt_id=None,
        reservation=None,
    )


async def _persist_open_attempt(
    *,
    run_id: str,
    request_id: str,
    owner_user_id: int,
    estimated_micro_points: int,
) -> str:
    attempt_id = uuid4().hex
    session_factory = get_session_factory()
    async with session_factory() as session:
        session.add(
            LLMBillingAttempt(
                attempt_id=attempt_id,
                run_id=run_id,
                reservation_id=None,
                request_id=request_id,
                owner_user_id=owner_user_id,
                estimated_micro_points=estimated_micro_points,
                status=STATUS_OPEN,
            )
        )
        await session.commit()
    return attempt_id


async def _update_attempt(
    *,
    attempt_id: str,
    status: str,
    actual_micro_points: int | None = None,
    reservation_id: str | None = None,
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
        if reservation_id:
            attempt.reservation_id = reservation_id
        attempt.last_error = error[:2000] if error else None
        if increment_retry:
            attempt.retry_count = int(attempt.retry_count or 0) + 1
        if status in {STATUS_DEBITED, STATUS_SKIPPED}:
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
    """Gate one model call against Member available quota, or raise QuotaExhaustedError.

    Anonymous / local-dev runs (user 0 or no internal token) skip Member.
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
        available = await quota_client.available_micro_points(user_id=owner_user_id)
    except QuotaExhaustedError:
        raise
    except Exception as exc:
        log.warning("billing.meter.available_lookup_failed", run_id=run_id, error=str(exc))
        raise BillingUnavailableError("Member quota authorization is unavailable") from exc
    if available is None:
        raise BillingUnavailableError("Member quota authorization is unavailable")
    if available < estimated:
        log.info(
            "billing.meter.quota_exhausted",
            run_id=run_id,
            estimated_micro_points=estimated,
            available_micro_points=available,
            owner_user_id=owner_user_id,
        )
        raise QuotaExhaustedError("积分不足")

    request_id = f"agent:{run_id}:call:{uuid4().hex}"
    try:
        attempt_id = await _persist_open_attempt(
            run_id=run_id,
            request_id=request_id,
            owner_user_id=owner_user_id,
            estimated_micro_points=estimated,
        )
    except Exception as exc:
        log.error("billing.meter.persist_attempt_failed", run_id=run_id, error=str(exc))
        raise BillingUnavailableError("Billing attempt could not be persisted") from exc

    return LLMCallHold(
        metered=True,
        run_id=run_id,
        estimated_micro_points=estimated,
        request_id=request_id,
        owner_user_id=owner_user_id,
        attempt_id=attempt_id,
        reservation=None,
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


async def settle_llm_call_hold(
    hold: LLMCallHold,
    *,
    actual_micro_points: int | None = None,
    usage_known: bool = True,
    provider_called: bool = True,
) -> None:
    """Settle one call. Missing usage is never treated as a free call."""
    if not provider_called:
        if hold.attempt_id:
            await _update_attempt(attempt_id=hold.attempt_id, status=STATUS_SKIPPED, actual_micro_points=0)
        return
    if not usage_known:
        if hold.attempt_id:
            await _update_attempt(
                attempt_id=hold.attempt_id,
                status=STATUS_PENDING,
                error="provider usage is unknown; manual/provider reconciliation required",
            )
        return

    actual = max(0, int(actual_micro_points or 0))
    if hold.run_id:
        await _add_consumed_micro_points(run_id=hold.run_id, actual_micro_points=actual)
    if not hold.metered or not hold.request_id:
        if hold.attempt_id:
            await _update_attempt(
                attempt_id=hold.attempt_id,
                status=STATUS_SKIPPED if actual <= 0 else STATUS_DEBITED,
                actual_micro_points=actual,
            )
        return
    if actual <= 0:
        if hold.attempt_id:
            await _update_attempt(attempt_id=hold.attempt_id, status=STATUS_SKIPPED, actual_micro_points=0)
        return
    try:
        if hold.attempt_id:
            await _update_attempt(
                attempt_id=hold.attempt_id,
                status=STATUS_DEBITING,
                actual_micro_points=actual,
            )
        debit = await quota_client.debit(
            user_id=hold.owner_user_id,
            amount_micro_points=actual,
            run_id=hold.run_id or hold.request_id,
            request_id=hold.request_id,
            trace_id=hold.run_id or hold.request_id,
        )
        if hold.attempt_id:
            await _update_attempt(
                attempt_id=hold.attempt_id,
                status=STATUS_DEBITED,
                actual_micro_points=actual,
                reservation_id=debit.debit_id,
            )
    except Exception as exc:
        log.warning(
            "billing.meter.settle_failed",
            run_id=hold.run_id,
            request_id=hold.request_id,
            error=str(exc),
        )
        if hold.attempt_id:
            try:
                await _update_attempt(
                    attempt_id=hold.attempt_id,
                    status=STATUS_PENDING,
                    actual_micro_points=actual,
                    error=str(exc),
                    increment_retry=True,
                )
            except Exception as persist_exc:
                log.error(
                    "billing.meter.persist_settlement_failure_failed",
                    run_id=hold.run_id,
                    error=str(persist_exc),
                )
