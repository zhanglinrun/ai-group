from __future__ import annotations

import asyncio

import pytest

from core.config import settings
from service.llm.client import LLMClient, _resolve_max_tokens, _resolve_timeout_seconds
from service.llm.exceptions import LLMRequestError
from service.llm.response import ProviderRawResponse


class _SequencedProvider:
    def __init__(
        self,
        *,
        default_model: str,
        responses: list[ProviderRawResponse],
        request_errors: list[LLMRequestError] | None = None,
        delay_seconds: float = 0.0,
    ) -> None:
        self.default_model = default_model
        self._responses = responses
        self._request_errors = request_errors or []
        self._delay_seconds = delay_seconds
        self.call_count = 0
        self.inflight = 0
        self.max_inflight = 0
        self.calls: list[dict[str, object]] = []

    async def complete_json(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        model: str,
        timeout_seconds: int,
        max_tokens: int | None = None,
    ) -> ProviderRawResponse:
        del system_prompt, user_prompt, model
        self.calls.append({"timeout_seconds": timeout_seconds, "max_tokens": max_tokens})
        self.call_count += 1
        self.inflight += 1
        if self.inflight > self.max_inflight:
            self.max_inflight = self.inflight
        try:
            if self._delay_seconds > 0:
                await asyncio.sleep(self._delay_seconds)

            if self._request_errors:
                raise self._request_errors.pop(0)

            if not self._responses:
                raise RuntimeError("No fake response configured for provider test.")
            return self._responses.pop(0)
        finally:
            self.inflight -= 1


def _make_client(provider: _SequencedProvider, *, max_retries: int = 2, concurrency: int = 2) -> LLMClient:
    return LLMClient(
        providers={"doubao": provider},
        max_retries=max_retries,
        timeout_seconds=10,
        global_concurrency=concurrency,
        retry_base_seconds=0.0,
        retry_cap_seconds=0.0,
        tpm_budget=0,
    )


def _mock_research_slot(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "LLM_PROVIDER_RESEARCH", "doubao")
    monkeypatch.setattr(settings, "LLM_MODEL_RESEARCH", None)


def test_resolve_timeout_seconds_uses_slot_table(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "LLM_TIMEOUT_SECONDS", 31)
    monkeypatch.setattr(settings, "LLM_TIMEOUT_SUMMARIZATION", 181)
    monkeypatch.setattr(settings, "LLM_TIMEOUT_COMPRESSION", 121)
    monkeypatch.setattr(settings, "LLM_TIMEOUT_RESEARCH", 91)
    monkeypatch.setattr(settings, "LLM_TIMEOUT_QA", 92)
    monkeypatch.setattr(settings, "LLM_TIMEOUT_WRITER", 182)

    assert _resolve_timeout_seconds("summarization") == 181
    assert _resolve_timeout_seconds("compression") == 121
    assert _resolve_timeout_seconds("research") == 91
    assert _resolve_timeout_seconds("qa") == 92
    assert _resolve_timeout_seconds("writer") == 182
    assert _resolve_timeout_seconds("unknown-slot") == 31


def test_resolve_max_tokens_uses_slot_table(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "LLM_MAX_TOKENS_SUMMARIZATION", 4096)
    monkeypatch.setattr(settings, "LLM_MAX_TOKENS_COMPRESSION", 2048)
    monkeypatch.setattr(settings, "LLM_MAX_TOKENS_RESEARCH", 1024)
    monkeypatch.setattr(settings, "LLM_MAX_TOKENS_QA", 1536)
    monkeypatch.setattr(settings, "LLM_MAX_TOKENS_WRITER", 8192)

    assert _resolve_max_tokens("summarization") == 4096
    assert _resolve_max_tokens("compression") == 2048
    assert _resolve_max_tokens("research") == 1024
    assert _resolve_max_tokens("qa") == 1536
    assert _resolve_max_tokens("writer") == 8192
    assert _resolve_max_tokens("unknown-slot") is None


def test_resolve_max_tokens_treats_zero_as_unlimited(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "LLM_MAX_TOKENS_RESEARCH", 0)

    assert _resolve_max_tokens("research") is None


