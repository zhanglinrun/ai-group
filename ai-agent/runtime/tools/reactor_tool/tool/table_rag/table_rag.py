"""Legacy table reasoning is not executed in the deterministic Python worker."""

from __future__ import annotations


class TableRAGAgent:
    async def run(self, *args, **kwargs):
        return []
