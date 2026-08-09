from __future__ import annotations

from types import SimpleNamespace

import httpx
import pytest

from service.llm import providers as llm_providers
from service.llm.exceptions import LLMRequestError
from service.llm.providers import DoubaoProvider, OpenAIProvider, QwenProvider, build_providers
from utils.logger import configure_logging


def _fake_response(*, model: str, content: str, prompt_tokens: int, completion_tokens: int):
    """Async stream mirroring DashScope's streamed chat-completion shape.

    The provider now streams (`stream=True`) to dodge the non-streaming
    long-output disconnect, so the fake `create` must return an async iterator
    of delta chunks followed by a usage-only chunk.
    """

    async def _stream():
        yield SimpleNamespace(
            model=model,
            usage=None,
            choices=[SimpleNamespace(delta=SimpleNamespace(content=content))],
        )
        yield SimpleNamespace(
            model=model,
            usage=SimpleNamespace(
                prompt_tokens=prompt_tokens,
                completion_tokens=completion_tokens,
            ),
            choices=[],
        )

    return _stream()


@pytest.mark.asyncio
async def test_doubao_provider_complete_json_success(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_create(**_: object):
        return _fake_response(
            model="doubao-seed",
            content='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"done"}',
            prompt_tokens=12,
            completion_tokens=6,
        )

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = DoubaoProvider(
        base_url="https://ark.example.com/v3",
        api_key="fake-key",
        default_model="ep-demo",
    )
    response = await provider.complete_json(
        system_prompt="system",
        user_prompt="user",
        model="ep-demo",
        timeout_seconds=10,
    )

    assert response.model_name == "doubao-seed"
    assert response.prompt_tokens == 12
    assert response.completion_tokens == 6
    assert response.content_raw.startswith('{"chosen_tool"')


@pytest.mark.asyncio
async def test_doubao_provider_wraps_connection_error(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging()

    class DummyConnectionError(Exception):
        pass

    async def fake_create(**_: object):
        raise DummyConnectionError("network down")

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "APIConnectionError", DummyConnectionError)
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = DoubaoProvider(
        base_url="https://ark.example.com/v3",
        api_key="fake-key",
        default_model="ep-demo",
    )
    with pytest.raises(LLMRequestError):
        await provider.complete_json(
            system_prompt="system",
            user_prompt="user",
            model="ep-demo",
            timeout_seconds=10,
        )
    logged = capsys.readouterr().out
    assert "llm.provider.error" in logged
    assert "llm.call.error" not in logged
    assert "retryable" in logged
    assert "attempt" in logged


@pytest.mark.asyncio
async def test_provider_redacts_deployment_model_id_in_errors(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    configure_logging()

    class DummyConnectionError(Exception):
        pass

    raw_model = "ep-sensitive-deployment-id"

    async def fake_create(**_: object):
        raise DummyConnectionError(f"connection failed for {raw_model}")

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "APIConnectionError", DummyConnectionError)
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = DoubaoProvider(
        base_url="https://ark.example.com/v3",
        api_key="fake-key",
        default_model=raw_model,
    )
    with pytest.raises(LLMRequestError) as exc_info:
        await provider.complete_json(
            system_prompt="system",
            user_prompt="user",
            model=raw_model,
            timeout_seconds=10,
        )

    logged = capsys.readouterr().out
    assert raw_model not in str(exc_info.value)
    assert raw_model not in logged
    assert "[REDACTED_MODEL_ID]" in str(exc_info.value)
    assert "[REDACTED_MODEL_ID]" in logged


@pytest.mark.asyncio
async def test_provider_classifies_429_retry_after(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class DummyStatusError(Exception):
        def __init__(self) -> None:
            super().__init__("rate limited")
            self.status_code = 429
            self.response = SimpleNamespace(headers={"retry-after": "2.5"})

    async def fake_create(**_: object):
        raise DummyStatusError()

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "APIStatusError", DummyStatusError)
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = DoubaoProvider(
        base_url="https://ark.example.com/v3",
        api_key="fake-key",
        default_model="ep-demo",
    )
    with pytest.raises(LLMRequestError) as exc_info:
        await provider.complete_json(
            system_prompt="system",
            user_prompt="user",
            model="ep-demo",
            timeout_seconds=10,
        )

    assert exc_info.value.retryable is True
    assert exc_info.value.http_status == 429
    assert exc_info.value.retry_after_seconds == 2.5
    assert exc_info.value.error_class == "rate_limit"


@pytest.mark.asyncio
async def test_provider_classifies_401_as_non_retryable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class DummyStatusError(Exception):
        def __init__(self) -> None:
            super().__init__("unauthorized")
            self.status_code = 401

    async def fake_create(**_: object):
        raise DummyStatusError()

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "APIStatusError", DummyStatusError)
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = DoubaoProvider(
        base_url="https://ark.example.com/v3",
        api_key="fake-key",
        default_model="ep-demo",
    )
    with pytest.raises(LLMRequestError) as exc_info:
        await provider.complete_json(
            system_prompt="system",
            user_prompt="user",
            model="ep-demo",
            timeout_seconds=10,
        )

    assert exc_info.value.retryable is False
    assert exc_info.value.http_status == 401
    assert exc_info.value.error_class == "http_4xx"


