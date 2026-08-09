from __future__ import annotations

import asyncio
from functools import lru_cache
from urllib.parse import urlsplit

import httpx
from tavily import TavilyClient
from tavily.errors import (
    BadRequestError as TavilyBadRequestError,
    ForbiddenError as TavilyForbiddenError,
    InvalidAPIKeyError as TavilyInvalidAPIKeyError,
    MissingAPIKeyError as TavilyMissingAPIKeyError,
    TimeoutError as TavilyTimeoutError,
    UsageLimitExceededError as TavilyUsageLimitExceededError,
)

from agents.tools.html_clean import extract_main_text, post_clean_text
from agents.tools.parse_page import infer_source_type, official_hosts_for_competitor
from core.config import settings
from service.collector.base import BaseChannel, CollectorObservation, ToolObservationResult
from service.collector.errors import ChannelError, FetchTimeout, RateLimited
from service.collector.http_client import get_collector_http_client
from service.collector.rate_limiter import PerHostLimiter
from service.collector.robots import RobotsGate
from service.collector.source_quality import MIN_EXTRACTED_TEXT_CHARS, is_low_semantic_text

_TAVILY_EXTRACT_ERRORS: tuple[type[Exception], ...] = (
    TavilyBadRequestError,
    TavilyForbiddenError,
    TavilyInvalidAPIKeyError,
    TavilyMissingAPIKeyError,
    TavilyTimeoutError,
    TavilyUsageLimitExceededError,
)

# Content types readability cannot parse; we skip the local path so the Tavily
# fallback (or a loud failure) handles them instead of producing garbled text.
_NON_HTML_CONTENT_MARKERS: tuple[str, ...] = (
    "application/pdf",
    "application/zip",
    "application/octet-stream",
    "image/",
    "video/",
    "audio/",
)


@lru_cache
def _get_per_host_limiter() -> PerHostLimiter:
    return PerHostLimiter(qps=settings.COLLECTOR_PER_HOST_QPS)


@lru_cache
def _get_robots_gate() -> RobotsGate:
    return RobotsGate(cache_ttl_seconds=settings.COLLECTOR_ROBOTS_CACHE_TTL_S)


async def _tavily_extract(*, url: str, query: str | None) -> dict[str, object]:
    client = TavilyClient(api_key=settings.TAVILY_API_KEY)
    kwargs: dict[str, object] = {
        "urls": [url],
        "extract_depth": "advanced",
        "format": "markdown",
        "include_images": False,
        "timeout": float(settings.COLLECTOR_FETCH_TIMEOUT_S),
    }
    if query:
        kwargs["query"] = query
        kwargs["chunks_per_source"] = 3
    try:
        return await asyncio.to_thread(client.extract, **kwargs)
    except TavilyTimeoutError as exc:
        raise FetchTimeout(f"tavily extract timed out: {exc}") from exc
    except TavilyUsageLimitExceededError as exc:
        raise RateLimited(f"tavily usage limit exceeded: {exc}") from exc
    except _TAVILY_EXTRACT_ERRORS as exc:
        raise ChannelError(f"tavily extract failed ({type(exc).__name__}): {exc}") from exc


def _extract_text_from_tavily_response(response: dict[str, object]) -> tuple[str, str | None]:
    results_raw = response.get("results")
    results = results_raw if isinstance(results_raw, list) else []
    if not results:
        failed_raw = response.get("failed_results")
        raise ChannelError(f"fetch_url extract returned no results; failed_results={failed_raw!r}")
    first = results[0]
    if not isinstance(first, dict):
        raise ChannelError("fetch_url extract returned malformed result.")
    raw_text = first.get("raw_content") or first.get("content")
    if not isinstance(raw_text, str) or not raw_text.strip():
        raise ChannelError("fetch_url extract returned empty content.")
    source_url_raw = first.get("url")
    source_url = source_url_raw if isinstance(source_url_raw, str) else None
    return raw_text.strip(), source_url


def _validate_extracted_text(text: str) -> None:
    compact = " ".join(text.split())
    low_semantic, reason = is_low_semantic_text(text)
    if not low_semantic:
        return
    if reason == "too_short":
        raise ChannelError(
            f"fetch_url extracted content too short: chars={len(compact)} "
            f"min_chars={MIN_EXTRACTED_TEXT_CHARS}"
        )
    if reason == "navigation_boilerplate":
        raise ChannelError("fetch_url extracted content looks like navigation/footer boilerplate.")
    raise ChannelError(f"fetch_url extracted content is low semantic quality: reason={reason}")


def _looks_like_html(content_type: str | None) -> bool:
    if content_type is None:
        return True
    lowered = content_type.lower()
    return not any(marker in lowered for marker in _NON_HTML_CONTENT_MARKERS)


