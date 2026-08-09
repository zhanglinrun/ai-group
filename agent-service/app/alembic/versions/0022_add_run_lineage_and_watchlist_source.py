"""add run lineage and watchlist source columns

Revision ID: 0022_run_lineage_watchlist
Revises: 0021_knowledge_missing_reasons
Create Date: 2026-06-17 23:30:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0022_run_lineage_watchlist"
down_revision: str | None = "0021_knowledge_missing_reasons"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("runs", sa.Column("parent_run_id", sa.String(length=64), nullable=True))
    op.add_column(
        "runs",
        sa.Column(
            "seed_competitor_ids",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=True,
        ),
    )
    op.create_index(op.f("ix_runs_parent_run_id"), "runs", ["parent_run_id"], unique=False)

    op.add_column("watchlist", sa.Column("added_from_run_id", sa.String(length=64), nullable=True))
    op.add_column("watchlist", sa.Column("source_role", sa.String(length=64), nullable=True))
    op.create_index(
        op.f("ix_watchlist_added_from_run_id"),
        "watchlist",
        ["added_from_run_id"],
        unique=False,
    )


def downgrade() -> None:
    op.drop_index(op.f("ix_watchlist_added_from_run_id"), table_name="watchlist")
    op.drop_column("watchlist", "source_role")
    op.drop_column("watchlist", "added_from_run_id")

    op.drop_index(op.f("ix_runs_parent_run_id"), table_name="runs")
    op.drop_column("runs", "seed_competitor_ids")
    op.drop_column("runs", "parent_run_id")
