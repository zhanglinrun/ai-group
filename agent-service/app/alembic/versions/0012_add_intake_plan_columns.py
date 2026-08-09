"""add intake_draft and plan_tree columns to runs

Revision ID: 0012_add_intake_plan_columns
Revises: 0011_add_watchlist
Create Date: 2026-05-31 18:00:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision: str = "0012_add_intake_plan_columns"
down_revision: str | None = "0011_add_watchlist"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    # Both columns are nullable so legacy POST /api/runs (no intake) stays a no-op.
    op.add_column(
        "runs",
        sa.Column(
            "intake_draft",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=True,
        ),
    )
    op.add_column(
        "runs",
        sa.Column(
            "plan_tree",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=True,
        ),
    )


def downgrade() -> None:
    op.drop_column("runs", "plan_tree")
    op.drop_column("runs", "intake_draft")
