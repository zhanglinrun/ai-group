"""Stable local/container entrypoint for the Python Agent service."""

from __future__ import annotations

import sys
from pathlib import Path

APP_DIR = Path(__file__).resolve().parent / "app"
sys.path.insert(0, str(APP_DIR))

from app_main import app  # noqa: E402

__all__ = ["app"]
