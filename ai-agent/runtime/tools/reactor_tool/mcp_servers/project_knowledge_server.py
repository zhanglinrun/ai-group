"""A fixed-corpus, read-only project knowledge MCP server."""

from __future__ import annotations

import json
import re
from functools import lru_cache
from pathlib import Path
from typing import Annotated, Any

from mcp.server.fastmcp import FastMCP
from pydantic import Field

from ._result import bounded_json, error_json


MAX_QUERY_LENGTH = 200
MAX_SEARCH_RESULTS = 5
MAX_FLOW_NAME_LENGTH = 64
_DATA_FILE = Path(__file__).with_name("data") / "project_knowledge.json"

mcp = FastMCP(
    "ai-group-project-knowledge",
    instructions=(
        "只读查询 AI Group 项目的架构知识与演示流程。"
        "所有数据来自服务内置白名单语料，不访问用户提供的外部资源。"
    ),
)


@lru_cache(maxsize=1)
def _load_corpus() -> dict[str, Any]:
    """Load only the server-owned corpus next to this module."""

    with _DATA_FILE.open("r", encoding="utf-8") as handle:
        corpus = json.load(handle)
    if not isinstance(corpus.get("entries"), list) or not isinstance(corpus.get("flows"), dict):
        raise ValueError("invalid built-in project corpus")
    return corpus


def _normalized(value: str) -> str:
    return " ".join(value.casefold().split())


def _query_terms(query: str) -> list[str]:
    terms = re.findall(r"[a-z0-9_+.-]+|[\u4e00-\u9fff]+", query.casefold())
    return list(dict.fromkeys([query.casefold(), *terms]))


def _search_score(entry: dict[str, Any], normalized_query: str) -> int:
    searchable = " ".join(
        str(value)
        for value in (
            entry.get("title", ""),
            entry.get("summary", ""),
            *entry.get("keywords", []),
            *entry.get("highlights", []),
        )
    ).casefold()
    score = 20 if normalized_query in searchable else 0
    score += sum(3 for term in _query_terms(normalized_query) if term and term in searchable)
    return score


@mcp.tool(
    description="从内置只读语料中检索本项目的 Agent、记忆、MCP、支付和治理设计。",
)
def project_search_knowledge(
    query: Annotated[
        str,
        Field(min_length=1, max_length=MAX_QUERY_LENGTH, description="要检索的项目主题"),
    ],
    limit: Annotated[
        int,
        Field(ge=1, le=MAX_SEARCH_RESULTS, description="最多返回的结果数"),
    ] = 3,
) -> str:
    """Search the fixed project corpus and return compact JSON."""

    if not isinstance(query, str):
        return error_json("invalid_argument", "query 必须是字符串。")
    normalized_query = _normalized(query)
    if not normalized_query or len(query) > MAX_QUERY_LENGTH:
        return error_json("invalid_argument", "query 长度必须为 1 到 200 个字符。")
    if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= MAX_SEARCH_RESULTS:
        return error_json("invalid_argument", "limit 必须是 1 到 5 的整数。")

    try:
        entries = _load_corpus()["entries"]
    except (OSError, ValueError, json.JSONDecodeError):
        return error_json("corpus_unavailable", "内置项目语料暂时不可用。")

    ranked: list[tuple[int, dict[str, Any]]] = []
    for entry in entries:
        score = _search_score(entry, normalized_query)
        if score > 0:
            ranked.append((score, entry))
    ranked.sort(key=lambda item: (-item[0], str(item[1].get("id", ""))))

    results = [
        {
            "id": entry["id"],
            "title": entry["title"],
            "summary": entry["summary"],
            "highlights": entry.get("highlights", []),
        }
        for _, entry in ranked[:limit]
    ]
    return bounded_json(
        {
            "ok": True,
            "query": query.strip(),
            "count": len(results),
            "results": results,
        }
    )


@mcp.tool(
    description="读取一个内置的项目端到端流程，可用于演示或架构讲解。",
)
def project_get_flow(
    flow_name: Annotated[
        str,
        Field(min_length=1, max_length=MAX_FLOW_NAME_LENGTH, description="内置流程名称"),
    ],
) -> str:
    """Return one flow selected from the built-in allowlist."""

    if not isinstance(flow_name, str):
        return error_json("invalid_argument", "flow_name 必须是字符串。")
    normalized_name = flow_name.strip().casefold()
    if not normalized_name or len(flow_name) > MAX_FLOW_NAME_LENGTH:
        return error_json("invalid_argument", "flow_name 长度必须为 1 到 64 个字符。")

    try:
        flows = _load_corpus()["flows"]
    except (OSError, ValueError, json.JSONDecodeError):
        return error_json("corpus_unavailable", "内置项目语料暂时不可用。")
    flow = flows.get(normalized_name)
    if flow is None:
        return bounded_json(
            {
                "ok": False,
                "error": {
                    "code": "unknown_flow",
                    "message": "未找到该内置流程。",
                },
                "available_flows": sorted(flows),
            }
        )
    return bounded_json({"ok": True, "flow_name": normalized_name, **flow})


def main() -> None:
    """Run the official FastMCP STDIO transport."""

    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
