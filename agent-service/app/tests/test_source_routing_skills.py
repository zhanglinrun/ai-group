from __future__ import annotations

from pathlib import Path

from service.skill_store import SkillStore


def test_source_routing_skills_exist_and_have_payloads() -> None:
    skills_root = Path(__file__).resolve().parents[2] / "skills"
    store = SkillStore(skills_root)
    names = store.list_by_applies_to("source_routing")

    assert names
    assert "pricing-official-priority" in names
    assert "feature-docs-priority" in names
    assert "user-feedback-public-review-priority" in names

    for name in names:
        parsed = store.load(name)
        assert parsed is not None
        assert "Routing Payload" in parsed.content
        assert "source_type:" in parsed.content
        assert "priority_delta:" in parsed.content
