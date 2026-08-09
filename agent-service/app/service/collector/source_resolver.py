from __future__ import annotations

import re
from dataclasses import dataclass
from functools import lru_cache
from urllib.parse import urljoin, urlsplit, urlunsplit

from core.config import settings
from core.defaults import (
    SOURCE_RESOLVER_MAX_CANDIDATE_URLS,
    SOURCE_RESOLVER_MAX_KEY_PAGES,
    SOURCE_RESOLVER_MAX_SITEMAP_URLS,
)
from schemas.contracts import validate_source_type
from service.collector.errors import ChannelError, RobotsBlocked
from service.collector.http_client import CollectorHTTPClient, get_collector_http_client
from service.collector.rate_limiter import PerHostLimiter
from service.collector.robots import RobotsGate

_SITEMAP_LOC_PATTERN = re.compile(r"<loc>\s*([^<\s]+)\s*</loc>", flags=re.IGNORECASE)
_HREF_PATTERN = re.compile(r'href=["\']([^"\']+)["\']', flags=re.IGNORECASE)
_TITLE_PATTERN = re.compile(r"<title[^>]*>(.*?)</title>", flags=re.IGNORECASE | re.DOTALL)
_HTML_TAG_PATTERN = re.compile(r"<[^>]+>")
_KEY_PAGE_BUCKETS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("pricing_page", ("/pricing", "/plan", "/plans", "/billing")),
    ("docs", ("/docs", "/doc", "/api", "/reference")),
    ("product_changelog", ("/changelog", "/releases", "/release-notes", "/updates", "/what-is-new", "/blog/release", "/blog/changelog")),
    ("official_site", ("/enterprise", "/business", "/security", "/compliance")),
)
_NON_OFFICIAL_HOST_HINTS: tuple[str, ...] = (
    "wikipedia.org",
    "reddit.com",
    "youtube.com",
    "medium.com",
    "g2.com",
    "capterra.com",
    "techcrunch.com",
)
_GENERIC_COMPETITOR_TOKENS: frozenset[str] = frozenset(
    {"ai", "app", "tool", "tools", "software", "the", "inc", "labs", "lab"}
)


@dataclass(frozen=True)
class SourcePage:
    url: str
    source_type: str
    signal: str


@dataclass(frozen=True)
class SourceResolutionResult:
    official_urls: list[str]
    official_hosts: list[str]
    key_pages: list[SourcePage]
    attempted_candidate_count: int
    validated_candidate_count: int


@lru_cache
def _resolver_per_host_limiter() -> PerHostLimiter:
    return PerHostLimiter(qps=settings.COLLECTOR_PER_HOST_QPS)


@lru_cache
def _resolver_robots_gate() -> RobotsGate:
    return RobotsGate(cache_ttl_seconds=settings.COLLECTOR_ROBOTS_CACHE_TTL_S)


def _normalize_url(url: str) -> str | None:
    cleaned = url.strip()
    if not cleaned:
        return None
    parsed = urlsplit(cleaned)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        return None
    path = parsed.path or "/"
    return urlunsplit((parsed.scheme.lower(), parsed.netloc.lower(), path, "", ""))


def _root_url(url: str) -> str:
    parsed = urlsplit(url)
    return urlunsplit((parsed.scheme, parsed.netloc, "/", "", ""))


def _host(url: str) -> str:
    return urlsplit(url).netloc.lower().removeprefix("www.")


