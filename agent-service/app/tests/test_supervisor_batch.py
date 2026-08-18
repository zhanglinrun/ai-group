from __future__ import annotations

from types import SimpleNamespace

import pytest

from agents.state import ACCUMULATING_STATE_FIELDS, spread_without_accumulators
from agents.nodes.supervisor import (
    _decision_from_qa_feedback,
    _decision_from_tool_output,
    _derive_write_sections,
    _fallback_decision,
    _discovery_search_queries,
    _resolve_fallback_dimensions,
    supervisor_node,
)
from agents.nodes.planner import planner_generate_node
from schemas.intake import RunIntakeDraft
from schemas.agent_outputs import SupervisorToolCallOutput
from schemas.supervisor import SupervisorDecision
from service.event_bus import RunEventType
from service.llm.prompts import _format_plan_tree_for_supervisor
from service.llm.response import LLMResponse


def _fake_supervisor_llm_response() -> LLMResponse:
    return LLMResponse(
        model_slot="research",
        provider="fake",
        model_name="fake-supervisor-model",
        prompt_preview="fake supervisor prompt",
        prompt_hash="fake_hash",
        content={},
        prompt_tokens=1,
        completion_tokens=1,
        latency_ms=1,
        error=None,
    )


def test_spread_without_accumulators_drops_all_operator_add_fields() -> None:
    state = {
        "run_id": "run_test",
        "competitors": ["Cursor"],
        "discovered_competitors": ["Windsurf"],
        "researched_competitors": ["Cursor"],
        "follow_up_queue": [{"id": "fu_1"}],
        "status": "running",
    }

    result = spread_without_accumulators(state)

    for field_name in ACCUMULATING_STATE_FIELDS:
        assert field_name not in result
    assert result == {"run_id": "run_test", "status": "running"}


async def _run_supervisor_node_with_output(
    monkeypatch: pytest.MonkeyPatch,
    *,
    output: SupervisorToolCallOutput,
    state: dict[str, object],
    step_id: str,
) -> tuple[dict[str, object], list[tuple[RunEventType, str | None, dict[str, object]]]]:
    captured: list[tuple[RunEventType, str | None, dict[str, object]]] = []

    async def _fake_complete_structured(**_: object) -> SimpleNamespace:
        return SimpleNamespace(
            value=output,
            llm_response=_fake_supervisor_llm_response(),
        )

    async def _fake_persist_iteration(**_: object) -> str:
        return step_id

    async def _fake_load_pending_follow_ups(**_: object) -> list[dict[str, object]]:
        return []

    async def _fake_emit_run_event(
        *,
        run_id: str,
        event_type: RunEventType,
        step_id: str | None = None,
        payload: dict[str, object] | None = None,
    ) -> None:
        del run_id
        captured.append((event_type, step_id, dict(payload or {})))

    monkeypatch.setattr("agents.nodes.supervisor.complete_structured", _fake_complete_structured)
    monkeypatch.setattr("agents.nodes.supervisor._persist_iteration", _fake_persist_iteration)
    monkeypatch.setattr(
        "agents.nodes.supervisor._load_pending_follow_ups",
        _fake_load_pending_follow_ups,
    )
    monkeypatch.setattr("agents.nodes.supervisor.emit_run_event", _fake_emit_run_event)
    monkeypatch.setattr("agents.nodes.supervisor.get_session_factory", lambda: object())

    new_state = await supervisor_node(state)
    return dict(new_state), captured


def test_decision_from_tool_output_accepts_conduct_research_batch() -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "ConductResearchBatch",
            "tool_args": {
                "topics": [
                    {
                        "research_topic": "comp_cursor vs user_query=fake",
                        "competitor_id": "comp_cursor",
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                        "max_iterations": 6,
                        "fallback_to_offline": True,
                    },
                    {
                        "research_topic": "comp_windsurf vs user_query=fake",
                        "competitor_id": "comp_windsurf",
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                        "max_iterations": 6,
                        "fallback_to_offline": True,
                    },
                ],
                "parallelism_rationale": "parallelize independent competitors",
            },
            "reasoning_summary": "Batch pending competitors.",
        }
    )

    decision = _decision_from_tool_output(
        run_id="run_test",
        iteration=1,
        output=output,
        triggered_by="user_query",
        fallback_dimensions=["feature", "pricing", "user_feedback"],
        fallback_sections=["feature", "pricing", "user_feedback"],
    )

    assert decision.chosen_tool == "ConductResearchBatch"
    topics = decision.tool_args["topics"]
    assert isinstance(topics, list)
    assert len(topics) == 2
    assert {item["competitor_id"] for item in topics} == {"comp_cursor", "comp_windsurf"}
    assert decision.outcome == "dispatched"


