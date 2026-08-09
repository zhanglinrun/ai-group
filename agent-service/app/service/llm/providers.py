from __future__ import annotations

from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import re
from typing import Any, ClassVar, Protocol

import httpx
import structlog
from openai import APIConnectionError, APIStatusError, APITimeoutError, AsyncOpenAI, RateLimitError

from core.config import settings
from service.llm.exceptions import LLMRequestError, LLMResponseFormatError
from service.llm.response import ProviderRawResponse
from utils.logger import get_logger

log = get_logger("service.llm.providers")

_json_mode_fallback_keys: set[str] = set()
_JSON_MODE_FALLBACK_KEY_LIMIT = 512
_json_mode_capability_cache: dict[tuple[str, str], bool] = {}
_DEPLOYMENT_MODEL_ID_RE = re.compile(r"\bep-[A-Za-z0-9_-]+\b")


def _json_mode_cache_key(*, provider: str, model: str) -> tuple[str, str]:
    return (provider, model)


def _provider_default_json_mode(provider_name: str) -> bool:
    if not settings.LLM_JSON_MODE_ENABLED:
        return False
    if provider_name == "doubao":
        return False
    return True


def _resolve_json_mode(*, provider_name: str, model: str) -> bool:
    cache_key = _json_mode_cache_key(provider=provider_name, model=model)
    cached = _json_mode_capability_cache.get(cache_key)
    if cached is not None:
        return cached
    return _provider_default_json_mode(provider_name)


def _remember_json_mode_unsupported(*, provider_name: str, model: str) -> None:
    _json_mode_capability_cache[_json_mode_cache_key(provider=provider_name, model=model)] = False


def clear_json_mode_capability_cache() -> None:
    _json_mode_capability_cache.clear()


def _log_json_mode_fallback(
    *,
    provider: str,
    model: str,
    http_status: object,
    error_preview: str,
) -> None:
    ctx = structlog.contextvars.get_contextvars()
    run_id_raw = ctx.get("run_id")
    run_id = run_id_raw if isinstance(run_id_raw, str) else "unknown"
    key = f"{run_id}:{provider}:{model}"
    if key in _json_mode_fallback_keys:
        log.debug(
            "llm.call.json_mode_fallback",
            provider=provider,
            http_status=http_status,
            repeat=True,
        )
        return
    _json_mode_fallback_keys.add(key)
    if len(_json_mode_fallback_keys) > _JSON_MODE_FALLBACK_KEY_LIMIT:
        _json_mode_fallback_keys.clear()
    log.info(
        "llm.call.json_mode_fallback",
        provider=provider,
        http_status=http_status,
        error_preview=error_preview,
    )


class LLMProvider(Protocol):
    name: ClassVar[str]
    default_model: str

    async def complete_json(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        model: str,
        timeout_seconds: int,
        max_tokens: int | None = None,
    ) -> ProviderRawResponse:
        ...


def _delta_text(choice: Any) -> str | None:
    delta = getattr(choice, "delta", None)
    if delta is None:
        return None
    content = getattr(delta, "content", None)
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            text_part = item.get("text") if isinstance(item, dict) else getattr(item, "text", None)
            if isinstance(text_part, str):
                parts.append(text_part)
        return "".join(parts)
    return None


async def _consume_stream(*, stream: Any, requested_model: str) -> ProviderRawResponse:
    """Accumulate a streaming chat completion.

    DashScope's OpenAI-compatible endpoint closes the connection on long
    non-streaming generations (~60s server window), surfacing as a transport
    error. Streaming keeps the connection alive chunk-by-chunk, so deep-report
    writer/analyst calls no longer get truncated into the fallback path.
    """
    content_parts: list[str] = []
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    model_name: str | None = None
    async for chunk in stream:
        model_raw = getattr(chunk, "model", None)
        if isinstance(model_raw, str) and model_raw:
            model_name = model_raw
        choices = getattr(chunk, "choices", None)
        if isinstance(choices, list) and choices:
            piece = _delta_text(choices[0])
            if piece:
                content_parts.append(piece)
        usage = getattr(chunk, "usage", None)
        if usage is not None:
            prompt_tokens_raw = getattr(usage, "prompt_tokens", None)
            completion_tokens_raw = getattr(usage, "completion_tokens", None)
            if isinstance(prompt_tokens_raw, int):
                prompt_tokens = prompt_tokens_raw
            if isinstance(completion_tokens_raw, int):
                completion_tokens = completion_tokens_raw

    content_raw = "".join(content_parts).strip()
    if not content_raw:
        raise LLMResponseFormatError("Provider stream returned empty content.")
    return ProviderRawResponse(
        content_raw=content_raw,
        model_name=model_name or requested_model,
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
    )


