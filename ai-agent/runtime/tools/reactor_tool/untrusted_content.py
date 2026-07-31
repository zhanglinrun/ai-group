# -*- coding: utf-8 -*-
"""Risk-only classification for content fetched from outside the trust boundary."""

from __future__ import annotations

import re


UNTRUSTED_WEB_CONTENT_OPEN = "<<<UNTRUSTED_WEB_CONTENT>>>"
UNTRUSTED_WEB_CONTENT_CLOSE = "<<<END_UNTRUSTED_WEB_CONTENT>>>"

_INSTRUCTION_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("ignore_instructions", re.compile(r"\b(ignore|disregard|override)\b.{0,80}\b(instruction|prompt|rule)s?\b", re.I | re.S)),
    ("system_prompt_request", re.compile(r"\b(system prompt|developer message|hidden reasoning)\b", re.I)),
    ("credential_exfiltration", re.compile(r"\b(api key|secret|token|password)\b.{0,80}\b(send|upload|exfiltrate|reveal)\b", re.I | re.S)),
    ("ignore_instructions_zh", re.compile(r"忽略.{0,40}(指令|规则|提示)|无视.{0,40}(指令|规则|提示)")),
    ("secret_exfiltration_zh", re.compile(r"(系统提示|隐藏推理|密钥|令牌|密码).{0,40}(泄露|上传|外传|输出)")),
)


def detect_untrusted_content_risk_signals(content: str) -> list[str]:
    """Returns bounded labels only; detection never grants or denies a tool call by itself."""
    sample = (content or "")[:20_000]
    return [signal for signal, pattern in _INSTRUCTION_PATTERNS if pattern.search(sample)]


def wrap_untrusted_web_content(content: str) -> str:
    """Delimit browser-fetched text before it reaches an Agent-facing tool result."""
    escaped = (content or "").replace("<<<", "‹‹‹")
    return f"{UNTRUSTED_WEB_CONTENT_OPEN}\n{escaped}\n{UNTRUSTED_WEB_CONTENT_CLOSE}"
