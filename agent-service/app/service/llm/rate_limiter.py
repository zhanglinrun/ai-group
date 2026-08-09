from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from math import ceil
from time import monotonic


def estimate_tokens(*, system_prompt: str, user_prompt: str) -> int:
    prompt_chars = len(system_prompt) + len(user_prompt)
    return max(1, ceil(prompt_chars / 3) + 1024)


class AsyncTokenBucket:
    def __init__(
        self,
        *,
        tpm_budget: int,
        monotonic_func: Callable[[], float] = monotonic,
        sleep_func: Callable[[float], Awaitable[object]] = asyncio.sleep,
    ) -> None:
        self._capacity = max(0, tpm_budget)
        self._tokens = float(self._capacity)
        self._last_refill_at = monotonic_func()
        self._monotonic = monotonic_func
        self._sleep = sleep_func
        self._lock = asyncio.Lock()

    async def acquire(self, tokens: int) -> None:
        if self._capacity <= 0 or tokens <= 0:
            return

        requested = min(float(tokens), float(self._capacity))
        refill_per_second = self._capacity / 60.0

        while True:
            async with self._lock:
                now = self._monotonic()
                elapsed = max(0.0, now - self._last_refill_at)
                self._tokens = min(
                    float(self._capacity),
                    self._tokens + elapsed * refill_per_second,
                )
                self._last_refill_at = now

                if self._tokens >= requested:
                    self._tokens -= requested
                    return

                missing = requested - self._tokens
                sleep_seconds = missing / refill_per_second

            await self._sleep(sleep_seconds)
