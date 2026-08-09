from __future__ import annotations

import re

from service.desensitize.errors import DesensitizeError
from service.desensitize.patterns import DESENSITIZE_PATTERNS

_NUL = "\x00"


def normalize_text_for_storage(text: str) -> str:
    """Strip NUL bytes — PostgreSQL UTF-8 text/json rejects \\x00."""
    if _NUL not in text:
        return text
    return text.replace(_NUL, "")


def desensitize_text(text: str) -> str:
    if not isinstance(text, str):
        raise DesensitizeError("desensitize_text requires a string input.")
    output = normalize_text_for_storage(text)
    try:
        for pattern in DESENSITIZE_PATTERNS:
            output = pattern.regex.sub(pattern.replacement, output)
    except re.error as exc:
        raise DesensitizeError(f"invalid desensitize regex: {exc}") from exc
    return output
