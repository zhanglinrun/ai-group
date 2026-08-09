from __future__ import annotations

import json
import re
from typing import Any

TRACE_TEXT_LIMIT = 20_000
_TRUNCATED_SUFFIX = "\n...[truncated]"

_SECRET_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"sk-[A-Za-z0-9][A-Za-z0-9_-]{8,}"), "sk-[REDACTED]"),
    (re.compile(r"Bearer\s+[A-Za-z0-9._~+/=-]+", re.IGNORECASE), "Bearer [REDACTED]"),
    (
        re.compile(
            r"(?i)\b(api[_-]?key|password|token|secret|authorization)\b\s*[:=]\s*([\"']?)[^\s,\"'}]+"
        ),
        r"\1=[REDACTED]",
    ),
)


def redact_trace_text(value: str) -> str:
    redacted = value
    for pattern, replacement in _SECRET_PATTERNS:
        redacted = pattern.sub(replacement, redacted)
    return redacted


def truncate_trace_text(value: str, limit: int = TRACE_TEXT_LIMIT) -> str:
    if len(value) <= limit:
        return value
    suffix_budget = max(limit - len(_TRUNCATED_SUFFIX), 0)
    return value[:suffix_budget] + _TRUNCATED_SUFFIX


def sanitize_trace_text(value: str | None, *, limit: int = TRACE_TEXT_LIMIT) -> str | None:
    if value is None:
        return None
    return truncate_trace_text(redact_trace_text(value), limit=limit)


def build_prompt_trace_text(*, system_prompt: str, user_prompt: str) -> str:
    raw = f"[system]\n{system_prompt}\n\n[user]\n{user_prompt}".strip()
    return truncate_trace_text(redact_trace_text(raw))


def build_prompt_preview(prompt_text: str, *, limit: int = 256) -> str:
    return prompt_text.replace("\n", "\\n")[:limit]


def serialize_response_content(
    content: dict[str, object],
    *,
    limit: int = TRACE_TEXT_LIMIT,
) -> dict[str, object]:
    serialized = json.dumps(content, ensure_ascii=False, default=str)
    sanitized = truncate_trace_text(redact_trace_text(serialized), limit=limit)
    try:
        parsed = json.loads(sanitized)
    except json.JSONDecodeError:
        return {"_serialized": sanitized, "_truncated": len(serialized) > limit}
    return parsed if isinstance(parsed, dict) else {"_serialized": sanitized}


def response_content_from_unknown(value: Any) -> dict[str, object] | None:
    if isinstance(value, dict):
        return serialize_response_content(value)
    return None
