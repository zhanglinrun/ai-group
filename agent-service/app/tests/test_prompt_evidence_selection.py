from __future__ import annotations

import json
import re

from service.llm.prompts import (
    ANALYST_EVIDENCE_BRIEF_PROMPT_LIMIT,
    ANALYST_SYSTEM_PROMPT,
    EVIDENCE_BRIEF_PROMPT_LIMIT,
    QA_SEMANTIC_SYSTEM_PROMPT,
    build_analyst_user_prompt,
    build_qa_semantic_user_prompt,
    select_layered_evidence_briefs,
)


def test_select_layered_evidence_briefs_covers_competitor_dimension_groups() -> None:
    evidence_briefs: list[dict[str, object]] = []
    for index in range(30):
        evidence_briefs.append(
            {
                "evidence_id": f"old_{index}",
                "competitor_id": "Cursor",
                "dimension": "pricing",
            }
        )
    evidence_briefs.extend(
        [
            {
                "evidence_id": "windsurf_pricing",
                "competitor_id": "Windsurf",
                "dimension": "pricing",
            },
            {
                "evidence_id": "cursor_security",
                "competitor_id": "Cursor",
                "dimension": "security",
            },
            {
                "evidence_id": "windsurf_security",
                "competitor_id": "Windsurf",
                "dimension": "security",
            },
        ]
    )

    selected = select_layered_evidence_briefs(evidence_briefs, limit=4)

    selected_groups = {
        (item.get("competitor_id"), item.get("dimension"))
        for item in selected
    }
    assert len(selected) == 4
    assert selected_groups == {
        ("Cursor", "pricing"),
        ("Windsurf", "pricing"),
        ("Cursor", "security"),
        ("Windsurf", "security"),
    }


def test_select_layered_evidence_briefs_prefers_official_within_group() -> None:
    # Same (competitor, dimension) group: an official source must win over a newer
    # third-party one so buyer-critical claims surface vendor evidence (R10).
    evidence_briefs = [
        {
            "evidence_id": "official_pricing",
            "competitor_id": "Cursor",
            "dimension": "pricing",
            "source_authority": "official",
        },
        {
            "evidence_id": "third_party_pricing_newer",
            "competitor_id": "Cursor",
            "dimension": "pricing",
            "source_authority": "third_party",
        },
    ]

    selected = select_layered_evidence_briefs(evidence_briefs, limit=1)

    assert [item["evidence_id"] for item in selected] == ["official_pricing"]


def test_select_layered_evidence_briefs_fills_remaining_with_newest() -> None:
    evidence_briefs = [
        {"evidence_id": f"ev_{index}", "competitor_id": "Cursor", "dimension": "pricing"}
        for index in range(10)
    ]

    selected = select_layered_evidence_briefs(evidence_briefs, limit=3)

    assert [item["evidence_id"] for item in selected] == ["ev_7", "ev_8", "ev_9"]


def test_analyst_prompt_requires_per_dimension_insights() -> None:
    assert "at least one insight per focus dimension" in ANALYST_SYSTEM_PROMPT
    assert "Write all analysis output in response_language" in ANALYST_SYSTEM_PROMPT

    prompt = build_analyst_user_prompt(
        user_query="compare AI coding tools",
        competitors=["Cursor", "Windsurf"],
        focus_dimensions=["pricing", "security"],
        evidence_briefs=[
            {
                "evidence_id": "ev_pricing",
                "competitor_id": "Cursor",
                "dimension": "pricing",
            }
        ],
        analysis_intent="Compare enterprise pricing and security posture.",
        market_scope="Global enterprise buyers",
        response_language="en",
    )

    assert "For each focus dimension that has grounded evidence" in prompt
    assert "- analysis_intent: Compare enterprise pricing and security posture." in prompt
    assert "- market_scope: Global enterprise buyers" in prompt
    assert "- response_language: en" in prompt


def test_analyst_prompt_uses_larger_evidence_budget() -> None:
    assert ANALYST_EVIDENCE_BRIEF_PROMPT_LIMIT > EVIDENCE_BRIEF_PROMPT_LIMIT
    evidence_briefs = [
        {
            "evidence_id": f"ev_{index}",
            "competitor_id": f"competitor_{index}",
            "dimension": f"dimension_{index}",
        }
        for index in range(ANALYST_EVIDENCE_BRIEF_PROMPT_LIMIT + 5)
    ]

    prompt = build_analyst_user_prompt(
        user_query="compare many competitors",
        competitors=["Cursor", "Windsurf"],
        focus_dimensions=["pricing"],
        evidence_briefs=evidence_briefs,
    )
    match = re.search(r"- evidence_briefs: (.+)\n\nProduce", prompt)
    assert match is not None
    selected = json.loads(match.group(1))

    assert len(selected) == ANALYST_EVIDENCE_BRIEF_PROMPT_LIMIT


def test_qa_semantic_prompt_includes_numeric_claims_contract() -> None:
    assert "unsupported_numeric_claims" in QA_SEMANTIC_SYSTEM_PROMPT
    prompt = build_qa_semantic_user_prompt(
        report_markdown="Report says efficiency improved 28%.",
        report_json={"sections": []},
        failed_rule_ids=[],
        evidence_briefs=[],
        numeric_claims=[
            {
                "section_id": "efficiency",
                "claim": "Report says efficiency improved 28%.",
                "numbers": ["28%"],
                "evidence_ids": ["ev_001"],
                "evidence_quotes": [{"evidence_id": "ev_001", "quote_preview": "No number."}],
            }
        ],
    )

    assert "- numeric_claims:" in prompt
    assert "Report says efficiency improved 28%." in prompt
