"""add watchlist table

Revision ID: 0011_add_watchlist
Revises: 0010_remove_industry_pack
Create Date: 2026-05-30 20:20:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

# revision identifiers, used by Alembic.
revision: str = "0011_add_watchlist"
down_revision: str | None = "0010_remove_industry_pack"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "watchlist",
        sa.Column("watch_id", sa.String(length=64), nullable=False),
        sa.Column("competitor_id", sa.String(length=128), nullable=False),
        sa.Column("note", sa.Text(), nullable=True),
        sa.Column("next_refresh_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.PrimaryKeyConstraint("watch_id", name=op.f("pk_watchlist")),
    )
    op.create_index(op.f("ix_watchlist_competitor_id"), "watchlist", ["competitor_id"], unique=True)


def downgrade() -> None:
    op.drop_index(op.f("ix_watchlist_competitor_id"), table_name="watchlist")
    op.drop_table("watchlist")
