from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest
from pydantic import BaseModel, Field

from service.llm.harness import complete_structured
from service.llm.response import LLMResponse


class _SampleOutput(BaseModel):
    message: str = Field(min_length=3)


@pytest.mark.asyncio
async def test_complete_structured_repairs_after_validation_failure() -> None:
    invalid = LLMResponse(
        model_slot="research",
        provider="stub",
        model_name="stub",
        prompt_preview="preview",
        prompt_hash="hash",
        content={"message": "x"},
        prompt_tokens=1,
        completion_tokens=1,
        latency_ms=1,
        error=None,
    )
    repaired = LLMResponse(
        model_slot="research",
        provider="stub",
        model_name="stub",
        prompt_preview="preview",
        prompt_hash="hash2",
        content={"message": "valid"},
        prompt_tokens=1,
        completion_tokens=1,
        latency_ms=1,
        error=None,
    )

    with patch("service.llm.harness.get_llm_client") as get_client:
        client = AsyncMock()
        client.complete_json = AsyncMock(side_effect=[invalid, repaired])
        get_client.return_value = client

        result = await complete_structured(
            model_slot="research",
            system_prompt="system",
            user_prompt="user",
            output_model=_SampleOutput,
            parser=lambda content: _SampleOutput.model_validate(content),
            repair_user_prompt_builder=lambda errors: f"repair: {errors[0]}",
        )

    assert result.outcome == "repaired"
    assert result.value is not None
    assert result.value.message == "valid"
    assert client.complete_json.await_count == 2
    repair_prompt = client.complete_json.await_args_list[1].kwargs["user_prompt"]
    assert "Previous invalid JSON output (must be corrected):" in repair_prompt
    assert '{"message": "x"}' in repair_prompt
    assert "repair:" in repair_prompt


@pytest.mark.asyncio
async def test_complete_structured_uses_fallback_prompt_after_repair_failure() -> None:
    invalid = LLMResponse(
        model_slot="research",
        provider="stub",
        model_name="stub",
        prompt_preview="preview",
        prompt_hash="hash",
        content={"message": "x"},
        prompt_tokens=1,
        completion_tokens=1,
        latency_ms=1,
        error=None,
    )
    fallback_ok = LLMResponse(
        model_slot="research",
        provider="stub",
        model_name="stub",
        prompt_preview="fallback",
        prompt_hash="hash3",
        content={"message": "valid"},
        prompt_tokens=1,
        completion_tokens=1,
        latency_ms=1,
        error=None,
    )

    with patch("service.llm.harness.get_llm_client") as get_client:
        client = AsyncMock()
        client.complete_json = AsyncMock(side_effect=[invalid, invalid, fallback_ok])
        get_client.return_value = client

        result = await complete_structured(
            model_slot="research",
            system_prompt="system",
            user_prompt="user",
            output_model=_SampleOutput,
            parser=lambda content: _SampleOutput.model_validate(content),
            fallback_user_prompt="fallback user",
            repair_user_prompt_builder=lambda errors: f"repair: {errors[0]}",
        )

    assert result.outcome == "fallback_prompt"
    assert result.value is not None
    assert client.complete_json.await_count == 3


@pytest.mark.asyncio
async def test_complete_structured_failed_when_all_attempts_invalid() -> None:
    invalid = LLMResponse(
        model_slot="research",
        provider="stub",
        model_name="stub",
        prompt_preview="preview",
        prompt_hash="hash",
        content={"message": "x"},
        prompt_tokens=1,
        completion_tokens=1,
        latency_ms=1,
        error=None,
    )

    with patch("service.llm.harness.get_llm_client") as get_client:
        client = AsyncMock()
        client.complete_json = AsyncMock(return_value=invalid)
        get_client.return_value = client

        result = await complete_structured(
            model_slot="research",
            system_prompt="system",
            user_prompt="user",
            output_model=_SampleOutput,
            parser=lambda content: _SampleOutput.model_validate(content),
            fallback_user_prompt="fallback user",
            repair_user_prompt_builder=lambda errors: f"repair: {errors[0]}",
        )

    assert result.outcome == "failed"
    assert result.value is None
    assert result.schema_error is not None
