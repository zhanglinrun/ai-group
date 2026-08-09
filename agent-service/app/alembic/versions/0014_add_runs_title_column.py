"""add title column to runs

Revision ID: 0014_add_runs_title_column
Revises: 0013_add_runs_follow_ups_column
Create Date: 2026-06-01 19:30:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "0014_add_runs_title_column"
down_revision: str | None = "0013_add_runs_follow_ups_column"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    # Short LLM-generated summary of user_query (5-15 chars typical). Nullable
    # so historical runs and the brief intake-only window before action=complete
    # don't violate the constraint; the FE falls back to truncating user_query
    # whenever this is null.
    op.add_column(
        "runs",
        sa.Column("title", sa.String(120), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("runs", "title")
