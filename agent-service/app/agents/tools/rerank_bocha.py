from __future__ import annotations

from functools import lru_cache
from urllib.parse import urljoin

import httpx

from core.config import settings
from service.collector.errors import ChannelError, FetchTimeout, RateLimited
from service.collector.rate_limiter import PerHostLimiter
from utils.logger import get_logger

log = get_logger("agents.tools.rerank_bocha")


@lru_cache
def _get_bocha_rerank_rate_limiter() -> PerHostLimiter:
    return PerHostLimiter(qps=settings.COLLECTOR_PER_HOST_QPS)


def _bocha_rerank_url() -> str:
    return urljoin(settings.BOCHA_BASE_URL.rstrip("/") + "/", "rerank")


def _classify_bocha_rerank_status(status_code: int, message: str) -> ChannelError:
    if status_code in {403, 429}:
        return RateLimited(f"bocha rerank rate limited or quota exhausted: {message}")
    if status_code == 401:
        return ChannelError(f"bocha rerank authentication failed: {message}")
    return ChannelError(f"bocha rerank failed with status {status_code}: {message}")


def _coerce_bocha_code(value: object) -> int | None:
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip().isdigit():
        return int(value.strip())
    return None


def _coerce_score(value: object) -> float | None:
    if isinstance(value, (int, float)):
        score = float(value)
    elif isinstance(value, str):
        try:
            score = float(value.strip())
        except ValueError:
            return None
    else:
        return None
    if score < 0:
        return 0.0
    if score > 1:
        return 1.0
    return score


def _score_value(item: dict[str, object]) -> object:
    if "relevance_score" in item:
        return item.get("relevance_score")
    return item.get("score")


async def _request_bocha_rerank(
    *,
    query: str,
    documents: list[str],
    top_n: int,
) -> list[tuple[int, float]]:
    if not query.strip():
        raise ChannelError("bocha rerank requires non-empty query.")
    if not documents:
        return []
    if top_n <= 0 or top_n > len(documents):
        raise ChannelError("bocha rerank top_n must be in range [1, len(documents)].")
    if not settings.BOCHA_API_KEY:
        raise ChannelError("BOCHA_API_KEY is required for bocha rerank.")

    await _get_bocha_rerank_rate_limiter().acquire(
        "api.bochaai.com",
        timeout_seconds=float(settings.COLLECTOR_FETCH_TIMEOUT_S),
    )
    timeout = httpx.Timeout(float(settings.COLLECTOR_FETCH_TIMEOUT_S))
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(
                _bocha_rerank_url(),
                headers={
                    "Authorization": f"Bearer {settings.BOCHA_API_KEY}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": settings.BOCHA_RERANK_MODEL,
                    "query": query,
                    "documents": documents,
                    "top_n": top_n,
                    "return_documents": False,
                },
            )
        response.raise_for_status()
        payload = response.json()
    except httpx.TimeoutException as exc:
        raise FetchTimeout(f"bocha rerank timed out: {exc}") from exc
    except httpx.HTTPStatusError as exc:
        message = str(exc.response.text or exc)
        raise _classify_bocha_rerank_status(exc.response.status_code, message) from exc
    except httpx.RequestError as exc:
        raise ChannelError(f"bocha rerank request failed: {exc}") from exc
    except ValueError as exc:
        raise ChannelError(f"bocha rerank returned invalid JSON: {exc}") from exc

    if not isinstance(payload, dict):
        raise ChannelError("bocha rerank returned non-object payload.")
    code_raw = payload.get("code")
    code = _coerce_bocha_code(code_raw)
    if code_raw is not None and code not in {0, 200}:
        message_raw = payload.get("message") or payload.get("msg") or "unknown error"
        message = message_raw if isinstance(message_raw, str) else str(message_raw)
        raise _classify_bocha_rerank_status(code or 500, message)

    data_raw = payload.get("data")
    data = data_raw if isinstance(data_raw, dict) else payload
    results_raw = data.get("results")
    results = results_raw if isinstance(results_raw, list) else []
    ranked: list[tuple[int, float]] = []
    seen: set[int] = set()
    for item in results:
        if not isinstance(item, dict):
            continue
        index_raw = item.get("index")
        score = _coerce_score(_score_value(item))
        if not isinstance(index_raw, int) or score is None:
            continue
        if index_raw < 0 or index_raw >= len(documents) or index_raw in seen:
            continue
        seen.add(index_raw)
        ranked.append((index_raw, score))
    if not ranked:
        raise ChannelError("bocha rerank returned no usable scores.")
    return ranked


async def rerank(
    *,
    query: str,
    documents: list[str],
    top_n: int | None = None,
) -> list[tuple[int, float | None]]:
    effective_top_n = top_n if top_n is not None else len(documents)
    try:
        ranked = await _request_bocha_rerank(
            query=query,
            documents=documents,
            top_n=effective_top_n,
        )
    except (ChannelError, FetchTimeout, RateLimited) as exc:
        log.warning(
            "bocha.rerank.fail_soft",
            error_class=type(exc).__name__,
            error_preview=str(exc)[:200],
            document_count=len(documents),
        )
        return [(index, None) for index in range(len(documents))]
    return [(index, score) for index, score in ranked]
