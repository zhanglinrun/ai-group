from __future__ import annotations

import asyncio
import json
import os
import time
import urllib.request
from urllib.parse import urlparse

from agents.tools.search_web import TavilySearchChannel

QUERIES = [
    "Cursor AI enterprise pricing 2025 official",
    "通义灵码 企业版 定价 2025 官方",
    "文心 Comate 企业版 价格 功能 官方",
]


def _host(url: object) -> str | None:
    if not isinstance(url, str):
        return None
    return urlparse(url).netloc or None


async def _test_tavily() -> list[dict[str, object]]:
    channel = TavilySearchChannel()
    rows: list[dict[str, object]] = []
    for query in QUERIES:
        started = time.perf_counter()
        try:
            observation = await channel.invoke(query=query, max_results=5)
            latency_ms = int((time.perf_counter() - started) * 1000)
            snippets = observation.result.snippets
            rows.append(
                {
                    "provider": "tavily_search_basic",
                    "query": query,
                    "ok": True,
                    "latency_ms": latency_ms,
                    "result_count": len(snippets),
                    "hosts": [_host(item.source_url) for item in snippets[:5]],
                    "titles": [item.source_title for item in snippets[:3]],
                    "text_lens": [len(item.quote or "") for item in snippets[:5]],
                }
            )
        except Exception as exc:  # noqa: BLE001 - probe must report provider boundary failures.
            rows.append(
                {
                    "provider": "tavily_search_basic",
                    "query": query,
                    "ok": False,
                    "error_class": type(exc).__name__,
                    "error": str(exc)[:240],
                }
            )
    return rows


def _extract_volc_output(data: dict[str, object]) -> tuple[list[dict[str, object]], list[str]]:
    output = data.get("output")
    output_items = output if isinstance(output, list) else []
    citations: list[dict[str, object]] = []
    text_parts: list[str] = []
    for item in output_items:
        if not isinstance(item, dict):
            continue
        content = item.get("content")
        if not isinstance(content, list):
            continue
        for part in content:
            if not isinstance(part, dict):
                continue
            text = part.get("text")
            if isinstance(text, str):
                text_parts.append(text)
            annotations = part.get("annotations")
            if isinstance(annotations, list):
                citations.extend(
                    annotation
                    for annotation in annotations
                    if isinstance(annotation, dict)
                    and annotation.get("type") == "url_citation"
                )
    return citations, text_parts


def _test_volc(*, forced: bool) -> list[dict[str, object]]:
    api_key = os.environ.get("DOUBAO_API_KEY")
    model = os.environ.get("LLM_MODEL_RESEARCH") or os.environ.get("DOUBAO_EP")
    rows: list[dict[str, object]] = []
    if not api_key or not model:
        return [
            {
                "provider": "volc_responses_web_search_forced" if forced else "volc_responses_web_search_auto",
                "query": query,
                "ok": False,
                "error_class": "MissingEnv",
                "error": "DOUBAO_API_KEY or DOUBAO_EP is missing.",
            }
            for query in QUERIES
        ]

    for query in QUERIES:
        payload: dict[str, object] = {
            "model": model,
            "input": [
                {
                    "role": "system",
                    "content": (
                        "你是竞品分析资料检索器。请优先搜索官方来源、价格页、"
                        "企业版页面，并用中文简短列出来源。"
                    ),
                },
                {"role": "user", "content": query},
            ],
            "tools": [{"type": "web_search", "limit": 5}],
            "max_tool_calls": 2,
        }
        if forced:
            payload["tool_choice"] = "required"
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            "https://ark.cn-beijing.volces.com/api/v3/responses",
            data=body,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                data = json.loads(response.read().decode("utf-8"))
            latency_ms = int((time.perf_counter() - started) * 1000)
            citations, text_parts = _extract_volc_output(data)
            rows.append(
                {
                    "provider": (
                        "volc_responses_web_search_forced"
                        if forced
                        else "volc_responses_web_search_auto"
                    ),
                    "query": query,
                    "ok": True,
                    "latency_ms": latency_ms,
                    "model": data.get("model"),
                    "citation_count": len(citations),
                    "hosts": [_host(item.get("url")) for item in citations[:5]],
                    "titles": [item.get("title") for item in citations[:3]],
                    "answer_chars": sum(len(text) for text in text_parts),
                    "answer_preview": " ".join(text_parts)[:240],
                }
            )
        except Exception as exc:  # noqa: BLE001 - probe must report provider boundary failures.
            rows.append(
                {
                    "provider": (
                        "volc_responses_web_search_forced"
                        if forced
                        else "volc_responses_web_search_auto"
                    ),
                    "query": query,
                    "ok": False,
                    "error_class": type(exc).__name__,
                    "error": str(exc)[:260],
                }
            )
    return rows


async def main() -> None:
    result: list[dict[str, object]] = []
    result.extend(await _test_tavily())
    result.extend(_test_volc(forced=False))
    result.extend(_test_volc(forced=True))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
