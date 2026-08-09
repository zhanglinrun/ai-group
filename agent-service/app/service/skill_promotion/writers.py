from __future__ import annotations

import os
import tempfile
from pathlib import Path
from typing import Any, Literal

import yaml


class PromotionWriteError(RuntimeError):
    pass


def _atomic_write_text(*, path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=path.parent,
            delete=False,
        ) as tmp_file:
            tmp_file.write(content)
            tmp_file.flush()
            os.fsync(tmp_file.fileno())
            tmp_path = tmp_file.name
        os.replace(tmp_path, path)
    except OSError as exc:
        raise PromotionWriteError(f"Failed to atomically write {path}") from exc
    finally:
        if tmp_path is not None and os.path.exists(tmp_path):
            os.unlink(tmp_path)


def _render_frontmatter(frontmatter: dict[str, Any]) -> str:
    frontmatter_yaml = yaml.safe_dump(frontmatter, sort_keys=False, allow_unicode=True).strip()
    return f"---\n{frontmatter_yaml}\n---\n"


def write_skill_markdown(
    *,
    path: Path,
    frontmatter: dict[str, Any],
    body_markdown: str,
) -> Literal["created", "updated"]:
    action: Literal["created", "updated"] = "updated" if path.exists() else "created"
    content = f"{_render_frontmatter(frontmatter)}\n{body_markdown.strip()}\n"
    _atomic_write_text(path=path, content=content)
    return action
