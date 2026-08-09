from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from statistics import median

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from models.comparison import ComparisonCellRecord
from models.conclusion import ConclusionRecord
from models.evidence import EvidenceRecord
from models.knowledge import RunKnowledgeRecord
from models.llm_call import LLMCall
from models.report import Report
from models.run import Run
from models.skill_candidate import SkillCandidateRecord
from models.step import Step
from models.supervisor_decision import SupervisorDecisionRecord
from schemas.contracts import DERIVED_DIMENSIONS, validate_dimension
from service.locale import source_locale, target_country_from_scope


def _normalize_dimension(value: str) -> str | None:
    try:
        return validate_dimension(value)
    except ValueError:
        return None


@dataclass(frozen=True)
class RunMetricsSnapshot:
    run_id: str
    coverage_rate: float
    evidence_count_total: int
    evidence_count_by_competitor: dict[str, int]
    evidence_count_by_dimension: dict[str, int]
    comparison_dimensions: list[str]
    conclusion_sections: list[str]
    report_section_ids: list[str]
    dimension_coverage_rate: float
    evidence_dimension_coverage_rate: float
    report_char_count: int
    report_section_count: int
    report_depth: str
    report_section_coverage_rate: float
    knowledge_feature_count: int
    knowledge_pricing_count: int
    knowledge_persona_count: int
    knowledge_schema_coverage_rate: float
    source_type_distribution: dict[str, int]
    source_authority_distribution: dict[str, int]
    locale_match_rate: float
    locale_distribution: dict[str, int]
    desensitization_coverage: float
    qa_total_steps: int
    qa_rejected_steps: int
    qa_rejection_rate: float
    supervisor_iterations: int
    llm_token_total: int
    llm_call_count: int
    llm_latency_p50_ms: int | None
    llm_provider_error_count: int
    llm_retry_total: int
    manual_review_rate: float
    manual_review_is_proxy: bool
    run_wall_clock_seconds: int | None
    evidence_floor_count: int = 0
    non_floor_grounded_count: int = 0


def _extract_competitor_id(span: dict[str, object] | None) -> str | None:
    if not isinstance(span, dict):
        return None
    competitor_id = span.get("competitor_id")
    return competitor_id if isinstance(competitor_id, str) else None


def _extract_dimension(span: dict[str, object] | None) -> str | None:
    if not isinstance(span, dict):
        return None
    dimension = span.get("dimension")
    return _normalize_dimension(dimension) if isinstance(dimension, str) and dimension else None


def _extract_source_authority(span: dict[str, object] | None) -> str:
    if not isinstance(span, dict):
        return "unknown"
    source_authority = span.get("source_authority")
    if isinstance(source_authority, str) and source_authority:
        return source_authority
    return "unknown"


def _is_evidence_floor_row(span: dict[str, object] | None) -> bool:
    """A floor row is a placeholder persisted when a competitor had no real grounded evidence."""
    return isinstance(span, dict) and span.get("evidence_floor") is True


def _expected_dimensions_from_plan_tree(plan_tree: dict[str, object] | None) -> set[str]:
    if not isinstance(plan_tree, dict):
        return set()
    tasks_raw = plan_tree.get("tasks")
    if not isinstance(tasks_raw, list):
        return set()
    dimensions: set[str] = set()
    for task_raw in tasks_raw:
        if not isinstance(task_raw, dict):
            continue
        focus_raw = task_raw.get("focus_dimensions")
        if not isinstance(focus_raw, list):
            continue
        for item in focus_raw:
            if isinstance(item, str) and item:
                normalized = _normalize_dimension(item)
                if normalized is not None:
                    dimensions.add(normalized)
    return dimensions


