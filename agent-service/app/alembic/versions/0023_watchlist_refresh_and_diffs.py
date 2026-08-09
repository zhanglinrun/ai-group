"""add watchlist refresh fields and competitor_diffs table

Revision ID: 0023_watchlist_refresh_and_diffs
Revises: 0022_run_lineage_watchlist
Create Date: 2026-06-18 10:00:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "0023_watchlist_refresh_and_diffs"
down_revision: str | None = "0022_run_lineage_watchlist"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("watchlist", sa.Column("last_refreshed_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("watchlist", sa.Column("refresh_interval_hours", sa.Integer(), nullable=True))
    op.add_column("watchlist", sa.Column("last_run_id", sa.String(length=64), nullable=True))

    op.create_table(
        "competitor_diffs",
        sa.Column("diff_id", sa.String(length=64), nullable=False),
        sa.Column("competitor_id", sa.String(length=128), nullable=False),
        sa.Column("run_id_new", sa.String(length=64), nullable=False),
        sa.Column("run_id_old", sa.String(length=64), nullable=False),
        sa.Column("dimension", sa.String(length=64), nullable=False),
        sa.Column("change_type", sa.String(length=32), nullable=False),
        sa.Column("old_value", sa.dialects.postgresql.JSONB(), nullable=True),
        sa.Column("new_value", sa.dialects.postgresql.JSONB(), nullable=True),
        sa.Column("significance", sa.String(length=16), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.PrimaryKeyConstraint("diff_id", name=op.f("pk_competitor_diffs")),
    )
    op.create_index(op.f("ix_competitor_diffs_competitor_id"), "competitor_diffs", ["competitor_id"])
    op.create_index(op.f("ix_competitor_diffs_run_id_new"), "competitor_diffs", ["run_id_new"])


def downgrade() -> None:
    op.drop_index(op.f("ix_competitor_diffs_run_id_new"), table_name="competitor_diffs")
    op.drop_index(op.f("ix_competitor_diffs_competitor_id"), table_name="competitor_diffs")
    op.drop_table("competitor_diffs")

    op.drop_column("watchlist", "last_run_id")
    op.drop_column("watchlist", "refresh_interval_hours")
    op.drop_column("watchlist", "last_refreshed_at")
