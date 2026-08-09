from __future__ import annotations

import pytest

from service.llm.rate_limiter import AsyncTokenBucket, estimate_tokens


@pytest.mark.asyncio
async def test_token_bucket_sleeps_until_budget_refills() -> None:
    now = 0.0
    sleep_calls: list[float] = []

    def fake_monotonic() -> float:
        return now

    async def fake_sleep(seconds: float) -> None:
        nonlocal now
        sleep_calls.append(seconds)
        now += seconds

    bucket = AsyncTokenBucket(
        tpm_budget=60,
        monotonic_func=fake_monotonic,
        sleep_func=fake_sleep,
    )

    await bucket.acquire(60)
    await bucket.acquire(30)

    assert sleep_calls == [30.0]


@pytest.mark.asyncio
async def test_token_bucket_zero_budget_is_disabled() -> None:
    async def fake_sleep(seconds: float) -> None:
        raise AssertionError(f"unexpected sleep {seconds}")

    bucket = AsyncTokenBucket(tpm_budget=0, sleep_func=fake_sleep)

    await bucket.acquire(10_000)


def test_estimate_tokens_is_conservative_character_based() -> None:
    assert estimate_tokens(system_prompt="abc", user_prompt="def") == 1026
