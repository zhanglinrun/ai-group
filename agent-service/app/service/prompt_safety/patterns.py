from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Final


@dataclass(frozen=True, slots=True)
class PromptSafetyPattern:
    name: str
    regex: re.Pattern[str]
    replacement: str


PROMPT_SAFETY_PATTERNS: Final[tuple[PromptSafetyPattern, ...]] = (
    PromptSafetyPattern(
        name="ignore_previous",
        regex=re.compile(r"\bignore (all )?(previous|above) instructions?\b", re.IGNORECASE),
        replacement="[REDACTED_INSTRUCTION:ignore_previous]",
    ),
    PromptSafetyPattern(
        name="role_override",
        regex=re.compile(r"\byou are (now|an?) .*?(system|developer)\b", re.IGNORECASE),
        replacement="[REDACTED_INSTRUCTION:role_override]",
    ),
    PromptSafetyPattern(
        name="dan_mode",
        regex=re.compile(r"\b(DAN|do anything now)\b", re.IGNORECASE),
        replacement="[REDACTED_INSTRUCTION:dan_mode]",
    ),
    PromptSafetyPattern(
        name="developer_mode",
        regex=re.compile(r"\bdeveloper mode\b", re.IGNORECASE),
        replacement="[REDACTED_INSTRUCTION:developer_mode]",
    ),
    PromptSafetyPattern(
        name="system_prompt_leak",
        regex=re.compile(r"\bshow (me )?(the )?(system|developer) prompt\b", re.IGNORECASE),
        replacement="[REDACTED_INSTRUCTION:prompt_leak]",
    ),
    PromptSafetyPattern(
        name="tool_call_override",
        regex=re.compile(r"\b(function_call|tool_call|call_tool)\b", re.IGNORECASE),
        replacement="[REDACTED_INSTRUCTION:tool_override]",
    ),
    PromptSafetyPattern(
        name="xml_role_tags",
        regex=re.compile(r"</?(system|assistant|developer|user)>", re.IGNORECASE),
        replacement="[REDACTED_INSTRUCTION:xml_role]",
    ),
    PromptSafetyPattern(
        name="base64_payload",
        regex=re.compile(r"\b(base64|decode this)\b", re.IGNORECASE),
        replacement="[REDACTED_INSTRUCTION:base64_payload]",
    ),
    PromptSafetyPattern(
        name="safety_bypass",
        regex=re.compile(r"\b(bypass|override|disable) (all )?(safety|guardrails?|policies?)\b", re.IGNORECASE),
        replacement="[REDACTED_INSTRUCTION:safety_bypass]",
    ),
    PromptSafetyPattern(
        name="out_of_band",
        regex=re.compile(r"\b(out[- ]of[- ]band|oob|exfiltrate|send to external)\b", re.IGNORECASE),
        replacement="[REDACTED_INSTRUCTION:out_of_band]",
    ),
)
