from __future__ import annotations

import asyncio

from agents.subgraphs.researcher import llm_decide


def test_llm_decide_short_circuits_after_search_provider_degraded() -> None:
    state = {
        "competitor_id": "BioLAMR",
        "search_provider_degraded": True,
        "search_provider_degraded_reason": "Tavily usage limit exceeded",
        "turn_count": 1,
        "max_turns": 8,
        "pending_dimensions": ["methods", "datasets"],
    }

    result = asyncio.run(llm_decide(state))

    assert result["next_action"] == "finalize"
    assert "usage limit" in str(result["pending_action_args"]["reason"])
