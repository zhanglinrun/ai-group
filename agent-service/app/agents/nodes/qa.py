from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from agents.state import AgentState
from core.tiers import resolve_tier_profile
from db.engine import get_session_factory
from models.report import Report
from models.step import Step
from schemas.ids import make_id
from schemas.qa import Approval, Rejection
from service.event_bus import RunEventType, emit_run_event
from service.llm.records import build_llm_call_record
from service.qa.engine import evaluate_report
from utils.log_node import log_node
from utils.logger import get_logger

log = get_logger("agents.qa")


async def _load_review_targets(
    *,
    session_factory: async_sessionmaker[AsyncSession],
    run_id: str,
    pending_review_target_step_id: str | None,
) -> tuple[Step, Report]:
    async with session_factory() as session:
        if pending_review_target_step_id is not None:
            writer_step = await session.get(Step, pending_review_target_step_id)
            if (
                writer_step is not None
                and writer_step.run_id == run_id
                and writer_step.agent_name == "writer"
            ):
                pass
            else:
                writer_step = None
        else:
            writer_step = None

        if writer_step is None:
            writer_step = (
                await session.execute(
                    select(Step)
                    .where(Step.run_id == run_id, Step.agent_name == "writer")
                    .order_by(Step.created_at.desc())
                    .limit(1)
                )
            ).scalars().first()
        if writer_step is None:
            raise RuntimeError(f"No writer step found for run_id={run_id} before QA review.")

        report = (
            await session.execute(
                select(Report)
                .where(Report.run_id == run_id)
                .order_by(Report.created_at.desc())
                .limit(1)
            )
        ).scalars().first()
        if report is None:
            raise RuntimeError(f"No report found for run_id={run_id} before QA review.")

        return writer_step, report


def _make_qa_payload(
    *,
    target_step_id: str,
    report_id: str,
    review_result: Approval | Rejection,
) -> dict[str, object]:
    if isinstance(review_result, Approval):
        return {
            "target_step_id": target_step_id,
            "report_id": report_id,
            "qa_outcome": "approved",
            "qa_reject_to": None,
            "passed_rule_ids": review_result.passed_rule_ids,
            "failed_rule_count": 0,
            "failed_rule_ids": [],
            "warning_rule_ids": review_result.warning_rule_ids,
        }
    failed_rule_ids = list(review_result.failed_rule_ids)
    return {
        "target_step_id": target_step_id,
        "report_id": report_id,
        "qa_outcome": "rejected",
        "qa_reject_to": review_result.reject_to,
        "failed_rule_ids": failed_rule_ids,
        "failed_rule_count": len(failed_rule_ids),
        "warning_rule_ids": review_result.warning_rule_ids,
        "reject_to": review_result.reject_to,
    }


def _warning_category(rule_id: str) -> str:
    if "official_source" in rule_id:
        return "missing_official_source"
    if "locale" in rule_id:
        return "locale_risk"
    if "knowledge" in rule_id:
        return "schema_coverage_risk"
    if "semantic" in rule_id:
        return "semantic_quality_risk"
    return "qa_warning"


def _qa_warning_items(
    *,
    qa_payload: dict[str, object],
    semantic_metadata: dict[str, object],
) -> list[dict[str, object]]:
    warning_rule_ids_raw = qa_payload.get("warning_rule_ids", [])
    warning_rule_ids = (
        [item for item in warning_rule_ids_raw if isinstance(item, str)]
        if isinstance(warning_rule_ids_raw, list)
        else []
    )
    warnings = [
        {
            "category": _warning_category(rule_id),
            "rule_id": rule_id,
            "message": f"QA warning: {rule_id}",
        }
        for rule_id in warning_rule_ids
    ]
    unsupported_numeric_claims = semantic_metadata.get("qa_unsupported_numeric_claims", [])
    if isinstance(unsupported_numeric_claims, list) and unsupported_numeric_claims:
        warnings.append(
            {
                "category": "numeric_claim_unsupported",
                "rule_id": "qa_unsupported_numeric_claims",
                "message": "报告包含未被引用证据支持的数字结论。",
                "count": len([item for item in unsupported_numeric_claims if isinstance(item, dict)]),
            }
        )
    return warnings


