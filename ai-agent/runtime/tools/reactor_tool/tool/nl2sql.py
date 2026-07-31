"""NL2SQL planning moved to the Java model boundary."""

from __future__ import annotations

import asyncio


class NL2SQLAgent:
    def __init__(self, queue: asyncio.Queue | None = None):
        self.queue = queue or asyncio.Queue()

    async def run_nl2sql(self, *args, **kwargs):
        return {"code": 501, "status": "MODEL_EXECUTION_MOVED_TO_JAVA", "data": []}