def _redact_deployment_model_ids(value: str) -> str:
    return _DEPLOYMENT_MODEL_ID_RE.sub("[REDACTED_MODEL_ID]", value)


def _redact_model_id(model: str) -> str:
    if _DEPLOYMENT_MODEL_ID_RE.fullmatch(model):
        return "[REDACTED_MODEL_ID]"
    return model


def _request_error_message(provider_name: str, model: str, exc: Exception) -> str:
    return _redact_deployment_model_ids(
        f"{provider_name} request failed for model={_redact_model_id(model)}: {exc}"
    )


def _status_error_body_snippet(exc: APIStatusError, *, limit: int = 200) -> str | None:
    body = getattr(exc, "body", None)
    if body is None:
        return None
    body_text = str(body)
    redacted = _redact_deployment_model_ids(body_text)
    return redacted[:limit] if redacted else None


def _response_header(exc: Exception, name: str) -> str | None:
    response = getattr(exc, "response", None)
    headers = getattr(response, "headers", None)
    if headers is None:
        return None
    value = headers.get(name)
    return str(value) if value is not None else None


def _parse_retry_after_seconds(exc: Exception) -> float | None:
    retry_after_ms = _response_header(exc, "retry-after-ms")
    if retry_after_ms is not None:
        try:
            parsed_ms = float(retry_after_ms)
        except ValueError:
            parsed_ms = -1.0
        if parsed_ms >= 0:
            return parsed_ms / 1000.0

    retry_after = _response_header(exc, "retry-after")
    if retry_after is None:
        return None
    try:
        parsed_seconds = float(retry_after)
    except ValueError:
        parsed_seconds = -1.0
    if parsed_seconds >= 0:
        return parsed_seconds

    try:
        retry_at = parsedate_to_datetime(retry_after)
    except (TypeError, ValueError):
        return None
    if retry_at.tzinfo is None:
        retry_at = retry_at.replace(tzinfo=timezone.utc)
    return max(0.0, (retry_at - datetime.now(timezone.utc)).total_seconds())


def _classify_status_error(exc: APIStatusError) -> tuple[str, bool, int | None, float | None]:
    status_code_raw = getattr(exc, "status_code", None)
    status_code = status_code_raw if isinstance(status_code_raw, int) else None
    if status_code == 429:
        return "rate_limit", True, status_code, _parse_retry_after_seconds(exc)
    if status_code is not None and status_code >= 500:
        return "http_5xx", True, status_code, None
    return "http_4xx", False, status_code, None


def _classify_transport_error(exc: Exception) -> tuple[str, bool, int | None, float | None]:
    if isinstance(exc, RateLimitError):
        return "rate_limit", True, 429, _parse_retry_after_seconds(exc)
    if isinstance(exc, (APITimeoutError, httpx.TimeoutException)):
        return "timeout", True, None, None
    return "connection", True, None, None


def _raise_request_error(
    *,
    provider_name: str,
    model: str,
    exc: Exception,
    error_class: str,
    retryable: bool,
    http_status: int | None,
    retry_after_seconds: float | None,
) -> None:
    raise LLMRequestError(
        _request_error_message(provider_name, model, exc),
        retryable=retryable,
        http_status=http_status,
        retry_after_seconds=retry_after_seconds,
        error_class=error_class,
    ) from exc


def _should_retry_without_json_mode(exc: APIStatusError) -> bool:
    status_code = getattr(exc, "status_code", None)
    if status_code not in {400, 422}:
        return False
    if _is_json_mode_unsupported(exc):
        return True
    # Doubao and some compatible APIs return generic 400 for json_object without
    # a descriptive message — still worth one retry without response_format.
    return True


def _is_json_mode_unsupported(exc: APIStatusError) -> bool:
    status_code = getattr(exc, "status_code", None)
    if status_code not in {400, 422}:
        return False

    message = str(exc).lower()
    return (
        "response_format" in message
        and "json_object" in message
        and ("not supported" in message or "invalidparameter" in message or "unsupported" in message)
    )


def _is_stream_options_unsupported(exc: APIStatusError) -> bool:
    status_code = getattr(exc, "status_code", None)
    if status_code not in {400, 422}:
        return False
    body_snippet = _status_error_body_snippet(exc) or ""
    message = f"{exc} {body_snippet}".lower()
    return "stream_options" in message or "stream options" in message or "include_usage" in message


