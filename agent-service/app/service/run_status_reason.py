from __future__ import annotations

from collections.abc import Mapping, Sequence

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from models.run import Run
from models.step import Step
from models.supervisor_decision import SupervisorDecisionRecord
from schemas.report_sections import SECTION_REGISTRY

MAX_REASON_LEN = 500
DEFAULT_DEGRADED_REASON = "报告已完成，但存在已知质量缺口。"
DEFAULT_FAILED_REASON = "运行过程中发生错误。"
DEFAULT_CANCELLED_REASON = "你已停止此次调研。"
UNEXPECTED_CANCEL_REASON = "后台任务被中止（可能是服务重启）。"
ORPHAN_RESTART_REASON = "服务重启时此任务仍在执行，已标记为失败。请重新发起调研。"

_FALLBACK_SECTION_LABELS_ZH: dict[str, str] = {
    "competitor_profiles": "竞品画像",
    "comparison_matrix": "对比矩阵",
    "positioning_map": "定位分析",
    "self_positioning": "我方定位",
    "executive_summary": "执行摘要",
    "market_definition": "市场定义",
    "key_players": "关键玩家",
    "methodology_limits": "方法论与证据边界",
}


def clip_reason(text: str) -> str:
    compact = " ".join(text.split())
    if len(compact) <= MAX_REASON_LEN:
        return compact
    return compact[: MAX_REASON_LEN - 1].rstrip() + "…"


def section_label_zh(section_id: str) -> str:
    spec = SECTION_REGISTRY.get(section_id)
    if spec is not None:
        return spec.title_zh
    return _FALLBACK_SECTION_LABELS_ZH.get(section_id, section_id)


def humanize_failure_message(raw: str | None) -> str:
    text = (raw or "").strip()
    if not text:
        return DEFAULT_FAILED_REASON
    lower = text.lower()
    if (
        "401" in text
        or "invalid token" in lower
        or "invalid_api_key" in lower
        or "incorrect api key" in lower
        or "authentication" in lower
        or "new_api_error" in lower
    ):
        return "写作模型未能连上，请稍后重试。"
    if "429" in text or "rate limit" in lower or "too many requests" in lower:
        return "模型服务请求过于频繁，请稍后重试。"
    if "timeout" in lower or "timed out" in lower or "deadline" in lower:
        return "模型或检索服务超时，请稍后重试。"
    if "connection" in lower and ("refused" in lower or "reset" in lower or "error" in lower):
        return "外部服务连接失败，请稍后重试。"
    # Keep a short, user-facing last line without stack frames or request ids.
    first_line = text.splitlines()[0]
    first_line = first_line.split("request id:")[0].strip(" -")
    if len(first_line) > 180:
        return DEFAULT_FAILED_REASON
    return clip_reason(first_line)


def _join_section_labels(section_ids: Sequence[str]) -> str:
    labels = [section_label_zh(item) for item in section_ids if item]
    return "、".join(labels)


def _writer_fallback_reason(writer_fallback_reason: str | None) -> str:
    fallback_human = humanize_failure_message(writer_fallback_reason)
    if "过于频繁" in fallback_human:
        return "写作模型请求过于频繁，已改用模板报告。"
    if "超时" in fallback_human:
        return "写作模型超时，已改用模板报告。"
    return "写作模型未能连上，已改用模板报告。"