@pytest.mark.asyncio
async def test_llm_client_success(monkeypatch: pytest.MonkeyPatch) -> None:
    _mock_research_slot(monkeypatch)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[
            ProviderRawResponse(
                content_raw='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"done"}',
                model_name="ep-default",
                prompt_tokens=9,
                completion_tokens=3,
            )
        ],
    )
    client = _make_client(provider)
    response = await client.complete_json(
        model_slot="research",
        system_prompt="system",
        user_prompt="user",
    )

    assert response.error is None
    assert response.provider == "doubao"
    assert response.model_name == "ep-default"
    assert response.prompt_tokens == 9
    assert response.completion_tokens == 3
    assert response.content["chosen_tool"] == "Finalize"
    assert response.prompt_text == "[system]\nsystem\n\n[user]\nuser"
    assert response.prompt_preview == "[system]\\nsystem\\n\\n[user]\\nuser"
    assert response.response_raw is not None
    assert response.response_raw.startswith('{"chosen_tool"')
    assert provider.calls == [
        {
            "timeout_seconds": settings.LLM_TIMEOUT_RESEARCH,
            "max_tokens": settings.LLM_MAX_TOKENS_RESEARCH,
        }
    ]


@pytest.mark.asyncio
async def test_llm_client_trace_fields_are_redacted(monkeypatch: pytest.MonkeyPatch) -> None:
    _mock_research_slot(monkeypatch)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[
            ProviderRawResponse(
                content_raw='{"result":"ok","echoed_secret":"api_key=raw-secret"}',
                model_name="ep-default",
                prompt_tokens=9,
                completion_tokens=3,
            )
        ],
    )
    client = _make_client(provider)
    response = await client.complete_json(
        model_slot="research",
        system_prompt="system api_key=system-secret",
        user_prompt="user token=user-secret",
    )

    assert response.error is None
    assert response.prompt_text is not None
    assert "system-secret" not in response.prompt_text
    assert "user-secret" not in response.prompt_text
    assert "api_key=[REDACTED]" in response.prompt_text
    assert response.response_raw is not None
    assert "raw-secret" not in response.response_raw
    assert "api_key=[REDACTED]" in response.response_raw


@pytest.mark.asyncio
async def test_llm_client_json_parse_error(monkeypatch: pytest.MonkeyPatch) -> None:
    _mock_research_slot(monkeypatch)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[
            ProviderRawResponse(
                content_raw="not-a-json-object",
                model_name="ep-default",
                prompt_tokens=10,
                completion_tokens=2,
            )
        ],
    )
    client = _make_client(provider)
    response = await client.complete_json(
        model_slot="research",
        system_prompt="system",
        user_prompt="user",
    )

    assert response.content == {}
    assert response.error is not None
    assert "LLMResponseFormatError" in response.error


@pytest.mark.asyncio
async def test_llm_client_retries_on_request_error(monkeypatch: pytest.MonkeyPatch) -> None:
    _mock_research_slot(monkeypatch)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[
            ProviderRawResponse(
                content_raw='{"chosen_tool":"Analyze","tool_args":{"parallel_by_dimension":false,"require_cross_competitor":true},"reasoning_summary":"next"}',
                model_name="ep-default",
                prompt_tokens=11,
                completion_tokens=4,
            )
        ],
        request_errors=[
            LLMRequestError("first failed"),
            LLMRequestError("second failed"),
        ],
    )
    client = _make_client(provider, max_retries=2)
    response = await client.complete_json(
        model_slot="research",
        system_prompt="system",
        user_prompt="user",
    )

    assert provider.call_count == 3
    assert response.error is None
    assert response.content["chosen_tool"] == "Analyze"
    assert response.retry_count == 2


