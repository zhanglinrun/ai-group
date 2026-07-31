"""The deterministic worker does not judge evidence completeness."""

from __future__ import annotations


async def search_reasoning(*args, **kwargs) -> dict[str, int]:
    return {"is_verify": 1}
