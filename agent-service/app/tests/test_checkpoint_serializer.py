from __future__ import annotations

from service.checkpoint import CHECKPOINT_ALLOWED_TYPES, checkpoint_serializer
from schemas.intake import IntakeClarifyRequest, IntakeExchange, IntakeUserReply, RunIntakeDraft
from schemas.plan import PlanTask, PlanTree


def test_checkpoint_serializer_round_trips_agent_state_models() -> None:
    serializer = checkpoint_serializer()
    draft = RunIntakeDraft(
        user_query="AMR and LLM academic survey",
        research_mode="academic",
        user_role="researcher",
        analysis_intent="survey papers and benchmarks",
        competitors_discovery_mode=True,
    )
    clarify = IntakeClarifyRequest(
        question="Which publication window should be used?",
        field_targets=["time_range"],
    )
    exchange = IntakeExchange(
        clarify=clarify,
        reply=IntakeUserReply(text="2022-2026"),
    )
    plan = PlanTree(
        plan_id="plan_test",
        version=1,
        tasks=[
            PlanTask(
                task_id="task_test",
                stage="research",
                title="Collect papers",
                description="Find methods, datasets, and benchmark results.",
            )
        ],
    )

    payload = {
        "intake_draft": draft,
        "pending_clarify": clarify,
        "intake_history": [exchange],
        "plan_tree": plan,
    }
    kind, data = serializer.dumps_typed(payload)
    restored = serializer.loads_typed((kind, data))

    assert restored["intake_draft"] == draft
    assert restored["pending_clarify"] == clarify
    assert restored["intake_history"] == [exchange]
    assert restored["plan_tree"] == plan
    assert RunIntakeDraft in CHECKPOINT_ALLOWED_TYPES


def test_checkpoint_serializer_round_trips_postgres_metadata() -> None:
    serializer = checkpoint_serializer()
    metadata = {
        "source": "loop",
        "step": 3,
        "parents": {"root": "checkpoint_1"},
        "run_id": "run_test",
        "counters_since_delta_snapshot": {"researcher": (2, 1)},
    }

    encoded = serializer.dumps(metadata)
    restored = serializer.loads(encoded)

    assert isinstance(encoded, bytes)
    assert restored == {
        **metadata,
        "counters_since_delta_snapshot": {"researcher": [2, 1]},
    }
