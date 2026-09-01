from __future__ import annotations

import re
from typing import Any


_BASE_TRACE_AGENTS = {
    "intake_agent",
    "planner_agent",
    "supervisor",
    "analyst",
    "writer",
    "qa",
}


def _sections(outputs: dict[str, Any]) -> list[dict[str, Any]]:
    report = outputs.get("report")
    content_json = report.get("content_json") if isinstance(report, dict) else None
    sections = content_json.get("sections") if isinstance(content_json, dict) else None
    return [item for item in sections if isinstance(item, dict)] if isinstance(sections, list) else []


def _report_text(outputs: dict[str, Any]) -> str:
    report = outputs.get("report")
    if not isinstance(report, dict):
        return ""
    markdown = report.get("content_markdown")
    if isinstance(markdown, str):
        return markdown
    content_json = report.get("content_json")
    return str(content_json) if isinstance(content_json, dict) else ""


def _unqualified_forbidden_terms(text: str, terms: list[str]) -> list[str]:
    """Return forbidden terms used as commercial claims, not boundary disclaimers.

    Academic reports may explicitly state that pricing, vendors, or user feedback
    were not covered. Those negative scope statements are not commercial pollution;
    positive claims such as "价格为..." or "用户反馈显示..." remain violations.
    """
    sentence_parts = re.split(r"(?<=[。！？.!?])|\n+", text.casefold())
    negation_markers = (
        "未提供", "没有", "无", "不做", "不输出", "不足以支持", "缺少", "不包含",
        "未覆盖", "不纳入", "不将", "not provided", "no ", "without ", "not covered",
        "insufficient", "excluded", "does not support",
    )
    hits: list[str] = []
    for term in terms:
        normalized_term = term.casefold()
        for sentence in sentence_parts:
            if normalized_term not in sentence:
                continue
            if any(marker in sentence for marker in negation_markers):
                continue
            hits.append(term)
            break
    return hits


def terminal_success_evaluator(
    inputs: dict[str, Any],
    outputs: dict[str, Any],
    reference_outputs: dict[str, Any] | None = None,
) -> dict[str, Any]:
    del inputs, reference_outputs
    status = str(outputs.get("status") or "")
    passed = status in {"completed", "degraded"}
    return {"key": "terminal_success", "score": 1.0 if passed else 0.0, "value": {"status": status}}


def research_mode_evaluator(
    inputs: dict[str, Any],
    outputs: dict[str, Any],
    reference_outputs: dict[str, Any] | None = None,
) -> dict[str, Any]:
    del reference_outputs
    expected = str(inputs.get("research_mode") or "general").strip().lower()
    detail = outputs.get("detail")
    draft = detail.get("intake_draft") if isinstance(detail, dict) else None
    query = str(inputs.get("user_query") or "")
    combined = " ".join([query, _report_text(outputs)]).casefold()
    actual = str(outputs.get("research_mode") or "").strip().lower()
    if isinstance(draft, dict):
        explicit_mode = draft.get("research_mode")
        if isinstance(explicit_mode, str) and explicit_mode.strip():
            actual = explicit_mode.strip().lower()
    forbidden = [str(item).casefold() for item in inputs.get("forbidden_terms", []) if str(item).strip()]
    contamination = _unqualified_forbidden_terms(combined, forbidden)
    passed = actual == expected and not contamination
    return {
        "key": "research_mode",
        "score": 1.0 if passed else 0.0,
        "value": {"expected": expected, "actual": actual, "forbidden_hits": contamination},
    }