async def _create_completion(
    *,
    client: AsyncOpenAI,
    provider_name: str,
    model: str,
    system_prompt: str,
    user_prompt: str,
    timeout_seconds: int,
    max_tokens: int | None,
    use_json_mode: bool,
    extra_body: dict[str, Any] | None = None,
) -> ProviderRawResponse:
    kwargs: dict[str, Any] = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "timeout": httpx.Timeout(
            connect=float(settings.LLM_CONNECT_TIMEOUT_SECONDS),
            read=float(timeout_seconds),
            write=10.0,
            pool=5.0,
        ),
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    if max_tokens is not None:
        kwargs["max_tokens"] = max_tokens
    if use_json_mode:
        kwargs["response_format"] = {"type": "json_object"}
    if extra_body:
        kwargs["extra_body"] = extra_body
    try:
        stream = await client.chat.completions.create(**kwargs)
    except APIStatusError as exc:
        if not _is_stream_options_unsupported(exc):
            raise
        kwargs.pop("stream_options", None)
        log.info(
            "llm.call.stream_options_fallback",
            provider=provider_name,
            model=_redact_model_id(model),
            http_status=getattr(exc, "status_code", None),
            error_preview=_status_error_body_snippet(exc)
            or _redact_deployment_model_ids(str(exc))[:200],
        )
        stream = await client.chat.completions.create(**kwargs)
    return await _consume_stream(stream=stream, requested_model=model)


class _OpenAICompatibleProvider:
    name: ClassVar[str] = "unknown"

    def __init__(self, *, base_url: str, api_key: str, default_model: str) -> None:
        self.default_model = default_model
        self._client = AsyncOpenAI(
            base_url=base_url,
            api_key=api_key,
        )

    def _request_extra_body(self) -> dict[str, Any] | None:
        return None

    async def complete_json(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        model: str,
        timeout_seconds: int,
        max_tokens: int | None = None,
    ) -> ProviderRawResponse:
        use_json_mode = _resolve_json_mode(provider_name=self.name, model=model)
        extra_body = self._request_extra_body()
        try:
            response = await _create_completion(
                client=self._client,
                provider_name=self.name,
                model=model,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                timeout_seconds=timeout_seconds,
                max_tokens=max_tokens,
                use_json_mode=use_json_mode,
                extra_body=extra_body,
            )
        except APIStatusError as exc:
            if use_json_mode and _should_retry_without_json_mode(exc):
                _remember_json_mode_unsupported(provider_name=self.name, model=model)
                _log_json_mode_fallback(
                    provider=self.name,
                    model=model,
                    http_status=getattr(exc, "status_code", None),
                    error_preview=_redact_deployment_model_ids(str(exc))[:200],
                )
                try:
                    response = await _create_completion(
                        client=self._client,
                        provider_name=self.name,
                        model=model,
                        system_prompt=system_prompt,
                        user_prompt=user_prompt,
                        timeout_seconds=timeout_seconds,
                        max_tokens=max_tokens,
                        use_json_mode=False,
                        extra_body=extra_body,
                    )
                except APIStatusError as fallback_exc:
                    error_class, retryable, http_status, retry_after_seconds = _classify_status_error(
                        fallback_exc
                    )
                    log.warning(
                        "llm.provider.error",
                        provider=self.name,
                        model=_redact_model_id(model),
                        error_class=error_class,
                        http_status=http_status,
                        retryable=retryable,
                        attempt=2,
                        error_preview=_request_error_message(self.name, model, fallback_exc)[:200],
                    )
                    _raise_request_error(
                        provider_name=self.name,
                        model=model,
                        exc=fallback_exc,
                        error_class=error_class,
                        retryable=retryable,
                        http_status=http_status,
                        retry_after_seconds=retry_after_seconds,
                    )
                except (APIConnectionError, APITimeoutError, RateLimitError, httpx.TransportError) as fallback_exc:
                    error_class, retryable, http_status, retry_after_seconds = _classify_transport_error(
                        fallback_exc
                    )
                    log.warning(
                        "llm.provider.error",
                        provider=self.name,
                        model=_redact_model_id(model),
                        error_class=error_class,
                        http_status=http_status,
                        retryable=retryable,
                        attempt=2,
                        error_preview=_request_error_message(self.name, model, fallback_exc)[:200],
                    )
                    _raise_request_error(
                        provider_name=self.name,
                        model=model,
                        exc=fallback_exc,
                        error_class=error_class,
                        retryable=retryable,
                        http_status=http_status,
                        retry_after_seconds=retry_after_seconds,
                    )
            else:
                error_class, retryable, http_status, retry_after_seconds = _classify_status_error(exc)
                log.warning(
                    "llm.provider.error",
                    provider=self.name,
                    model=_redact_model_id(model),
                    error_class=error_class,
                    http_status=http_status,
                    retryable=retryable,
                    attempt=1,
                    error_preview=_status_error_body_snippet(exc)
                    or _redact_deployment_model_ids(str(exc))[:200],
                )
                _raise_request_error(
                    provider_name=self.name,
                    model=model,
                    exc=exc,
                    error_class=error_class,
                    retryable=retryable,
                    http_status=http_status,
                    retry_after_seconds=retry_after_seconds,
                )
        # Streaming iteration surfaces raw httpx transport errors (e.g. a peer
        # that closes the connection mid-stream -> RemoteProtocolError, the
        # symptom DashScope shows on long generations). The OpenAI SDK does NOT
        # wrap these into APIConnectionError; catch httpx.TransportError too so a
        # mid-stream drop degrades to fallback instead of crashing the node.
        except (APIConnectionError, APITimeoutError, RateLimitError, httpx.TransportError) as exc:
            error_class, retryable, http_status, retry_after_seconds = _classify_transport_error(exc)
            log.warning(
                "llm.provider.error",
                provider=self.name,
                model=_redact_model_id(model),
                error_class=error_class,
                http_status=http_status,
                retryable=retryable,
                attempt=1,
                error_preview=_request_error_message(self.name, model, exc)[:200],
            )
            _raise_request_error(
                provider_name=self.name,
                model=model,
                exc=exc,
                error_class=error_class,
                retryable=retryable,
                http_status=http_status,
                retry_after_seconds=retry_after_seconds,
            )

        return response


