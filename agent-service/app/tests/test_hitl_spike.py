"""Phase 0a HITL spike: lock down LangGraph interrupt/resume invariants on the real stack.

This is a pre-implementation spike for the Agent-native intake + plan-then-execute work.
It encodes the 6 invariants from the plan as executable assertions so the actual
intake_node / planner_node implementation has a verified API contract to build on.

Empirically discovered facts on langgraph==0.2.50 (do not assume, they were measured):

- `interrupt(value)` exists in `langgraph.types`; resume via `Command(resume=value)`.
- The interrupt payload is NOT returned under an `__interrupt__` key on this version.
  It must be read from `(await graph.aget_state(cfg)).tasks[0].interrupts[0].value`.
  This is the canonical extraction used by Invariant D (sync-until-first-interrupt).
- Code BEFORE `interrupt()` re-executes on resume. A single node that both calls the
  LLM and then interrupts will call the LLM twice. The fix is a two-node split:
  a generate node (commits state, runs once) feeding a wait node (only interrupts).
"""

from __future__ import annotations

import uuid
from typing import Any, Callable, TypedDict

import pytest
from langgraph.checkpoint.memory import MemorySaver
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt
from sqlalchemy import create_engine, text

from core.config import settings


class _SpikeState(TypedDict, total=False):
    phase: str
    pending_clarify: dict[str, Any]
    intake_draft: dict[str, Any]
    answer: str


def _extract_first_interrupt_value(snapshot: Any) -> Any:
    """Canonical interrupt-payload extraction for langgraph 0.2.50 (Invariant D)."""
    for task in snapshot.tasks:
        if task.interrupts:
            return task.interrupts[0].value
    return None


def test_invariant_a_naive_single_node_double_calls() -> None:
    """Invariant A trap: LLM/side-effect work placed before interrupt() runs twice.

    Locks the knowledge that the naive pattern is broken, so nobody "simplifies"
    the two-node split back into one node later.
    """
    call_counter = {"generate": 0}

    def naive_node(state: _SpikeState) -> _SpikeState:
        # Simulates an LLM call + event emit happening before the pause.
        call_counter["generate"] += 1
        reply = interrupt({"q": "enter x"})
        return {"answer": str(reply)}

    graph = StateGraph(_SpikeState)
    graph.add_node("naive_node", naive_node)
    graph.add_edge(START, "naive_node")
    graph.add_edge("naive_node", END)
    app = graph.compile(checkpointer=MemorySaver())

    cfg = {"configurable": {"thread_id": "spike_naive"}}
    app.invoke({"phase": "intake"}, config=cfg)
    app.invoke(Command(resume="answer-42"), config=cfg)

    assert call_counter["generate"] == 2, (
        "Expected the naive single-node pattern to double-call on resume. "
        "If this is 1, the langgraph version changed semantics and the plan can be simplified."
    )


def _build_two_node_intake_graph(
    *,
    on_generate: Callable[[], None],
) -> StateGraph:
    """Two-node split: generate node commits once, wait node only interrupts (Invariant A fix)."""

    def generate_clarify(state: _SpikeState) -> _SpikeState:
        # Expensive LLM call + side-effecting emit live here. This node fully
        # completes and commits to the checkpoint, so it never re-runs on resume.
        on_generate()
        return {"pending_clarify": {"q": "enter x"}}

    def wait_for_reply(state: _SpikeState) -> _SpikeState:
        pending = state.get("pending_clarify")
        reply = interrupt(pending)  # no side effects before this line
        draft = dict(state.get("intake_draft") or {})
        draft["answer"] = str(reply)
        return {"answer": str(reply), "intake_draft": draft, "pending_clarify": None}

    graph = StateGraph(_SpikeState)
    graph.add_node("generate_clarify", generate_clarify)
    graph.add_node("wait_for_reply", wait_for_reply)
    graph.add_edge("generate_clarify", "wait_for_reply")
    graph.add_edge("wait_for_reply", END)
    return graph


def test_invariant_a_two_node_split_single_call() -> None:
    """Invariant A fix: two-node split keeps the expensive node at exactly one execution."""
    call_counter = {"generate": 0}

    def on_generate() -> None:
        call_counter["generate"] += 1

    graph = _build_two_node_intake_graph(on_generate=on_generate)
    graph.add_edge(START, "generate_clarify")
    app = graph.compile(checkpointer=MemorySaver())

    cfg = {"configurable": {"thread_id": "spike_split"}}
    app.invoke({"phase": "intake"}, config=cfg)
    snapshot = app.get_state(cfg)
    assert _extract_first_interrupt_value(snapshot) == {"q": "enter x"}

    result = app.invoke(Command(resume="answer-42"), config=cfg)

    assert call_counter["generate"] == 1, "Two-node split must call the generate node exactly once."
    assert result["answer"] == "answer-42"
    assert result["intake_draft"] == {"answer": "answer-42"}