def trace_completeness_evaluator(
    inputs: dict[str, Any],
    outputs: dict[str, Any],
    reference_outputs: dict[str, Any] | None = None,
) -> dict[str, Any]:
    del reference_outputs
    trace = outputs.get("trace")
    if not isinstance(trace, dict):
        return {"key": "trace_completeness", "score": 0.0, "value": {"reason": "trace_missing"}}
    steps = trace.get("steps")
    timeline = trace.get("timeline")
    agent_names = {
        str(item.get("agent_name"))
        for item in steps
        if isinstance(item, dict) and item.get("agent_name")
    } if isinstance(steps, list) else set()
    required_agents = set(_BASE_TRACE_AGENTS)
    if not inputs.get("competitors"):
        required_agents.update({"discovery", "replanner"})
    detail = outputs.get("detail")
    resulting_competitors = detail.get("competitors") if isinstance(detail, dict) else None
    if inputs.get("competitors") or (
        isinstance(resulting_competitors, list) and resulting_competitors
    ):
        required_agents.add("researcher")
    missing_agents = sorted(required_agents - agent_names)
    llm_calls = trace.get("llm_calls")
    invalid_llm_metadata = [
        str(item.get("id") or "unknown")
        for item in llm_calls
        if isinstance(item, dict)
        and (
            not isinstance(item.get("provider"), str)
            or not isinstance(item.get("model_name"), str)
        )
    ] if isinstance(llm_calls, list) else ["llm_calls_missing"]
    score = 1.0 if (
        isinstance(steps, list)
        and isinstance(timeline, list)
        and len(timeline) > 0
        and not missing_agents
        and isinstance(llm_calls, list)
        and len(llm_calls) > 0
        and not invalid_llm_metadata
    ) else 0.0
    return {
        "key": "trace_completeness",
        "score": score,
        "value": {
            "step_count": len(steps) if isinstance(steps, list) else 0,
            "timeline_count": len(timeline) if isinstance(timeline, list) else 0,
            "llm_call_count": len(llm_calls) if isinstance(llm_calls, list) else 0,
            "agent_names": sorted(agent_names),
            "missing_agents": missing_agents,
            "invalid_llm_metadata": invalid_llm_metadata,
        },
    }


def duplicate_evidence_evaluator(
    inputs: dict[str, Any],
    outputs: dict[str, Any],
    reference_outputs: dict[str, Any] | None = None,
) -> dict[str, Any]:
    del inputs, reference_outputs
    evidence_ids = [item for item in outputs.get("evidence_ids", []) if isinstance(item, str)]
    unique = len(set(evidence_ids))
    score = 1.0 if len(evidence_ids) == unique else 0.0
    return {"key": "duplicate_evidence", "score": score, "value": {"count": len(evidence_ids), "unique_count": unique}}


def duplicate_task_evaluator(
    inputs: dict[str, Any],
    outputs: dict[str, Any],
    reference_outputs: dict[str, Any] | None = None,
) -> dict[str, Any]:
    del inputs, reference_outputs
    detail = outputs.get("detail")
    plan_tree = detail.get("plan_tree") if isinstance(detail, dict) else None
    tasks = plan_tree.get("tasks") if isinstance(plan_tree, dict) else []
    task_ids = [
        str(item.get("task_id"))
        for item in tasks
        if isinstance(item, dict) and item.get("task_id")
    ] if isinstance(tasks, list) else []
    duplicate_ids = sorted({task_id for task_id in task_ids if task_ids.count(task_id) > 1})
    return {
        "key": "duplicate_task",
        "score": 0.0 if duplicate_ids else 1.0,
        "value": {"task_count": len(task_ids), "duplicate_task_ids": duplicate_ids},
    }


def error_explainability_evaluator(
    inputs: dict[str, Any],
    outputs: dict[str, Any],
    reference_outputs: dict[str, Any] | None = None,
) -> dict[str, Any]:
    del inputs, reference_outputs
    detail = outputs.get("detail")
    status = str(outputs.get("status") or "")
    reason = str(detail.get("status_reason") or "") if isinstance(detail, dict) else ""
    invalid_credential = "invalid internal service credential" in reason.casefold()
    explained = status == "completed" or bool(reason.strip())
    passed = explained and not invalid_credential
    return {
        "key": "error_explainability",
        "score": 1.0 if passed else 0.0,
        "value": {
            "status": status,
            "has_reason": bool(reason.strip()),
            "invalid_internal_service_credential": invalid_credential,
            "reason": reason[:500],
        },
    }


