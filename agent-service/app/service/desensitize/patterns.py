from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Final


@dataclass(frozen=True, slots=True)
class DesensitizePattern:
    name: str
    regex: re.Pattern[str]
    replacement: str


DESENSITIZE_PATTERNS: Final[tuple[DesensitizePattern, ...]] = (
    DesensitizePattern(
        name="email",
        regex=re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
        replacement="[REDACTED_EMAIL]",
    ),
    DesensitizePattern(
        name="cn_mobile",
        regex=re.compile(r"(?<!\d)(?:\+?86[-\s]?)?1[3-9]\d[-\s]?\d{4}[-\s]?\d{4}(?!\d)"),
        replacement="[REDACTED_PHONE]",
    ),
    DesensitizePattern(
        name="cn_id_card",
        regex=re.compile(r"\b\d{17}[\dXx]\b"),
        replacement="[REDACTED_IDCARD]",
    ),
    DesensitizePattern(
        name="mention",
        regex=re.compile(r"@[A-Za-z0-9_\-\u4e00-\u9fff]{2,32}"),
        replacement="@REDACTED_USER",
    ),
    DesensitizePattern(
        name="avatar_url",
        regex=re.compile(r"https?://[^\s\"'<>]*(?:avatar|profile|head|userpic)[^\s\"'<>]*", re.IGNORECASE),
        replacement="[REDACTED_AVATAR_URL]",
    ),
    DesensitizePattern(
        name="bearer_token",
        regex=re.compile(r"Bearer\s+[A-Za-z0-9\-._~+/]+=*", re.IGNORECASE),
        replacement="Bearer [REDACTED_TOKEN]",
    ),
)
