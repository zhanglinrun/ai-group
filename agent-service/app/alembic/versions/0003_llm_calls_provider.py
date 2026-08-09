"""add provider column to llm_calls

Revision ID: 0003_llm_calls_provider
Revises: 0002_step_rejection_reason
Create Date: 2026-05-23 20:55:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

# revision identifiers, used by Alembic.
revision: str = "0003_llm_calls_provider"
down_revision: str | None = "0002_step_rejection_reason"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("llm_calls", sa.Column("provider", sa.String(length=32), nullable=True))
    op.create_index(op.f("ix_llm_calls_provider"), "llm_calls", ["provider"], unique=False)


def downgrade() -> None:
    op.drop_index(op.f("ix_llm_calls_provider"), table_name="llm_calls")
    op.drop_column("llm_calls", "provider")
