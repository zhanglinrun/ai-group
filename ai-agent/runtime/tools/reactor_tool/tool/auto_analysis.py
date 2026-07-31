"""Automated analysis moved to the Java Harness model boundary."""

from __future__ import annotations


class AutoAnalysisAgent:
    async def run(self, *args, **kwargs):
        raise RuntimeError("auto_analysis is unavailable in the deterministic Python data plane")
