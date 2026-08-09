from __future__ import annotations

from core.tiers import (
    DEEP_SEARCHES_PER_RUN_BUDGET,
    QUICK_SEARCHES_PER_RUN_BUDGET,
    TIER_PROFILES,
    resolve_tier_profile,
    searches_per_run_estimate,
)

# Demand grows from debug -> quick -> deep; every per-run knob must be
# non-decreasing so a deeper tier never recalls less than a shallower one.
_NON_DECREASING_FIELDS = (
    "max_competitors",
    "max_dimensions",
    "search_max_results",
    "react_turns",
    "search_attempts_per_dim",
)


def test_recall_budget_constants_match_industry_anchors() -> None:
    quick = resolve_tier_profile("quick")
    deep = resolve_tier_profile("deep")
    assert quick.search_attempts_per_dim == 2
    assert quick.react_turns == 8
    assert deep.search_attempts_per_dim == 3
    assert deep.react_turns == 12


def test_searches_per_run_estimate_stays_under_documented_budget() -> None:
    # 8 competitors x 3 dimensions x 2 searches = 48 <= 60.
    assert searches_per_run_estimate(resolve_tier_profile("quick")) == 48
    assert searches_per_run_estimate(resolve_tier_profile("quick")) <= QUICK_SEARCHES_PER_RUN_BUDGET
    # 8 competitors x 5 dimensions x 3 searches = 120 <= 130.
    assert searches_per_run_estimate(resolve_tier_profile("deep")) == 120
    assert searches_per_run_estimate(resolve_tier_profile("deep")) <= DEEP_SEARCHES_PER_RUN_BUDGET


def test_tier_fields_are_non_decreasing_across_depth() -> None:
    debug = TIER_PROFILES["debug"]
    quick = TIER_PROFILES["quick"]
    deep = TIER_PROFILES["deep"]
    for field in _NON_DECREASING_FIELDS:
        debug_value = getattr(debug, field)
        quick_value = getattr(quick, field)
        deep_value = getattr(deep, field)
        assert debug_value <= quick_value <= deep_value, field
