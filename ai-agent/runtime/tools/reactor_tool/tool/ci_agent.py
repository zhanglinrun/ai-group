"""Legacy code-agent entry point retired in favor of explicit sandbox scripts."""

from __future__ import annotations


class CIAgent:
    def __init__(self, *args, **kwargs):
        raise RuntimeError("CIAgent was removed: provide explicit code to code_interpreter instead")