@pytest.mark.asyncio
async def test_doubao_provider_skips_json_mode_by_default(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    llm_providers.clear_json_mode_capability_cache()
    call_kwargs: list[dict[str, object]] = []

    async def fake_create(**kwargs: object):
        call_kwargs.append(dict(kwargs))
        return _fake_response(
            model="doubao-seed",
            content='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"done"}',
            prompt_tokens=12,
            completion_tokens=6,
        )

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = DoubaoProvider(
        base_url="https://ark.example.com/v3",
        api_key="fake-key",
        default_model="ep-demo",
    )
    response = await provider.complete_json(
        system_prompt="system",
        user_prompt="user",
        model="ep-demo",
        timeout_seconds=10,
    )

    assert response.model_name == "doubao-seed"
    assert len(call_kwargs) == 1
    assert "response_format" not in call_kwargs[0]


@pytest.mark.asyncio
async def test_provider_passes_split_timeout_and_max_tokens(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    call_kwargs: list[dict[str, object]] = []

    async def fake_create(**kwargs: object):
        call_kwargs.append(dict(kwargs))
        return _fake_response(
            model="doubao-seed",
            content='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"done"}',
            prompt_tokens=12,
            completion_tokens=6,
        )

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers.settings, "LLM_CONNECT_TIMEOUT_SECONDS", 5)
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = DoubaoProvider(
        base_url="https://ark.example.com/v3",
        api_key="fake-key",
        default_model="ep-demo",
    )
    await provider.complete_json(
        system_prompt="system",
        user_prompt="user",
        model="ep-demo",
        timeout_seconds=90,
        max_tokens=2048,
    )

    assert len(call_kwargs) == 1
    timeout = call_kwargs[0]["timeout"]
    assert isinstance(timeout, httpx.Timeout)
    assert timeout.connect == 5.0
    assert timeout.read == 90.0
    assert timeout.write == 10.0
    assert timeout.pool == 5.0
    assert call_kwargs[0]["max_tokens"] == 2048
    # Long deep-report generations must stream to survive DashScope's
    # non-streaming response window; usage rides the final stream chunk.
    assert call_kwargs[0]["stream"] is True
    assert call_kwargs[0]["stream_options"] == {"include_usage": True}


@pytest.mark.asyncio
async def test_provider_keeps_streaming_when_stream_options_are_unsupported(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class DummyStatusError(Exception):
        def __init__(self) -> None:
            super().__init__("InvalidParameter: stream_options.include_usage is not supported")
            self.status_code = 400
            self.body = {"message": "stream_options.include_usage is not supported"}

    call_kwargs: list[dict[str, object]] = []

    async def fake_create(**kwargs: object):
        call_kwargs.append(dict(kwargs))
        if "stream_options" in kwargs:
            raise DummyStatusError()
        return _fake_response(
            model="gpt-4o-mini",
            content='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"done"}',
            prompt_tokens=None,
            completion_tokens=None,
        )

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "APIStatusError", DummyStatusError)
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = OpenAIProvider(
        base_url="https://api.openai.com/v1",
        api_key="fake-key",
        default_model="gpt-4o-mini",
    )
    response = await provider.complete_json(
        system_prompt="system",
        user_prompt="user",
        model="gpt-4o-mini",
        timeout_seconds=90,
        max_tokens=2048,
    )

    assert response.content_raw.startswith('{"chosen_tool"')
    assert len(call_kwargs) == 2
    assert call_kwargs[0]["stream"] is True
    assert call_kwargs[0]["stream_options"] == {"include_usage": True}
    assert call_kwargs[0]["response_format"] == {"type": "json_object"}
    assert call_kwargs[1]["stream"] is True
    assert "stream_options" not in call_kwargs[1]
    assert call_kwargs[1]["response_format"] == {"type": "json_object"}


@pytest.mark.asyncio
async def test_provider_wraps_mid_stream_disconnect_as_retryable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A peer that drops the connection mid-stream must degrade, not crash.

    DashScope closes long streamed generations with httpx.RemoteProtocolError
    ("incomplete chunked read"), which the OpenAI SDK does not wrap. The provider
    must convert it into a retryable LLMRequestError so the agent node falls back
    instead of raising a raw transport error to the graph.
    """

    async def fake_create(**_: object):
        async def _stream():
            yield SimpleNamespace(
                model="qwen3.7-max",
                usage=None,
                choices=[SimpleNamespace(delta=SimpleNamespace(content='{"chosen_tool":'))],
            )
            raise httpx.RemoteProtocolError(
                "peer closed connection without sending complete message body (incomplete chunked read)"
            )

        return _stream()

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = QwenProvider(
        base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
        api_key="fake-qwen-key",
        default_model="qwen3.7-max",
    )
    with pytest.raises(LLMRequestError) as exc_info:
        await provider.complete_json(
            system_prompt="system",
            user_prompt="user",
            model="qwen3.7-max",
            timeout_seconds=180,
        )

    assert exc_info.value.retryable is True
    assert exc_info.value.error_class == "connection"


@pytest.mark.asyncio
async def test_doubao_provider_caches_json_mode_unsupported_after_400(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    llm_providers.clear_json_mode_capability_cache()

    class DummyStatusError(Exception):
        def __init__(self, message: str) -> None:
            super().__init__(message)
            self.status_code = 400

    call_kwargs: list[dict[str, object]] = []

    async def fake_create(**kwargs: object):
        call_kwargs.append(dict(kwargs))
        if "response_format" in kwargs:
            raise DummyStatusError(
                "InvalidParameter: response_format.type json_object is not supported by this model"
            )
        return _fake_response(
            model="gpt-4o-mini",
            content='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"done"}',
            prompt_tokens=12,
            completion_tokens=6,
        )

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "APIStatusError", DummyStatusError)
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = OpenAIProvider(
        base_url="https://api.openai.com/v1",
        api_key="fake-key",
        default_model="gpt-4o-mini",
    )
    await provider.complete_json(
        system_prompt="system",
        user_prompt="user",
        model="gpt-4o-mini",
        timeout_seconds=10,
        max_tokens=2048,
    )
    call_kwargs.clear()
    await provider.complete_json(
        system_prompt="system",
        user_prompt="user",
        model="gpt-4o-mini",
        timeout_seconds=10,
        max_tokens=2048,
    )

    assert len(call_kwargs) == 1
    assert "response_format" not in call_kwargs[0]
    assert call_kwargs[0]["max_tokens"] == 2048


@pytest.mark.asyncio
async def test_provider_json_mode_fallback_preserves_max_tokens(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    llm_providers.clear_json_mode_capability_cache()

    class DummyStatusError(Exception):
        def __init__(self, message: str) -> None:
            super().__init__(message)
            self.status_code = 400

    call_kwargs: list[dict[str, object]] = []

    async def fake_create(**kwargs: object):
        call_kwargs.append(dict(kwargs))
        if "response_format" in kwargs:
            raise DummyStatusError(
                "InvalidParameter: response_format.type json_object is not supported by this model"
            )
        return _fake_response(
            model="gpt-4o-mini",
            content='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"done"}',
            prompt_tokens=12,
            completion_tokens=6,
        )

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "APIStatusError", DummyStatusError)
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = OpenAIProvider(
        base_url="https://api.openai.com/v1",
        api_key="fake-key",
        default_model="gpt-4o-mini",
    )
    await provider.complete_json(
        system_prompt="system",
        user_prompt="user",
        model="gpt-4o-mini",
        timeout_seconds=10,
        max_tokens=2048,
    )

    assert len(call_kwargs) == 2
    assert call_kwargs[0]["max_tokens"] == 2048
    assert call_kwargs[1]["max_tokens"] == 2048
    assert "response_format" in call_kwargs[0]
    assert "response_format" not in call_kwargs[1]


@pytest.mark.asyncio
async def test_doubao_provider_fallbacks_when_json_mode_unsupported(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Doubao skips json_mode by default; no 400 probe round-trip."""
    llm_providers.clear_json_mode_capability_cache()

    call_kwargs: list[dict[str, object]] = []

    async def fake_create(**kwargs: object):
        call_kwargs.append(dict(kwargs))
        return _fake_response(
            model="doubao-seed",
            content='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"done"}',
            prompt_tokens=12,
            completion_tokens=6,
        )

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = DoubaoProvider(
        base_url="https://ark.example.com/v3",
        api_key="fake-key",
        default_model="ep-demo",
    )
    response = await provider.complete_json(
        system_prompt="system",
        user_prompt="user",
        model="ep-demo",
        timeout_seconds=10,
    )

    assert response.model_name == "doubao-seed"
    assert len(call_kwargs) == 1
    assert "response_format" not in call_kwargs[0]


@pytest.mark.asyncio
async def test_doubao_provider_retries_on_generic_json_mode_400(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Doubao never sends json_mode on first attempt."""
    llm_providers.clear_json_mode_capability_cache()

    call_kwargs: list[dict[str, object]] = []

    async def fake_create(**kwargs: object):
        call_kwargs.append(dict(kwargs))
        return _fake_response(
            model="doubao-seed",
            content='{"chosen_tool":"Finalize","tool_args":{"completion_reason":"all_dimensions_covered"},"reasoning_summary":"done"}',
            prompt_tokens=12,
            completion_tokens=6,
        )

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = DoubaoProvider(
        base_url="https://ark.example.com/v3",
        api_key="fake-key",
        default_model="ep-demo",
    )
    response = await provider.complete_json(
        system_prompt="system",
        user_prompt="user",
        model="ep-demo",
        timeout_seconds=10,
    )

    assert response.model_name == "doubao-seed"
    assert len(call_kwargs) == 1
    assert "response_format" not in call_kwargs[0]


@pytest.mark.asyncio
async def test_openai_provider_complete_json_success(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_create(**_: object):
        return _fake_response(
            model="gpt-4o-mini",
            content='{"chosen_tool":"Analyze","tool_args":{"parallel_by_dimension":false,"require_cross_competitor":true},"reasoning_summary":"analyze"}',
            prompt_tokens=20,
            completion_tokens=9,
        )

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = OpenAIProvider(
        base_url="https://api.openai.com/v1",
        api_key="fake-openai-key",
        default_model="gpt-4o-mini",
    )
    response = await provider.complete_json(
        system_prompt="system",
        user_prompt="user",
        model="gpt-4o-mini",
        timeout_seconds=10,
    )

    assert response.model_name == "gpt-4o-mini"
    assert response.prompt_tokens == 20
    assert response.completion_tokens == 9
    assert response.content_raw.startswith('{"chosen_tool"')


@pytest.mark.asyncio
async def test_qwen_provider_complete_json_success(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_create(**kwargs: object):
        captured.update(kwargs)
        return _fake_response(
            model="qwen-plus",
            content='{"chosen_tool":"Write","tool_args":{"style":"concise"},"reasoning_summary":"write"}',
            prompt_tokens=18,
            completion_tokens=8,
        )

    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    provider = QwenProvider(
        base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
        api_key="fake-qwen-key",
        default_model="qwen-plus",
    )
    response = await provider.complete_json(
        system_prompt="system",
        user_prompt="user",
        model="qwen-plus",
        timeout_seconds=10,
    )

    assert response.model_name == "qwen-plus"
    assert response.prompt_tokens == 18
    assert response.completion_tokens == 8
    assert response.content_raw.startswith('{"chosen_tool"')
    # Thinking must be forced off so hybrid Qwen models stream JSON output only.
    assert captured["extra_body"] == {"enable_thinking": False}


def test_provider_default_model_properties(monkeypatch: pytest.MonkeyPatch) -> None:
    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=None)))
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)

    doubao = DoubaoProvider(
        base_url="https://ark.example.com/v3",
        api_key="fake-key",
        default_model="ep-demo",
    )
    openai_provider = OpenAIProvider(
        base_url="https://api.openai.com/v1",
        api_key="fake-openai-key",
        default_model="gpt-4o-mini",
    )
    qwen_provider = QwenProvider(
        base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
        api_key="fake-qwen-key",
        default_model="qwen-plus",
    )

    assert doubao.default_model == "ep-demo"
    assert openai_provider.default_model == "gpt-4o-mini"
    assert qwen_provider.default_model == "qwen-plus"


def test_build_providers_uses_active_and_overridden_provider_catalog(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=None)))
    monkeypatch.setattr(llm_providers, "AsyncOpenAI", lambda **_: fake_client)
    monkeypatch.setattr(llm_providers.settings, "LLM_ACTIVE_PROVIDER", "doubao")
    monkeypatch.setattr(llm_providers.settings, "LLM_PROVIDER_RESEARCH", None)
    monkeypatch.setattr(llm_providers.settings, "LLM_PROVIDER_SUMMARIZATION", None)
    monkeypatch.setattr(llm_providers.settings, "LLM_PROVIDER_COMPRESSION", None)
    monkeypatch.setattr(llm_providers.settings, "LLM_PROVIDER_QA", "qwen")
    monkeypatch.setattr(llm_providers.settings, "LLM_PROVIDER_WRITER", None)
    monkeypatch.setattr(llm_providers.settings, "DOUBAO_API_KEY", "fake-doubao-key")
    monkeypatch.setattr(llm_providers.settings, "DOUBAO_EP", "ep-fallback")
    monkeypatch.setattr(llm_providers.settings, "DOUBAO_MODEL_BALANCED", "ep-balanced")
    monkeypatch.setattr(llm_providers.settings, "QWEN_API_KEY", "fake-qwen-key")
    monkeypatch.setattr(llm_providers.settings, "QWEN_MODEL_BALANCED", "qwen-plus-catalog")

    providers = build_providers()

    assert set(providers) == {"doubao", "qwen"}
    assert providers["doubao"].default_model == "ep-balanced"
    assert providers["qwen"].default_model == "qwen-plus-catalog"
