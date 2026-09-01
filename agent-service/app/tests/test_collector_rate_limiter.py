from __future__ import annotations

import asyncio
import warnings

from service.collector.rate_limiter import PerHostLimiter


def test_per_host_limiter_recreates_bucket_for_new_event_loop() -> None:
    limiter = PerHostLimiter(qps=100)

    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        asyncio.run(limiter.acquire("example.com"))
        asyncio.run(limiter.acquire("example.com"))

    assert not any("re-used across loops" in str(item.message) for item in caught)
