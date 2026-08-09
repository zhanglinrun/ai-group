from __future__ import annotations

from typing import Any, Literal

from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from agents.nodes.analyst import analyst_node
from agents.nodes.discovery import discovery_node
from agents.nodes.intake import intake_generate_node, intake_wait_node
from agents.nodes.planner import (
    planner_generate_node,
    planner_wait_node,
    planning_profile_wait_node,
)
from agents.nodes.qa import qa_node
from agents.nodes.replanner import replanner_node
from agents.nodes.researcher import researcher_node
from agents.nodes.supervisor import supervisor_node
from agents.nodes.writer import deepen_node, writer_node
from agents.state import AgentState


def _route_after_supervisor(
    state: AgentState,
) -> list[Send] | Literal["discovery", "researcher", "analyst", "writer", "finalize"]:
    next_action = state.get("next_action", "finalize")
    if next_action == "discovery":
        return "discovery"
    if next_action != "researcher":
        if next_action in {"analyst", "writer", "finalize"}:
            return next_action
        return "finalize"

    pending_tool_args = state.get("pending_tool_args")
    topics = pending_tool_args.get("topics") if isinstance(pending_tool_args, dict) else None
    if not isinstance(topics, list) or len(topics) <= 1:
        return "researcher"

    run_id = state.get("run_id")
    domain_hint = state.get("domain_hint")
    reference_urls = state.get("reference_urls", [])
    discovered_competitor_sources = state.get("discovered_competitor_sources", {})
    if run_id is None:
        return "researcher"

    sends: list[Send] = []
    for topic in topics:
        if not isinstance(topic, dict):
            continue
        sends.append(
            Send(
                "researcher",
                {
                    "run_id": run_id,
                    "domain_hint": domain_hint,
                    "reference_urls": reference_urls,
                    "discovered_competitor_sources": discovered_competitor_sources,
                    "pending_tool_args": topic,
                },
            )
        )
    if sends:
        return sends
    return "researcher"


def _route_after_qa(state: AgentState) -> Literal["replanner", "supervisor", "deepen"]:
    qa_outcome = state.get("qa_outcome")
    if qa_outcome == "approved":
        return "deepen"
    if qa_outcome == "rejected":
        return "replanner"
    return "supervisor"


def _route_entry(state: AgentState) -> Literal["intake_generate", "planner_generate", "supervisor"]:
    # Invariant B: drive entry from explicit `phase`. Legacy runs without `phase`
    # default to `supervisor` so POST /api/runs keeps its synchronous-bus contract.
    # Phase 2: `phase="planning"` entry skips intake (e.g. expert-mode runs that
    # arrive with a fully-formed intake_draft already).
    phase = state.get("phase")
    if phase == "intake":
        return "intake_generate"
    if phase == "planning":
        return "planner_generate"
    return "supervisor"


def _route_after_intake_generate(
    state: AgentState,
) -> Literal["intake_wait", "planning_profile_wait", "planner_generate", "supervisor"]:
    # Chat intake handoff now pauses for an explicit depth choice before planner.
    # Intake ask-turns still route back to intake_wait.
    phase = state.get("phase")
    if phase == "intake":
        return "intake_wait"
    if phase == "planning":
        if state.get("report_depth_selection_pending") is True:
            return "planning_profile_wait"
        return "planner_generate"
    return "supervisor"


def _route_after_planner_generate(state: AgentState) -> Literal["planner_wait"]:
    # planner_generate always commits and routes to planner_wait. The branch is
    # declared explicitly so the graph DAG stays self-documenting.
    return "planner_wait"


def build_graph_uncompiled() -> StateGraph:
    graph = StateGraph(AgentState)
    graph.add_node("supervisor", supervisor_node)
    graph.add_node("discovery", discovery_node)
    graph.add_node("researcher", researcher_node)
    graph.add_node("analyst", analyst_node)
    graph.add_node("writer", writer_node)
    graph.add_node("deepen", deepen_node)
    graph.add_node("qa", qa_node)
    graph.add_node("replanner", replanner_node)
    graph.add_node("intake_generate", intake_generate_node)
    graph.add_node("intake_wait", intake_wait_node)
    graph.add_node("planning_profile_wait", planning_profile_wait_node)
    graph.add_node("planner_generate", planner_generate_node)
    graph.add_node("planner_wait", planner_wait_node)
    graph.add_conditional_edges(
        START,
        _route_entry,
        {
            "intake_generate": "intake_generate",
            "planner_generate": "planner_generate",
            "supervisor": "supervisor",
        },
    )
    graph.add_conditional_edges(
        "intake_generate",
        _route_after_intake_generate,
        {
            "intake_wait": "intake_wait",
            "planning_profile_wait": "planning_profile_wait",
            "planner_generate": "planner_generate",
            "supervisor": "supervisor",
        },
    )
    graph.add_edge("intake_wait", "intake_generate")
    graph.add_edge("planning_profile_wait", "planner_generate")
    graph.add_conditional_edges(
        "planner_generate",
        _route_after_planner_generate,
        {"planner_wait": "planner_wait"},
    )
    graph.add_edge("planner_wait", "supervisor")
    graph.add_conditional_edges(
        "supervisor",
        _route_after_supervisor,
        {
            "discovery": "discovery",
            "researcher": "researcher",
            "analyst": "analyst",
            "writer": "writer",
            "finalize": END,
        },
    )
    graph.add_edge("discovery", "replanner")
    graph.add_edge("replanner", "supervisor")
    graph.add_edge("researcher", "supervisor")
    graph.add_edge("analyst", "supervisor")
    graph.add_edge("writer", "qa")
    graph.add_conditional_edges(
        "qa",
        _route_after_qa,
        {
            "replanner": "replanner",
            "supervisor": "supervisor",
            "deepen": "deepen",
        },
    )
    graph.add_edge("deepen", END)
    return graph


def compile_graph(*, checkpointer: Any | None = None):
    graph = build_graph_uncompiled()
    if checkpointer is None:
        return graph.compile()
    return graph.compile(checkpointer=checkpointer)
