from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(slots=True)
class ProviderRawResponse:
    content_raw: str
    model_name: str
    prompt_tokens: int | None
    completion_tokens: int | None


@dataclass(slots=True)
class LLMResponse:
    model_slot: str
    provider: str
    model_name: str | None
    prompt_preview: str
    prompt_hash: str
    content: dict[str, Any]
    prompt_tokens: int | None
    completion_tokens: int | None
    latency_ms: int | None
    error: str | None
    retry_count: int = 0
    fallback_used: bool = False
    fallback_reason: str | None = None
    prompt_text: str | None = None
    response_raw: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "model_slot": self.model_slot,
            "provider": self.provider,
            "model_name": self.model_name,
            "prompt_preview": self.prompt_preview,
            "prompt_hash": self.prompt_hash,
            "content": self.content,
            "prompt_tokens": self.prompt_tokens,
            "completion_tokens": self.completion_tokens,
            "latency_ms": self.latency_ms,
            "error": self.error,
            "retry_count": self.retry_count,
            "fallback_used": self.fallback_used,
            "fallback_reason": self.fallback_reason,
            "prompt_text": self.prompt_text,
            "response_raw": self.response_raw,
        }

    def get(self, key: str, default: Any = None) -> Any:
        return self.to_dict().get(key, default)

    def __getitem__(self, key: str) -> Any:
        return self.to_dict()[key]
