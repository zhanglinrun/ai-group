from __future__ import annotations

from typing import Any

import pytest
from langgraph.errors import GraphInterrupt

from agents.state import AgentState
from utils.log_node import log_node


@log_node("intake_wait")
async def _node_that_pauses(state: AgentState) -> dict[str, Any]:
    raise GraphInterrupt("clarify")


@log_node("planner_generate")
async def _node_that_raises(state: AgentState) -> dict[str, Any]:
    raise ValueError("boom")


@pytest.mark.asyncio
async def test_log_node_records_pause_for_graph_interrupt(
    capsys: pytest.CaptureFixture[str],
) -> None:
    state: AgentState = {"run_id": "run_test_pause", "current_iteration": 0}
    with pytest.raises(GraphInterrupt):
        await _node_that_pauses(state)

    logged = capsys.readouterr().out
    assert "node.start" in logged
    assert "node.pause" in logged
    assert "GraphInterrupt" in logged
    assert "node.error" not in logged
    assert "node.finish" not in logged


@pytest.mark.asyncio
async def test_log_node_records_error_for_unexpected_exception(
    capsys: pytest.CaptureFixture[str],
) -> None:
    state: AgentState = {"run_id": "run_test_error", "current_iteration": 0}
    with pytest.raises(ValueError, match="boom"):
        await _node_that_raises(state)

    logged = capsys.readouterr().out
    assert "node.error" in logged
    assert "Traceback" not in logged
    assert "node.finish" not in logged
