from __future__ import annotations

import pytest

from service.billing import QuotaExhaustedError, Reservation
from service.billing_meter import acquire_llm_call_hold, estimate_call_hold_micro_points


def test_call_hold_is_capped() -> None:
    hold = estimate_call_hold_micro_points(prompt_tokens=200_000, output_tokens=20_000)
    capped = estimate_call_hold_micro_points(prompt_tokens=24_000, output_tokens=8_192)
    assert hold == capped
    assert hold > 0


@pytest.mark.asyncio
async def test_acquire_raises_when_member_quota_exhausted(monkeypatch: pytest.MonkeyPatch) -> None:
    async def _owner(_run_id: str) -> int:
        return 42

    async def _reserve(**_kwargs: object) -> Reservation:
        raise QuotaExhaustedError("积分不足")

    monkeypatch.setattr("service.billing_meter.current_run_id", lambda: "run_1")
    monkeypatch.setattr("service.billing_meter._load_run_owner", _owner)
    monkeypatch.setattr("service.billing_meter.quota_client.reserve", _reserve)
    with pytest.raises(QuotaExhaustedError):
        await acquire_llm_call_hold(estimated_micro_points=100)


@pytest.mark.asyncio
async def test_acquire_is_unmetered_without_run_context() -> None:
    hold = await acquire_llm_call_hold(estimated_micro_points=100)
    assert hold.metered is False
    assert hold.reservation is None