def test_decision_from_tool_output_preserves_topic_dimensions_when_valid() -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "ConductResearchBatch",
            "tool_args": {
                "topics": [
                    {
                        "research_topic": "comp_meta vs user_query=fake",
                        "competitor_id": "comp_meta",
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                        "max_iterations": 6,
                        "fallback_to_offline": True,
                    },
                    {
                        "research_topic": "comp_nvidia vs user_query=fake",
                        "competitor_id": "comp_nvidia",
                        "focus_dimensions": ["market_differences"],
                        "max_iterations": 6,
                        "fallback_to_offline": True,
                    },
                ],
                "parallelism_rationale": "preserve per-competitor focus",
            },
            "reasoning_summary": "Batch pending competitors.",
        }
    )

    decision = _decision_from_tool_output(
        run_id="run_test",
        iteration=1,
        output=output,
        triggered_by="user_query",
        # Legacy dimension ids remain valid fallback dimensions for researcher routing.
        fallback_dimensions=["product_positioning", "pricing_strategy"],
        fallback_sections=["product_positioning", "pricing_strategy"],
    )

    topics = decision.tool_args["topics"]
    assert isinstance(topics, list)
    by_competitor = {item["competitor_id"]: item for item in topics}
    assert by_competitor["comp_meta"]["focus_dimensions"] == [
        "feature",
        "pricing",
        "user_feedback",
    ]
    assert by_competitor["comp_nvidia"]["focus_dimensions"] == ["market_differences"]


def test_decision_from_tool_output_truncates_batch_topics_to_max_eight() -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "ConductResearchBatch",
            "tool_args": {
                "topics": [
                    {
                        "research_topic": f"comp_{idx} vs user_query=fake",
                        "competitor_id": f"comp_{idx}",
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                        "max_iterations": 6,
                        "fallback_to_offline": True,
                    }
                    for idx in range(10)
                ],
                "parallelism_rationale": "parallelize independent competitors",
            },
            "reasoning_summary": "Batch pending competitors.",
        }
    )
    decision = _decision_from_tool_output(
        run_id="run_test",
        iteration=1,
        output=output,
        triggered_by="user_query",
        fallback_dimensions=["feature", "pricing", "user_feedback"],
        fallback_sections=["feature", "pricing", "user_feedback"],
    )

    topics = decision.tool_args["topics"]
    assert isinstance(topics, list)
    assert len(topics) == 8
    assert topics[0]["competitor_id"] == "comp_0"
    assert topics[-1]["competitor_id"] == "comp_7"


def test_discovery_search_queries_localize_chinese_market_scope() -> None:
    queries = _discovery_search_queries(
        user_query="OPC 变现工具",
        domain_context=None,
        market_scope="中国市场",
        response_language="zh",
    )

    assert queries
    assert all("中国市场" in query for query in queries)
    assert any("竞品" in query or "替代" in query for query in queries)
    assert not any("competitors alternatives" in query for query in queries)


def test_fallback_decision_uses_localized_discovery_queries() -> None:
    decision = _fallback_decision(
        run_id="run_test",
        iteration=1,
        competitors=[],
        researched_competitors=[],
        analysis_done=False,
        report_draft_done=False,
        triggered_by="user_query",
        user_query="OPC 变现工具",
        fallback_dimensions=["feature", "pricing"],
        fallback_sections=["feature", "pricing"],
        market_scope="中国市场",
        domain_context=None,
        response_language="zh",
    )

    assert decision.chosen_tool == "DiscoverCompetitors"
    search_queries = decision.tool_args["search_queries"]
    assert isinstance(search_queries, list)
    assert all("中国市场" in query for query in search_queries)
    assert not any("competitors alternatives" in query for query in search_queries)


def test_fallback_discovery_prefers_resolved_domain_context() -> None:
    decision = _fallback_decision(
        run_id="run_test",
        iteration=1,
        competitors=[],
        researched_competitors=[],
        analysis_done=False,
        report_draft_done=False,
        triggered_by="user_query",
        user_query="OPC 变现工具",
        fallback_dimensions=["feature", "pricing"],
        fallback_sections=["feature", "pricing"],
        domain_context="one person company monetization",
        response_language="zh",
    )

    assert decision.chosen_tool == "DiscoverCompetitors"
    assert decision.tool_args["domain_context"] == "one person company monetization"
    assert all("one person company monetization" in query for query in decision.tool_args["search_queries"])


def test_decision_from_tool_output_clamps_llm_dimensions_to_fallback_dimensions() -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "Analyze",
            "tool_args": {
                "focus_dimensions": ["made_up_dimension", "subscription_tiers"],
                "parallel_by_dimension": True,
                "require_cross_competitor": True,
            },
            "reasoning_summary": "Analyze with LLM-invented dimensions.",
        }
    )

    decision = _decision_from_tool_output(
        run_id="run_test",
        iteration=2,
        output=output,
        triggered_by="researcher_completion",
        fallback_dimensions=["product_positioning", "pricing_strategy"],
        fallback_sections=["product_positioning", "pricing_strategy"],
    )

    assert decision.chosen_tool == "Analyze"
    assert decision.tool_args["focus_dimensions"] == ["product_positioning", "pricing_strategy"]


