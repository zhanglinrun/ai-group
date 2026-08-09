from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

from service.skill_store.models import ParsedSkill, SkillMetadata
from service.skill_store.parser import parse_metadata_only, parse_skill_file
from utils.logger import get_logger

log = get_logger("service.skill_store")

SkillDirSignature = tuple[float, tuple[tuple[str, float, int], ...]] | None


class SkillStore:
    def __init__(self, skills_dir: Path) -> None:
        self.skills_dir = skills_dir
        self._metadata_cache: dict[str, SkillMetadata] = {}
        self._content_cache: dict[str, ParsedSkill] = {}
        self._scanned = False
        self._skills_dir_signature: SkillDirSignature = None

    def _build_skills_dir_signature(self) -> SkillDirSignature:
        if not self.skills_dir.exists():
            return None
        dir_mtime = self.skills_dir.stat().st_mtime
        skill_entries: list[tuple[str, float, int]] = []
        for skill_file in self.skills_dir.rglob("SKILL.md"):
            try:
                stat = skill_file.stat()
            except OSError:
                continue
            skill_entries.append(
                (
                    skill_file.relative_to(self.skills_dir).as_posix(),
                    stat.st_mtime,
                    stat.st_size,
                )
            )
        return dir_mtime, tuple(sorted(skill_entries))

    def invalidate(self) -> None:
        self._metadata_cache.clear()
        self._content_cache.clear()
        self._scanned = False
        self._skills_dir_signature = None

    def _ensure_scanned(self) -> None:
        signature = self._build_skills_dir_signature()
        if self._scanned and signature == self._skills_dir_signature:
            return
        self._rescan(signature=signature)

    def _rescan(self, *, signature: SkillDirSignature) -> None:
        self._metadata_cache.clear()
        self._content_cache.clear()

        if not self.skills_dir.exists():
            log.info("skill_store.scan.skip", reason="skills_dir_not_found", skills_dir=str(self.skills_dir))
            self._scanned = True
            self._skills_dir_signature = signature
            return

        for skill_file in sorted(self.skills_dir.rglob("SKILL.md")):
            try:
                metadata = parse_metadata_only(skill_file)
            except (OSError, ValueError, yaml.YAMLError) as exc:
                log.warning("skill_store.scan.invalid_skill", path=str(skill_file), error=str(exc))
                continue
            skill_name = metadata.name
            if skill_name in self._metadata_cache:
                log.warning(
                    "skill_store.scan.duplicate_name",
                    name=skill_name,
                    old_path=str(self._metadata_cache[skill_name].path),
                    new_path=str(skill_file),
                )
                continue
            self._metadata_cache[skill_name] = metadata

        self._scanned = True
        self._skills_dir_signature = signature
        log.info(
            "skill_store.scan.finish",
            skill_count=len(self._metadata_cache),
            skills_dir=str(self.skills_dir),
        )

    def scan(self) -> dict[str, SkillMetadata]:
        self._ensure_scanned()
        return self._metadata_cache

    def get_skill_names(self) -> list[str]:
        self._ensure_scanned()
        return sorted(self._metadata_cache.keys())

    def get_metadata(self, skill_name: str) -> SkillMetadata | None:
        self._ensure_scanned()
        return self._metadata_cache.get(skill_name)

    def load(self, skill_name: str) -> ParsedSkill | None:
        self._ensure_scanned()
        if skill_name in self._content_cache:
            return self._content_cache[skill_name]

        metadata = self._metadata_cache.get(skill_name)
        if metadata is None or metadata.path is None:
            return None

        try:
            parsed = parse_skill_file(metadata.path)
        except (OSError, ValueError, yaml.YAMLError) as exc:
            log.warning("skill_store.load.invalid_skill", skill_name=skill_name, error=str(exc))
            return None
        self._content_cache[skill_name] = parsed
        return parsed

    def list_by_tag(self, tag: str) -> list[str]:
        self._ensure_scanned()
        target = tag.strip().lower()
        if not target:
            return []
        return sorted(
            name
            for name, metadata in self._metadata_cache.items()
            if any(item.lower() == target for item in metadata.tags)
        )

    def list_by_applies_to(self, applies_to: str) -> list[str]:
        self._ensure_scanned()
        target = applies_to.strip().lower()
        if not target:
            return []
        return sorted(
            name for name, metadata in self._metadata_cache.items() if metadata.applies_to.lower() == target
        )

    def list_supporting_files(self, skill_name: str) -> list[str]:
        self._ensure_scanned()
        metadata = self._metadata_cache.get(skill_name)
        if metadata is None or metadata.path is None:
            return []
        skill_dir = metadata.path.parent
        files: list[str] = []
        for path in sorted(skill_dir.rglob("*")):
            if not path.is_file() or path.name == "SKILL.md":
                continue
            files.append(path.relative_to(skill_dir).as_posix())
        return files

    def read_supporting_file(self, skill_name: str, filename: str) -> str:
        self._ensure_scanned()
        metadata = self._metadata_cache.get(skill_name)
        if metadata is None or metadata.path is None:
            raise FileNotFoundError(f"Skill not found: {skill_name}")
        skill_dir = metadata.path.parent.resolve()
        target_path = (skill_dir / filename).resolve()
        try:
            target_path.relative_to(skill_dir)
        except ValueError as exc:
            raise ValueError(f"Path escapes skill directory: {filename}") from exc
        if not target_path.is_file():
            raise FileNotFoundError(f"Supporting file not found: {filename}")
        return target_path.read_text(encoding="utf-8")