@pytest.mark.asyncio
async def test_llm_client_does_not_retry_exhausted_timeout_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _mock_research_slot(monkeypatch)
    monkeypatch.setattr(settings, "LLM_TIMEOUT_RESEARCH", 10)
    monkeypatch.setattr(settings, "LLM_RETRY_WALL_CLOCK_BUDGET_FACTOR", 2.0)
    times = iter([0.0, 0.0, 9.6, 9.6])

    def fake_perf_counter() -> float:
        return next(times)

    monkeypatch.setattr("service.llm.client.perf_counter", fake_perf_counter)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[],
        request_errors=[
            LLMRequestError("connection failed", retryable=True, error_class="connection"),
            LLMRequestError("should not run", retryable=True, error_class="connection"),
        ],
    )
    client = _make_client(provider, max_retries=2)
    response = await client.complete_json(
        model_slot="research",
        system_prompt="system",
        user_prompt="user",
    )

    assert provider.call_count == 1
    assert response.error is not None
    assert response.retry_count == 0


@pytest.mark.asyncio
async def test_llm_client_stops_retry_when_wall_clock_budget_would_be_exceeded(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _mock_research_slot(monkeypatch)
    monkeypatch.setattr(settings, "LLM_TIMEOUT_RESEARCH", 10)
    monkeypatch.setattr(settings, "LLM_RETRY_WALL_CLOCK_BUDGET_FACTOR", 1.1)
    times = iter([0.0, 0.0, 2.0, 2.0])

    def fake_perf_counter() -> float:
        return next(times)

    monkeypatch.setattr("service.llm.client.perf_counter", fake_perf_counter)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[],
        request_errors=[
            LLMRequestError("connection failed", retryable=True, error_class="connection"),
            LLMRequestError("should not run", retryable=True, error_class="connection"),
        ],
    )
    client = _make_client(provider, max_retries=2)
    response = await client.complete_json(
        model_slot="research",
        system_prompt="system",
        user_prompt="user",
    )

    assert provider.call_count == 1
    assert response.error is not None
    assert response.retry_count == 0


@pytest.mark.asyncio
async def test_llm_client_does_not_retry_non_retryable_error(monkeypatch: pytest.MonkeyPatch) -> None:
    _mock_research_slot(monkeypatch)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[],
        request_errors=[
            LLMRequestError(
                "bad request",
                retryable=False,
                http_status=400,
                error_class="http_4xx",
            ),
        ],
    )
    client = _make_client(provider, max_retries=2)
    response = await client.complete_json(
        model_slot="research",
        system_prompt="system",
        user_prompt="user",
    )

    assert provider.call_count == 1
    assert response.error is not None
    assert response.retry_count == 0


@pytest.mark.asyncio
async def test_llm_client_uses_retry_after_before_retry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _mock_research_slot(monkeypatch)
    sleep_calls: list[float] = []

    async def fake_sleep(seconds: float) -> None:
        sleep_calls.append(seconds)

    monkeypatch.setattr("service.llm.client.asyncio.sleep", fake_sleep)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[
            ProviderRawResponse(
                content_raw='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"done"}',
                model_name="ep-default",
                prompt_tokens=1,
                completion_tokens=1,
            )
        ],
        request_errors=[
            LLMRequestError(
                "rate limited",
                retryable=True,
                http_status=429,
                retry_after_seconds=1.25,
                error_class="rate_limit",
            )
        ],
    )
    client = _make_client(provider, max_retries=1)

    response = await client.complete_json(
        model_slot="research",
        system_prompt="system",
        user_prompt="user",
    )

    assert response.error is None
    assert response.retry_count == 1
    assert sleep_calls == [1.25]


