from __future__ import annotations

import asyncio
from dataclasses import dataclass
from functools import lru_cache

import httpx

from core.config import settings
from service.collector.errors import ChannelError, FetchTimeout


@dataclass(slots=True)
class FetchResponse:
    url: str
    status_code: int
    text: str
    content_type: str | None


class CollectorHTTPClient:
    def __init__(
        self,
        *,
        user_agent: str,
        timeout_seconds: int,
    ) -> None:
        if timeout_seconds <= 0:
            raise ValueError("CollectorHTTPClient timeout_seconds must be positive.")
        self._user_agent = user_agent
        self._timeout_seconds = timeout_seconds
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(timeout_seconds),
            follow_redirects=True,
            headers={"User-Agent": user_agent},
        )

    @property
    def client(self) -> httpx.AsyncClient:
        return self._client

    async def fetch_text(self, url: str, *, retries: int = 1) -> FetchResponse:
        if not url:
            raise ValueError("CollectorHTTPClient.fetch_text requires non-empty url.")
        attempts = retries + 1
        last_error: Exception | None = None
        for attempt in range(attempts):
            try:
                response = await self._client.get(url)
            except httpx.TimeoutException as exc:
                last_error = exc
                if attempt < attempts - 1:
                    await asyncio.sleep(0.25 * (2**attempt))
                    continue
                raise FetchTimeout(f"fetch timeout url={url}") from exc
            except httpx.HTTPError as exc:
                last_error = exc
                if attempt < attempts - 1:
                    await asyncio.sleep(0.25 * (2**attempt))
                    continue
                break

            if response.status_code >= 500 and attempt < attempts - 1:
                await asyncio.sleep(0.25 * (2**attempt))
                continue
            if response.status_code >= 400:
                raise ChannelError(f"fetch failed status={response.status_code} url={url}")
            return FetchResponse(
                url=str(response.url),
                status_code=response.status_code,
                text=response.text,
                content_type=response.headers.get("content-type"),
            )

        raise ChannelError(f"fetch failed url={url} error={type(last_error).__name__ if last_error else 'unknown'}")


@lru_cache
def get_collector_http_client() -> CollectorHTTPClient:
    return CollectorHTTPClient(
        user_agent=settings.COLLECTOR_USER_AGENT,
        timeout_seconds=settings.COLLECTOR_FETCH_TIMEOUT_S,
    )
