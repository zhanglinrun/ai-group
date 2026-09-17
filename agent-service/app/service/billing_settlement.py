from __future__ import annotations

import asyncio
from collections.abc import Sequence
from datetime import datetime, timezone

from sqlalchemy import select

from core.config import settings
from db.engine import get_session_factory
from models.llm_call import LLMCall
from models.llm_billing_attempt import LLMBillingAttempt
from models.run import Run
from models.step import Step
from service.billing import (
    FreezeSlice,
    Reservation,
    allocate_actual_across_slices,
    freeze_slices_from_mapping,
    quota_client,
    reservation_request_id,
)
from utils.logger import get_logger

log = get_logger("service.billing_settlement")

TERMINAL_RUN_STATUSES: tuple[str, ...] = ("completed", "degraded", "failed", "cancelled")
UNSETTLED_BILLING_STATUSES: tuple[str, ...] = ("RESERVED", "PENDING_RECONCILIATION")
OPEN_ATTEMPT_STATUSES: tuple[str, ...] = (
    "OPEN",
    "DEBITING",
    "RESERVED",
    "CONFIRMING",
    "RELEASING",
    "PENDING_RECONCILIATION",
)
LEGACY_FREEZE_ATTEMPT_STATUSES: frozenset[str] = frozenset(
    {"RESERVED", "CONFIRMING", "RELEASING"}
)


def is_unsettled_billing(billing_status: str | None) -> bool:
    return billing_status in UNSETTLED_BILLING_STATUSES


def _is_legacy_freeze_attempt(attempt: object) -> bool:
    status = str(getattr(attempt, "status", "") or "")
    if status in LEGACY_FREEZE_ATTEMPT_STATUSES:
        return True
    reservation_id = str(getattr(attempt, "reservation_id", "") or "")
    return status == "PENDING_RECONCILIATION" and bool(reservation_id)


async def reconcile_llm_billing_attempts(*, run_id: str, limit: int = 100) -> int:
    """Retry durable per-call Member settlements and return unresolved count.

    Debit/confirm/release are idempotent at Member. Unknown provider usage is
    kept pending rather than settled as zero.
    """
    session_factory = get_session_factory()
    async with session_factory() as session:
        raw_attempts = (
            await session.execute(
                select(LLMBillingAttempt)
                .where(
                    LLMBillingAttempt.run_id == run_id,
                    LLMBillingAttempt.status.in_(OPEN_ATTEMPT_STATUSES),
                )
                .order_by(LLMBillingAttempt.created_at.asc())
                .limit(max(1, int(limit)))
            )
            ).scalars().all()

        # Some lightweight unit-test sessions return a generic row collection
        # for every SELECT.  Ignore rows that are not billing-attempt records;
        # a real SQLAlchemy session always returns LLMBillingAttempt instances.
        attempts = [
            item for item in raw_attempts
            if hasattr(item, "actual_micro_points") and hasattr(item, "reservation_id")
        ]
    unresolved = 0
    for attempt in attempts:
        actual = attempt.actual_micro_points
        if actual is None:
            unresolved += 1
            await _mark_attempt_pending(
                attempt.attempt_id,
                "provider usage is unknown; manual/provider reconciliation required",
            )
            continue

        user_id = int(attempt.owner_user_id or 0)
        try:
            if _is_legacy_freeze_attempt(attempt):
                reservation = Reservation(
                    reservation_id=str(attempt.reservation_id),
                    amount_micro_points=int(attempt.estimated_micro_points or 0),
                    request_id=attempt.request_id,
                    user_id=user_id,
                )
                if int(actual) <= 0:
                    await quota_client.release(reservation, trace_id=run_id)
                    await _mark_attempt_final(attempt.attempt_id, "RELEASED", int(actual))
                else:
                    await quota_client.confirm(
                        reservation,
                        actual_micro_points=min(int(actual), reservation.amount_micro_points),
                        trace_id=run_id,
                    )
                    await _mark_attempt_final(attempt.attempt_id, "CONFIRMED", int(actual))
            elif int(actual) <= 0:
                await _mark_attempt_final(attempt.attempt_id, "SKIPPED", int(actual))
            else:
                debit = await quota_client.debit(
                    user_id=user_id,
                    amount_micro_points=int(actual),
                    run_id=run_id,
                    request_id=attempt.request_id,
                    trace_id=run_id,
                )
                await _mark_attempt_final(
                    attempt.attempt_id,
                    "DEBITED",
                    int(actual),
                    reservation_id=debit.debit_id,
                )
        except Exception as exc:
            unresolved += 1
            await _mark_attempt_pending(attempt.attempt_id, str(exc))
    return unresolved


