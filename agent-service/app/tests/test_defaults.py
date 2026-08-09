from __future__ import annotations

from core import defaults
from core.tiers import resolve_tier_profile


def test_s4a_business_caps_remain_at_legacy_values() -> None:
    assert defaults.MAX_SUPERVISOR_ITERATIONS == 10
    # This is the legacy schema/default cap. Runtime researcher budgets are tiered
    # below, so this should not be read as the live quick/deep ReAct turn budget.
    assert defaults.MAX_REACT_TURNS == 6
    assert defaults.MAX_ADDITIONAL_PLAN_TASKS == 5
    assert defaults.MAX_FOCUS_DIMENSIONS == 5
    assert defaults.MAX_QA_RERESEARCH_ITERATIONS == 3

    assert defaults.MAX_DISCOVERY_SEARCH_QUERIES == 5
    assert defaults.DISCOVERY_SEARCH_MAX_RESULTS_CAP == 10
    assert defaults.DISCOVERY_SNIPPETS_TO_EXTRACT == 20
    assert defaults.DEFAULT_DISCOVER_MAX_RESULTS == 8

    assert defaults.PLAN_TASK_TITLE_MAX_LEN == 60
    assert defaults.PLAN_TASK_DESCRIPTION_MAX_LEN == 500


def test_runtime_tiers_extend_legacy_react_turn_default() -> None:
    assert resolve_tier_profile("quick").react_turns == 8
    assert resolve_tier_profile("deep").react_turns == 12
    assert resolve_tier_profile("quick").react_turns > defaults.MAX_REACT_TURNS
