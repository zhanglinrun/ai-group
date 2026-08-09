from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ValidationError

from service.llm.client import get_llm_client
from service.llm.response import LLMResponse
from utils.logger import get_logger

log = get_logger("service.llm.harness")

T = TypeVar("T", bound=BaseModel)
HarnessOutcome = Literal["primary", "repaired", "fallback_prompt", "failed"]
HarnessPhase = Literal["primary", "repair", "fallback_prompt"]
_REPAIR_PREVIOUS_OUTPUT_PREVIEW_MAX_CHARS = 2000


def format_validation_errors(error: ValidationError) -> list[str]:
    formatted: list[str] = []
    for item in error.errors():
        location = ".".join(str(part) for part in item.get("loc", ()))
        message = str(item.get("msg", "invalid value"))
        formatted.append(f"{location}: {message}" if location else message)
    return formatted


@dataclass(frozen=True, slots=True)
class HarnessAttempt:
    attempt: int
    phase: HarnessPhase
    llm_error: str | None
    validation_errors: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class StructuredLLMResult(Generic[T]):
    value: T | None
    outcome: HarnessOutcome
    llm_response: LLMResponse
    validation_errors: tuple[str, ...]
    attempts: tuple[HarnessAttempt, ...]
    schema_error: str | None = None


def _validate_parsed_output(
    *,
    output_model: type[T],
    content: dict[str, object],
    parser: Callable[[dict[str, object]], T],
) -> tuple[T | None, tuple[str, ...]]:
    try:
        return parser(content), ()
    except ValidationError as exc:
        return None, tuple(format_validation_errors(exc))
    except ValueError as exc:
        return None, (str(exc),)


def _serialize_repair_previous_output(content: dict[str, object]) -> str:
    try:
        serialized = json.dumps(content, sort_keys=True)
    except (TypeError, ValueError):
        serialized = str(content)
    if len(serialized) <= _REPAIR_PREVIOUS_OUTPUT_PREVIEW_MAX_CHARS:
        return serialized
    return f"{serialized[:_REPAIR_PREVIOUS_OUTPUT_PREVIEW_MAX_CHARS]}..."


def _build_repair_prompt(
    *,
    validation_errors: tuple[str, ...],
    previous_invalid_output: dict[str, object],
    repair_user_prompt_builder: Callable[[list[str]], str],
) -> str:
    previous_output_preview = _serialize_repair_previous_output(previous_invalid_output)
    builder_prompt = repair_user_prompt_builder(list(validation_errors))
    return (
        "Previous invalid JSON output (must be corrected):\n"
        f"{previous_output_preview}\n\n"
        f"{builder_prompt}"
    )