@pytest.mark.asyncio
async def test_supervisor_node_marks_llm_tool_output_for_happy_path_dimensions(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "ConductResearchBatch",
            "tool_args": {
                "topics": [
                    {
                        "research_topic": "comp_cursor vs user_query=fake",
                        "competitor_id": "comp_cursor",
                        "focus_dimensions": ["feature", "pricing"],
                        "max_iterations": 6,
                        "fallback_to_offline": True,
                    }
                ],
                "parallelism_rationale": "parallelize independent competitors",
            },
            "reasoning_summary": "Batch pending competitors.",
        }
    )
    new_state, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_dimension",
        state={
            "run_id": "run_test",
            "user_query": "compare coding assistants",
            "competitors": ["comp_cursor"],
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "current_iteration": 0,
            "decisions": [],
        },
    )

    assert new_state["next_action"] == "researcher"
    for field_name in ACCUMULATING_STATE_FIELDS:
        assert field_name not in new_state
    assert captured == [
        (
            RunEventType.SUPERVISOR_DECISION,
            "step_supervisor_dimension",
            {
                "iteration": 1,
                "chosen_tool": "ConductResearchBatch",
                "triggered_by": "user_query",
                "outcome": "dispatched",
                "plan_task_ids": [],
                "consumed_follow_up_ids": [],
                "dimension_source": "default",
            },
        )
    ]


@pytest.mark.asyncio
async def test_supervisor_node_leaves_dimension_source_empty_for_discovery(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "DiscoverCompetitors",
            "tool_args": {
                "search_queries": ["coding assistant alternatives"],
                "domain_context": "AI coding assistant",
                "max_results": 5,
            },
            "reasoning_summary": "Discover competitors first.",
        }
    )
    _, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_discover",
        state={
            "run_id": "run_test",
            "user_query": "find competitors",
            "competitors": [],
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "current_iteration": 0,
            "decisions": [],
        },
    )

    assert captured[0][2]["chosen_tool"] == "DiscoverCompetitors"
    assert captured[0][2]["dimension_source"] is None


@pytest.mark.asyncio
async def test_planner_generate_seeds_explicit_competitors_when_discovery_is_enabled(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def _fake_complete_structured(**_: object) -> SimpleNamespace:
        return SimpleNamespace(
            value=None,
            llm_response=_fake_supervisor_llm_response(),
        )

    async def _fake_persist_planner_step(**_: object) -> str:
        return "step_planner"

    async def _fake_persist_plan_tree_to_run(**_: object) -> None:
        return None

    async def _fake_emit_run_event(**_: object) -> None:
        return None

    monkeypatch.setattr("agents.nodes.planner.complete_structured", _fake_complete_structured)
    monkeypatch.setattr("agents.nodes.planner._persist_planner_step", _fake_persist_planner_step)
    monkeypatch.setattr(
        "agents.nodes.planner._persist_plan_tree_to_run",
        _fake_persist_plan_tree_to_run,
    )
    monkeypatch.setattr("agents.nodes.planner.emit_run_event", _fake_emit_run_event)
    monkeypatch.setattr("agents.nodes.planner._resolve_session_factory", lambda _: object())

    new_state = await planner_generate_node(
        {
            "run_id": "run_test",
            "user_query": "TRAE 对标 Cursor、通义灵码，并主动发现其他竞品",
            "intake_draft": RunIntakeDraft(
                user_query="TRAE 对标 Cursor、通义灵码，并主动发现其他竞品",
                user_role="pm",
                analysis_intent="AI coding IDE comparison",
                competitors_explicit=["Cursor", "通义灵码"],
                competitors_discovery_mode=True,
                self_product="TRAE",
            ),
            "competitors": [],
        }
    )

    assert new_state["competitors"] == ["Cursor", "通义灵码"]
    pending_plan = new_state["pending_plan_tree"]
    assert pending_plan is not None
    assert [task.stage for task in pending_plan.tasks][:3] == ["discover", "research", "research"]


@pytest.mark.asyncio
async def test_supervisor_runs_plan_discovery_before_researching_seeded_competitors(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "Analyze",
            "tool_args": {
                "focus_dimensions": ["feature", "pricing", "user_feedback"],
                "parallel_by_dimension": True,
                "require_cross_competitor": True,
            },
            "reasoning_summary": "LLM would analyze too early.",
        }
    )

    new_state, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_plan_discovery",
        state={
            "run_id": "run_test",
            "user_query": "TRAE 对标 Cursor、通义灵码，并主动发现其他竞品",
            "domain_hint": "AI coding IDE",
            "competitors": ["Cursor", "通义灵码"],
            "discovered_competitors": [],
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "current_iteration": 0,
            "decisions": [],
            "plan_tree": {
                "tasks": [
                    {"stage": "discover", "enabled": True},
                    {"stage": "research", "competitor_id": "Cursor", "enabled": True},
                    {"stage": "research", "competitor_id": "通义灵码", "enabled": True},
                    {"stage": "analyze", "enabled": True},
                    {"stage": "write", "enabled": True},
                ],
            },
        },
    )

    assert new_state["next_action"] == "discovery"
    assert new_state["pending_tool_args"]["domain_context"] == "AI coding IDE"
    assert captured[0][2]["chosen_tool"] == "DiscoverCompetitors"
    assert captured[0][2]["plan_task_ids"] == []
    assert captured[0][2]["dimension_source"] is None


