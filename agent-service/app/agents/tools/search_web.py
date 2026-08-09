from __future__ import annotations

import asyncio
from functools import lru_cache

from tavily import TavilyClient
from tavily.errors import (
    BadRequestError as TavilyBadRequestError,
    ForbiddenError as TavilyForbiddenError,
    InvalidAPIKeyError as TavilyInvalidAPIKeyError,
    MissingAPIKeyError as TavilyMissingAPIKeyError,
    TimeoutError as TavilyTimeoutError,
    UsageLimitExceededError as TavilyUsageLimitExceededError,
)

from core.config import settings
from service.collector.base import BaseChannel, CollectorObservation, ToolObservationResult
from service.collector.errors import ChannelError, FetchTimeout, RateLimited
from service.collector.rate_limiter import PerHostLimiter

from agents.tools.parse_page import infer_source_type, official_hosts_for_competitor

# Tavily SDK exceptions all subclass plain Exception with no common base. Pin
# them by name so unrelated bugs (e.g. AttributeError from a broken SDK upgrade)
# stay loud instead of getting wrapped as a generic ChannelError.
_TAVILY_ERRORS: tuple[type[Exception], ...] = (
    TavilyBadRequestError,
    TavilyForbiddenError,
    TavilyInvalidAPIKeyError,
    TavilyMissingAPIKeyError,
    TavilyTimeoutError,
    TavilyUsageLimitExceededError,
)

# Tavily's SDK wraps the underlying requests transport.  Transient TLS/socket
# failures (for example ``SSLEOFError`` from a reused connection) are not
# exposed as one of the SDK exception classes above, but they are safe to retry
# and should not fail an otherwise valid research run on the first attempt.
_TAVILY_TRANSIENT_MARKERS = (
    "ssl",
    "eof",
    "connection reset",
    "connection aborted",
    "connection refused",
    "max retries exceeded",
    "temporarily unavailable",
    "remote end closed",
)
_TAVILY_RETRY_ATTEMPTS = 3
_TAVILY_RETRY_BASE_SECONDS = 0.75


def _is_transient_tavily_error(exc: Exception) -> bool:
    message = str(exc).casefold()
    return any(marker in message for marker in _TAVILY_TRANSIENT_MARKERS)


def _is_tavily_quota_error(exc: Exception) -> bool:
    message = str(exc).casefold()
    return any(
        marker in message
        for marker in (
            "usage limit",
            "quota",
            "exceeds your plan",
            "upgrade your plan",
        )
    )


@lru_cache
def _get_tavily_rate_limiter() -> PerHostLimiter:
    return PerHostLimiter(qps=settings.COLLECTOR_PER_HOST_QPS)


async def _tavily_search(
    query: str,
    *,
    max_results: int,
    country: str | None = None,
) -> dict[str, object]:
    client = TavilyClient(api_key=settings.TAVILY_API_KEY)
    kwargs: dict[str, object] = {
        "query": query,
        "max_results": max_results,
        "search_depth": "advanced",
        "include_raw_content": False,
        "include_images": False,
    }
    if country is not None:
        kwargs["country"] = country

    for attempt in range(_TAVILY_RETRY_ATTEMPTS):
        try:
            try:
                return await asyncio.to_thread(client.search, **kwargs)
            except TypeError:
                # Older Tavily SDKs do not accept the optional country/depth
                # arguments.  Retain compatibility with those versions.
                return await asyncio.to_thread(
                    client.search, query=query, max_results=max_results
                )
        except TavilyTimeoutError as exc:
            raise FetchTimeout(f"tavily search timed out: {exc}") from exc
        except TavilyUsageLimitExceededError as exc:
            raise RateLimited(f"tavily usage limit exceeded: {exc}") from exc
        except TavilyForbiddenError as exc:
            if _is_tavily_quota_error(exc):
                raise RateLimited(f"tavily usage limit exceeded: {exc}") from exc
            raise ChannelError(f"tavily search failed ({type(exc).__name__}): {exc}") from exc
        except _TAVILY_ERRORS as exc:
            # The remaining tavily errors are all auth/parameter failures from
            # the provider — translate to ChannelError so callers can keep
            # treating search-channel boundary failures uniformly.
            raise ChannelError(f"tavily search failed ({type(exc).__name__}): {exc}") from exc
        except Exception as exc:
            if not _is_transient_tavily_error(exc) or attempt == _TAVILY_RETRY_ATTEMPTS - 1:
                raise ChannelError(
                    f"tavily search failed ({type(exc).__name__}): {exc}"
                ) from exc
            await asyncio.sleep(_TAVILY_RETRY_BASE_SECONDS * (attempt + 1))

    raise ChannelError("tavily search failed after retry budget was exhausted.")