def evidence_coverage_evaluator(
    inputs: dict[str, Any],
    outputs: dict[str, Any],
    reference_outputs: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Score whether the run produced enough evidence for its requested scope."""
    del inputs, reference_outputs
    metrics = outputs.get("metrics")
    coverage = metrics.get("evidence_dimension_coverage_rate", 0.0) if isinstance(metrics, dict) else 0.0
    evidence_count = metrics.get("evidence_count_total", 0) if isinstance(metrics, dict) else 0
    score = float(coverage) if isinstance(coverage, (int, float)) else 0.0
    return {
        "key": "evidence_coverage",
        "score": max(0.0, min(1.0, score)),
        "value": {"evidence_dimension_coverage_rate": score, "evidence_count_total": evidence_count},
    }


def citation_grounding_evaluator(
    inputs: dict[str, Any],
    outputs: dict[str, Any],
    reference_outputs: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Score section-level evidence references against collected evidence IDs."""
    del inputs, reference_outputs
    evidence_ids = set(outputs.get("evidence_ids", []))
    sections = _sections(outputs)
    if not sections:
        return {"key": "citation_grounding", "score": 0.0, "value": {"section_count": 0}}
    grounded = 0
    for section in sections:
        refs = section.get("evidence_refs")
        if isinstance(refs, list) and refs and all(isinstance(ref, str) and ref in evidence_ids for ref in refs):
            grounded += 1
    score = grounded / len(sections)
    return {
        "key": "citation_grounding",
        "score": score,
        "value": {"grounded_sections": grounded, "section_count": len(sections)},
    }


def report_shape_evaluator(
    inputs: dict[str, Any],
    outputs: dict[str, Any],
    reference_outputs: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Score basic report completeness and expected section coverage."""
    del reference_outputs
    sections = _sections(outputs)
    expected = inputs.get("expected_sections", [])
    section_ids = {item.get("section_id") for item in sections if isinstance(item.get("section_id"), str)}
    expected_ids = {item for item in expected if isinstance(item, str)}
    shape_score = 1.0 if sections and all(str(item.get("content_markdown", "")).strip() for item in sections) else 0.0
    if expected_ids:
        shape_score *= len(section_ids & expected_ids) / len(expected_ids)
    return {
        "key": "report_shape",
        "score": shape_score,
        "value": {"section_ids": sorted(section_ids), "expected_sections": sorted(expected_ids)},
    }


def make_llm_judge(model_name: str):
    """Build an optional LangChain judge for qualitative report quality."""
    from langchain_openai import ChatOpenAI

    judge = ChatOpenAI(model=model_name, temperature=0)

    def _judge(
        inputs: dict[str, Any],
        outputs: dict[str, Any],
        reference_outputs: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        del reference_outputs
        report = outputs.get("report", {})
        content = report.get("content_markdown", "") if isinstance(report, dict) else ""
        mode = str(inputs.get("research_mode") or "general").strip().lower()
        rubric = {
            "academic": "Score literature relevance, method/dataset/benchmark synthesis, research gaps, limitations, and citation adherence.",
            "technical": "Score technical completeness, architecture and implementation detail, engineering constraints, and evidence grounding.",
            "commercial": "Score competitor coverage, feature/pricing/user-feedback comparison, market reasoning, and actionable conclusions.",
            "general": "Score factual usefulness, scope coverage, balanced synthesis, limitations, and citation adherence.",
        }.get(mode, "Score factual usefulness, scope coverage, balanced synthesis, limitations, and citation adherence.")
        prompt = (
            "Evaluate this deep-research report. Return only JSON with keys score (0..1) and rationale. "
            f"Research mode: {mode}. {rubric}\n"
            f"User request: {inputs.get('user_query', '')}\nReport:\n{content[:20000]}"
        )
        response = judge.invoke(prompt)
        text = getattr(response, "content", "")
        if isinstance(text, list):
            text = " ".join(str(item) for item in text)
        try:
            import json

            parsed = json.loads(str(text))
        except (TypeError, ValueError):
            parsed = {"score": 0.0, "rationale": "judge returned non-JSON output"}
        score = parsed.get("score", 0.0) if isinstance(parsed, dict) else 0.0
        return {
            "key": "llm_report_quality",
            "score": max(0.0, min(1.0, float(score))) if isinstance(score, (int, float)) else 0.0,
            "value": parsed,
        }

    return _judge