@pytest.mark.asyncio
async def test_supervisor_finalize_degrades_when_researcher_had_zero_evidence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "Finalize",
            "tool_args": {
                "completion_reason": "all_dimensions_covered",
                "notes": "Done",
            },
            "reasoning_summary": "Workflow completed with a degraded researcher step.",
        }
    )
    new_state, _ = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_degraded_research",
        state={
            "run_id": "run_test",
            "user_query": "compare coding assistants",
            "competitors": ["comp_cursor"],
            "researched_competitors": ["comp_cursor"],
            "researcher_degraded_competitors": ["comp_cursor"],
            "analysis_done": True,
            "report_draft_done": True,
            "current_iteration": 0,
            "decisions": [],
        },
    )

    assert new_state["status"] == "degraded"
    assert "comp_cursor" in str(new_state.get("status_reason", ""))
    assert "有效证据" in str(new_state.get("status_reason", ""))


@pytest.mark.asyncio
async def test_qa_writer_rewrite_reuses_prior_writer_contract() -> None:
    prior_writer_step = SimpleNamespace(
        run_id="run_test",
        agent_name="writer",
        payload={
            "template_id": "executive_briefing",
            # This test locks QA rewrite compatibility with historical writer
            # payloads. New outline target parsing is covered in test_agent_outputs.
            "target_sections": ["product_positioning", "pricing_strategy"],
        },
    )

    class _FakeSession:
        async def __aenter__(self) -> "_FakeSession":
            return self

        async def __aexit__(self, *_: object) -> None:
            return None

        async def get(self, *_: object) -> object:
            return prior_writer_step

    decision_bundle = await _decision_from_qa_feedback(
        session_factory=lambda: _FakeSession(),  # type: ignore[arg-type]
        run_id="run_test",
        iteration=3,
        triggered_by="qa_rejection",
        qa_outcome="rejected",
        qa_reject_to="writer",
        qa_reasons=["Unsupported numeric claims."],
        qa_degrade_reason=None,
        qa_degraded_required_sections=[],
        qa_unsupported_numeric_claims=[{"claim": "$40/seat"}],
        user_query="compare coding assistants",
        competitors=["Cursor"],
        fallback_dimensions=["feature"],
        fallback_sections=["feature", "differentiation"],
        pending_review_target_step_id="step_writer_v1",
    )

    assert decision_bundle is not None
    decision, _, forced_degraded = decision_bundle
    assert forced_degraded is False
    assert decision.chosen_tool == "Write"
    assert decision.tool_args["template_id"] == "executive_briefing"
    assert decision.tool_args["sections"] == ["product_positioning", "pricing_strategy"]
    assert decision.tool_args["unsupported_numeric_claims"] == [{"claim": "$40/seat"}]


@pytest.mark.asyncio
async def test_qa_data_degraded_force_finalize_uses_data_gap_reason() -> None:
    class _FakeSession:
        async def __aenter__(self) -> "_FakeSession":
            return self

        async def __aexit__(self, *_: object) -> None:
            return None

        async def get(self, *_: object) -> object:
            return None

    decision_bundle = await _decision_from_qa_feedback(
        session_factory=lambda: _FakeSession(),  # type: ignore[arg-type]
        run_id="run_test",
        iteration=2,
        triggered_by="qa_rejection",
        qa_outcome="force_degraded",
        qa_reject_to="supervisor",
        qa_reasons=["Required sections lack grounded evidence."],
        qa_degrade_reason="report_degraded_required_sections",
        qa_degraded_required_sections=["market_definition", "key_players", "methodology_limits"],
        qa_unsupported_numeric_claims=[],
        user_query="landscape ai hardware",
        competitors=["Meta", "NVIDIA"],
        fallback_dimensions=["feature"],
        fallback_sections=["market_definition"],
        pending_review_target_step_id="step_writer_v2",
    )

    assert decision_bundle is not None
    decision, llm_response, forced_degraded = decision_bundle
    assert forced_degraded is True
    assert decision.chosen_tool == "Finalize"
    assert "degraded_required=market_definition, key_players, methodology_limits" in decision.reasoning_summary
    assert llm_response.prompt_preview == "qa_data_degraded"


def test_fallback_decision_prefers_batch_when_multiple_competitors_pending() -> None:
    fallback_dimensions = ["feature", "pricing", "user_feedback"]
    decision = _fallback_decision(
        run_id="run_test",
        iteration=2,
        competitors=["comp_cursor", "comp_windsurf", "comp_copilot"],
        researched_competitors=["comp_cursor"],
        analysis_done=False,
        report_draft_done=False,
        triggered_by="researcher_completion",
        user_query="compare coding assistants",
        fallback_dimensions=fallback_dimensions,
        fallback_sections=_derive_write_sections(focus_dimensions=fallback_dimensions),
    )

    assert decision.chosen_tool == "ConductResearchBatch"
    topics = decision.tool_args["topics"]
    assert isinstance(topics, list)
    assert len(topics) == 2
    assert {item["competitor_id"] for item in topics} == {"comp_windsurf", "comp_copilot"}


