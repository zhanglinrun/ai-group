# -*- coding: utf-8 -*-
# =====================
#
#
# Author: liumin.423
# Date:   2025/7/7
# =====================
import asyncio
import json
import os
import re
import tempfile
import time
from pathlib import Path

from dotenv import load_dotenv
from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse
from jinja2 import Template
from loguru import logger
from sse_starlette import ServerSentEvent, EventSourceResponse

from reactor_tool.model.code import ActionOutput, CodeOuput
from reactor_tool.model.protocal import (
    CIRequest,
    CalEngineRequest,
    ReportRequest,
    DeepSearchRequest,
    ScriptRunnerRequest,
    ImageGenerationRequest,
    WebFetchRequest,
)
from reactor_tool.tool.web_fetcher import WebFetcher
from reactor_tool.tool.code_interpreter_policy import CodeExecutionPermissionError
from reactor_tool.util.file_util import upload_file, upload_file_by_path
from reactor_tool.util.pptx_util import render_pptx, requested_slide_limit, safe_pptx_name
from reactor_tool.util.report_file_util import (
    render_strict_query_markdown,
    sanitize_report_html_content,
    sanitize_strict_grounded_markdown,
)
from reactor_tool.util.prompt_util import get_prompt
from reactor_tool.util.middleware_util import RequestHandlerRoute
load_dotenv()



router = APIRouter(route_class=RequestHandlerRoute)


def _error_response(status_code: int, message: str) -> JSONResponse:
    """统一错误响应结构，便于 Java 侧直连排障。"""
    return JSONResponse(status_code=status_code, content={"message": message})


@router.post("/code_interpreter")
async def post_code_interpreter(
    body: CIRequest,
):
    # 按需导入重型依赖，避免仅使用轻量路由时被 smolagents 等可选依赖阻塞。
    from reactor_tool.tool.code_interpreter import code_interpreter_agent

     # 处理文件路径
    if body.file_names:
        for idx, f_name in enumerate(body.file_names):
            if not f_name.startswith("/") and not f_name.startswith("http"):
                body.file_names[idx] = f"{os.getenv('FILE_SERVER_URL')}/preview/{body.request_id}/{f_name}"

    async def _stream():
        acc_content = ""
        acc_token = 0
        acc_time = time.time()
        try:
            async for chunk in code_interpreter_agent(
                task=body.task,
                file_names=body.file_names,
                request_id=body.request_id,
                stream=True,
                permission_profile=body.permission_profile,
            ):


                if isinstance(chunk, CodeOuput):
                    yield ServerSentEvent(
                        data=json.dumps(
                            {
                                "requestId": body.request_id,
                                "code": chunk.code,
                                "fileInfo": chunk.file_list,
                                "isFinal": False,
                            },
                            ensure_ascii=False,
                        )
                    )
                elif isinstance(chunk, ActionOutput):
                    yield ServerSentEvent(
                        data=json.dumps(
                            {
                                "requestId": body.request_id,
                                "codeOutput": chunk.content,
                                "fileInfo": chunk.file_list,
                                "isFinal": True,
                            },
                            ensure_ascii=False,
                        )
                    )
                    yield ServerSentEvent(data="[DONE]")
                elif isinstance(chunk, str):
                    acc_content += chunk
                    acc_token += 1
                    if body.stream_mode.mode == "general":
                        yield ServerSentEvent(
                            data=json.dumps(
                                {"requestId": body.request_id, "data": chunk, "isFinal": False},
                                ensure_ascii=False,
                            )
                        )
                    elif body.stream_mode.mode == "token":
                        if acc_token >= body.stream_mode.token:
                            yield ServerSentEvent(
                                data=json.dumps(
                                    {
                                        "requestId": body.request_id,
                                        "data": acc_content,
                                        "isFinal": False,
                                    },
                                    ensure_ascii=False,
                                )
                            )
                            acc_token = 0
                            acc_content = ""
                    elif body.stream_mode.mode == "time":
                        if time.time() - acc_time > body.stream_mode.time:
                            yield ServerSentEvent(
                                data=json.dumps(
                                    {
                                        "requestId": body.request_id,
                                        "data": acc_content,
                                        "isFinal": False,
                                    },
                                    ensure_ascii=False,
                                )
                            )
                            acc_time = time.time()
                            acc_content = ""
                    if body.stream_mode.mode in ["time", "token"] and acc_content:
                        yield ServerSentEvent(
                            data=json.dumps(
                                {
                                    "requestId": body.request_id,
                                    "data": acc_content,
                                    "isFinal": False,
                                },
                                ensure_ascii=False,
                            )
                        )
        except CodeExecutionPermissionError as exc:
            yield ServerSentEvent(
                data=json.dumps(
                    {
                        "requestId": body.request_id,
                        "data": exc.to_public_payload(),
                        "isFinal": True,
                    },
                    ensure_ascii=False,
                )
            )
            yield ServerSentEvent(data="[DONE]")
            

    if body.stream:
        return EventSourceResponse(
            _stream(),
            ping_message_factory=lambda: ServerSentEvent(data="heartbeat"),
            ping=15,
        )
    else:
        content = ""
        try:
            async for chunk in code_interpreter_agent(
                task=body.task,
                file_names=body.file_names,
                request_id=body.request_id,
                stream=body.stream,
                permission_profile=body.permission_profile,
            ):
                # stream=False yields a single RunResult from smolagents
                if hasattr(chunk, "output"):
                    content = str(chunk.output) if chunk.output is not None else ""
                    break
                if isinstance(chunk, str):
                    content += chunk
        except CodeExecutionPermissionError as exc:
            return JSONResponse(
                status_code=400,
                content={
                    "code": 400,
                    "data": exc.to_public_payload(),
                    "requestId": body.request_id,
                },
            )
        if not content:
            content = ""
        out_file_name = body.file_name or "code_output"
        out_file_type = getattr(body, "file_type", None) or "md"
        if out_file_type == "ppt":
            out_file_type = "html"
        file_info = [
            await upload_file(
                content=content,
                file_name=out_file_name,
                request_id=body.request_id,
                file_type=out_file_type,
            )
        ]
        return {
            "code": 200,
            "data": content,
            "fileInfo": file_info,
            "requestId": body.request_id,
        }


