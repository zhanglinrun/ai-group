from __future__ import annotations

import os
from datetime import datetime, timezone
from pathlib import Path

import pytest

from models.skill_candidate import SkillCandidateRecord
from service.skill_store import SkillStore
import service.skill_promotion as skill_promotion


def _write_skill(
    *,
    base_dir: Path,
    applies_to: str,
    skill_name: str,
    body_markdown: str,
) -> Path:
    skill_dir = base_dir / applies_to / skill_name
    skill_dir.mkdir(parents=True, exist_ok=True)
    path = skill_dir / "SKILL.md"
    path.write_text(
        (
            "---\n"
            f"name: {skill_name}\n"
            "description: test skill\n"
            "version: 1.0.0\n"
            "tags:\n"
            "- generic\n"
            f"applies_to: {applies_to}\n"
            "---\n\n"
            f"{body_markdown}\n"
        ),
        encoding="utf-8",
    )
    return path


def test_skill_store_scan_and_query(tmp_path: Path) -> None:
    _write_skill(
        base_dir=tmp_path,
        applies_to="qa_rule",
        skill_name="rule_pricing_recent",
        body_markdown="## Rule\n\n```yaml\nid: rule_pricing_recent\n```",
    )
    _write_skill(
        base_dir=tmp_path,
        applies_to="prompt_template",
        skill_name="writer_summary_template",
        body_markdown="## Template\n\n```text\nsummary body\n```",
    )
    supporting_file = tmp_path / "qa_rule" / "rule_pricing_recent" / "examples.md"
    supporting_file.write_text("# examples", encoding="utf-8")

    store = SkillStore(tmp_path)
    metadata_map = store.scan()

    assert sorted(metadata_map.keys()) == ["rule_pricing_recent", "writer_summary_template"]
    assert store.list_by_applies_to("qa_rule") == ["rule_pricing_recent"]
    assert store.list_by_tag("generic") == ["rule_pricing_recent", "writer_summary_template"]
    assert store.list_supporting_files("rule_pricing_recent") == ["examples.md"]


def test_skill_store_read_supporting_file(tmp_path: Path) -> None:
    _write_skill(
        base_dir=tmp_path,
        applies_to="source_routing",
        skill_name="prefer_docs_source",
        body_markdown="## Routing\n\n```yaml\nsource_type: docs\n```",
    )
    note_path = tmp_path / "source_routing" / "prefer_docs_source" / "note.txt"
    note_path.write_text("hello", encoding="utf-8")

    store = SkillStore(tmp_path)
    store.scan()

    assert store.read_supporting_file("prefer_docs_source", "note.txt") == "hello"


def test_skill_store_rescans_only_when_mtime_changes(tmp_path: Path) -> None:
    _write_skill(
        base_dir=tmp_path,
        applies_to="qa_rule",
        skill_name="rule_once",
        body_markdown="## Rule\n",
    )
    store = SkillStore(tmp_path)
    store.scan()
    first_count = len(store.get_skill_names())
    store.get_metadata("rule_once")
    store.load("rule_once")
    store.list_by_applies_to("qa_rule")
    assert len(store.get_skill_names()) == first_count

    _write_skill(
        base_dir=tmp_path,
        applies_to="qa_rule",
        skill_name="rule_twice",
        body_markdown="## Rule 2\n",
    )
    store.invalidate()
    store.scan()
    assert len(store.get_skill_names()) == first_count + 1


def test_skill_store_detects_new_skill_when_directory_mtime_is_unchanged(tmp_path: Path) -> None:
    store = SkillStore(tmp_path)
    assert store.scan() == {}
    original_mtime = tmp_path.stat().st_mtime

    skill_path = _write_skill(
        base_dir=tmp_path,
        applies_to="qa_rule",
        skill_name="rule_same_tick",
        body_markdown="## Rule\n\n```yaml\nid: rule_same_tick\n```",
    )
    for path in [
        skill_path,
        skill_path.parent,
        skill_path.parent.parent,
        tmp_path,
    ]:
        os.utime(path, (original_mtime, original_mtime))

    assert store.get_skill_names() == ["rule_same_tick"]


class _SpySkillStore(SkillStore):
    def __init__(self, skills_dir: Path) -> None:
        super().__init__(skills_dir)
        self.invalidate_count = 0

    def invalidate(self) -> None:
        self.invalidate_count += 1
        super().invalidate()


def test_promote_approved_candidate_invalidates_skill_store(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    store = _SpySkillStore(tmp_path)
    store.scan()
    monkeypatch.setattr(skill_promotion, "get_skill_store", lambda: store)
    record = SkillCandidateRecord(
        id="skill_candidate_cache_test",
        candidate_type="qa_rule",
        applies_to="qa_rule",
        tags=["pricing", "quality"],
        payload={
            "rule_yaml": (
                "id: rule_promoted_cache_test\n"
                "when:\n"
                "  section_id_in: [pricing]\n"
                "require:\n"
                "  evidence_refs_count_gte: 1\n"
            ),
            "triggered_failures_count": 1,
            "similar_existing_rules": [],
        },
        rationale="cache invalidation regression",
        supporting_run_ids=["run_cache_test"],
        confidence="medium",
        status="staging",
    )

    artifacts = skill_promotion.promote_approved_candidate(
        record=record,
        skills_root=tmp_path,
        reviewed_by="owner_wh",
        reviewed_at=datetime.now(timezone.utc),
    )

    assert store.invalidate_count == 1
    entry_id = str(artifacts[0]["entry_id"])
    assert entry_id
    assert store.get_metadata(entry_id) is not None