class TavilySearchChannel(BaseChannel):
    name = "search_web"

    async def invoke(self, **kwargs: object) -> CollectorObservation:
        query = kwargs.get("query")
        max_results = kwargs.get("max_results", 5)
        if not isinstance(query, str) or not query.strip():
            raise ChannelError("search_web requires non-empty query.")
        if not isinstance(max_results, int):
            raise ChannelError("search_web max_results must be int.")
        if max_results <= 0 or max_results > 10:
            raise ChannelError("search_web max_results must be in range [1, 10].")
        if not settings.TAVILY_API_KEY:
            raise ChannelError("TAVILY_API_KEY is required for search_web channel.")
        competitor_id_raw = kwargs.get("competitor_id")
        competitor_id = (
            competitor_id_raw.strip()
            if isinstance(competitor_id_raw, str) and competitor_id_raw.strip()
            else None
        )
        official_hosts_raw = kwargs.get("official_hosts")
        if isinstance(official_hosts_raw, (list, tuple, set)):
            official_hosts = {
                item.strip()
                for item in official_hosts_raw
                if isinstance(item, str) and item.strip()
            }
        elif competitor_id is not None:
            official_hosts = official_hosts_for_competitor(competitor_id)
        else:
            official_hosts = set()
        country_raw = kwargs.get("country")
        country = country_raw.strip() if isinstance(country_raw, str) and country_raw.strip() else None

        await _get_tavily_rate_limiter().acquire(
            "api.tavily.com",
            timeout_seconds=float(settings.COLLECTOR_FETCH_TIMEOUT_S),
        )
        response = await _tavily_search(query, max_results=max_results, country=country)
        results_raw = response.get("results", [])
        results = results_raw if isinstance(results_raw, list) else []
        snippets: list = []
        for result in results:
            if not isinstance(result, dict):
                continue
            raw_text = result.get("content") or result.get("raw_content")
            source_url = result.get("url")
            source_title = result.get("title")
            if not isinstance(raw_text, str) or not raw_text.strip():
                continue
            normalized_url = source_url if isinstance(source_url, str) else None
            normalized_title = source_title if isinstance(source_title, str) else "tavily_result"
            source_type = infer_source_type(
                source_url=normalized_url,
                official_hosts=official_hosts or None,
            )
            snippets.append(
                self._build_snippet(
                    raw_text=raw_text,
                    source_type=source_type,
                    source_url=normalized_url,
                    source_title=normalized_title,
                    metadata={
                        "source": "tavily_search",
                        "query": query,
                        "country": country,
                        "competitor_id": competitor_id,
                    },
                )
            )
        if not snippets:
            raise ChannelError("search_web returned no usable snippets.")

        return CollectorObservation(
            channel=self.name,
            args={
                "query": query,
                "max_results": max_results,
                "country": country,
            },
            result=ToolObservationResult(
                snippets=snippets,
                metadata={
                    "query": query,
                    "result_count": len(snippets),
                    "provider": "tavily",
                    "country": country,
                    "competitor_id": competitor_id,
                },
            ),
        )
