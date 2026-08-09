from __future__ import annotations

from service.collector.base import BaseChannel, CollectorObservation, ToolObservationResult
from service.collector.errors import ChannelError
from service.skill_store import get_skill_store


class LoadSkillChannel(BaseChannel):
    name = "load_skill"

    async def invoke(self, **kwargs: object) -> CollectorObservation:
        skill_id = kwargs.get("skill_id")
        if not isinstance(skill_id, str) or not skill_id.strip():
            raise ChannelError("load_skill requires non-empty skill_id.")
        normalized_skill_id = skill_id.strip()
        store = get_skill_store()
        parsed = store.load(normalized_skill_id)
        if parsed is None:
            return CollectorObservation(
                channel=self.name,
                args={"skill_id": normalized_skill_id},
                result=ToolObservationResult(
                    snippets=[],
                    metadata={
                        "error": f"Skill not found: {normalized_skill_id}",
                        "available_skills": store.get_skill_names(),
                    },
                ),
            )
        return CollectorObservation(
            channel=self.name,
            args={"skill_id": normalized_skill_id},
            result=ToolObservationResult(
                snippets=[],
                metadata={
                    "skill_id": normalized_skill_id,
                    "description": parsed.metadata.description,
                    "instructions": parsed.content,
                    "available_files": store.list_supporting_files(normalized_skill_id),
                    "tags": list(parsed.metadata.tags),
                    "applies_to": parsed.metadata.applies_to,
                },
            ),
        )


class ReadSkillFileChannel(BaseChannel):
    name = "read_skill_file"

    async def invoke(self, **kwargs: object) -> CollectorObservation:
        skill_id = kwargs.get("skill_id")
        filename = kwargs.get("filename")
        if not isinstance(skill_id, str) or not skill_id.strip():
            raise ChannelError("read_skill_file requires non-empty skill_id.")
        if not isinstance(filename, str) or not filename.strip():
            raise ChannelError("read_skill_file requires non-empty filename.")
        normalized_skill_id = skill_id.strip()
        normalized_filename = filename.strip()
        store = get_skill_store()
        try:
            content = store.read_supporting_file(normalized_skill_id, normalized_filename)
        except (FileNotFoundError, ValueError) as exc:
            return CollectorObservation(
                channel=self.name,
                args={"skill_id": normalized_skill_id, "filename": normalized_filename},
                result=ToolObservationResult(
                    snippets=[],
                    metadata={
                        "error": str(exc),
                        "available_files": store.list_supporting_files(normalized_skill_id),
                    },
                ),
            )
        return CollectorObservation(
            channel=self.name,
            args={"skill_id": normalized_skill_id, "filename": normalized_filename},
            result=ToolObservationResult(
                snippets=[],
                metadata={
                    "skill_id": normalized_skill_id,
                    "filename": normalized_filename,
                    "content": content,
                },
            ),
        )
