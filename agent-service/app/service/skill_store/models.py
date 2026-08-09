from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


@dataclass(slots=True)
class SkillMetadata:
    """Lightweight metadata parsed from SKILL.md frontmatter."""

    name: str
    description: str
    version: str = "1.0.0"
    tags: list[str] = field(default_factory=list)
    applies_to: str = "general"
    dependencies: list[str] = field(default_factory=list)
    path: Path | None = None


@dataclass(slots=True)
class ParsedSkill:
    """Full skill payload including markdown body."""

    metadata: SkillMetadata
    content: str
