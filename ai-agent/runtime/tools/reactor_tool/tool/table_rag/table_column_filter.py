"""Deterministic pass-through filter; semantic selection belongs to Java."""

from __future__ import annotations


class ColumnFilterModule:
    async def filter_tables(self, tables, *args, **kwargs):
        return tables or []

    async def filter_columns(self, columns, *args, **kwargs):
        return columns or []