def test_resolve_fallback_dimensions_prefers_matching_plan_task_over_hints() -> None:
    dimensions, source = _resolve_fallback_dimensions(
        plan_tree={
            "tasks": [
                {
                    "stage": "research",
                    "competitor_id": "comp_windsurf",
                    "focus_dimensions": ["supply_chain", "implementation"],
                    "enabled": True,
                }
            ]
        },
        intake_draft={"focus_dimensions": ["pricing"]},
        user_query="compare pricing for coding assistants",
        competitors=["comp_cursor", "comp_windsurf"],
        researched_competitors=["comp_cursor"],
        analysis_done=False,
        report_draft_done=False,
    )

    assert source == "upstream_task"
    assert dimensions == [
        "supply_chain",
        "implementation",
        "feature",
        "pricing",
        "user_feedback",
    ]


def test_resolve_fallback_dimensions_uses_intake_before_hints() -> None:
    dimensions, source = _resolve_fallback_dimensions(
        plan_tree=None,
        intake_draft=RunIntakeDraft(
            user_query="分析 ERP 实施风险和供应链集成差异",
            focus_dimensions=["implementation", "integration"],
        ),
        user_query="compare pricing for supply chain ERP",
        competitors=["comp_a", "comp_b"],
        researched_competitors=[],
        analysis_done=False,
        report_draft_done=False,
    )

    assert source == "intake"
    assert dimensions == [
        "implementation",
        "integration",
        "feature",
        "pricing",
        "user_feedback",
    ]


def test_resolve_fallback_dimensions_uses_hints_only_without_upstream() -> None:
    dimensions, source = _resolve_fallback_dimensions(
        plan_tree=None,
        intake_draft=None,
        user_query="我想比较这些产品的定价和企业套餐",
        competitors=["comp_a", "comp_b"],
        researched_competitors=[],
        analysis_done=False,
        report_draft_done=False,
    )

    assert source == "hints"
    assert dimensions[0] == "pricing"


def test_resolve_fallback_dimensions_landscape_keeps_original_research_dimensions() -> None:
    dimensions, source = _resolve_fallback_dimensions(
        plan_tree=None,
        intake_draft={
            "analysis_archetype": "landscape",
            "focus_dimensions": ["implementation", "market_differences"],
        },
        user_query="机会扫描，重点看实施门槛",
        competitors=["comp_a", "comp_b"],
        researched_competitors=[],
        analysis_done=False,
        report_draft_done=False,
    )

    assert source == "intake"
    assert dimensions == ["implementation", "market_differences"]


def test_resolve_fallback_dimensions_defaults_without_upstream_or_hints() -> None:
    dimensions, source = _resolve_fallback_dimensions(
        plan_tree=None,
        intake_draft=None,
        user_query="compare these products",
        competitors=["comp_a", "comp_b"],
        researched_competitors=[],
        analysis_done=False,
        report_draft_done=False,
    )

    assert source == "default"
    assert dimensions == ["feature", "pricing", "user_feedback"]


def test_format_plan_tree_hides_discover_task_after_discovery_completed() -> None:
    plan_tree = {
        "tasks": [
            {"stage": "discover", "title": "发现新兴竞品", "enabled": True},
            {"stage": "research", "competitor_id": "Cursor", "title": "研究 Cursor", "enabled": True},
        ],
    }

    before = _format_plan_tree_for_supervisor(
        plan_tree=plan_tree,
        researched_competitors=[],
        discovery_completed=False,
    )
    after = _format_plan_tree_for_supervisor(
        plan_tree=plan_tree,
        researched_competitors=[],
        discovery_completed=True,
    )

    assert "stage=discover" in before
    assert "stage=discover" not in after
    assert "stage=research" in after


@pytest.mark.asyncio
async def test_supervisor_blocks_repeated_discovery_with_fallback(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "DiscoverCompetitors",
            "tool_args": {
                "search_queries": ["AI coding assistant alternatives"],
                "domain_context": "AI coding assistant",
                "max_results": 5,
            },
            "reasoning_summary": "User explicitly requested to discover missing competitors.",
        }
    )
    new_state, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_discovery_loop",
        state={
            "run_id": "run_test",
            "user_query": "compare coding assistants",
            "competitors": ["Cursor", "GitHub Copilot"],
            "discovered_competitors": ["Cursor", "GitHub Copilot", "Codeium"],
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "current_iteration": 2,
            "decisions": [],
        },
    )

    assert new_state["next_action"] == "researcher"
    assert captured[0][2]["chosen_tool"] == "ConductResearchBatch"


@pytest.mark.asyncio
async def test_supervisor_blocks_analyze_when_competitors_are_empty(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "Analyze",
            "tool_args": {
                "focus_dimensions": ["feature", "pricing", "user_feedback"],
                "parallel_by_dimension": False,
                "require_cross_competitor": True,
            },
            "reasoning_summary": "LLM attempts to analyze before competitor discovery.",
        }
    )
    new_state, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_empty_competitors_guardrail",
        state={
            "run_id": "run_test",
            "user_query": "AI 硬件的主流产品以及发展趋势。",
            "competitors": [],
            "discovered_competitors": [],
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "current_iteration": 0,
            "decisions": [],
            "intake_draft": {
                "analysis_archetype": "landscape",
                "competitors_discovery_mode": True,
            },
        },
    )

    assert new_state["next_action"] == "discovery"
    assert captured[0][2]["chosen_tool"] == "DiscoverCompetitors"