def _research_dimensions_from_plan_tree(plan_tree: dict[str, object] | None) -> set[str]:
    """Dimensions that research tasks were asked to gather evidence for.

    Unlike `_expected_dimensions_from_plan_tree` this ignores analyze/write tasks,
    so analyst-synthesized (derived) dimensions don't pollute the evidence-coverage
    denominator (R9).
    """
    if not isinstance(plan_tree, dict):
        return set()
    tasks_raw = plan_tree.get("tasks")
    if not isinstance(tasks_raw, list):
        return set()
    dimensions: set[str] = set()
    for task_raw in tasks_raw:
        if not isinstance(task_raw, dict):
            continue
        if task_raw.get("stage") != "research":
            continue
        focus_raw = task_raw.get("focus_dimensions")
        if not isinstance(focus_raw, list):
            continue
        for item in focus_raw:
            if isinstance(item, str) and item:
                normalized = _normalize_dimension(item)
                if normalized is not None:
                    dimensions.add(normalized)
    return dimensions


def _add_focus_dimensions_from_mapping(
    *,
    dimensions: set[str],
    payload: dict[str, object],
) -> None:
    focus_raw = payload.get("focus_dimensions")
    if isinstance(focus_raw, list):
        for item in focus_raw:
            if isinstance(item, str) and item:
                normalized = _normalize_dimension(item)
                if normalized is not None:
                    dimensions.add(normalized)
    topics_raw = payload.get("topics")
    if not isinstance(topics_raw, list):
        return
    for topic_raw in topics_raw:
        if isinstance(topic_raw, dict):
            _add_focus_dimensions_from_mapping(dimensions=dimensions, payload=topic_raw)


def _expected_dimensions_from_decisions(
    decision_rows: list[SupervisorDecisionRecord],
) -> set[str]:
    dimensions: set[str] = set()
    for decision in decision_rows:
        tool_args = decision.tool_args
        if isinstance(tool_args, dict):
            _add_focus_dimensions_from_mapping(dimensions=dimensions, payload=tool_args)
    return dimensions


def _report_depth_from_run(run: Run) -> str:
    if isinstance(run.intake_draft, dict):
        depth_raw = run.intake_draft.get("report_depth")
        if depth_raw in {"debug", "quick", "deep"}:
            return str(depth_raw)
    return "quick"


def _target_country_from_run(run: Run) -> str | None:
    market_scope = (
        run.intake_draft.get("market_scope")
        if isinstance(run.intake_draft, dict)
        else None
    )
    return target_country_from_scope(market_scope=market_scope)


def _locale_matches(
    *,
    source_url: str | None,
    span: dict[str, object] | None,
    sanitized_text: str,
    target_country: str | None,
) -> bool:
    """A source is in-scope unless an explicit region target excludes it.

    No region target → every source counts (global breadth is correct). Region/language
    spread stays observable via locale_distribution; locale_match_rate only measures
    coverage of an explicitly requested region.
    """
    if target_country != "china":
        return True
    locale = source_locale(
        source_url=source_url,
        span=span,
        sanitized_text=sanitized_text,
    )
    return locale["country"] == "china" or locale["language"] == "zh"


def _latest_report(report_rows: list[Report]) -> Report | None:
    if not report_rows:
        return None
    return max(report_rows, key=lambda row: row.created_at)


def _latest_knowledge(knowledge_rows: list[RunKnowledgeRecord]) -> RunKnowledgeRecord | None:
    if not knowledge_rows:
        return None
    return max(knowledge_rows, key=lambda row: row.created_at)


def _coerce_competitor_id(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    return normalized if normalized else None


def _non_empty_string(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _non_empty_string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str) and item.strip()]