@pytest.mark.asyncio
async def test_llm_client_full_jitter_uses_configured_upper_bound(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _mock_research_slot(monkeypatch)
    sleep_calls: list[float] = []
    jitter_bounds: list[tuple[float, float]] = []

    async def fake_sleep(seconds: float) -> None:
        sleep_calls.append(seconds)

    def fake_uniform(lower: float, upper: float) -> float:
        jitter_bounds.append((lower, upper))
        return upper

    monkeypatch.setattr("service.llm.client.asyncio.sleep", fake_sleep)
    monkeypatch.setattr("service.llm.client.random.uniform", fake_uniform)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[
            ProviderRawResponse(
                content_raw='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"done"}',
                model_name="ep-default",
                prompt_tokens=1,
                completion_tokens=1,
            )
        ],
        request_errors=[
            LLMRequestError("first", retryable=True),
            LLMRequestError("second", retryable=True),
        ],
    )
    client = LLMClient(
        providers={"doubao": provider},
        max_retries=2,
        timeout_seconds=10,
        global_concurrency=2,
        retry_base_seconds=2.0,
        retry_cap_seconds=3.0,
        tpm_budget=0,
    )

    response = await client.complete_json(
        model_slot="research",
        system_prompt="system",
        user_prompt="user",
    )

    assert response.error is None
    assert response.retry_count == 2
    assert jitter_bounds == [(0.0, 2.0), (0.0, 3.0)]
    assert sleep_calls == [2.0, 3.0]


@pytest.mark.asyncio
async def test_llm_client_uses_fallback_prompt_after_primary_failures(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _mock_research_slot(monkeypatch)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[
            ProviderRawResponse(
                content_raw='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"fallback"}',
                model_name="ep-default",
                prompt_tokens=7,
                completion_tokens=2,
            )
        ],
        request_errors=[
            LLMRequestError("primary failed once"),
            LLMRequestError("primary failed twice"),
        ],
    )
    client = _make_client(provider, max_retries=1)
    response = await client.complete_json(
        model_slot="research",
        system_prompt="system",
        user_prompt="user",
        fallback_system_prompt="fallback-system",
        fallback_user_prompt="fallback-user",
    )

    assert provider.call_count == 3
    assert response.error is None
    assert response.fallback_used is True
    assert response.fallback_reason is not None
    assert response.content["chosen_tool"] == "Finalize"


@pytest.mark.asyncio
async def test_llm_client_returns_error_when_fallback_also_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _mock_research_slot(monkeypatch)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[],
        request_errors=[
            LLMRequestError("primary failed once"),
            LLMRequestError("primary failed twice"),
            LLMRequestError("fallback failed"),
            LLMRequestError("fallback failed twice"),
        ],
    )
    client = _make_client(provider, max_retries=1)
    response = await client.complete_json(
        model_slot="research",
        system_prompt="system",
        user_prompt="user",
        fallback_system_prompt="fallback-system",
        fallback_user_prompt="fallback-user",
    )

    assert provider.call_count == 4
    assert response.error is not None
    assert "primary=" in response.error
    assert "fallback=" in response.error
    assert response.fallback_used is True
    assert response.retry_count == 2


@pytest.mark.asyncio
async def test_llm_client_prompt_hash_stable(monkeypatch: pytest.MonkeyPatch) -> None:
    _mock_research_slot(monkeypatch)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[
            ProviderRawResponse(
                content_raw='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"a"}',
                model_name="ep-default",
                prompt_tokens=1,
                completion_tokens=1,
            ),
            ProviderRawResponse(
                content_raw='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"b"}',
                model_name="ep-default",
                prompt_tokens=1,
                completion_tokens=1,
            ),
        ],
    )
    client = _make_client(provider)
    first = await client.complete_json(
        model_slot="research",
        system_prompt="same-system",
        user_prompt="same-user",
    )
    second = await client.complete_json(
        model_slot="research",
        system_prompt="same-system",
        user_prompt="same-user",
    )

    assert first.prompt_hash == second.prompt_hash


@pytest.mark.asyncio
async def test_llm_client_semaphore_limits_concurrency(monkeypatch: pytest.MonkeyPatch) -> None:
    _mock_research_slot(monkeypatch)
    provider = _SequencedProvider(
        default_model="ep-default",
        responses=[
            ProviderRawResponse(
                content_raw='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"ok"}',
                model_name="ep-default",
                prompt_tokens=1,
                completion_tokens=1,
            )
            for _ in range(8)
        ],
        delay_seconds=0.05,
    )
    client = _make_client(provider, concurrency=2)

    await asyncio.gather(
        *[
            client.complete_json(
                model_slot="research",
                system_prompt="system",
                user_prompt=f"user-{index}",
            )
            for index in range(8)
        ]
    )

    assert provider.max_inflight <= 2
