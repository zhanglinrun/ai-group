from __future__ import annotations

from abc import ABC, abstractmethod
from datetime import datetime, timezone
from typing import Any

from pydantic import BaseModel, Field, field_validator

from schemas.contracts import validate_source_type
from service.prompt_safety.sanitizer import sanitize_text

SourceType = str
KNOWN_SOURCE_TYPES: frozenset[str] = frozenset(
    {
        "official_site",
        "docs",
        "official_doc",
        "pricing_page",
        "market_report",
        "public_review",
        "article",
        "local_note",
        "offline_snapshot",
    }
)


class CollectorSnippet(BaseModel):
    quote: str
    sanitized_text: str
    source_url: str | None = None
    source_title: str | None = None
    source_type: SourceType
    desensitized: bool
    metadata: dict[str, object] = Field(default_factory=dict)

    @field_validator("source_type")
    @classmethod
    def _validate_source_type(cls, value: str) -> str:
        return validate_source_type(value)


class ToolObservationResult(BaseModel):
    snippets: list[CollectorSnippet] = Field(default_factory=list)
    metadata: dict[str, object] = Field(default_factory=dict)


class CollectorObservation(BaseModel):
    channel: str
    args: dict[str, Any]
    result: ToolObservationResult


class BaseChannel(ABC):
    name: str

    @abstractmethod
    async def invoke(self, **kwargs: object) -> CollectorObservation:
        """Run channel logic and return normalized collector observations."""

    @staticmethod
    def _now_iso() -> str:
        return datetime.now(timezone.utc).isoformat()

    def _build_snippet(
        self,
        *,
        raw_text: str,
        source_type: SourceType,
        source_url: str | None,
        source_title: str | None,
        metadata: dict[str, object] | None = None,
    ) -> CollectorSnippet:
        from service.desensitize.engine import desensitize_text, normalize_text_for_storage

        desensitized_text = desensitize_text(raw_text)
        safety_result = sanitize_text(desensitized_text)
        snippet_metadata = dict(metadata or {})
        if safety_result.hit_patterns:
            snippet_metadata["prompt_safety_hit_patterns"] = list(safety_result.hit_patterns)
        snippet_metadata["retrieved_at"] = self._now_iso()
        snippet_metadata["desensitize_changed"] = desensitized_text != raw_text

        normalized_url = (
            normalize_text_for_storage(source_url) if isinstance(source_url, str) else None
        )
        normalized_title = (
            normalize_text_for_storage(source_title) if isinstance(source_title, str) else None
        )

        return CollectorSnippet(
            quote=safety_result.text,
            sanitized_text=safety_result.text,
            source_url=normalized_url,
            source_title=normalized_title,
            source_type=source_type,
            desensitized=True,
            metadata=snippet_metadata,
        )