def _knowledge_substantial_lookup(
    knowledge_row: RunKnowledgeRecord | None,
) -> dict[str, set[str]]:
    lookup: dict[str, set[str]] = {}
    if knowledge_row is None:
        return lookup
    features = knowledge_row.features if isinstance(knowledge_row.features, list) else []
    pricings = knowledge_row.pricings if isinstance(knowledge_row.pricings, list) else []
    personas = knowledge_row.personas if isinstance(knowledge_row.personas, list) else []
    feedback = knowledge_row.feedback if isinstance(knowledge_row.feedback, list) else []

    evidence_owner_by_id: dict[str, str] = {}
    for item in [*features, *pricings, *feedback]:
        if not isinstance(item, dict):
            continue
        competitor_id = _coerce_competitor_id(item.get("competitor_id"))
        if competitor_id is None:
            continue
        evidence_ids = _non_empty_string_list(item.get("evidence_ids"))
        for evidence_id in evidence_ids:
            evidence_owner_by_id.setdefault(evidence_id, competitor_id)

    for item in features:
        if not isinstance(item, dict):
            continue
        competitor_id = _coerce_competitor_id(item.get("competitor_id"))
        if competitor_id is None:
            continue
        if _non_empty_string(item.get("name")) and _non_empty_string_list(item.get("evidence_ids")):
            lookup.setdefault(competitor_id, set()).add("feature")

    for item in pricings:
        if not isinstance(item, dict):
            continue
        competitor_id = _coerce_competitor_id(item.get("competitor_id"))
        if competitor_id is None:
            continue
        tiers_raw = item.get("tiers")
        tiers = tiers_raw if isinstance(tiers_raw, list) else []
        has_substantial_pricing = bool(_non_empty_string_list(item.get("evidence_ids"))) and (
            bool(tiers) or _non_empty_string(item.get("model"))
        )
        if has_substantial_pricing:
            lookup.setdefault(competitor_id, set()).add("pricing")

    for item in feedback:
        if not isinstance(item, dict):
            continue
        competitor_id = _coerce_competitor_id(item.get("competitor_id"))
        if competitor_id is None:
            continue
        has_substantial_feedback = (
            _non_empty_string(item.get("topic"))
            and _non_empty_string(item.get("summary"))
            and bool(_non_empty_string_list(item.get("evidence_ids")))
        )
        if has_substantial_feedback:
            lookup.setdefault(competitor_id, set()).add("feedback")

    for item in personas:
        if not isinstance(item, dict):
            continue
        evidence_ids = _non_empty_string_list(item.get("evidence_ids"))
        has_substantial_persona = (
            (_non_empty_string(item.get("name")) or _non_empty_string(item.get("role")))
            and (
                bool(_non_empty_string_list(item.get("pain_points")))
                or bool(_non_empty_string_list(item.get("jobs_to_be_done")))
                or bool(evidence_ids)
            )
        )
        if not has_substantial_persona:
            continue
        owner_competitors = {
            evidence_owner_by_id[evidence_id]
            for evidence_id in evidence_ids
            if evidence_id in evidence_owner_by_id
        }
        if not owner_competitors:
            owner_competitors = {"__global__"}
        for competitor_id in owner_competitors:
            lookup.setdefault(competitor_id, set()).add("persona")

    return lookup


def _dimension_has_substantial_record(
    *,
    competitor_id: str,
    dimension: str,
    substantial_lookup: dict[str, set[str]],
) -> bool:
    if dimension in substantial_lookup.get(competitor_id, set()):
        return True
    if dimension == "persona" and dimension in substantial_lookup.get("__global__", set()):
        return True
    return False


def _knowledge_schema_coverage_rate(
    coverage_payload: object,
    *,
    substantial_lookup: dict[str, set[str]],
) -> float:
    if not isinstance(coverage_payload, dict):
        return 0.0
    covered = 0.0
    total = 0
    for competitor_id, competitor_payload in coverage_payload.items():
        if not isinstance(competitor_id, str):
            continue
        if not isinstance(competitor_payload, dict):
            continue
        for dimension, status_raw in competitor_payload.items():
            if not isinstance(dimension, str):
                continue
            if not isinstance(status_raw, str):
                continue
            if status_raw in {"not_applicable_for_archetype", "not_requested"}:
                continue
            total += 1
            if not _dimension_has_substantial_record(
                competitor_id=competitor_id,
                dimension=dimension,
                substantial_lookup=substantial_lookup,
            ):
                continue
            if status_raw == "complete":
                covered += 1.0
            elif status_raw == "partial":
                covered += 0.5
    return _safe_rate(covered, total)