@pytest.mark.asyncio
async def test_supervisor_allows_analyze_after_discovery_attempt_with_empty_competitors(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "Analyze",
            "tool_args": {
                "focus_dimensions": ["feature", "pricing", "user_feedback"],
                "parallel_by_dimension": False,
                "require_cross_competitor": True,
            },
            "reasoning_summary": "Proceed with analysis after one discovery attempt.",
        }
    )
    new_state, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_empty_competitors_after_discovery",
        state={
            "run_id": "run_test",
            "user_query": "AI 硬件的主流产品以及发展趋势。",
            "competitors": [],
            "discovered_competitors": [],
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "current_iteration": 1,
            "decisions": [
                SupervisorDecision(
                    id="decision_prior_discovery",
                    run_id="run_test",
                    iteration=0,
                    chosen_tool="DiscoverCompetitors",
                    tool_args={},
                    reasoning_summary="prior discovery attempt",
                    triggered_by="user_query",
                    outcome="dispatched",
                    outcome_recorded_at="2026-01-01T00:00:00Z",
                    created_at="2026-01-01T00:00:00Z",
                )
            ],
            "intake_draft": {
                "analysis_archetype": "landscape",
                "competitors_discovery_mode": True,
            },
        },
    )

    assert new_state["next_action"] == "analyst"
    assert captured[0][2]["chosen_tool"] == "Analyze"


@pytest.mark.asyncio
async def test_supervisor_landscape_research_is_forced_to_batch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "ConductResearch",
            "tool_args": {
                "research_topic": "comp_meta vs user_query=fake",
                "competitor_id": "Meta Ray-Ban",
                "focus_dimensions": ["feature", "pricing", "user_feedback"],
                "max_iterations": 6,
                "fallback_to_offline": True,
            },
            "reasoning_summary": "LLM selected one competitor first.",
        }
    )
    new_state, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_landscape_batch_guardrail",
        state={
            "run_id": "run_test",
            "user_query": "AI 硬件 landscape",
            "competitors": ["Meta Ray-Ban", "XREAL", "NVIDIA"],
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "current_iteration": 0,
            "decisions": [],
            "intake_draft": {
                "analysis_archetype": "landscape",
                "focus_dimensions": ["market_differences"],
            },
            "plan_tree": {
                "tasks": [
                    {
                        "stage": "research",
                        "competitor_id": "Meta Ray-Ban",
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                        "enabled": True,
                    },
                    {
                        "stage": "research",
                        "competitor_id": "XREAL",
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                        "enabled": True,
                    },
                    {
                        "stage": "research",
                        "competitor_id": "NVIDIA",
                        "focus_dimensions": ["market_differences"],
                        "enabled": True,
                    },
                ],
                "competitor_sources": {
                    "NVIDIA": {
                        "candidate_role": "upstream_supplier",
                        "relevance_reason": "上游芯片供应商，提供 AI 眼镜核心算力与供应链约束。",
                    }
                },
            },
        },
    )

    assert new_state["next_action"] == "researcher"
    assert captured[0][2]["chosen_tool"] == "ConductResearchBatch"
    topics = new_state["pending_tool_args"]["topics"]
    assert isinstance(topics, list)
    assert [item["competitor_id"] for item in topics] == ["Meta Ray-Ban", "XREAL", "NVIDIA"]


@pytest.mark.asyncio
async def test_supervisor_landscape_batch_rewrites_focus_dimension_drift(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "ConductResearchBatch",
            "tool_args": {
                "topics": [
                    {
                        "research_topic": "Meta Ray-Ban主流产品与发展趋势",
                        "competitor_id": "Meta Ray-Ban",
                        "focus_dimensions": ["product_lineup", "tech_specs", "market_trends"],
                        "max_iterations": 3,
                        "search_max_results": 5,
                        "fallback_to_offline": False,
                    },
                    {
                        "research_topic": "XREAL主流产品与发展趋势",
                        "competitor_id": "XREAL",
                        "focus_dimensions": ["product_lineup", "tech_specs", "market_trends"],
                        "max_iterations": 3,
                        "search_max_results": 5,
                        "fallback_to_offline": False,
                    },
                    {
                        "research_topic": "NVIDIA主流产品与发展趋势",
                        "competitor_id": "NVIDIA",
                        "focus_dimensions": ["product_lineup", "tech_specs", "market_trends"],
                        "max_iterations": 3,
                        "search_max_results": 5,
                        "fallback_to_offline": False,
                    },
                ],
                "parallelism_rationale": "llm output keeps same competitors but drifts dimensions",
            },
            "reasoning_summary": "LLM selected the same competitor set with drifted dimensions.",
        }
    )
    new_state, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_landscape_batch_dimension_guardrail",
        state={
            "run_id": "run_test",
            "user_query": "AI 硬件 landscape",
            "competitors": ["Meta Ray-Ban", "XREAL", "NVIDIA"],
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "current_iteration": 0,
            "decisions": [],
            "intake_draft": {
                "analysis_archetype": "landscape",
                "focus_dimensions": ["market_differences"],
            },
            "plan_tree": {
                "tasks": [
                    {
                        "stage": "research",
                        "competitor_id": "Meta Ray-Ban",
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                        "enabled": True,
                    },
                    {
                        "stage": "research",
                        "competitor_id": "XREAL",
                        "focus_dimensions": ["feature", "pricing", "user_feedback"],
                        "enabled": True,
                    },
                    {
                        "stage": "research",
                        "competitor_id": "NVIDIA",
                        "focus_dimensions": ["market_differences"],
                        "enabled": True,
                    },
                ],
                "competitor_sources": {
                    "NVIDIA": {
                        "candidate_role": "upstream_supplier",
                        "relevance_reason": "上游芯片供应商，提供 AI 眼镜核心算力与供应链约束。",
                    }
                },
            },
        },
    )

    assert new_state["next_action"] == "researcher"
    assert captured[0][2]["chosen_tool"] == "ConductResearchBatch"
    topics = new_state["pending_tool_args"]["topics"]
    assert isinstance(topics, list)
    by_competitor = {item["competitor_id"]: item for item in topics}
    assert by_competitor["Meta Ray-Ban"]["focus_dimensions"] == [
        "feature",
        "pricing",
        "user_feedback",
    ]
    assert by_competitor["XREAL"]["focus_dimensions"] == [
        "feature",
        "pricing",
        "user_feedback",
    ]
    assert by_competitor["NVIDIA"]["focus_dimensions"] == ["market_differences"]
    assert "上游芯片供应商" in by_competitor["NVIDIA"]["research_topic"]


