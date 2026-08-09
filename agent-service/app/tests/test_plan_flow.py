"""Phase 2 planner integration test: real XiongDoctor graph + AsyncPostgresSaver + Fake LLM.

Locks the planner-stage invariants before the API endpoint depends on them:

- planner_generate runs *exactly once* per pause (Invariant A, two-node split).
- planner_generate persists Run.plan_tree before planner_wait interrupts, so the
  API can read the plan via GET /api/runs/{id} without poking graph state.
- The interrupt payload is shaped `{"kind": "plan_confirm", "plan_tree": {...}}`.
- Resume with a PlanConfirmRequest filters disabled tasks, bumps version, sets
  confirmed_at, and routes the graph into the supervisor (paused via
  `interrupt_before` to keep this test scoped to planning).
"""

from __future__ import annotations

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
from schemas.intake import RunIntakeDraft
from schemas.plan import PlanConfirmRequest, PlanTree


@pytest.fixture(autouse=True)
async def _isolate_async_engine() -> Any:
    # Same rationale as test_intake_flow: dispose the async engine after every
    # function so pytest-asyncio's per-test event loop doesn't get stale asyncpg conns.
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
                competitors=["Notion", "Cursor"],
                started_at=datetime.now(timezone.utc),
            )
        )
        await session.commit()


async def _read_plan_tree_from_run(run_id: str) -> dict[str, object] | None:
    init_engine()
    session_factory = get_session_factory()
    async with session_factory() as session:
        run = await session.get(Run, run_id)
        if run is None:
            return None
        return run.plan_tree if isinstance(run.plan_tree, dict) else None


async def _count_planner_steps(run_id: str) -> int:
    init_engine()
    session_factory = get_session_factory()
    async with session_factory() as session:
        result = await session.execute(
            select(Step).where(Step.run_id == run_id, Step.agent_name == "planner_agent")
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
async def test_plan_flow_real_graph_postgres_resume() -> None:
    """End-to-end planner stage: enter with phase=planning, pause at planner_wait,
    resume with PlanConfirmRequest, route into supervisor (paused by interrupt_before).
    """
    dsn = settings.LANGGRAPH_CHECKPOINT_DSN
    assert dsn is not None

    run_id = make_id("run_")
    cfg = {"configurable": {"thread_id": run_id}}
    await _create_run_row(run_id, "对比 Notion 与 Cursor 的定价")

    try:
        async with AsyncPostgresSaver.from_conn_string(dsn) as checkpointer:
            graph_builder = build_graph_uncompiled()
            # Stop before supervisor so the test focuses on planner_generate and
            # planner_wait. We do NOT interrupt_before planner_generate — we want
            # it to run and pause at planner_wait via its own interrupt().
            app = graph_builder.compile(
                checkpointer=checkpointer,
                interrupt_before=["supervisor"],
            )

            complete_draft = RunIntakeDraft(
                user_query="对比 Notion 与 Cursor 的定价",
                user_role="pm",
                analysis_intent="对比 Notion 与 Cursor 的定价",
                competitors_explicit=["Notion", "Cursor"],
            )
            initial_state: AgentState = {
                "run_id": run_id,
                "user_query": "对比 Notion 与 Cursor 的定价",
                "phase": "planning",
                "intake_draft": complete_draft,
                "intake_history": [],
                "pending_clarify": None,
                "competitors": ["Notion", "Cursor"],
            }
            await app.ainvoke(initial_state, config=cfg)

            # planner_generate ran once and the graph paused at planner_wait.
            snapshot = await app.aget_state(cfg)
            assert snapshot.next == ("planner_wait",), (
                f"Expected pause at planner_wait, got next={snapshot.next}"
            )
            assert await _count_planner_steps(run_id) == 1

            # Invariant D: the interrupt payload is reachable from the snapshot.
            interrupt_payload = _extract_first_interrupt_value(snapshot)
            assert isinstance(interrupt_payload, dict)
            assert interrupt_payload.get("kind") == "plan_confirm"
            published_plan_raw = interrupt_payload.get("plan_tree")
            assert isinstance(published_plan_raw, dict)
            published_plan = PlanTree.model_validate(published_plan_raw)
            assert published_plan.confirmed_at is None
            assert published_plan.version == 1
            # Fake LLM emits: research(Notion), research(Cursor), analyze, write.
            stages = [task.stage for task in published_plan.tasks]
            assert stages == ["research", "research", "analyze", "write"]
            research_competitors = [
                task.competitor_id for task in published_plan.tasks if task.stage == "research"
            ]
            assert research_competitors == ["Notion", "Cursor"]

            # Run.plan_tree must be mirrored before the interrupt so the API can
            # render the plan without poking graph state.
            mirrored = await _read_plan_tree_from_run(run_id)
            assert mirrored is not None
            assert mirrored.get("plan_id") == published_plan.plan_id
            assert mirrored.get("confirmed_at") is None

            # Resume with a PlanConfirmRequest disabling the Cursor research task.
            cursor_task_id = next(
                task.task_id
                for task in published_plan.tasks
                if task.stage == "research" and task.competitor_id == "Cursor"
            )
            confirm = PlanConfirmRequest(disabled_task_ids=[cursor_task_id])
            await app.ainvoke(Command(resume=confirm.model_dump()), config=cfg)

            # After resume, planner_wait emitted plan.confirmed and the graph
            # advances to the supervisor (interrupted before it actually runs).
            snapshot = await app.aget_state(cfg)
            assert snapshot.next == ("supervisor",), (
                f"After plan.confirmed, graph must route to supervisor; got next={snapshot.next}"
            )
            # planner_generate did NOT re-run on resume (Invariant A).
            assert await _count_planner_steps(run_id) == 1

            values = snapshot.values
            assert values.get("phase") == "executing"
            confirmed_raw = values.get("plan_tree")
            assert confirmed_raw is not None
            confirmed_plan = (
                confirmed_raw
                if isinstance(confirmed_raw, PlanTree)
                else PlanTree.model_validate(confirmed_raw)
            )
            assert confirmed_plan.version == 2
            assert confirmed_plan.confirmed_at is not None
            confirmed_stages = [task.stage for task in confirmed_plan.tasks]
            # Cursor research was dropped; Notion research kept.
            assert confirmed_stages == ["research", "analyze", "write"]
            assert all(task.task_id != cursor_task_id for task in confirmed_plan.tasks)

            # Run.plan_tree should reflect the confirmed plan, including confirmed_at.
            mirrored_after = await _read_plan_tree_from_run(run_id)
            assert mirrored_after is not None
            assert mirrored_after.get("version") == 2
            assert mirrored_after.get("confirmed_at") == confirmed_plan.confirmed_at
            assert len(mirrored_after.get("tasks") or []) == 3
            assert values.get("pending_plan_tree") is None
    finally:
        _cleanup_run_and_checkpoint(run_id)
