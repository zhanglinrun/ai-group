from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

from service.skill_store.models import ParsedSkill, SkillMetadata


def _split_frontmatter(content: str, *, path: Path) -> tuple[dict[str, Any], str]:
    lines = content.splitlines()
    if len(lines) < 3 or lines[0].strip() != "---":
        raise ValueError(f"SKILL.md missing YAML frontmatter: {path}")
    end_idx = -1
    for idx in range(1, len(lines)):
        if lines[idx].strip() == "---":
            end_idx = idx
            break
    if end_idx == -1:
        raise ValueError(f"SKILL.md frontmatter closing marker not found: {path}")

    frontmatter_raw = "\n".join(lines[1:end_idx])
    loaded = yaml.safe_load(frontmatter_raw)
    if not isinstance(loaded, dict):
        raise ValueError(f"SKILL.md frontmatter must be a mapping object: {path}")
    body = "\n".join(lines[end_idx + 1 :]).strip()
    return loaded, body


def _normalize_str_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item.strip() for item in value if isinstance(item, str) and item.strip()]


def _parse_metadata(frontmatter: dict[str, Any], *, path: Path) -> SkillMetadata:
    name_raw = frontmatter.get("name")
    description_raw = frontmatter.get("description")
    version_raw = frontmatter.get("version")
    applies_to_raw = frontmatter.get("applies_to")
    name = name_raw.strip() if isinstance(name_raw, str) and name_raw.strip() else path.parent.name
    description = (
        description_raw.strip()
        if isinstance(description_raw, str) and description_raw.strip()
        else "No description provided."
    )
    version = version_raw.strip() if isinstance(version_raw, str) and version_raw.strip() else "1.0.0"
    applies_to = (
        applies_to_raw.strip()
        if isinstance(applies_to_raw, str) and applies_to_raw.strip()
        else "general"
    )
    return SkillMetadata(
        name=name,
        description=description,
        version=version,
        tags=_normalize_str_list(frontmatter.get("tags")),
        applies_to=applies_to,
        dependencies=_normalize_str_list(frontmatter.get("dependencies")),
        path=path,
    )


def parse_metadata_only(path: Path) -> SkillMetadata:
    content = path.read_text(encoding="utf-8")
    frontmatter, _ = _split_frontmatter(content, path=path)
    return _parse_metadata(frontmatter, path=path)


def parse_skill_file(path: Path) -> ParsedSkill:
    content = path.read_text(encoding="utf-8")
    frontmatter, body = _split_frontmatter(content, path=path)
    return ParsedSkill(
        metadata=_parse_metadata(frontmatter, path=path),
        content=body,
    )
