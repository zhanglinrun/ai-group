"""Deterministic query normalization retained for compatibility with legacy callers."""

from __future__ import annotations


async def query_decompose(query: str, *args, **kwargs) -> list[str]:
    normalized = (query or "").strip()
    return [normalized] if normalized else []
