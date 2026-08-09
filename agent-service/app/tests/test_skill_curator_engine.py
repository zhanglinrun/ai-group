from __future__ import annotations

import pytest

from service.skill_curator.engine import generate_skill_candidates


@pytest.mark.asyncio
async def test_generate_skill_candidates_emits_only_consumed_qa_rules() -> None:
    result = await generate_skill_candidates(
        run_id="run_test_curator_dispatch",
        domain_hint="ai coding assistants",
        qa_rejection_count=1,
        qa_reasons=["pricing evidence missing"],
        supervisor_decisions=[],
        evidence_source_counts={"pricing_page": 2, "official_doc": 1},
        total_evidence_count=3,
    )
    assert result.error is None
    candidate_types = sorted(item.candidate_type for item in result.candidates)
    assert candidate_types == ["qa_rule"]

