from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from urllib.parse import urlsplit
from urllib.robotparser import RobotFileParser

import httpx

from service.collector.errors import RobotsBlocked


@dataclass(slots=True)
class RobotsDecision:
    allowed: bool
    reason: str
    robots_url: str


@dataclass(slots=True)
class _RobotsCacheEntry:
    parser: RobotFileParser | None
    expires_at: datetime
    robots_url: str
    reason: str


class RobotsGate:
    def __init__(self, *, cache_ttl_seconds: int) -> None:
        if cache_ttl_seconds <= 0:
            raise ValueError("RobotsGate cache_ttl_seconds must be positive.")
        self._ttl_seconds = cache_ttl_seconds
        self._cache: dict[str, _RobotsCacheEntry] = {}
        self._lock = asyncio.Lock()

    async def evaluate(
        self,
        *,
        target_url: str,
        user_agent: str,
        client: httpx.AsyncClient,
    ) -> RobotsDecision:
        parsed = urlsplit(target_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            return RobotsDecision(
                allowed=False,
                reason="invalid_target_url",
                robots_url="",
            )

        cache_key = f"{parsed.scheme}://{parsed.netloc}"
        entry = await self._get_or_refresh(
            cache_key=cache_key,
            client=client,
            user_agent=user_agent,
        )
        if entry.parser is None:
            # fail-open when robots cannot be fetched; avoid false hard blocks.
            return RobotsDecision(allowed=True, reason=entry.reason, robots_url=entry.robots_url)
        allowed = entry.parser.can_fetch(user_agent, target_url)
        return RobotsDecision(
            allowed=allowed,
            reason="allowed" if allowed else "blocked_by_robots",
            robots_url=entry.robots_url,
        )

    async def ensure_allowed(
        self,
        *,
        target_url: str,
        user_agent: str,
        client: httpx.AsyncClient,
    ) -> None:
        decision = await self.evaluate(target_url=target_url, user_agent=user_agent, client=client)
        if not decision.allowed:
            raise RobotsBlocked(f"robots denied target={target_url} reason={decision.reason}")

    async def _get_or_refresh(
        self,
        *,
        cache_key: str,
        client: httpx.AsyncClient,
        user_agent: str,
    ) -> _RobotsCacheEntry:
        now = datetime.now(timezone.utc)
        async with self._lock:
            cached = self._cache.get(cache_key)
            if cached is not None and cached.expires_at > now:
                return cached

        parsed = urlsplit(cache_key)
        robots_url = f"{parsed.scheme}://{parsed.netloc}/robots.txt"
        entry = await self._fetch_robots(
            robots_url=robots_url,
            client=client,
            user_agent=user_agent,
        )
        async with self._lock:
            self._cache[cache_key] = entry
        return entry

    async def _fetch_robots(
        self,
        *,
        robots_url: str,
        client: httpx.AsyncClient,
        user_agent: str,
    ) -> _RobotsCacheEntry:
        now = datetime.now(timezone.utc)
        expires_at = now + timedelta(seconds=self._ttl_seconds)
        try:
            response = await client.get(
                robots_url,
                headers={"User-Agent": user_agent},
            )
        except httpx.HTTPError:
            return _RobotsCacheEntry(
                parser=None,
                expires_at=expires_at,
                robots_url=robots_url,
                reason="robots_unavailable",
            )

        if response.status_code == 404:
            return _RobotsCacheEntry(
                parser=None,
                expires_at=expires_at,
                robots_url=robots_url,
                reason="robots_missing",
            )
        if response.status_code >= 400:
            return _RobotsCacheEntry(
                parser=None,
                expires_at=expires_at,
                robots_url=robots_url,
                reason=f"robots_http_{response.status_code}",
            )

        parser = RobotFileParser()
        parser.set_url(robots_url)
        parser.parse(response.text.splitlines())
        return _RobotsCacheEntry(
            parser=parser,
            expires_at=expires_at,
            robots_url=robots_url,
            reason="robots_loaded",
        )
