from __future__ import annotations

from functools import lru_cache
from urllib.parse import urljoin

import httpx

from agents.tools.parse_page import infer_source_type, official_hosts_for_competitor
from core.config import settings
from service.collector.base import BaseChannel, CollectorObservation, ToolObservationResult
from service.collector.errors import ChannelError, FetchTimeout, RateLimited
from service.collector.rate_limiter import PerHostLimiter


@lru_cache
def _get_bocha_rate_limiter() -> PerHostLimiter:
    return PerHostLimiter(qps=settings.COLLECTOR_PER_HOST_QPS)


def _bocha_url() -> str:
    return urljoin(settings.BOCHA_BASE_URL.rstrip("/") + "/", "web-search")


def _classify_bocha_status(status_code: int, message: str) -> ChannelError:
    if status_code in {403, 429}:
        return RateLimited(f"bocha search rate limited or quota exhausted: {message}")
    if status_code == 401:
        return ChannelError(f"bocha search authentication failed: {message}")
    return ChannelError(f"bocha search failed with status {status_code}: {message}")


def _coerce_bocha_code(value: object) -> int | None:
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip().isdigit():
        return int(value.strip())
    return None


class BochaSearchChannel(BaseChannel):
    name = "bocha_search"

    async def invoke(self, **kwargs: object) -> CollectorObservation:
        query = kwargs.get("query")
        max_results = kwargs.get("max_results", 5)
        if not isinstance(query, str) or not query.strip():
            raise ChannelError("bocha_search requires non-empty query.")
        if not isinstance(max_results, int):
            raise ChannelError("bocha_search max_results must be int.")
        if max_results <= 0 or max_results > 10:
            raise ChannelError("bocha_search max_results must be in range [1, 10].")
        if not settings.BOCHA_API_KEY:
            raise ChannelError("BOCHA_API_KEY is required for bocha_search channel.")
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

        await _get_bocha_rate_limiter().acquire(
            "api.bochaai.com",
            timeout_seconds=float(settings.COLLECTOR_FETCH_TIMEOUT_S),
        )
        timeout = httpx.Timeout(float(settings.COLLECTOR_FETCH_TIMEOUT_S))
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.post(
                    _bocha_url(),
                    headers={
                        "Authorization": f"Bearer {settings.BOCHA_API_KEY}",
                        "Content-Type": "application/json",
                    },
                    json={
                        "query": query,
                        "count": max_results,
                        "summary": True,
                        "freshness": "noLimit",
                    },
                )
            response.raise_for_status()
            payload = response.json()
        except httpx.TimeoutException as exc:
            raise FetchTimeout(f"bocha search timed out: {exc}") from exc
        except httpx.HTTPStatusError as exc:
            message = str(exc.response.text or exc)
            raise _classify_bocha_status(exc.response.status_code, message) from exc
        except httpx.RequestError as exc:
            raise ChannelError(f"bocha search request failed: {exc}") from exc
        except ValueError as exc:
            raise ChannelError(f"bocha search returned invalid JSON: {exc}") from exc

        if not isinstance(payload, dict):
            raise ChannelError("bocha search returned non-object payload.")
        code = _coerce_bocha_code(payload.get("code"))
        if code not in {0, 200}:
            message_raw = payload.get("message") or payload.get("msg") or "unknown error"
            message = message_raw if isinstance(message_raw, str) else str(message_raw)
            raise _classify_bocha_status(code or 500, message)

        data_raw = payload.get("data")
        data = data_raw if isinstance(data_raw, dict) else {}
        web_pages_raw = data.get("webPages")
        web_pages = web_pages_raw if isinstance(web_pages_raw, dict) else {}
        values_raw = web_pages.get("value")
        values = values_raw if isinstance(values_raw, list) else []
        snippets: list = []
        for item in values:
            if not isinstance(item, dict):
                continue
            summary_raw = item.get("summary")
            snippet_raw = item.get("snippet")
            raw_text = summary_raw if isinstance(summary_raw, str) and summary_raw.strip() else snippet_raw
            source_url_raw = item.get("url")
            title_raw = item.get("name") or item.get("siteName")
            if not isinstance(raw_text, str) or not raw_text.strip():
                continue
            source_url = source_url_raw if isinstance(source_url_raw, str) else None
            source_title = title_raw if isinstance(title_raw, str) else "bocha_result"
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
                        "source": "bocha_search",
                        "query": query,
                        "competitor_id": competitor_id,
                        "date_published": item.get("datePublished")
                        if isinstance(item.get("datePublished"), str)
                        else None,
                    },
                )
            )
        if not snippets:
            raise ChannelError("bocha_search returned no usable snippets.")

        return CollectorObservation(
            channel=self.name,
            args={
                "query": query,
                "max_results": max_results,
            },
            result=ToolObservationResult(
                snippets=snippets,
                metadata={
                    "query": query,
                    "result_count": len(snippets),
                    "provider": "bocha",
                    "competitor_id": competitor_id,
                },
            ),
        )