async def _mark_attempt_pending(attempt_id: str, error: str) -> None:
    session_factory = get_session_factory()
    async with session_factory() as session:
        attempt = await session.get(LLMBillingAttempt, attempt_id)
        if attempt is None:
            return
        attempt.status = "PENDING_RECONCILIATION"
        attempt.last_error = error[:2000]
        attempt.retry_count = int(attempt.retry_count or 0) + 1
        await session.commit()


async def _mark_attempt_final(
    attempt_id: str,
    status: str,
    actual: int,
    reservation_id: str | None = None,
) -> None:
    session_factory = get_session_factory()
    async with session_factory() as session:
        attempt = await session.get(LLMBillingAttempt, attempt_id)
        if attempt is None:
            return
        attempt.status = status
        attempt.actual_micro_points = max(0, int(actual))
        if reservation_id:
            attempt.reservation_id = reservation_id
        attempt.last_error = None
        attempt.settled_at = datetime.now(timezone.utc)
        await session.commit()


async def settle_run_billing(*, run_id: str, terminal_status: str) -> str | None:
    """Snapshot token usage after a run reaches a terminal status.

    Pay-as-you-go calls already debited themselves. This pass only records
    consumed_micro_points and marks SETTLED, or PENDING_RECONCILIATION when
    provider usage is missing or a debit is still open.
    """
    unresolved_attempts = await reconcile_llm_billing_attempts(run_id=run_id)
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None or run.billing_status == "SETTLED":
            return None if run is None else run.billing_status
        llm_rows = (
            await session.execute(
                select(LLMCall)
                .join(Step, LLMCall.step_id == Step.step_id)
                .where(Step.run_id == run_id)
            )
        ).scalars().all()
        # Supervisor state gates and QA guardrails also emit trace-only pseudo
        # records.  They intentionally have no token usage and must not keep a
        # real Member reservation in pending reconciliation.  Only records with
        # a non-pseudo prompt hash represent an actual provider call.
        billable_llm_rows = [row for row in llm_rows if row.prompt_hash != "pseudo_response"]
        actual = sum(max(0, int(row.charged_micro_points or 0)) for row in billable_llm_rows)
        unknown_usage_count = sum(
            1
            for row in billable_llm_rows
            if not row.error
            and (row.prompt_tokens is None or row.completion_tokens is None)
        )
        user_id = int(run.owner_user_id or 0)
        slices = freeze_slices_from_mapping(
            run_id=run_id,
            reservation_id=run.reservation_id,
            reserved_micro_points=int(run.reserved_micro_points or 0),
            billing_reservations=getattr(run, "billing_reservations", None),
        )
        if unknown_usage_count and slices:
            # A provider that omits usage must never be charged from an estimate.
            run.consumed_micro_points = actual
            run.billing_status = "PENDING_RECONCILIATION"
            run.billing_error = (
                f"{unknown_usage_count} successful LLM call(s) did not return token usage"
            )
            await session.commit()
            log.warning(
                "billing.usage_missing",
                run_id=run_id,
                status=terminal_status,
                unknown_usage_count=unknown_usage_count,
            )
            return run.billing_status
        if not slices:
            # Pay-as-you-go: each LLM call settles itself.  If an Agent process
            # died between the provider response and Member confirm/release,
            # the durable attempt sweep above keeps the Run pending instead of
            # falsely marking it as settled.
            run.consumed_micro_points = actual
            if unresolved_attempts:
                run.billing_status = "PENDING_RECONCILIATION"
                run.billing_error = f"{unresolved_attempts} LLM billing attempt(s) pending reconciliation"
            else:
                run.billing_status = "SETTLED"
                run.billing_error = None
            await session.commit()
            return run.billing_status
        try:
            assignments, overage = allocate_actual_across_slices(slices, actual)
            if overage > 0:
                extra = await quota_client.reserve(
                    user_id=user_id,
                    amount_micro_points=overage,
                    run_id=run_id,
                    trace_id=run_id,
                    request_id=reservation_request_id(run_id, "overage"),
                )
                extra_slice = FreezeSlice(
                    freeze_id=extra.reservation_id,
                    request_id=extra.request_id,
                    amount_micro_points=extra.amount_micro_points,
                )
                slices.append(extra_slice)
                assignments.append((extra_slice, overage))
                run.billing_reservations = [item.to_record() for item in slices]
                run.reserved_micro_points = int(run.reserved_micro_points or 0) + extra.amount_micro_points
                if not run.reservation_id:
                    run.reservation_id = extra.reservation_id
            for slice_, charged in assignments:
                await quota_client.confirm(
                    Reservation(
                        reservation_id=slice_.freeze_id,
                        amount_micro_points=slice_.amount_micro_points,
                        request_id=slice_.request_id,
                        user_id=user_id,
                    ),
                    actual_micro_points=charged,
                    trace_id=run_id,
                )
            run.consumed_micro_points = actual
            run.billing_status = "SETTLED"
            run.billing_error = None
        except Exception as exc:
            run.consumed_micro_points = actual
            run.billing_status = "PENDING_RECONCILIATION"
            run.billing_error = str(exc)[:2000]
            log.warning(
                "billing.settlement.pending",
                run_id=run_id,
                status=terminal_status,
                error=str(exc),
            )
        await session.commit()
        return run.billing_status