@router.post("/report")
async def post_report(
    body: ReportRequest,
):
    from reactor_tool.tool.report import report
    from reactor_tool.tool.report import _requires_strict_grounding

    def _looks_like_nested_tool_call(content: str) -> bool:
        normalized = (content or "").strip()
        if not normalized:
            return True
        compact = re.sub(r"\s+", " ", normalized).lower()
        without_fences = re.sub(r"```(?:tool_code|json|python)?|```", "", compact).strip()
        return (
            len(normalized) < 256
            and (
                "tool_code" in compact
                or re.fullmatch(r"report_tool\s*\([^)]*\)\s*", without_fences) is not None
            )
        )

    async def _collect_report_chunks(task: str):
        chunks = []
        async for chunk in report(
            task=task,
            original_query=body.query,
            file_names=body.file_names,
            file_type=body.file_type,
            template_type=body.template_type,
        ):
            chunks.append(chunk)
        return chunks

    async def _generate_report_chunks():
        has_markdown_bullets = any(
            line.strip().startswith(("- ", "* "))
            for line in (body.query or "").splitlines()
        )
        if (
            body.file_type == "markdown"
            and not body.file_names
            and has_markdown_bullets
            and _requires_strict_grounding(body.query, body.task)
        ):
            try:
                return [render_strict_query_markdown(body.query or "", body.file_name)]
            except ValueError as exc:
                raise HTTPException(status_code=422, detail=str(exc)) from exc
        chunks = await _collect_report_chunks(body.task)
        content = "".join(chunks)
        if _looks_like_nested_tool_call(content):
            retry_task = (
                f"{body.task}\n\n"
                "上一次输出被判定为嵌套工具调用，不是报告。"
                "你已处于 report_tool 内部；不得输出 report_tool(...) 或 tool_code，"
                "请立即输出完整的报告正文。"
            )
            chunks = await _collect_report_chunks(retry_task)
            content = "".join(chunks)
        if _looks_like_nested_tool_call(content):
            raise RuntimeError("报告生成模型连续返回嵌套工具调用，已拒绝上传伪报告产物")
        return chunks

    async def _store_report(content: str):
        if body.file_type == "ppt":
            content = sanitize_report_html_content(content)
            with tempfile.TemporaryDirectory(prefix="reactor-ppt-") as temp_dir:
                output_path = Path(temp_dir) / safe_pptx_name(body.file_name)
                render_pptx(
                    content,
                    str(output_path),
                    requested_slide_limit(body.query, body.task),
                )
                file_info = await upload_file_by_path(str(output_path), body.request_id)
            if not file_info:
                raise RuntimeError("PPTX generation succeeded but artifact upload failed")
            return content, [file_info]
        if body.file_type == "html":
            content = sanitize_report_html_content(content)
        elif body.file_type == "markdown" and _requires_strict_grounding(body.query, body.task):
            content = sanitize_strict_grounded_markdown(content)
        file_info = await upload_file(
            content=content,
            file_name=body.file_name,
            request_id=body.request_id,
            file_type=body.file_type,
        )
        return content, [file_info]

    # 处理文件路径
    if body.file_names:
        for idx, f_name in enumerate(body.file_names):
            if not f_name.startswith("/") and not f_name.startswith("http"):
                body.file_names[idx] = f"{os.getenv('FILE_SERVER_URL')}/preview/{body.request_id}/{f_name}"
    
    async def _stream():
        content = ""
        acc_content = ""
        acc_token = 0
        acc_time = time.time()
        for chunk in await _generate_report_chunks():
            content += chunk
            acc_content += chunk
            acc_token += 1
            if body.stream_mode.mode == "general":
                yield ServerSentEvent(
                    data=json.dumps(
                        {"requestId": body.request_id, "data": chunk, "isFinal": False},
                        ensure_ascii=False,
                    )
                )
            elif body.stream_mode.mode == "token":
                if acc_token >= body.stream_mode.token:
                    yield ServerSentEvent(
                        data=json.dumps(
                            {
                                "requestId": body.request_id,
                                "data": acc_content,
                                "isFinal": False,
                            },
                            ensure_ascii=False,
                        )
                    )
                    acc_token = 0
                    acc_content = ""
            elif body.stream_mode.mode == "time":
                if time.time() - acc_time > body.stream_mode.time:
                    yield ServerSentEvent(
                        data=json.dumps(
                            {
                                "requestId": body.request_id,
                                "data": acc_content,
                                "isFinal": False,
                            },
                            ensure_ascii=False,
                        )
                    )
                    acc_time = time.time()
                    acc_content = ""
        if body.stream_mode.mode in ["time", "token"] and acc_content:
            yield ServerSentEvent(
                data=json.dumps({"requestId": body.request_id, "data": acc_content, "isFinal": False},
                                ensure_ascii=False))
        content, file_info = await _store_report(content)
        yield ServerSentEvent(data=json.dumps(
            {"requestId": body.request_id, "data": content, "fileInfo": file_info,
             "isFinal": True}, ensure_ascii=False))
        yield ServerSentEvent(data="[DONE]")

    if body.stream:
        return EventSourceResponse(
            _stream(),
            ping_message_factory=lambda: ServerSentEvent(data="heartbeat"),
            ping=15,
        )
    else:
        content = "".join(await _generate_report_chunks())
        content, file_info = await _store_report(content)
        return {"code": 200, "data": content, "fileInfo": file_info, "requestId": body.request_id}