def _ordered_unique(values: list[str]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        ordered.append(value)
    return ordered


def _competitor_tokens(*, competitor_name: str, competitor_id: str) -> set[str]:
    raw_tokens = set(re.findall(r"[a-z0-9\u4e00-\u9fff]+", f"{competitor_name} {competitor_id}".casefold()))
    return {
        token
        for token in raw_tokens
        if token not in _GENERIC_COMPETITOR_TOKENS and (len(token) >= 2 or not token.isascii())
    }


def _extract_title(html: str | None) -> str | None:
    if html is None:
        return None
    match = _TITLE_PATTERN.search(html)
    if match is None:
        return None
    title = " ".join(_HTML_TAG_PATTERN.sub(" ", match.group(1)).split())
    return title or None


def _text_preview(html: str | None) -> str:
    if html is None:
        return ""
    text = " ".join(_HTML_TAG_PATTERN.sub(" ", html).split())
    return text[:1200]


def _looks_like_official_host(host: str) -> bool:
    return not any(host == blocked or host.endswith(f".{blocked}") for blocked in _NON_OFFICIAL_HOST_HINTS)


def _title_or_body_mentions_competitor(
    *,
    tokens: set[str],
    title: str | None,
    body_preview: str,
) -> bool:
    combined = f"{title or ''} {body_preview}".casefold()
    return any(token in combined for token in tokens)


def _source_type_for_url(url: str) -> str:
    path = urlsplit(url).path.lower()
    for source_type, keywords in _KEY_PAGE_BUCKETS:
        if any(keyword in path for keyword in keywords):
            return validate_source_type(source_type)
    return "official_site"


def _score_key_page(url: str) -> int:
    path = urlsplit(url).path.lower()
    if any(keyword in path for keyword in ("/pricing", "/plan", "/plans", "/billing")):
        return 40
    if any(keyword in path for keyword in ("/docs", "/doc", "/api", "/reference")):
        return 30
    if any(keyword in path for keyword in ("/changelog", "/releases", "/release-notes", "/updates", "/blog/release", "/blog/changelog")):
        return 25
    if any(keyword in path for keyword in ("/enterprise", "/business", "/security", "/compliance")):
        return 20
    return 5


async def _fetch_text_with_budget(
    *,
    url: str,
    http_client: CollectorHTTPClient,
) -> str | None:
    parsed = urlsplit(url)
    host = parsed.netloc.lower()
    if not host:
        return None
    try:
        await _resolver_per_host_limiter().acquire(
            host,
            timeout_seconds=float(settings.COLLECTOR_FETCH_TIMEOUT_S),
        )
        await _resolver_robots_gate().ensure_allowed(
            target_url=url,
            user_agent=settings.COLLECTOR_USER_AGENT,
            client=http_client.client,
        )
        response = await http_client.fetch_text(url)
    except (ChannelError, RobotsBlocked, ValueError):
        return None
    return response.text


def _extract_sitemap_urls(sitemap_text: str) -> list[str]:
    urls: list[str] = []
    for match in _SITEMAP_LOC_PATTERN.findall(sitemap_text):
        normalized = _normalize_url(match)
        if normalized is not None:
            urls.append(normalized)
        if len(urls) >= SOURCE_RESOLVER_MAX_SITEMAP_URLS:
            break
    return _ordered_unique(urls)


def _extract_home_links(*, html: str, base_url: str) -> list[str]:
    links: list[str] = []
    for raw_href in _HREF_PATTERN.findall(html):
        resolved = _normalize_url(urljoin(base_url, raw_href))
        if resolved is None:
            continue
        links.append(resolved)
    return _ordered_unique(links)


async def _enumerate_key_pages(
    *,
    root_url: str,
    http_client: CollectorHTTPClient,
    key_page_budget: int,
) -> list[SourcePage]:
    host = _host(root_url)
    candidates: list[str] = []

    sitemap_url = urljoin(root_url, "sitemap.xml")
    sitemap_text = await _fetch_text_with_budget(url=sitemap_url, http_client=http_client)
    if sitemap_text is not None:
        candidates.extend(_extract_sitemap_urls(sitemap_text))

    home_text = await _fetch_text_with_budget(url=root_url, http_client=http_client)
    if home_text is not None:
        candidates.extend(_extract_home_links(html=home_text, base_url=root_url))

    same_host_candidates = [
        candidate
        for candidate in _ordered_unique(candidates)
        if _host(candidate) == host
    ]
    filtered = [candidate for candidate in same_host_candidates if _score_key_page(candidate) > 5]
    ranked = sorted(filtered, key=lambda item: (-_score_key_page(item), len(item)))

    pages: list[SourcePage] = []
    for url in ranked[:key_page_budget]:
        pages.append(
            SourcePage(
                url=url,
                source_type=_source_type_for_url(url),
                signal="sitemap_or_nav",
            )
        )
    return pages


async def resolve_official_sources(
    *,
    competitor_id: str,
    competitor_name: str,
    candidate_urls: list[str],
    candidate_url_budget: int = SOURCE_RESOLVER_MAX_CANDIDATE_URLS,
    key_page_budget: int = SOURCE_RESOLVER_MAX_KEY_PAGES,
    http_client: CollectorHTTPClient | None = None,
) -> SourceResolutionResult:
    normalized_candidates = _ordered_unique(
        [
            normalized
            for normalized in (_normalize_url(item) for item in candidate_urls)
            if normalized is not None
        ]
    )[:candidate_url_budget]
    if not normalized_candidates:
        return SourceResolutionResult(
            official_urls=[],
            official_hosts=[],
            key_pages=[],
            attempted_candidate_count=0,
            validated_candidate_count=0,
        )

    collector_http_client = http_client or get_collector_http_client()
    tokens = _competitor_tokens(competitor_name=competitor_name, competitor_id=competitor_id)

    validated_roots: list[str] = []
    for candidate_url in normalized_candidates:
        candidate_host = _host(candidate_url)
        if not _looks_like_official_host(candidate_host):
            continue
        page_text = await _fetch_text_with_budget(url=candidate_url, http_client=collector_http_client)
        if page_text is None:
            page_text = await _fetch_text_with_budget(
                url=_root_url(candidate_url),
                http_client=collector_http_client,
            )
        if page_text is None:
            continue
        if not _title_or_body_mentions_competitor(
            tokens=tokens,
            title=_extract_title(page_text),
            body_preview=_text_preview(page_text),
        ):
            continue
        validated_roots.append(_root_url(candidate_url))

    validated_roots = _ordered_unique(validated_roots)
    if not validated_roots:
        return SourceResolutionResult(
            official_urls=[],
            official_hosts=[],
            key_pages=[],
            attempted_candidate_count=len(normalized_candidates),
            validated_candidate_count=0,
        )

    key_pages: list[SourcePage] = []
    for root_url in validated_roots:
        key_pages.extend(
            await _enumerate_key_pages(
                root_url=root_url,
                http_client=collector_http_client,
                key_page_budget=key_page_budget,
            )
        )

    all_pages = [
        SourcePage(url=root_url, source_type="official_site", signal="validated_root")
        for root_url in validated_roots
    ]
    all_pages.extend(key_pages)

    deduped_pages: list[SourcePage] = []
    seen_urls: set[str] = set()
    for page in sorted(all_pages, key=lambda item: (-_score_key_page(item.url), len(item.url))):
        if page.url in seen_urls:
            continue
        seen_urls.add(page.url)
        deduped_pages.append(page)
        if len(deduped_pages) >= key_page_budget:
            break

    return SourceResolutionResult(
        official_urls=[item.url for item in deduped_pages],
        official_hosts=_ordered_unique([_host(item) for item in validated_roots]),
        key_pages=deduped_pages,
        attempted_candidate_count=len(normalized_candidates),
        validated_candidate_count=len(validated_roots),
    )
