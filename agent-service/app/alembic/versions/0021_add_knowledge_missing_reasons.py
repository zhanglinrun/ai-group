"""add missing_reasons to run knowledge

Revision ID: 0021_knowledge_missing_reasons
Revises: 0020_add_knowledge_feedback
Create Date: 2026-06-09 18:10:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0021_knowledge_missing_reasons"
down_revision: str | None = "0020_add_knowledge_feedback"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "run_knowledge",
        sa.Column(
            "missing_reasons",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
    )


def downgrade() -> None:
    op.drop_column("run_knowledge", "missing_reasons")