def _section_ids_from_report(report: Report | None) -> set[str]:
    if report is None or not isinstance(report.content_json, dict):
        return set()
    content_json = report.content_json
    sections_raw = report.content_json.get("sections")
    section_ids: set[str] = set()
    if isinstance(sections_raw, list):
        for section_raw in sections_raw:
            if not isinstance(section_raw, dict):
                continue
            section_id_raw = section_raw.get("section_id")
            if isinstance(section_id_raw, str) and section_id_raw:
                normalized = _normalize_dimension(section_id_raw)
                if normalized is not None:
                    section_ids.add(normalized)
    executive_summary_raw = content_json.get("executive_summary")
    if isinstance(executive_summary_raw, str) and executive_summary_raw.strip():
        section_ids.add("executive_summary")
    return section_ids


def _dimensions_from_comparisons(rows: list[ComparisonCellRecord]) -> set[str]:
    return {
        normalized
        for row in rows
        if isinstance(row.dimension, str) and row.dimension
        for normalized in [_normalize_dimension(row.dimension)]
        if normalized is not None
    }


def _sections_from_conclusions(rows: list[ConclusionRecord]) -> set[str]:
    return {
        normalized
        for row in rows
        if isinstance(row.section, str) and row.section
        for normalized in [_normalize_dimension(row.section)]
        if normalized is not None
    }


def _section_count_from_report(report: Report | None) -> int:
    # Must stay in lockstep with report_section_ids. The writer emits
    # executive_summary as a top-level field outside `sections`, so counting the
    # raw array alone undercounts the rendered report (ids=4 vs count=3).
    return len(_section_ids_from_report(report))


def _latest_writer_target_sections(step_rows: list[Step]) -> set[str]:
    writer_steps = [row for row in step_rows if row.agent_name == "writer"]
    if not writer_steps:
        return set()
    latest_writer = max(writer_steps, key=lambda row: row.created_at)
    if not isinstance(latest_writer.payload, dict):
        return set()
    sections_raw = latest_writer.payload.get("target_sections")
    if not isinstance(sections_raw, list):
        sections_raw = latest_writer.payload.get("sections")
    if not isinstance(sections_raw, list):
        return set()
    return {
        normalized
        for item in sections_raw
        if isinstance(item, str) and item
        for normalized in [_normalize_dimension(item)]
        if normalized is not None
    }


def _safe_rate(numerator: float, denominator: int) -> float:
    if denominator <= 0:
        return 0.0
    return float(numerator / denominator)


def _calc_latency_p50_ms(llm_rows: list[LLMCall]) -> int | None:
    latencies = [row.latency_ms for row in llm_rows if isinstance(row.latency_ms, int)]
    if not latencies:
        return None
    return int(median(latencies))


