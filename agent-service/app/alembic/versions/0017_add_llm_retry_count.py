"""add llm retry count

Revision ID: 0017_add_llm_retry_count
Revises: 0016_add_llm_trace_fields
Create Date: 2026-06-06 20:40:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "0017_add_llm_retry_count"
down_revision: str | None = "0016_add_llm_trace_fields"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "llm_calls",
        sa.Column("retry_count", sa.Integer(), server_default="0", nullable=False),
    )


def downgrade() -> None:
    op.drop_column("llm_calls", "retry_count")
