# -*- coding: utf-8 -*-
# =====================
# 
# 
# Author: wanghanmin1
# Date:   2025/7/8
# =====================
import asyncio
import json
import os
import time
from functools import partial
from typing import List, AsyncGenerator, Tuple

from reactor_tool.util.log_util import logger
from reactor_tool.util.llm_util import ask_llm
from reactor_tool.model.document import Doc
from reactor_tool.util.log_util import timer
from reactor_tool.tool.search_component.query_process import query_decompose
from reactor_tool.tool.search_component.answer import answer_question
from reactor_tool.tool.search_component.reasoning import search_reasoning
from reactor_tool.tool.search_component.search_engine import MixSearch
from reactor_tool.model.protocal import StreamMode
from reactor_tool.util.file_util import truncate_files
from reactor_tool.model.context import LLMModelInfoFactory


class DeepSearch:
    """深度搜索工具"""

    def __init__(self, engines: List[str] = []):
        normalized_engines = [engine.strip().lower() for engine in engines if engine and engine.strip()]
        if not normalized_engines:
            env_value = os.getenv("USE_SEARCH_ENGINE", "ddg")
            normalized_engines = [engine.strip().lower() for engine in env_value.split(",") if engine.strip()]
        if not normalized_engines:
            normalized_engines = ["ddg"]

        self.engines = normalized_engines
        use_ddg = "ddg" in normalized_engines
        use_bing = "bing" in normalized_engines
        use_jina = "jina" in normalized_engines
        use_sogou = "sogou" in normalized_engines
        use_serp = "serp" in normalized_engines
        use_exa = "exa" in normalized_engines
        self._search_single_query = partial(
            MixSearch().search_and_dedup,
            use_ddg=use_ddg,
            use_bing=use_bing,
            use_jina=use_jina,
            use_sogou=use_sogou,
            use_serp=use_serp,
            use_exa=use_exa,
            use_jina_reader=False,
        )
        self.searched_queries = []
        self.current_docs = []

    def search_docs_str(self, model: str = None) -> str:
        current_docs_str = ""
        max_tokens = LLMModelInfoFactory.get_context_length(model)
        truncate_docs = truncate_files(self.current_docs, max_tokens=int(max_tokens * 0.8)) if model else self.current_docs
        for i, doc in enumerate(truncate_docs, start=1):
            current_docs_str += f"文档编号〔{i}〕. \n{doc.to_html()}\n"
        return current_docs_str

    @timer()
    async def run(
            self,
            query: str,
            request_id: str = None,
            max_loop: int = 1,
            stream: bool = False,
            stream_mode: StreamMode = StreamMode(),
            *args,
            **kwargs
    ) -> AsyncGenerator[str, None]:
        """深度搜索回复（流式）"""

        # deep_search 运行在 Agent 的有限 run budget 内，不能设置成远大于
        # Agent budget 的 20 分钟，否则上游只能在客户端断开后被动取消。
        total_timeout_seconds = int(os.getenv("DEEPSEARCH_TOTAL_TIMEOUT_SECONDS", "150"))
        deadline = time.monotonic() + total_timeout_seconds

        def _remaining_timeout() -> float:
            return max(0.1, deadline - time.monotonic())

        current_loop = 1
        try:
            # 执行深度搜索循环
            while current_loop <= max_loop:
                logger.info(f"{request_id} 第 {current_loop} 轮深度搜索...")
                # 查询分解
                sub_queries = await asyncio.wait_for(
                    query_decompose(query=query),
                    timeout=_remaining_timeout(),
                )

                yield json.dumps({
                    "requestId": request_id,
                    "query": query,
                    "searchResult": {"query": sub_queries, "docs": [[]] * len(sub_queries)},
                    "isFinal": False,
                    "messageType": "extend"
                }, ensure_ascii=False)

                await asyncio.sleep(0.1)

                # 去除已经检索过的query
                sub_queries = [sub_query for sub_query in sub_queries
                               if sub_query not in self.searched_queries]
                # 并行搜索并去重
                searched_docs, docs_list = await asyncio.wait_for(
                    self._search_queries_and_dedup(
                        queries=sub_queries,
                        request_id=request_id,
                    ),
                    timeout=_remaining_timeout(),
                )

                truncate_len = int(os.getenv("SINGLE_PAGE_MAX_SIZE", 200))
                yield json.dumps(
                    {
                        "requestId": request_id,
                        "query": query,
                        "searchResult": {
                            "query": sub_queries,
                            "docs": [[d.to_dict(truncate_len=truncate_len) for d in docs_l] for docs_l in docs_list]
                        },
                        "isFinal": False,
                        "messageType": "search"
                    }, ensure_ascii=False)

                # 更新上下文
                self.current_docs.extend(searched_docs)
                self.searched_queries.extend(sub_queries)

                # 如果是最后一轮，直接跳出
                if current_loop == max_loop:
                    break

                # 推理验证是否需要继续搜索
                reasoning_result = await asyncio.wait_for(
                    asyncio.to_thread(
                        search_reasoning,
                        request_id=request_id,
                        query=query,
                        content=self.search_docs_str(os.getenv("SEARCH_REASONING_MODEL")),
                    ),
                    timeout=_remaining_timeout(),
                )

                # 如果推理判断已经可以回答，跳出循环
                if reasoning_result.get("is_verify", "1") in ["1", 1]:
                    logger.info(f"{request_id} reasoning 判断没有得到新的查询，流程结束")
                    break

                current_loop += 1

            # 生成最终答案
            answer = ""
            acc_content = ""
            acc_token = 0
            answer_stream = answer_question(
                query=query, search_content=self.search_docs_str(os.getenv("SEARCH_ANSWER_MODEL"))
            )
            while True:
                try:
                    chunk = await asyncio.wait_for(
                        answer_stream.__anext__(),
                        timeout=_remaining_timeout(),
                    )
                except StopAsyncIteration:
                    break

                if stream:
                    if acc_token >= stream_mode.token:
                        yield json.dumps({
                            "requestId": request_id,
                            "query": query,
                            "searchResult": {
                                "query": [],
                                "docs": [],
                            },
                            "answer": acc_content,
                            "isFinal": False,
                            "messageType": "report"
                        }, ensure_ascii=False)
                        acc_content = ""
                        acc_token = 0
                    acc_content += chunk
                    acc_token += 1
                answer += chunk
            if stream and acc_content:
                yield json.dumps({
                    "requestId": request_id,
                    "query": query,
                    "searchResult": {
                        "query": [],
                        "docs": [],
                    },
                    "answer": acc_content,
                    "isFinal": False,
                    "messageType": "report"
                }, ensure_ascii=False)
            yield json.dumps({
                    "requestId": request_id,
                    "query": query,
                    "searchResult": {
                        "query": [],
                        "docs": [],
                    },
                    "answer": "" if stream else answer,
                    "isFinal": True,
                    "messageType": "report"
                }, ensure_ascii=False)
        except asyncio.TimeoutError:
            logger.warning(f"{request_id} deepsearch total timeout after {total_timeout_seconds}s")
            fallback_answer = "深度搜索超时，已返回当前可用结果，请基于已有搜索内容继续处理。"
            yield json.dumps({
                "requestId": request_id,
                "query": query,
                "searchResult": {
                    "query": [],
                    "docs": [],
                },
                "answer": fallback_answer,
                "isFinal": True,
                "messageType": "report"
            }, ensure_ascii=False)

    async def _search_queries_and_dedup(
            self,
            queries: List[str],
            request_id: str,
    ) -> Tuple[List[Doc], List[List[Doc]]]:
        """异步并行搜索多个查询并去重"""
        def _run_async(*args, **kwargs):
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            s_result = loop.run_until_complete(self._search_single_query(*args, **kwargs))
            loop.close()
            return s_result

        # 在线程中运行同步搜索客户端，但通过 asyncio Future 等待，
        # 这样上层 asyncio.wait_for 才能在超时时立即取消等待，不阻塞
        # Reactor 的事件循环、SSE heartbeat 和最终 fallback。
        tasks = [
            asyncio.create_task(asyncio.to_thread(_run_async, query, request_id))
            for query in queries
        ]
        raw_results = await asyncio.gather(*tasks, return_exceptions=True)
        results = []
        for query, raw_result in zip(queries, raw_results):
            if isinstance(raw_result, Exception):
                logger.warning(f"{request_id} search query skipped query={query!r} error={raw_result}")
                results.append([])
            else:
                results.append(raw_result)
        all_docs = [doc for docs in results for doc in docs]
        # 去重
        seen_content = set()
        deduped_docs = []
        for doc in all_docs:
            if doc.content and doc.content not in seen_content:
                deduped_docs.append(doc)
                seen_content.add(doc.content)
        return deduped_docs, results