def _can_use_tavily_fallback() -> bool:
    if not settings.COLLECTOR_FETCH_TAVILY_FALLBACK_ENABLED:
        return False
    return bool(settings.TAVILY_API_KEY)


# Jina Reader rate-limits anonymous traffic with 429 (rate) / 451 (abuse
# heuristics); both mean "back off", not a permanent failure, so we treat them as
# RateLimited and fall through to the next fallback instead of crashing the run.
_JINA_RATE_LIMIT_STATUS: frozenset[int] = frozenset({429, 451})


def _can_use_jina_fallback() -> bool:
    if not settings.COLLECTOR_FETCH_JINA_FALLBACK_ENABLED:
        return False
    return bool(settings.JINA_READER_BASE_URL.strip())


async def _fetch_via_jina_reader(*, url: str) -> tuple[str, str]:
    reader_url = f"{settings.JINA_READER_BASE_URL.rstrip('/')}/{url}"
    # Default markdown return is already nav/ad-stripped article text. Do NOT send
    # X-Token-Budget: it 409s on normal-length articles (10k-40k tokens); the
    # downstream extractor handles length, not this hop.
    headers = {"X-Return-Format": "markdown"}
    if settings.JINA_API_KEY:
        headers["Authorization"] = f"Bearer {settings.JINA_API_KEY}"
    timeout = httpx.Timeout(float(settings.COLLECTOR_FETCH_TIMEOUT_S))
    try:
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=True) as client:
            response = await client.get(reader_url, headers=headers)
    except httpx.TimeoutException as exc:
        raise FetchTimeout(f"jina reader timed out: {exc}") from exc
    except httpx.RequestError as exc:
        raise ChannelError(f"jina reader request failed: {exc}") from exc
    if response.status_code in _JINA_RATE_LIMIT_STATUS:
        raise RateLimited(f"jina reader rate limited: status={response.status_code}")
    if response.status_code != 200:
        raise ChannelError(f"jina reader failed with status {response.status_code}")
    extracted_text = post_clean_text(response.text)
    _validate_extracted_text(extracted_text)
    return extracted_text, url


async def _fetch_via_httpx(*, url: str) -> tuple[str, str]:
    http_client = get_collector_http_client()
    fetched = await http_client.fetch_text(url, retries=1)
    if not _looks_like_html(fetched.content_type):
        raise ChannelError(f"fetch_url httpx got non-HTML content_type={fetched.content_type}")
    try:
        extracted_text = extract_main_text(fetched.text)
    except (AttributeError, TypeError, ValueError) as exc:
        raise ChannelError(f"fetch_url local HTML extraction failed: {type(exc).__name__}") from exc
    _validate_extracted_text(extracted_text)
    return extracted_text, str(fetched.url)


async def _fetch_via_tavily(*, url: str, query: str | None) -> tuple[str, str]:
    if not settings.TAVILY_API_KEY:
        raise ChannelError("TAVILY_API_KEY is required for fetch_url tavily fallback.")
    response = await _tavily_extract(url=url, query=query)
    extracted_text, extracted_url = _extract_text_from_tavily_response(response)
    extracted_text = post_clean_text(extracted_text)
    _validate_extracted_text(extracted_text)
    return extracted_text, extracted_url or url


async def _fetch_via_search_snippet_fallback(
    *,
    host: str,
    query: str,
    competitor_id: str | None,
    dimension: str | None,
) -> tuple[str, str]:
    from service.collector.registry import get_channel_registry

    scoped_query = f"site:{host} {query}".strip()
    search_args: dict[str, object] = {
        "query": scoped_query,
        "query_variants": [query],
        "max_results": 3,
    }
    if competitor_id is not None:
        search_args["competitor_id"] = competitor_id
    if dimension is not None:
        search_args["dimension"] = dimension

    observation = await get_channel_registry().invoke("search_web", args=search_args)
    snippets = list(observation.result.snippets)
    if not snippets:
        raise ChannelError("fetch_url search snippet fallback returned no snippets.")
    first = snippets[0]
    source_url = first.source_url.strip() if isinstance(first.source_url, str) else ""
    if not source_url:
        raise ChannelError("fetch_url search snippet fallback returned snippet without source_url.")
    snippet_text = post_clean_text(first.sanitized_text)
    if not snippet_text:
        raise ChannelError("fetch_url search snippet fallback returned empty snippet text.")
    return snippet_text, source_url