def test_invariant_b_conditional_entry_routes_by_phase() -> None:
    """Invariant B: dynamic entry routing via add_conditional_edges(START, ...) dispatches by phase."""
    intake_calls = {"n": 0}

    def intake_marker(state: _SpikeState) -> _SpikeState:
        intake_calls["n"] += 1
        return {"answer": "intake-path"}

    def execute_marker(state: _SpikeState) -> _SpikeState:
        return {"answer": "execute-path"}

    def route_entry(state: _SpikeState) -> str:
        return "intake_marker" if state.get("phase") == "intake" else "execute_marker"

    graph = StateGraph(_SpikeState)
    graph.add_node("intake_marker", intake_marker)
    graph.add_node("execute_marker", execute_marker)
    graph.add_conditional_edges(
        START,
        route_entry,
        {"intake_marker": "intake_marker", "execute_marker": "execute_marker"},
    )
    graph.add_edge("intake_marker", END)
    graph.add_edge("execute_marker", END)
    app = graph.compile(checkpointer=MemorySaver())

    intake_result = app.invoke({"phase": "intake"}, config={"configurable": {"thread_id": "spike_b_intake"}})
    execute_result = app.invoke({"phase": "executing"}, config={"configurable": {"thread_id": "spike_b_exec"}})

    assert intake_result["answer"] == "intake-path"
    assert execute_result["answer"] == "execute-path"
    assert intake_calls["n"] == 1, "Execute-phase entry must not touch the intake node."


def _cleanup_checkpoint_rows(thread_id: str) -> None:
    """Best-effort removal of spike checkpoint rows; keyed by a unique per-test thread_id."""
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            for table in ("checkpoint_writes", "checkpoint_blobs", "checkpoints"):
                connection.execute(
                    text(f"DELETE FROM {table} WHERE thread_id = :thread_id"),
                    {"thread_id": thread_id},
                )
    finally:
        engine.dispose()


@pytest.mark.asyncio
async def test_invariant_c_e_postgres_resume_across_fresh_checkpointer() -> None:
    """Invariants C + E: interrupt state persists to PG and resumes from a fresh checkpointer.

    Why this single test discharges the idle-connection risk (Invariant E):
    the interrupt is produced by checkpointer instance #1, whose connection is then
    fully closed (simulating worker death or a dropped idle connection). A brand-new
    checkpointer instance #2 (fresh connection) then resumes the same thread_id purely
    from PostgreSQL state. If a fresh connection can always resume, a stale idle
    connection is never on the critical path.

    It simultaneously re-confirms Invariant A under real persistence: the generate
    node ran during instance #1 and must NOT re-run during the instance #2 resume.
    """
    dsn = settings.LANGGRAPH_CHECKPOINT_DSN
    assert dsn is not None, "LANGGRAPH_CHECKPOINT_DSN must be configured for the spike."

    thread_id = f"spike_pg_{uuid.uuid4().hex[:8]}"
    cfg = {"configurable": {"thread_id": thread_id}}
    call_counter = {"generate": 0}

    def on_generate() -> None:
        call_counter["generate"] += 1

    def compile_with(checkpointer: AsyncPostgresSaver) -> Any:
        graph = _build_two_node_intake_graph(on_generate=on_generate)
        graph.add_edge(START, "generate_clarify")
        return graph.compile(checkpointer=checkpointer)

    try:
        # Instance #1: run until the interrupt, then drop the connection.
        async with AsyncPostgresSaver.from_conn_string(dsn) as checkpointer_1:
            app_1 = compile_with(checkpointer_1)
            await app_1.ainvoke({"phase": "intake"}, config=cfg)
            snapshot = await app_1.aget_state(cfg)
            assert snapshot.next == ("wait_for_reply",), "Graph must be paused at the wait node."
            assert _extract_first_interrupt_value(snapshot) == {"q": "enter x"}

        # Instance #2: a fresh connection resumes purely from persisted PG state.
        async with AsyncPostgresSaver.from_conn_string(dsn) as checkpointer_2:
            app_2 = compile_with(checkpointer_2)
            result = await app_2.ainvoke(Command(resume="answer-42"), config=cfg)
            assert result["answer"] == "answer-42"

            final_snapshot = await app_2.aget_state(cfg)
            assert final_snapshot.next == (), "Graph must reach END after resume."

        assert call_counter["generate"] == 1, (
            "Generate node must run exactly once across both checkpointer instances "
            "(Invariant A holds under real persistence)."
        )
    finally:
        _cleanup_checkpoint_rows(thread_id)
