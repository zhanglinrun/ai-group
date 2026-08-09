from __future__ import annotations

from typing import Literal, TypedDict


class PromotedArtifact(TypedDict):
    path: str
    action: Literal["created", "updated"]
    entry_id: str
