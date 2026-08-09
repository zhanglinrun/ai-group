from __future__ import annotations

from collections import Counter
from collections.abc import Awaitable, Callable
import re
from datetime import datetime, timezone
from typing import Literal
from uuid import uuid4

from sqlalchemy import delete, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from agents.state import AgentState
from agents.state_coercion import coerce_intake_draft_or_default
from core.config import settings
from db.engine import get_session_factory
from models.artifact import Artifact
from models.evidence import EvidenceRecord
from models.report import Report
from models.step import Step
from schemas.agent_outputs import (
    MIN_WRITER_SECTION_CHARS,
    AnalystOutput,
    WriterExecutionContext,
    WriterReportOutput,
    WriterSectionOutput,
)
from schemas.ids import make_id
from schemas.report_sections import (
    CORE_DISCOVERY_ROLES,
    SectionEvidenceContext,
    get_section_spec,
    section_title,
    triage_outline_sections,
)
from schemas.supervisor import Write
from schemas.contracts import validate_section_id
from service.comparison import load_comparisons_for_run
from service.event_bus import RunEventType, emit_run_event
from service.conclusion import load_conclusions_for_run
from service.llm import (
    EVIDENCE_QUOTE_CHAR_BUDGET,
    WRITER_SECTION_SYSTEM_PROMPT,
    WRITER_SYSTEM_PROMPT,
    build_writer_fallback_user_prompt,
    build_writer_repair_user_prompt,
    build_writer_section_deepen_user_prompt,
    build_writer_user_prompt,
    select_layered_evidence_briefs,
)
from service.knowledge import EMPTY_RUN_KNOWLEDGE, load_knowledge_for_run
from service.llm.harness import complete_structured
from service.llm.exceptions import LLMError
from service.llm.records import build_llm_call_record
from service.llm.response import LLMResponse
from utils.log_node import log_node
from utils.logger import get_logger

log = get_logger("agents.writer")

BARE_EVIDENCE_ID_PATTERN = re.compile(r"(?<!\[)\b(ev_[A-Za-z0-9_]+)\b(?!\])")
BRACKETED_EVIDENCE_ID_PATTERN = re.compile(r"\[(ev_[A-Za-z0-9_]+)\]")
INSIGHT_ID_PATTERN = re.compile(r"\binsight_[A-Za-z0-9_]+\b")
NUMERIC_RANGE_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_])\d+(?:\.\d+)?\s*(?:-|–|—|~|～|至|到)\s*\d+(?:\.\d+)?"
    r"(?:\s*(?:%|％|元|美元|人民币|cny|rmb|usd))?(?![A-Za-z0-9_])",
    flags=re.IGNORECASE,
)
NUMERIC_LITERAL_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_])\d+(?:\.\d+)?"
    r"(?:\s*(?:%|％|元|美元|人民币|cny|rmb|usd))?(?![A-Za-z0-9_])",
    flags=re.IGNORECASE,
)
def _copy_empty_knowledge_payload() -> dict[str, object]:
    return {
        "schema_version": EMPTY_RUN_KNOWLEDGE.get("schema_version", "schema_v0.2"),
        "features": list(EMPTY_RUN_KNOWLEDGE.get("features", [])),
        "pricings": list(EMPTY_RUN_KNOWLEDGE.get("pricings", [])),
        "personas": list(EMPTY_RUN_KNOWLEDGE.get("personas", [])),
        "feedback": list(EMPTY_RUN_KNOWLEDGE.get("feedback", [])),
        "missing_reasons": dict(EMPTY_RUN_KNOWLEDGE.get("missing_reasons", {})),
        "coverage": dict(EMPTY_RUN_KNOWLEDGE.get("coverage", {})),
        "supporting_target_evidence_ids": dict(
            EMPTY_RUN_KNOWLEDGE.get("supporting_target_evidence_ids", {})
        ),
    }


def _section_title(section_id: str, *, response_language: str | None) -> str:
    return section_title(section_id, response_language=response_language)


def _markdown_cell(value: object) -> str:
    if value is None:
        return "-"
    text = str(value).replace("\n", " ").replace("|", "/").strip()
    return text or "-"


def _build_markdown_table(*, headers: list[str], rows: list[list[object]]) -> str:
    header_line = "|" + "|".join(_markdown_cell(item) for item in headers) + "|"
    separator_line = "|" + "|".join("---" for _ in headers) + "|"
    row_lines = [
        "|" + "|".join(_markdown_cell(item) for item in row) + "|"
        for row in rows
    ]
    return "\n".join([header_line, separator_line, *row_lines])


def _is_valid_section_id(value: str) -> bool:
    try:
        validate_section_id(value)
    except ValueError:
        return False
    return True


def _insight_matches_section(*, insight: dict[str, object], section_id: str) -> bool:
    dimension_raw = insight.get("dimension")
    if not isinstance(dimension_raw, str):
        return False
    return dimension_raw.strip().lower() == section_id


def _select_insights_for_section(
    *,
    section_id: str,
    insight_briefs: list[dict[str, object]],
) -> list[dict[str, object]]:
    matched = [
        insight
        for insight in insight_briefs
        if _insight_matches_section(insight=insight, section_id=section_id)
    ]
    return matched[:3]


def _select_evidence_ids_for_section(
    *,
    section_id: str,
    evidence_briefs: list[dict[str, object]],
    evidence_ids: list[str],
) -> list[str]:
    matched = [
        item["evidence_id"]
        for item in evidence_briefs
        if item.get("evidence_id") in evidence_ids
        and isinstance(item.get("dimension"), str)
        and str(item.get("dimension")).lower() == section_id
    ]
    return _stable_unique(matched)[:3]


