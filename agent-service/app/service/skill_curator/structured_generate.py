from __future__ import annotations

from collections.abc import Callable

from schemas.agent_outputs import SkillCuratorHarnessOutput
from service.llm.harness import complete_structured
from service.llm.response import LLMResponse
from service.skill_curator.models import SkillCuratorCandidate
from utils.logger import get_logger

log = get_logger("service.skill_curator.structured_generate")


async def complete_curator_structured(
    *,
    allowed_types: frozenset[str],
    model_slot: str,
    system_prompt: str,
    user_prompt: str,
    fallback_system_prompt: str,
    fallback_user_prompt: str,
    repair_user_prompt_builder: Callable[[list[str]], str],
    log_event: str,
    inferred_tags: list[str],
) -> tuple[list[SkillCuratorCandidate], LLMResponse, str | None]:
    harness_result = await complete_structured(
        model_slot=model_slot,
        system_prompt=system_prompt,
        user_prompt=user_prompt,
        output_model=SkillCuratorHarnessOutput,
        parser=lambda content: SkillCuratorHarnessOutput.parse_llm_content(
            content,
            allowed_types=allowed_types,
        ),
        fallback_system_prompt=fallback_system_prompt,
        fallback_user_prompt=fallback_user_prompt,
        repair_user_prompt_builder=repair_user_prompt_builder,
        log_event=log_event,
    )
    llm_response = harness_result.llm_response
    if llm_response.error is not None:
        return [], llm_response, llm_response.error
    if harness_result.value is None:
        schema_error = harness_result.schema_error or "skill_curator_schema_invalid"
        return [], llm_response, schema_error

    candidates = [
        SkillCuratorCandidate.model_validate(
            {
                **item.model_dump(),
                "tags": inferred_tags,
            }
        )
        for item in harness_result.value.candidates
        if item.candidate_type in allowed_types
    ]
    return candidates, llm_response, None