def _to_qa_reasons(rejection: Rejection) -> list[str]:
    # Prefer actionable rewrite hints (curated Chinese instruction, or the
    # rule's own message as fallback) so the writer's next attempt is targeted.
    hints = [hint for hint in rejection.remediation_hints.values() if hint]
    if hints:
        findings = [item for item in rejection.semantic_findings if item]
        return list(dict.fromkeys([*hints, *findings]))
    reasons = [item for item in rejection.semantic_findings if item]
    if reasons:
        return reasons
    return list(rejection.failed_rule_ids)


def _report_has_writer_fallback_mode(content_json: dict[str, object]) -> bool:
    risk_callouts_raw = content_json.get("risk_callouts")
    if not isinstance(risk_callouts_raw, list):
        return False
    return "writer_fallback_mode" in risk_callouts_raw


def _report_degraded_required_sections(content_json: dict[str, object]) -> list[str]:
    degraded_raw = content_json.get("report_degraded_required_sections")
    if not isinstance(degraded_raw, list):
        return []
    degraded: list[str] = []
    for item in degraded_raw:
        if not isinstance(item, str):
            continue
        section_id = item.strip()
        if section_id and section_id not in degraded:
            degraded.append(section_id)
    return degraded


def _state_report_depth(state: AgentState) -> str | None:
    intake_draft_raw = state.get("intake_draft")
    if isinstance(intake_draft_raw, dict):
        depth_raw = intake_draft_raw.get("report_depth")
        if isinstance(depth_raw, str):
            return depth_raw
    depth_raw = state.get("report_depth")
    if isinstance(depth_raw, str):
        return depth_raw
    return None


def _numeric_claim_key(item: dict[str, object]) -> tuple[str, str]:
    claim = item.get("claim")
    section_id = item.get("section_id")
    return (
        " ".join(claim.split()).casefold() if isinstance(claim, str) else "",
        section_id.strip().casefold() if isinstance(section_id, str) else "",
    )


def _merge_numeric_claim_blocklist(
    *,
    prior_items: object,
    new_items: list[dict[str, object]],
) -> list[dict[str, object]]:
    merged: list[dict[str, object]] = []
    seen: set[tuple[str, str]] = set()
    candidate_items = [
        *([item for item in prior_items if isinstance(item, dict)] if isinstance(prior_items, list) else []),
        *new_items,
    ]
    for item in candidate_items:
        key = _numeric_claim_key(item)
        if key == ("", "") or key in seen:
            continue
        seen.add(key)
        merged.append(item)
    return merged


