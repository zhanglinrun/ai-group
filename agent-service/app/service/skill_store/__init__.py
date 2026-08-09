from __future__ import annotations

from pathlib import Path

from service.skill_store.models import ParsedSkill, SkillMetadata
from service.skill_store.store import SkillStore


def _default_skills_root() -> Path:
    return Path(__file__).resolve().parents[3] / "skills"


_skill_store = SkillStore(_default_skills_root())


def get_skill_store() -> SkillStore:
    return _skill_store


__all__ = [
    "ParsedSkill",
    "SkillMetadata",
    "SkillStore",
    "get_skill_store",
]
