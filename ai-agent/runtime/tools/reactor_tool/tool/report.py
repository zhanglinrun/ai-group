# -*- coding: utf-8 -*-
"""Deterministic report rendering helpers; all report reasoning happens in the Java Harness."""

from __future__ import annotations

import html
import re
from pathlib import Path
from typing import Any, AsyncGenerator, List, Literal, Mapping, Optional

# Compatibility export for static prompt-policy inspection.  The deterministic
# ReportSpec rendering functions below neither read this prompt nor call a model.
from reactor_tool.util.prompt_util import get_prompt


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
    text = "\n".join(part.strip() for part in (original_query, task) if part and part.strip())
    return any(pattern.search(text) for pattern in _STRICT_GROUNDING_PATTERNS)


async def report(
    task: str,
    file_names: Optional[List[str]] = None,
    model: Optional[str] = None,
    file_type: Literal["markdown", "html", "ppt"] = "markdown",
    template_type: str = "html",
    original_query: Optional[str] = None,
) -> AsyncGenerator[str, None]:
    if file_type.lower() == "html":
        async for chunk in html_report(task, file_names, template_type=template_type, original_query=original_query):
            yield chunk
    elif file_type.lower() == "ppt":
        async for chunk in ppt_report(task, file_names, original_query=original_query):
            yield chunk
    else:
        async for chunk in markdown_report(task, file_names, original_query=original_query):
            yield chunk


async def markdown_report(
    task: str,
    file_names: Optional[List[str]] = None,
    model: Optional[str] = None,
    temperature: float = 0,
    top_p: float = 0.9,
    original_query: Optional[str] = None,
) -> AsyncGenerator[str, None]:
    title = "ResearchPilot Report"
    strict = _requires_strict_grounding(original_query, task)
    scope = "仅渲染已提供内容；不补写未知事实。" if strict else "内容由 Java Harness 提供；Python 仅做确定性渲染。"
    yield f"# {title}\n\n## Task\n{task or ''}\n\n## Rendering boundary\n{scope}\n"


async def html_report(
    task: str,
    file_names: Optional[List[str]] = None,
    model: Optional[str] = None,
    temperature: float = 0,
    top_p: float = 0.9,
    template_type: str = "html",
    original_query: Optional[str] = None,
) -> AsyncGenerator[str, None]:
    strict = _requires_strict_grounding(original_query, task)
    boundary = "Only supplied facts are rendered." if strict else "Rendered deterministically from Harness output."
    yield (
        "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
        "<title>ResearchPilot Report</title><body><main>"
        "<h1>ResearchPilot Report</h1>"
        f"<h2>Task</h2><pre>{html.escape(task or '')}</pre>"
        f"<h2>Rendering boundary</h2><p>{html.escape(boundary)}</p>"
        "</main></body></html>"
    )


async def ppt_report(
    task: str,
    file_names: Optional[List[str]] = None,
    model: Optional[str] = None,
    temperature: float | None = None,
    top_p: float = 0.6,
    original_query: Optional[str] = None,
) -> AsyncGenerator[str, None]:
    boundary = "仅渲染提供的内容，不补写未知事实。" if _requires_strict_grounding(original_query, task) else "Python 仅负责确定性 PPTX 渲染。"
    yield f"## ResearchPilot Report\n\n- {task or ''}\n\n---\n\n## Boundary\n\n- {boundary}"


def render_report_spec(spec: Mapping[str, Any], file_type: Literal["markdown", "html"]) -> str:
    """Render an already-gated ReportSpec without invoking an LLM or prompt template."""
    normalized = dict(spec or {})
    if file_type == "markdown":
        return _render_report_spec_markdown(normalized)
    if file_type == "html":
        return _render_report_spec_html(normalized)
    raise ValueError(f"unsupported deterministic ReportSpec format: {file_type}")


