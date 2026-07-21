# -*- coding: utf-8 -*-
# =====================
# 
# 
# Author: liumin.423
# Date:   2025/7/7
# =====================
import os
import re
from datetime import datetime
from typing import Optional, List, Literal, AsyncGenerator

from dotenv import load_dotenv
from jinja2 import Template
from loguru import logger

from reactor_tool.util.file_util import download_all_files, truncate_files, flatten_search_file
from reactor_tool.util.prompt_util import get_prompt
from reactor_tool.util.llm_util import ask_llm
from reactor_tool.util.log_util import timer
from reactor_tool.model.context import LLMModelInfoFactory

load_dotenv()


_STRICT_GROUNDING_PATTERNS = tuple(
    re.compile(pattern, re.IGNORECASE | re.DOTALL)
    for pattern in (
        r"(?:仅|只)(?:允许|能|可)?(?:使用|依据|基于|采用)",
        r"(?:必须|务必)?严格(?:依据|基于|限于)",
        r"(?:禁止|不得|不要)[^。；;\n]{0,40}(?:推测|猜测|补写|补充|扩写|编造|杜撰|虚构|臆造)",
        r"(?:未提供|未知)[^。；;\n]{0,30}(?:不要|不得|禁止)[^。；;\n]{0,20}(?:输出|补充|推测|编造)",
        r"source[\s_-]*of[\s_-]*truth",
        r"closed[\s_-]*world",
        r"only\s+(?:use|based\s+on|rely\s+on)",
        r"(?:do\s+not|don't|must\s+not)\s+(?:infer|speculate|invent|fabricate|add\s+unsupported)",
    )
)


def _requires_strict_grounding(original_query: Optional[str], task: Optional[str]) -> bool:
    """Detect explicit closed-world/source-of-truth instructions without restricting normal reports."""
    text = "\n".join(part.strip() for part in (original_query, task) if part and part.strip())
    return any(pattern.search(text) for pattern in _STRICT_GROUNDING_PATTERNS)


def _build_report_messages(
        report_prompts: dict,
        user_prompt: str,
        original_query: Optional[str],
        task: Optional[str],
        base_system_prompt: Optional[str] = None,
) -> List[dict]:
    """Build report messages with grounding rules at system priority."""
    strict_grounding = _requires_strict_grounding(original_query, task)
    grounding_prompt = Template(report_prompts["grounding_prompt"]).render(
        strict_grounding=strict_grounding,
    )
    messages = []
    if base_system_prompt:
        messages.append({"role": "system", "content": base_system_prompt})
    messages.append({"role": "system", "content": grounding_prompt})
    messages.append({"role": "user", "content": user_prompt})
    return messages


def _resolve_report_model(explicit_model: Optional[str] = None) -> str:
    """Resolve the report model without silently routing to an unrelated provider model."""
    candidates = (
        explicit_model,
        os.getenv("REPORT_MODEL"),
        os.getenv("DEFAULT_MODEL"),
    )
    for candidate in candidates:
        if candidate and candidate.strip():
            return candidate.strip()
    raise RuntimeError("REPORT_MODEL or DEFAULT_MODEL must be configured")


@timer(key="enter")
async def report(
        task: str,
        file_names: Optional[List[str]] = tuple(),
        model: Optional[str] = None,
        file_type: Literal["markdown", "html", "ppt"] = "markdown",
        template_type: str = "html",
        original_query: Optional[str] = None,
) -> AsyncGenerator:
    report_factory = {
        "ppt": ppt_report,
        "markdown": markdown_report,
        "html": html_report,
    }
    model = _resolve_report_model(model)
    if file_type.lower() == "html":
        async for chunk in html_report(
                task, file_names, model, template_type=template_type, original_query=original_query):
            yield chunk
    else:
        async for chunk in report_factory[file_type](
                task, file_names, model, original_query=original_query):
            yield chunk