def build_run_metrics_snapshot(
    *,
    run: Run,
    evidence_rows: list[EvidenceRecord],
    step_rows: list[Step],
    llm_rows: list[LLMCall],
    decision_rows: list[SupervisorDecisionRecord],
    candidate_rows: list[SkillCandidateRecord],
    report_rows: list[Report] | None = None,
    comparison_rows: list[ComparisonCellRecord] | None = None,
    conclusion_rows: list[ConclusionRecord] | None = None,
    knowledge_rows: list[RunKnowledgeRecord] | None = None,
) -> RunMetricsSnapshot:
    run_competitors = [competitor for competitor in run.competitors if isinstance(competitor, str)]
    evidence_count_by_competitor: dict[str, int] = {competitor: 0 for competitor in run_competitors}
    expected_dimensions = _expected_dimensions_from_plan_tree(run.plan_tree)
    if not expected_dimensions:
        expected_dimensions = _expected_dimensions_from_decisions(decision_rows)
    evidence_count_by_dimension: dict[str, int] = {
        dimension: 0 for dimension in sorted(expected_dimensions)
    }
    source_type_distribution = dict(Counter(row.source_type for row in evidence_rows))
    source_authority_distribution = dict(
        Counter(_extract_source_authority(row.span) for row in evidence_rows)
    )
    target_country = _target_country_from_run(run)
    locale_distribution_counter: Counter[str] = Counter()
    locale_matched_count = 0

    for row in evidence_rows:
        row_locale = source_locale(
            source_url=row.source_url,
            span=row.span if isinstance(row.span, dict) else None,
            sanitized_text=row.sanitized_text,
        )
        locale_key = f'{row_locale["country"]}:{row_locale["language"]}'
        locale_distribution_counter[locale_key] += 1
        if _locale_matches(
            source_url=row.source_url,
            span=row.span if isinstance(row.span, dict) else None,
            sanitized_text=row.sanitized_text,
            target_country=target_country,
        ):
            locale_matched_count += 1
        competitor_id = _extract_competitor_id(row.span)
        if competitor_id is not None:
            evidence_count_by_competitor[competitor_id] = (
                evidence_count_by_competitor.get(competitor_id, 0) + 1
            )
        dimension = _extract_dimension(row.span)
        if dimension is not None:
            evidence_count_by_dimension[dimension] = (
                evidence_count_by_dimension.get(dimension, 0) + 1
            )

    covered_competitor_count = sum(
        1 for competitor in run_competitors if evidence_count_by_competitor.get(competitor, 0) > 0
    )
    coverage_rate = _safe_rate(covered_competitor_count, len(run_competitors))
    evidence_dimensions = {
        dimension
        for dimension, count in evidence_count_by_dimension.items()
        if count > 0
    }
    latest_report = _latest_report(report_rows or [])
    latest_knowledge = _latest_knowledge(knowledge_rows or [])
    report_section_ids = _section_ids_from_report(latest_report)
    comparison_dimensions = _dimensions_from_comparisons(comparison_rows or [])
    conclusion_sections = _sections_from_conclusions(conclusion_rows or [])
    downstream_dimensions = comparison_dimensions | conclusion_sections | report_section_ids
    dimension_denominator = expected_dimensions or downstream_dimensions or evidence_dimensions
    covered_dimension_count = (
        sum(1 for dimension in expected_dimensions if dimension in downstream_dimensions)
        if expected_dimensions
        else len(downstream_dimensions)
    )
    dimension_coverage_rate = _safe_rate(covered_dimension_count, len(dimension_denominator))

    # Evidence-grounded coverage: of the dimensions research tasks were asked to
    # gather, how many actually have on-dimension evidence. Unlike the downstream
    # `dimension_coverage_rate` (satisfied by a report section existing), this is
    # not inflated by derived dimensions that carry zero gathered evidence (R9).
    research_dimensions = _research_dimensions_from_plan_tree(run.plan_tree)
    if not research_dimensions:
        research_dimensions = {
            dimension
            for dimension in expected_dimensions
            if dimension not in DERIVED_DIMENSIONS
        }
    evidence_dimension_denominator = research_dimensions or evidence_dimensions
    covered_evidence_dimension_count = sum(
        1
        for dimension in evidence_dimension_denominator
        if evidence_count_by_dimension.get(dimension, 0) > 0
    )
    evidence_dimension_coverage_rate = _safe_rate(
        covered_evidence_dimension_count, len(evidence_dimension_denominator)
    )
    expected_report_sections = _latest_writer_target_sections(step_rows) or expected_dimensions
    report_section_coverage_rate = (
        _safe_rate(
            sum(1 for section_id in expected_report_sections if section_id in report_section_ids),
            len(expected_report_sections),
        )
        if expected_report_sections
        else 0.0
    )

    desensitized_count = sum(1 for row in evidence_rows if row.desensitized)
    desensitization_coverage = _safe_rate(desensitized_count, len(evidence_rows))
    knowledge_feature_count = (
        len(latest_knowledge.features)
        if latest_knowledge is not None and isinstance(latest_knowledge.features, list)
        else 0
    )
    knowledge_pricing_count = (
        len(latest_knowledge.pricings)
        if latest_knowledge is not None and isinstance(latest_knowledge.pricings, list)
        else 0
    )
    knowledge_persona_count = (
        len(latest_knowledge.personas)
        if latest_knowledge is not None and isinstance(latest_knowledge.personas, list)
        else 0
    )
    knowledge_schema_coverage_rate = _knowledge_schema_coverage_rate(
        latest_knowledge.coverage if latest_knowledge is not None else None,
        substantial_lookup=_knowledge_substantial_lookup(latest_knowledge),
    )

    qa_steps = [step for step in step_rows if step.agent_name == "qa"]
    qa_rejected_steps = [
        step
        for step in qa_steps
        if step.rejection_reason is not None or step.status == "rejected"
    ]
    qa_rejection_rate = _safe_rate(len(qa_rejected_steps), len(qa_steps))

    supervisor_iterations = max((row.iteration for row in decision_rows), default=0)
    llm_token_total = sum(
        (row.prompt_tokens or 0) + (row.completion_tokens or 0) for row in llm_rows
    )
    llm_provider_error_count = sum(1 for row in llm_rows if row.error is not None)
    llm_retry_total = sum(row.retry_count or 0 for row in llm_rows)

    supporting_candidates = []
    for candidate in candidate_rows:
        supporting_run_ids = [
            run_id for run_id in candidate.supporting_run_ids if isinstance(run_id, str)
        ]
        if run.run_id in supporting_run_ids:
            supporting_candidates.append(candidate)
    reviewed_candidates_count = sum(
        1 for candidate in supporting_candidates if candidate.reviewed_by is not None
    )
    manual_review_rate = _safe_rate(reviewed_candidates_count, len(supporting_candidates))

    run_wall_clock_seconds: int | None = None
    if run.finished_at is not None:
        delta = int((run.finished_at - run.started_at).total_seconds())
        run_wall_clock_seconds = max(delta, 0)

    evidence_floor_count = sum(
        1 for row in evidence_rows if _is_evidence_floor_row(row.span)
    )
    non_floor_grounded_count = len(evidence_rows) - evidence_floor_count

    return RunMetricsSnapshot(
        run_id=run.run_id,
        coverage_rate=coverage_rate,
        evidence_count_total=len(evidence_rows),
        evidence_count_by_competitor=evidence_count_by_competitor,
        evidence_count_by_dimension=evidence_count_by_dimension,
        comparison_dimensions=sorted(comparison_dimensions),
        conclusion_sections=sorted(conclusion_sections),
        report_section_ids=sorted(report_section_ids),
        dimension_coverage_rate=dimension_coverage_rate,
        evidence_dimension_coverage_rate=evidence_dimension_coverage_rate,
        report_char_count=len(latest_report.content_markdown.strip()) if latest_report is not None else 0,
        report_section_count=_section_count_from_report(latest_report),
        report_depth=_report_depth_from_run(run),
        report_section_coverage_rate=report_section_coverage_rate,
        knowledge_feature_count=knowledge_feature_count,
        knowledge_pricing_count=knowledge_pricing_count,
        knowledge_persona_count=knowledge_persona_count,
        knowledge_schema_coverage_rate=knowledge_schema_coverage_rate,
        source_type_distribution=source_type_distribution,
        source_authority_distribution=source_authority_distribution,
        locale_match_rate=_safe_rate(locale_matched_count, len(evidence_rows)),
        locale_distribution=dict(locale_distribution_counter),
        desensitization_coverage=desensitization_coverage,
        qa_total_steps=len(qa_steps),
        qa_rejected_steps=len(qa_rejected_steps),
        qa_rejection_rate=qa_rejection_rate,
        supervisor_iterations=supervisor_iterations,
        llm_token_total=llm_token_total,
        llm_call_count=len(llm_rows),
        llm_latency_p50_ms=_calc_latency_p50_ms(llm_rows),
        llm_provider_error_count=llm_provider_error_count,
        llm_retry_total=llm_retry_total,
        manual_review_rate=manual_review_rate,
        manual_review_is_proxy=True,
        run_wall_clock_seconds=run_wall_clock_seconds,
        evidence_floor_count=evidence_floor_count,
        non_floor_grounded_count=non_floor_grounded_count,
    )


