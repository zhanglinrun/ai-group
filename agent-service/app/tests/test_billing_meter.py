from __future__ import annotations

import pytest

from service.billing import Debit, QuotaExhaustedError
from service.billing_meter import (
    LLMCallHold,
    acquire_llm_call_hold,
    estimate_call_hold_micro_points,
    settle_llm_call_hold,
)


def test_call_hold_is_capped() -> None:
    hold = estimate_call_hold_micro_points(prompt_tokens=200_000, output_tokens=20_000)
    capped = estimate_call_hold_micro_points(prompt_tokens=24_000, output_tokens=8_192)
    assert hold == capped
    assert hold > 0


@pytest.mark.asyncio
async def test_acquire_raises_when_member_quota_exhausted(monkeypatch: pytest.MonkeyPatch) -> None:
    async def _owner(_run_id: str) -> int:
        return 42

    async def _available(*, user_id: int) -> int:
        assert user_id == 42
        return 0

    monkeypatch.setattr("service.billing_meter.current_run_id", lambda: "run_1")
    monkeypatch.setattr("service.billing_meter._load_run_owner", _owner)
    monkeypatch.setattr("service.billing_meter.quota_client.available_micro_points", _available)
    with pytest.raises(QuotaExhaustedError):
        await acquire_llm_call_hold(estimated_micro_points=100)


@pytest.mark.asyncio
async def test_acquire_is_unmetered_without_run_context() -> None:
    hold = await acquire_llm_call_hold(estimated_micro_points=100)
    assert hold.metered is False
    assert hold.reservation is None
    assert hold.request_id is None


@pytest.mark.asyncio
async def test_acquire_gates_on_available_then_persists(monkeypatch: pytest.MonkeyPatch) -> None:
    async def _owner(_run_id: str) -> int:
        return 42

    async def _available(*, user_id: int) -> int:
        assert user_id == 42
        return 10_000

    async def _persist(**kwargs: object) -> str:
        assert kwargs["request_id"].startswith("agent:run_1:call:")
        return "attempt_1"

    monkeypatch.setattr("service.billing_meter.current_run_id", lambda: "run_1")
    monkeypatch.setattr("service.billing_meter._load_run_owner", _owner)
    monkeypatch.setattr("service.billing_meter.quota_client.available_micro_points", _available)
    monkeypatch.setattr("service.billing_meter._persist_open_attempt", _persist)
    hold = await acquire_llm_call_hold(estimated_micro_points=100)
    assert hold.metered is True
    assert hold.attempt_id == "attempt_1"
    assert hold.request_id is not None


@pytest.mark.asyncio
async def test_settle_missing_usage_stays_pending(monkeypatch: pytest.MonkeyPatch) -> None:
    updates: list[str] = []

    async def _update(*, attempt_id: str, status: str, **_kwargs: object) -> None:
        assert attempt_id == "attempt_1"
        updates.append(status)

    async def _debit(**_kwargs: object) -> Debit:
        raise AssertionError("missing usage must not debit")

    monkeypatch.setattr("service.billing_meter._update_attempt", _update)
    monkeypatch.setattr("service.billing_meter.quota_client.debit", _debit)
    hold = LLMCallHold(
        metered=True,
        run_id="run_1",
        estimated_micro_points=100,
        request_id="agent:run_1:call:abc",
        owner_user_id=42,
        attempt_id="attempt_1",
    )
    await settle_llm_call_hold(hold, usage_known=False, provider_called=True)
    assert updates == ["PENDING_RECONCILIATION"]


@pytest.mark.asyncio
async def test_settle_debits_actual_tokens(monkeypatch: pytest.MonkeyPatch) -> None:
    updates: list[tuple[str, int | None]] = []
    consumed: list[int] = []

    async def _update(*, attempt_id: str, status: str, actual_micro_points: int | None = None, **_kwargs: object) -> None:
        del attempt_id
        updates.append((status, actual_micro_points))

    async def _add(*, run_id: str, actual_micro_points: int) -> None:
        del run_id
        consumed.append(actual_micro_points)

    async def _debit(**kwargs: object) -> Debit:
        assert kwargs["amount_micro_points"] == 35
        assert kwargs["request_id"] == "agent:run_1:call:abc"
        return Debit("debit_1", 35, "agent:run_1:call:abc", 42)

    monkeypatch.setattr("service.billing_meter._update_attempt", _update)
    monkeypatch.setattr("service.billing_meter._add_consumed_micro_points", _add)
    monkeypatch.setattr("service.billing_meter.quota_client.debit", _debit)
    hold = LLMCallHold(
        metered=True,
        run_id="run_1",
        estimated_micro_points=100,
        request_id="agent:run_1:call:abc",
        owner_user_id=42,
        attempt_id="attempt_1",
    )
    await settle_llm_call_hold(hold, actual_micro_points=35, usage_known=True, provider_called=True)
    assert consumed == [35]
    assert updates[-1][0] == "DEBITED"


@pytest.mark.asyncio
async def test_settle_skips_when_provider_not_called(monkeypatch: pytest.MonkeyPatch) -> None:
    updates: list[str] = []

    async def _update(*, attempt_id: str, status: str, **_kwargs: object) -> None:
        del attempt_id
        updates.append(status)

    async def _debit(**_kwargs: object) -> Debit:
        raise AssertionError("uncalled provider must not debit")

    monkeypatch.setattr("service.billing_meter._update_attempt", _update)
    monkeypatch.setattr("service.billing_meter.quota_client.debit", _debit)
    hold = LLMCallHold(
        metered=True,
        run_id="run_1",
        estimated_micro_points=100,
        request_id="agent:run_1:call:abc",
        owner_user_id=42,
        attempt_id="attempt_1",
    )
    await settle_llm_call_hold(hold, provider_called=False, usage_known=False)
    assert updates == ["SKIPPED"]
