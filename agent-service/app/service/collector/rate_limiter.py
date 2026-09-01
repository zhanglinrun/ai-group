from __future__ import annotations

import asyncio

from aiolimiter import AsyncLimiter

from service.collector.errors import RateLimiterTimeout


class PerHostLimiter:
    def __init__(self, *, qps: int) -> None:
        if qps <= 0:
            raise ValueError("PerHostLimiter qps must be positive.")
        self._qps = qps
        self._limiters: dict[str, tuple[asyncio.AbstractEventLoop, AsyncLimiter]] = {}
        self._lock = asyncio.Lock()

    async def _get_or_create(self, host: str) -> AsyncLimiter:
        current_loop = asyncio.get_running_loop()
        async with self._lock:
            cached = self._limiters.get(host)
            if cached is None or cached[0] is not current_loop:
                limiter = AsyncLimiter(self._qps, 1)
                self._limiters[host] = (current_loop, limiter)
                return limiter
            return cached[1]

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