async def settle_if_needed_for_delete(
    *,
    run_id: str,
    status: str,
    billing_status: str,
) -> str:
    """Settle a deletable run. Running + unsettled is left untouched so mid-flight usage is not clipped."""
    if not is_unsettled_billing(billing_status):
        return billing_status
    if status in {"running", "paused"}:
        return billing_status
    result = await settle_run_billing(run_id=run_id, terminal_status=status)
    return result or billing_status


async def list_unsettled_terminal_runs(*, limit: int) -> list[tuple[str, str]]:
    session_factory = get_session_factory()
    async with session_factory() as session:
        rows = (
            await session.execute(
                select(Run.run_id, Run.status)
                .where(
                    Run.status.in_(TERMINAL_RUN_STATUSES),
                    Run.billing_status.in_(UNSETTLED_BILLING_STATUSES),
                )
                .order_by(Run.finished_at.asc().nulls_last(), Run.started_at.asc())
                .limit(max(1, int(limit)))
            )
        ).all()
    return [(str(run_id), str(status)) for run_id, status in rows]


async def sweep_unsettled_runs(*, limit: int | None = None) -> int:
    batch_limit = settings.BILLING_SETTLEMENT_BATCH_LIMIT if limit is None else limit
    pending = await list_unsettled_terminal_runs(limit=batch_limit)
    attempted = 0
    for run_id, status in pending:
        await settle_run_billing(run_id=run_id, terminal_status=status)
        attempted += 1
    if attempted:
        log.info("billing.settlement.sweep", count=attempted)
    return attempted


async def settle_run_ids(run_ids: Sequence[str], *, terminal_status: str) -> None:
    for run_id in run_ids:
        await settle_run_billing(run_id=run_id, terminal_status=terminal_status)


async def start_loop(interval_seconds: int | None = None) -> None:
    interval = (
        settings.BILLING_SETTLEMENT_INTERVAL_SECONDS
        if interval_seconds is None
        else interval_seconds
    )
    log.info("billing.settlement.loop.start", interval_seconds=interval)
    while True:
        try:
            await sweep_unsettled_runs()
        except Exception as exc:
            log.warning("billing.settlement.loop.error", error=str(exc))
        await asyncio.sleep(max(1, int(interval)))
