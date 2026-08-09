from __future__ import annotations

from pathlib import Path

import pytest

import service.skill_store as skill_store_module
from agents.tools.skill_tools import LoadSkillChannel, ReadSkillFileChannel
from service.skill_store import SkillStore


def _prepare_store(tmp_path: Path) -> SkillStore:
    skill_dir = tmp_path / "qa_rule" / "rule_fresh_pricing"
    skill_dir.mkdir(parents=True, exist_ok=True)
    (skill_dir / "SKILL.md").write_text(
        (
            "---\n"
            "name: rule_fresh_pricing\n"
            "description: pricing recency rule\n"
            "version: 1.0.0\n"
            "tags:\n"
            "- generic\n"
            "applies_to: qa_rule\n"
            "---\n\n"
            "## Rule DSL\n\n```yaml\nid: rule_fresh_pricing\n```\n"
        ),
        encoding="utf-8",
    )
    (skill_dir / "example.md").write_text("example", encoding="utf-8")
    store = SkillStore(tmp_path)
    store.scan()
    return store


@pytest.mark.asyncio
async def test_load_skill_channel_returns_skill_payload(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    store = _prepare_store(tmp_path)
    monkeypatch.setattr(skill_store_module, "_skill_store", store)

    channel = LoadSkillChannel()
    observation = await channel.invoke(skill_id="rule_fresh_pricing")

    assert observation.channel == "load_skill"
    assert observation.result.metadata["skill_id"] == "rule_fresh_pricing"
    assert "Rule DSL" in str(observation.result.metadata.get("instructions"))
    assert observation.result.metadata["available_files"] == ["example.md"]


@pytest.mark.asyncio
async def test_read_skill_file_channel_reads_supporting_file(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    store = _prepare_store(tmp_path)
    monkeypatch.setattr(skill_store_module, "_skill_store", store)

    channel = ReadSkillFileChannel()
    observation = await channel.invoke(skill_id="rule_fresh_pricing", filename="example.md")

    assert observation.channel == "read_skill_file"
    assert observation.result.metadata["content"] == "example"


@pytest.mark.asyncio
async def test_read_skill_file_channel_returns_error_for_missing_file(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    store = _prepare_store(tmp_path)
    monkeypatch.setattr(skill_store_module, "_skill_store", store)

    channel = ReadSkillFileChannel()
    observation = await channel.invoke(skill_id="rule_fresh_pricing", filename="missing.md")

    assert "error" in observation.result.metadata