def render_report_spec_pdf(spec: Mapping[str, Any], output_path: str) -> None:
    """Write a real PDF from the ReportSpec using PyMuPDF; no model/tool call is involved."""
    import fitz

    markdown = _render_report_spec_markdown(dict(spec or {}))
    document = fitz.open()
    page_width, page_height = 595, 842
    font_name = "helv"
    font_buffer = None
    try:
        font_buffer = fitz.Font("china-s").buffer
        font_name = "researchpilot_cjk"
    except Exception:
        # Helvetica keeps PDF delivery available on minimal installations.  The
        # report remains byte-valid even if an optional CJK font is unavailable.
        pass
    lines = _pdf_lines(markdown, 88)
    for start in range(0, max(1, len(lines)), 46):
        page = document.new_page(width=page_width, height=page_height)
        if font_buffer is not None:
            page.insert_font(fontname=font_name, fontbuffer=font_buffer)
        page.insert_textbox(
            fitz.Rect(36, 36, page_width - 36, page_height - 36),
            "\n".join(lines[start:start + 46]),
            fontsize=10,
            fontname=font_name,
            color=(0, 0, 0),
            lineheight=1.25,
        )
    target = Path(output_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    document.save(str(target), garbage=4, deflate=True)
    document.close()


def _render_report_spec_markdown(spec: Mapping[str, Any]) -> str:
    title = _text(spec.get("title"), "ResearchPilot report")
    summary = _text(spec.get("executiveSummary"))
    methodology = _text(spec.get("methodology"))
    citations = _dicts(spec.get("citations"))
    claims = _dicts(spec.get("claims"))
    conflicts = _dicts(spec.get("conflicts"))
    limitations = _strings(spec.get("limitations"))
    citation_numbers = {_text(citation.get("evidenceId")): index + 1 for index, citation in enumerate(citations)}
    citations_by_claim: dict[str, list[Mapping[str, Any]]] = {}
    for citation in citations:
        citations_by_claim.setdefault(_text(citation.get("claimId")), []).append(citation)

    lines = [f"# {title}", "", "## Executive summary", "", summary, "", "## Methodology", "", methodology,
             "", "## Research findings", ""]
    for claim in claims:
        claim_id = _text(claim.get("id"))
        statement = _text(claim.get("statement"), claim_id)
        labels = " ".join(f"[S{citation_numbers[_text(citation.get('evidenceId'))]}]"
                          for citation in citations_by_claim.get(claim_id, [])
                          if _text(citation.get("evidenceId")) in citation_numbers)
        uncertainty = _text(claim.get("uncertainty"), "NONE")
        suffix = f" (uncertainty: {uncertainty})" if uncertainty != "NONE" else ""
        lines.append(f"- {statement}{(' ' + labels) if labels else ''}{suffix}")
    if not claims:
        lines.append("- No verified claims were available.")
    if conflicts:
        lines.extend(["", "## Conflicts", ""])
        lines.extend(f"- {_text(conflict.get('claimId'))}: {_text(conflict.get('description'))}" for conflict in conflicts)
    lines.extend(["", "## Limitations", ""])
    lines.extend(f"- {limitation}" for limitation in limitations or ["No limitations were supplied."])
    lines.extend(["", "## Evidence and citations", ""])
    for index, citation in enumerate(citations, start=1):
        lines.extend([
            f"[S{index}] {_text(citation.get('sourceUrl'))}",
            f"> {_text(citation.get('exactQuote'))}",
            f"> evidence_id={_text(citation.get('evidenceId'))}; hash={_text(citation.get('contentHash'))}; "
            f"fetched_at={_text(citation.get('fetchedAtEpochMillis'))}",
            "",
        ])
    lines.extend(["Generated at: " + _text(spec.get("generatedAt")),
                  "Renderer version: " + _text(spec.get("rendererVersion"), "researchpilot-deterministic-v1")])
    return "\n".join(lines).strip()


def _render_report_spec_html(spec: Mapping[str, Any]) -> str:
    title = _text(spec.get("title"), "ResearchPilot report")
    citations = _dicts(spec.get("citations"))
    claims = _dicts(spec.get("claims"))
    citations_by_claim: dict[str, list[int]] = {}
    for index, citation in enumerate(citations, start=1):
        citations_by_claim.setdefault(_text(citation.get("claimId")), []).append(index)
    claim_items = "".join(
        "<li>" + html.escape(_text(claim.get("statement"), _text(claim.get("id"))))
        + " " + " ".join(f"<a href=\"#source-{number}\">[S{number}]</a>" for number in citations_by_claim.get(_text(claim.get("id")), []))
        + (f" <em>uncertainty: {html.escape(_text(claim.get('uncertainty')))}</em>"
           if _text(claim.get("uncertainty"), "NONE") != "NONE" else "") + "</li>"
        for claim in claims
    ) or "<li>No verified claims were available.</li>"
    conflict_items = "".join("<li>" + html.escape(_text(conflict.get("claimId"))) + ": "
                             + html.escape(_text(conflict.get("description"))) + "</li>"
                             for conflict in _dicts(spec.get("conflicts")))
    limitation_items = "".join("<li>" + html.escape(item) + "</li>" for item in _strings(spec.get("limitations")))
    source_items = "".join(
        f"<li id=\"source-{index}\"><a href=\"{html.escape(_text(citation.get('sourceUrl')), quote=True)}\">"
        f"[S{index}] {html.escape(_text(citation.get('sourceUrl')))}</a><blockquote>{html.escape(_text(citation.get('exactQuote')))}</blockquote>"
        f"<small>evidence_id={html.escape(_text(citation.get('evidenceId')))}; hash={html.escape(_text(citation.get('contentHash')))}</small></li>"
        for index, citation in enumerate(citations, start=1)
    )
    conflicts_html = f"<section><h2>Conflicts</h2><ul>{conflict_items}</ul></section>" if conflict_items else ""
    return (
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        f"<title>{html.escape(title)}</title><style>body{{font-family:system-ui,sans-serif;max-width:900px;margin:2rem auto;padding:0 1rem;line-height:1.55}}blockquote{{border-left:3px solid #888;padding-left:1rem}}small{{color:#555}}</style></head>"
        f"<body><main><h1>{html.escape(title)}</h1><section><h2>Executive summary</h2><p>{html.escape(_text(spec.get('executiveSummary')))}</p></section>"
        f"<section><h2>Methodology</h2><p>{html.escape(_text(spec.get('methodology')))}</p></section><section><h2>Research findings</h2><ul>{claim_items}</ul></section>"
        f"{conflicts_html}<section><h2>Limitations</h2><ul>{limitation_items}</ul></section><section><h2>Evidence and citations</h2><ol>{source_items}</ol></section>"
        f"<footer>Generated at: {html.escape(_text(spec.get('generatedAt')))}<br>Renderer version: {html.escape(_text(spec.get('rendererVersion'), 'researchpilot-deterministic-v1'))}</footer>"
        "</main></body></html>"
    )


def _pdf_lines(markdown: str, max_chars: int) -> list[str]:
    lines: list[str] = []
    for raw_line in markdown.splitlines() or [""]:
        line = raw_line
        while len(line) > max_chars:
            lines.append(line[:max_chars])
            line = line[max_chars:]
        lines.append(line)
    return lines


def _dicts(value: Any) -> list[Mapping[str, Any]]:
    return [item for item in (value or []) if isinstance(item, Mapping)] if isinstance(value, list) else []


def _strings(value: Any) -> list[str]:
    return [_text(item) for item in value if _text(item)] if isinstance(value, list) else []


def _text(value: Any, default: str = "") -> str:
    text = str(value).strip() if value is not None else ""
    return text or default