@router.post("/image_generation")
async def post_image_generation(body: ImageGenerationRequest):
    """图片生成端点，支持文生图与图生图两种模式。"""
    from reactor_tool.tool.image_generation import generate_images

    def _normalize_image_reference(reference: str) -> str:
        normalized = (reference or "").strip()
        if not normalized:
            return ""
        if normalized.startswith("/") or normalized.startswith("http") or normalized.startswith("data:"):
            return normalized

        file_server_url = (os.getenv("FILE_SERVER_URL") or "").rstrip("/")
        if not file_server_url:
            return normalized
        return f"{file_server_url}/preview/{body.request_id}/{normalized}"

    if body.file_names:
        body.file_names = [
            normalized
            for reference in body.file_names
            if (normalized := _normalize_image_reference(reference))
        ]
    if body.mask_file_names:
        body.mask_file_names = [
            _normalize_image_reference(reference) for reference in body.mask_file_names
        ]

    async def _run_generation():
        try:
            return await generate_images(body)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except HTTPException:
            raise
        except Exception as exc:
            logger.exception("image_generation request failed")
            raise HTTPException(status_code=500, detail=str(exc)) from exc

    async def _stream():
        yield ServerSentEvent(
            data=json.dumps(
                {
                    "requestId": body.request_id,
                    "data": "开始执行图片生成任务...",
                    "isFinal": False,
                },
                ensure_ascii=False,
            )
        )
        try:
            result = await _run_generation()
        except HTTPException as exc:
            yield ServerSentEvent(
                data=json.dumps(
                    {
                        "requestId": body.request_id,
                        "data": exc.detail,
                        "isFinal": True,
                    },
                    ensure_ascii=False,
                )
            )
            yield ServerSentEvent(data="[DONE]")
            return

        yield ServerSentEvent(
            data=json.dumps(
                {
                    **result,
                    "isFinal": True,
                },
                ensure_ascii=False,
            )
        )
        yield ServerSentEvent(data="[DONE]")

    if body.stream:
        return EventSourceResponse(
            _stream(),
            ping_message_factory=lambda: ServerSentEvent(data="heartbeat"),
            ping=15,
        )

    result = await _run_generation()
    return result


