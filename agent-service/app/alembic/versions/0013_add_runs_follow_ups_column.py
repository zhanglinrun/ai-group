"""add follow_ups column to runs

Revision ID: 0013_add_runs_follow_ups_column
Revises: 0012_add_intake_plan_columns
Create Date: 2026-05-31 21:30:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0013_add_runs_follow_ups_column"
down_revision: str | None = "0012_add_intake_plan_columns"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    # JSONB list of FollowUpEntry dicts. Nullable so legacy runs (created
    # before this migration) keep working without backfill.
    op.add_column(
        "runs",
        sa.Column(
            "follow_ups",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=True,
        ),
    )


def downgrade() -> None:
    op.drop_column("runs", "follow_ups")