async def load_run_metrics_snapshot(
    *,
    session: AsyncSession,
    run_id: str,
) -> RunMetricsSnapshot:
    run = await session.get(Run, run_id)
    if run is None:
        raise RuntimeError(f"run_id={run_id} does not exist")

    evidence_rows = (
        await session.execute(
            select(EvidenceRecord)
            .where(EvidenceRecord.run_id == run_id)
            .order_by(EvidenceRecord.created_at.asc())
        )
    ).scalars().all()
    step_rows = (
        await session.execute(
            select(Step).where(Step.run_id == run_id).order_by(Step.created_at.asc())
        )
    ).scalars().all()
    llm_rows = (
        await session.execute(
            select(LLMCall)
            .join(Step, LLMCall.step_id == Step.step_id)
            .where(Step.run_id == run_id)
            .order_by(LLMCall.created_at.asc())
        )
    ).scalars().all()
    decision_rows = (
        await session.execute(
            select(SupervisorDecisionRecord)
            .where(SupervisorDecisionRecord.run_id == run_id)
            .order_by(SupervisorDecisionRecord.created_at.asc())
        )
    ).scalars().all()
    report_rows = (
        await session.execute(
            select(Report)
            .where(Report.run_id == run_id)
            .order_by(Report.created_at.asc())
        )
    ).scalars().all()
    comparison_rows = (
        await session.execute(
            select(ComparisonCellRecord)
            .where(ComparisonCellRecord.run_id == run_id)
            .order_by(
                ComparisonCellRecord.dimension.asc(),
                ComparisonCellRecord.competitor_id.asc(),
                ComparisonCellRecord.created_at.asc(),
            )
        )
    ).scalars().all()
    conclusion_rows = (
        await session.execute(
            select(ConclusionRecord)
            .where(ConclusionRecord.run_id == run_id)
            .order_by(ConclusionRecord.created_at.asc(), ConclusionRecord.conclusion_id.asc())
        )
    ).scalars().all()
    knowledge_rows = (
        await session.execute(
            select(RunKnowledgeRecord)
            .where(RunKnowledgeRecord.run_id == run_id)
            .order_by(RunKnowledgeRecord.created_at.asc(), RunKnowledgeRecord.sequence_id.asc())
        )
    ).scalars().all()
    candidate_rows = (await session.execute(select(SkillCandidateRecord))).scalars().all()
    candidate_rows = [
        row
        for row in candidate_rows
        if run_id in (row.supporting_run_ids if isinstance(row.supporting_run_ids, list) else [])
    ]

    return build_run_metrics_snapshot(
        run=run,
        evidence_rows=list(evidence_rows),
        step_rows=list(step_rows),
        llm_rows=list(llm_rows),
        decision_rows=list(decision_rows),
        candidate_rows=list(candidate_rows),
        report_rows=list(report_rows),
        comparison_rows=list(comparison_rows),
        conclusion_rows=list(conclusion_rows),
        knowledge_rows=list(knowledge_rows),
    )