@router.post("/deepsearch")
async def post_deepsearch(
    body: DeepSearchRequest,
):
    """深度搜索端点"""
    from reactor_tool.tool.deepsearch import DeepSearch

    deepsearch = DeepSearch(engines=body.search_engines)
    async def _stream():
        async for chunk in deepsearch.run(
                query=body.query,
                request_id=body.request_id,
                max_loop=body.max_loop,
                stream=True,
                stream_mode=body.stream_mode,
        ):
            yield ServerSentEvent(data=chunk)
        yield ServerSentEvent(data="[DONE]")

    return EventSourceResponse(_stream(), ping_message_factory=lambda: ServerSentEvent(data="heartbeat"), ping=15)


@router.post("/web_fetch")
async def post_web_fetch(body: WebFetchRequest):
    """单网页抓取端点，始终把完整正文沉淀为文件产物。"""
    try:
        result = await WebFetcher().fetch(body)
        file_info = [
            await upload_file(
                content=result.full_content,
                file_name=result.file_name,
                request_id=body.request_id,
                file_type="markdown",
            )
        ]
        return {
            "code": 200,
            "data": result.to_response_data(),
            "fileInfo": file_info,
            "requestId": body.request_id,
        }
    except ValueError as exc:
        logger.warning("web_fetch request failed: {}", exc)
        return JSONResponse(
            status_code=400,
            content={
                "code": 400,
                "message": str(exc),
                "requestId": body.request_id,
            },
        )
    except Exception as exc:
        logger.exception("web_fetch request failed unexpectedly")
        return JSONResponse(
            status_code=502,
            content={
                "code": 502,
                "message": str(exc),
                "requestId": body.request_id,
            },
        )


@router.post("/cal_engine")
async def cal_engine(body: CalEngineRequest):
    """根据用户获取数据和用户 query 生成指标计算公式"""
    from reactor_tool.util.llm_util import ask_llm

    prompt = Template(get_prompt("analysis")["cal_engine_prompt"]).render(
        query=body.query,
        data=body.data,
    )

    async for chunk in ask_llm(messages=prompt, model=os.getenv("CAL_ENGINE_MODEL", "qwen-vl-max"), only_content=True):
        expression = chunk
    return {"code": 200, "expression": expression, "request_id": body.request_id, "query": body.query}


@router.post("/script_runner")
async def post_script_runner(body: ScriptRunnerRequest):
    """skill 脚本执行端点"""
    from reactor_tool.tool.script_runner import run_script_request

    response = await run_script_request(body)
    return response.model_dump(by_alias=True)


