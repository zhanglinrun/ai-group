from __future__ import annotations

import json

import pytest

from core.config import settings
from service.llm.client import LLMClient
from service.llm.exceptions import LLMRequestError
from service.llm.response import ProviderRawResponse
from utils.logger import configure_logging


def _json_log_lines(logged: str) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for line in logged.splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(item, dict):
            rows.append(item)
    return rows


class _SingleResponseProvider:
    def __init__(self, response: ProviderRawResponse) -> None:
        self.default_model = "ep-default"
        self._response = response
        self.call_count = 0

    async def complete_json(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        model: str,
        timeout_seconds: int,
        max_tokens: int | None = None,
    ) -> ProviderRawResponse:
        del system_prompt, user_prompt, model, timeout_seconds, max_tokens
        self.call_count += 1
        if self.call_count > 1:
            raise RuntimeError("Provider called more than once in redaction test.")
        return self._response


@pytest.mark.asyncio
async def test_llm_client_logs_redact_prompt_and_fake_key(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging()
    monkeypatch.setattr(settings, "LLM_PROVIDER_RESEARCH", "doubao")
    monkeypatch.setattr(settings, "LLM_MODEL_RESEARCH", None)
    fake_key = "sk-test-FAKESECRET-12345"
    system_prompt = f"system prompt with secret {fake_key}"
    user_prompt = f"user prompt mirrors secret {fake_key}"
    provider = _SingleResponseProvider(
        ProviderRawResponse(
            content_raw='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"ok"}',
            model_name="ep-default",
            prompt_tokens=13,
            completion_tokens=4,
        )
    )
    client = LLMClient(
        providers={"doubao": provider},
        max_retries=0,
        timeout_seconds=5,
        global_concurrency=1,
    )

    _ = await client.complete_json(
        model_slot="research",
        system_prompt=system_prompt,
        user_prompt=user_prompt,
    )

    logged = capsys.readouterr().out
    rows = _json_log_lines(logged)
    assert not any(
        row.get("event") == "llm.call.start" and row.get("level") == "info"
        for row in rows
    )
    assert any(
        row.get("event") == "llm.call.finish" and row.get("level") == "info"
        for row in rows
    )
    assert fake_key not in logged
    assert system_prompt not in logged
    assert user_prompt not in logged
    assert "prompt_text" not in logged
    assert "response_raw" not in logged


@pytest.mark.asyncio
async def test_llm_client_logs_call_error_on_terminal_failure(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging()
    monkeypatch.setattr(settings, "LLM_PROVIDER_RESEARCH", "doubao")
    monkeypatch.setattr(settings, "LLM_MODEL_RESEARCH", None)
    provider = _SingleResponseProvider(
        ProviderRawResponse(
            content_raw="{}",
            model_name="ep-default",
            prompt_tokens=1,
            completion_tokens=1,
        )
    )

    async def _raise_request_error(
        *,
        system_prompt: str,
        user_prompt: str,
        model: str,
        timeout_seconds: int,
        max_tokens: int | None = None,
    ) -> ProviderRawResponse:
        del system_prompt, user_prompt, model, timeout_seconds, max_tokens
        raise LLMRequestError("connection reset")

    provider.complete_json = _raise_request_error  # type: ignore[method-assign]

    client = LLMClient(
        providers={"doubao": provider},
        max_retries=0,
        timeout_seconds=5,
        global_concurrency=1,
    )

    response = await client.complete_json(
        model_slot="research",
        system_prompt="system",
        user_prompt="user",
    )

    logged = capsys.readouterr().out
    assert response.error is not None
    assert logged.count("llm.call.error") == 1
    assert "llm.call.finish" in logged
    assert "error_class" in logged