@pytest.mark.asyncio
async def test_supervisor_landscape_batch_promotes_core_competitors_to_triplet_dimensions(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "ConductResearchBatch",
            "tool_args": {
                "topics": [
                    {
                        "research_topic": "Intel AI Solutions 用户反馈",
                        "competitor_id": "Intel AI Solutions",
                        "focus_dimensions": ["user_feedback"],
                        "max_iterations": 3,
                        "search_max_results": 5,
                        "fallback_to_offline": True,
                    },
                    {
                        "research_topic": "AMD Instinct Accelerators 用户反馈",
                        "competitor_id": "AMD Instinct Accelerators",
                        "focus_dimensions": ["user_feedback"],
                        "max_iterations": 3,
                        "search_max_results": 5,
                        "fallback_to_offline": True,
                    },
                    {
                        "research_topic": "NVIDIA 生态趋势",
                        "competitor_id": "NVIDIA",
                        "focus_dimensions": ["market_differences"],
                        "max_iterations": 3,
                        "search_max_results": 5,
                        "fallback_to_offline": True,
                    },
                ],
                "parallelism_rationale": "llm output keeps thin dimensions for all competitors",
            },
            "reasoning_summary": "LLM keeps single-dimension focus for core competitors.",
        }
    )
    new_state, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_landscape_core_triplet_guardrail",
        state={
            "run_id": "run_test",
            "user_query": "AI 硬件 landscape",
            "competitors": [
                "Intel AI Solutions",
                "AMD Instinct Accelerators",
                "NVIDIA",
            ],
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "current_iteration": 0,
            "decisions": [],
            "intake_draft": {
                "analysis_archetype": "landscape",
                "focus_dimensions": ["market_differences"],
            },
            "plan_tree": {
                "tasks": [
                    {
                        "stage": "research",
                        "competitor_id": "Intel AI Solutions",
                        "focus_dimensions": ["user_feedback"],
                        "enabled": True,
                    },
                    {
                        "stage": "research",
                        "competitor_id": "AMD Instinct Accelerators",
                        "focus_dimensions": ["user_feedback"],
                        "enabled": True,
                    },
                    {
                        "stage": "research",
                        "competitor_id": "NVIDIA",
                        "focus_dimensions": ["market_differences"],
                        "enabled": True,
                    },
                ],
                "competitor_sources": {
                    "Intel AI Solutions": {
                        "candidate_role": "direct_competitor",
                        "relevance_reason": "Intel 在边缘端 AI 能力上有直接竞对关系。",
                    },
                    "AMD Instinct Accelerators": {
                        "candidate_role": "adjacent_competitor",
                        "relevance_reason": "AMD 在算力和生态上构成相邻竞品。",
                    },
                    "NVIDIA": {
                        "candidate_role": "upstream_supplier",
                        "relevance_reason": "上游芯片供应商，影响供给与成本。",
                    },
                },
            },
        },
    )

    assert new_state["next_action"] == "researcher"
    assert captured[0][2]["chosen_tool"] == "ConductResearchBatch"
    topics = new_state["pending_tool_args"]["topics"]
    assert isinstance(topics, list)
    by_competitor = {item["competitor_id"]: item for item in topics}
    assert by_competitor["Intel AI Solutions"]["focus_dimensions"] == [
        "feature",
        "pricing",
        "user_feedback",
    ]
    assert by_competitor["AMD Instinct Accelerators"]["focus_dimensions"] == [
        "feature",
        "pricing",
        "user_feedback",
    ]
    assert by_competitor["NVIDIA"]["focus_dimensions"] == ["market_differences"]


