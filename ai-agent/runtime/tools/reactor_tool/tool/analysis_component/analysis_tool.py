"""Legacy model-directed data analysis tools are intentionally unavailable in the deterministic Worker."""

from __future__ import annotations


class _UnavailableTool:
    def __init__(self, *args, **kwargs):
        pass

    def forward(self, *args, **kwargs):
        raise RuntimeError("model-directed analysis moved to Java")


GetDataTool = DataTransTool = InsightTool = SaveInsightTool = FinalAnswerTool = _UnavailableTool
