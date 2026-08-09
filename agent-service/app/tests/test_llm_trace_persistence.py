from __future__ import annotations

from service.llm.records import build_llm_call_record, build_llm_call_record_from_mapping
from service.llm.response import LLMResponse


def test_build_llm_call_record_persists_redacted_trace_fields() -> None:
    response = LLMResponse(
        model_slot="research",
        provider="fake",
        model_name="fake-model",
        prompt_preview="[system]\\napi_key=[REDACTED]",
        prompt_hash="hash",
        content={"answer": "api_key=raw-secret"},
        prompt_tokens=3,
        completion_tokens=4,
        latency_ms=12,
        error=None,
        retry_count=2,
        fallback_used=True,
        fallback_reason="token=raw-token",
        prompt_text="[system]\napi_key=[REDACTED]",
        response_raw='{"answer":"api_key=[REDACTED]"}',
    )

    row = build_llm_call_record(step_id="step_1", response=response)

    assert row.step_id == "step_1"
    assert row.prompt_text == "[system]\napi_key=[REDACTED]"
    assert row.prompt_preview == "[system]\\napi_key=[REDACTED]"
    assert row.response_raw == '{"answer":"api_key=[REDACTED]"}'
    assert row.response_content == {"answer": "api_key=[REDACTED]"}
    assert row.retry_count == 2
    assert row.fallback_used is True
    assert row.fallback_reason == "token=[REDACTED]"


def test_build_llm_call_record_from_mapping_preserves_subgraph_trace() -> None:
    row = build_llm_call_record_from_mapping(
        step_id="step_2",
        item={
            "model_slot": "compression",
            "provider": "fake",
            "model_name": "fake-model",
            "prompt_hash": "hash",
            "prompt_text": "[user]\npassword=[REDACTED]",
            "prompt_preview": "[user]\\npassword=[REDACTED]",
            "content": {"summary": "secret=raw-secret"},
            "response_raw": '{"summary":"secret=[REDACTED]"}',
            "prompt_tokens": 1,
            "completion_tokens": 2,
            "latency_ms": 3,
            "retry_count": 1,
            "fallback_used": False,
        },
    )

    assert row is not None
    assert row.step_id == "step_2"
    assert row.response_content == {"summary": "secret=[REDACTED]"}
    assert row.response_raw == '{"summary":"secret=[REDACTED]"}'
    assert row.retry_count == 1
    assert row.fallback_used is False
