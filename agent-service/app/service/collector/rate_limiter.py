from __future__ import annotations

import asyncio

from aiolimiter import AsyncLimiter

from service.collector.errors import RateLimiterTimeout


class PerHostLimiter:
    def __init__(self, *, qps: int) -> None:
        if qps <= 0:
            raise ValueError("PerHostLimiter qps must be positive.")
        self._qps = qps
        self._limiters: dict[str, AsyncLimiter] = {}
        self._lock = asyncio.Lock()

    async def _get_or_create(self, host: str) -> AsyncLimiter:
        async with self._lock:
            limiter = self._limiters.get(host)
            if limiter is None:
                limiter = AsyncLimiter(self._qps, 1)
                self._limiters[host] = limiter
            return limiter

    async def acquire(self, host: str, *, timeout_seconds: float | None = None) -> None:
        if not host:
            raise ValueError("PerHostLimiter.acquire requires non-empty host.")
        limiter = await self._get_or_create(host)
        if timeout_seconds is None:
            await limiter.acquire()
            return
        try:
            await asyncio.wait_for(limiter.acquire(), timeout=timeout_seconds)
        except TimeoutError as exc:
            raise RateLimiterTimeout(f"rate limiter timeout on host={host}") from exc
