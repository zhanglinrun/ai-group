from __future__ import annotations

import pytest

from service.skill_curator.generators.prompt_template import (
    generate_prompt_template_candidates,
)
from service.skill_curator.generators.qa_rule import generate_qa_rule_candidates
from service.skill_curator.generators.source_routing import (
    generate_source_routing_candidates,
)


@pytest.mark.asyncio
async def test_generate_qa_rule_candidates() -> None:
    result = await generate_qa_rule_candidates(
        run_id="run_test_curator_qa",
        domain_hint="ai coding assistants",
        qa_rejection_count=1,
        qa_reasons=["missing pricing source"],
        supervisor_decisions=[],
        evidence_source_counts={"pricing_page": 2},
        total_evidence_count=2,
    )
    assert result.error is None
    assert result.candidates
    assert all(item.candidate_type == "qa_rule" for item in result.candidates)


@pytest.mark.asyncio
async def test_generate_prompt_template_candidates() -> None:
    result = await generate_prompt_template_candidates(
        run_id="run_test_curator_prompt",
        domain_hint="ai coding assistants",
        qa_rejection_count=0,
        qa_reasons=[],
        supervisor_decisions=[],
        evidence_source_counts={"official_doc": 1},
        total_evidence_count=1,
    )
    assert result.error is None
    assert result.candidates
    assert all(item.candidate_type == "prompt_template" for item in result.candidates)


@pytest.mark.asyncio
async def test_generate_source_routing_candidates() -> None:
    result = await generate_source_routing_candidates(
        run_id="run_test_curator_source",
        domain_hint="ai coding assistants",
        qa_rejection_count=0,
        qa_reasons=[],
        supervisor_decisions=[],
        evidence_source_counts={"pricing_page": 3},
        total_evidence_count=3,
    )
    assert result.error is None
    assert result.candidates
    assert all(item.candidate_type == "source_routing" for item in result.candidates)

