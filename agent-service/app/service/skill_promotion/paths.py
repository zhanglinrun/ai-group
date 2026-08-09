from __future__ import annotations

from pathlib import Path
import re


_SEGMENT_PATTERN = re.compile(r"[^a-z0-9_\\-]+")


def _normalize_segment(raw: str, *, fallback: str) -> str:
    normalized = _SEGMENT_PATTERN.sub("_", raw.strip().lower()).strip("_")
    return normalized or fallback


def build_skill_path(*, skills_root: Path, applies_to: str, skill_id: str) -> Path:
    applies_to_segment = _normalize_segment(applies_to, fallback="general")
    skill_segment = _normalize_segment(skill_id, fallback="skill")
    return skills_root / applies_to_segment / skill_segment / "SKILL.md"