@log_node("qa")
async def qa_node(state: AgentState) -> AgentState:
    run_id = state.get("run_id")
    if run_id is None:
        raise RuntimeError("AgentState.run_id is required for qa node.")

    session_factory = get_session_factory()
    pending_review_target_step_id = state.get("pending_review_target_step_id")
    qa_rejection_count = int(state.get("qa_rejection_count", 0))
    qa_reject_budget = resolve_tier_profile(_state_report_depth(state)).qa_reject_budget
    qa_step_id = make_id("step_")

    writer_step, report = await _load_review_targets(
        session_factory=session_factory,
        run_id=run_id,
        pending_review_target_step_id=pending_review_target_step_id,
    )
    review_result, semantic_llm_response, semantic_metadata = await evaluate_report(
        run_id=run_id,
        report_id=report.report_id,
        target_step_id=writer_step.step_id,
        reviewer_step_id=qa_step_id,
        session_factory=session_factory,
        qa_rejection_count=qa_rejection_count,
    )
    promoted_qa_rule_ids_raw = semantic_metadata.get("promoted_qa_rule_ids", [])
    promoted_qa_rule_ids = (
        [item for item in promoted_qa_rule_ids_raw if isinstance(item, str)]
        if isinstance(promoted_qa_rule_ids_raw, list)
        else []
    )
    enforced_count_raw = semantic_metadata.get("promoted_qa_enforced_count", 0)
    parse_error_count_raw = semantic_metadata.get("promoted_qa_parse_error_count", 0)
    blocked_rule_ids_raw = semantic_metadata.get("promoted_qa_blocked_rule_ids", [])
    enforced_count = enforced_count_raw if isinstance(enforced_count_raw, int) else 0
    parse_error_count = parse_error_count_raw if isinstance(parse_error_count_raw, int) else 0
    blocked_rule_ids = (
        [item for item in blocked_rule_ids_raw if isinstance(item, str)]
        if isinstance(blocked_rule_ids_raw, list)
        else []
    )
    degraded_required_sections = _report_degraded_required_sections(report.content_json)
    data_degraded_by_writer = bool(degraded_required_sections)
    writer_fallback_mode = _report_has_writer_fallback_mode(report.content_json)
    approval_blocked_for_fallback = (
        isinstance(review_result, Approval) and writer_fallback_mode
    )
    if data_degraded_by_writer:
        updated_rejection_count = qa_rejection_count
        is_force_degraded = True
    elif approval_blocked_for_fallback:
        updated_rejection_count = qa_rejection_count + 1
        is_force_degraded = updated_rejection_count > qa_reject_budget
    else:
        updated_rejection_count = (
            qa_rejection_count + 1 if isinstance(review_result, Rejection) else qa_rejection_count
        )
        is_force_degraded = (
            isinstance(review_result, Rejection)
            and updated_rejection_count > qa_reject_budget
        )
    qa_payload = _make_qa_payload(
        target_step_id=writer_step.step_id,
        report_id=report.report_id,
        review_result=review_result,
    )
    if data_degraded_by_writer:
        qa_payload["qa_outcome"] = "force_degraded"
        qa_payload["qa_reject_to"] = "supervisor"
        qa_payload["reject_to"] = "supervisor"
        qa_payload["failed_rule_ids"] = ["rule_writer_data_degraded_required_sections"]
        qa_payload["failed_rule_count"] = 1
        qa_payload["qa_degrade_reason"] = "report_degraded_required_sections"
        qa_payload["qa_degraded_required_sections"] = degraded_required_sections
    elif approval_blocked_for_fallback:
        qa_payload["qa_outcome"] = "force_degraded" if is_force_degraded else "rejected"
        qa_payload["qa_reject_to"] = "supervisor" if is_force_degraded else "writer"
        qa_payload["reject_to"] = "supervisor" if is_force_degraded else "writer"
        qa_payload["failed_rule_ids"] = ["rule_writer_no_fallback_mode"]
        qa_payload["failed_rule_count"] = 1
    elif isinstance(review_result, Rejection) and is_force_degraded:
        qa_payload["qa_outcome"] = "force_degraded"
        qa_payload["qa_reject_to"] = "supervisor"
        qa_payload["reject_to"] = "supervisor"

    qa_warnings = _qa_warning_items(
        qa_payload=qa_payload,
        semantic_metadata=semantic_metadata,
    )
    if qa_warnings:
        qa_payload["qa_warnings"] = qa_warnings

    async with session_factory() as session:
        report_row = await session.get(Report, report.report_id)
        if report_row is not None and qa_warnings:
            report_row.content_json = {
                **report_row.content_json,
                "qa_warnings": qa_warnings,
            }
        step = Step(
            step_id=qa_step_id,
            run_id=run_id,
            agent_name="qa",
            status="running",
            retry_count=0,
            payload=qa_payload
            | semantic_metadata
            | {"promoted_qa_rule_ids": promoted_qa_rule_ids},
            rejection_reason=(
                review_result.model_dump()
                if isinstance(review_result, Rejection)
                else None
            ),
        )
        session.add(step)
        await session.flush()
        if semantic_llm_response is not None:
            session.add(build_llm_call_record(step_id=qa_step_id, response=semantic_llm_response))
        step.status = "completed"
        step.finished_at = datetime.now(timezone.utc)
        await session.commit()
    if (
        isinstance(review_result, Approval)
        and not approval_blocked_for_fallback
        and not data_degraded_by_writer
    ):
        event_qa_outcome = "approved"
        event_reject_to: str | None = None
    else:
        event_qa_outcome = "force_degraded" if is_force_degraded else "rejected"
        event_reject_to = (
            "supervisor"
            if is_force_degraded
            else (
                "writer"
                if approval_blocked_for_fallback
                else review_result.reject_to
            )
        )

    if (
        isinstance(review_result, Approval)
        and not approval_blocked_for_fallback
        and not data_degraded_by_writer
    ):
        log.info(
            "qa.promoted_rules",
            count=len(promoted_qa_rule_ids),
            rule_id_list=promoted_qa_rule_ids,
            enforced_count=enforced_count,
            parse_error_count=parse_error_count,
            blocked_rule_ids=blocked_rule_ids,
        )
        log.info(
            "qa.outcome",
            outcome="approved",
            retry_count=qa_rejection_count,
            target_step_id=writer_step.step_id,
            writer_fallback_mode=writer_fallback_mode,
        )
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.QA_OUTCOME,
            step_id=qa_step_id,
            payload={
                "qa_outcome": event_qa_outcome,
                "reject_to": event_reject_to,
                "target_step_id": writer_step.step_id,
                "warning_rule_ids": qa_payload.get("warning_rule_ids", []),
                "qa_warnings": qa_warnings,
            },
        )
        return {
            "last_completed_node": "writer",
            "pending_review_target_step_id": None,
            "qa_outcome": "approved",
            "qa_reject_to": None,
            "qa_rejection_count": qa_rejection_count,
            "qa_reasons": [],
            "qa_unsupported_numeric_claims": [],
            "qa_degrade_reason": None,
            "qa_degraded_required_sections": [],
            "status": "running",
        }

    log.info(
        "qa.promoted_rules",
        count=len(promoted_qa_rule_ids),
        rule_id_list=promoted_qa_rule_ids,
        enforced_count=enforced_count,
        parse_error_count=parse_error_count,
        blocked_rule_ids=blocked_rule_ids,
    )
    failed_rule_ids = (
        ["rule_writer_data_degraded_required_sections"]
        if data_degraded_by_writer
        else (
            ["rule_writer_no_fallback_mode"]
            if approval_blocked_for_fallback
            else review_result.failed_rule_ids
        )
    )
    log.info(
        "qa.outcome",
        outcome="force_degraded" if is_force_degraded else "rejected",
        reject_to=event_reject_to,
        failed_rule_ids=failed_rule_ids,
        retry_count=updated_rejection_count,
        target_step_id=writer_step.step_id,
        writer_fallback_mode=writer_fallback_mode,
    )
    await emit_run_event(
        run_id=run_id,
        event_type=RunEventType.QA_OUTCOME,
        step_id=qa_step_id,
        payload={
            "qa_outcome": event_qa_outcome,
            "reject_to": event_reject_to,
            "target_step_id": writer_step.step_id,
            "failed_rule_count": len(failed_rule_ids),
            "warning_rule_ids": qa_payload.get("warning_rule_ids", []),
            "qa_warnings": qa_warnings,
            "qa_degraded_required_sections": degraded_required_sections,
        },
    )
    qa_reasons = (
        (
            [
                "Required intent sections have insufficient grounded evidence; finalize in degraded mode."
            ]
            + [f"degraded_required:{item}" for item in degraded_required_sections]
        )
        if data_degraded_by_writer
        else (
            ["Report must not be generated in deterministic writer fallback mode."]
            if approval_blocked_for_fallback
            else _to_qa_reasons(review_result)
        )
    )
    unsupported_numeric_claims_raw = semantic_metadata.get("qa_unsupported_numeric_claims", [])
    unsupported_numeric_claims = (
        [item for item in unsupported_numeric_claims_raw if isinstance(item, dict)]
        if isinstance(unsupported_numeric_claims_raw, list)
        else []
    )
    numeric_claim_blocklist = _merge_numeric_claim_blocklist(
        prior_items=state.get("qa_numeric_claim_blocklist", []),
        new_items=unsupported_numeric_claims,
    )
    return {
        "last_completed_node": "writer",
        "pending_review_target_step_id": None,
        "qa_outcome": "force_degraded" if is_force_degraded else "rejected",
        "qa_reject_to": event_reject_to,
        "qa_rejection_count": updated_rejection_count,
        "qa_reasons": qa_reasons,
        "qa_unsupported_numeric_claims": unsupported_numeric_claims,
        "qa_numeric_claim_blocklist": numeric_claim_blocklist,
        "qa_degrade_reason": (
            "report_degraded_required_sections" if data_degraded_by_writer else None
        ),
        "qa_degraded_required_sections": degraded_required_sections,
        "status": "running",
    }