def _stable_unique(items: list[str]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for item in items:
        if item in seen:
            continue
        seen.add(item)
        ordered.append(item)
    return ordered


def _normalize_heading(value: str) -> str:
    collapsed = re.sub(r"[\s:：\-\u2014_]+", "", value.strip().lower())
    return collapsed


def _is_duplicate_executive_summary_section(
    *,
    section_title: str,
    executive_summary_label: str,
) -> bool:
    normalized_section = _normalize_heading(section_title)
    normalized_label = _normalize_heading(executive_summary_label)
    if not normalized_section or not normalized_label:
        return False
    return normalized_section.startswith(normalized_label)


def _format_distribution(
    distribution: Counter[str],
    *,
    empty_label: str,
) -> str:
    if not distribution:
        return empty_label
    ordered = sorted(distribution.items(), key=lambda item: (-item[1], item[0]))
    return ", ".join(f"{name}: {count}" for name, count in ordered)


def _build_methodology_section_lines(
    *,
    evidence_briefs: list[dict[str, object]],
    labels: dict[str, str],
) -> list[str]:
    authority_counts: Counter[str] = Counter()
    source_type_counts: Counter[str] = Counter()
    competitor_stats: dict[str, dict[str, bool]] = {}

    for brief in evidence_briefs:
        authority_raw = brief.get("source_authority")
        source_type_raw = brief.get("source_type")
        competitor_raw = brief.get("competitor_id")

        authority = (
            authority_raw.strip().lower()
            if isinstance(authority_raw, str) and authority_raw.strip()
            else "unknown"
        )
        source_type = (
            source_type_raw.strip().lower()
            if isinstance(source_type_raw, str) and source_type_raw.strip()
            else "unknown"
        )

        authority_counts[authority] += 1
        source_type_counts[source_type] += 1

        if not isinstance(competitor_raw, str):
            continue
        competitor_id = competitor_raw.strip()
        if not competitor_id or competitor_id == "unknown":
            continue
        stats = competitor_stats.setdefault(
            competitor_id,
            {
                "has_official": False,
                "has_pricing_page": False,
            },
        )
        if authority == "official":
            stats["has_official"] = True
        if source_type == "pricing_page":
            stats["has_pricing_page"] = True

    competitor_names = sorted(competitor_stats.keys())
    competitor_list = (
        ", ".join(competitor_names)
        if competitor_names
        else labels["methodology_no_competitors"]
    )
    data_gaps: list[str] = []
    for competitor_id in competitor_names:
        stats = competitor_stats[competitor_id]
        has_official = stats["has_official"]
        has_pricing_page = stats["has_pricing_page"]
        if has_official and has_pricing_page:
            continue
        if not has_official and not has_pricing_page:
            data_gaps.append(
                f"{competitor_id}: {labels['methodology_gap_official_and_pricing']}"
            )
            continue
        if not has_official:
            data_gaps.append(f"{competitor_id}: {labels['methodology_gap_official_only']}")
            continue
        data_gaps.append(f"{competitor_id}: {labels['methodology_gap_pricing_only']}")

    gap_summary = (
        "; ".join(data_gaps)
        if data_gaps
        else labels["methodology_no_data_gaps"]
    )
    generated_on = datetime.now(timezone.utc).date().isoformat()
    return [
        f"- {labels['methodology_generated_on']}: {generated_on}",
        (
            f"- {labels['methodology_competitors']}: "
            f"{len(competitor_names)} ({competitor_list})"
        ),
        f"- {labels['methodology_evidence_total']}: {len(evidence_briefs)}",
        (
            f"- {labels['methodology_authority_distribution']}: "
            f"{_format_distribution(authority_counts, empty_label=labels['methodology_none'])}"
        ),
        (
            f"- {labels['methodology_source_type_distribution']}: "
            f"{_format_distribution(source_type_counts, empty_label=labels['methodology_none'])}"
        ),
        f"- {labels['methodology_data_gaps']}: {gap_summary}",
    ]


def _sanitize_report_markdown_text(
    value: str,
    *,
    allowed_evidence_ids: set[str],
) -> str:
    def replace_bracketed_evidence(match: re.Match[str]) -> str:
        evidence_id = match.group(1)
        if evidence_id in allowed_evidence_ids:
            return f"[{evidence_id}]"
        return ""

    def replace_evidence_id(match: re.Match[str]) -> str:
        evidence_id = match.group(1)
        if evidence_id in allowed_evidence_ids:
            return f"[{evidence_id}]"
        return ""

    sanitized = BRACKETED_EVIDENCE_ID_PATTERN.sub(replace_bracketed_evidence, value)
    sanitized = BARE_EVIDENCE_ID_PATTERN.sub(replace_evidence_id, sanitized)
    sanitized = INSIGHT_ID_PATTERN.sub("", sanitized)
    sanitized = re.sub(r"[ \t]{2,}", " ", sanitized)
    sanitized = re.sub(r" ?([,.;:，。；：])", r"\1", sanitized)
    sanitized = re.sub(r"\n{3,}", "\n\n", sanitized)
    return sanitized.strip()


def _numeric_placeholder(*, response_language: str | None) -> str:
    return "若干" if response_language == "zh" else "several"


def _normalize_writer_section_id(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    raw = value.strip()
    if not raw:
        return None
    if not _is_valid_section_id(raw):
        return None
    return raw


def _apply_numeric_claim_guardrail(
    *,
    report_content: dict[str, object],
    unsupported_numeric_claims: list[dict[str, object]],
    response_language: str | None,
) -> tuple[dict[str, object], list[str]]:
    if not unsupported_numeric_claims:
        return report_content, []
    sections_raw = report_content.get("sections")
    if not isinstance(sections_raw, list):
        return report_content, []
    section_ids: set[str] = set()
    explicit_claims_by_section: dict[str, list[str]] = {}
    for item in unsupported_numeric_claims:
        if not isinstance(item, dict):
            continue
        section_id = _normalize_writer_section_id(item.get("section_id"))
        if section_id is None:
            continue
        section_ids.add(section_id)
        claim_raw = item.get("claim")
        if not isinstance(claim_raw, str):
            continue
        claim = " ".join(claim_raw.split())
        if not claim:
            continue
        explicit_claims_by_section.setdefault(section_id, []).append(claim)
    if not section_ids:
        return report_content, []

    placeholder = _numeric_placeholder(response_language=response_language)
    section_note = (
        "- 注：本段具体数值已按 QA 反馈降级为定性表述，待补充可核验数字证据。"
        if response_language == "zh"
        else "- Note: exact numeric claims in this section were downgraded to qualitative statements pending verifiable evidence."
    )
    updated_sections: list[dict[str, object]] = []
    downgraded_sections: list[str] = []
    for section_raw in sections_raw:
        if not isinstance(section_raw, dict):
            continue
        section = dict(section_raw)
        section_id = _normalize_writer_section_id(section.get("section_id"))
        if section_id is None or section_id not in section_ids:
            updated_sections.append(section)
            continue
        body_raw = section.get("content_markdown")
        if not isinstance(body_raw, str) or not body_raw.strip():
            updated_sections.append(section)
            continue
        rewritten = body_raw
        for claim in explicit_claims_by_section.get(section_id, []):
            if claim and claim in rewritten:
                rewritten = rewritten.replace(claim, f"{placeholder}区间" if response_language == "zh" else "a qualitative range")
        # Deterministic blocks render evidence-linked structured data (each row
        # carries evidence_ids), so blanket-erasing every digit would destroy
        # verifiable dates/models/figures. Limit them to the exact QA-flagged
        # claims above; keep the defensive whole-body sweep only for narrative
        # prose, where unflagged numbers are unverifiable LLM output.
        spec = get_section_spec(section_id)
        if spec is None or spec.kind != "deterministic":
            rewritten = NUMERIC_RANGE_PATTERN.sub(
                f"{placeholder}区间" if response_language == "zh" else "a qualitative range",
                rewritten,
            )
            rewritten = NUMERIC_LITERAL_PATTERN.sub(placeholder, rewritten)
        rewritten = re.sub(r"\s{2,}", " ", rewritten)
        rewritten = rewritten.strip()
        if rewritten == body_raw.strip():
            updated_sections.append(section)
            continue
        if section_note not in rewritten:
            rewritten = f"{rewritten}\n\n{section_note}"
        section["content_markdown"] = rewritten
        updated_sections.append(section)
        downgraded_sections.append(section_id)

    if not downgraded_sections:
        return report_content, []
    risk_callouts_raw = report_content.get("risk_callouts")
    risk_callouts = (
        [item for item in risk_callouts_raw if isinstance(item, str)]
        if isinstance(risk_callouts_raw, list)
        else []
    )
    guarded_callouts = [
        f"numeric_claims_downgraded:{section_id}"
        for section_id in _stable_unique(downgraded_sections)
    ]
    return (
        {
            **report_content,
            "sections": updated_sections,
            "risk_callouts": _stable_unique([*risk_callouts, *guarded_callouts]),
        },
        _stable_unique(downgraded_sections),
    )


def _report_depth_from_state(state: AgentState) -> Literal["quick", "deep"]:
    intake_draft = state.get("intake_draft")
    if isinstance(intake_draft, dict):
        depth_raw = intake_draft.get("report_depth")
    else:
        depth_raw = getattr(intake_draft, "report_depth", None)
    return "deep" if depth_raw == "deep" else "quick"


def _analyst_payload_from_conclusions(
    conclusions: list[dict[str, object]],
    *,
    comparison_rows: list[dict[str, object]] | None = None,
) -> AnalystOutput:
    insights: list[dict[str, object]] = []
    risk_flags: list[str] = []
    recommended_sections: list[str] = []
    for index, item in enumerate(conclusions):
        section_raw = item.get("section")
        claim_raw = item.get("claim")
        confidence_raw = item.get("confidence")
        evidence_ids_raw = item.get("evidence_ids")
        if (
            not isinstance(section_raw, str)
            or not _is_valid_section_id(section_raw)
            or not isinstance(claim_raw, str)
            or not claim_raw.strip()
            or not isinstance(evidence_ids_raw, list)
        ):
            continue
        evidence_ids = [evidence_id for evidence_id in evidence_ids_raw if isinstance(evidence_id, str)]
        if not evidence_ids:
            continue
        confidence = (
            confidence_raw
            if isinstance(confidence_raw, str) and confidence_raw in {"high", "medium", "low"}
            else "medium"
        )
        insights.append(
            {
                "dimension": section_raw,
                "finding": claim_raw.strip(),
                "confidence": confidence,
                "evidence_ids": _stable_unique(evidence_ids),
            }
        )
        recommended_sections.append(section_raw)
        raw_risk_flags = item.get("risk_flags")
        if isinstance(raw_risk_flags, list):
            risk_flags.extend(flag for flag in raw_risk_flags if isinstance(flag, str))

    summary = (
        f"Loaded {len(insights)} persisted conclusions from structured storage."
        if insights
        else ""
    )
    parsed = AnalystOutput.parse_persisted(
        {
            "summary": summary or "Conclusions loaded from structured storage.",
            "insights": insights,
            "comparisons": [
                item for item in (comparison_rows or []) if isinstance(item, dict)
            ],
            "risk_flags": _stable_unique(risk_flags),
            "recommended_sections": _stable_unique(recommended_sections),
        }
    )
    if parsed is None:
        return AnalystOutput.build_fallback(focus_dimensions=[], evidence_briefs=[])
    return parsed


async def _load_writer_inputs(
    *,
    session_factory: async_sessionmaker[AsyncSession],
    run_id: str,
) -> tuple[list[EvidenceRecord], AnalystOutput, dict[str, object]]:
    async with session_factory() as session:
        evidence_rows = (
            await session.execute(
                select(EvidenceRecord)
                .where(EvidenceRecord.run_id == run_id)
                .order_by(EvidenceRecord.created_at.asc())
            )
        ).scalars().all()
        try:
            knowledge_payload = await load_knowledge_for_run(
                session=session,
                run_id=run_id,
            )
        except SQLAlchemyError as exc:
            log.info(
                "writer.knowledge.fallback_to_empty",
                run_id=run_id,
                reason="query_error",
                error=str(exc)[:500],
            )
            knowledge_payload = _copy_empty_knowledge_payload()
        if settings.WRITER_READ_CONCLUSIONS_FROM_TABLE:
            try:
                conclusion_rows = await load_conclusions_for_run(
                    session=session,
                    run_id=run_id,
                )
            except SQLAlchemyError as exc:
                log.info(
                    "writer.conclusions.fallback_to_json",
                    run_id=run_id,
                    reason="query_error",
                    error=str(exc)[:500],
                )
                conclusion_rows = []
            if conclusion_rows:
                try:
                    comparison_rows = await load_comparisons_for_run(
                        session=session,
                        run_id=run_id,
                    )
                except SQLAlchemyError as exc:
                    log.info(
                        "writer.comparisons.fallback_to_json",
                        run_id=run_id,
                        reason="query_error",
                        error=str(exc)[:500],
                    )
                    comparison_rows = []
                return (
                    evidence_rows,
                    _analyst_payload_from_conclusions(
                        conclusion_rows,
                        comparison_rows=comparison_rows,
                    ),
                    knowledge_payload,
                )
            log.info(
                "writer.conclusions.fallback_to_json",
                run_id=run_id,
                reason="empty_conclusions",
            )
        analyst_step = (
            await session.execute(
                select(Step)
                .where(
                    Step.run_id == run_id,
                    Step.agent_name == "analyst",
                    Step.status == "completed",
                )
                .order_by(Step.created_at.desc())
                .limit(1)
            )
        ).scalars().first()

    evidence_briefs = _build_evidence_briefs(evidence_rows)
    if analyst_step is None:
        return (
            evidence_rows,
            AnalystOutput.build_fallback(
                focus_dimensions=[],
                evidence_briefs=evidence_briefs,
            ),
            knowledge_payload,
        )
    parsed = AnalystOutput.parse_persisted(analyst_step.payload.get("analysis_payload"))
    if parsed is None:
        return (
            evidence_rows,
            AnalystOutput.build_fallback(
                focus_dimensions=[],
                evidence_briefs=evidence_briefs,
            ),
            knowledge_payload,
        )
    return evidence_rows, parsed, knowledge_payload


def _build_evidence_briefs(evidence_rows: list[EvidenceRecord]) -> list[dict[str, object]]:
    briefs: list[dict[str, object]] = []
    for row in evidence_rows:
        span = row.span if isinstance(row.span, dict) else {}
        if span.get("evidence_floor") is True:
            continue
        dimension_raw = span.get("dimension")
        competitor_id_raw = span.get("competitor_id")
        authority_raw = span.get("source_authority")
        category_relevance_raw = span.get("category_relevance")
        category_reason_raw = span.get("category_relevance_reason")
        briefs.append(
            {
                "evidence_id": row.id,
                "dimension": dimension_raw if isinstance(dimension_raw, str) else None,
                "competitor_id": competitor_id_raw if isinstance(competitor_id_raw, str) else "unknown",
                "quote_preview": row.sanitized_text[:EVIDENCE_QUOTE_CHAR_BUDGET],
                "source_title": row.source_title or "",
                "source_url": row.source_url or "",
                "source_type": row.source_type or "",
                "source_authority": authority_raw if isinstance(authority_raw, str) else "third_party",
                "category_relevance": (
                    category_relevance_raw if isinstance(category_relevance_raw, str) else "unknown"
                ),
                "category_relevance_reason": (
                    category_reason_raw if isinstance(category_reason_raw, str) else ""
                ),
            }
        )
    return briefs


def _build_insight_briefs(
    *,
    analyst_output: AnalystOutput,
    allowed_evidence_ids: set[str],
) -> list[dict[str, object]]:
    insight_briefs: list[dict[str, object]] = []
    for index, insight in enumerate(analyst_output.insights):
        evidence_ids = [
            evidence_id for evidence_id in insight.evidence_ids if evidence_id in allowed_evidence_ids
        ]
        insight_briefs.append(
            {
                "insight_id": f"insight_{index + 1}",
                "dimension": insight.dimension,
                "finding": insight.finding,
                "confidence": insight.confidence,
                "evidence_ids": evidence_ids,
            }
        )
    return insight_briefs


def _build_comparison_briefs(
    *,
    analyst_output: AnalystOutput,
    allowed_evidence_ids: set[str],
) -> list[dict[str, object]]:
    comparison_briefs: list[dict[str, object]] = []
    for comparison in analyst_output.comparisons:
        cells_payload: list[dict[str, object]] = []
        for cell in comparison.cells:
            grounded_evidence_ids = [
                evidence_id
                for evidence_id in cell.evidence_ids
                if evidence_id in allowed_evidence_ids
            ]
            cells_payload.append(
                {
                    "competitor_id": cell.competitor_id,
                    "stance": cell.stance,
                    "summary": cell.summary,
                    "evidence_ids": grounded_evidence_ids,
                }
            )
        if not cells_payload:
            continue
        comparison_briefs.append(
            {
                "dimension": comparison.dimension,
                "cells": cells_payload,
            }
        )
    return comparison_briefs


def _ordered_competitors_for_report(
    *,
    state_competitors: list[str],
    evidence_briefs: list[dict[str, object]],
    knowledge_payload: dict[str, object],
) -> list[str]:
    ordered: list[str] = []
    for competitor in state_competitors:
        if isinstance(competitor, str) and competitor.strip():
            ordered.append(competitor.strip())
    for brief in evidence_briefs:
        competitor_raw = brief.get("competitor_id")
        if isinstance(competitor_raw, str) and competitor_raw.strip() and competitor_raw != "unknown":
            ordered.append(competitor_raw.strip())
    for key in ("features", "pricings", "feedback", "personas"):
        rows_raw = knowledge_payload.get(key)
        if not isinstance(rows_raw, list):
            continue
        for row in rows_raw:
            if not isinstance(row, dict):
                continue
            competitor_raw = row.get("competitor_id")
            if isinstance(competitor_raw, str) and competitor_raw.strip():
                ordered.append(competitor_raw.strip())
    coverage_raw = knowledge_payload.get("coverage")
    if isinstance(coverage_raw, dict):
        for competitor_raw in coverage_raw.keys():
            if isinstance(competitor_raw, str) and competitor_raw.strip():
                ordered.append(competitor_raw.strip())
    return _stable_unique(ordered)


def _knowledge_rows_for_competitor(
    *,
    rows: object,
    competitor: str,
) -> list[dict[str, object]]:
    if not isinstance(rows, list):
        return []
    filtered: list[dict[str, object]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        competitor_raw = row.get("competitor_id")
        if isinstance(competitor_raw, str) and competitor_raw.strip() == competitor:
            filtered.append(row)
    return filtered


def _coverage_status(
    *,
    coverage: dict[str, object],
    competitor: str,
    key: str,
) -> str:
    competitor_coverage_raw = coverage.get(competitor)
    if not isinstance(competitor_coverage_raw, dict):
        return "insufficient_data"
    status_raw = competitor_coverage_raw.get(key)
    if isinstance(status_raw, str) and status_raw.strip():
        return status_raw.strip()
    return "insufficient_data"


def _normalized_coverage_for_triage(
    coverage: dict[str, object],
) -> dict[str, dict[str, str]]:
    normalized: dict[str, dict[str, str]] = {}
    for competitor_raw, dims_raw in coverage.items():
        if not isinstance(competitor_raw, str) or not isinstance(dims_raw, dict):
            continue
        dims: dict[str, str] = {}
        for dim_raw, status_raw in dims_raw.items():
            if (
                isinstance(dim_raw, str)
                and dim_raw.strip()
                and isinstance(status_raw, str)
                and status_raw.strip()
            ):
                dims[dim_raw.strip()] = status_raw.strip()
        if dims:
            normalized[competitor_raw] = dims
    return normalized


def _status_label(*, status: str, response_language: str | None) -> str:
    if response_language == "zh":
        return {
            "complete": "完整",
            "partial": "部分",
            "insufficient_data": "未核验",
            "missing": "未采集",
        }.get(status, status)
    return {
        "complete": "Complete",
        "partial": "Partial",
        "insufficient_data": "Insufficient data",
        "missing": "Missing",
    }.get(status, status)


def _gap_dimension_labels(*, dimensions: list[str], response_language: str | None) -> list[str]:
    if response_language == "zh":
        labels = {
            "feature": "功能",
            "pricing": "定价",
            "feedback": "口碑",
            "persona": "用户画像",
        }
    else:
        labels = {
            "feature": "feature",
            "pricing": "pricing",
            "feedback": "feedback",
            "persona": "persona",
        }
    return [labels.get(item, item) for item in dimensions]


def _fallback_evidence_refs(*, allowed_evidence_ids: set[str], limit: int = 4) -> list[str]:
    if not allowed_evidence_ids:
        return []
    return sorted(allowed_evidence_ids)[:limit]


def _collect_competitor_evidence_refs(
    *,
    competitor: str,
    knowledge_payload: dict[str, object],
    evidence_briefs: list[dict[str, object]],
    allowed_evidence_ids: set[str],
    limit: int = 6,
) -> list[str]:
    refs: list[str] = []
    for key in ("features", "pricings", "feedback", "personas"):
        rows = _knowledge_rows_for_competitor(
            rows=knowledge_payload.get(key),
            competitor=competitor,
        )
        for row in rows:
            evidence_ids_raw = row.get("evidence_ids")
            if not isinstance(evidence_ids_raw, list):
                continue
            refs.extend(
                evidence_id
                for evidence_id in evidence_ids_raw
                if isinstance(evidence_id, str) and evidence_id in allowed_evidence_ids
            )
    for brief in evidence_briefs:
        if brief.get("competitor_id") != competitor:
            continue
        evidence_id_raw = brief.get("evidence_id")
        if isinstance(evidence_id_raw, str) and evidence_id_raw in allowed_evidence_ids:
            refs.append(evidence_id_raw)
    return _stable_unique(refs)[:limit]


def _feature_summary(
    *,
    features: list[dict[str, object]],
    response_language: str | None,
) -> str:
    names = [
        str(item.get("name")).strip()
        for item in features
        if isinstance(item.get("name"), str) and str(item.get("name")).strip()
    ]
    if not names:
        return "未找到可核验证据" if response_language == "zh" else "no verifiable evidence found"
    return ", ".join(_stable_unique(names)[:5])


def _pricing_summary(
    *,
    pricings: list[dict[str, object]],
    response_language: str | None,
) -> str:
    if not pricings:
        return "未找到可核验证据" if response_language == "zh" else "no verifiable evidence found"
    models = [
        str(item.get("model")).strip()
        for item in pricings
        if isinstance(item.get("model"), str) and str(item.get("model")).strip()
    ]
    free_plan = any(item.get("free_plan") is True for item in pricings)
    enterprise_plan = any(item.get("enterprise_plan") is True for item in pricings)
    if response_language == "zh":
        return (
            f"模型: {', '.join(_stable_unique(models)[:3]) or 'unknown'}; "
            f"免费版: {'是' if free_plan else '否/未知'}; "
            f"企业版: {'是' if enterprise_plan else '否/未知'}"
        )
    return (
        f"models: {', '.join(_stable_unique(models)[:3]) or 'unknown'}; "
        f"free_plan: {'yes' if free_plan else 'no/unknown'}; "
        f"enterprise_plan: {'yes' if enterprise_plan else 'no/unknown'}"
    )


def _feedback_summary(
    *,
    feedback_rows: list[dict[str, object]],
    response_language: str | None,
) -> str:
    if not feedback_rows:
        return "未找到可核验证据" if response_language == "zh" else "no verifiable evidence found"
    sentiments = Counter(
        str(item.get("sentiment")).strip()
        for item in feedback_rows
        if isinstance(item.get("sentiment"), str) and str(item.get("sentiment")).strip()
    )
    topics = [
        str(item.get("topic")).strip()
        for item in feedback_rows
        if isinstance(item.get("topic"), str) and str(item.get("topic")).strip()
    ]
    sentiment_text = _format_distribution(
        sentiments,
        empty_label="无" if response_language == "zh" else "none",
    )
    topics_text = ", ".join(_stable_unique(topics)[:3]) or ("无" if response_language == "zh" else "none")
    if response_language == "zh":
        return f"情绪分布: {sentiment_text}; 高频主题: {topics_text}"
    return f"sentiment: {sentiment_text}; top topics: {topics_text}"


def _positioning_summary_from_comparisons(
    *,
    competitor: str,
    comparison_briefs: list[dict[str, object]],
    response_language: str | None,
) -> str:
    for comparison in comparison_briefs:
        cells_raw = comparison.get("cells")
        if not isinstance(cells_raw, list):
            continue
        for cell in cells_raw:
            if not isinstance(cell, dict):
                continue
            competitor_raw = cell.get("competitor_id")
            summary_raw = cell.get("summary")
            if (
                isinstance(competitor_raw, str)
                and competitor_raw == competitor
                and isinstance(summary_raw, str)
                and summary_raw.strip()
            ):
                return summary_raw.strip()
    return "定位信息不足" if response_language == "zh" else "positioning data is limited"


def _upsert_section(
    *,
    sections: list[dict[str, object]],
    section_payload: dict[str, object],
) -> None:
    section_id_raw = section_payload.get("section_id")
    if not isinstance(section_id_raw, str) or not section_id_raw.strip():
        return
    section_id = section_id_raw.strip()
    for index, section in enumerate(sections):
        if not isinstance(section, dict):
            continue
        existing_section_id = section.get("section_id")
        if isinstance(existing_section_id, str) and existing_section_id.strip() == section_id:
            sections[index] = section_payload
            return
    sections.append(section_payload)


def _source_payload_for_competitor(
    discovered_competitor_sources: dict[str, dict[str, object]] | None,
    competitor: str,
) -> dict[str, object]:
    payload = (discovered_competitor_sources or {}).get(competitor)
    return payload if isinstance(payload, dict) else {}


def _admission_status_for_competitor(
    discovered_competitor_sources: dict[str, dict[str, object]] | None,
    competitor: str,
) -> str:
    payload = _source_payload_for_competitor(discovered_competitor_sources, competitor)
    status = payload.get("admission_status")
    if isinstance(status, str) and status.strip():
        return status.strip()
    role = payload.get("candidate_role")
    if role in CORE_DISCOVERY_ROLES:
        return "main_player"
    if role == "upstream_supplier":
        return "value_chain"
    if role == "trend_reference":
        return "watchlist"
    return "watchlist"


def _target_evidence_refs_for_competitor(
    *,
    competitor: str,
    evidence_briefs: list[dict[str, object]],
    allowed_evidence_ids: set[str],
    limit: int = 5,
) -> list[str]:
    refs = [
        str(brief["evidence_id"])
        for brief in evidence_briefs
        if brief.get("competitor_id") == competitor
        and brief.get("category_relevance") == "target"
        and isinstance(brief.get("evidence_id"), str)
        and brief["evidence_id"] in allowed_evidence_ids
    ]
    return _stable_unique(refs)[:limit]


def _admitted_key_players(
    *,
    ordered_competitors: list[str],
    discovered_competitor_sources: dict[str, dict[str, object]] | None,
    evidence_briefs: list[dict[str, object]],
    allowed_evidence_ids: set[str],
) -> list[str]:
    admitted: list[str] = []
    for competitor in ordered_competitors:
        status = _admission_status_for_competitor(discovered_competitor_sources, competitor)
        target_refs = _target_evidence_refs_for_competitor(
            competitor=competitor,
            evidence_briefs=evidence_briefs,
            allowed_evidence_ids=allowed_evidence_ids,
        )
        if status == "main_player" and target_refs:
            admitted.append(competitor)
        elif status == "segment_player" and target_refs:
            admitted.append(competitor)
    return admitted


def _section_payload(
    *,
    section_id: str,
    response_language: str | None,
    content_markdown: str,
    evidence_refs: list[str],
) -> dict[str, object]:
    return {
        "section_id": section_id,
        "title": _section_title(section_id, response_language=response_language),
        "content_markdown": content_markdown.strip(),
        "evidence_refs": _stable_unique(evidence_refs),
        "insight_refs": [],
    }


def _landscape_status_groups(
    *,
    ordered_competitors: list[str],
    discovered_competitor_sources: dict[str, dict[str, object]] | None,
) -> dict[str, list[str]]:
    groups: dict[str, list[str]] = {
        "main_player": [],
        "segment_player": [],
        "value_chain": [],
        "watchlist": [],
    }
    for competitor in ordered_competitors:
        status = _admission_status_for_competitor(discovered_competitor_sources, competitor)
        if status in groups:
            groups[status].append(competitor)
    return groups


def _landscape_evidence_ref_split(
    *,
    evidence_briefs: list[dict[str, object]],
    allowed_evidence_ids: set[str],
    target_category: str | None,
) -> tuple[list[str], list[str]]:
    target_relevance_values = {"target"}
    if target_category is None:
        target_relevance_values.add("unknown")
    target_refs = [
        str(brief["evidence_id"])
        for brief in evidence_briefs
        if brief.get("category_relevance") in target_relevance_values
        and isinstance(brief.get("evidence_id"), str)
        and brief["evidence_id"] in allowed_evidence_ids
    ]
    adjacent_refs = [
        str(brief["evidence_id"])
        for brief in evidence_briefs
        if brief.get("category_relevance") == "adjacent_segment"
        and isinstance(brief.get("evidence_id"), str)
        and brief["evidence_id"] in allowed_evidence_ids
    ]
    return _stable_unique(target_refs), _stable_unique(adjacent_refs)


def _landscape_reference_refs(
    *,
    evidence_briefs: list[dict[str, object]],
    allowed_evidence_ids: set[str],
    target_category: str | None,
    limit: int,
) -> list[str]:
    target_refs, adjacent_refs = _landscape_evidence_ref_split(
        evidence_briefs=evidence_briefs,
        allowed_evidence_ids=allowed_evidence_ids,
        target_category=target_category,
    )
    refs = _stable_unique([*target_refs, *adjacent_refs])[:limit]
    return refs or _fallback_evidence_refs(allowed_evidence_ids=allowed_evidence_ids, limit=limit)


def _landscape_structure_facts(
    *,
    ordered_competitors: list[str],
    discovered_competitor_sources: dict[str, dict[str, object]] | None,
    knowledge_payload: dict[str, object],
    evidence_briefs: list[dict[str, object]],
    allowed_evidence_ids: set[str],
    target_category: str | None,
    category_aliases: list[str],
    excluded_categories: list[str],
    market_segments: list[str],
    scope_policy: str | None,
) -> dict[str, object]:
    """System-derived landscape facts handed to the writer LLM as grounded input.

    The LLM owns every section narrative; this only encodes the classification the
    model must not invent (admission tiers, category scope, coverage, evidence counts)
    so it can write grounded prose and cite the right evidence instead of guessing.
    """
    coverage_raw = knowledge_payload.get("coverage")
    coverage = coverage_raw if isinstance(coverage_raw, dict) else {}
    status_groups = _landscape_status_groups(
        ordered_competitors=ordered_competitors,
        discovered_competitor_sources=discovered_competitor_sources,
    )
    key_players = _admitted_key_players(
        ordered_competitors=ordered_competitors,
        discovered_competitor_sources=discovered_competitor_sources,
        evidence_briefs=evidence_briefs,
        allowed_evidence_ids=allowed_evidence_ids,
    )
    target_refs, adjacent_refs = _landscape_evidence_ref_split(
        evidence_briefs=evidence_briefs,
        allowed_evidence_ids=allowed_evidence_ids,
        target_category=target_category,
    )

    def _player_fact(competitor: str) -> dict[str, object]:
        competitor_coverage = coverage.get(competitor) if isinstance(coverage, dict) else None
        return {
            "competitor_id": competitor,
            "admission_status": _admission_status_for_competitor(
                discovered_competitor_sources, competitor
            ),
            "target_evidence_ids": _target_evidence_refs_for_competitor(
                competitor=competitor,
                evidence_briefs=evidence_briefs,
                allowed_evidence_ids=allowed_evidence_ids,
            ),
            "coverage": competitor_coverage if isinstance(competitor_coverage, dict) else {},
        }

    coverage_gaps: dict[str, list[str]] = {}
    missing_reasons_raw = knowledge_payload.get("missing_reasons")
    if isinstance(missing_reasons_raw, dict):
        for competitor, reasons in missing_reasons_raw.items():
            if isinstance(competitor, str) and isinstance(reasons, list) and reasons:
                coverage_gaps[competitor] = [str(reason) for reason in reasons[:6]]

    return {
        "target_category": target_category,
        "category_aliases": list(category_aliases),
        "excluded_categories": list(excluded_categories),
        "market_segments": list(market_segments),
        "scope_policy": scope_policy or "explicit_category",
        "admission_tiers": {
            "main_player": [_player_fact(item) for item in status_groups["main_player"]],
            "segment_player": [_player_fact(item) for item in status_groups["segment_player"]],
            "value_chain": [
                {
                    "competitor_id": competitor,
                    "reason": str(
                        _source_payload_for_competitor(
                            discovered_competitor_sources, competitor
                        ).get("admission_reason")
                        or "value chain evidence"
                    ),
                }
                for competitor in status_groups["value_chain"]
            ],
            "watchlist": list(status_groups["watchlist"]),
        },
        "key_players": key_players,
        "target_evidence_count": len(target_refs),
        "adjacent_evidence_count": len(adjacent_refs),
        "coverage_gaps": coverage_gaps,
    }


# Plain-language disclosure of per-competitor coverage gaps. Keys are the
# `reason` half of the `bucket:reason` codes produced upstream; ordering goes
# from most to least concerning so the report leads with real evidence gaps.
_GAP_REASON_ORDER: tuple[str, ...] = (
    "no_grounded_evidence",
    "category_mismatch",
    "coverage_partial",
    "not_applicable_for_archetype",
)
_GAP_REASON_PHRASES: dict[str, dict[str, str]] = {
    "no_grounded_evidence": {
        "zh": "公开证据不足，未展开功能/定价/画像分析",
        "en": "insufficient public evidence; not analyzed in depth",
    },
    "category_mismatch": {
        "zh": "现有证据与目标品类匹配度有限，结论仅供参考",
        "en": "evidence only partially matches the target category; treat as indicative",
    },
    "coverage_partial": {
        "zh": "证据部分覆盖，结论以现有维度为准",
        "en": "partial coverage; conclusions limited to covered dimensions",
    },
    "not_applicable_for_archetype": {
        "zh": "趋势/全景模式下不强制结构化字段",
        "en": "structured fields not required in landscape mode",
    },
}


def _dominant_gap_reason(reasons: list[object]) -> str | None:
    """Pick one disclosure bucket for a competitor from its `bucket:reason` codes.

    Severity order wins ties so a competitor with any genuine evidence gap is
    grouped under that gap rather than a benign "not applicable" note.
    """
    seen: set[str] = set()
    for raw in reasons:
        text = str(raw)
        suffix = text.split(":", 1)[1] if ":" in text else text
        if suffix in _GAP_REASON_PHRASES:
            seen.add(suffix)
    for reason_key in _GAP_REASON_ORDER:
        if reason_key in seen:
            return reason_key
    return None


def _landscape_methodology_section(
    *,
    facts: dict[str, object],
    evidence_briefs: list[dict[str, object]],
    allowed_evidence_ids: set[str],
    response_language: str | None,
) -> dict[str, object]:
    zh = response_language == "zh"
    target_count = int(facts.get("target_evidence_count", 0) or 0)
    adjacent_count = int(facts.get("adjacent_evidence_count", 0) or 0)
    tiers_raw = facts.get("admission_tiers")
    tiers = tiers_raw if isinstance(tiers_raw, dict) else {}
    watchlist_raw = tiers.get("watchlist")
    watchlist = [str(item) for item in watchlist_raw] if isinstance(watchlist_raw, list) else []
    lines = [
        f"- 目标品类证据数: {target_count}" if zh else f"- Target-category evidence count: {target_count}",
        (
            f"- 相邻细分证据数: {adjacent_count}"
            if zh
            else f"- Adjacent-segment evidence count: {adjacent_count}"
        ),
        (
            f"- 观察名单不进入关键玩家主分析: {', '.join(watchlist) or '暂无'}"
            if zh
            else f"- Watchlist excluded from key-player analysis: {', '.join(watchlist) or 'none'}"
        ),
    ]
    coverage_gaps = facts.get("coverage_gaps")
    if isinstance(coverage_gaps, dict):
        # Disclose gaps in plain language grouped by dominant reason, instead of
        # dumping raw `bucket:reason` machine codes (e.g. feature:no_grounded_evidence)
        # into the user-facing report — those codes read as garbage to a reader.
        competitors_by_reason: dict[str, list[str]] = {}
        for competitor, reasons in coverage_gaps.items():
            if not (isinstance(competitor, str) and isinstance(reasons, list) and reasons):
                continue
            dominant = _dominant_gap_reason(reasons)
            if dominant is None:
                continue
            competitors_by_reason.setdefault(dominant, []).append(competitor)
        for reason_key in _GAP_REASON_ORDER:
            competitors = competitors_by_reason.get(reason_key)
            if not competitors:
                continue
            phrase = _GAP_REASON_PHRASES[reason_key]["zh" if zh else "en"]
            joined = "、".join(sorted(competitors)) if zh else ", ".join(sorted(competitors))
            lines.append(f"- {joined}：{phrase}" if zh else f"- {joined}: {phrase}")
    lines.append(
        "- 方法论边界优先披露证据缺口、样本偏差和准入规则，避免把未覆盖信息包装成确定性结论。"
        if zh
        else "- Methodology limits disclose evidence gaps, sample bias, and admission rules before presenting uncovered areas as conclusions."
    )
    if facts.get("scope_policy") == "broad_market" and adjacent_count > 0:
        lines.append(
            "- 样本偏差: 本轮证据集中在相邻/细分赛道，整体市场结论已按保守口径处理。"
            if zh
            else "- Sample bias: evidence clusters in adjacent/segment markets; whole-market claims are conservative."
        )
    target_category = (
        facts.get("target_category") if isinstance(facts.get("target_category"), str) else None
    )
    return _section_payload(
        section_id="methodology_limits",
        response_language=response_language,
        content_markdown="\n".join(lines),
        evidence_refs=_landscape_reference_refs(
            evidence_briefs=evidence_briefs,
            allowed_evidence_ids=allowed_evidence_ids,
            target_category=target_category,
            limit=6,
        ),
    )


def _landscape_degrade_stub(
    *,
    section_id: str,
    facts: dict[str, object],
    evidence_briefs: list[dict[str, object]],
    allowed_evidence_ids: set[str],
    response_language: str | None,
) -> dict[str, object]:
    zh = response_language == "zh"
    content = (
        "本节所需的目标品类证据不足，未生成结论；相关缺口已在方法论与证据边界中披露，"
        "待补充可核验证据后再扩展该章节。"
        if zh
        else "Target-category evidence for this section is insufficient, so no conclusion is generated; "
        "the gap is disclosed in methodology limits and will be expanded once verifiable evidence is added."
    )
    target_category = (
        facts.get("target_category") if isinstance(facts.get("target_category"), str) else None
    )
    return _section_payload(
        section_id=section_id,
        response_language=response_language,
        content_markdown=content,
        evidence_refs=_landscape_reference_refs(
            evidence_briefs=evidence_briefs,
            allowed_evidence_ids=allowed_evidence_ids,
            target_category=target_category,
            limit=4,
        ),
    )


def _build_comparison_matrix_section(
    *,
    competitors: list[str],
    knowledge_payload: dict[str, object],
    coverage: dict[str, object],
    response_language: str | None,
    evidence_briefs: list[dict[str, object]],
    allowed_evidence_ids: set[str],
) -> dict[str, object]:
    feature_rows: list[list[object]] = []
    pricing_rows: list[list[object]] = []
    feedback_rows: list[list[object]] = []
    evidence_refs: list[str] = []
    for competitor in competitors:
        features = _knowledge_rows_for_competitor(
            rows=knowledge_payload.get("features"),
            competitor=competitor,
        )
        pricings = _knowledge_rows_for_competitor(
            rows=knowledge_payload.get("pricings"),
            competitor=competitor,
        )
        feedback = _knowledge_rows_for_competitor(
            rows=knowledge_payload.get("feedback"),
            competitor=competitor,
        )
        evidence_refs.extend(
            _collect_competitor_evidence_refs(
                competitor=competitor,
                knowledge_payload=knowledge_payload,
                evidence_briefs=evidence_briefs,
                allowed_evidence_ids=allowed_evidence_ids,
                limit=4,
            )
        )
        feature_rows.append(
            [
                competitor,
                _feature_summary(features=features, response_language=response_language),
                _status_label(
                    status=_coverage_status(coverage=coverage, competitor=competitor, key="feature"),
                    response_language=response_language,
                ),
            ]
        )
        pricing_rows.append(
            [
                competitor,
                _pricing_summary(pricings=pricings, response_language=response_language),
                _status_label(
                    status=_coverage_status(coverage=coverage, competitor=competitor, key="pricing"),
                    response_language=response_language,
                ),
            ]
        )
        feedback_rows.append(
            [
                competitor,
                _feedback_summary(feedback_rows=feedback, response_language=response_language),
                _status_label(
                    status=_coverage_status(coverage=coverage, competitor=competitor, key="feedback"),
                    response_language=response_language,
                ),
            ]
        )
    if not feature_rows:
        empty_label = "未找到可核验证据" if response_language == "zh" else "no verifiable evidence found"
        empty_status = "未核验" if response_language == "zh" else "Insufficient data"
        feature_rows = [["-", empty_label, empty_status]]
        pricing_rows = [["-", empty_label, empty_status]]
        feedback_rows = [["-", empty_label, empty_status]]
    lines = [
        "### 功能矩阵" if response_language == "zh" else "### Feature Matrix",
        _build_markdown_table(
            headers=(
                ["竞品", "核心功能摘要", "覆盖状态"]
                if response_language == "zh"
                else ["Competitor", "Feature Summary", "Coverage"]
            ),
            rows=feature_rows,
        ),
        "",
        "### 定价对比" if response_language == "zh" else "### Pricing Comparison",
        _build_markdown_table(
            headers=(
                ["竞品", "定价摘要", "覆盖状态"]
                if response_language == "zh"
                else ["Competitor", "Pricing Summary", "Coverage"]
            ),
            rows=pricing_rows,
        ),
        "",
        "### 口碑矩阵" if response_language == "zh" else "### Feedback Matrix",
        _build_markdown_table(
            headers=(
                ["竞品", "口碑摘要", "覆盖状态"]
                if response_language == "zh"
                else ["Competitor", "Feedback Summary", "Coverage"]
            ),
            rows=feedback_rows,
        ),
    ]
    section_refs = _stable_unique(evidence_refs)[:12] or _fallback_evidence_refs(
        allowed_evidence_ids=allowed_evidence_ids,
    )
    return {
        "section_id": "comparison_matrix",
        "title": _section_title("comparison_matrix", response_language=response_language),
        "content_markdown": "\n".join(lines),
        "evidence_refs": section_refs,
        "insight_refs": [],
    }


def _build_competitor_profiles_section(
    *,
    profile_competitors: list[str],
    knowledge_payload: dict[str, object],
    coverage: dict[str, object],
    response_language: str | None,
    comparison_briefs: list[dict[str, object]],
    evidence_briefs: list[dict[str, object]],
    allowed_evidence_ids: set[str],
) -> dict[str, object]:
    lines: list[str] = []
    section_refs: list[str] = []
    for competitor in profile_competitors:
        features = _knowledge_rows_for_competitor(
            rows=knowledge_payload.get("features"),
            competitor=competitor,
        )
        pricings = _knowledge_rows_for_competitor(
            rows=knowledge_payload.get("pricings"),
            competitor=competitor,
        )
        feedback_rows = _knowledge_rows_for_competitor(
            rows=knowledge_payload.get("feedback"),
            competitor=competitor,
        )
        competitor_refs = _collect_competitor_evidence_refs(
            competitor=competitor,
            knowledge_payload=knowledge_payload,
            evidence_briefs=evidence_briefs,
            allowed_evidence_ids=allowed_evidence_ids,
            limit=5,
        )
        section_refs.extend(competitor_refs)
        weakness_dimensions = [
            key
            for key in ("feature", "pricing", "feedback")
            if _coverage_status(coverage=coverage, competitor=competitor, key=key)
            in {"insufficient_data", "missing"}
        ]
        gap_labels = _gap_dimension_labels(
            dimensions=weakness_dimensions,
            response_language=response_language,
        )
        refs_text = ", ".join(f"[{item}]" for item in competitor_refs) if competitor_refs else "-"
        lines.append(f"### {competitor}")
        lines.append(
            f"- 定位: {_positioning_summary_from_comparisons(competitor=competitor, comparison_briefs=comparison_briefs, response_language=response_language)}"
            if response_language == "zh"
            else (
                f"- Positioning: {_positioning_summary_from_comparisons(competitor=competitor, comparison_briefs=comparison_briefs, response_language=response_language)}"
            )
        )
        if features:
            lines.append(
                f"- 优势: {_feature_summary(features=features, response_language=response_language)}"
                if response_language == "zh"
                else f"- Strengths: {_feature_summary(features=features, response_language=response_language)}"
            )
        if pricings:
            lines.append(
                f"- 定价: {_pricing_summary(pricings=pricings, response_language=response_language)}"
                if response_language == "zh"
                else f"- Pricing: {_pricing_summary(pricings=pricings, response_language=response_language)}"
            )
        if feedback_rows:
            lines.append(
                f"- 口碑: {_feedback_summary(feedback_rows=feedback_rows, response_language=response_language)}"
                if response_language == "zh"
                else f"- Feedback: {_feedback_summary(feedback_rows=feedback_rows, response_language=response_language)}"
            )
        if gap_labels:
            gap_text = "、".join(gap_labels) if response_language == "zh" else ", ".join(gap_labels)
            lines.append(
                f"- 数据缺口: {gap_text} 缺少可核验证据"
                if response_language == "zh"
                else f"- Evidence gaps: {gap_text} lack verifiable evidence"
            )
        else:
            lines.append(
                "- 数据覆盖: 关键维度覆盖完整"
                if response_language == "zh"
                else "- Data coverage: key dimensions are covered"
            )
        lines.extend(
            [
                f"- 代表证据: {refs_text}"
                if response_language == "zh"
                else f"- Key evidence: {refs_text}",
                "",
            ]
        )
    if not lines:
        lines = [
            "- 暂无可用竞品画像，建议触发补充研究并优先补齐关键维度证据。"
            if response_language == "zh"
            else "- No usable competitor profiles yet; trigger follow-up research to fill key-dimension evidence."
        ]
    return {
        "section_id": "competitor_profiles",
        "title": _section_title("competitor_profiles", response_language=response_language),
        "content_markdown": "\n".join(lines).strip(),
        "evidence_refs": _stable_unique(section_refs)[:12]
        or _fallback_evidence_refs(allowed_evidence_ids=allowed_evidence_ids),
        "insight_refs": [],
    }


def _positioning_clusters_from_coverage(
    *,
    competitors: list[str],
    coverage: dict[str, object],
) -> dict[str, list[str]]:
    def score(status: str) -> int:
        if status == "complete":
            return 2
        if status == "partial":
            return 1
        return 0

    clusters: dict[str, list[str]] = {
        "leaders": [],
        "capability_potential": [],
        "commercial_execution": [],
        "watchlist": [],
    }
    for competitor in competitors:
        capability = score(_coverage_status(coverage=coverage, competitor=competitor, key="feature")) + score(
            _coverage_status(coverage=coverage, competitor=competitor, key="feedback")
        )
        commercialization = score(_coverage_status(coverage=coverage, competitor=competitor, key="pricing"))
        if capability >= 2 and commercialization >= 1:
            clusters["leaders"].append(competitor)
        elif capability >= 2 and commercialization < 1:
            clusters["capability_potential"].append(competitor)
        elif capability < 2 and commercialization >= 1:
            clusters["commercial_execution"].append(competitor)
        else:
            clusters["watchlist"].append(competitor)
    return clusters


def _positioning_signal_summary(
    *,
    clusters: dict[str, list[str]],
    response_language: str | None,
) -> str:
    ordered_clusters = [
        ("领先梯队", "leaders", "leaders"),
        ("能力潜力梯队", "capability_potential", "capability potential"),
        ("商业执行梯队", "commercial_execution", "commercial execution"),
        ("观察梯队", "watchlist", "watchlist"),
    ]
    if response_language == "zh":
        parts = [
            f"{zh_label} {', '.join(clusters.get(key, []))}"
            for zh_label, key, _en_label in ordered_clusters
            if clusters.get(key)
        ]
        if not parts:
            return "当前证据不足以形成稳定定位信号。"
        return "基于功能深度与商业化成熟度同一定位信号：" + "；".join(parts) + "。"
    parts = [
        f"{en_label} {', '.join(clusters.get(key, []))}"
        for _zh_label, key, en_label in ordered_clusters
        if clusters.get(key)
    ]
    if not parts:
        return "Current evidence is insufficient for a stable positioning signal."
    return "Using the same positioning signal (capability depth + commercial maturity): " + "; ".join(parts) + "."


def _build_positioning_map_section(
    *,
    competitors: list[str],
    response_language: str | None,
    knowledge_payload: dict[str, object],
    evidence_briefs: list[dict[str, object]],
    allowed_evidence_ids: set[str],
    clusters: dict[str, list[str]],
) -> dict[str, object]:
    refs: list[str] = []
    for competitor in competitors:
        refs.extend(
            _collect_competitor_evidence_refs(
                competitor=competitor,
                knowledge_payload=knowledge_payload,
                evidence_briefs=evidence_briefs,
                allowed_evidence_ids=allowed_evidence_ids,
                limit=3,
            )
        )
    if response_language == "zh":
        candidates = [
            "- 定位信号: 能力深度（feature + feedback） × 商业化成熟度（pricing）",
            (
                "- 能力潜力梯队（能力强、商业化待补）: "
                f"{', '.join(clusters.get('capability_potential', []))}"
            ),
            (
                "- 商业执行梯队（商业化强、能力待补）: "
                f"{', '.join(clusters.get('commercial_execution', []))}"
            ),
            f"- 观察梯队（能力与商业化均待补）: {', '.join(clusters.get('watchlist', []))}",
        ]
        lines = [
            "- 定位信号: 能力深度（feature + feedback） × 商业化成熟度（pricing）"
        ]
        if clusters.get("leaders"):
            lines.append(f"- 领先梯队（能力深、商业化强）: {', '.join(clusters['leaders'])}")
        lines.extend(
            candidate
            for candidate in candidates[1:]
            if not candidate.endswith(": ")
        )
    else:
        lines = [
            "- Positioning signal: capability depth (feature + feedback) x commercial maturity (pricing).",
        ]
        if clusters.get("leaders"):
            lines.append(f"- Leaders (high capability, high maturity): {', '.join(clusters['leaders'])}")
        if clusters.get("capability_potential"):
            lines.append(
                "- Capability potential (high capability, maturity gap): "
                f"{', '.join(clusters['capability_potential'])}"
            )
        if clusters.get("commercial_execution"):
            lines.append(
                "- Commercial execution (high maturity, capability gap): "
                f"{', '.join(clusters['commercial_execution'])}"
            )
        if clusters.get("watchlist"):
            lines.append(f"- Watchlist (capability and maturity both thin): {', '.join(clusters['watchlist'])}")
    return {
        "section_id": "positioning_map",
        "title": _section_title("positioning_map", response_language=response_language),
        "content_markdown": "\n".join(lines),
        "evidence_refs": _stable_unique(refs)[:10]
        or _fallback_evidence_refs(allowed_evidence_ids=allowed_evidence_ids),
        "insight_refs": [],
    }


def _build_self_positioning_section(
    *,
    self_product: str | None,
    competitors: list[str],
    response_language: str | None,
    allowed_evidence_ids: set[str],
) -> dict[str, object]:
    leaders = ", ".join(competitors[:2]) if competitors else ("关键竞品" if response_language == "zh" else "key competitors")
    if response_language == "zh":
        product_name = self_product or "我方产品"
        content = "\n".join(
            [
                f"- 对照对象: {leaders}",
                f"- 当前定位: {product_name} 需围绕功能深度与商业化成熟度建立稳定差异化。",
                "- 建议动作: 以可验证的功能优势 + 可执行定价策略形成 why-win/why-lose 叙事闭环。",
            ]
        )
    else:
        product_name = self_product or "our product"
        content = "\n".join(
            [
                f"- Reference set: {leaders}",
                f"- Current position: {product_name} should build durable differentiation on capability depth and commercial maturity.",
                "- Action focus: combine verifiable feature advantages with executable pricing strategy for clear why-win/why-lose narratives.",
            ]
        )
    return {
        "section_id": "self_positioning",
        "title": _section_title("self_positioning", response_language=response_language),
        "content_markdown": content,
        "evidence_refs": _fallback_evidence_refs(allowed_evidence_ids=allowed_evidence_ids),
        "insight_refs": [],
    }


# Sections the deterministic pass later rebuilds from tables/bookkeeping — a
# deepening call on these is wasted because `_apply_structured_writer_sections`
# overwrites them. Everything else in a landscape report is LLM-owned narrative.
_DETERMINISTIC_OVERWRITE_SECTIONS_COMPARISON: frozenset[str] = frozenset(
    {"competitor_profiles", "comparison_matrix", "positioning_map", "self_positioning"}
)


def _section_is_deterministically_overwritten(
    section_id: str, *, analysis_archetype: str
) -> bool:
    if analysis_archetype == "landscape":
        return section_id == "methodology_limits"
    return section_id in _DETERMINISTIC_OVERWRITE_SECTIONS_COMPARISON


def _section_deepen_briefs(
    *,
    section_refs: list[str],
    evidence_briefs: list[dict[str, object]],
) -> list[dict[str, object]]:
    """Evidence pool for one section: its cited briefs first, then a layered pool.

    Keeping the section's own citations guarantees the prose stays grounded in the
    evidence it already used, while the layered pool lets the model reach for
    adjacent facts when it deepens the analysis.
    """
    by_id = {
        brief["evidence_id"]: brief
        for brief in evidence_briefs
        if isinstance(brief.get("evidence_id"), str)
    }
    primary = [by_id[ref] for ref in section_refs if ref in by_id]
    seen = {brief["evidence_id"] for brief in primary}
    merged = list(primary)
    for brief in select_layered_evidence_briefs(evidence_briefs):
        evidence_id = brief.get("evidence_id")
        if not isinstance(evidence_id, str) or evidence_id in seen:
            continue
        seen.add(evidence_id)
        merged.append(brief)
    return merged


async def _deepen_single_section(
    *,
    section_id: str,
    section_title_text: str,
    draft_markdown: str,
    user_query: str,
    analysis_archetype: str,
    response_language: str | None,
    report_depth: Literal["quick", "deep"],
    target_min_chars: int,
    section_allowed_ids: set[str],
    briefs: list[dict[str, object]],
    analyst_summary: str,
    insight_briefs: list[dict[str, object]],
    landscape_facts: dict[str, object] | None,
) -> tuple[WriterSectionOutput | None, LLMResponse]:
    def _parse(content: dict[str, object]) -> WriterSectionOutput:
        parsed = WriterSectionOutput.model_validate(content)
        grounded_refs = _stable_unique(
            [ref for ref in parsed.evidence_refs if ref in section_allowed_ids]
        )
        if not grounded_refs:
            raise ValueError("section deepen produced no grounded evidence_refs")
        return parsed.model_copy(
            update={
                "section_id": section_id,
                "title": section_title_text,
                "evidence_refs": grounded_refs,
            }
        )

    harness_result = await complete_structured(
        model_slot="writer",
        system_prompt=WRITER_SECTION_SYSTEM_PROMPT,
        user_prompt=build_writer_section_deepen_user_prompt(
            section_id=section_id,
            section_title=section_title_text,
            section_draft=draft_markdown,
            user_query=user_query,
            analysis_archetype=analysis_archetype,
            response_language=response_language,
            report_depth=report_depth,
            target_min_chars=target_min_chars,
            allowed_evidence_ids=sorted(section_allowed_ids),
            evidence_briefs=briefs,
            analyst_summary=analyst_summary,
            analyst_insights=insight_briefs,
            landscape_facts=landscape_facts,
        ),
        output_model=WriterSectionOutput,
        parser=_parse,
        log_event="writer.section_deepen.finish",
    )
    return harness_result.value, harness_result.llm_response


async def _deepen_report_sections(
    *,
    report_content: dict[str, object],
    analysis_archetype: str,
    response_language: str | None,
    report_depth: Literal["quick", "deep"],
    user_query: str,
    evidence_briefs: list[dict[str, object]],
    analyst_summary: str,
    insight_briefs: list[dict[str, object]],
    landscape_facts: dict[str, object] | None,
    persist_response: Callable[[str, LLMResponse], Awaitable[None]] | None = None,
) -> tuple[dict[str, object], list[LLMResponse], list[str]]:
    """Second pass: rewrite each LLM-owned narrative section on its own.

    One focused call per section lets the model spend its full output budget on a
    single topic instead of collapsing every section into a few sentences. Runs
    sequentially to avoid stacking concurrent provider calls; any per-section
    failure (or a result no longer/shorter than the draft) keeps the first draft.
    """
    sections_raw = report_content.get("sections")
    if not isinstance(sections_raw, list):
        return report_content, [], []
    min_chars = max(MIN_WRITER_SECTION_CHARS, settings.WRITER_SECTION_DEEPEN_MIN_CHARS)
    updated_sections: list[object] = []
    deepen_responses: list[LLMResponse] = []
    deepened_ids: list[str] = []
    for section in sections_raw:
        if not isinstance(section, dict):
            updated_sections.append(section)
            continue
        section_id = section.get("section_id")
        draft = section.get("content_markdown")
        if (
            not isinstance(section_id, str)
            or not isinstance(draft, str)
            or _section_is_deterministically_overwritten(
                section_id, analysis_archetype=analysis_archetype
            )
        ):
            updated_sections.append(section)
            continue
        section_refs = [ref for ref in section.get("evidence_refs", []) if isinstance(ref, str)]
        briefs = _section_deepen_briefs(
            section_refs=section_refs,
            evidence_briefs=evidence_briefs,
        )
        section_allowed_ids = {
            brief["evidence_id"]
            for brief in briefs
            if isinstance(brief.get("evidence_id"), str)
        }
        if not section_allowed_ids:
            updated_sections.append(section)
            continue
        title_raw = section.get("title")
        section_title_text = (
            title_raw.strip()
            if isinstance(title_raw, str) and title_raw.strip()
            else section_title(section_id, response_language=response_language)
        )
        deepened, llm_response = await _deepen_single_section(
            section_id=section_id,
            section_title_text=section_title_text,
            draft_markdown=draft,
            user_query=user_query,
            analysis_archetype=analysis_archetype,
            response_language=response_language,
            report_depth=report_depth,
            target_min_chars=min_chars,
            section_allowed_ids=section_allowed_ids,
            briefs=briefs,
            analyst_summary=analyst_summary,
            insight_briefs=insight_briefs,
            landscape_facts=landscape_facts,
        )
        deepen_responses.append(llm_response)
        if persist_response is not None:
            await persist_response(section_id, llm_response)
        deepened_body = deepened.content_markdown.strip() if deepened is not None else ""
        if deepened is not None and len(deepened_body) >= max(min_chars, len(draft.strip())):
            merged = dict(section)
            merged["content_markdown"] = deepened.content_markdown
            merged["evidence_refs"] = _stable_unique([*section_refs, *deepened.evidence_refs])
            updated_sections.append(merged)
            deepened_ids.append(section_id)
        else:
            updated_sections.append(section)
    return {**report_content, "sections": updated_sections}, deepen_responses, deepened_ids


def _apply_structured_writer_sections(
    *,
    report_content: dict[str, object],
    target_sections: list[str],
    analysis_archetype: str,
    response_language: str | None,
    report_depth: Literal["quick", "deep"],
    knowledge_payload: dict[str, object],
    comparison_briefs: list[dict[str, object]],
    evidence_briefs: list[dict[str, object]],
    allowed_evidence_ids: set[str],
    state_competitors: list[str],
    discovered_competitor_sources: dict[str, dict[str, object]] | None,
    self_product: str | None,
    target_category: str | None,
    category_aliases: list[str],
    excluded_categories: list[str],
    market_segments: list[str],
    scope_policy: str | None,
    preserve_llm_executive_summary: bool,
) -> dict[str, object]:
    sections_raw = report_content.get("sections")
    sections = (
        [dict(item) for item in sections_raw if isinstance(item, dict)]
        if isinstance(sections_raw, list)
        else []
    )
    coverage_raw = knowledge_payload.get("coverage")
    coverage = coverage_raw if isinstance(coverage_raw, dict) else {}
    normalized_coverage = _normalized_coverage_for_triage(coverage)
    ordered_competitors = _ordered_competitors_for_report(
        state_competitors=state_competitors,
        evidence_briefs=evidence_briefs,
        knowledge_payload=knowledge_payload,
    )
    profile_competitors = ordered_competitors
    if analysis_archetype == "landscape":
        profile_competitors = _admitted_key_players(
            ordered_competitors=ordered_competitors,
            discovered_competitor_sources=discovered_competitor_sources,
            evidence_briefs=evidence_briefs,
            allowed_evidence_ids=allowed_evidence_ids,
        )
    positioning_competitors = (
        profile_competitors if analysis_archetype == "landscape" else ordered_competitors
    )
    positioning_clusters = _positioning_clusters_from_coverage(
        competitors=positioning_competitors,
        coverage=coverage,
    )
    triage_result = triage_outline_sections(
        target_sections=target_sections,
        archetype=analysis_archetype,
        ctx=SectionEvidenceContext(
            coverage=normalized_coverage,
            competitors=tuple(ordered_competitors),
            core_competitors=tuple(
                profile_competitors if analysis_archetype == "landscape" else ordered_competitors
            ),
        ),
    )
    renderable_sections = list(triage_result.renderable)
    renderable_set = set(renderable_sections)

    if analysis_archetype == "landscape":
        landscape_facts = _landscape_structure_facts(
            ordered_competitors=ordered_competitors,
            discovered_competitor_sources=discovered_competitor_sources,
            knowledge_payload=knowledge_payload,
            evidence_briefs=evidence_briefs,
            allowed_evidence_ids=allowed_evidence_ids,
            target_category=target_category,
            category_aliases=category_aliases,
            excluded_categories=excluded_categories,
            market_segments=market_segments,
            scope_policy=scope_policy,
        )
        llm_section_ids = {
            section.get("section_id")
            for section in sections
            if isinstance(section.get("section_id"), str)
        }
        # The writer LLM owns every landscape section narrative. Deterministic code only
        # (1) overrides methodology_limits with exact bookkeeping and (2) backfills a
        # required registry section the LLM omitted with an honest-degrade stub — it never
        # overwrites LLM prose, which is what previously turned reports into key:value stubs.
        if "methodology_limits" in renderable_set:
            _upsert_section(
                sections=sections,
                section_payload=_landscape_methodology_section(
                    facts=landscape_facts,
                    evidence_briefs=evidence_briefs,
                    allowed_evidence_ids=allowed_evidence_ids,
                    response_language=response_language,
                ),
            )
        for section_id in renderable_sections:
            if section_id in {"executive_summary", "methodology_limits"}:
                continue
            if section_id in llm_section_ids:
                continue
            if get_section_spec(section_id) is None:
                continue
            _upsert_section(
                sections=sections,
                section_payload=_landscape_degrade_stub(
                    section_id=section_id,
                    facts=landscape_facts,
                    evidence_briefs=evidence_briefs,
                    allowed_evidence_ids=allowed_evidence_ids,
                    response_language=response_language,
                ),
            )
    elif "competitor_profiles" in renderable_set:
        _upsert_section(
            sections=sections,
            section_payload=_build_competitor_profiles_section(
                profile_competitors=profile_competitors,
                knowledge_payload=knowledge_payload,
                coverage=coverage,
                response_language=response_language,
                comparison_briefs=comparison_briefs,
                evidence_briefs=evidence_briefs,
                allowed_evidence_ids=allowed_evidence_ids,
            ),
        )
    if analysis_archetype != "landscape" and "comparison_matrix" in renderable_set:
        _upsert_section(
            sections=sections,
            section_payload=_build_comparison_matrix_section(
                competitors=(
                    profile_competitors if analysis_archetype == "landscape" else ordered_competitors
                ),
                knowledge_payload=knowledge_payload,
                coverage=coverage,
                response_language=response_language,
                evidence_briefs=evidence_briefs,
                allowed_evidence_ids=allowed_evidence_ids,
            ),
        )
    if analysis_archetype != "landscape" and "positioning_map" in renderable_set:
        _upsert_section(
            sections=sections,
            section_payload=_build_positioning_map_section(
                competitors=positioning_competitors,
                response_language=response_language,
                knowledge_payload=knowledge_payload,
                evidence_briefs=evidence_briefs,
                allowed_evidence_ids=allowed_evidence_ids,
                clusters=positioning_clusters,
            ),
        )
    if "self_positioning" in renderable_set:
        _upsert_section(
            sections=sections,
            section_payload=_build_self_positioning_section(
                self_product=self_product,
                competitors=ordered_competitors,
                response_language=response_language,
                allowed_evidence_ids=allowed_evidence_ids,
            ),
        )

    section_by_id = {
        section_id: section
        for section in sections
        for section_id in [section.get("section_id")]
        if isinstance(section_id, str)
    }
    ordered_sections: list[dict[str, object]] = []
    for section_id in renderable_sections:
        if section_id == "executive_summary":
            continue
        section = section_by_id.get(section_id)
        if isinstance(section, dict):
            ordered_sections.append(section)

    risk_callouts_raw = report_content.get("risk_callouts")
    risk_callouts: list[str] = []
    if isinstance(risk_callouts_raw, list):
        for item in risk_callouts_raw:
            if not isinstance(item, str):
                continue
            if item.startswith("uncovered_section:"):
                section_id = item.split(":", 1)[1].strip()
                if section_id and section_id not in renderable_set:
                    continue
            risk_callouts.append(item)
    if analysis_archetype == "landscape" and not profile_competitors:
        risk_callouts.append("landscape_core_profiles_empty")
    degraded_required_sections = list(triage_result.degraded_required)
    risk_callouts.extend(
        [f"report_degraded_required_section:{section_id}" for section_id in degraded_required_sections]
    )
    existing_summary_raw = report_content.get("executive_summary")
    existing_summary = (
        existing_summary_raw.strip() if isinstance(existing_summary_raw, str) else ""
    )
    # The LLM primary report already carries an evidence-grounded narrative summary;
    # only synthesize the deterministic positioning signal when the report fell back
    # or produced no summary, so we never downgrade a real narrative to a template.
    if preserve_llm_executive_summary and existing_summary:
        executive_summary = existing_summary
    elif analysis_archetype == "landscape":
        takeaways_section = section_by_id.get("executive_takeaways", {})
        takeaways_content = (
            takeaways_section.get("content_markdown")
            if isinstance(takeaways_section, dict)
            else None
        )
        executive_summary = (
            takeaways_content.strip()
            if isinstance(takeaways_content, str) and takeaways_content.strip()
            else (
                "报告已按商业市场报告结构生成，并锁定目标品类证据边界。"
                if response_language == "zh"
                else "The report is generated in a commercial market-report structure with target-category evidence boundaries."
            )
        )
    else:
        executive_summary = _positioning_signal_summary(
            clusters=positioning_clusters,
            response_language=response_language,
        )
    return {
        **report_content,
        "executive_summary": executive_summary,
        "sections": ordered_sections,
        "risk_callouts": _stable_unique(risk_callouts),
        "report_renderable_sections": renderable_sections,
        "report_omitted_sections": list(triage_result.omitted),
        "report_degraded_required_sections": degraded_required_sections,
    }


def _build_fallback_report(
    *,
    template_id: str | None,
    target_sections: list[str],
    evidence_ids: list[str],
    analyst_summary: str,
    insight_briefs: list[dict[str, object]],
    evidence_briefs: list[dict[str, object]],
    risk_flags: list[str],
) -> dict[str, object]:
    sections: list[dict[str, object]] = []
    uncovered_sections: list[str] = []
    for section_id in target_sections:
        related_insights = _select_insights_for_section(
            section_id=section_id,
            insight_briefs=insight_briefs,
        )
        if related_insights:
            insight_refs = [
                item["insight_id"]
                for item in related_insights
                if isinstance(item.get("insight_id"), str)
            ]
        else:
            insight_refs = []

        evidence_refs = _select_evidence_ids_for_section(
            section_id=section_id,
            evidence_briefs=evidence_briefs,
            evidence_ids=evidence_ids,
        )
        for insight in related_insights:
            evidence_ids_raw = insight.get("evidence_ids")
            if not isinstance(evidence_ids_raw, list):
                continue
            for evidence_id in evidence_ids_raw:
                if isinstance(evidence_id, str) and evidence_id in evidence_ids:
                    evidence_refs.append(evidence_id)
        evidence_refs = _stable_unique([item for item in evidence_refs if item in evidence_ids])[:4]

        if related_insights:
            insight_lines = [
                f"- {item['finding']} (confidence: {item.get('confidence', 'medium')})"
                for item in related_insights
                if isinstance(item.get("finding"), str)
            ]
        else:
            insight_lines = [
                f"- {item['quote_preview']} [{item['competitor_id']}]"
                for item in evidence_briefs
                if item.get("evidence_id") in evidence_refs and item.get("quote_preview")
            ][:3]
        if not insight_lines:
            uncovered_sections.append(section_id)
            insight_lines = [
                "- No grounded evidence matched this section; keep it open for follow-up research."
            ]
        content_markdown = "\n".join(insight_lines)
        sections.append(
            {
                "section_id": section_id,
                "title": section_id.replace("_", " ").title(),
                "content_markdown": content_markdown,
                "evidence_refs": evidence_refs,
                "insight_refs": _stable_unique(insight_refs),
            }
        )

    if not sections:
        section_id = target_sections[0] if target_sections else "general"
        uncovered_sections.append(section_id)
        sections.append(
            {
                "section_id": section_id,
                "title": section_id.replace("_", " ").title(),
                "content_markdown": (
                    "Fallback writer generated a minimal section because no valid target sections were resolved "
                    "from request/recommended inputs."
                ),
                "evidence_refs": [],
                "insight_refs": [],
            }
        )

    summary = analyst_summary.strip() if analyst_summary.strip() else "Fallback writer summary from analyst context."
    return {
        "template_id": template_id or "default",
        "title": "熊博士竞品研究战报",
        "executive_summary": summary,
        "sections": sections,
        "risk_callouts": _stable_unique(
            [
                *(risk_flags or ["writer_fallback_mode"]),
                *(f"uncovered_section:{section_id}" for section_id in uncovered_sections),
            ]
        ),
    }


def _render_report_markdown(
    report_content: dict[str, object],
    *,
    allowed_evidence_ids: set[str],
    response_language: str | None = None,
    evidence_briefs: list[dict[str, object]] | None = None,
) -> str:
    labels = (
        {
            "default_title": "熊博士报告",
            "executive_summary": "执行摘要",
            "section": "章节",
            "no_executive_summary": "暂无执行摘要。",
            "no_content": "暂无内容。",
            "evidence": "证据",
            "risk_callouts": "风险提示",
            "methodology": "数据来源与方法论",
            "methodology_generated_on": "生成日期(UTC)",
            "methodology_competitors": "覆盖竞品",
            "methodology_evidence_total": "证据总数",
            "methodology_authority_distribution": "来源等级分布",
            "methodology_source_type_distribution": "来源类型分布",
            "methodology_data_gaps": "数据缺口披露",
            "methodology_no_competitors": "未识别竞品",
            "methodology_gap_official_and_pricing": "官方来源和定价页均未覆盖（仅第三方资料）",
            "methodology_gap_official_only": "官方来源未覆盖（仅第三方资料）",
            "methodology_gap_pricing_only": "定价页未覆盖（定价可能未公开）",
            "methodology_no_data_gaps": "当前证据未发现明显来源缺口",
            "methodology_none": "无",
        }
        if response_language == "zh"
        else {
            "default_title": "熊博士 Report",
            "executive_summary": "Executive Summary",
            "section": "Section",
            "no_executive_summary": "No executive summary.",
            "no_content": "No content.",
            "evidence": "Evidence",
            "risk_callouts": "Risk Callouts",
            "methodology": "Data Sources and Methodology",
            "methodology_generated_on": "Generated on (UTC)",
            "methodology_competitors": "Covered competitors",
            "methodology_evidence_total": "Total evidence",
            "methodology_authority_distribution": "Source authority distribution",
            "methodology_source_type_distribution": "Source type distribution",
            "methodology_data_gaps": "Data gap disclosure",
            "methodology_no_competitors": "No identified competitors",
            "methodology_gap_official_and_pricing": (
                "official sources and pricing pages are both missing "
                "(third-party evidence only)"
            ),
            "methodology_gap_official_only": "official sources are missing (third-party evidence only)",
            "methodology_gap_pricing_only": "pricing pages are missing (pricing may not be public)",
            "methodology_no_data_gaps": "No obvious source gaps in current evidence",
            "methodology_none": "none",
        }
    )
    title_raw = report_content.get("title")
    title = title_raw.strip() if isinstance(title_raw, str) and title_raw.strip() else labels["default_title"]
    executive_summary_raw = report_content.get("executive_summary")
    executive_summary = (
        executive_summary_raw.strip()
        if isinstance(executive_summary_raw, str) and executive_summary_raw.strip()
        else labels["no_executive_summary"]
    )
    executive_summary = _sanitize_report_markdown_text(
        executive_summary,
        allowed_evidence_ids=allowed_evidence_ids,
    )
    markdown_lines = [
        f"# {title}",
        "",
        f"## {labels['executive_summary']}",
        executive_summary,
        "",
    ]
    sections_raw = report_content.get("sections")
    if isinstance(sections_raw, list):
        for section in sections_raw:
            if not isinstance(section, dict):
                continue
            section_title_raw = section.get("title")
            section_title = (
                section_title_raw.strip()
                if isinstance(section_title_raw, str) and section_title_raw.strip()
                else labels["section"]
            )
            if _is_duplicate_executive_summary_section(
                section_title=section_title,
                executive_summary_label=labels["executive_summary"],
            ):
                continue
            section_body_raw = section.get("content_markdown")
            section_body = (
                section_body_raw.strip()
                if isinstance(section_body_raw, str) and section_body_raw.strip()
                else labels["no_content"]
            )
            section_body = _sanitize_report_markdown_text(
                section_body,
                allowed_evidence_ids=allowed_evidence_ids,
            )
            markdown_lines.extend(
                [
                    f"## {section_title}",
                    section_body,
                ]
            )
            evidence_refs_raw = section.get("evidence_refs")
            if isinstance(evidence_refs_raw, list):
                evidence_refs = [
                    item
                    for item in evidence_refs_raw
                    if isinstance(item, str) and item in allowed_evidence_ids
                ]
            else:
                evidence_refs = []
            if evidence_refs:
                markdown_lines.append(
                    f"{labels['evidence']}: "
                    + ", ".join(f"[{evidence_id}]" for evidence_id in evidence_refs)
                )
            markdown_lines.append("")

    risk_callouts_raw = report_content.get("risk_callouts")
    internal_risk_prefixes = (
        "uncovered_section:",
        "numeric_claims_downgraded:",
        "report_degraded_required_section:",
    )
    if isinstance(risk_callouts_raw, list):
        risk_callouts = [
            item
            for item in risk_callouts_raw
            if isinstance(item, str) and not item.startswith(internal_risk_prefixes)
        ]
    else:
        risk_callouts = []
    if risk_callouts:
        markdown_lines.append(f"## {labels['risk_callouts']}")
        for item in risk_callouts:
            sanitized_item = _sanitize_report_markdown_text(
                item,
                allowed_evidence_ids=allowed_evidence_ids,
            )
            if sanitized_item:
                markdown_lines.append(f"- {sanitized_item}")
        markdown_lines.append("")

    methodology_lines = _build_methodology_section_lines(
        evidence_briefs=evidence_briefs or [],
        labels=labels,
    )
    markdown_lines.extend(
        [
            f"## {labels['methodology']}",
            *methodology_lines,
            "",
        ]
    )

    return "\n".join(markdown_lines).strip() + "\n"


@log_node("writer")
async def writer_node(state: AgentState) -> AgentState:
    run_id = state.get("run_id")
    if run_id is None:
        raise RuntimeError("AgentState.run_id is required for writer node.")

    session_factory = get_session_factory()
    request = Write.model_validate(state.get("pending_tool_args", {}))
    step_id = make_id("step_")
    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.STEP_START,
        step_id=step_id,
        payload={
            "agent_name": "writer",
            "template_id": request.template_id,
        },
    )
    report_id = f"report_{uuid4().hex[:12]}"
    evidence_rows, analyst_output, knowledge_payload = await _load_writer_inputs(
        session_factory=session_factory,
        run_id=run_id,
    )
    evidence_briefs = _build_evidence_briefs(evidence_rows)
    allowed_evidence_ids = {item["evidence_id"] for item in evidence_briefs}
    insight_briefs = _build_insight_briefs(
        analyst_output=analyst_output,
        allowed_evidence_ids=allowed_evidence_ids,
    )
    comparison_briefs = _build_comparison_briefs(
        analyst_output=analyst_output,
        allowed_evidence_ids=allowed_evidence_ids,
    )
    allowed_insight_ids = {
        item["insight_id"]
        for item in insight_briefs
        if isinstance(item.get("insight_id"), str)
    }
    intake_draft = coerce_intake_draft_or_default(state)
    execution_context = WriterExecutionContext.resolve(
        template_id=request.template_id,
        requested_sections=request.sections,
        analysis_archetype=intake_draft.analysis_archetype,
        analyst_output=analyst_output,
        allowed_evidence_ids=allowed_evidence_ids,
        allowed_insight_ids=allowed_insight_ids,
    )
    target_sections = execution_context.target_sections
    report_depth = _report_depth_from_state(state)
    analyst_summary = analyst_output.summary
    risk_flags = list(analyst_output.risk_flags)
    discovered_competitor_sources_raw = state.get("discovered_competitor_sources")
    discovered_competitor_sources = (
        discovered_competitor_sources_raw
        if isinstance(discovered_competitor_sources_raw, dict)
        else None
    )
    state_competitors = [
        item for item in state.get("competitors", []) if isinstance(item, str) and item.strip()
    ]
    landscape_facts: dict[str, object] | None = None
    if intake_draft.analysis_archetype == "landscape":
        landscape_facts = _landscape_structure_facts(
            ordered_competitors=_ordered_competitors_for_report(
                state_competitors=state_competitors,
                evidence_briefs=evidence_briefs,
                knowledge_payload=knowledge_payload,
            ),
            discovered_competitor_sources=discovered_competitor_sources,
            knowledge_payload=knowledge_payload,
            evidence_briefs=evidence_briefs,
            allowed_evidence_ids=allowed_evidence_ids,
            target_category=intake_draft.target_category,
            category_aliases=list(intake_draft.category_aliases),
            excluded_categories=list(intake_draft.excluded_categories),
            market_segments=list(intake_draft.market_segments),
            scope_policy=intake_draft.scope_policy,
        )
    fallback_user_prompt = build_writer_fallback_user_prompt(
        template_id=request.template_id,
        requested_sections=target_sections,
        evidence_ids=sorted(allowed_evidence_ids),
        analyst_summary=analyst_summary,
        user_query=str(state.get("user_query", "")),
        response_language=intake_draft.response_language,
        report_depth=report_depth,
        analyst_insights=insight_briefs,
        evidence_briefs=evidence_briefs,
    )
    harness_result = await complete_structured(
        model_slot="writer",
        system_prompt=WRITER_SYSTEM_PROMPT,
        user_prompt=build_writer_user_prompt(
            user_query=str(state.get("user_query", "")),
            template_id=request.template_id,
            target_sections=target_sections,
            requested_sections=request.sections or [],
            competitors=list(state.get("competitors", [])),
            evidence_briefs=evidence_briefs,
            allowed_evidence_ids=sorted(allowed_evidence_ids),
            analyst_summary=analyst_summary,
            analyst_insights=insight_briefs,
            analyst_comparisons=comparison_briefs,
            risk_flags=risk_flags,
            recommended_sections=analyst_output.recommended_sections,
            qa_reasons=request.qa_reasons,
            unsupported_numeric_claims=request.unsupported_numeric_claims,
            report_depth=report_depth,
            domain_hint=intake_draft.domain_hint,
            analysis_intent=intake_draft.analysis_intent,
            market_scope=intake_draft.market_scope,
            response_language=intake_draft.response_language,
            analysis_archetype=intake_draft.analysis_archetype,
            landscape_facts=landscape_facts,
        ),
        output_model=WriterReportOutput,
        parser=lambda content: WriterReportOutput.parse_llm_content(
            content,
            execution_context=execution_context,
        ),
        fallback_system_prompt=WRITER_SYSTEM_PROMPT,
        fallback_user_prompt=fallback_user_prompt,
        repair_user_prompt_builder=lambda errors: build_writer_repair_user_prompt(
            validation_errors=errors,
            template_id=request.template_id,
            target_sections=target_sections,
            evidence_ids=sorted(allowed_evidence_ids),
            analyst_summary=analyst_summary,
        ),
        log_event="writer.harness.finish",
    )
    llm_response = harness_result.llm_response
    writer_schema_error: str | None = None
    writer_mode: Literal["llm", "fallback"]
    if harness_result.value is not None:
        writer_mode = "llm"
        report_mode = (
            "primary"
            if harness_result.outcome in {"primary", "repaired"}
            else "llm_fallback"
        )
        report_content = harness_result.value.to_report_content()
        fallback_reason = llm_response.fallback_reason
    else:
        writer_mode = "fallback"
        report_mode = "deterministic_fallback"
        if llm_response.error is None:
            writer_schema_error = harness_result.schema_error or "writer_output_schema_invalid"
        fallback_reason = llm_response.error or writer_schema_error
        report_content = _build_fallback_report(
            template_id=request.template_id,
            target_sections=target_sections,
            evidence_ids=sorted(allowed_evidence_ids),
            analyst_summary=analyst_summary,
            insight_briefs=insight_briefs,
            evidence_briefs=evidence_briefs,
            risk_flags=risk_flags,
        )
    deepened_section_ids: list[str] = []
    report_content = _apply_structured_writer_sections(
        report_content=report_content,
        target_sections=target_sections,
        analysis_archetype=intake_draft.analysis_archetype,
        response_language=intake_draft.response_language,
        report_depth=report_depth,
        knowledge_payload=knowledge_payload,
        comparison_briefs=comparison_briefs,
        evidence_briefs=evidence_briefs,
        allowed_evidence_ids=allowed_evidence_ids,
        state_competitors=state_competitors,
        discovered_competitor_sources=discovered_competitor_sources,
        self_product=intake_draft.self_product,
        target_category=intake_draft.target_category,
        category_aliases=list(intake_draft.category_aliases),
        excluded_categories=list(intake_draft.excluded_categories),
        market_segments=list(intake_draft.market_segments),
        scope_policy=intake_draft.scope_policy,
        preserve_llm_executive_summary=writer_mode == "llm",
    )
    report_content, numeric_guardrail_sections = _apply_numeric_claim_guardrail(
        report_content=report_content,
        unsupported_numeric_claims=request.unsupported_numeric_claims,
        response_language=intake_draft.response_language,
    )
    log.info(
        "writer.report_mode",
        report_mode=report_mode,
        writer_mode=writer_mode,
        harness_outcome=harness_result.outcome,
        target_sections=target_sections,
        fallback_reason=fallback_reason,
        writer_schema_error=writer_schema_error,
        section_count=len(report_content.get("sections", []))
        if isinstance(report_content.get("sections"), list)
        else 0,
        degraded_required_sections=report_content.get("report_degraded_required_sections"),
        llm_fallback_used=llm_response.fallback_used,
        numeric_guardrail_sections=numeric_guardrail_sections,
    )
    markdown = _render_report_markdown(
        report_content,
        allowed_evidence_ids=allowed_evidence_ids,
        response_language=intake_draft.response_language,
        evidence_briefs=evidence_briefs,
    )
    llm_call_error = llm_response.error or writer_schema_error
    section_count = (
        len(report_content["sections"])
        if isinstance(report_content.get("sections"), list)
        else 0
    )
    degraded_required_sections_raw = report_content.get("report_degraded_required_sections")
    degraded_required_sections = (
        [item for item in degraded_required_sections_raw if isinstance(item, str)]
        if isinstance(degraded_required_sections_raw, list)
        else []
    )
    evidence_ref_count = 0
    sections_raw = report_content.get("sections")
    if isinstance(sections_raw, list):
        for section in sections_raw:
            if not isinstance(section, dict):
                continue
            section_refs = section.get("evidence_refs")
            if isinstance(section_refs, list):
                evidence_ref_count += len([item for item in section_refs if isinstance(item, str)])

    async with session_factory() as session:
        step = Step(
            step_id=step_id,
            run_id=run_id,
            agent_name="writer",
            status="running",
            retry_count=0,
            payload={
                **request.model_dump(),
                "report_depth": report_depth,
                "target_sections": target_sections,
                "renderable_sections": report_content.get("report_renderable_sections", target_sections),
                "writer_mode": writer_mode,
                "report_title": report_content.get("title"),
                "section_count": section_count,
                "deepened_sections": deepened_section_ids,
                "evidence_ref_count": evidence_ref_count,
                "fallback_reason": fallback_reason,
                "numeric_guardrail_sections": numeric_guardrail_sections,
                "llm_provider": llm_response.provider,
                "llm_prompt_preview": llm_response.prompt_preview,
                "llm_fallback_used": llm_response.fallback_used,
                "llm_fallback_reason": llm_response.fallback_reason,
                "report_degraded_required_sections": degraded_required_sections,
            },
        )
        session.add(step)
        await session.flush()
        session.add(
            build_llm_call_record(
                step_id=step_id,
                response=llm_response,
                error=llm_call_error,
            )
        )
        # One report per run: a QA-reject retry re-runs the writer, and without
        # this the old draft lingered as a second `reports` row (the "methodology
        # 重复入库" / duplicated-section artifact). The API reads latest-by-created_at
        # so users never saw it, but the stale row is dead weight — drop it so the
        # run owns exactly one report.
        await session.execute(delete(Report).where(Report.run_id == run_id))
        session.add(
            Report(
                report_id=report_id,
                run_id=run_id,
                status="completed",
                content_json=report_content,
                content_markdown=markdown,
            )
        )
        session.add(
            Artifact(
                artifact_id=make_id("artifact_"),
                step_id=step_id,
                kind="report_draft",
                uri=f"memory://report/{run_id}/{report_id}",
                sha256=None,
                size_bytes=None,
            )
        )
        step.status = "completed"
        step.finished_at = datetime.now(timezone.utc)
        await session.commit()
    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.STEP_FINISH,
        step_id=step_id,
        payload={
            "agent_name": "writer",
            "status": "completed",
            "writer_mode": writer_mode,
            "section_count": section_count,
        },
    )

    return {
        "report_draft_done": True,
        "pending_tool_args": {},
        "pending_review_target_step_id": step_id,
        "writer_report_fallback_mode": writer_mode == "fallback",
        "report_renderable_sections": report_content.get("report_renderable_sections", []),
        "report_degraded_required_sections": degraded_required_sections,
        "status": "running",
    }


async def _load_latest_report_for_run(
    *,
    session: AsyncSession,
    run_id: str,
) -> Report | None:
    return (
        await session.execute(
            select(Report).where(Report.run_id == run_id).order_by(Report.created_at.desc()).limit(1)
        )
    ).scalars().first()


@log_node("deepen")
async def deepen_node(state: AgentState) -> AgentState:
    run_id = state.get("run_id")
    if run_id is None:
        raise RuntimeError("AgentState.run_id is required for deepen node.")

    session_factory = get_session_factory()
    step_id = make_id("step_")
    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.STEP_START,
        step_id=step_id,
        payload={
            "agent_name": "deepen",
        },
    )

    if not settings.WRITER_SECTION_DEEPENING_ENABLED:
        async with session_factory() as session:
            session.add(
                Step(
                    step_id=step_id,
                    run_id=run_id,
                    agent_name="deepen",
                    status="completed",
                    retry_count=0,
                    payload={
                        "deepen_enabled": False,
                        "skipped_reason": "writer_section_deepening_disabled",
                        "deepened_sections": [],
                        "deepen_attempts": 0,
                    },
                    finished_at=datetime.now(timezone.utc),
                )
            )
            await session.commit()
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.STEP_FINISH,
            step_id=step_id,
            payload={
                "agent_name": "deepen",
                "status": "completed",
                "deepen_attempts": 0,
            },
        )
        return {"status": "running"}

    intake_draft = coerce_intake_draft_or_default(state)
    report_depth = _report_depth_from_state(state)
    evidence_rows, analyst_output, knowledge_payload = await _load_writer_inputs(
        session_factory=session_factory,
        run_id=run_id,
    )
    evidence_briefs = _build_evidence_briefs(evidence_rows)
    allowed_evidence_ids = {item["evidence_id"] for item in evidence_briefs}
    insight_briefs = _build_insight_briefs(
        analyst_output=analyst_output,
        allowed_evidence_ids=allowed_evidence_ids,
    )
    analyst_summary = analyst_output.summary

    discovered_competitor_sources_raw = state.get("discovered_competitor_sources")
    discovered_competitor_sources = (
        discovered_competitor_sources_raw
        if isinstance(discovered_competitor_sources_raw, dict)
        else None
    )
    state_competitors = [
        item for item in state.get("competitors", []) if isinstance(item, str) and item.strip()
    ]
    landscape_facts: dict[str, object] | None = None
    if intake_draft.analysis_archetype == "landscape":
        landscape_facts = _landscape_structure_facts(
            ordered_competitors=_ordered_competitors_for_report(
                state_competitors=state_competitors,
                evidence_briefs=evidence_briefs,
                knowledge_payload=knowledge_payload,
            ),
            discovered_competitor_sources=discovered_competitor_sources,
            knowledge_payload=knowledge_payload,
            evidence_briefs=evidence_briefs,
            allowed_evidence_ids=allowed_evidence_ids,
            target_category=intake_draft.target_category,
            category_aliases=list(intake_draft.category_aliases),
            excluded_categories=list(intake_draft.excluded_categories),
            market_segments=list(intake_draft.market_segments),
            scope_policy=intake_draft.scope_policy,
        )

    async with session_factory() as session:
        report = await _load_latest_report_for_run(session=session, run_id=run_id)
        if report is None:
            raise RuntimeError(f"No report found for run_id={run_id} before deepen.")
        report_content_raw = report.content_json
        if not isinstance(report_content_raw, dict):
            raise RuntimeError("Report content_json must be an object before deepen.")
        report_id = report.report_id
        report_content = report_content_raw
        session.add(
            Step(
                step_id=step_id,
                run_id=run_id,
                agent_name="deepen",
                status="running",
                retry_count=0,
                payload={
                    "deepen_enabled": True,
                    "source_report_id": report_id,
                    "report_depth": report_depth,
                    "analysis_archetype": intake_draft.analysis_archetype,
                    "deepened_sections": [],
                    "deepen_attempts": 0,
                    "skipped_reason": None,
                    "error": None,
                },
            )
        )
        await session.commit()

    async def _persist_section_response(_: str, response: LLMResponse) -> None:
        async with session_factory() as callback_session:
            callback_session.add(
                build_llm_call_record(
                    step_id=step_id,
                    response=response,
                )
            )
            await callback_session.commit()

    deepened_section_ids: list[str] = []
    deepen_attempts = 0
    skipped_reason: str | None = None
    deepen_error: str | None = None
    updated_report_content = report_content
    updated_markdown: str | None = None
    try:
        if not evidence_briefs:
            skipped_reason = "no_qualified_evidence"
        else:
            (
                updated_report_content,
                section_deepen_responses,
                deepened_section_ids,
            ) = await _deepen_report_sections(
                report_content=report_content,
                analysis_archetype=intake_draft.analysis_archetype,
                response_language=intake_draft.response_language,
                report_depth=report_depth,
                user_query=str(state.get("user_query", "")),
                evidence_briefs=evidence_briefs,
                analyst_summary=analyst_summary,
                insight_briefs=insight_briefs,
                landscape_facts=landscape_facts,
                persist_response=_persist_section_response,
            )
            deepen_attempts = len(section_deepen_responses)
            if deepened_section_ids:
                updated_markdown = _render_report_markdown(
                    updated_report_content,
                    allowed_evidence_ids=allowed_evidence_ids,
                    response_language=intake_draft.response_language,
                    evidence_briefs=evidence_briefs,
                )
            else:
                skipped_reason = "no_section_upgraded"
            log.info(
                "writer.section_deepen",
                deepened_sections=deepened_section_ids,
                deepen_attempts=deepen_attempts,
            )
    except (LLMError, ValueError, TypeError) as exc:
        skipped_reason = "deepen_failed_best_effort"
        deepen_error = str(exc)[:500]
        log.warning(
            "writer.section_deepen.best_effort_skip",
            run_id=run_id,
            reason=skipped_reason,
            error=deepen_error,
        )

    async with session_factory() as session:
        if updated_markdown is not None:
            report_row = await session.get(Report, report_id)
            if report_row is None:
                raise RuntimeError(f"report_id={report_id} not found while updating deepened content.")
            report_row.content_json = updated_report_content
            report_row.content_markdown = updated_markdown
        step_row = await session.get(Step, step_id)
        if step_row is None:
            raise RuntimeError(f"deepen step_id={step_id} missing during finalization.")
        payload_raw = step_row.payload
        payload = payload_raw if isinstance(payload_raw, dict) else {}
        step_row.payload = {
            **payload,
            "deepened_sections": deepened_section_ids,
            "deepen_attempts": deepen_attempts,
            "skipped_reason": skipped_reason,
            "error": deepen_error,
            "updated_report": updated_markdown is not None,
        }
        step_row.status = "completed"
        step_row.finished_at = datetime.now(timezone.utc)
        await session.commit()

    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.STEP_FINISH,
        step_id=step_id,
        payload={
            "agent_name": "deepen",
            "status": "completed",
            "deepened_sections": deepened_section_ids,
            "deepen_attempts": deepen_attempts,
            "updated_report": updated_markdown is not None,
        },
    )
    return {"status": "running"}