class FetchUrlChannel(BaseChannel):
    name = "fetch_url"

    async def invoke(self, **kwargs: object) -> CollectorObservation:
        url = kwargs.get("url")
        competitor_id = kwargs.get("competitor_id")
        if not isinstance(url, str) or not url.strip():
            raise ChannelError("fetch_url requires non-empty url.")
        if competitor_id is not None and not isinstance(competitor_id, str):
            raise ChannelError("fetch_url competitor_id must be string when provided.")
        query_raw = kwargs.get("query")
        query = query_raw.strip() if isinstance(query_raw, str) and query_raw.strip() else None
        dimension_raw = kwargs.get("dimension")
        dimension = dimension_raw.strip() if isinstance(dimension_raw, str) and dimension_raw.strip() else None

        parsed = urlsplit(url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ChannelError(f"fetch_url invalid url={url}")
        host = parsed.netloc.lower()

        http_client = get_collector_http_client()
        await _get_per_host_limiter().acquire(host, timeout_seconds=float(settings.COLLECTOR_FETCH_TIMEOUT_S))
        await _get_robots_gate().ensure_allowed(
            target_url=url,
            user_agent=settings.COLLECTOR_USER_AGENT,
            client=http_client.client,
        )

        # Local httpx extraction is the primary path so collection no longer
        # depends on a paid extract quota; Tavily stays as an optional fallback
        # for pages the local path cannot parse (when a key is configured).
        fetch_source = "httpx_extract"
        extracted_text: str | None = None
        source_url: str | None = None
        httpx_error: ChannelError | FetchTimeout | None = None
        jina_error: ChannelError | FetchTimeout | RateLimited | None = None
        tavily_error: ChannelError | FetchTimeout | RateLimited | None = None
        search_fallback_error: ChannelError | FetchTimeout | RateLimited | None = None
        try:
            extracted_text, source_url = await _fetch_via_httpx(url=url)
        except (ChannelError, FetchTimeout) as exc:
            httpx_error = exc

        # Jina Reader is the free full-text fallback; it carries most of the load
        # now that Tavily Extract quota is exhausted, handling JS/anti-bot pages
        # local httpx cannot. Tavily stays after it only when a key is configured.
        if extracted_text is None or source_url is None:
            if _can_use_jina_fallback():
                try:
                    extracted_text, source_url = await _fetch_via_jina_reader(url=url)
                    fetch_source = "jina_reader"
                except (ChannelError, FetchTimeout, RateLimited) as exc:
                    jina_error = exc
            else:
                jina_error = ChannelError(
                    "fetch_url jina fallback skipped: disabled by config."
                )

        if extracted_text is None or source_url is None:
            if _can_use_tavily_fallback():
                try:
                    extracted_text, source_url = await _fetch_via_tavily(url=url, query=query)
                    fetch_source = "tavily_extract"
                except (ChannelError, FetchTimeout, RateLimited) as exc:
                    tavily_error = exc
            else:
                tavily_error = ChannelError(
                    "fetch_url tavily fallback skipped: disabled by config or missing TAVILY_API_KEY."
                )

        if extracted_text is None or source_url is None:
            if settings.COLLECTOR_FETCH_SEARCH_FALLBACK_ENABLED and query is not None:
                try:
                    extracted_text, source_url = await _fetch_via_search_snippet_fallback(
                        host=host,
                        query=query,
                        competitor_id=competitor_id if isinstance(competitor_id, str) else None,
                        dimension=dimension,
                    )
                    fetch_source = "search_snippet_fallback"
                except (ChannelError, FetchTimeout, RateLimited) as exc:
                    search_fallback_error = exc
            else:
                search_fallback_error = ChannelError(
                    "fetch_url search snippet fallback skipped: disabled by config or missing query."
                )

        if extracted_text is None or source_url is None:
            raise ChannelError(
                f"fetch_url failed for url={url}: httpx_error={httpx_error}; "
                f"jina_error={jina_error}; tavily_error={tavily_error}; "
                f"search_fallback_error={search_fallback_error}"
            )

        source_type = infer_source_type(
            source_url=source_url,
            official_hosts=official_hosts_for_competitor(
                competitor_id if isinstance(competitor_id, str) else None
            ),
        )
        snippet = self._build_snippet(
            raw_text=extracted_text,
            source_type=source_type,
            source_url=source_url,
            source_title=source_url,
            metadata={
                "source": fetch_source,
                "host": host,
                "query": query,
                "competitor_id": competitor_id if isinstance(competitor_id, str) else None,
            },
        )
        return CollectorObservation(
            channel=self.name,
            args={
                "url": url,
                "competitor_id": competitor_id,
                "query": query,
            },
            result=ToolObservationResult(
                snippets=[snippet],
                metadata={
                    "host": host,
                    "source": fetch_source,
                },
            ),
        )
