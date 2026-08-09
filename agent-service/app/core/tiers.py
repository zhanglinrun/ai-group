from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from core.defaults import (
    MAX_FOCUS_DIMENSIONS,
    MAX_QA_RERESEARCH_ITERATIONS,
    MAX_RESEARCH_COMPETITORS,
    MAX_SUPERVISOR_ITERATIONS,
)

AnalysisTier = Literal["debug", "quick", "deep"]
ReportDepthSemantics = Literal["quick", "deep"]

_DEBUG_MAX_COMPETITORS = 2
_DEBUG_MAX_DIMENSIONS = 2
_DEBUG_SEARCH_MAX_RESULTS = 3
_DEBUG_REACT_TURNS = 2
_DEBUG_SEARCH_ATTEMPTS_PER_DIM = 1
_DEBUG_SUPERVISOR_ITERATIONS = 6
_DEBUG_QA_REJECT_BUDGET = 1
_DEBUG_REPLAN_BUDGET = 1
_DEBUG_LANDSCAPE_CORE_DEEPDIVE_N = 1

_QUICK_MAX_DIMENSIONS = 3
_QUICK_SEARCH_MAX_RESULTS = 5
# Anchored to ReAct global step budget (6-12); a single search+fetch per
# dimension used to exhaust the old budget of 3.
_QUICK_REACT_TURNS = 8
# Anchored to "2-3 searches per sub-agent" (GPT Researcher / Open Deep Research):
# never give up a dimension after a single search.
_QUICK_SEARCH_ATTEMPTS_PER_DIM = 2
_QUICK_QA_REJECT_BUDGET = 2
_QUICK_REPLAN_BUDGET = 2
_QUICK_LANDSCAPE_CORE_DEEPDIVE_N = 3

_DEEP_SEARCH_MAX_RESULTS = 8
_DEEP_REACT_TURNS = 12
_DEEP_SEARCH_ATTEMPTS_PER_DIM = 3
_DEEP_SUPERVISOR_ITERATIONS = 12
_DEEP_REPLAN_BUDGET = 4
_DEEP_LANDSCAPE_CORE_DEEPDIVE_N = 5

# Per-run worst-case search budget (searches_per_run_estimate must stay under it).
# Demand (competitors x dimensions x searches-per-dim) and supply (3 providers x
# COLLECTOR_PER_HOST_QPS, language-chain retry, provider cooldown) are two ends of
# one throughput equation: doubling a tier constant in isolation trips the
# invariant test in test_tiers instead of triggering a rate-limit cascade.
QUICK_SEARCHES_PER_RUN_BUDGET = 60
DEEP_SEARCHES_PER_RUN_BUDGET = 130


def _derive_recursion_limit(supervisor_max_iterations: int) -> int:
    return (supervisor_max_iterations * 4) + 10


@dataclass(frozen=True, slots=True)
class TierProfile:
    max_competitors: int
    max_dimensions: int
    search_max_results: int
    react_turns: int
    search_attempts_per_dim: int
    supervisor_max_iterations: int
    qa_reject_budget: int
    replan_budget: int
    landscape_core_deepdive_n: int
    recursion_limit: int
    report_depth_semantics: ReportDepthSemantics
    enable_deep_qa_rules: bool


def _build_profile(
    *,
    max_competitors: int,
    max_dimensions: int,
    search_max_results: int,
    react_turns: int,
    search_attempts_per_dim: int,
    supervisor_max_iterations: int,
    qa_reject_budget: int,
    replan_budget: int,
    landscape_core_deepdive_n: int,
    report_depth_semantics: ReportDepthSemantics,
    enable_deep_qa_rules: bool,
) -> TierProfile:
    return TierProfile(
        max_competitors=max_competitors,
        max_dimensions=max_dimensions,
        search_max_results=search_max_results,
        react_turns=react_turns,
        search_attempts_per_dim=search_attempts_per_dim,
        supervisor_max_iterations=supervisor_max_iterations,
        qa_reject_budget=qa_reject_budget,
        replan_budget=replan_budget,
        landscape_core_deepdive_n=landscape_core_deepdive_n,
        recursion_limit=_derive_recursion_limit(supervisor_max_iterations),
        report_depth_semantics=report_depth_semantics,
        enable_deep_qa_rules=enable_deep_qa_rules,
    )


def searches_per_run_estimate(profile: TierProfile) -> int:
    """Worst-case open-web searches a single run can issue under this tier."""
    return profile.max_competitors * profile.max_dimensions * profile.search_attempts_per_dim


TIER_PROFILES: dict[AnalysisTier, TierProfile] = {
    "debug": _build_profile(
        max_competitors=_DEBUG_MAX_COMPETITORS,
        max_dimensions=_DEBUG_MAX_DIMENSIONS,
        search_max_results=_DEBUG_SEARCH_MAX_RESULTS,
        react_turns=_DEBUG_REACT_TURNS,
        search_attempts_per_dim=_DEBUG_SEARCH_ATTEMPTS_PER_DIM,
        supervisor_max_iterations=_DEBUG_SUPERVISOR_ITERATIONS,
        qa_reject_budget=_DEBUG_QA_REJECT_BUDGET,
        replan_budget=_DEBUG_REPLAN_BUDGET,
        landscape_core_deepdive_n=_DEBUG_LANDSCAPE_CORE_DEEPDIVE_N,
        report_depth_semantics="quick",
        enable_deep_qa_rules=False,
    ),
    "quick": _build_profile(
        max_competitors=MAX_RESEARCH_COMPETITORS,
        max_dimensions=_QUICK_MAX_DIMENSIONS,
        search_max_results=_QUICK_SEARCH_MAX_RESULTS,
        react_turns=_QUICK_REACT_TURNS,
        search_attempts_per_dim=_QUICK_SEARCH_ATTEMPTS_PER_DIM,
        supervisor_max_iterations=MAX_SUPERVISOR_ITERATIONS,
        qa_reject_budget=_QUICK_QA_REJECT_BUDGET,
        replan_budget=_QUICK_REPLAN_BUDGET,
        landscape_core_deepdive_n=_QUICK_LANDSCAPE_CORE_DEEPDIVE_N,
        report_depth_semantics="quick",
        enable_deep_qa_rules=False,
    ),
    "deep": _build_profile(
        max_competitors=MAX_RESEARCH_COMPETITORS,
        max_dimensions=MAX_FOCUS_DIMENSIONS,
        search_max_results=_DEEP_SEARCH_MAX_RESULTS,
        react_turns=_DEEP_REACT_TURNS,
        search_attempts_per_dim=_DEEP_SEARCH_ATTEMPTS_PER_DIM,
        supervisor_max_iterations=_DEEP_SUPERVISOR_ITERATIONS,
        qa_reject_budget=MAX_QA_RERESEARCH_ITERATIONS,
        replan_budget=_DEEP_REPLAN_BUDGET,
        landscape_core_deepdive_n=_DEEP_LANDSCAPE_CORE_DEEPDIVE_N,
        report_depth_semantics="deep",
        enable_deep_qa_rules=True,
    ),
}


def normalize_analysis_tier(report_depth: str | None) -> AnalysisTier:
    if report_depth in TIER_PROFILES:
        return report_depth
    return "quick"


def resolve_tier_profile(report_depth: str | None) -> TierProfile:
    tier = normalize_analysis_tier(report_depth)
    return TIER_PROFILES[tier]
