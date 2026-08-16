"""Phase 1b intake integration test: real ai-group graph + AsyncPostgresSaver + Fake LLM.

This is the safety net for Phase 1b before any endpoint or DB column change:

- Verifies that `compile_graph()` with the new conditional entry routes phase="intake"
  to `intake_generate` (Invariant B) and falls back to `supervisor` otherwise.
- Verifies that the two-node split (intake_generate → intake_wait) makes intake_generate
  run *exactly once per turn* under real PostgreSQL persistence (Invariant A).
- Verifies that `(await graph.aget_state(cfg)).tasks[0].interrupts[0].value` returns the
  serialized IntakeClarifyRequest (Invariant D) — the same extraction the API endpoint will use.
- Verifies that after the IntakeAgent decides `complete`, the graph routes into the
  Phase 2 planner stage (paused at `planner_generate` via `interrupt_before` to keep
  this test scoped to intake; planner-specific behavior lives in test_plan_flow.py).
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any

import pytest
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.types import Command
from sqlalchemy import create_engine, select, text

from agents.graph import build_graph_uncompiled
from agents.state import AgentState
from core.config import settings
from db.engine import dispose_engine, get_session_factory, init_engine
from models.run import Run
from models.step import Step
from schemas.ids import make_id
from schemas.intake import IntakeUserReply, RunIntakeDraft


@pytest.fixture(autouse=True)
async def _isolate_async_engine() -> Any:
    # pytest-asyncio creates a fresh event loop per function-scoped test. The
    # async SQLAlchemy engine binds asyncpg connections to whatever loop initialized
    # it, so a cached engine from the previous test crashes with "loop is closed".
    yield
    await dispose_engine()


def _extract_first_interrupt_value(snapshot: Any) -> Any:
    for task in snapshot.tasks:
        if task.interrupts:
            return task.interrupts[0].value
    return None


async def _create_run_row(run_id: str, user_query: str) -> None:
    init_engine()
    session_factory = get_session_factory()
    async with session_factory() as session:
        session.add(
            Run(
                run_id=run_id,
                user_query=user_query,
                domain_hint=None,
                reference_urls=[],
                status="running",
                target_roles=[],
                competitors=[],
                started_at=datetime.now(timezone.utc),
            )
        )
        await session.commit()


async def _count_intake_steps(run_id: str) -> int:
    init_engine()
    session_factory = get_session_factory()
    async with session_factory() as session:
        result = await session.execute(
            select(Step).where(Step.run_id == run_id, Step.agent_name == "intake_agent")
        )
        return len(list(result.scalars()))


def _cleanup_run_and_checkpoint(run_id: str) -> None:
    engine = create_engine(settings.DATABASE_URL_SYNC)
    try:
        with engine.begin() as connection:
            connection.execute(
                text("DELETE FROM llm_calls WHERE step_id IN (SELECT step_id FROM steps WHERE run_id = :run_id)"),
                {"run_id": run_id},
            )
            connection.execute(text("DELETE FROM steps WHERE run_id = :run_id"), {"run_id": run_id})
            connection.execute(text("DELETE FROM runs WHERE run_id = :run_id"), {"run_id": run_id})
            for table in ("checkpoint_writes", "checkpoint_blobs", "checkpoints"):
                connection.execute(
                    text(f"DELETE FROM {table} WHERE thread_id = :thread_id"),
                    {"thread_id": run_id},
                )
    finally:
        engine.dispose()


@pytest.mark.asyncio
async def test_intake_flow_real_graph_postgres_resume() -> None:
    """End-to-end intake clarification flow on the real graph with PG checkpointer.

    Drives a 3-turn clarification (role → intent → competitors) and verifies the
    graph reaches the supervisor entry, with every intake_generate turn persisting
    exactly one Step row.
    """
    dsn = settings.LANGGRAPH_CHECKPOINT_DSN
    assert dsn is not None, "LANGGRAPH_CHECKPOINT_DSN must be configured."

    run_id = make_id("run_")
    cfg = {"configurable": {"thread_id": run_id}}
    await _create_run_row(run_id, "我想分析定价竞品")

    try:
        async with AsyncPostgresSaver.from_conn_string(dsn) as checkpointer:
            graph_builder = build_graph_uncompiled()
            # Stop before planner_generate AND supervisor so the test stays scoped
            # to intake invariants. Phase 2: intake.complete now routes through
            # planner_generate; halting before it keeps this test single-concern.
            app = graph_builder.compile(
                checkpointer=checkpointer,
                interrupt_before=["planner_generate", "supervisor"],
            )

            initial_state: AgentState = {
                "run_id": run_id,
                "user_query": "我想分析定价竞品",
                "phase": "intake",
                "intake_draft": RunIntakeDraft(user_query="我想分析定价竞品"),
                "intake_history": [],
                "pending_clarify": None,
            }
            await app.ainvoke(initial_state, config=cfg)

            # Turn 1: pause at intake_wait asking for user_role.
            snapshot = await app.aget_state(cfg)
            assert snapshot.next == ("intake_wait",), (
                f"Expected pause at intake_wait, got next={snapshot.next}"
            )
            clarify_1 = _extract_first_interrupt_value(snapshot)
            assert isinstance(clarify_1, dict)
            assert clarify_1["field_targets"] == ["user_role"]
            assert await _count_intake_steps(run_id) == 1

            # Resume turn 1 → pause at intake_wait asking for analysis_intent.
            reply_1 = IntakeUserReply(text="pm", selected_options=["pm"]).model_dump()
            await app.ainvoke(Command(resume=reply_1), config=cfg)
            snapshot = await app.aget_state(cfg)
            assert snapshot.next == ("intake_wait",)
            clarify_2 = _extract_first_interrupt_value(snapshot)
            assert clarify_2["field_targets"] == ["analysis_intent"]
            assert await _count_intake_steps(run_id) == 2

            # Resume turn 2 → pause at intake_wait asking for competitor path.
            reply_2 = IntakeUserReply(text="对比 Notion 和 Cursor 的定价策略").model_dump()
            await app.ainvoke(Command(resume=reply_2), config=cfg)
            snapshot = await app.aget_state(cfg)
            assert snapshot.next == ("intake_wait",)
            clarify_3 = _extract_first_interrupt_value(snapshot)
            assert set(clarify_3["field_targets"]) == {
                "competitors_explicit",
                "competitors_discovery_mode",
            }
            assert await _count_intake_steps(run_id) == 3

            # Resume turn 3 → intake completes and pauses at depth-selection gate.
            reply_3 = IntakeUserReply(
                text="Notion, Cursor",
                selected_options=["已有名单"],
            ).model_dump()
            await app.ainvoke(Command(resume=reply_3), config=cfg)
            snapshot = await app.aget_state(cfg)
            assert snapshot.next == ("planning_profile_wait",), (
                "After intake.complete the graph must wait for report_depth selection; "
                f"got next={snapshot.next}"
            )
            assert await _count_intake_steps(run_id) == 4

            # Resume depth selection → graph hands off to planner_generate.
            depth_reply = IntakeUserReply(
                text="",
                selected_options=["quick"],
            ).model_dump()
            await app.ainvoke(Command(resume=depth_reply), config=cfg)
            snapshot = await app.aget_state(cfg)
            assert snapshot.next == ("planner_generate",), (
                "After selecting report_depth, graph must hand off to planner_generate; "
                f"got next={snapshot.next}"
            )

            # Verify final intake_draft on the checkpoint matches what we expect.
            values = snapshot.values
            persisted_draft_raw = values.get("intake_draft")
            assert persisted_draft_raw is not None
            persisted_draft = (
                persisted_draft_raw
                if isinstance(persisted_draft_raw, RunIntakeDraft)
                else RunIntakeDraft.model_validate(persisted_draft_raw)
            )
            assert persisted_draft.user_role == "pm"
            assert persisted_draft.analysis_intent == "对比 Notion 和 Cursor 的定价策略"
            assert persisted_draft.competitors_explicit == ["Notion", "Cursor"]
            assert persisted_draft.response_language == "zh"
            assert persisted_draft.is_complete is True
            assert values.get("phase") == "planning"
            # History should record all three exchanges (clarify, reply) pairs.
            assert len(values.get("intake_history") or []) == 3
    finally:
        _cleanup_run_and_checkpoint(run_id)


@pytest.mark.asyncio
async def test_intake_skip_when_phase_not_intake_routes_to_supervisor() -> None:
    """Invariant B: legacy POST /api/runs path (no phase) must route straight to supervisor.

    Locks the backward-compat guarantee that the existing synchronous E2E callers
    (test_smoke.py / golden) are not regressed by the new conditional entry.
    """
    dsn = settings.LANGGRAPH_CHECKPOINT_DSN
    assert dsn is not None

    run_id = make_id("run_")
    cfg = {"configurable": {"thread_id": run_id}}
    await _create_run_row(run_id, "legacy run without intake")

    try:
        async with AsyncPostgresSaver.from_conn_string(dsn) as checkpointer:
            graph_builder = build_graph_uncompiled()
            app = graph_builder.compile(
                checkpointer=checkpointer,
                interrupt_before=["planner_generate", "supervisor"],
            )

            initial_state: AgentState = {
                "run_id": run_id,
                "user_query": "legacy run without intake",
                "competitors": [],
            }
            await app.ainvoke(initial_state, config=cfg)
            snapshot = await app.aget_state(cfg)
            assert snapshot.next == ("supervisor",), (
                f"Legacy entry (no phase) must pause before supervisor; got next={snapshot.next}"
            )
            assert await _count_intake_steps(run_id) == 0
    finally:
        _cleanup_run_and_checkpoint(run_id)
