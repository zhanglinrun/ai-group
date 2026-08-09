from __future__ import annotations

from typing import Any

from models.llm_call import LLMCall
from service.llm.response import LLMResponse
from service.llm.trace import response_content_from_unknown, sanitize_trace_text
from service.billing import charge_micro_points
from core.config import settings


def _trim_error(error: str | None) -> str | None:
    return error[:2000] if error is not None else None


def build_llm_call_record(
    *,
    step_id: str,
    response: LLMResponse,
    error: str | None = None,
) -> LLMCall:
    llm_error = error if error is not None else response.error
    return LLMCall(
        step_id=step_id,
        model_slot=response.model_slot,
        provider=response.provider,
        model_name=response.model_name,
        prompt_hash=response.prompt_hash,
        prompt_text=response.prompt_text,
        response_content=response_content_from_unknown(response.content),
        response_raw=response.response_raw,
        prompt_preview=response.prompt_preview,
        prompt_tokens=response.prompt_tokens,
        completion_tokens=response.completion_tokens,
        charged_micro_points=charge_micro_points(response.prompt_tokens, response.completion_tokens),
        price_version=settings.BILLING_PRICE_VERSION,
        latency_ms=response.latency_ms,
        error=_trim_error(llm_error),
        retry_count=response.retry_count,
        fallback_used=response.fallback_used,
        fallback_reason=sanitize_trace_text(response.fallback_reason, limit=2000),
    )


def build_llm_call_record_from_mapping(
    *,
    step_id: str,
    item: dict[str, Any],
) -> LLMCall | None:
    model_slot_raw = item.get("model_slot")
    if not isinstance(model_slot_raw, str):
        return None
    provider_raw = item.get("provider")
    model_name_raw = item.get("model_name")
    prompt_hash_raw = item.get("prompt_hash")
    prompt_text_raw = item.get("prompt_text")
    response_raw_raw = item.get("response_raw")
    prompt_preview_raw = item.get("prompt_preview")
    prompt_tokens_raw = item.get("prompt_tokens")
    completion_tokens_raw = item.get("completion_tokens")
    latency_ms_raw = item.get("latency_ms")
    error_raw = item.get("error")
    fallback_used_raw = item.get("fallback_used")
    fallback_reason_raw = item.get("fallback_reason")
    retry_count_raw = item.get("retry_count")
    return LLMCall(
        step_id=step_id,
        model_slot=model_slot_raw,
        provider=provider_raw if isinstance(provider_raw, str) else None,
        model_name=model_name_raw if isinstance(model_name_raw, str) else None,
        prompt_hash=prompt_hash_raw if isinstance(prompt_hash_raw, str) else None,
        prompt_text=prompt_text_raw if isinstance(prompt_text_raw, str) else None,
        response_content=response_content_from_unknown(item.get("content")),
        response_raw=response_raw_raw if isinstance(response_raw_raw, str) else None,
        prompt_preview=prompt_preview_raw if isinstance(prompt_preview_raw, str) else None,
        prompt_tokens=prompt_tokens_raw if isinstance(prompt_tokens_raw, int) else None,
        completion_tokens=completion_tokens_raw
        if isinstance(completion_tokens_raw, int)
        else None,
        charged_micro_points=charge_micro_points(
            prompt_tokens_raw if isinstance(prompt_tokens_raw, int) else None,
            completion_tokens_raw if isinstance(completion_tokens_raw, int) else None,
        ),
        price_version=settings.BILLING_PRICE_VERSION,
        latency_ms=latency_ms_raw if isinstance(latency_ms_raw, int) else None,
        error=error_raw[:2000] if isinstance(error_raw, str) else None,
        retry_count=retry_count_raw if isinstance(retry_count_raw, int) else 0,
        fallback_used=fallback_used_raw if isinstance(fallback_used_raw, bool) else None,
        fallback_reason=(
            sanitize_trace_text(fallback_reason_raw, limit=2000)
            if isinstance(fallback_reason_raw, str)
            else None
        ),
    )