async def complete_structured(
    *,
    model_slot: str,
    system_prompt: str,
    user_prompt: str,
    output_model: type[T],
    parser: Callable[[dict[str, object]], T],
    fallback_system_prompt: str | None = None,
    fallback_user_prompt: str | None = None,
    repair_user_prompt_builder: Callable[[list[str]], str] | None = None,
    max_repair_attempts: int = 1,
    log_event: str = "llm.harness.finish",
) -> StructuredLLMResult[T]:
    """Structured-output harness: LLM call → schema validate → repair → fallback prompt."""
    attempts: list[HarnessAttempt] = []
    llm_client = get_llm_client()
    current_user_prompt = user_prompt
    phase: HarnessPhase = "primary"
    llm_response = await llm_client.complete_json(
        model_slot=model_slot,
        system_prompt=system_prompt,
        user_prompt=current_user_prompt,
        fallback_system_prompt=fallback_system_prompt,
        fallback_user_prompt=fallback_user_prompt,
    )

    if llm_response.error is not None:
        attempts.append(
            HarnessAttempt(
                attempt=len(attempts) + 1,
                phase=phase,
                llm_error=llm_response.error,
                validation_errors=(),
            )
        )
        if fallback_user_prompt is not None and not llm_response.fallback_used:
            phase = "fallback_prompt"
            llm_response = await llm_client.complete_json(
                model_slot=model_slot,
                system_prompt=fallback_system_prompt or system_prompt,
                user_prompt=fallback_user_prompt,
                fallback_system_prompt=fallback_system_prompt,
                fallback_user_prompt=fallback_user_prompt,
            )
            if llm_response.error is not None:
                attempts.append(
                    HarnessAttempt(
                        attempt=len(attempts) + 1,
                        phase=phase,
                        llm_error=llm_response.error,
                        validation_errors=(),
                    )
                )
                result = StructuredLLMResult(
                    value=None,
                    outcome="failed",
                    llm_response=llm_response,
                    validation_errors=(),
                    attempts=tuple(attempts),
                )
                _log_harness_finish(log_event=log_event, model_slot=model_slot, result=result)
                return result

    parsed, validation_errors = _validate_parsed_output(
        output_model=output_model,
        content=llm_response.content,
        parser=parser,
    )
    attempts.append(
        HarnessAttempt(
            attempt=len(attempts) + 1,
            phase=phase,
            llm_error=llm_response.error,
            validation_errors=validation_errors,
        )
    )
    if parsed is not None:
        outcome: HarnessOutcome = "primary"
        if phase == "fallback_prompt" or llm_response.fallback_used:
            outcome = "fallback_prompt"
        result = StructuredLLMResult(
            value=parsed,
            outcome=outcome,
            llm_response=llm_response,
            validation_errors=(),
            attempts=tuple(attempts),
        )
        _log_harness_finish(log_event=log_event, model_slot=model_slot, result=result)
        return result

    repair_attempts = 0
    while repair_user_prompt_builder is not None and repair_attempts < max_repair_attempts:
        repair_attempts += 1
        phase = "repair"
        repair_prompt = _build_repair_prompt(
            validation_errors=validation_errors,
            previous_invalid_output=llm_response.content,
            repair_user_prompt_builder=repair_user_prompt_builder,
        )
        llm_response = await llm_client.complete_json(
            model_slot=model_slot,
            system_prompt=system_prompt,
            user_prompt=repair_prompt,
            fallback_system_prompt=fallback_system_prompt,
            fallback_user_prompt=fallback_user_prompt,
        )
        if llm_response.error is not None:
            attempts.append(
                HarnessAttempt(
                    attempt=len(attempts) + 1,
                    phase=phase,
                    llm_error=llm_response.error,
                    validation_errors=(),
                )
            )
            break

        parsed, validation_errors = _validate_parsed_output(
            output_model=output_model,
            content=llm_response.content,
            parser=parser,
        )
        attempts.append(
            HarnessAttempt(
                attempt=len(attempts) + 1,
                phase=phase,
                llm_error=llm_response.error,
                validation_errors=validation_errors,
            )
        )
        if parsed is not None:
            result = StructuredLLMResult(
                value=parsed,
                outcome="repaired",
                llm_response=llm_response,
                validation_errors=(),
                attempts=tuple(attempts),
            )
            _log_harness_finish(log_event=log_event, model_slot=model_slot, result=result)
            return result

    if fallback_user_prompt is not None and phase != "fallback_prompt":
        phase = "fallback_prompt"
        llm_response = await llm_client.complete_json(
            model_slot=model_slot,
            system_prompt=fallback_system_prompt or system_prompt,
            user_prompt=fallback_user_prompt,
            fallback_system_prompt=fallback_system_prompt,
            fallback_user_prompt=fallback_user_prompt,
        )
        if llm_response.error is None:
            parsed, validation_errors = _validate_parsed_output(
                output_model=output_model,
                content=llm_response.content,
                parser=parser,
            )
            attempts.append(
                HarnessAttempt(
                    attempt=len(attempts) + 1,
                    phase=phase,
                    llm_error=llm_response.error,
                    validation_errors=validation_errors,
                )
            )
            if parsed is not None:
                result = StructuredLLMResult(
                    value=parsed,
                    outcome="fallback_prompt",
                    llm_response=llm_response,
                    validation_errors=(),
                    attempts=tuple(attempts),
                )
                _log_harness_finish(log_event=log_event, model_slot=model_slot, result=result)
                return result

    schema_error = validation_errors[0] if validation_errors else "structured_output_invalid"
    result = StructuredLLMResult(
        value=None,
        outcome="failed",
        llm_response=llm_response,
        validation_errors=validation_errors,
        attempts=tuple(attempts),
        schema_error=schema_error,
    )
    _log_harness_finish(log_event=log_event, model_slot=model_slot, result=result)
    return result


def _log_harness_finish(*, log_event: str, model_slot: str, result: StructuredLLMResult[BaseModel]) -> None:
    log.info(
        log_event,
        model_slot=model_slot,
        outcome=result.outcome,
        attempt_count=len(result.attempts),
        validation_error_count=len(result.validation_errors),
        schema_error=result.schema_error,
        llm_error=result.llm_response.error,
        llm_fallback_used=result.llm_response.fallback_used,
    )
