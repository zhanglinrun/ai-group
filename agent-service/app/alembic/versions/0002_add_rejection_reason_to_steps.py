"""add rejection_reason to steps

Revision ID: 0002_step_rejection_reason
Revises: 0001_initial_schema
Create Date: 2026-05-23 19:00:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision: str = "0002_step_rejection_reason"
down_revision: str | None = "0001_initial_schema"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "steps",
        sa.Column(
            "rejection_reason",
            postgresql.JSONB(astext_type=sa.Text(), none_as_null=True),
            nullable=True,
        ),
    )
    op.create_index(
        "ix_steps_rejection_reason_gin",
        "steps",
        ["rejection_reason"],
        unique=False,
        postgresql_using="gin",
    )


def downgrade() -> None:
    op.drop_index("ix_steps_rejection_reason_gin", table_name="steps")
    op.drop_column("steps", "rejection_reason")
