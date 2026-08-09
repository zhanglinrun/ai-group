from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from service.llm.response import LLMResponse
from service.skill_curator.generators.tags import infer_candidate_tags
from service.skill_curator.models import SkillCuratorCandidate
from service.skill_curator.prompts import (
    SKILL_CURATOR_QA_RULE_SYSTEM_PROMPT,
    build_skill_curator_qa_rule_fallback_user_prompt,
    build_skill_curator_qa_rule_user_prompt,
)
from service.skill_curator.repair_prompts import build_skill_curator_repair_user_prompt
from service.skill_curator.structured_generate import complete_curator_structured
from utils.logger import get_logger

log = get_logger("service.skill_curator.generator.qa_rule")

_QA_RULE_TYPES = frozenset({"qa_rule"})


@dataclass(slots=True)
class CuratorGeneratorResult:
    candidates: list[SkillCuratorCandidate]
    llm_response: LLMResponse
    error: str | None


async def generate_qa_rule_candidates(
    *,
    run_id: str,
    domain_hint: str | None,
    qa_rejection_count: int,
    qa_reasons: Sequence[str],
    supervisor_decisions: Sequence[dict[str, object]],
    evidence_source_counts: dict[str, int],
    total_evidence_count: int,
) -> CuratorGeneratorResult:
    inferred_tags = infer_candidate_tags(
        domain_hint=domain_hint,
        evidence_source_counts=evidence_source_counts,
        qa_rejection_count=qa_rejection_count,
    )
    log.info(
        "skill_curator.qa_rule.start",
        run_id=run_id,
        domain_hint=domain_hint,
        inferred_tags=inferred_tags,
    )
    user_prompt = build_skill_curator_qa_rule_user_prompt(
        run_id=run_id,
        domain_hint=domain_hint,
        inferred_tags=inferred_tags,
        qa_rejection_count=qa_rejection_count,
        qa_reasons=qa_reasons,
        supervisor_decisions=supervisor_decisions,
        evidence_source_counts=evidence_source_counts,
        total_evidence_count=total_evidence_count,
    )
    fallback_user_prompt = build_skill_curator_qa_rule_fallback_user_prompt(
        run_id=run_id,
        domain_hint=domain_hint,
        inferred_tags=inferred_tags,
        qa_rejection_count=qa_rejection_count,
        evidence_source_counts=evidence_source_counts,
        total_evidence_count=total_evidence_count,
    )
    candidates, llm_response, error = await complete_curator_structured(
        allowed_types=_QA_RULE_TYPES,
        model_slot="qa",
        system_prompt=SKILL_CURATOR_QA_RULE_SYSTEM_PROMPT,
        user_prompt=user_prompt,
        fallback_system_prompt=SKILL_CURATOR_QA_RULE_SYSTEM_PROMPT,
        fallback_user_prompt=fallback_user_prompt,
        repair_user_prompt_builder=lambda errors: build_skill_curator_repair_user_prompt(
            validation_errors=errors,
            allowed_types=sorted(_QA_RULE_TYPES),
        ),
        log_event="skill_curator.qa_rule.harness.finish",
        inferred_tags=inferred_tags,
    )
    log.info(
        "skill_curator.qa_rule.finish",
        candidate_count=len(candidates) if error is None else 0,
        has_error=error is not None,
    )
    return CuratorGeneratorResult(candidates=candidates, llm_response=llm_response, error=error)
