from __future__ import annotations

from pathlib import Path

from service.skill_promotion.writers import write_skill_markdown


def test_write_skill_markdown_creates_file(tmp_path: Path) -> None:
    path = tmp_path / "skills" / "qa_rule" / "rule_1" / "SKILL.md"
    action = write_skill_markdown(
        path=path,
        frontmatter={
            "name": "rule_1",
            "description": "test skill",
            "tags": ["generic"],
            "applies_to": "qa_rule",
        },
        body_markdown="## Rule DSL\n\n```yaml\nid: rule_1\n```",
    )

    assert action == "created"
    content = path.read_text(encoding="utf-8")
    assert content.startswith("---\n")
    assert "name: rule_1" in content
    assert "applies_to: qa_rule" in content
    assert "id: rule_1" in content


def test_write_skill_markdown_updates_existing_file(tmp_path: Path) -> None:
    path = tmp_path / "skills" / "prompt_template" / "tmpl_1" / "SKILL.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("---\nname: old\n---\n\nold", encoding="utf-8")

    action = write_skill_markdown(
        path=path,
        frontmatter={
            "name": "tmpl_1",
            "description": "updated skill",
            "tags": ["source_docs"],
            "applies_to": "prompt_template",
        },
        body_markdown="## Template Body\n\n```text\nhello\n```",
    )

    assert action == "updated"
    content = path.read_text(encoding="utf-8")
    assert "name: tmpl_1" in content
    assert "old" not in content