@pytest.mark.asyncio
async def test_supervisor_routes_write_without_llm_when_analysis_is_done(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def _fail_if_llm_called(**_: object) -> SimpleNamespace:
        raise AssertionError("analysis_done state should route to writer before LLM planning")

    async def _fake_persist_iteration(**_: object) -> str:
        return "step_supervisor_analysis_done_write"

    async def _fake_emit_run_event(**_: object) -> None:
        return None

    monkeypatch.setattr("agents.nodes.supervisor.complete_structured", _fail_if_llm_called)
    monkeypatch.setattr("agents.nodes.supervisor._persist_iteration", _fake_persist_iteration)
    monkeypatch.setattr("agents.nodes.supervisor.emit_run_event", _fake_emit_run_event)
    monkeypatch.setattr("agents.nodes.supervisor.get_session_factory", lambda: object())

    new_state = await supervisor_node(
        {
            "run_id": "run_test",
            "user_query": "AI 硬件的主流产品以及发展趋势。",
            "competitors": ["Meta Ray-Ban", "XREAL"],
            "researched_competitors": ["Meta Ray-Ban", "XREAL"],
            "analysis_done": True,
            "report_draft_done": False,
            "current_iteration": 4,
            "decisions": [],
            "last_completed_node": "analyst",
        }
    )

    assert new_state["next_action"] == "writer"
    assert new_state["pending_tool_args"]["sections"]
    assert new_state["status"] == "running"


@pytest.mark.asyncio
async def test_supervisor_allows_qa_analyst_retry_after_analysis_done(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "Write",
            "tool_args": {
                "sections": ["feature", "pricing"],
            },
            "reasoning_summary": "LLM output is bypassed by QA fast path.",
        }
    )
    new_state, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_qa_analyst_retry",
        state={
            "run_id": "run_test",
            "user_query": "compare coding assistants",
            "competitors": ["Cursor", "GitHub Copilot"],
            "researched_competitors": ["Cursor", "GitHub Copilot"],
            "analysis_done": True,
            "report_draft_done": True,
            "current_iteration": 4,
            "decisions": [],
            "qa_outcome": "rejected",
            "qa_reject_to": "analyst",
            "qa_reasons": ["Needs deeper cross-competitor analysis."],
        },
    )

    assert new_state["next_action"] == "analyst"
    assert new_state["report_draft_done"] is False
    assert captured[0][2]["chosen_tool"] == "Analyze"


@pytest.mark.asyncio
async def test_supervisor_finalize_without_report_routes_writer_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "Finalize",
            "tool_args": {"completion_reason": "all_dimensions_covered", "notes": None},
            "reasoning_summary": "LLM finalize without report.",
        }
    )
    new_state, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_finalize_writer_once",
        state={
            "run_id": "run_test",
            "user_query": "compare coding assistants",
            "competitors": ["Cursor", "GitHub Copilot"],
            "researched_competitors": ["Cursor", "GitHub Copilot"],
            "plan_tree": {
                "tasks": [
                    {"stage": "write", "enabled": True},
                ]
            },
            "analysis_done": True,
            "report_draft_done": False,
            "current_iteration": 1,
            "decisions": [],
        },
    )

    assert new_state["next_action"] == "writer"
    assert new_state["status"] == "running"
    assert captured[0][2]["chosen_tool"] == "Write"
    assert new_state["pending_tool_args"]["sections"]


@pytest.mark.asyncio
async def test_supervisor_finalize_without_report_degrades_after_writer_attempted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "Finalize",
            "tool_args": {"completion_reason": "all_dimensions_covered", "notes": None},
            "reasoning_summary": "Try finalize again.",
        }
    )
    new_state, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_finalize_degraded",
        state={
            "run_id": "run_test",
            "user_query": "compare coding assistants",
            "competitors": ["Cursor", "GitHub Copilot"],
            "researched_competitors": ["Cursor", "GitHub Copilot"],
            "plan_tree": {
                "tasks": [
                    {"stage": "write", "enabled": True},
                ]
            },
            "analysis_done": True,
            "report_draft_done": False,
            "current_iteration": 2,
            "decisions": [
                SimpleNamespace(chosen_tool="Write")
            ],
        },
    )

    assert new_state["next_action"] == "finalize"
    assert new_state["status"] == "degraded"
    assert new_state["pending_tool_args"]["completion_reason"] == "fallback_path"
    assert captured[0][2]["chosen_tool"] == "Finalize"


@pytest.mark.asyncio
async def test_supervisor_forced_finalize_when_budget_exhausted_with_report(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = SupervisorToolCallOutput.parse_llm_content(
        {
            "chosen_tool": "Analyze",
            "tool_args": {
                "focus_dimensions": ["feature"],
                "parallel_by_dimension": False,
                "require_cross_competitor": True,
            },
            "reasoning_summary": "LLM output is irrelevant; guardrail branch fires first.",
        }
    )
    new_state, captured = await _run_supervisor_node_with_output(
        monkeypatch,
        output=output,
        step_id="step_supervisor_forced_finalize",
        state={
            "run_id": "run_test",
            "user_query": "compare coding assistants",
            "report_depth": "debug",
            "competitors": ["Cursor", "GitHub Copilot"],
            "researched_competitors": ["Cursor", "GitHub Copilot"],
            "analysis_done": True,
            "report_draft_done": True,
            "current_iteration": 6,
            "decisions": [],
        },
    )

    assert new_state["next_action"] == "finalize"
    assert new_state["status"] == "degraded"
    assert captured[0][2]["chosen_tool"] == "Finalize"
