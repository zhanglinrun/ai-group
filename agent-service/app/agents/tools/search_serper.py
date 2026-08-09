from __future__ import annotations

from functools import lru_cache

import httpx

from agents.tools.parse_page import infer_source_type, official_hosts_for_competitor
from core.config import settings
from service.collector.base import BaseChannel, CollectorObservation, ToolObservationResult
from service.collector.errors import ChannelError, FetchTimeout, RateLimited
from service.collector.rate_limiter import PerHostLimiter

_SERPER_SEARCH_URL = "https://google.serper.dev/search"
_SERPER_HOST = "google.serper.dev"

# Tavily-style localization names (what the router carries) -> Serper `gl` ISO-3166 country
# codes. English/global stays unset so Serper uses its global default.
_SERPER_GL_BY_COUNTRY: dict[str, str] = {
    "china": "cn",
    "japan": "jp",
    "south korea": "kr",
    "germany": "de",
    "france": "fr",
    "spain": "es",
}
# ISO 639-1 carrier language -> Serper `hl` UI language.
_SERPER_HL_BY_LANGUAGE: dict[str, str] = {
    "zh": "zh-cn",
    "en": "en",
    "ja": "ja",
    "ko": "ko",
    "de": "de",
    "fr": "fr",
    "es": "es",
}


@lru_cache
def _get_serper_rate_limiter() -> PerHostLimiter:
    return PerHostLimiter(qps=settings.COLLECTOR_PER_HOST_QPS)


def _serper_gl(country: str | None) -> str | None:
    if not isinstance(country, str) or not country.strip():
        return None
    key = country.strip().casefold()
    if key in _SERPER_GL_BY_COUNTRY:
        return _SERPER_GL_BY_COUNTRY[key]
    # Already an ISO-3166 alpha-2 code (e.g. an explicit override) — pass through.
    return key if len(key) == 2 and key.isalpha() else None


def _serper_hl(language: str | None) -> str | None:
    if not isinstance(language, str) or not language.strip():
        return None
    return _SERPER_HL_BY_LANGUAGE.get(language.strip().casefold())


def _is_serper_quota_message(message: str) -> bool:
    lowered = message.casefold()
    return any(
        marker in lowered
        for marker in ("not enough credits", "quota", "usage limit", "credit")
    )


def _classify_serper_status(status_code: int, message: str) -> ChannelError:
    if status_code == 429 or _is_serper_quota_message(message):
        return RateLimited(f"serper search rate limited or quota exhausted: {message}")
    if status_code in {401, 403}:
        return ChannelError(f"serper search authentication failed: {message}")
    return ChannelError(f"serper search failed with status {status_code}: {message}")


class SerperSearchChannel(BaseChannel):
    name = "serper_search"

    async def invoke(self, **kwargs: object) -> CollectorObservation:
        query = kwargs.get("query")
        max_results = kwargs.get("max_results", 5)
        if not isinstance(query, str) or not query.strip():
            raise ChannelError("serper_search requires non-empty query.")
        if not isinstance(max_results, int):
            raise ChannelError("serper_search max_results must be int.")
        if max_results <= 0 or max_results > 10:
            raise ChannelError("serper_search max_results must be in range [1, 10].")
        if not settings.SERPER_API_KEY:
            raise ChannelError("SERPER_API_KEY is required for serper_search channel.")
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
        language_raw = kwargs.get("language")
        gl = _serper_gl(country_raw if isinstance(country_raw, str) else None)
        hl = _serper_hl(language_raw if isinstance(language_raw, str) else None)

        await _get_serper_rate_limiter().acquire(
            _SERPER_HOST,
            timeout_seconds=float(settings.COLLECTOR_FETCH_TIMEOUT_S),
        )
        body: dict[str, object] = {"q": query, "num": max_results}
        if gl is not None:
            body["gl"] = gl
        if hl is not None:
            body["hl"] = hl
        timeout = httpx.Timeout(float(settings.COLLECTOR_FETCH_TIMEOUT_S))
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.post(
                    _SERPER_SEARCH_URL,
                    headers={
                        "X-API-KEY": settings.SERPER_API_KEY,
                        "Content-Type": "application/json",
                    },
                    json=body,
                )
            response.raise_for_status()
            payload = response.json()
        except httpx.TimeoutException as exc:
            raise FetchTimeout(f"serper search timed out: {exc}") from exc
        except httpx.HTTPStatusError as exc:
            message = str(exc.response.text or exc)
            raise _classify_serper_status(exc.response.status_code, message) from exc
        except httpx.RequestError as exc:
            raise ChannelError(f"serper search request failed: {exc}") from exc
        except ValueError as exc:
            raise ChannelError(f"serper search returned invalid JSON: {exc}") from exc

        if not isinstance(payload, dict):
            raise ChannelError("serper search returned non-object payload.")
        organic_raw = payload.get("organic")
        organic = organic_raw if isinstance(organic_raw, list) else []
        snippets: list = []
        for item in organic:
            if not isinstance(item, dict):
                continue
            raw_text = item.get("snippet")
            source_url_raw = item.get("link")
            title_raw = item.get("title")
            if not isinstance(raw_text, str) or not raw_text.strip():
                continue
            source_url = source_url_raw if isinstance(source_url_raw, str) else None
            source_title = title_raw if isinstance(title_raw, str) else "serper_result"
            snippets.append(
                self._build_snippet(
                    raw_text=raw_text,
                    source_type=infer_source_type(
                        source_url=source_url,
                        official_hosts=official_hosts or None,
                    ),
                    source_url=source_url,
                    source_title=source_title,
                    metadata={
                        "source": "serper_search",
                        "query": query,
                        "competitor_id": competitor_id,
                        "date_published": item.get("date")
                        if isinstance(item.get("date"), str)
                        else None,
                    },
                )
            )
        if not snippets:
            raise ChannelError("serper_search returned no usable snippets.")

        return CollectorObservation(
            channel=self.name,
            args={
                "query": query,
                "max_results": max_results,
                "gl": gl,
                "hl": hl,
            },
            result=ToolObservationResult(
                snippets=snippets,
                metadata={
                    "query": query,
                    "result_count": len(snippets),
                    "provider": "serper",
                    "gl": gl,
                    "hl": hl,
                    "competitor_id": competitor_id,
                },
            ),
        )