def build_degraded_reason(
    *,
    forced_degraded_by_qa: bool = False,
    qa_degrade_reason: str | None = None,
    degraded_required_sections: Sequence[str] = (),
    writer_fallback: bool = False,
    writer_fallback_reason: str | None = None,
    completion_reason: str = "",
    report_draft_done: bool = True,
    researcher_degraded_competitors: Sequence[str] = (),
    competitor_count: int = 0,
) -> str:
    """Return one root-cause sentence. Downstream QA symptoms are omitted."""
    sections = [item for item in degraded_required_sections if item]
    if writer_fallback:
        return _writer_fallback_reason(writer_fallback_reason)
    if researcher_degraded_competitors:
        names = "、".join(researcher_degraded_competitors[:4])
        extra = " 等" if len(researcher_degraded_competitors) > 4 else ""
        return clip_reason(f"部分对象未收集到有效证据：{names}{extra}。")
    if "comparison_matrix" in sections and competitor_count < 2:
        return "当前只调研了 1 个对象，对比矩阵需要至少 2 个对照对象。"
    if qa_degrade_reason == "report_degraded_required_sections" or sections:
        labels = _join_section_labels(sections) or "部分必填章节"
        return clip_reason(f"必填章节证据不足：{labels}。")
    if completion_reason == "max_iterations_hit":
        return "达到最大迭代次数，已按现有结果收口。"
    if not report_draft_done:
        return "报告草稿未完整生成。"
    if forced_degraded_by_qa or completion_reason == "fallback_path":
        if forced_degraded_by_qa:
            return "质检未通过且无法继续返工，已降级收口。"
        return DEFAULT_DEGRADED_REASON
    return DEFAULT_DEGRADED_REASON


def _string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str) and item]


def _mapping(value: object) -> Mapping[str, object]:
    if isinstance(value, dict):
        return value
    return {}


def derive_degraded_reason_from_records(
    *,
    competitor_ids: Sequence[str],
    qa_payload: Mapping[str, object] | None,
    writer_payload: Mapping[str, object] | None,
    finalize_tool_args: Mapping[str, object] | None,
) -> str:
    qa = qa_payload or {}
    writer = writer_payload or {}
    tool_args = finalize_tool_args or {}
    sections = _string_list(qa.get("qa_degraded_required_sections")) or _string_list(
        writer.get("report_degraded_required_sections")
    )
    writer_mode = writer.get("writer_mode")
    writer_fallback = writer_mode == "fallback" or bool(writer.get("llm_fallback_used"))
    fallback_reason_raw = writer.get("fallback_reason") or writer.get("llm_fallback_reason")
    fallback_reason = fallback_reason_raw if isinstance(fallback_reason_raw, str) else None
    qa_degrade_reason_raw = qa.get("qa_degrade_reason")
    qa_degrade_reason = qa_degrade_reason_raw if isinstance(qa_degrade_reason_raw, str) else None
    qa_outcome = qa.get("qa_outcome")
    completion_reason_raw = tool_args.get("completion_reason")
    completion_reason = completion_reason_raw if isinstance(completion_reason_raw, str) else ""
    return build_degraded_reason(
        forced_degraded_by_qa=qa_outcome == "force_degraded",
        qa_degrade_reason=qa_degrade_reason,
        degraded_required_sections=sections,
        writer_fallback=writer_fallback,
        writer_fallback_reason=fallback_reason,
        completion_reason=completion_reason,
        report_draft_done=True,
        researcher_degraded_competitors=[],
        competitor_count=len([item for item in competitor_ids if item]),
    )


async def resolve_run_status_reason(session: AsyncSession, run: Run) -> str | None:
    stored = run.status_reason.strip() if isinstance(run.status_reason, str) else ""
    if stored:
        return clip_reason(stored)
    if run.status == "cancelled":
        return DEFAULT_CANCELLED_REASON
    if run.status == "failed":
        if run.billing_error:
            return humanize_failure_message(run.billing_error)
        return DEFAULT_FAILED_REASON
    if run.status != "degraded":
        return None

    qa_row = (
        await session.execute(
            select(Step)
            .where(Step.run_id == run.run_id, Step.agent_name == "qa")
            .order_by(Step.started_at.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    writer_row = (
        await session.execute(
            select(Step)
            .where(Step.run_id == run.run_id, Step.agent_name == "writer")
            .order_by(Step.started_at.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    decision_row = (
        await session.execute(
            select(SupervisorDecisionRecord)
            .where(
                SupervisorDecisionRecord.run_id == run.run_id,
                SupervisorDecisionRecord.chosen_tool == "Finalize",
            )
            .order_by(SupervisorDecisionRecord.iteration.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    return derive_degraded_reason_from_records(
        competitor_ids=list(run.competitors or []),
        qa_payload=_mapping(None if qa_row is None else qa_row.payload),
        writer_payload=_mapping(None if writer_row is None else writer_row.payload),
        finalize_tool_args=_mapping(None if decision_row is None else decision_row.tool_args),
    )
