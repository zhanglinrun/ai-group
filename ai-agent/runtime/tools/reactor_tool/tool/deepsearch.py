# -*- coding: utf-8 -*-
"""Deterministic evidence collection for the durable ``deep_search`` tool."""

from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime, timezone
from functools import partial
from typing import AsyncGenerator, List

from reactor_tool.model.protocal import StreamMode
from reactor_tool.tool.search_component.search_engine import MixSearch
from reactor_tool.util.log_util import logger


class DeepSearch:
    """Searches and fetches pages but never drafts, reasons, or calls a model."""

    def __init__(self, engines: List[str] | None = None):
        normalized_engines = [engine.strip().lower() for engine in (engines or []) if engine and engine.strip()]
        if not normalized_engines:
            normalized_engines = [engine.strip().lower() for engine in os.getenv("USE_SEARCH_ENGINE", "ddg").split(",") if engine.strip()]
        self.engines = normalized_engines or ["ddg"]
        self._search_single_query = partial(
            MixSearch().search_and_dedup,
            use_ddg="ddg" in self.engines,
            use_bing="bing" in self.engines,
            use_jina="jina" in self.engines,
            use_sogou="sogou" in self.engines,
            use_serp="serp" in self.engines,
            use_exa="exa" in self.engines,
            use_jina_reader=False,
        )
        self.searched_queries: list[str] = []
        self.current_docs: list = []

    async def collect_evidence(self, query: str, request_id: str = "", max_results: int = 10) -> list[dict]:
        """Return only stable evidence candidates; sorting prevents provider ordering from leaking into results."""
        normalized_query = (query or "").strip()
        if not normalized_query:
            return []
        try:
            docs = await self._search_single_query(query=normalized_query, request_id=request_id)
        except Exception as error:  # A search provider must not turn into a fabricated answer.
            logger.warning("deep_search provider failure request_id={} error={}", request_id, error)
            return []
        fetched_at = datetime.now(timezone.utc).isoformat()
        candidates = []
        for doc in docs or []:
            url = str(getattr(doc, "link", "") or "").strip()
            if not url:
                continue
            content = str(getattr(doc, "content", "") or "").strip()
            title = str(getattr(doc, "title", "") or "").strip()
            source_hash = "sha256:" + hashlib.sha256((url + "\n" + content).encode("utf-8")).hexdigest()
            candidates.append({
                "query": normalized_query,
                "title": title,
                "url": url,
                "snippet": content[:4000],
                "sourceType": "web_page",
                "fetchedAt": fetched_at,
                "sourceHash": source_hash,
                "searchEngine": getattr(doc, "data", {}).get("search_engine") if getattr(doc, "data", None) else None,
            })
        candidates.sort(key=lambda item: (item["url"], item["title"], item["sourceHash"]))
        return candidates[: max(1, min(int(max_results or 10), 50))]

    async def run(
        self,
        query: str,
        request_id: str | None = None,
        max_loop: int = 1,
        stream: bool = False,
        stream_mode: StreamMode = StreamMode(),
        *args,
        **kwargs,
    ) -> AsyncGenerator[str, None]:
        """Legacy SSE compatibility: report messages contain candidates, never an LLM conclusion."""
        normalized_query = (query or "").strip()
        yield json.dumps({
            "requestId": request_id,
            "query": normalized_query,
            "searchResult": {"query": [normalized_query], "docs": [[]]},
            "isFinal": False,
            "messageType": "extend",
        }, ensure_ascii=False)
        candidates = await self.collect_evidence(normalized_query, request_id or "")
        self.searched_queries = [normalized_query]
        self.current_docs = candidates
        yield json.dumps({
            "requestId": request_id,
            "query": normalized_query,
            "searchResult": {"query": [normalized_query], "docs": [[
                {"title": item["title"], "link": item["url"], "content": item["snippet"]}
                for item in candidates
            ]]},
            "evidenceCandidates": candidates,
            "isFinal": False,
            "messageType": "search",
        }, ensure_ascii=False)
        payload = {"evidenceCandidates": candidates, "candidateCount": len(candidates)}
        yield json.dumps({
            "requestId": request_id,
            "query": normalized_query,
            "answer": json.dumps(payload, ensure_ascii=False),
            "evidenceCandidates": candidates,
            "isFinal": True,
            "messageType": "report",
        }, ensure_ascii=False)