class DoubaoProvider(_OpenAICompatibleProvider):
    name: ClassVar[str] = "doubao"


class OpenAIProvider(_OpenAICompatibleProvider):
    name: ClassVar[str] = "openai"


class QwenProvider(_OpenAICompatibleProvider):
    name: ClassVar[str] = "qwen"

    def _request_extra_body(self) -> dict[str, Any] | None:
        # DashScope hybrid models (qwen3.x-plus/max) default thinking ON, which
        # streams reasoning tokens we neither parse nor want billed. Force it off;
        # the structured path streams output tokens only.
        return {"enable_thinking": False}


def _clean_optional_string(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = value.strip()
    return cleaned or None


def _provider_default_model(provider_name: str) -> str | None:
    if provider_name == "doubao":
        return _clean_optional_string(settings.DOUBAO_MODEL_BALANCED) or _clean_optional_string(
            settings.DOUBAO_EP
        )
    if provider_name == "openai":
        return _clean_optional_string(settings.OPENAI_MODEL_BALANCED) or _clean_optional_string(
            settings.OPENAI_DEFAULT_MODEL
        )
    if provider_name == "qwen":
        return _clean_optional_string(settings.QWEN_MODEL_BALANCED) or _clean_optional_string(
            settings.QWEN_DEFAULT_MODEL
        )
    return None


def _configured_provider_names() -> set[str]:
    provider_names = {settings.LLM_ACTIVE_PROVIDER}
    for value in (
        settings.LLM_PROVIDER_RESEARCH,
        settings.LLM_PROVIDER_SUMMARIZATION,
        settings.LLM_PROVIDER_COMPRESSION,
        settings.LLM_PROVIDER_QA,
        settings.LLM_PROVIDER_WRITER,
    ):
        provider_name = _clean_optional_string(value)
        if provider_name is not None:
            provider_names.add(provider_name.lower())
    return provider_names


def build_providers() -> dict[str, LLMProvider]:
    providers: dict[str, LLMProvider] = {}
    configured_names = _configured_provider_names()

    if "doubao" in configured_names:
        if not settings.DOUBAO_API_KEY:
            raise RuntimeError("DOUBAO_API_KEY is required when any slot uses doubao provider.")
        default_model = _provider_default_model("doubao")
        if default_model is None:
            raise RuntimeError(
                "DOUBAO_MODEL_BALANCED or DOUBAO_EP is required when any slot uses doubao provider."
            )
        providers["doubao"] = DoubaoProvider(
            base_url=settings.DOUBAO_BASE_URL,
            api_key=settings.DOUBAO_API_KEY,
            default_model=default_model,
        )

    if "openai" in configured_names:
        if not settings.OPENAI_API_KEY:
            raise RuntimeError("OPENAI_API_KEY is required when any slot uses openai provider.")
        default_model = _provider_default_model("openai")
        if default_model is None:
            raise RuntimeError(
                "OPENAI_MODEL_BALANCED or OPENAI_DEFAULT_MODEL is required when any slot uses openai provider."
            )
        providers["openai"] = OpenAIProvider(
            base_url=settings.OPENAI_BASE_URL,
            api_key=settings.OPENAI_API_KEY,
            default_model=default_model,
        )

    if "qwen" in configured_names:
        if not settings.QWEN_API_KEY:
            raise RuntimeError("QWEN_API_KEY is required when any slot uses qwen provider.")
        default_model = _provider_default_model("qwen")
        if default_model is None:
            raise RuntimeError(
                "QWEN_MODEL_BALANCED or QWEN_DEFAULT_MODEL is required when any slot uses qwen provider."
            )
        providers["qwen"] = QwenProvider(
            base_url=settings.QWEN_BASE_URL,
            api_key=settings.QWEN_API_KEY,
            default_model=default_model,
        )

    return providers