@timer(key="enter")
async def ppt_report(
        task: str,
        file_names: Optional[List[str]] = tuple(),
        model: Optional[str] = None,
        temperature: float = None,
        top_p: float = 0.6,
        original_query: Optional[str] = None,
) -> AsyncGenerator:
    model = _resolve_report_model(model)
    files = await download_all_files(file_names)
    flat_files = []

    # 1. 首先解析 md html 文件，没有这部分文件则使用全部
    filtered_files = [f for f in files if f["file_name"].split(".")[-1] in ["md", "html"]
                      and not f["file_name"].endswith("_搜索结果.md")] or files
    for f in filtered_files:
        # 对于搜索文件有结构，需要重新解析
        if f["file_name"].endswith("_search_result.txt"):
            flat_files.extend(flatten_search_file(f))
        else:
            flat_files.append(f)

    truncate_flat_files = truncate_files(flat_files, max_tokens=int(LLMModelInfoFactory.get_context_length(model) * 0.8))
    report_prompts = get_prompt("report")
    prompt = Template(report_prompts["ppt_prompt"]) \
        .render(task=task, original_query=original_query, files=truncate_flat_files,
                date=datetime.now().strftime("%Y-%m-%d"))
    messages = _build_report_messages(report_prompts, prompt, original_query, task)

    async for chunk in ask_llm(messages=messages, model=model, stream=True,
                               temperature=temperature, top_p=top_p, only_content=True):
        yield chunk


@timer(key="enter")
async def markdown_report(
        task,
        file_names: Optional[List[str]] = tuple(),
        model: Optional[str] = None,
        temperature: float = 0,
        top_p: float = 0.9,
        original_query: Optional[str] = None,
) -> AsyncGenerator:
    model = _resolve_report_model(model)
    files = await download_all_files(file_names)
    flat_files = []
    for f in files:
        # 对于搜索文件有结构，需要重新解析
        if f["file_name"].endswith("_search_result.txt"):
            flat_files.extend(flatten_search_file(f))
        else:
            flat_files.append(f)

    truncate_flat_files = truncate_files(flat_files, max_tokens=int(LLMModelInfoFactory.get_context_length(model) * 0.8))
    report_prompts = get_prompt("report")
    prompt = Template(report_prompts["markdown_prompt"]) \
        .render(task=task, original_query=original_query, files=truncate_flat_files,
                current_time=datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    messages = _build_report_messages(report_prompts, prompt, original_query, task)

    async for chunk in ask_llm(messages=messages, model=model, stream=True,
                               temperature=temperature, top_p=top_p, only_content=True):
        yield chunk


@timer(key="enter")
async def html_report(
        task,
        file_names: Optional[List[str]] = tuple(),
        model: Optional[str] = None,
        temperature: float = 0,
        top_p: float = 0.9,
        template_type: str = "html",
        original_query: Optional[str] = None,
) -> AsyncGenerator:
    model = _resolve_report_model(model)
    files = await download_all_files(file_names)
    key_files = []
    flat_files = []
    # 对于搜索文件有结构，需要重新解析
    for f in files:
        fpath = f["file_name"]
        fname = os.path.basename(fpath)
        if fname.split(".")[-1] in ["md", "txt", "csv"]:
            # CI 输出结果
            if "代码输出" in fname:
                key_files.append({"content": f["content"], "description": fname, "type": "txt", "link": fpath})
            # 搜索文件
            elif fname.endswith("_search_result.txt"):
                try:
                    flat_files.extend([{
                            "content": tf["content"],
                            "description": tf.get("title") or tf["content"][:20],
                            "type": "txt",
                            "link": tf.get("link"),
                        } for tf in flatten_search_file(f)
                    ])
                except Exception as e:
                    logger.warning(f"html_report parser file [{fpath}] error: {e}")
            # 其他文件
            else:
                flat_files.append({
                    "content": f["content"],
                    "description": fname,
                    "type": "txt",
                    "link": fpath
                })
    discount = int(LLMModelInfoFactory.get_context_length(model) * 0.8)
    key_files = truncate_files(key_files, max_tokens=discount)
    flat_files = truncate_files(flat_files, max_tokens=discount - sum([len(f["content"]) for f in key_files]))

    report_prompts = get_prompt("report")
    prompt = Template(report_prompts["html_task"]) \
        .render(task=task, original_query=original_query, key_files=key_files, files=flat_files,
                date=datetime.now().strftime('%Y年%m月%d日'))

    if template_type == "fix":
        messages = _build_report_messages(
            report_prompts, prompt, original_query, task, report_prompts["fix_html_prompt"])
        async for chunk in ask_llm(
                messages=messages,
                model=model, stream=True, temperature=temperature, top_p=top_p, only_content=True):
            yield chunk
    else:
        messages = _build_report_messages(
            report_prompts, prompt, original_query, task, report_prompts["html_prompt"])
        async for chunk in ask_llm(
                messages=messages,
                model=model, stream=True, temperature=temperature, top_p=top_p, only_content=True):
            yield chunk


if __name__ == "__main__":
    pass
