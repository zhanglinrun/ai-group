from __future__ import annotations

import re
from dataclasses import dataclass

from service.prompt_safety.patterns import PROMPT_SAFETY_PATTERNS


@dataclass(frozen=True, slots=True)
class SanitizedResult:
    text: str
    hit_patterns: list[str]


def sanitize_text(text: str) -> SanitizedResult:
    if not isinstance(text, str):
        raise ValueError("sanitize_text requires a string input.")
    output = text
    hits: list[str] = []
    try:
        for pattern in PROMPT_SAFETY_PATTERNS:
            output, replaced_count = pattern.regex.subn(pattern.replacement, output)
            if replaced_count > 0 and pattern.name not in hits:
                hits.append(pattern.name)
    except re.error as exc:
        raise ValueError(f"invalid prompt safety regex: {exc}") from exc
    return SanitizedResult(text=output, hit_patterns=hits)
