"""Compatibility marker; response quality checks run in Java and never call a hidden model here."""

from __future__ import annotations


class FinalAnswerCheck:
    def __call__(self, value):
        return value
