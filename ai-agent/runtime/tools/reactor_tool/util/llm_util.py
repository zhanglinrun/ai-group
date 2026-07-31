"""Removed model gateway placeholder.

The Python runtime is a deterministic data plane. Model routing and inference
are implemented by the Java Agent Harness.
"""

from __future__ import annotations


class ModelExecutionMovedToJava(RuntimeError):
    pass
