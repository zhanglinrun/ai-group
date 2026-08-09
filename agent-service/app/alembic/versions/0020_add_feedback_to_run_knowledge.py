"""add feedback to run knowledge

Revision ID: 0020_add_knowledge_feedback
Revises: 0019_add_run_knowledge
Create Date: 2026-06-09 17:35:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0020_add_knowledge_feedback"
down_revision: str | None = "0019_add_run_knowledge"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "run_knowledge",
        sa.Column(
            "feedback",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
    )


def downgrade() -> None:
    op.drop_column("run_knowledge", "feedback")
